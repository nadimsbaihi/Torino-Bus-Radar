#!/usr/bin/env python3
"""Create a compact, scheduled Trenitalia Piemonte rail index from GTFS."""

import csv
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path


def rows(archive, name):
    return csv.DictReader(line.decode("utf-8-sig") for line in archive.open(name))


def seconds(value):
    hour, minute, second = (int(part) for part in value.split(":"))
    return hour * 3600 + minute * 60 + second


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
        routes = {row["route_id"]: row for row in rows(archive, "routes.txt")}
        trips = {row["trip_id"]: row for row in rows(archive, "trips.txt")}
        active_dates = defaultdict(list)
        for row in rows(archive, "calendar_dates.txt"):
            if row["exception_type"] == "1":
                active_dates[row["service_id"]].append(row["date"])

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
            dates = active_dates[trip["service_id"]]
            if len(stop_times) < 2 or not dates:
                continue
            route = routes[trip["route_id"]]
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
