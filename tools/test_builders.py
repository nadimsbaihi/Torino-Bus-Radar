#!/usr/bin/env python3
"""Checks for the index builders, using small synthetic inputs.

Usage: python3 tools/test_builders.py
"""
import gzip
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import build_offline_city as city  # noqa: E402
import build_transit_index as transit  # noqa: E402


def run(script, *args):
    subprocess.run([sys.executable, str(TOOLS / script), *map(str, args)],
                   check=True, stdout=subprocess.DEVNULL)


def write_zip(path, files):
    with zipfile.ZipFile(path, 'w') as archive:
        for name, content in files.items():
            archive.writestr(name, content)


class TransitIndexTest(unittest.TestCase):
    def test_patterns_follow_stop_sequences_not_one_sample_trip(self):
        stops = "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(
            f"{s},{s},45.0{i},7.0\n" for i, s in enumerate("ABCD"))
        trips = ("route_id,trip_id,direction_id,trip_headsign,shape_id\n"
                 "r,t1,0,North,s1\nr,t2,0,North,s1\nr,t3,0,North,s1\n"
                 "r,t4,0,North,s2\nr,t5,0,North,\n")

        def times(trip, sequence):
            return "".join(f"{trip},{i},{s},08:00:00,08:00:00\n"
                           for i, s in enumerate(sequence, 1))
        stop_times = ("trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                      + times("t1", "ABCD") + times("t2", "ABCD") + times("t3", "ACD")
                      + times("t4", "ABCD") + times("t5", "ABCD"))
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory, 'gtfs.zip'), Path(directory, 'out.json')
            write_zip(source, {'stops.txt': stops, 'routes.txt': "route_id,route_short_name\nr,1\n",
                               'trips.txt': trips, 'stop_times.txt': stop_times})
            run('build_transit_index.py', source, output)
            patterns = json.loads(output.read_text())['patterns']
        by_stops = {tuple(s['id'] for s in p['stops']): p for p in patterns}
        self.assertEqual(2, len(patterns), "identical journeys on different shapes are one pattern")
        self.assertEqual({'t1', 't2', 't4', 't5'}, set(by_stops[tuple('ABCD')]['tripIds']))
        self.assertEqual(['t3'], by_stops[tuple('ACD')]['tripIds'])
        self.assertEqual(2, len({p['id'] for p in patterns}))


def decode_polyline(encoded):
    points, index, lat, lon = [], 0, 0, 0
    while index < len(encoded):
        for axis in (0, 1):
            result = shift = 0
            while True:
                chunk = ord(encoded[index]) - 63
                index += 1
                result |= (chunk & 0x1F) << shift
                shift += 5
                if chunk < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if axis == 0:
                lat += delta
            else:
                lon += delta
        points.append((lat / 1e5, lon / 1e5))
    return points


class TransitShapeTest(unittest.TestCase):
    def test_polyline_matches_the_reference_encoding(self):
        self.assertEqual("_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                         transit.encode_polyline([(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]))

    def test_simplify_drops_points_on_the_line_and_keeps_the_bends(self):
        line = [(45.0, 7.0), (45.0, 7.005), (45.0, 7.01), (45.005, 7.01), (45.01, 7.01)]
        self.assertEqual([(45.0, 7.0), (45.0, 7.01), (45.01, 7.01)], transit.simplify(line))

    def test_patterns_carry_the_shape_most_trips_drive(self):
        stops = "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(
            f"{s},{s},45.0{i},7.0\n" for i, s in enumerate("ABC"))
        trips = ("route_id,trip_id,direction_id,trip_headsign,shape_id\n"
                 "r,t1,0,North,rare\nr,t2,0,North,common\nr,t3,0,North,common\nr,t4,0,North,\n")
        stop_times = ("trip_id,stop_sequence,stop_id,arrival_time,departure_time\n" + "".join(
            f"{t},{i},{s},08:00:00,08:00:00\n" for t in ("t1", "t2", "t3", "t4")
            for i, s in enumerate("ABC", 1)))
        shapes = ("shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
                  "common,45.0,7.0,1\ncommon,45.0,7.01,2\ncommon,45.01,7.01,3\n"
                  "rare,45.0,7.0,1\nrare,45.01,7.0,2\nunused,45.0,7.0,1\nunused,45.5,7.5,2\n")
        files = {'stops.txt': stops, 'routes.txt': "route_id,route_short_name\nr,1\n",
                 'trips.txt': trips, 'stop_times.txt': stop_times}
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory, 'gtfs.zip'), Path(directory, 'out.json')
            write_zip(source, {**files, 'shapes.txt': shapes})
            run('build_transit_index.py', source, output)
            index = json.loads(output.read_text())
            write_zip(source, files)
            run('build_transit_index.py', source, output)
            shapeless = json.loads(output.read_text())
        self.assertEqual(5, index['version'])
        self.assertEqual(1, len(index['patterns']))
        self.assertEqual('common', index['patterns'][0]['shape'])
        self.assertEqual({'common'}, set(index['shapes']), "only shapes a pattern uses are shipped")
        self.assertEqual([(45.0, 7.0), (45.0, 7.01), (45.01, 7.01)],
                         decode_polyline(index['shapes']['common']))
        self.assertNotIn('shape', shapeless['patterns'][0], "a feed without shapes still builds")
        self.assertEqual({}, shapeless['shapes'])


class TransitTurnaroundTest(unittest.TestCase):
    def test_patterns_know_what_the_same_bus_does_next(self):
        stops = "stop_id,stop_name,stop_lat,stop_lon\nA,A,45.00,7.0\nB,B,45.01,7.0\nC,C,45.02,7.0\nFar,Far,45.5,7.5\n"
        # Block b1 runs out (t1), back (t2), out again (t3), then ends. t5 starts at a different
        # terminus and t6 waits two hours: neither is a turnaround.
        trips = ("route_id,service_id,trip_id,direction_id,trip_headsign,shape_id,block_id\n"
                 "r,s,t1,0,Out,,b1\nr,s,t2,1,Back,,b1\nr,s,t3,0,Out,,b1\n"
                 "r,s,t4,0,Out,,b2\nr,s,t5,1,Back,,b2\n"
                 "r,s,t6,0,Out,,b3\nr,s,t7,1,Back,,b3\n")

        def trip(trip_id, sequence, first, last):
            return "".join(f"{trip_id},{i},{stop},{when},{when}\n"
                           for i, (stop, when) in enumerate(zip(sequence, (first, last)), 1))
        stop_times = ("trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                      + trip("t1", "AC", "08:00:00", "08:20:00")
                      + trip("t2", "CA", "08:30:00", "08:50:00")
                      + trip("t3", "AC", "09:00:00", "09:20:00")
                      + trip("t4", "AC", "10:00:00", "10:20:00")
                      + trip("t5", ["Far", "A"], "10:30:00", "10:50:00")
                      + trip("t6", "AC", "11:00:00", "11:20:00")
                      + trip("t7", "CA", "13:30:00", "13:50:00"))
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory, 'gtfs.zip'), Path(directory, 'out.json')
            write_zip(source, {'stops.txt': stops, 'routes.txt': "route_id,route_short_name\nr,1\n",
                               'trips.txt': trips, 'stop_times.txt': stop_times})
            run('build_transit_index.py', source, output)
            patterns = json.loads(output.read_text())['patterns']
        out = next(p for p in patterns if p['headsign'] == 'Out')
        back = next(p for p in patterns if p['headsign'] == 'Back')
        # t1 -> t2 waits 10 minutes; t3 ends the block; t4, t5 and t6 do not turn around.
        self.assertEqual(back['id'], out['next']['id'])
        self.assertEqual(['t1'], out['next']['trips'])
        self.assertEqual(600, out['next']['wait'])
        self.assertEqual(out['id'], back['next']['id'])
        self.assertEqual(['t2'], back['next']['trips'])

    def test_patterns_list_the_trips_that_end_a_bus_s_duty(self):
        stops = "stop_id,stop_name,stop_lat,stop_lon\nA,A,45.00,7.0\nC,C,45.02,7.0\n"
        # b1 runs t1, t2, t3 and then goes home; b2 is a single trip; t9 has no block at all.
        trips = ("route_id,service_id,trip_id,direction_id,trip_headsign,shape_id,block_id\n"
                 "r,s,t1,0,Out,,b1\nr,s,t2,1,Back,,b1\nr,s,t3,0,Out,,b1\n"
                 "r,s,t4,0,Out,,b2\nr,s,t9,0,Out,,\n"
                 "r,other,t5,0,Out,,b1\n")

        def trip(trip_id, sequence, first, last):
            return "".join(f"{trip_id},{i},{stop},{when},{when}\n"
                           for i, (stop, when) in enumerate(zip(sequence, (first, last)), 1))
        stop_times = ("trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                      + trip("t1", "AC", "08:00:00", "08:20:00") + trip("t2", "CA", "08:30:00", "08:50:00")
                      + trip("t3", "AC", "09:00:00", "09:20:00") + trip("t4", "AC", "10:00:00", "10:20:00")
                      + trip("t9", "AC", "11:00:00", "11:20:00")
                      # The same block id under another service is another day's duty.
                      + trip("t5", "AC", "12:00:00", "12:20:00"))
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory, 'gtfs.zip'), Path(directory, 'out.json')
            write_zip(source, {'stops.txt': stops, 'routes.txt': "route_id,route_short_name\nr,1\n",
                               'trips.txt': trips, 'stop_times.txt': stop_times})
            run('build_transit_index.py', source, output)
            patterns = json.loads(output.read_text())['patterns']
        out = next(p for p in patterns if p['headsign'] == 'Out')
        back = next(p for p in patterns if p['headsign'] == 'Back')
        self.assertEqual(['t3', 't4', 't5'], out['last'])
        self.assertNotIn('last', back, "t2 is followed by t3 in the same duty")


class TrainIndexTest(unittest.TestCase):
    def test_weekly_calendar_and_exceptions(self):
        stops = "stop_id,stop_name,stop_lat,stop_lon\nA,A,45.0,7.0\nB,B,45.1,7.0\n"
        calendar = ("service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                    "start_date,end_date\nwk,1,1,1,1,1,0,0,20261012,20261018\n")
        exceptions = "service_id,date,exception_type\nwk,20261014,2\nwk,20261017,1\n"
        times = ("trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                 "x,1,A,25:10:00,25:12:00\nx,2,B,25:30:00,25:31:00\n")
        trips = "route_id,trip_id,service_id,trip_short_name,trip_headsign\nr,x,wk,0123,Foo\n"
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory, 'rail.zip'), Path(directory, 'out.json')
            write_zip(source, {'stops.txt': stops, 'trips.txt': trips, 'calendar.txt': calendar,
                               'calendar_dates.txt': exceptions, 'stop_times.txt': times})
            run('build_train_index.py', source, output)
            trip = json.loads(output.read_text())['trips'][0]
        self.assertEqual(['20261012', '20261013', '20261015', '20261016', '20261017'], trip['dates'])
        self.assertEqual('123', trip['number'])
        self.assertEqual(25 * 3600 + 10 * 60, trip['stops'][0]['arrival'])


class OfflineCityGeometryTest(unittest.TestCase):
    def test_only_walkable_highways_become_footpaths(self):
        self.assertTrue(city.pedestrian_way({'highway': 'residential'}))
        self.assertTrue(city.pedestrian_way({'highway': 'service', 'access': 'private', 'foot': 'yes'}))
        self.assertFalse(city.pedestrian_way({'highway': 'bus_guideway'}))
        self.assertFalse(city.pedestrian_way({'highway': 'via_ferrata'}))
        self.assertFalse(city.pedestrian_way({'highway': 'trunk'}))
        self.assertFalse(city.pedestrian_way({'highway': 'footway', 'foot': 'no'}))
        self.assertFalse(city.pedestrian_way({'building': 'yes'}))

    def test_street_midpoint_is_half_way_by_length_not_the_middle_vertex(self):
        points = [(45.0, 7.0), (45.0, 7.0002), (45.0, 7.21)]
        lat, lon = city.polyline_midpoint(points)
        self.assertAlmostEqual(7.105, lon, places=3)

    def test_area_point_lies_inside_a_concave_area(self):
        ring = [(0.0, 0.0), (0.0, 10.0), (1.0, 10.0), (1.0, 1.0), (10.0, 1.0), (10.0, 0.0), (0.0, 0.0)]
        self.assertTrue(city.inside_polygon(city.polygon_point(ring), ring))

    def test_same_named_streets_far_apart_stay_separate(self):
        near = [(45.000, 7.000, {}), (45.002, 7.000, {})]
        far = [(45.050, 7.080, {})]
        streets = city.cluster_streets(near + far)
        self.assertEqual(2, len(streets))

    def test_end_to_end_places(self):
        def node(i, lat, lon, **tags):
            inner = ''.join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items())
            return f'<node id="{i}" lat="{lat}" lon="{lon}">{inner}</node>'

        def way(i, refs, **tags):
            return (f'<way id="{i}">' + ''.join(f'<nd ref="{r}"/>' for r in refs)
                    + ''.join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items()) + '</way>')
        xml = ('<osm><bounds minlat="44.9" minlon="6.9" maxlat="45.5" maxlon="7.5"/>'
               + node(1, 45.000, 7.000) + node(2, 45.001, 7.000) + node(3, 45.050, 7.080)
               + node(4, 45.051, 7.080) + node(5, 45.0, 7.0005, name='Torino', place='city')
               + node(6, 45.05, 7.0805, name='Moncalieri', place='town')
               + node(7, 45.4, 7.4) + node(8, 45.4, 7.401) + node(9, 45.401, 7.401)
               + way(10, [1, 2], highway='residential', name='Via Roma')
               + way(11, [3, 4], highway='residential', name='Via Roma')
               + way(12, [7, 8]) + way(13, [8, 9])
               + '<relation id="20"><member type="way" ref="12" role="outer"/>'
                 '<member type="way" ref="13" role="outer"/><tag k="type" v="multipolygon"/>'
                 '<tag k="name" v="Parco Rel"/><tag k="leisure" v="park"/></relation></osm>')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'src').mkdir()
            with gzip.open(root / 'src' / 'Turin.osm.gz', 'wb') as stream:
                stream.write(xml.encode())
            write_zip(root / 'src' / 'Turin.osm.mapsforge-osm.zip', {
                'Turin-mapsforge-osm/Turin.map': 'map', 'Turin-mapsforge-osm/README.txt': 'readme'})
            (root / 'transit.json').write_text(json.dumps({'patterns': []}))
            (root / 'train.json').write_text(json.dumps({'trips': []}))
            run('build_offline_city.py', root / 'src', root / 'out', root / 'transit.json', root / 'train.json')
            database = sqlite3.connect(root / 'out' / 'places.sqlite')
            streets = database.execute(
                "SELECT label FROM places WHERE kind = 'street' AND name = 'Via Roma' ORDER BY label").fetchall()
            parks = database.execute("SELECT name FROM places WHERE name = 'Parco Rel'").fetchall()
            database.close()
        self.assertEqual([('Via Roma · Moncalieri',), ('Via Roma · Torino',)], streets)
        self.assertEqual([('Parco Rel',)], parks)


if __name__ == '__main__':
    unittest.main()
