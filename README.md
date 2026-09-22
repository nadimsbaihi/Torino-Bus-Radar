# MATO Live Bus for Android

Native Android app for finding the nearest live GTT vehicle in Torino.

## What works

- reads the official GTT GTFS-Realtime vehicle-position feed
- requests the phone's location and sorts matching vehicles by distance
- displays vehicles on an OpenStreetMap map
- searches all live routes for the fastest catchable journey
- compares scheduled Trenitalia Piemonte regional trains with live GTT journeys
- refreshes live positions every 15 seconds
- compares the full bus ETA with a walking estimate and opens real walking
navigation in Google Maps
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

## Pedestrian routing

MATO uses a local estimate to decide whether walking is likely faster. Choosing
“Walk in Maps” sends the destination coordinates to Google Maps in
walking-navigation mode, where the actual pedestrian streets and live Maps
directions are calculated. If the Google Maps app is unavailable, the same
walking request opens in the browser.

## Data

Live positions come from GTT's public GTFS-Realtime feed:

`https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx`

Static route and stop data comes from GTT GTFS. OpenStreetMap tiles are shown
through osmdroid.

Trenitalia regional trains are sourced from Regione Piemonte's published GTFS
timetable. They are scheduled results only; this app does not claim that train
positions or delays are live.
