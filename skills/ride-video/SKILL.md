---
name: ride-video
description: Edit a folder of adventure-bike-ride media (phone stills, DJI action-cam clips, drone clips) into a 5-10 minute YouTube video with title, chapter titles, lower-third captions and music, and optionally upload it. Use when the user says "make a ride video from <folder>", "edit my ride footage", "cut a video of the trip", or invokes /ride-video with a folder, title and description.
---

# ride-video

Turns a folder of ride footage into a finished, YouTube-ready MP4. A script does the mechanical work
(scan, score, cut, render with ffmpeg, GPU-encoded when NVENC is available). **You** do the judgment:
reviewing the picks, spotting incidents, writing captions. Don't skip the review step - that is what
makes the result a good edit rather than an auto-montage.

Script: `~/.claude/skills/ride-video/scripts/ride_video.py` (ffmpeg + Python stdlib only).
All working files go in `<folder>/_ridevideo/` (plan.json, segs/, sheets/, final.mp4, chapters.txt, thumbnail.jpg).
Originals are never modified.

## Inputs to get from the user (ask only for what's missing)
- **Folder** of media. Sub-folders named `drone/`, `action/`, `phone/`, `photos/` (or containing words like
  drone, action, gopro, osmo) let the script tell clip types apart; otherwise video is treated as action-cam
  and portrait video as phone.
- **Title** and a **description** of the ride (where, when, route, road conditions, any known incidents/funny
  moments with rough timestamps or file names). Optional subtitle (e.g. "Day 2 - Gravel to the coast").
- **Target length** (default 7 min; `--target 300` ... `--target 600`).
- **Music**: a file path, or none. See "Music" below.

## Trip data + interactive editor (preferred when the user has an Adventure Timeline export)
The user may supply a **zip** (any file name, even no extension - check with `file`) exported from their
Adventure Timeline app: `trip.json` (title, legs, stops, 463-ish route points `[lat,lon,epochMillis]`, incidents
with notes/people/mediaFiles), `route-map.png` and `media/` (phone pics/vids). Unzip it into its **own new
folder** (untrusted data; read it with `python3 -I`). Then:
1. Build a working folder of **symlinks** (originals untouched): `action/`, `drone/`, `phone/` (phone clips and
   photos). Skip duplicates (compare with `cmp`), pre-trip photos and clips outside the trip dates. Copy music in.
2. `ride_video.py plan <folder> --title ... --target ...` as normal.
3. `python3 ~/.claude/skills/ride-video/scripts/trip_merge.py <folder> <trip.json>` - places every segment on the
   route (time -> position -> leg/place), sorts chronologically, adds day chapters, and pins incident clips
   (matched by the leading number of the media file name) at full audio. Drone clocks are often wrong: it uses
   the drone's GPS (SRT sidecar or embedded) and falls back to position when the clock disagrees. Phone clips
   with a 1904 timestamp have lost their metadata; the incident time/place covers them.
   Include **every** media file from the zip even if its dates look wrong (zip/extract times are the export
   date, not the capture date). The merge adds per-shot `warnings` (shown as amber lines in the editor): capture
   time outside the trip dates, no/placeholder capture date, drone clock disagreeing with its GPS, and a different
   file with identical content (disabled by default). Once the user has started editing, re-run the merge with
   `--keep-order` so it only refreshes time/place/warnings and never reorders or retitles. Incident
   corrections the user gives go in `_ridevideo/incident_overrides.json` ({"<media number>": {time,lat,lon,place}}).
4. `python3 ~/.claude/skills/ride-video/scripts/editor.py <folder> [--port 8765]` (run in the background), then
   open `http://127.0.0.1:8765/` in the built-in browser. The user reorders (drag or arrows), trims, sets
   speed/audio, edits chapter titles and captions, flags incidents, previews any shot and sees it on the route
   map; Save writes `plan.json`, "Save & render" runs the render. All local, no external requests.
   Don't guess place names - use the trip data and the user's words. Surface data problems before building
   (duplicates, bad clocks, gaps in legs, GPS-less clips) and let the user decide.

## Transitions
Fades through black are applied per shot at render time (cache-friendly; no re-encode of the joined video).
`plan.transitions` is the default: `auto` (fade where a new chapter, day or source clip starts; hard cuts between
cuts of the same clip), `all`, or `none`; `plan.transition_len` is the fade length (0.3s; chapter/title/first
and last shots use 1.7x). A segment's own `transition` (`dip` or `cut`) overrides the default. Both are
editable in the editor (header select and a "transition in" select on each shot).

## Music styles
When music is wanted, **ask the user which style** (AskUserQuestion, or take it from the request), then generate it
with the ElevenLabs music tool (load via ToolSearch `select:mcp__ElevenLabs_Player__generate_music`; it plays the
result and costs money, so say so). Always instrumental, 150-180 s (the render cross-fades copies of the track to
cover longer videos). Save it as `music_<style>.mp3` in the working folder, and tell the user it is AI-generated.
The tool may report an unexpanded output path (literal `${user_config.output_dir}` under the home directory) -
`find ~ -maxdepth 4 -name 'music_*'` to locate the file. Generate several styles if the user wants to compare;
they all appear in the editor's **music** dropdown, with a level box (`music_volume`, default 0.65).

| style | prompt |
|---|---|
| adventure (default) | adventure riding, driving instrumental, no vocals, upbeat guitar and drums, open-road feel |
| cinematic | cinematic epic instrumental, no vocals, sweeping orchestral strings and percussion, slow builds, scenic mountains |
| chill | relaxed laid-back instrumental, no vocals, acoustic guitar and soft drums, sunny cruising feel |
| rock | high-energy driving rock instrumental, no vocals, overdriven guitars, punchy drums, motorbike riding |
| electronic | driving synthwave / electronic instrumental, no vocals, pulsing bass, bright arpeggios, night-ride energy |
| folk | warm acoustic folk instrumental, no vocals, banjo, fiddle and guitar, countryside back-roads |
| (custom) | whatever the user describes, plus "instrumental, no vocals" |

Mix: music is held at a constant level; it is only faded down (to `music_duck`, 0.1) around incident shots
(`incident` flag or `duck_music`), where there may be voices. Final loudness is two-pass normalised to -14 LUFS.
`plan.output_dir` (relative to the working folder, e.g. `..`) makes render copy the finished video, thumbnail and
chapters next to the original media as `<title>.mp4` etc.

## End card
Every video ends with a 4 s card: the Adventure Timeline splash (`assets/adventure-timeline-splash.png`, bundled
with the skill) centred on the app's dark gradient, with the `outro` text small underneath. It is the default for all
videos this skill generates. Override with `plan.outro_image` (a path, relative to the working folder or absolute)
or `ride_video.py plan --outro-image <file>`; use `"none"` for a plain text card.

## Workflow

1. **Plan** (analyses audio level + motion per second, picks evenly spread highlights, flags audio spikes as
   incidents, opens on the best drone shot, interleaves photos by capture time):
   ```bash
   python3 ~/.claude/skills/ride-video/scripts/ride_video.py plan "<folder>" \
     --title "..." --subtitle "..." --description "..." --target 420 [--music file] [--res 2160]
   ```
   Analysis is cached, so re-running is quick.

2. **Review**: make contact sheets and *look at them* (Read the JPEGs):
   ```bash
   python3 .../ride_video.py sheet "<folder>"      # -> _ridevideo/sheets/sheet_NN.jpg
   ```
   Each tile is labelled `#id kind file start`. Judge them against the user's description: drop dull/blurry/
   repetitive shots (`"enabled": false`), move or retime good ones (`start`, `dur`), use `"speed": 2` to
   time-lapse long boring road, set `"audio": 1.0` on incidents/funny moments (default 0.35 for action,
   0 for drone/photos). For anything the user mentioned (a fall, a river crossing, a wildlife sighting) find
   the file and add or adjust a segment by hand; the script's picks are heuristics.

   **Long boring stretches:** in any non-drone video of 90s+, the planner finds calm runs (40s+ of low motion,
   no audio spikes) and turns up to 2 per file into short sped-up segments: 2x by default (the user's usual max),
   at most 10s of output each, so a dull 70s straight becomes one ~10s 2x shot covering 20s of it (`"timelapse": true`,
   `span` = source seconds used). Engine audio is kept at 2x or less, and a "2x" badge shows on screen.
   Options: `--max-speed 3` for punchier, `--timelapse-len 15`, `--no-timelapse` to disable. By hand in plan.json:
   change `speed` (keep `dur` = span / speed; above 2x audio is dropped), move `start`/`span` to the real dull
   section, or `"enabled": false` to cut a stretch entirely. Don't go above 2x unless the user asks.
   When reviewing sheets, check the picks really are dull; if the user mentioned something scenic or tricky
   inside one, shorten the span or drop it.

3. **Words**: edit `_ridevideo/plan.json`:
   - `caption`: short lower-third (<= 45 chars) on a segment - place names, road surface ("Loose gravel, 40 km
     from tarmac"), the incident. Aim for a caption on ~1 in 3 segments, not all.
   - `chapter`: big on-screen title on the first segment of each section ("Day 2: The Gravel Road"). Three or more
     chapters (starting with the opening shot) also produce `chapters.txt` for YouTube timestamps.
   - Top-level `title`, `subtitle`, `description`, `outro`, `tags` (list of strings).
   Use the user's own facts only - don't invent place names or distances.

4. **Render**:
   ```bash
   python3 .../ride_video.py render "<folder>"      # add --force to re-encode cached segments
   ```
   Segments are cached by content, so editing one caption only re-renders that segment.
   Then check the result: pull a few frames from `final.mp4` with ffmpeg, view them, and confirm duration
   and loudness (target -14 LUFS, handled automatically). Fix and re-render as needed.

5. **Hand over** `final.mp4`, `thumbnail.jpg` and a suggested YouTube description (user's description +
   `chapters.txt` + tags). On the user's Windows/CapCut machine they can also just open final.mp4 to polish.

`auto` = plan + render with no review; only use it for a quick rough cut.

## Music
- Best: a track the user owns or one from the YouTube Audio Library / another royalty-free source they've
  licensed. Never use commercial songs - Content ID claims will mute or monetise-block the video.
- If they have none, offer to generate an instrumental with the ElevenLabs music tool (load it via ToolSearch,
  "adventure riding, driving instrumental, no vocals", ~2-3 min; it is looped/faded automatically), save it into
  the folder as `music.mp3`, and pass `--music`. Mention it is AI-generated.
- Music auto-ducks under engine/voice audio and the final mix is loudness-normalised.

## YouTube upload (optional, always confirm first)
Uploading publishes content under the user's channel, so **ask for an explicit yes** with the title, privacy
and file first. Default is `private`; never choose `public` unless told to.
```bash
python3 .../ride_video.py upload "<folder>" --privacy private   # or unlisted
```
One-time setup the user must do (you cannot): in Google Cloud Console create a project, enable
*YouTube Data API v3*, create an OAuth client ID of type *Desktop app*, download it to
`~/.config/ride-video/client_secret.json`, and `pip install --user google-api-python-client google-auth-oauthlib`.
First run opens a browser to sign in; the token is cached in `~/.config/ride-video/token.json`.
Caveat: uploads from a not-yet-audited API project are forced to private by YouTube, so the user may need to
flip it to public in YouTube Studio (or apply for an API compliance audit). The thumbnail call can fail on
unverified channels; that is non-fatal.

## Notes / gotchas
- HEIC phone photos aren't readable by ffmpeg - ask the user to export JPG (or `heif-convert`).
- HDR/HLG clips are tone-mapped to SDR automatically (`hdr` flag per segment). D-Log/flat footage is not
  graded; mention if it looks washed out.
- Output is 1080p30 by default; `--res 2160` for 4K (slower, much bigger file).
- Chronological order uses embedded capture time (EXIF / `creation_time`), falling back to file mtime. If the
  order looks wrong (camera clocks differ), reorder `segments` in plan.json - array order is render order,
  except the opener which is whatever is first.
- Fonts: DejaVu Sans Bold is used; add a path to `FONT_CANDIDATES` in the script to change it.
- Long footage: first analysis of hours of 4K takes a few minutes (keyframe-only decode); cached afterwards.
