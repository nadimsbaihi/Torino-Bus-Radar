#!/usr/bin/env python3
"""Create a compact, scheduled rail index from a Trenitalia GTFS feed.

Usage: build_train_index.py [--metro] SOURCE.zip OUTPUT.json [MINLAT,MINLON,MAXLAT,MAXLON]

With an area, only trains calling at two or more stations inside it are kept, and only those
stations: the app plans journeys within its map, so the rest of a national feed is dead weight.
Long-distance and bus services are left out; the trains are the ones a local ticket covers.

With --metro, the source is GTT's feed and only its metro is kept, under the category METRO and
the line's name as the number. Runs with the same times on different days become one run.
"""

import csv
import json
import re
import sys
import zipfile
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
# Categories of the national feed's routes that need a long-distance ticket, or are not trains.
LONG_DISTANCE = {"FRECCIAROSSA", "FRECCIARGENTO", "FRECCIABIANCA", "EUROCITY", "EURONIGHT",
                 "INTERCITY", "INTERCITYNOTTE", "AUTOBUS"}


def rows(archive, name):
    return csv.DictReader(line.decode("utf-8-sig") for line in archive.open(name))


def seconds(value):
    hour, minute, second = (int(part) for part in value.split(":"))
    return hour * 3600 + minute * 60 + second


def parse_date(value):
    return date(int(value[:4]), int(value[4:6]), int(value[6:]))


def service_dates(archive):
    """Dates each service runs: weekly calendar (if any), then added/removed exceptions."""
    names = set(archive.namelist())
    dates = defaultdict(set)
    if "calendar.txt" in names:
        for row in rows(archive, "calendar.txt"):
            day = parse_date(row["start_date"])
            end = parse_date(row["end_date"])
            while day <= end:
                if row[WEEKDAYS[day.weekday()]] == "1":
                    dates[row["service_id"]].add(day.strftime("%Y%m%d"))
                day += timedelta(days=1)
    if "calendar_dates.txt" in names:
        for row in rows(archive, "calendar_dates.txt"):
            if row["exception_type"] == "1":
                dates[row["service_id"]].add(row["date"])
            elif row["exception_type"] == "2":
                dates[row["service_id"]].discard(row["date"])
    return {service: sorted(days) for service, days in dates.items()}


# Station names the national feed abbreviates past what a rider would search for.
FULL_NAMES = {"Torino Reb.Foss.": "Torino Rebaudengo Fossata"}


PARTICLES = {"a", "al", "alla", "d", "da", "dal", "dalla", "de", "dei", "del", "della", "delle",
             "di", "e", "in", "sul", "sulla"}


def readable(name):
    """Station names in capitals ("VILLANOVA D'ASTI") as people write them ("Villanova d'Asti")."""
    if not name.isupper():
        return name
    words = []
    for index, word in enumerate(name.lower().split()):
        parts = re.split(r"(['’\-])", word)
        parts = [part if part in "'’-" or (part in PARTICLES and (index > 0 or i < len(parts) - 1))
                 else part[:1].upper() + part[1:] for i, part in enumerate(parts)]
        words.append("".join(parts))
    return " ".join(words)


def category_of(trip, route):
    """The train's category: the regional feed names it per trip, the national one per route."""
    category = (trip.get("train_category") or route.get("route_long_name") or "REGIONALE").strip()
    return category.upper() or "REGIONALE"


def main(source, output, area=None, metro=False):
    box = [float(part) for part in area.split(",")] if area else None

    def inside(stop):
        return box is None or (box[0] <= stop["lat"] <= box[2] and box[1] <= stop["lon"] <= box[3])

    with zipfile.ZipFile(source) as archive:
        names = set(archive.namelist())
        routes = ({row["route_id"]: row for row in rows(archive, "routes.txt")}
                  if "routes.txt" in names else {})
        stops = {
            row["stop_id"]: {
                "id": row["stop_id"],
                "name": readable(FULL_NAMES.get(row["stop_name"].strip(), row["stop_name"].strip())),
                "lat": round(float(row["stop_lat"]), 6),
                "lon": round(float(row["stop_lon"]), 6),
            }
            for row in rows(archive, "stops.txt")
            if row["stop_lat"] and row["stop_lon"]
        }
        trips = {row["trip_id"]: row for row in rows(archive, "trips.txt")}
        active_dates = service_dates(archive)

        times = defaultdict(list)
        for row in rows(archive, "stop_times.txt"):
            stop = stops.get(row["stop_id"])
            if stop:
                times[row["trip_id"]].append((
                    int(row["stop_sequence"]),
                    stop,
                    seconds(row["arrival_time"]),
                    seconds(row["departure_time"]),
                ))

        output_trips = []
        for trip_id, trip in trips.items():
            route = routes.get(trip.get("route_id"), {})
            if metro:
                if route.get("route_type") != "1":
                    continue
                category = "METRO"
            else:
                if route.get("route_type", "2") == "3":
                    continue
                category = category_of(trip, route)
                if category in LONG_DISTANCE:
                    continue
            all_times = sorted(times[trip_id], key=lambda entry: entry[0])
            stop_times = [entry for entry in all_times if inside(entry[1])]
            dates = active_dates.get(trip["service_id"])
            if len(stop_times) < 2 or not dates:
                continue
            # The national feed leaves the headsign out: a train is going to its last station.
            headsign = (trip.get("trip_headsign") or "").strip() or all_times[-1][1]["name"]
            if metro:
                # "METRO BENGASI": on the metro, every train is a metro train.
                headsign = readable(re.sub(r"^METRO\s+", "", headsign, flags=re.IGNORECASE))
            number = (route["route_short_name"].strip() if metro
                      else trip["trip_short_name"].lstrip("0") or trip["trip_short_name"])
            output_trips.append({
                "number": number,
                "category": category,
                "headsign": headsign,
                "dates": dates,
                "stops": [
                    {**stop, "arrival": arrival, "departure": departure}
                    for _, stop, arrival, departure in stop_times
                ],
            })

    index = {"version": 1, "trips": output_trips}
    if metro:
        index = compact(merge_same_runs(output_trips))
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(index, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(index['trips'])} {'metro' if metro else 'train'} trips to {output}")


def merge_same_runs(trips):
    """One run per timetable: a metro train at 07:04 on weekdays and on Saturdays is listed once."""
    merged = {}
    for trip in trips:
        key = (trip["number"], trip["headsign"],
               tuple((stop["id"], stop["arrival"], stop["departure"]) for stop in trip["stops"]))
        if key in merged:
            merged[key]["dates"] = sorted(set(merged[key]["dates"]) | set(trip["dates"]))
        else:
            merged[key] = trip
    return list(merged.values())


def compact(trips):
    """Version 2: each station once, and each run as station numbers with arrival, departure pairs."""
    stations, numbers = [], {}
    for trip in trips:
        for stop in trip["stops"]:
            if stop["id"] not in numbers:
                numbers[stop["id"]] = len(stations)
                stations.append({key: stop[key] for key in ("id", "name", "lat", "lon")})
    return {"version": 2, "stations": stations, "trips": [{
        "number": trip["number"],
        "category": trip["category"],
        "headsign": trip["headsign"],
        "dates": trip["dates"],
        "calls": [numbers[stop["id"]] for stop in trip["stops"]],
        "times": [time for stop in trip["stops"] for time in (stop["arrival"], stop["departure"])],
    } for trip in trips]}


if __name__ == "__main__":
    arguments = sys.argv[1:]
    metro_only = "--metro" in arguments
    main(*[argument for argument in arguments if argument != "--metro"][:3], metro=metro_only)
