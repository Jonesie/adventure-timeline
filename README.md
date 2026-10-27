# Travel Diary

A personal, offline-first Android app for recording the trips you take: where you went, how you
got there, who with, plus photos, audio and notes. It is travel-only by design; it does not record
online activity.

All data is stored on the device (Room/SQLite). Nothing is synced automatically; use Settings to back up.

## Features

- **Home screen**: the app opens on the all-trips map; a bottom bar switches to the list of trips.
  The map keeps its position, zoom and filters while the app is open.
- **Trip stats**: totals, distance by transport and by year, rating spread and highlights.
- **Trips** with dates, notes, free-text companions and a 1-5 star rating. Rated trips are
  coloured on the diary list (1 star dark red through to 5 stars yellow). The list also shows icons for
  trips that have photos/videos or voice notes.
- **Sort** the trip list by date or rating, ascending or descending (unrated trips always last by rating).
- **Search** the diary by title, by place name, or near your current location. Place and location
  searches list trips whose route, stops, notes or media came within 1, 5 or 25 km.
- **Trip screen with four tabs**: Overview (dates, rating, route map, companions), Legs, Media, Notes.
- **Legs** with a transport type (walk, bike, motorbike, car, 4WD, bus, train, plane, boat, other),
  plus a difficulty (1-5) and an enjoyment (1-5 stars). Edit a single leg, or change the transport for
  every leg of a type across a whole trip.
- **Route map** (OpenStreetMap via osmdroid) drawn from the recorded GPS trail, with a full-screen view.
  Rated legs are coloured by difficulty (green easiest to purple hardest), with a legend.
  An optional "Waypoints" toggle (off by default) marks each stop with its name and time.
- **Photos, videos and audio**: capture with the camera or microphone, pick from the phone
  (the picker opens on the trip's dates; items are linked in place, not copied), or auto-add everything your camera shot during the trip's dates (`DCIM/Camera` only). Shown as
  a thumbnail gallery with full-screen viewing and in-app playback.
- **All-trips map**: every trip's route on one map, coloured by star rating, with tap-to-open, a
  Waypoints toggle and transport-type filter chips. Stretches the phone never tracked are left
  unjoined; flights with no trail are shown as a dashed straight line.
- **Photo pins**: photos and videos with a location (read from their own metadata, once, in the
  background) are pinned on trip maps (on by default) and on the all-trips map (off by default);
  tap a pin to open it.
- **Centre on my location** button on every map.
- **Photo viewer**: pinch/double-tap to zoom, and the place a photo or video was taken (from its
  metadata) with a tap to open it in a maps app. Gallery items need the "location info in photos"
  permission to reveal it.
- **Find photos by location**: the gallery's photo locations are indexed once (in the background),
  so Add from phone has a "Near route" mode (1/2/5 km, optionally only photos not in any trip) and the
  Media tab suggests unassigned photos taken along the route. A home area (found from where trips
  start and end, or set in Settings) is left out so photos taken around home aren't suggested for every trip.
- **Share tab** on each trip: compose a title, the framed route map, a caption, companions (optional),
  notes and up to 7 photos, then share it as a high-resolution picture or a PDF through the phone's
  share sheet. The route is redrawn sharp over the map tiles so it stays readable when zoomed. The home area can be hidden from the shared map.
- **Themes**: light, dark, or follow the system (Settings → Appearance). Map tiles are inverted in dark mode.
- **Settings**: back up the whole diary (trips, routes, notes, attached files) to one zip you save
  anywhere, e.g. Drive, and restore from it (restore replaces the current diary). "Free up space"
  swaps duplicate imported copies for links to the originals in the gallery. Also holds "Delete all trips".
  Backups are built locally, checked, then copied to the chosen place, and the dialog shows the
  expected size. Photos/videos from the gallery stay linked (not copied) unless you tick the
  camera-roll option, which can need tens of GB free. A reminder card appears on the diary list when
  a backup is due (7/14/30 days or off).
- **Companions matched to contacts**: while typing a companion, names from the phone's contacts
  (including synced Google contacts) are suggested, and companions are linked to the matching contact
  so tapping one opens their contact card. Settings can link existing names in one go. Only names
  and Android's contact IDs are read; nothing leaves the phone.
- **Notes**: quick timestamped impressions, tagged with your location when available.
- **Google Timeline import**: pulls the trips Google has already detected, with their legs, transport
  and route. Re-importing updates existing trips in place and keeps your manual changes
  (hand-edited transport and titles).
- **"Have I been here before?"**: tap the compass icon to list earlier notes and media recorded
  within about 1 km of where you are now.
- **Place names**: looked up with Android's Geocoder. Local places show just the town or suburb;
  places outside your home country also show the country.

## Importing from Google Timeline

Google no longer exposes Timeline through an API, and Google Takeout only contains your Timeline
settings, not the trips. The data has to be exported from the phone itself:

1. On the phone: **Settings → Location → Location services → Timeline → Export Timeline Data**.
   This saves a `Timeline.json` file to the device.
2. In the app, tap the cloud-download icon on the diary list, choose that file, and tick the trips
   you want. Google's own trip detection supplies the trip boundaries.

The export is large (tens of MB for a couple of years), so it is read as a stream rather than
loaded into memory.

## Building

Requirements: Android SDK (compileSdk 36), JDK 17 or newer, and the Gradle wrapper that is included.

```bash
# point Gradle at your SDK (this file is git-ignored)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Stack: Kotlin, Jetpack Compose (Material 3), Room, osmdroid. `minSdk` is 26. Play Services is
deliberately not required, so the app can run on an older phone that does not have it.

## Network use

The app works offline. It only uses the network to fetch map tiles and to look up place names.

## Status and not built yet

- Syncing to a private, self-hosted store (planned instead of a third-party cloud).

## Notes for developers

- The database has real migrations (see `TravelDiaryDatabase.kt`); bump the version and add a
  `Migration` when changing an entity. A destructive fallback exists only for versions with no
  migration path.
- Route lines come from Timeline's breadcrumb points stored per trip. Trips with no imported
  route fall back to straight lines between leg endpoints.

## Builds and releases

Pull requests are built and tested by GitHub Actions (`.github/workflows/ci.yml`). Pushing a tag such as
`v1.0.0` builds a signed app bundle and publishes it (`.github/workflows/release.yml`); see
[docs/RELEASING.md](docs/RELEASING.md) for the one-time signing and Play Console setup.
Run the tests locally with `./gradlew testDebugUnitTest`.
