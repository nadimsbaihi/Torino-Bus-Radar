# MATO Live Bus for Android

Native Android app for finding the nearest live GTT vehicle in Torino.

## What works

- reads the official GTT GTFS-Realtime vehicle-position feed
- obtains a recent phone location (including approximate permission) before planning
- opens a full-screen neighborhood map, with destination search at the top
- shows journey details in a dismissible card instead of a bottom menu
- displays all available buses on a simplified offline Torino map, then focuses
  on the selected journey's buses or the selected line; closing the selection
  restores all buses. The basemap has no POI icons and fewer street labels
- searches bundled places and addresses locally, without a geocoding API
- searches live routes for direct and catchable one-transfer journeys
- matches missing or unknown trip IDs by line, nearby route geometry and available
  heading; inferred directions are labeled and ambiguous opposite directions rejected
- accepts rides that get closer to the destination and shows the remaining walk
- uses optimistic boarding estimates: a brisk 2 m/s walk and no extra boarding
  buffer, including transfers; ordinary walking estimates remain at 1.35 m/s
- measures walking legs over a bundled pedestrian street graph, including the
  walk to the boarding stop and between transfer stops
- shows direct options while checking connections and reuses recently fetched positions
- compares scheduled Trenitalia Piemonte regional trains with live GTT journeys
- refreshes live positions every 15 seconds
- compares the full bus ETA with a walking estimate and opens real walking
  navigation in Google Maps
- opens Google Maps walking navigation to the selected boarding stop
- restores the last destination and replans when the app is reopened
- appears in Android's Share sheet and detects route numbers in shared
  directions text (for example, “Bus 10” or “Linea 10”)
- clearly reports a GTT feed outage instead of displaying simulated vehicles

## Build

Open this directory in Android Studio, install Android SDK 35 if prompted, and
run the `androidApp` configuration on a device or emulator running Android 8+.

```sh
./gradlew :androidApp:assembleDebug
```

## Validation

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

The JVM tests cover location permission/freshness, offline search, pedestrian
street routing, actual vector tile rendering, feed caching, cancellation,
transfer timing and missing-trip direction matching, delayed-position arrival
estimates and bus marker visibility. They use
Robolectric; real-device location, network latency, and map interaction still
need device testing. The rendering test writes `androidApp/build/reports/offline-map-preview.png`.
Debug logs under
`MatoLiveBus` report location, geocoding, feed, and planning durations separately.

## Pedestrian routing

MATO uses a local estimate to decide whether walking is likely faster. Choosing
“Walk in Maps” sends the destination coordinates to Google Maps in
walking-navigation mode, where the actual pedestrian streets and live Maps
directions are calculated. If the Google Maps app is unavailable, the same
walking request opens in the browser.

For a selected bus or train, “Walk to boarding stop in Google Maps” opens walking
navigation to that stop. Local walking estimates follow pedestrian streets in
the bundled OpenStreetMap extract, and the dashed walking line draws that same
street path. Where no connected path is available, the app falls back to an
approximate distance and omits the walking line. Google Maps may choose a
different pedestrian route.

## Data

Live positions come from GTT's public GTFS-Realtime feed:

`https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx`

Static route and stop data comes from GTT GTFS. The bundled OpenStreetMap vector
extract is rendered locally through Mapsforge and osmdroid, with visible
attribution. Map display and destination search require neither internet nor an
API key. Live bus positions still require internet; downloading a map does not
make stale positions current. Missing trip IDs can use estimated direction matching;
positions up to five minutes old remain available, with positions over two minutes
old shown faded and labeled as delayed. Arrival estimates subtract the position
age; positions older than five minutes are excluded. Walking navigation
still opens Google Maps.

The September 20, 2026 [BBBike Torino extract](https://download.bbbike.org/osm/bbbike/Turin/)
covers latitude 44.941–45.210 and longitude 7.430–7.929. It contains a 15.1 MB
vector map, a 34.0 MB SQLite index with 159,370 places, addresses and streets,
and a 17.2 MB pedestrian graph
(decimal MB). These files are compressed in the APK and copied into private,
non-backed-up app storage on first use. Search coverage depends on OpenStreetMap:
Comala is present, but Via Chiesa della Salute number 1 is absent in this snapshot.
The phone's location remains the journey origin.

To rebuild the bundled files, download `Turin.osm.mapsforge-osm.zip`,
`Turin.osm.gz`, `Turin.poly`, and `CHECKSUM.txt` from the extract directory into
`build/offline-source/`, verify the archives against the published checksums,
then run:

```sh
python3 tools/build_offline_city.py build/offline-source androidApp/src/main/assets/offline
```

The builder uses only Python's standard library. The bundled `offline/manifest.json`
records file hashes and source metadata; `offline/NOTICE.txt` contains source
attribution and the OpenStreetMap ODbL license link. Updating the assets and
rebuilding the APK updates the city snapshot; no automatic map download is used.

Trenitalia regional trains are sourced from Regione Piemonte's published GTFS
timetable. They are scheduled results only; this app does not claim that train
positions or delays are live.
