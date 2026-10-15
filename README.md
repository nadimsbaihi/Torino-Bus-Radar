# Torino Bus Radar

Find your next ride in Torino. See live GTT buses on the map, compare routes
with walking or regional trains, and get directions to your boarding stop.

**[Download the latest APK](https://github.com/nadimsbaihi/Torino-Bus-Radar/releases/latest)**
· Android 8.0 or newer · English and Italian

## Get started

1. Download and install the APK from the latest release.
2. Allow location access and search for a destination.
3. Choose a journey, then open walking directions to your boarding stop in Google Maps.

Your phone's location is the starting point unless you pick another. The app remembers your last
destination and whether to include trains.

## Features

- **Live bus map.** See GTT vehicles across the city, with positions refreshed
  every 15 seconds while the app is open, with a note of how long ago they
  were fetched. Select a journey or line to focus the map, or tap a bus
  to highlight its whole course and the stops it has yet to reach.
- **What is coming to a stop.** Tap a stop on a highlighted course, or the
  boarding and exit stops of a journey, to see the next few buses heading there
  with their line, direction and estimated minutes. The list updates with each
  refresh while it is open.
- **Bus and train journeys.** Find direct trips and connections, including
  bus-to-train and train-to-bus options. Trains include the SFM suburban lines
  (such as the airport and Venaria line), Regionale and Regionale Veloce.
- **Changes.** A change to a second bus says when that bus is due and how long you
  wait for it, or "tight change" when it is under a minute or likely to be missed.
- **Journey steps, out of the way of the map.** Your journey opens as a short
  list of steps (walk, board, get off, walk) in a panel at the bottom, so the
  map stays in view. Pull the panel up for every step, or swipe it down to
  close it. The back button closes things one level at a time: the expanded
  panel, a highlighted bus, then the journey, before it leaves the app.
  The refresh button beside the close button fetches fresh bus positions and
  plans the journey again.
- **Approach alerts.** Tap Notify me to get a notification when your bus is
  300, 500, 700, or 1000 metres from your boarding stop. The app remembers your
  choice.
- **Leave-by time.** The walk to your boarding stop says when to set off, such
  as "leave in ~3 min", or "leave now" when the bus is about as far away as
  your walk.
- **Start somewhere else.** Once a journey is on screen, its panel says "From:
  My location". Tap it to plan from a saved place or any place you search for,
  or use the arrows to swap start and destination and plan the way back. The
  journey, the walking estimates and the directions all start from there, and
  the approach alert is off, since you are not at that start. Closing the
  journey goes back to your location.
- **Other ways to go.** The app picks the journey that best weighs time against
  walking. When another journey has fewer changes, arrives sooner, or walks less,
  a button beside it shows that one instead. Include or exclude regional trains.
  A ride that takes more than 1.5 times the walk and 15 minutes longer, such as
  tomorrow's first train, is not offered; when no ride is left, the app shows the walk.
- **Offline search and maps.** Search bundled places, addresses, bus stops,
  and regional stations without a connection or an API key.
- **Dark mode.** The app and its offline map follow your phone's dark-mode
  setting. Tap the half-moon button at the top to hold it light or dark instead.
- **Saved places.** Tap the bookmark beside the search box to give any
  destination a name of your own, such as "Mum's" or "Work". Saved names come
  first in search, and an empty search box lists them, followed by the last few
  places you went to. Tap the bookmark again to rename or remove one. Saved and
  recent places stay on your phone.
- **Home-screen shortcuts.** Your first four saved places appear when you press
  and hold the app icon; tap one to plan a journey there.
- **Go anywhere from the map.** Press and hold on the map to plan a journey to
  that spot, even where the bundled data has no address. Bookmark it to keep it.
- **Open locations from other apps.** Share a place from a maps app or a link
  to Torino Bus Radar and it plans a journey to that spot.
- **Walking directions.** Compare a ride with walking, then open Google Maps
  directions to the destination or boarding stop. Maps opens only when you tap
  a directions button, and the button stays so you can reopen the route.
  Every walking step in the journey, such as the walk from where you get off
  one bus to the next stop, has its own directions button for just that leg.
- **Shared routes.** Share directions text from another app to find a bus line
  mentioned in it.

## Coverage and live data

The offline map covers Torino and nearby suburbs. Search results and walking
estimates use the data bundled with the app, so some places or addresses may
be missing.

Live bus positions require internet and come from GTT. Delayed positions are
marked, and the app reports feed outages. If most live buses stop matching the
bundled timetable, which happens when GTT publishes a new one, the app tells you
once that its data may be out of date and should be updated. **Regional train times are scheduled,
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
  station is 2.5 km.
  Timetables use Europe/Rome time; once the day's trains have gone, the first
  trains tomorrow are offered, marked "(+1)".
- Bus arrival estimates add 18 s per stop to the drive between stops. Distances
  follow the line's road shape: stop to stop along the shape, and from a bus to the
  next stop from where it sits on the shape. A line without a shape that fits its
  stops falls back to straight lines between stops, 15% longer. The drive
  uses speeds learned from the live feed: between two refreshes, how far each bus got
  along its line, with the time at stops taken out. Standing, jittering,
  off-line, turned-around, and implausible samples are dropped. The app keeps a
  robust running average per line and part of the day (night, morning peak, midday,
  evening peak, evening), pulled towards the city-wide figure for that part of the day while a
  line has few samples, and towards 5.5 m/s (20 km/h between stops, about
  16 km/h with stops) while the city has few. Old samples fade with a two-week
  half-life, and the figures live in a small private file, `bus_speeds.txt`.
  A walker needs 15% slack on the walk to a stop and 20 s to change buses.
- Catching a bus is a chance, not a yes or no. An arrival is treated as normally
  distributed around the estimate, with a spread of at least 20 s that grows by 10% of
  the time still to wait, by half a second per second of position age, and by a minute
  for a bus that has still to turn around; the walker's pace adds 10% of the walk.
  Each boarding and each change gets the chance that its margin holds, and the
  journey's score (not its travel time) is charged 7 min times the chance of missing,
  so a slim connection loses to a safe one that is a little slower. The panel then adds
  "Missed it? Next bus ..." for the next vehicle that reaches the same boarding stop,
  and the same exit, after the planned one.
- Journeys come from one round-based search (in the manner of RAPTOR) over every live bus
  and the day's regional trains. Round k holds the best ways to reach each stop after k
  vehicles; each round boards every run it can catch (a bus must still be ahead, with
  walk plus 15% slack and 20 s to change, or 60 s after a train; a train needs 2 min),
  then walks on to stops within 350 m (500 m between stop and station). The planner keeps
  a few ways per stop rather than one, so a slower way with less walking or a safer
  connection survives. The panel can show a bus, two buses, or a bus and a train in either
  order; the search itself handles three vehicles, but the panel stops at two. Trains
  more than three hours away are ignored and tomorrow's are only offered by the
  train-only search. Beside the best journey, chips offer the one with fewest changes, the
  soonest, and the one with least walking, when they differ.
- Bus positions older than two minutes are faded and labeled as delayed;
  positions older than five minutes are excluded. Arrival estimates account
  for the age of the position.
- When a trip ID is missing, the app can estimate direction from the line,
  route geometry, and heading.

</details>

## Publish a release

Push a version tag such as `v0.2.1`. [GitHub Actions](.github/workflows/release.yml)
runs the tests and lint, builds and signs the APK, then attaches it to a GitHub
Release. Failed validation prevents publishing.

The workflow requires the repository secrets `ANDROID_KEYSTORE_BASE64` and
`ANDROID_KEYSTORE_PASSWORD`. Keep a secure backup of the signing key and
password: future updates must use the same key. The tag sets the version name;
the workflow run number sets the version code.

## Data and credits

- **GTT:** live vehicle positions and static routes, stops, and route shapes.
- **OpenStreetMap contributors:** the offline map, places, addresses, and walking
  network, using a [BBBike Torino extract](https://download.bbbike.org/osm/bbbike/Turin/).
- **Trenitalia:** train timetables from the national NeTEx feed (CCISS National Access Point),
  converted to GTFS by [Clément Desouche](https://github.com/deryclem/trenitalia-gtfs), CC BY 4.0.
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
27 train stations. Files are compressed in the APK and copied to
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

The bus index, including the road shapes drawn when a bus is highlighted, comes from
GTT's GTFS feed (`https://www.gtt.to.it/open_data/gtt_gtfs.zip`):

```sh
python3 tools/build_transit_index.py gtt_gtfs.zip androidApp/src/main/assets/transit_index.json
```

The index also records, from the timetable's blocks, which run a bus usually does after
another, so a bus about to finish its line counts as the one coming back from the terminus.
It also marks the last trip of each block: a bus that finishes one is going to the depot, and
the app stops showing and planning with it until the feed gives it another trip. The feed has no
out-of-service flag, so a bus the feed gives no trip at all is also left out when it is more
than 500 m from every road of its line. A bus with no trip that is simply waiting at its
terminus between runs stays.
Trip IDs change when GTT publishes a new feed, and the app matches live buses to routes by
trip ID, so refresh this index when live buses stop matching it.

The train index comes from the Trenitalia GTFS
(`https://github.com/deryclem/trenitalia-gtfs/raw/main/gtfs-trenitalia.zip`, updated weekly).
Only local trains (SFM, Regionale, Regionale Veloce) that call at two or more stations in the
area are kept, and only those stations. Its timetable ends at the next timetable change
(December 12, 2026); rebuild it after that, then swap the stations in the search database
without rebuilding the map:

```sh
python3 tools/build_train_index.py gtfs-trenitalia.zip \
  androidApp/src/main/assets/trenitalia_rail_index.json 44.92,7.41,45.23,7.95
python3 tools/build_offline_city.py --stations androidApp/src/main/assets/offline \
  androidApp/src/main/assets/trenitalia_rail_index.json
```

The live GTT feed is
`https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx`.

</details>
