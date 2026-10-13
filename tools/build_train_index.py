#!/usr/bin/env python3
"""Create a compact, scheduled Trenitalia Piemonte rail index from GTFS."""

import csv
import json
import sys
import zipfile
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


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


def main(source, output):
    with zipfile.ZipFile(source) as archive:
        stops = {
            row["stop_id"]: {
                "id": row["stop_id"],
                "name": row["stop_name"].strip(),
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
            stop_times = sorted(times[trip_id])
            dates = active_dates.get(trip["service_id"])
            if len(stop_times) < 2 or not dates:
                continue
            output_trips.append({
                "number": trip["trip_short_name"].lstrip("0") or trip["trip_short_name"],
                "category": trip.get("train_category", "REGIONALE").strip() or "REGIONALE",
                "headsign": trip["trip_headsign"].strip(),
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
    main(sys.argv[1], sys.argv[2])
