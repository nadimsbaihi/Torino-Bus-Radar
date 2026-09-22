#!/usr/bin/env python3
"""Build the compact direction/stop index consumed by the Android app."""

import csv
import json
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path


def rows(archive, name):
    return csv.DictReader(
        (line.decode("utf-8-sig") for line in archive.open(name))
    )


def main(source, output):
    with zipfile.ZipFile(source) as archive:
        routes = {row["route_id"]: row for row in rows(archive, "routes.txt")}
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

        counts = Counter()
        sample_trip = {}
        trip_ids = defaultdict(list)
        for trip in rows(archive, "trips.txt"):
            key = (
                trip["route_id"],
                trip["direction_id"],
                trip["trip_headsign"].strip(),
                trip["shape_id"],
            )
            counts[key] += 1
            sample_trip[key] = trip["trip_id"]
            trip_ids[key].append(trip["trip_id"])

        # Preserve every scheduled shape variant so a live trip is matched to
        # its own ordered stop sequence, not a representative route shape.
        trip_to_key = {trip_id: key for key, trip_id in sample_trip.items()}

        stop_sequences = defaultdict(list)
        for stop_time in rows(archive, "stop_times.txt"):
            key = trip_to_key.get(stop_time["trip_id"])
            stop = stops.get(stop_time["stop_id"])
            if key and stop:
                stop_sequences[key].append(
                    (int(stop_time["stop_sequence"]), stop)
                )

        patterns = []
        for full_key, count in counts.items():
            route_id, direction_id, headsign, shape_id = full_key
            route = routes.get(route_id)
            ordered = [item[1] for item in sorted(stop_sequences[full_key])]
            if not route or not headsign or len(ordered) < 2:
                continue
            patterns.append(
                {
                    "id": f"{route_id}:{direction_id}:{headsign}:{shape_id}",
                    "routeId": route_id,
                    "route": route["route_short_name"].strip(),
                    "headsign": headsign,
                    "directionId": direction_id,
                    "tripIds": trip_ids[full_key],
                    "stops": ordered,
                }
            )

    payload = {"version": 3, "patterns": patterns}
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"Wrote {len(patterns)} patterns to {output}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
