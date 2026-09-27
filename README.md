# Torino Bus Radar

Find your next ride in Torino. See live GTT buses on the map, compare routes
with walking or regional trains, and get directions to your boarding stop.

**[Download the latest APK](https://github.com/nadimsbaihi/Torino-Bus-Radar/releases/latest)**
· Android 8.0 or newer · English and Italian

## Get started

1. Download and install the APK from the latest release.
2. Allow location access and search for a destination.
3. Choose a journey, then open walking directions to your boarding stop in Google Maps.

Your phone's location is the starting point. The app remembers your last
destination and your search preferences.

## Features

- **Live bus map.** See GTT vehicles across the city, with positions refreshed
  every 15 seconds. Select a journey or line to focus the map.
- **Bus and train journeys.** Find direct trips and connections, including
  bus-to-train and train-to-bus options.
- **Your choice of walking.** Favor less walking, a balance, or faster trips
  with more walking. Include or exclude regional trains.
- **Offline search and maps.** Search bundled places, addresses, bus stops,
  and regional stations without a connection or an API key.
- **Walking directions.** Compare a ride with walking, then open Google Maps
  directions to the destination or boarding stop.
- **Shared routes.** Share directions text from another app to find a bus line
  mentioned in it.

## Coverage and live data

The offline map covers Torino and nearby suburbs. Search results and walking
estimates use the data bundled with the app, so some places or addresses may
be missing.

Live bus positions require internet and come from GTT. Delayed positions are
marked, and the app reports feed outages. **Regional train times are scheduled,
not live:** train delays and positions are not available. Connections are
estimates and can change as new bus positions arrive.

Walking directions open in Google Maps, or in your browser if Maps is not
installed. Google Maps may choose a different route from the app's estimate.

## Build and test

Use JDK 17 and Android SDK 35. Open the project in Android Studio and run the
`androidApp` configuration, or build from the command line:

```sh
./gradlew :androidApp:assembleDebug
```

Run the unit tests, build, and Android lint:

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

Tests use Robolectric and cover journey planning, location, offline search,
map rendering, and feed handling. Device testing is still needed for location,
network behavior, and map interaction.

<details>
<summary>Debugging and journey estimates</summary>

- Debug logs use the `MatoLiveBus` tag.
- The map rendering test writes `androidApp/build/reports/offline-map-preview.png`.
- Walking estimates follow a bundled pedestrian street graph at 1.2 m/s.
  When no connected path is available, the app uses an approximate distance
  and omits the walking line.
- Train journeys allow two minutes for boarding. The maximum walk to a boarding
  station is 1.5 km for Less walking, 2.5 km for Balanced, and 5 km for More walking.
- Bus positions older than two minutes are faded and labeled as delayed;
  positions older than five minutes are excluded. Arrival estimates account
  for the age of the position.
- When a trip ID is missing, the app can estimate direction from the line,
  route geometry, and heading. Inferred directions are labeled.

</details>

## Publish a release

Push a version tag such as `v0.1.2`. [GitHub Actions](.github/workflows/release.yml)
runs the tests and lint, builds and signs the APK, then attaches it to a GitHub
Release. Failed validation prevents publishing.

The workflow requires the repository secrets `ANDROID_KEYSTORE_BASE64` and
`ANDROID_KEYSTORE_PASSWORD`. Keep a secure backup of the signing key and
password: future updates must use the same key. The tag sets the version name;
the workflow run number sets the version code.

## Data and credits

- **GTT:** live vehicle positions and static routes and stops.
- **OpenStreetMap contributors:** the offline map, places, addresses, and walking
  network, using a [BBBike Torino extract](https://download.bbbike.org/osm/bbbike/Turin/).
- **Regione Piemonte:** published GTFS timetables for Trenitalia regional trains.
- **Mapsforge and osmdroid:** offline map rendering.

Source details and license links are in the bundled
[NOTICE](androidApp/src/main/assets/offline/NOTICE.txt). The
[manifest](androidApp/src/main/assets/offline/manifest.json) records the map
bounds, source hashes, and file checksums.

<details>
<summary>Offline snapshot and rebuilding the data</summary>

The September 20, 2026 map extract covers latitude 44.941–45.210 and longitude
7.430–7.929. It includes a 15.1 MB vector map, a 17.2 MB walking graph, and a
34.9 MB search database with 159,370 OpenStreetMap entries, 4,313 GTT stops, and
12 regional train stations. Files are compressed in the APK and copied to
private, non-backed-up storage on first use.

To rebuild, download `Turin.osm.mapsforge-osm.zip`, `Turin.osm.gz`, `Turin.poly`,
and `CHECKSUM.txt` from the extract directory into `build/offline-source/`.
Verify the archives against the published checksums, then run:

```sh
python3 tools/build_offline_city.py build/offline-source androidApp/src/main/assets/offline \
  androidApp/src/main/assets/transit_index.json \
  androidApp/src/main/assets/trenitalia_rail_index.json
```

The builder uses only Python's standard library. Rebuild the search index when
either GTFS-derived index changes. Ship updated assets in a new APK; the app
does not download map updates automatically.

The live GTT feed is
`https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx`.

</details>
