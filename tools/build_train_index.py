#!/usr/bin/env python3
"""Create a compact, scheduled rail index from a Trenitalia GTFS feed.

Usage: build_train_index.py SOURCE.zip OUTPUT.json [MINLAT,MINLON,MAXLAT,MAXLON]

With an area, only trains calling at two or more stations inside it are kept, and only those
stations: the app plans journeys within its map, so the rest of a national feed is dead weight.
Long-distance and bus services are left out; the trains are the ones a local ticket covers.
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


def main(source, output, area=None):
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
            output_trips.append({
                "number": trip["trip_short_name"].lstrip("0") or trip["trip_short_name"],
                "category": category,
                "headsign": headsign,
                "dates": dates,
                "stops": [
                    {**stop, "arrival": arrival, "departure": departure}
                    for _, stop, arrival, departure in stop_times
                ],
            })

    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(
        json.dumps({"version": 1, "trips": output_trips}, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"Wrote {len(output_trips)} train trips to {output}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
