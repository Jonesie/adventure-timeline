# Travel Diary

A personal, offline-first Android app for recording the trips you take: where you went, how you
got there, who with, plus photos, audio and notes. It is travel-only by design; it does not record
online activity.

All data is stored on the device (Room/SQLite). Nothing is synced anywhere yet.

## Features

- **Trips** with dates, notes, free-text companions and a 1-5 star rating. Rated trips are
  coloured on the diary list (1 star dark red through to 5 stars yellow).
- **Trip screen with four tabs**: Overview (dates, rating, route map, companions), Legs, Media, Notes.
- **Legs** with a transport type (walk, bike, motorbike, car, bus, train, plane, boat, other).
  Change it on a single leg, or for every leg of a type across a whole trip.
- **Route map** (OpenStreetMap via osmdroid) drawn from the recorded GPS trail, with a full-screen view.
- **Photos, videos and audio**: capture with the camera or microphone, pick from anywhere on the phone
  (the picker opens on the trip's dates), or auto-add everything your camera shot during the trip's dates (`DCIM/Camera` only). Shown as
  a thumbnail gallery with full-screen viewing and in-app playback.
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
- Hands-free listening that notices a spoken moment and prompts for a photo, and answering
  "have I been here before?" by voice.
- Matching companions against Google Contacts.

## Notes for developers

- The database has real migrations (see `TravelDiaryDatabase.kt`); bump the version and add a
  `Migration` when changing an entity. A destructive fallback exists only for versions with no
  migration path.
- Route lines come from Timeline's breadcrumb points stored per trip. Trips with no imported
  route fall back to straight lines between leg endpoints.
