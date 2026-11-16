#!/usr/bin/env python3
"""Packs the timetable indexes for the app's data feed (the `data` release of this repository).

Usage: build_data_release.py OUT_DIR INDEX.json [INDEX.json ...] [--previous MANIFEST.json]

Writes OUT_DIR/NAME.json.gz for each index and OUT_DIR/manifest.json, which tells the app, for each
file, the asset to download, the SHA-256 of the unpacked JSON and the layout ("schema") it uses.
With --previous, an index whose checksum equals the previously published one is left out, and when
none changed nothing is written and "changed=false" is printed; otherwise "changed=true".
Compression is deterministic, so the same index always gives the same asset.
"""
import datetime
import gzip
import hashlib
import json
import sys
from pathlib import Path

# An index with fewer entries than this is a broken build, not a quiet timetable.
LISTS = {"patterns": 100, "trips": 100}


def check(path, data):
    """The index must be a parsable one with a layout number and a real timetable inside."""
    root = json.loads(data)
    for key, minimum in LISTS.items():
        if key in root:
            if len(root[key]) < minimum:
                raise SystemExit(f"{path.name}: only {len(root[key])} {key}, refusing to publish")
            return root.get("version", 1)
    raise SystemExit(f"{path.name}: neither patterns nor trips, refusing to publish")


def main(out_dir, sources, previous=None):
    out = Path(out_dir)
    old = {}
    if previous:
        old = json.loads(Path(previous).read_text(encoding="utf-8")).get("files", {})
    files, changed = {}, False
    for source in map(Path, sources):
        data = source.read_bytes()
        schema = check(source, data)
        digest = hashlib.sha256(data).hexdigest()
        entry = {"asset": source.name + ".gz", "sha256": digest, "schema": schema, "bytes": len(data)}
        files[source.name] = entry
        if old.get(source.name, {}).get("sha256") != digest:
            changed = True
    if previous and not changed:
        print("changed=false")
        return
    out.mkdir(parents=True, exist_ok=True)
    for source in map(Path, sources):
        # mtime=0 and no file name keep the gzip bytes the same from run to run.
        (out / (source.name + ".gz")).write_bytes(gzip.compress(source.read_bytes(), 9, mtime=0))
    manifest = {
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "files": files,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    print("changed=true")


if __name__ == "__main__":
    arguments = sys.argv[1:]
    previous = None
    if "--previous" in arguments:
        position = arguments.index("--previous")
        previous = arguments[position + 1]
        del arguments[position:position + 2]
    main(arguments[0], arguments[1:], previous)
