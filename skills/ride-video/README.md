# ride-video

Turn a folder of ride footage (DJI action-cam and drone clips, phone videos and photos) into an edited,
YouTube-ready video: opening title card, day chapters, lower-third captions, speed-ups, fades and music.
Pair it with an [Adventure Timeline](../../README.md) trip export and it also places every shot on your route,
names the places, and pins the incident clips.

It is a [Claude Code](https://claude.com/claude-code) skill (Claude does the judgment: reviewing the picks,
captions, incidents) wrapped around plain Python scripts that do the mechanical work with `ffmpeg`. You can
also run the scripts by hand without Claude.

| File | What it does |
|---|---|
| `SKILL.md` | Instructions Claude follows (the skill itself) |
| `scripts/ride_video.py` | Scan, plan, contact sheets, render, optional YouTube upload |
| `scripts/trip_merge.py` | Merge an Adventure Timeline trip export into the plan (places, days, incidents, warnings) |
| `assets/` | The default end-card image (Adventure Timeline splash) |
| `scripts/editor.py` + `editor.html` | Local web editor for the plan: reorder, trim, speed, captions, chapters, transitions, music |

> **Status:** developed and tested on Linux (Ubuntu, NVIDIA GPU, ffmpeg 6.1, Python 3.12). The Windows and
> macOS steps below follow the same code paths and have been written with them in mind, but have not been run
> there. If something fails, see [Troubleshooting](#troubleshooting) and please open an issue.

## Requirements

- **Python 3.9 or newer** (standard library only for everything except YouTube upload).
- **ffmpeg and ffprobe, version 6 or newer**, built with `libx264` and `libfreetype` (the `drawtext` filter,
  used for titles and captions). NVIDIA NVENC is used automatically when available and is much faster, but is
  optional.
- A **bold TrueType font**. The scripts look in the usual places on each OS; set `RIDE_VIDEO_FONT` to use your own.
- Optional: `google-api-python-client` and `google-auth-oauthlib` (only to upload to YouTube).
- Optional: a converter for HEIC photos (`heif-convert`, or export JPG from your phone); ffmpeg cannot read HEIC.
- Optional, for the Claude workflow: [Claude Code](https://claude.com/claude-code) or the Claude desktop app.

## Setup

### Linux (Debian / Ubuntu)

```bash
sudo apt update
sudo apt install ffmpeg python3 fonts-dejavu-core
# optional
sudo apt install libheif-examples          # heif-convert for HEIC photos
python3 -m pip install --user google-api-python-client google-auth-oauthlib   # YouTube upload
```

Fedora / RHEL: enable [RPM Fusion](https://rpmfusion.org/), then `sudo dnf install ffmpeg python3 dejavu-sans-fonts`.
Arch: `sudo pacman -S ffmpeg python ttf-dejavu`.
For NVENC you need the proprietary NVIDIA driver; check with `ffmpeg -hide_banner -encoders | grep nvenc`.

### macOS

```bash
brew install ffmpeg python
# optional
brew install libheif                        # heif-convert for HEIC photos
python3 -m pip install --user google-api-python-client google-auth-oauthlib   # YouTube upload
```

The Homebrew build of ffmpeg includes `drawtext`. There is no NVENC on a Mac, so rendering uses `libx264`
(CPU); expect a render of a few minutes for a 5-10 minute video.

### Windows

In PowerShell (Windows 10/11):

```powershell
winget install Gyan.FFmpeg
winget install Python.Python.3.12
# optional
py -3 -m pip install --user google-api-python-client google-auth-oauthlib   # YouTube upload
```

Close and reopen PowerShell so `ffmpeg` and `python` are on your `PATH`. On Windows use `py -3` (or `python`)
wherever this README says `python3`. Arial Bold (`C:\Windows\Fonts\arialbd.ttf`) is found automatically. NVENC
works with a recent NVIDIA driver. **Symlinks:** the workflow below suggests symlinking your media into one working
folder; on Windows either turn on *Developer Mode* (Settings, Privacy & security, For developers) so non-admin
symlinks work, or simply copy the files.

### Check it works (all platforms)

```bash
ffmpeg -version                                   # 6.x or newer
ffmpeg -hide_banner -filters | grep drawtext      # must list drawtext (Windows: findstr drawtext)
ffmpeg -hide_banner -encoders | grep -E "libx264|nvenc"
python3 --version                                 # 3.9+
```

### Install the skill for Claude

Copy this folder into your Claude skills directory so the folder is named `ride-video`:

| OS | Destination |
|---|---|
| Linux / macOS | `~/.claude/skills/ride-video/` |
| Windows | `%USERPROFILE%\.claude\skills\ride-video\` |

```bash
mkdir -p ~/.claude/skills && cp -r skills/ride-video ~/.claude/skills/
```

Then in Claude Code say "make a ride video from `<folder>`", or run `/ride-video`. Claude calls the scripts from
the installed location (`~/.claude/skills/ride-video/scripts/`).

## Your media

Point it at one folder. Sub-folders let the scripts tell clip types apart:

```
My Ride/
  action/   DJI Osmo Action / GoPro clips (.mp4, .mov)
  drone/    drone clips, with their .SRT sidecars if you have them (GPS)
  phone/    phone videos and photos (JPG/PNG; export HEIC as JPG)
  music.mp3 optional
```

Originals are never modified. Everything the tools make goes in `My Ride/_ridevideo/`
(`plan.json`, cached segments, `final.mp4`, `chapters.txt`, `thumbnail.jpg`).

## Using it with Claude

Give Claude the folder, a title and a short description of the ride (where, when, any incidents or funny
moments), plus a target length and music preference. Claude plans, shows you contact sheets, writes captions and
chapter titles from your facts, and renders. See `SKILL.md` for the full workflow, including music styles.

## Using the scripts by hand

```bash
S=skills/ride-video/scripts     # or ~/.claude/skills/ride-video/scripts

# 1. Plan: analyse and pick highlights (writes My Ride/_ridevideo/plan.json)
python3 $S/ride_video.py plan "My Ride" --title "Labour Weekend" --description "..." --target 420 --music "My Ride/music.mp3"

# 2. Look at the picks (writes contact sheets to _ridevideo/sheets/)
python3 $S/ride_video.py sheet "My Ride"

# 3. Edit plan.json by hand, or use the visual editor (next section)

# 4. Render (segments are cached, so small edits re-render quickly; --force to redo everything)
python3 $S/ride_video.py render "My Ride"
```

Useful `plan` options: `--res 2160` for 4K, `--no-timelapse`, `--max-speed 3`, `--timelapse-len 15`.
Useful `plan.json` settings: `output_dir` (e.g. `".."` copies the finished video, thumbnail and chapters next to
your media as `<title>.mp4`), `output_name`, `transitions` (`auto` / `all` / `none`), `transition_len`,
`music_volume` (default 0.65), `music_duck` (level around incidents, default 0.1).
`outro_image` sets the end card (see below); `outro` is the text on it.

### End card

Every video ends with a 4-second card: the Adventure Timeline splash (`assets/adventure-timeline-splash.png`,
bundled) centred on the app's dark gradient, with the `outro` text underneath. Use your own image with
`plan --outro-image path/to/logo.png` (or `outro_image` in `plan.json`), or `none` for a plain text card.

## The visual editor

```bash
python3 skills/ride-video/scripts/editor.py "My Ride"          # then open http://127.0.0.1:8765/
```

Reorder (drag or arrows), switch shots on or off, trim, set speed and audio level, edit chapter titles and
captions, flag incidents, choose transitions and music, preview any shot with your current unsaved settings, and see
it on the route map. **Save & render** runs the render. It only listens on `127.0.0.1` and makes no external
requests. Use `--port` if 8765 is busy.

## Using an Adventure Timeline trip

In Adventure Timeline, open the trip, go to the **Share** tab and export it. You get a zip (the file name can be
anything) containing `trip.json`, `route-map.png` and `media/`. Then:

1. Unzip it into its **own new folder** and keep it separate from your clips.
2. Build a working folder (symlinks or copies) with `action/`, `drone/` and `phone/`; put the trip's `media/`
   files in `phone/` and the route map alongside them. Include every file from the zip even if its date looks wrong
   (zip and extract times are the export date, not the capture date).
3. Run `plan` as above, then merge the trip:

   ```bash
   python3 $S/trip_merge.py "My Ride" path/to/unzipped/trip.json
   ```

   This places every shot on the route, names the places, sorts shots chronologically, adds a chapter for each
   day, and pins incident clips at full audio. After you start editing, re-run it with `--keep-order` so it only
   refreshes places and warnings and never reorders or retitles.
4. Open the editor. Amber warnings flag shots whose capture date is outside the trip, drone clocks that disagree
   with their GPS, files with no capture date, and duplicates.

If an incident was logged at the wrong place or time, put the correction in
`My Ride/_ridevideo/incident_overrides.json`, for example
`{"002": {"time": "2026-10-24T13:22:22+13:00", "lat": -41.99, "lon": 172.89, "place": "Rainbow"}}`
(the key is the media file's leading number).

## Music

Use a track you own or one from a royalty-free library. Do not use commercial songs: Content ID claims will mute
or block the video. Music is held at a constant level and is only faded down around incident shots (where there
may be voices); final loudness is normalised to -14 LUFS.

With Claude and the ElevenLabs music tool connected, you can generate an instrumental in a chosen style
(adventure, cinematic, chill, rock, electronic, folk or your own description); see "Music styles" in `SKILL.md`.
Generated tracks are AI-generated and cost money on your ElevenLabs account. Save tracks as `music_<style>.mp3`
in the working folder and they appear in the editor's music dropdown.

## YouTube upload (optional)

Uploading publishes under your channel, so it is always an explicit step, and `private` is the default.

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project, enable **YouTube Data API v3**,
   create an OAuth client ID of type **Desktop app**, and download it to
   `~/.config/ride-video/client_secret.json` (Windows: `C:\Users\<you>\.config\ride-video\client_secret.json`).
2. Install the two optional Python packages (see Setup).
3. `python3 $S/ride_video.py upload "My Ride" --privacy private` (or `unlisted`). The first run opens a browser
   to sign in; the token is cached in `~/.config/ride-video/token.json`.

Uploads from a Google API project that has not been through YouTube's compliance audit are forced to private,
so you may need to switch the video to public in YouTube Studio. Setting the thumbnail can fail on unverified
channels; that is non-fatal.

## Troubleshooting

- **"no bold TTF font found"**: set `RIDE_VIDEO_FONT` to a bold `.ttf` (macOS example:
  `export RIDE_VIDEO_FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"`; Windows PowerShell:
  `$env:RIDE_VIDEO_FONT="C:\Windows\Fonts\arialbd.ttf"`).
- **`No such filter: 'drawtext'`**: your ffmpeg was built without freetype; install a full build (Homebrew,
  Gyan.FFmpeg or your distro's package).
- **HEIC photos are skipped or fail**: export them as JPG from the phone, or convert with `heif-convert`.
- **First `plan` takes minutes**: it analyses every second of footage (keyframes only) and caches the result in
  `_ridevideo/analysis/`; later runs are fast.
- **Washed-out colours**: flat or D-Log footage is not colour-graded. HDR/HLG clips are tone-mapped to SDR.
- **Wrong shot order**: chronological order uses the embedded capture time, falling back to file modified time.
  If camera clocks differ, reorder in the editor (array order in `plan.json` is render order).
- **Editor port in use**: `editor.py "My Ride" --port 8800`.
- **Large files**: 1080p is the default; `--res 2160` makes a much larger file and takes longer.
