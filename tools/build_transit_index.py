#!/usr/bin/env python3
"""Build the compact direction/stop index consumed by the Android app."""

import csv
import json
import math
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

# A drawn route line may stray this far from the surveyed shape; a metre is under a pixel.
SHAPE_TOLERANCE_METRES = 2.0
METRES_PER_DEGREE = 111_195.0
# A longer wait than this at the end of a line is a driver's break, not a turnaround.
MAX_TURNAROUND_SECONDS = 3600
# The next trip must start where the last one ended (almost always the very same stop).
MAX_TURNAROUND_GAP_METRES = 300


def rows(archive, name):
    return csv.DictReader(
        (line.decode("utf-8-sig") for line in archive.open(name))
    )


def simplify(points, tolerance=SHAPE_TOLERANCE_METRES):
    """Douglas-Peucker on (lat, lon) points; flat-earth maths is exact enough at city scale."""
    if len(points) < 3:
        return list(points)
    scale = math.cos(math.radians(points[0][0]))
    xy = [(lon * scale * METRES_PER_DEGREE, lat * METRES_PER_DEGREE) for lat, lon in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    pending = [(0, len(points) - 1)]
    while pending:
        first, last = pending.pop()
        (ax, ay), (bx, by) = xy[first], xy[last]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        far, far_distance = -1, tolerance
        for index in range(first + 1, last):
            px, py = xy[index]
            distance = (math.hypot(px - ax, py - ay) if length == 0
                        else abs(dx * (ay - py) - (ax - px) * dy) / length)
            if distance > far_distance:
                far, far_distance = index, distance
        if far >= 0:
            keep[far] = True
            pending += [(first, far), (far, last)]
    return [point for point, kept in zip(points, keep) if kept]


def encode_polyline(points):
    """Google's encoded polyline, five decimals (about a metre)."""
    out, last_lat, last_lon = [], 0, 0
    for lat, lon in points:
        lat, lon = round(lat * 1e5), round(lon * 1e5)
        for delta in (lat - last_lat, lon - last_lon):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                out.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            out.append(chr(value + 63))
        last_lat, last_lon = lat, lon
    return "".join(out)


def clock_seconds(value):
    """GTFS times run past 24:00:00 for trips that finish after midnight."""
    hours, minutes, seconds = value.strip().split(":")
    return int(hours) * 3600 + int(minutes) * 60 + int(seconds)


def metres_between(first, second):
    scale = math.cos(math.radians(first["lat"]))
    return math.hypot((first["lat"] - second["lat"]) * METRES_PER_DEGREE,
                      (first["lon"] - second["lon"]) * METRES_PER_DEGREE * scale)


def read_shapes(archive):
    """shape id -> encoded polyline; an absent shapes.txt just means no geometry."""
    if "shapes.txt" not in archive.namelist():
        return {}
    raw = defaultdict(list)
    for row in rows(archive, "shapes.txt"):
        raw[row["shape_id"]].append(
            (int(row["shape_pt_sequence"]), float(row["shape_pt_lat"]), float(row["shape_pt_lon"]))
        )
    return {
        shape_id: encode_polyline(simplify([(lat, lon) for _, lat, lon in sorted(points)]))
        for shape_id, points in raw.items()
        if len(points) >= 2
    }


def add_successors(patterns, trip_blocks, trip_span, stops):
    """Mark what each bus does after a pattern, from the blocks (one bus's duty) in the timetable.

    A bus finishing a trip at the end of the line usually sets off again the other way a few
    minutes later, so a bus about to terminate is a bus that will soon be coming back.
    Adds to a pattern: "next" (the pattern that most often follows, and the usual wait before it)
    and the trips of this pattern that really do continue.
    """
    by_id = {pattern["id"]: pattern for pattern in patterns}
    pattern_of = {trip: pattern["id"] for pattern in patterns for trip in pattern["tripIds"]}
    blocks = defaultdict(list)
    for trip, block in trip_blocks.items():
        if trip in pattern_of and trip in trip_span and None not in trip_span[trip]:
            blocks[block].append(trip)
    followers = defaultdict(lambda: defaultdict(list))  # pattern -> next pattern -> [(trip, wait)]
    for trips in blocks.values():
        trips.sort(key=lambda trip: clock_seconds(trip_span[trip][1]))
        for trip, following in zip(trips, trips[1:]):
            here, there = by_id[pattern_of[trip]], by_id[pattern_of[following]]
            wait = clock_seconds(trip_span[following][1]) - clock_seconds(trip_span[trip][3])
            if (here["routeId"] != there["routeId"] or not 0 <= wait <= MAX_TURNAROUND_SECONDS
                    or metres_between(here["stops"][-1], there["stops"][0]) > MAX_TURNAROUND_GAP_METRES):
                continue
            followers[here["id"]][there["id"]].append((trip, wait))
    for pattern_id, successors in followers.items():
        best_id, links = max(successors.items(), key=lambda item: (len(item[1]), item[0]))
        # Split between two successors is a guess, not a timetable.
        if len(links) * 2 < sum(len(found) for found in successors.values()):
            continue
        waits = sorted(wait for _, wait in links)
        by_id[pattern_id]["next"] = {
            "id": best_id,
            "wait": waits[len(waits) // 2],
            "trips": sorted(trip for trip, _ in links),
        }


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
        shapes = read_shapes(archive)

        # Trips sharing a shape usually share their stops, but not always (short runs,
        # skipped stops); group by the stop sequence itself, never by one sample trip.
        trip_keys = {}
        trip_blocks = {}
        for trip in rows(archive, "trips.txt"):
            key = (
                trip["route_id"],
                trip["direction_id"],
                trip["trip_headsign"].strip(),
                trip["shape_id"],
            )
            trip_keys[trip["trip_id"]] = key
            if trip.get("block_id"):
                trip_blocks[trip["trip_id"]] = (trip["block_id"], trip.get("service_id", ""))

        trip_stops = defaultdict(list)
        # trip -> [first sequence, departure there, last sequence, arrival there]
        trip_span = {}
        for stop_time in rows(archive, "stop_times.txt"):
            trip_id = stop_time["trip_id"]
            if trip_id in trip_keys and stop_time["stop_id"] in stops:
                sequence = int(stop_time["stop_sequence"])
                trip_stops[trip_id].append((sequence, stop_time["stop_id"]))
                if trip_id in trip_blocks:
                    span = trip_span.setdefault(trip_id, [sequence, None, sequence, None])
                    if sequence <= span[0] or span[1] is None:
                        span[0], span[1] = sequence, stop_time["departure_time"]
                    if sequence >= span[2] or span[3] is None:
                        span[2], span[3] = sequence, stop_time["arrival_time"]

        # (route, direction, headsign, shape) -> {stop id sequence: trip ids}
        variants = defaultdict(lambda: defaultdict(list))
        for trip_id, key in trip_keys.items():
            sequence = tuple(stop_id for _, stop_id in sorted(trip_stops.get(trip_id, ())))
            variants[key][sequence].append(trip_id)

        # Different shapes (or shapeless feeds) can still describe the same journey;
        # one pattern per distinct (route, direction, headsign, stop sequence).
        merged = {}
        shape_trips = defaultdict(Counter)
        for key in sorted(variants):
            route_id, direction_id, headsign, shape_id = key
            route = routes.get(route_id)
            if not route or not headsign:
                continue
            # Most common sequence first, so it keeps the plain, stable pattern id.
            ranked = sorted(variants[key].items(), key=lambda item: (-len(item[1]), item[0]))
            for rank, (sequence, ids) in enumerate(ranked):
                if len(sequence) < 2:
                    continue
                identity = (route_id, direction_id, headsign, sequence)
                shape_trips[identity][shape_id] += len(ids)
                if identity in merged:
                    merged[identity]["tripIds"].extend(ids)
                    continue
                pattern_id = f"{route_id}:{direction_id}:{headsign}:{shape_id}"
                if rank:
                    pattern_id += f":v{rank + 1}"
                merged[identity] = {
                    "id": pattern_id,
                    "routeId": route_id,
                    "route": route["route_short_name"].strip(),
                    "headsign": headsign,
                    "directionId": direction_id,
                    "tripIds": list(ids),
                    "stops": [stops[stop_id] for stop_id in sequence],
                }
        # Merged shapes describe the same journey; draw the one most trips actually drive.
        for identity, pattern in merged.items():
            drawn = [(count, shape_id) for shape_id, count in shape_trips[identity].items()
                     if shape_id in shapes]
            if drawn:
                pattern["shape"] = max(drawn, key=lambda item: (item[0], item[1]))[1]
        patterns = list(merged.values())
        add_successors(patterns, trip_blocks, trip_span, stops)
        used = {pattern["shape"] for pattern in patterns if "shape" in pattern}
        shapes = {shape_id: shapes[shape_id] for shape_id in sorted(used)}

    payload = {"version": 4, "patterns": patterns, "shapes": shapes}
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"Wrote {len(patterns)} patterns to {output}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
