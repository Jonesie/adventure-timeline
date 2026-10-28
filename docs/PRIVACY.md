---
layout: default
title: Adventure Timeline — Privacy Policy
permalink: /privacy/
---

# Adventure Timeline — Privacy Policy

_Last updated: 28 October 2026_

Adventure Timeline is a personal travel diary for Android. It is built to keep your information **on your own phone**. This policy explains what the app stores, what it asks permission for and why, and the few times it talks to the internet.

**In short:** the developer does not collect your data. There are no accounts, no advertising, no analytics and no tracking. Your trips, photos, notes and locations stay on your device unless **you** choose to back them up or share them.

## What the app stores on your device

Everything below is created by you or imported by you, and is kept in the app's private storage on your phone:

- **Trips and legs:** titles, dates, notes, ratings, transport types, difficulty and enjoyment ratings, and the places and times of each leg.
- **Routes:** the GPS trail for a trip, if you import it from a Google Maps Timeline export that you supply. The app reads that file on your phone and does not contact Google. It does not record your location in the background.
- **Photos, videos and audio:** photos and videos you take in the app or import are copied into the app's storage. Items you add from your gallery are *linked*, not copied; they stay where they are.
- **Notes and impressions,** with the time and (if allowed) the location you were at when you wrote them.
- **Companions:** names you type. If you allow access to contacts, a companion can be linked to a contact by its Android contact identifier.
- **A location index of your gallery:** to suggest photos taken near a trip's route, the app reads where each gallery photo and video was taken and keeps a small cache of those locations. Nothing about the pictures themselves is copied.
- **A home area:** the app works out roughly where "home" is from where your trips usually start and end (or uses a place you choose, or none). It is used only on your phone, to avoid suggesting photos taken at home for a trip and to leave home off maps you share.
- **Settings:** your theme, sort order, map view, home area and reminder choices.

This data never leaves your phone unless you do one of the things listed under "Backups and sharing". The app's source code is public, so you can check these statements for yourself.

## Permissions and why they are requested

The app only asks when you use a feature that needs it. You can refuse any of them and still use the rest of the app.

| Permission | Why |
|---|---|
| **Camera** | Take photos and videos to add to a trip. |
| **Microphone** | Record voice notes for a trip. |
| **Location** | Tag notes and photos with where you were, show "near me" results, answer "have I been here before?", and centre the map on you. Used only while you use these features. |
| **Photos, videos and audio** | Browse your gallery to add media to a trip, and find photos taken during or near a trip. |
| **Access to location in photos** | Read the location stored inside your photos and videos, to pin them on maps and suggest them for trips. |
| **Contacts (optional)** | Suggest names while you type a companion and link a companion to a contact. Only names and contact identifiers are read. |
| **Internet / network state** | Load map tiles and look up place names (see below). |

## When the app uses the internet

The app works offline. It contacts the internet only for:

1. **Map tiles.** Maps are drawn from OpenStreetMap. To show a map, your phone requests map images from OpenStreetMap's tile servers. Those requests reveal the map area you are viewing and your IP address to OpenStreetMap, under [its own privacy policy](https://osmfoundation.org/wiki/Privacy_Policy).
2. **Place names.** To turn coordinates into place names, and place names into coordinates (for example when you search for a place), the app uses Android's built-in geocoder. Your device's geocoding provider (on most phones, Google) receives those coordinates or place names under its own privacy policy.
3. **Links you tap,** such as the "Buy me a coffee" button, which opens your web browser. The app sends nothing with it.

The app has no developer-run server and sends nothing to the developer.

## Backups and sharing

These happen only when you ask for them:

- **Backup.** "Back up diary" writes a zip file to a location **you** choose (for example Google Drive or your Downloads folder). You control where it goes. The file is not encrypted by the app, so keep it somewhere you trust. "Restore from backup" reads a zip you pick.
- **Sharing a trip.** The Share tab builds a picture or PDF of a trip (title, map, notes, chosen photos) and hands it to the Android share sheet. It goes wherever **you** send it, and what that app does with it is governed by that app's policy. The map and photos can reveal where you have been; the Share tab offers to leave your home area off the map. Photos are re-saved for sharing, which removes the location data embedded in them.

## Keeping and deleting your data

Your data stays until you remove it. You can delete a trip, a photo or a note inside the app, or choose **Settings → Delete all trips**. Uninstalling the app removes everything it stored on the device. Copies you made yourself (backups, shared pictures) are outside the app and stay until you delete them.

## Google

Adventure Timeline is an independent project. It is not made, endorsed or sponsored by Google. "Google" and "Google Maps Timeline" are trademarks of Google LLC, used here only to describe the files the app can import.

## Children

Adventure Timeline is not directed at children and does not knowingly collect information from anyone.

## Changes

If this policy changes, the new version will be published at the same address with a new "last updated" date.

## Contact

Questions about this policy: please [open an issue](https://github.com/Jonesie/adventure-timeline/issues) on the project page.
