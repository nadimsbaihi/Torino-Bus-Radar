#!/usr/bin/env python3
"""Build the bundled Torino map/search package from BBBike's city extracts.

Usage: python3 tools/build_offline_city.py SOURCE_DIR ASSET_DIR TRANSIT_INDEX TRAIN_INDEX
SOURCE_DIR contains Turin.osm.gz and Turin.osm.mapsforge-osm.zip.
TRANSIT_INDEX and TRAIN_INDEX are the bundled GTFS-derived JSON indexes.

       python3 tools/build_offline_city.py --stations ASSET_DIR TRAIN_INDEX
replaces only the train stations in an existing search database, for a new train timetable
without a new map extract.
Only the Python standard library is required; no tile server is scraped.
"""
import gzip
import hashlib
import json
import math
import re
import sqlite3
import struct
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def normalize(value):
    text = unicodedata.normalize('NFD', value).lower()
    text = ''.join(c for c in text if unicodedata.category(c) != 'Mn')
    return ' '.join(re.findall(r'[^\W_]+', text))


TRAIN_SOURCE = 'Trenitalia rail GTFS stations'
TRAIN_LABEL = 'Regional train station'
TRAIN_NOTICE = ('Train stations: Trenitalia timetable (CCISS National Access Point), converted to GTFS\n'
                'by Clement Desouche, https://github.com/deryclem/trenitalia-gtfs, CC BY 4.0.\n')


def station_rows(train_index, bounds):
    """Each station of the train index inside the map once: (name, lat, lon)."""
    seen = set()
    for trip in json.loads(Path(train_index).read_text(encoding='utf-8'))['trips']:
        for stop in trip['stops']:
            lat, lon = stop['lat'], stop['lon']
            if stop['id'] in seen or not (bounds['minlat'] <= lat <= bounds['maxlat'] and
                                          bounds['minlon'] <= lon <= bounds['maxlon']):
                continue
            seen.add(stop['id'])
            yield stop['name'].strip(), lat, lon


def notice(source_note):
    return (source_note + '\nSearch database and pedestrian graph include data from the BBBike OSM extract.\n'
            'OSM-derived records are under ODbL 1.0: https://opendatacommons.org/licenses/odbl/1-0/\n'
            'Bus stops, timetables and live data: Data source: GTT S.p.A. – Gruppo Torinese Trasporti,\n'
            'https://www.gtt.to.it\n'
            'GTT GTFS terms: https://www.gtt.to.it/gtt_gtfs_license.html\n'
            + TRAIN_NOTICE +
            'Builder: tools/build_offline_city.py in the Torino Bus Radar source.\n'
            'App code libraries and their licences: in the app, tap the map credit, then Open-source licences.\n')


def refresh_stations(output, train_index):
    """Swaps the train stations of a built search database for those of [train_index]."""
    output, train_index = Path(output), Path(train_index)
    manifest = json.loads((output / 'manifest.json').read_text())
    database = sqlite3.connect(output / 'places.sqlite')
    stale = [row[0] for row in database.execute("SELECT id FROM places WHERE kind = 'train_station'")]
    database.executemany('DELETE FROM search WHERE rowid = ?', [(i,) for i in stale])
    database.execute("DELETE FROM places WHERE kind = 'train_station'")
    next_id = database.execute('SELECT COALESCE(MAX(id), 0) FROM places').fetchone()[0]
    seen = set()
    for name, lat, lon in station_rows(train_index, manifest['bounds']):
        key = (normalize(name), round(lat, 4), round(lon, 4))
        if key in seen:
            continue
        seen.add(key)
        next_id += 1
        database.execute('INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                         (next_id, name, f'{name} · {TRAIN_LABEL}', normalize(name), '',
                          'train_station', lat, lon))
        database.execute('INSERT INTO search(rowid, tokens) VALUES (?, ?)',
                         (next_id, normalize(f'{name} Torino')))
    database.execute("INSERT INTO search(search) VALUES ('optimize')")
    database.commit()
    database.execute('VACUUM')
    database.close()
    sources = {name: count for name, count in manifest['searchSources'].items()
               if 'rail' not in name.lower()}
    sources[TRAIN_SOURCE] = len(seen)
    manifest['searchSources'] = sources
    manifest['places'] = sum(sources.values())
    database_file = output / 'places.sqlite'
    manifest['files']['places.sqlite'] = {
        'bytes': database_file.stat().st_size,
        'sha256': hashlib.sha256(database_file.read_bytes()).hexdigest()}
    hashes = {name: value for name, value in manifest['sourceSha256'].items()
              if name not in ('trenitalia_rail_index.json', train_index.name)}
    hashes[train_index.name] = hashlib.sha256(train_index.read_bytes()).hexdigest()
    manifest['sourceSha256'] = hashes
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    text = (output / 'NOTICE.txt').read_text()
    start = text.index('Regional train stations:') if 'Regional train stations:' in text \
        else text.index('Train stations:')
    end = text.index('Builder:')
    (output / 'NOTICE.txt').write_text(text[:start] + TRAIN_NOTICE + text[end:])
    print(json.dumps(manifest['searchSources'], indent=2))


# Ways a person can plausibly walk along. An allow-list: new or odd highway values
# (guided busways, via ferratas, ...) must not silently become footpaths.
WALKABLE_HIGHWAYS = {
    'footway', 'pedestrian', 'path', 'steps', 'living_street', 'residential',
    'service', 'unclassified', 'road', 'track', 'cycleway', 'bridleway',
    'corridor', 'platform', 'tertiary', 'tertiary_link', 'secondary',
    'secondary_link', 'primary', 'primary_link',
}


def pedestrian_way(tags):
    if tags.get('highway') not in WALKABLE_HIGHWAYS:
        return False
    if tags.get('motorroad') == 'yes' or tags.get('foot') in {'no', 'private'}:
        return False
    return tags.get('access') not in {'no', 'private'} or tags.get('foot') in {
        'yes', 'designated', 'permissive'
    }


def walk_metres(first, second):
    lat1, lon1 = map(math.radians, first)
    lat2, lon2 = map(math.radians, second)
    delta_lat, delta_lon = lat2 - lat1, lon2 - lon1
    a = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 12_742_000 * math.asin(min(1, math.sqrt(a)))


def polyline_midpoint(points):
    """The point half way along a polyline, by length (not the middle vertex)."""
    if len(points) == 1:
        return points[0]
    lengths = [walk_metres(p, q) for p, q in zip(points, points[1:])]
    half = sum(lengths) / 2
    for (p, q), length in zip(zip(points, points[1:]), lengths):
        if half <= length:
            t = half / length if length else 0
            return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
        half -= length
    return points[-1]


def inside_polygon(point, ring):
    lat, lon = point
    inside = False
    for (lat1, lon1), (lat2, lon2) in zip(ring, ring[1:]):
        if (lat1 > lat) != (lat2 > lat):
            if lon < lon1 + (lat - lat1) / (lat2 - lat1) * (lon2 - lon1):
                inside = not inside
    return inside


def polygon_point(ring):
    """A point inside a closed ring: its centroid, or failing that the middle of its widest
    chord at the centroid's latitude. The bounding-box centre can be outside L-shaped areas."""
    lat0 = sum(p[0] for p in ring) / len(ring)
    scale = math.cos(math.radians(lat0))
    area = cx = cy = 0.0
    for (lat1, lon1), (lat2, lon2) in zip(ring, ring[1:]):
        x1, y1, x2, y2 = lon1 * scale, lat1, lon2 * scale, lat2
        cross = x1 * y2 - x2 * y1
        area += cross
        cx += (x1 + x2) * cross
        cy += (y1 + y2) * cross
    bbox = ((min(p[0] for p in ring) + max(p[0] for p in ring)) / 2,
            (min(p[1] for p in ring) + max(p[1] for p in ring)) / 2)
    if abs(area) < 1e-18:
        return bbox
    centroid = (cy / (3 * area), cx / (3 * area) / scale)
    if inside_polygon(centroid, ring):
        return centroid
    crossings = sorted(
        lon1 + (centroid[0] - lat1) / (lat2 - lat1) * (lon2 - lon1)
        for (lat1, lon1), (lat2, lon2) in zip(ring, ring[1:])
        if (lat1 > centroid[0]) != (lat2 > centroid[0])
    )
    chords = list(zip(crossings[0::2], crossings[1::2]))
    if not chords:
        return bbox
    start, end = max(chords, key=lambda chord: chord[1] - chord[0])
    return (centroid[0], (start + end) / 2)


def representative_point(refs, points, tags):
    """Where to put a way on the map: along a street, inside an area."""
    closed = len(refs) > 3 and refs[0] == refs[-1] and 'highway' not in tags
    if closed and len(points) == len(refs):
        return polygon_point(points)
    return polyline_midpoint(points)


STREET_LINK_METRES = 1000
SETTLEMENTS = {'city', 'town', 'village', 'suburb', 'hamlet', 'quarter'}


def cluster_streets(entries):
    """Split same-named street fragments into separate streets: fragments within
    STREET_LINK_METRES of each other are one street, farther apart they are not.
    Returns one (tags, lat, lon) per street, at the fragment closest to its centre."""
    parent = list(range(len(entries)))

    def find(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    cells = {}
    size = 0.02
    for index, (lat, lon, _) in enumerate(entries):
        cell = (math.floor(lat / size), math.floor(lon / size))
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for other in cells.get((cell[0] + dx, cell[1] + dy), ()):
                    if walk_metres(entries[other][:2], (lat, lon)) <= STREET_LINK_METRES:
                        parent[find(index)] = find(other)
        cells.setdefault(cell, []).append(index)
    groups = {}
    for index in range(len(entries)):
        groups.setdefault(find(index), []).append(index)
    streets = []
    for members in groups.values():
        centre = (sum(entries[i][0] for i in members) / len(members),
                  sum(entries[i][1] for i in members) / len(members))
        lat, lon, tags = min(
            (entries[i] for i in members), key=lambda e: walk_metres(centre, e[:2]))
        streets.append((tags, lat, lon))
    return streets


def nearest_settlement(lat, lon, settlements, limit=8000):
    best = min(settlements, key=lambda s: walk_metres((lat, lon), s[1:]), default=None)
    if best and walk_metres((lat, lon), best[1:]) <= limit:
        return best[0]
    return None


def write_walk_graph(path, nodes, ways):
    node_ids = sorted({ref for way in ways for ref in way if ref in nodes})
    indices = {ref: index for index, ref in enumerate(node_ids)}
    edges = [[] for _ in node_ids]
    for way in ways:
        for start, end in zip(way, way[1:]):
            if start not in indices or end not in indices or start == end:
                continue
            a, b = indices[start], indices[end]
            metres = walk_metres(nodes[start], nodes[end])
            if metres <= 0:
                continue
            edges[a].append((b, metres))
            edges[b].append((a, metres))
    arc_count = sum(map(len, edges))
    with path.open('wb') as stream:
        stream.write(struct.pack('>8sII', b'MATOWLK1', len(node_ids), arc_count))
        for ref in node_ids:
            stream.write(struct.pack('>dd', *nodes[ref]))
        offset = 0
        stream.write(struct.pack('>I', offset))
        for neighbours in edges:
            offset += len(neighbours)
            stream.write(struct.pack('>I', offset))
        for neighbours in edges:
            for index, metres in neighbours:
                stream.write(struct.pack('>If', index, metres))
    return len(node_ids), arc_count


def build(source, output, transit_index, train_index):
    source, output = Path(source), Path(output)
    transit_index, train_index = Path(transit_index), Path(train_index)
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source / 'Turin.osm.mapsforge-osm.zip') as archive:
        (output / 'torino.map').write_bytes(archive.read('Turin-mapsforge-osm/Turin.map'))
        source_note = archive.read('Turin-mapsforge-osm/README.txt').decode()

    database_path = output / 'places.sqlite'
    temporary = output / 'places.sqlite.tmp'
    temporary.unlink(missing_ok=True)
    database = sqlite3.connect(temporary)
    database.executescript('''
        CREATE TABLE places (
            id INTEGER PRIMARY KEY, name TEXT NOT NULL, label TEXT NOT NULL,
            name_key TEXT NOT NULL, address_key TEXT NOT NULL,
            kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL
        );
        CREATE VIRTUAL TABLE search USING fts4(tokens);
    ''')
    nodes = {}
    streets = {}
    settlements = []
    way_boxes = {}
    walk_ways = []
    seen = set()
    bounds = None
    count = 0

    def add(tags, lat, lon, kind, name=None, source_label=None):
        nonlocal count
        name = name or tags.get('name') or tags.get('addr:street', '')
        street = tags.get('addr:street', '')
        number = tags.get('addr:housenumber', '')
        address = ' '.join(filter(None, [street, number]))
        if kind == 'address':
            name = address
        if not name:
            return
        city = tags.get('addr:city', '')
        label = ' · '.join(dict.fromkeys(part for part in
                                       [name, address, city, source_label] if part))
        key = (normalize(name), round(lat, 4), round(lon, 4), kind)
        if key in seen:
            return
        seen.add(key)
        # These extracts contain several municipalities, so don't label every
        # untagged point "Torino". Search the city's name without inventing an address.
        tokens = normalize(' '.join([name, address, city, 'Torino', tags.get('alt_name', ''),
                                     tags.get('short_name', ''), tags.get('brand', '')]))
        count += 1
        database.execute('INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                         (count, name, label, normalize(name), normalize(address), kind, lat, lon))
        database.execute('INSERT INTO search(rowid, tokens) VALUES (?, ?)', (count, tokens))

    with gzip.open(source / 'Turin.osm.gz', 'rb') as stream:
        iterator = ET.iterparse(stream, events=('start', 'end'))
        _, root = next(iterator)
        for event, element in iterator:
            if event != 'end':
                continue
            if element.tag == 'bounds':
                bounds = {k: float(v) for k, v in element.attrib.items()}
                element.clear()
            elif element.tag in ('node', 'way', 'relation'):
                tags = {t.attrib['k']: t.attrib['v'] for t in element.findall('tag')}
                if element.tag == 'node':
                    lat, lon = float(element.attrib['lat']), float(element.attrib['lon'])
                    nodes[int(element.attrib['id'])] = (lat, lon)
                elif element.tag == 'way':
                    refs = [int(n.attrib['ref']) for n in element.findall('nd')]
                    points = [nodes[ref] for ref in refs if ref in nodes]
                    if not points:
                        element.clear(); root.clear(); continue
                    if pedestrian_way(tags):
                        walk_ways.append(refs)
                    # Relations only know their members by id: keep the extent of the ways
                    # that can be parts of one (buildings and streets are never outlines).
                    if 'highway' not in tags and 'building' not in tags:
                        way_boxes[int(element.attrib['id'])] = (
                            min(p[0] for p in points), max(p[0] for p in points),
                            min(p[1] for p in points), max(p[1] for p in points))
                    lat, lon = representative_point(refs, points, tags)
                elif tags.get('type') == 'multipolygon':
                    # Parks, hospitals and the like are often relations of untagged ways.
                    boxes = [way_boxes[int(m.attrib['ref'])] for m in element.findall('member')
                             if m.attrib.get('type') == 'way' and m.attrib.get('role') in ('outer', '')
                             and int(m.attrib['ref']) in way_boxes]
                    if not boxes:
                        element.clear(); root.clear(); continue
                    lat = (min(b[0] for b in boxes) + max(b[1] for b in boxes)) / 2
                    lon = (min(b[2] for b in boxes) + max(b[3] for b in boxes)) / 2
                else:
                    element.clear(); root.clear(); continue
                if tags.get('addr:street') and tags.get('addr:housenumber'):
                    add(tags, lat, lon, 'address')
                if tags.get('name'):
                    if 'highway' in tags and element.tag == 'way':
                        # Fragments of a street; grouped into streets once all are known.
                        streets.setdefault((tags['name'], tags.get('addr:city', '')), []).append(
                            (lat, lon, tags))
                    elif any(k in tags for k in ('amenity', 'shop', 'tourism', 'leisure', 'office',
                                                 'railway', 'public_transport', 'place', 'historic')):
                        add(tags, lat, lon, 'place')
                    if element.tag == 'node' and tags.get('place') in SETTLEMENTS:
                        settlements.append((tags['name'], lat, lon))
                element.clear()
                root.clear()
    # The extract holds several municipalities and street ways rarely say which they are in,
    # so a name can mean several streets: one result each, told apart by the nearest town.
    by_name = {}
    for (name, city), entries in streets.items():
        for street in cluster_streets(entries):
            by_name.setdefault(name, []).append((city, street))
    for name, found in by_name.items():
        for city, (tags, lat, lon) in found:
            locality = None
            if len(found) > 1 and not city:
                locality = nearest_settlement(lat, lon, settlements)
            add(tags, lat, lon, 'street', source_label=locality)
    source_counts = {'OpenStreetMap': count}
    for index_path, collection, kind, label, source_name in (
        (transit_index, 'patterns', 'bus_stop', 'GTT stop', 'GTT GTFS stops'),
        (train_index, 'trips', 'train_station', TRAIN_LABEL, TRAIN_SOURCE),
    ):
        seen_ids = set()
        before = count
        for service in json.loads(index_path.read_text(encoding='utf-8'))[collection]:
            for stop in service['stops']:
                if stop['id'] in seen_ids:
                    continue
                seen_ids.add(stop['id'])
                lat, lon = stop['lat'], stop['lon']
                if bounds and (bounds['minlat'] <= lat <= bounds['maxlat'] and
                               bounds['minlon'] <= lon <= bounds['maxlon']):
                    add({}, lat, lon, kind, stop['name'].strip(), label)
        source_counts[source_name] = count - before
    walk_nodes, walk_arcs = write_walk_graph(output / 'walk_graph.bin', nodes, walk_ways)
    database.execute("INSERT INTO search(search) VALUES ('optimize')")
    database.commit()
    database.execute('VACUUM')
    database.close()
    temporary.replace(database_path)
    manifest = {
        'name': 'Torino and nearby suburbs',
        'source': 'https://download.bbbike.org/osm/bbbike/Turin/',
        'attribution': '© OpenStreetMap contributors',
        'license': 'https://opendatacommons.org/licenses/odbl/1-0/',
        'bounds': bounds,
        'places': count,
        'searchSources': source_counts,
        'files': {name: {'bytes': (output / name).stat().st_size,
                         'sha256': hashlib.sha256((output / name).read_bytes()).hexdigest()}
                  for name in ['torino.map', 'places.sqlite', 'walk_graph.bin']},
        'walkGraph': {'nodes': walk_nodes, 'arcs': walk_arcs},
        'sourceSha256': {
            **{name: hashlib.sha256((source / name).read_bytes()).hexdigest()
               for name in ['Turin.osm.gz', 'Turin.osm.mapsforge-osm.zip']},
            transit_index.name: hashlib.sha256(transit_index.read_bytes()).hexdigest(),
            train_index.name: hashlib.sha256(train_index.read_bytes()).hexdigest(),
        },
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'NOTICE.txt').write_text(notice(source_note))
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    if sys.argv[1] == '--stations':
        refresh_stations(*sys.argv[2:])
    else:
        build(*sys.argv[1:])
