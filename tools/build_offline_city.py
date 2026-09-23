#!/usr/bin/env python3
"""Build the bundled Torino map/search package from BBBike's city extracts.

Usage: python3 tools/build_offline_city.py SOURCE_DIR ASSET_DIR
SOURCE_DIR contains Turin.osm.gz and Turin.osm.mapsforge-osm.zip.
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


def pedestrian_way(tags):
    highway = tags.get('highway')
    if highway is None or highway in {'motorway', 'motorway_link', 'trunk',
                                      'trunk_link', 'busway', 'raceway',
                                      'construction', 'proposed'}:
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


def build(source, output):
    source, output = Path(source), Path(output)
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
    roads = {}
    walk_ways = []
    seen = set()
    bounds = None
    count = 0

    def add(tags, lat, lon, kind, name=None):
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
        label = ' · '.join(dict.fromkeys(part for part in [name, address, city] if part))
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
                    # A point on a street, or a building/area's bounding-box centre.
                    if 'highway' in tags:
                        lat, lon = points[len(points) // 2]
                    else:
                        lat = (min(p[0] for p in points) + max(p[0] for p in points)) / 2
                        lon = (min(p[1] for p in points) + max(p[1] for p in points)) / 2
                else:
                    element.clear(); root.clear(); continue
                if tags.get('addr:street') and tags.get('addr:housenumber'):
                    add(tags, lat, lon, 'address')
                if tags.get('name'):
                    if 'highway' in tags and element.tag == 'way':
                        # Keep one representative per named street and municipality.
                        roads.setdefault((tags['name'], tags.get('addr:city', '')), (tags, lat, lon))
                    elif any(k in tags for k in ('amenity', 'shop', 'tourism', 'leisure', 'office',
                                                 'railway', 'public_transport', 'place', 'historic')):
                        add(tags, lat, lon, 'place')
                element.clear()
                root.clear()
    for tags, lat, lon in roads.values():
        add(tags, lat, lon, 'street')
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
        'files': {name: {'bytes': (output / name).stat().st_size,
                         'sha256': hashlib.sha256((output / name).read_bytes()).hexdigest()}
                  for name in ['torino.map', 'places.sqlite', 'walk_graph.bin']},
        'walkGraph': {'nodes': walk_nodes, 'arcs': walk_arcs},
        'sourceSha256': {name: hashlib.sha256((source / name).read_bytes()).hexdigest()
                         for name in ['Turin.osm.gz', 'Turin.osm.mapsforge-osm.zip']},
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'NOTICE.txt').write_text(source_note + '\nSearch database and pedestrian graph derived from the same BBBike OSM extract.\n'
                                    'Database licensed under ODbL 1.0: https://opendatacommons.org/licenses/odbl/1-0/\n'
                                    'Builder: tools/build_offline_city.py in the Torino Bus Radar source.\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2])
