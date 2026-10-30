# Releasing to Google Play

Two GitHub Actions workflows do the work:

- **CI** (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`: unit tests, Android lint, and a debug build (the APK is attached to the run).
- **Release** (`.github/workflows/release.yml`) runs when you push a tag such as `v1.2.0`: it runs the tests, builds a **signed** app bundle (`.aab`, what Play wants) and APK, attaches both to a GitHub release, and, if configured, uploads the bundle to Play's internal track.

```bash
git tag v1.0.0
git push origin v1.0.0
```

A tag with a suffix, like `v1.0.0-rc1`, is published as a pre-release.

For detailed release notes, add `docs/release-notes/v1.0.0.md` before tagging; the workflow puts it at the top of the GitHub release, followed by the generated list of changes. A short `v1.0.0-play.txt` beside it is the text for Play's "What's new" box (500 characters at most), which is pasted in by hand when rolling out the draft.

## Versions

The tag drives the version: `v1.2.3` gives `versionName` `1.2.3` and `versionCode` `10203` (`major*10000 + minor*100 + patch`). Play requires `versionCode` to keep increasing, so always tag a higher version than the last one uploaded.

## One-time setup

### 1. Create an upload key

```bash
keytool -genkeypair -v -keystore upload.keystore -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `upload.keystore` and its passwords somewhere safe (a password manager). **Never commit it** (`*.keystore` is git-ignored). Use Play App Signing: Google holds the real app-signing key and this is only your upload key, so a lost upload key can be reset through Play support.

### 2. Add GitHub secrets

Repository → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 upload.keystore` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `upload` (or the alias you chose) |
| `KEY_PASSWORD` | the key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | *optional* — JSON key for a Play API service account; enables the automatic upload |

### 3. Play Console

1. Create the app (package name `nz.net.jonesie.adventuretimeline`).
2. **The very first release must be uploaded by hand** in Play Console (the API can't create a brand-new app). Download the `.aab` from the first GitHub release and upload it to a test track.
3. For automatic uploads, create a service account (Google Cloud), grant it access in Play Console → Users and permissions, and store its JSON key as `PLAY_SERVICE_ACCOUNT_JSON`. Uploads go to the **internal** track as a **draft**; promote from Play Console. Edit `track` / `status` in `release.yml` when you want to change that.

## Store listing items to prepare

Play will ask for these before the app can go public:

- **Privacy policy URL** — required, because the app handles location, photos and (optionally) contacts.
- **Data safety form** — the app keeps everything on the device; the only network use is map tiles and place-name lookups.
- **Photo and video permissions declaration** — the app asks for `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`; Play wants a justification (finding trip photos in the gallery).
- Sensitive permissions in use: precise location, `ACCESS_MEDIA_LOCATION`, `READ_CONTACTS` (optional, for suggesting companions), `RECORD_AUDIO` (voice notes), `CAMERA`.
- Screenshots, short and full description, a 512×512 icon and a 1024×500 feature graphic.
- Content rating questionnaire and target audience.

## Notes

- R8 shrinking is currently off (`isMinifyEnabled = false`) to keep the release build the same as the one tested on a phone. Turning it on shrinks the app, but needs a test pass and keep-rules for Room, osmdroid and Coil first.
- Building locally without the signing variables produces an unsigned release build, which is fine for checking that it compiles.
