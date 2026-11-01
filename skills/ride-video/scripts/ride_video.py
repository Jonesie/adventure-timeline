#!/usr/bin/env python3
"""ride_video.py - turn a folder of ride media into an edited YouTube video.

Subcommands (all work inside <folder>/_ridevideo/):
  plan    DIR  scan + analyse media, write plan.json (the edit decision list)
  sheet   DIR  contact sheets of the planned segments, for visual review
  render  DIR  render plan.json -> final.mp4 (+ chapters.txt, thumbnail.jpg)
  upload  DIR  upload final.mp4 to YouTube (private by default)
  auto    DIR  plan + render in one go

Only needs ffmpeg/ffprobe and the Python standard library (upload needs
google-api-python-client + google-auth-oauthlib).
"""
import argparse, datetime, hashlib, json, math, os, re, shutil, subprocess, sys
from pathlib import Path

VIDEO_EXT = {'.mp4', '.mov', '.m4v', '.mkv', '.avi'}
IMAGE_EXT = {'.jpg', '.jpeg', '.png', '.webp'}
FONT_CANDIDATES = [
    os.environ.get('RIDE_VIDEO_FONT', ''),   # any bold .ttf of your choice
    '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf',
    '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf',
    '/Library/Fonts/Arial Bold.ttf',
    '/System/Library/Fonts/Supplemental/Arial Bold.ttf',
    '/System/Library/Fonts/Helvetica.ttc',
    'C:/Windows/Fonts/arialbd.ttf',
]
WORKDIR = '_ridevideo'
DEFAULT_OUTRO_IMAGE = Path(__file__).resolve().parent.parent / 'assets' / 'adventure-timeline-splash.png'


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- probing
def exif_info(path):
    """(timestamp, orientation) from a JPEG's EXIF, either may be None."""
    try:
        with open(path, 'rb') as f:
            d = f.read(262144)
        if d[:2] != b'\xff\xd8':
            return None, None
        i = 2
        while i < len(d) - 4 and d[i] == 0xFF:
            ln = int.from_bytes(d[i + 2:i + 4], 'big')
            if d[i + 1] == 0xE1 and d[i + 4:i + 10] == b'Exif\0\0':
                t = d[i + 10:i + 2 + ln]
                le = t[:2] == b'II'
                u16 = lambda o: int.from_bytes(t[o:o + 2], 'little' if le else 'big')
                u32 = lambda o: int.from_bytes(t[o:o + 4], 'little' if le else 'big')

                def ifd(off):
                    return {u16(off + 2 + k * 12): (u16(off + 4 + k * 12), u32(off + 6 + k * 12), u32(off + 10 + k * 12))
                            for k in range(u16(off))}
                i0 = ifd(u32(4))
                orient = (i0[0x112][2] & 0xFFFF if le else i0[0x112][2] >> 16) if 0x112 in i0 else None
                ts = None
                tags = []
                if 0x8769 in i0:
                    ex = ifd(i0[0x8769][2])
                    tags += [ex.get(0x9003), ex.get(0x9004)]
                tags.append(i0.get(0x132))
                for tg in tags:
                    if tg:
                        s = t[tg[2]:tg[2] + 19].decode('ascii', 'ignore')
                        try:
                            ts = datetime.datetime.strptime(s, '%Y:%m:%d %H:%M:%S').timestamp()
                            break
                        except ValueError:
                            pass
                return ts, orient
            i += 2 + ln
    except Exception:
        pass
    return None, None


def parse_iso(s):
    try:
        return datetime.datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp()
    except Exception:
        return None


def probe(path):
    j = json.loads(sh(['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path)]).stdout)
    v = next((s for s in j['streams'] if s['codec_type'] == 'video'), None)
    a = next((s for s in j['streams'] if s['codec_type'] == 'audio'), None)
    if not v:
        return None
    w, h = int(v['width']), int(v['height'])
    rot = 0
    for sd in v.get('side_data_list', []):
        if 'rotation' in sd:
            rot = int(sd['rotation'])
    if 'rotate' in v.get('tags', {}):
        rot = -int(v['tags']['rotate'])
    if abs(rot) in (90, 270):
        w, h = h, w
    num, _, den = v.get('avg_frame_rate', '0/1').partition('/')
    fps = float(num) / float(den or 1) if float(den or 1) else 0
    tags = {**j['format'].get('tags', {}), **v.get('tags', {})}
    return dict(width=w, height=h, fps=fps, has_audio=a is not None,
                duration=float(j['format'].get('duration') or v.get('duration') or 0),
                hdr=v.get('color_transfer') in ('arib-std-b67', 'smpte2084'),
                ctime=parse_iso(tags.get('creation_time', '')), tags=tags)


def classify(path, root, info):
    rel = str(path.relative_to(root)).lower()
    if path.suffix.lower() in IMAGE_EXT:
        return 'photo'
    if re.search(r'drone|mavic|mini|air ?\d|avata|fpv', rel):
        return 'drone'
    if re.search(r'phone|iphone|android|pixel|galaxy', rel):
        return 'phone'
    if re.search(r'action|osmo|gopro|helmet|handlebar|bar', rel):
        return 'action'
    if info and info['height'] > info['width']:
        return 'phone'
    return 'action'


def scan(root):
    items = []
    for p in sorted(root.rglob('*')):
        if WORKDIR in p.parts or not p.is_file() or p.name.startswith('.'):
            continue
        ext = p.suffix.lower()
        if ext in VIDEO_EXT:
            info = probe(p)
            if not info or info['duration'] < 0.5:
                continue
            t = info['ctime'] or p.stat().st_mtime
            items.append(dict(path=str(p.relative_to(root)), kind=classify(p, root, info), t=t, **{
                k: info[k] for k in ('width', 'height', 'fps', 'has_audio', 'duration', 'hdr')}))
        elif ext in IMAGE_EXT:
            ts, orient = exif_info(p)
            items.append(dict(path=str(p.relative_to(root)), kind='photo', t=ts or p.stat().st_mtime,
                              orient=orient or 1, duration=0))
        elif ext in ('.heic', '.heif'):
            log(f'skipping {p.name}: HEIC not supported by ffmpeg - convert to JPG first (e.g. heif-convert)')
    return items


# ---------------------------------------------------------------- analysis
def analyze(root, work, it):
    """Per-second motion and audio-level series for a video, cached."""
    src = root / it['path']
    key = hashlib.md5(f"{it['path']}{src.stat().st_size}".encode()).hexdigest()[:12]
    cache = work / 'analysis' / f'{key}.json'
    if cache.exists():
        return json.loads(cache.read_text(encoding='utf-8'))
    n = int(math.ceil(it['duration'])) + 1
    motion = [0.0] * n
    cnt = [0] * n
    # keyframes only: fast even on 4K; scene score ~ amount of change between them
    r = subprocess.run(['ffmpeg', '-v', 'error', '-skip_frame', 'nokey', '-i', str(src), '-an', '-vf',
                        r'scale=320:-2,select=gte(scene\,0),metadata=print:file=-', '-f', 'null', '-'],
                       capture_output=True, text=True)
    t = None
    for line in r.stdout.splitlines():
        m = re.search(r'pts_time:([\d.]+)', line)
        if m:
            t = float(m.group(1))
        m = re.search(r'scene_score=([\d.]+)', line)
        if m and t is not None and int(t) < n:
            motion[int(t)] += float(m.group(1))
            cnt[int(t)] += 1
    motion = [m / c if c else None for m, c in zip(motion, cnt)]
    # fill gaps (seconds with no keyframe) from neighbours
    last = 0.0
    for i, m in enumerate(motion):
        if m is None:
            motion[i] = last
        else:
            last = m
    audio = [-90.0] * n
    if it['has_audio']:
        r = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(src), '-vn', '-af',
                            'aresample=8000,asetnsamples=n=8000:p=0,astats=metadata=1:reset=1,'
                            'ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-', '-f', 'null', '-'],
                           capture_output=True, text=True)
        t = None
        for line in r.stdout.splitlines():
            m = re.search(r'pts_time:([\d.]+)', line)
            if m:
                t = float(m.group(1))
            m = re.search(r'RMS_level=(-?[\d.]+|-inf)', line)
            if m and t is not None and int(t) < n:
                audio[int(t)] = -90.0 if m.group(1) == '-inf' else max(-90.0, float(m.group(1)))
    out = dict(motion=motion, audio=audio)
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(out))
    return out


def pct_rank(xs):
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    r = [0.0] * len(xs)
    for k, i in enumerate(order):
        r[i] = k / max(1, len(xs) - 1)
    return r


def median(xs):
    s = sorted(xs)
    return s[len(s) // 2] if s else 0


def window_scores(an, kind, L):
    m = pct_rank(an['motion'])
    a = pct_rank(an['audio'])
    n = len(m)

    def score(s):
        seg = range(s, min(n, s + L))
        mm = sum(m[i] for i in seg) / len(seg)
        if kind == 'drone':
            return mm
        return 0.6 * mm + 0.4 * max(a[i] for i in seg)
    return score


def find_incidents(an, dur):
    """Seconds where audio jumps well above the file's normal level (shouts, laughs, bangs)."""
    au = an['audio']
    base = median([x for x in au if x > -80]) if any(x > -80 for x in au) else -90
    spikes = [(au[i] - base, i) for i in range(len(au)) if au[i] > -80 and au[i] - base > 10 and au[i] > -35]
    spikes.sort(reverse=True)
    picked = []
    for _, i in spikes:
        if all(abs(i - j) > 12 for j in picked) and 2 < i < dur - 2:
            picked.append(i)
        if len(picked) >= 2:
            break
    return picked


def find_steady(an, dur, incidents, min_run=40, max_span=90, k=2):
    """Long calm stretches (low motion, no audio spikes): candidates for a time-lapse.
    Returns [(start, span)] longest first, well separated."""
    m = pct_rank(an['motion'])
    n = len(m)
    sm = [sum(m[max(0, i - 5):i + 6]) / len(m[max(0, i - 5):i + 6]) for i in range(n)]
    calm = [sm[i] < 0.5 and all(abs(i - x) > 8 for x in incidents) for i in range(n)]
    runs, i = [], 0
    while i < n:
        if calm[i]:
            j = i
            while j < n and calm[j]:
                j += 1
            if j - i >= min_run:
                runs.append((i, j - i))
            i = j
        else:
            i += 1
    out = []
    for st, ln in sorted(runs, key=lambda r: -r[1]):
        span = min(ln - 4, max_span)
        if all(abs(st - o[0]) > max_span for o in out):
            out.append((st + 2, span))
        if len(out) >= k:
            break
    return out


# ---------------------------------------------------------------- planning
def build_plan(root, work, a):
    items = scan(root)
    if not items:
        sys.exit('no media found')
    vids = [i for i in items if i['kind'] != 'photo']
    photos = [i for i in items if i['kind'] == 'photo']
    log(f'found {len(vids)} videos, {len(photos)} photos; analysing...')
    target = a.target
    fixed = 6 + 4  # opener title overlay + outro
    photo_dur = 3.0
    photo_budget = min(0.15 * target, len(photos) * photo_dur)
    if photos and photo_budget / photo_dur < len(photos):
        keep = max(1, int(photo_budget / photo_dur))
        step = len(photos) / keep
        photos = [photos[int(i * step)] for i in range(keep)]
    vid_budget = max(10, target - fixed - len(photos) * photo_dur)

    weights = {i['path']: i['duration'] ** 0.7 for i in vids}
    wsum = sum(weights.values()) or 1
    segs = []
    for it in sorted(vids, key=lambda x: x['t']):
        an = analyze(root, work, it)
        dur = it['duration']
        L = 5 if it['kind'] == 'drone' else 4
        if dur <= L + 1:
            segs.append(dict(src=it['path'], kind=it['kind'], start=0.0, dur=round(dur, 2), score=0.5, t=it['t']))
            continue
        share = vid_budget * weights[it['path']] / wsum
        incidents = find_incidents(an, dur) if it['kind'] != 'drone' else []
        chosen = []
        for s in incidents:
            st = max(0, s - 4)
            chosen.append(dict(src=it['path'], kind=it['kind'], start=float(st), dur=8.0, incident=True,
                               audio=1.0, score=1.0, t=it['t'] + st))
        if a.timelapse and it['kind'] != 'drone' and dur >= 90:
            for st, span in find_steady(an, dur, incidents):
                speed = float(a.max_speed)
                out_dur = min(span / speed, float(a.timelapse_len))
                span = out_dur * speed
                audio = 0.35 if speed <= 2 else 0.0
                chosen.append(dict(src=it['path'], kind=it['kind'], start=float(st), dur=round(out_dur, 2),
                                   span=round(span, 2), speed=speed, timelapse=True, audio=audio, score=0.1, t=it['t'] + st))
        left = max(0, share - sum(c['dur'] for c in chosen))
        n = max(1 if not chosen else 0, int(round(left / L)))
        sc = window_scores(an, it['kind'], L)
        zone = (dur - 2) / max(1, n)
        for z in range(n):
            lo = 1 + z * zone
            hi = max(lo, lo + zone - L)
            cands = [int(x) for x in range(int(lo), int(hi) + 1) if x + L <= dur - 0.5]
            if not cands:
                continue
            best = max(cands, key=sc)
            if any(best < c['start'] + c.get('span', c['dur']) + 1 and c['start'] < best + L + 1 for c in chosen):
                continue
            chosen.append(dict(src=it['path'], kind=it['kind'], start=float(best), dur=float(L),
                               score=round(sc(best), 3), t=it['t'] + best))
        segs += chosen
    # trim to budget: drop weakest non-incident segments
    while sum(s['dur'] for s in segs) > vid_budget * 1.1 and len(segs) > 3:
        weakest = min((s for s in segs if not s.get('incident') and not s.get('timelapse')), key=lambda s: s['score'], default=None)
        if not weakest:
            break
        segs.remove(weakest)
    for p in photos:
        segs.append(dict(src=p['path'], kind='photo', start=0.0, dur=photo_dur, orient=p.get('orient', 1), t=p['t']))
    segs.sort(key=lambda s: s['t'])
    # hero opener: best drone shot (else best clip), moved to the front
    pool = [s for s in segs if s['kind'] == 'drone'] or [s for s in segs if s['kind'] not in ('photo',)]
    if pool:
        hero = max(pool, key=lambda s: s.get('score', 0))
        segs.remove(hero)
        hero = dict(hero, dur=max(hero['dur'], 6.0) if hero['kind'] != 'photo' else 6.0, hero=True)
        hero['dur'] = min(hero['dur'], 8.0)
        segs.insert(0, hero)
    meta = {i['path']: i for i in items}
    for n, s in enumerate(segs, 1):
        s['id'] = n
        s.setdefault('speed', 1.0)
        s.setdefault('audio', 0.0 if s['kind'] in ('drone', 'photo') else 0.35)
        s.setdefault('caption', '')
        s.setdefault('chapter', '')
        s['enabled'] = True
        s.pop('t', None)
        if s['kind'] != 'photo':
            s['hdr'] = bool(meta[s['src']].get('hdr'))
            s['has_audio'] = bool(meta[s['src']].get('has_audio'))
    w, h = (3840, 2160) if a.res == 2160 else (1920, 1080)
    plan = dict(title=a.title or root.name, subtitle=a.subtitle or '', description=a.description or '',
                outro=a.outro, music=str(Path(a.music).expanduser().resolve()) if a.music else None,
                width=w, height=h, fps=30, tags=[], segments=segs)
    if a.outro_image: plan['outro_image'] = a.outro_image   # otherwise the bundled default applies at render time
    return plan


# ---------------------------------------------------------------- rendering
def pick_encoder():
    test = subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=black:s=640x360:d=0.2',
                           '-c:v', 'h264_nvenc', '-f', 'null', '-'], capture_output=True)
    if test.returncode == 0:
        return ['-c:v', 'h264_nvenc', '-preset', 'p5', '-rc', 'vbr', '-cq', '19', '-b:v', '0']
    return ['-c:v', 'libx264', '-preset', 'medium', '-crf', '18']


def font_file(work):
    for f in FONT_CANDIDATES:
        if f and os.path.exists(f):
            shutil.copy(f, work / 'font.ttf')
            return 'font.ttf'
    sys.exit('no bold TTF font found - set RIDE_VIDEO_FONT to a bold .ttf file or add one to FONT_CANDIDATES')


def write_txt(work, name, text):
    (work / 'txt').mkdir(exist_ok=True)
    p = work / 'txt' / f'{name}.txt'
    p.write_text(text.replace('\\', '\\\\').replace('%', '\\%'), encoding='utf-8')
    return f'txt/{name}.txt'


def fit_text(text, size, max_w, max_lines=3):
    """Wrap text to lines that fit max_w pixels at `size` (bold sans is ~0.62 em/char); shrink if needed."""
    import textwrap
    for _ in range(12):
        per_line = max(8, int(max_w / (0.62 * size)))
        lines = textwrap.wrap(text, per_line) or ['']
        if len(lines) <= max_lines and max(len(l) for l in lines) * 0.62 * size <= max_w:
            if len(lines) > 1:  # balance line lengths so no orphan word
                lines = textwrap.wrap(text, -(-len(text) // len(lines)) + 4) or lines
            return size, lines
        size = int(size * 0.9)
    return size, lines


def drawtext(txt, size, x, y, start, end, box=False):
    d = end - start
    fade = min(0.5, d / 3)
    alpha = f"if(lt(t-{start},{fade}),(t-{start})/{fade},if(gt(t,{end}-{fade}),({end}-t)/{fade},1))"
    s = (f"drawtext=fontfile=font.ttf:textfile={txt}:fontsize={size}:fontcolor=white:"
         f"shadowcolor=black@0.8:shadowx=2:shadowy=2:x={x}:y={y}:alpha='{alpha}':enable='between(t,{start},{end})'")
    if box:
        s += f":box=1:boxcolor=black@0.45:boxborderw={int(size * 0.5)}"
    return s


def render_segment(work, plan, s, enc, out):
    W, H, F = plan['width'], plan['height'], plan['fps']
    src = str(work.parent / s['src'])
    dur = s['dur']
    sid = f"{s['id']:03d}"
    texts = []
    big, mid = int(H * 0.085), int(H * 0.045)
    if s.get('hero'):
        tsize, tlines = fit_text(plan['title'], big, W * 0.86)
        band = [0.38 * H - 0.45 * tsize, len(tlines) * 1.2 * tsize + 0.9 * tsize]   # [top, height] of the dark band (stills only)
        for i, line in enumerate(tlines):
            texts.append(drawtext(write_txt(work, f'{sid}_title{i}', line), tsize, '(w-text_w)/2',
                                  f'h*0.38+{i * 1.2 * tsize:.0f}', 0.6, min(dur, 5.5) - 0.2))
        if plan.get('subtitle'):
            ssize, slines = fit_text(plan['subtitle'], mid, W * 0.86)
            y0 = 0.38 * H + len(tlines) * 1.2 * tsize + 0.6 * ssize
            band[1] = y0 + len(slines) * 1.2 * ssize + 0.4 * ssize - band[0]
            for i, line in enumerate(slines):
                texts.append(drawtext(write_txt(work, f'{sid}_sub{i}', line), ssize, '(w-text_w)/2',
                                      f'{y0 + i * 1.2 * ssize:.0f}', 1.0, min(dur, 5.5) - 0.2))
    elif s.get('chapter'):
        csize, clines = fit_text(s['chapter'], int(H * 0.07), W * 0.86)
        for i, line in enumerate(clines):
            texts.append(drawtext(write_txt(work, f'{sid}_chap{i}', line), csize, 'w*0.06',
                                  f'h*0.42+{i * 1.2 * csize:.0f}', 0.3, min(dur - 0.2, 3.5)))
    if float(s.get('speed', 1.0)) >= 1.5:
        texts.append(drawtext(write_txt(work, f'{sid}_spd', f"{float(s['speed']):g}x"), int(H * 0.032), 'w*0.97-text_w', 'h*0.94-text_h', 0.0, dur, box=True))
    if s.get('caption'):
        texts.append(drawtext(write_txt(work, f'{sid}_cap', s['caption']), mid, 'w*0.05', 'h*0.86', 0.5, min(dur - 0.2, 4.5), box=True))
    tx = (',' + ','.join(texts)) if texts else ''
    if s.get('hero') and s['kind'] == 'photo':  # title card over a still (e.g. the route map): dim it so the title reads
        tx = (f",drawbox=x=0:y=0:w=iw:h=ih:color=black@0.25:t=fill,"
              f"drawbox=x=0:y={band[0]:.0f}:w=iw:h={band[1]:.0f}:color=black@0.6:t=fill") + tx

    fin, fout = float(s.get('_fin', 0)), float(s.get('_fout', 0))   # transition fades (set in render())
    fx = (f",fade=t=in:st=0:d={fin}" if fin else '') + (f",fade=t=out:st={max(0, dur - fout):.2f}:d={fout}" if fout else '')
    speed = float(s.get('speed', 1.0))
    if s['kind'] == 'photo':
        inp = ['-loop', '1', '-framerate', str(F), '-t', str(dur), '-i', src]
        pre = ''  # ffmpeg >= 6 applies EXIF orientation to stills itself
        vf = (f"[0:v]{pre}split[a][b];[a]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},boxblur=40:8[bg];"
              f"[b]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2,"
              f"scale={W * 2}:{H * 2},zoompan=z='min(1+0.0006*on,1.12)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s={W}x{H}:fps={F},"
              f"setsar=1,format=yuv420p{tx}{fx}[v]")
        af = None
    else:
        inp = ['-ss', str(s['start']), '-t', str(dur * speed), '-i', src]
        tm = ''
        if s.get('hdr'):
            tm = 'zscale=t=linear:npl=100,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,'
        fit = (f"scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2")
        blur = (f"split[a][b];[a]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},boxblur=40:8[bg];"
                f"[b]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2")
        body = blur if s.get('portrait') else fit
        pts = f"setpts=PTS/{speed}," if speed != 1 else ''
        vf = f"[0:v]{tm}{body},{pts}fps={F},setsar=1,format=yuv420p{tx}{fx}[v]"
        if s.get('has_audio') and s.get('audio', 0) > 0 and speed <= 2:
            at = f"atempo={speed}," if speed != 1 else ''
            af = (f"[0:a]{at}aresample=48000,aformat=channel_layouts=stereo,volume={s['audio']},"
                  f"afade=t=in:d={max(fin, 0.05)},afade=t=out:st={max(0, dur - max(fout, 0.05)):.2f}:d={max(fout, 0.05)},atrim=0:{dur}[a]")
        else:
            af = None
    cmd = ['ffmpeg', '-y', '-v', 'error', *inp]
    if af is None:
        cmd += ['-f', 'lavfi', '-t', str(dur), '-i', 'anullsrc=r=48000:cl=stereo']
        amap = ['-map', '1:a']
        fc = vf
    else:
        amap = ['-map', '[a]']
        fc = vf + ';' + af
    cmd += ['-filter_complex', fc, '-map', '[v]', *amap, *enc, '-r', str(F), '-g', str(F * 2),
            '-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-ac', '2', '-t', str(dur), '-pix_fmt', 'yuv420p', str(out)]
    r = subprocess.run(cmd, cwd=work, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"segment {sid} failed:\n{r.stderr[-1500:]}")


def set_transitions(plan, segs):
    """Fade through black where the sequence changes. plan['transitions']: auto (new chapter/day/source clip),
    all (every cut), none. A segment's own 'transition' ('cut' | 'dip') overrides. plan['transition_len'] seconds (0.3)."""
    mode, base = plan.get('transitions', 'auto'), float(plan.get('transition_len', 0.3))
    def want(prev, cur):
        o = cur.get('transition')
        if o == 'cut': return 0
        if o == 'dip': return base
        if mode == 'none': return 0
        if mode == 'all': return base
        if cur.get('hero') or cur.get('chapter'): return base * 1.7
        if prev['src'] != cur['src'] or prev.get('day') != cur.get('day'): return base
        return 0
    for i, s in enumerate(segs):
        s['_fin'] = s['_fout'] = 0
    for i, s in enumerate(segs):
        if i == 0:
            s['_fin'] = base * 1.7 if mode != 'none' and s.get('transition') != 'cut' else 0
            continue
        t = round(want(segs[i - 1], s), 2)
        s['_fin'] = t; segs[i - 1]['_fout'] = t
    if segs and mode != 'none': segs[-1]['_fout'] = round(base * 1.7, 2)   # into the outro card


def render(root, work, plan, args):
    work.mkdir(exist_ok=True)
    font_file(work)
    enc = pick_encoder()
    log('encoder:', enc[1])
    # fill in geometry / audio flags for segments edited by hand
    probes = {}
    segs = [s for s in plan['segments'] if s.get('enabled', True)]
    seg_dir = work / 'segs'
    seg_dir.mkdir(exist_ok=True)
    for s in segs:
        if s['kind'] != 'photo':
            p = probes.setdefault(s['src'], probe(root / s['src']))
            s['portrait'] = p['height'] > p['width'] * 1.05
            s.setdefault('has_audio', p['has_audio'])
            s.setdefault('hdr', p['hdr'])
    set_transitions(plan, segs)
    files = []
    for n, s in enumerate(segs, 1):
        s['id'] = n
        out = seg_dir / f"seg_{n:03d}.mp4"
        # only fields that change the picture/sound (editor metadata like place/warnings must not force re-renders)
        rel = {k: s.get(k) for k in ('src', 'kind', 'start', 'dur', 'speed', 'audio', 'caption', 'chapter', 'hero',
                                     'orient', 'hdr', 'has_audio', 'timelapse', 'span', '_fin', '_fout')}
        key = hashlib.md5(json.dumps([rel, plan['width'], plan['height'], plan['title'], plan.get('subtitle'), 'layout-v3'], sort_keys=True).encode()).hexdigest()[:10]
        stamp = out.with_suffix('.key')
        if not (out.exists() and stamp.exists() and stamp.read_text(encoding='utf-8') == key) or args.force:
            log(f'[{n}/{len(segs)}] {s["kind"]} {s["src"]} @{s["start"]}s +{s["dur"]}s')
            render_segment(work, plan, s, enc, out)
            stamp.write_text(key)
        files.append(out)
    # outro
    outro = seg_dir / 'outro.mp4'
    W, H, F = plan['width'], plan['height'], plan['fps']
    otxt = write_txt(work, 'outro', plan.get('outro') or 'Thanks for riding along')
    # End card: plan['outro_image'] (path, relative to the working folder or absolute) shown centred on a dark
    # gradient with the outro text underneath. Default is the Adventure Timeline splash bundled in assets/;
    # set outro_image to "none" (or --outro-image none) for a plain text card.
    img = plan.get('outro_image', str(DEFAULT_OUTRO_IMAGE))
    img = None if str(img).lower() in ('', 'none', 'false') else (root / img)
    if img and img.exists():
        ow = f"fade=t=in:st=0:d=0.5,fade=t=out:st=3.5:d=0.5"
        small = drawtext(otxt, int(H * 0.035), '(w-text_w)/2', 'h*0.925', 0.4, 3.7)
        fc = (f"[1:v]format=rgba,scale=-2:{int(H * 0.8)}[im];[0:v][im]overlay=(W-w)/2:(H-h)/2:format=auto,"
              f"{small},{ow},format=yuv420p[v]")
        cmd = ['ffmpeg', '-y', '-v', 'error',
               '-f', 'lavfi', '-i', f'gradients=s={W}x{H}:d=4:r={F}:c0=0x0F2036:c1=0x16110B:x0=0:y0=0:x1=0:y1={H}:speed=0.00001',
               '-loop', '1', '-framerate', str(F), '-t', '4', '-i', str(img),
               '-f', 'lavfi', '-t', '4', '-i', 'anullsrc=r=48000:cl=stereo',
               '-filter_complex', fc, '-map', '[v]', '-map', '2:a']
    else:
        cmd = ['ffmpeg', '-y', '-v', 'error', '-f', 'lavfi', '-i', f'color=c=0x101010:s={W}x{H}:d=4:r={F}',
               '-f', 'lavfi', '-t', '4', '-i', 'anullsrc=r=48000:cl=stereo', '-vf',
               drawtext(otxt, int(H * 0.07), '(w-text_w)/2', '(h-text_h)/2', 0.3, 3.8) + ',format=yuv420p']
    r = subprocess.run([*cmd, *enc, '-c:a', 'aac', '-b:a', '192k', '-t', '4', '-r', str(F), '-g', str(F * 2),
                        '-pix_fmt', 'yuv420p', str(outro)], cwd=work, capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr[-1500:])
    files.append(outro)
    (work / 'concat.txt').write_text(''.join(f"file '{f.as_posix()}'\n" for f in files), encoding='utf-8')
    joined = work / 'joined.mp4'
    sh(['ffmpeg', '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', str(work / 'concat.txt'), '-c', 'copy', str(joined)])
    total = sum(s['dur'] for s in segs) + 4
    # chapters
    t, ch = 0.0, []
    for s in segs:
        if s.get('hero'):
            ch.append((t, 'Intro'))
        elif s.get('chapter'):
            ch.append((t, s['chapter']))
        t += s['dur']
    if len(ch) >= 3 and ch[0][0] == 0:
        (work / 'chapters.txt').write_text(''.join(f"{int(a // 60)}:{int(a % 60):02d} {b}\n" for a, b in ch), encoding='utf-8')
    # music + final mix: constant music level, dipped only around incident shots (voices), smooth loop seams,
    # two-pass (linear) loudness normalisation so the level doesn't pump
    final = work / 'final.mp4'
    music = plan.get('music')
    pre = work / 'premix.wav'
    if music and os.path.exists(music):
        ramp, floor, base = 0.8, float(plan.get('music_duck', 0.1)), float(plan.get('music_volume', 0.65))
        t, dips = 0.0, []
        for s in segs:
            if s.get('incident') or s.get('duck_music'):
                dips.append((t - ramp, t + s['dur'] + ramp))
            t += s['dur']
        gain = '*'.join(f"(1-{1 - floor:.2f}*clip(min((t-{a_:.2f})/{ramp},({b_:.2f}-t)/{ramp}),0,1))" for a_, b_ in dips) or '1'
        mlen = float(sh(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', music]).stdout.strip())
        xf = min(3.0, mlen / 4)
        k = 1 if mlen >= total else min(8, math.ceil((total - xf) / (mlen - xf)))
        chain, last = '', '1:a'
        for i in range(2, k + 1):   # copies of the track cross-faded end to start so the loop has no seam
            chain += f"[{last}][{i}:a]acrossfade=d={xf}:c1=tri:c2=tri[b{i}];"
            last = f"b{i}"
        fc = (f"{chain}[{last}]atrim=0:{total},asetpts=N/SR/TB,volume='{base}*{gain}':eval=frame,"
              f"afade=t=in:d=2,afade=t=out:st={total - 4}:d=4[m];"
              "[0:a][m]amix=inputs=2:normalize=0:duration=first[a]")
        sh(['ffmpeg', '-y', '-v', 'error', '-i', str(joined), *sum((['-i', music] for _ in range(k)), []),
            '-filter_complex', fc, '-map', '[a]', '-ar', '48000', '-ac', '2', str(pre)])
    else:
        log('no music file - keeping camera audio only')
        sh(['ffmpeg', '-y', '-v', 'error', '-i', str(joined), '-vn', '-ar', '48000', '-ac', '2', str(pre)])
    ln = 'loudnorm=I=-14:TP=-1.5:LRA=11'
    r = subprocess.run(['ffmpeg', '-v', 'info', '-i', str(pre), '-af', ln + ':print_format=json', '-f', 'null', '-'],
                       capture_output=True, text=True)
    try:
        m = json.loads(r.stderr[r.stderr.rindex('{'):r.stderr.rindex('}') + 1])
        ln += (f":measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
               f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    except (ValueError, KeyError):
        log('loudness measure failed; using single-pass normalisation')
    sh(['ffmpeg', '-y', '-v', 'error', '-i', str(joined), '-i', str(pre), '-map', '0:v', '-map', '1:a', '-af', ln,
        '-c:v', 'copy', '-c:a', 'aac', '-b:a', '256k', '-movflags', '+faststart', str(final)])
    # thumbnail from the hero shot with the title
    thumb = work / 'thumbnail.jpg'
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-ss', '3', '-i', str(seg_dir / 'seg_001.mp4'), '-frames:v', '1',
                    '-vf', 'scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720', '-q:v', '3', str(thumb)])
    # deliver to the media root (plan['output_dir'], relative to the working folder; default: the working folder's
    # _ridevideo). The name comes from plan['output_name'] or the title.
    out_dir = (root / plan['output_dir']).resolve() if plan.get('output_dir') else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
        name = plan.get('output_name') or re.sub(r'[\\/:*?"<>|]+', '', plan.get('title') or 'ride video').strip() or 'ride video'
        shutil.copy2(final, out_dir / f'{name}.mp4')
        if thumb.exists(): shutil.copy2(thumb, out_dir / f'{name}.jpg')
        if (work / 'chapters.txt').exists(): shutil.copy2(work / 'chapters.txt', out_dir / f'{name}_chapters.txt')
        final = out_dir / f'{name}.mp4'
    log(f'\nDONE  {final}  ({total:.0f}s = {int(total // 60)}:{int(total % 60):02d})')


# ---------------------------------------------------------------- contact sheets
def sheets(root, work, plan):
    segs = [s for s in plan['segments'] if s.get('enabled', True)]
    d = work / 'sheets'
    shutil.rmtree(d, ignore_errors=True)
    (d / 'f').mkdir(parents=True)
    font = font_file(work)
    for n, s in enumerate(segs, 1):
        src = root / s['src']
        mid = s['start'] + s['dur'] / 2
        label = f"#{s['id']} {s['kind']} {s['src'].split('/')[-1][:18]} {s['start']:.0f}s" + (' INCIDENT' if s.get('incident') else '')
        label = re.sub(r"[:'\\%]", '', label)
        vf = f"scale=480:270:force_original_aspect_ratio=decrease,pad=480:270:(ow-iw)/2:(oh-ih)/2,drawtext=fontfile={font}:text='{label}':fontsize=16:fontcolor=white:box=1:boxcolor=black@0.6:x=4:y=4"
        cmd = ['ffmpeg', '-y', '-v', 'error'] + ([] if s['kind'] == 'photo' else ['-ss', str(mid)]) + ['-i', str(src), '-frames:v', '1', '-vf', vf, str(d / 'f' / f'{n:04d}.jpg')]
        subprocess.run(cmd, cwd=work)
    per = 12
    for k in range(0, len(segs), per):
        sh(['ffmpeg', '-y', '-v', 'error', '-start_number', str(k + 1), '-i', str(d / 'f' / '%04d.jpg'), '-frames:v', '1',
            '-vf', 'tile=4x3:padding=4:color=black', '-q:v', '3', str(d / f'sheet_{k // per + 1:02d}.jpg')])
    log('contact sheets in', d)


# ---------------------------------------------------------------- upload
def upload(root, work, plan, args):
    try:
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload
        from google_auth_oauthlib.flow import InstalledAppFlow
        from google.oauth2.credentials import Credentials
        from google.auth.transport.requests import Request
    except ImportError:
        sys.exit('pip install --user google-api-python-client google-auth-oauthlib')
    secret = Path(args.client_secret).expanduser()
    tokf = Path('~/.config/ride-video/token.json').expanduser()
    scopes = ['https://www.googleapis.com/auth/youtube.upload']
    creds = Credentials.from_authorized_user_file(tokf, scopes) if tokf.exists() else None
    if not creds or not creds.valid:
        if creds and creds.refresh_token:
            creds.refresh(Request())
        else:
            creds = InstalledAppFlow.from_client_secrets_file(str(secret), scopes).run_local_server(port=0)
        tokf.parent.mkdir(parents=True, exist_ok=True)
        tokf.write_text(creds.to_json())
    desc = plan.get('description', '')
    ch = work / 'chapters.txt'
    if ch.exists():
        desc += '\n\nChapters:\n' + ch.read_text(encoding='utf-8')
    body = dict(snippet=dict(title=plan['title'], description=desc, tags=plan.get('tags', []), categoryId='19'),
                status=dict(privacyStatus=args.privacy, selfDeclaredMadeForKids=False))
    yt = build('youtube', 'v3', credentials=creds)
    req = yt.videos().insert(part='snippet,status', body=body, media_body=MediaFileUpload(str(work / 'final.mp4'), chunksize=-1, resumable=True))
    resp = None
    while resp is None:
        st, resp = req.next_chunk()
        if st:
            log(f'uploaded {int(st.progress() * 100)}%')
    log('uploaded:', f"https://youtu.be/{resp['id']}", f'({args.privacy})')
    thumb = work / 'thumbnail.jpg'
    if thumb.exists():
        try:
            yt.thumbnails().set(videoId=resp['id'], media_body=MediaFileUpload(str(thumb))).execute()
        except Exception as e:
            log('thumbnail not set (channel may need verification):', e)


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('cmd', choices=['plan', 'sheet', 'render', 'upload', 'auto'])
    ap.add_argument('folder')
    ap.add_argument('--title')
    ap.add_argument('--subtitle')
    ap.add_argument('--description')
    ap.add_argument('--outro', default='Thanks for riding along - like & subscribe')
    ap.add_argument('--outro-image', help="end-card image (default: bundled Adventure Timeline splash; 'none' for a plain text card)")
    ap.add_argument('--music', help='audio file to use as background music')
    ap.add_argument('--target', type=int, default=420, help='target length in seconds (default 420 = 7 min)')
    ap.add_argument('--res', type=int, default=1080, choices=[1080, 2160])
    ap.add_argument('--no-timelapse', dest='timelapse', action='store_false', help='do not auto time-lapse long calm stretches')
    ap.add_argument('--max-speed', type=float, default=2.0, help='speed for auto time-lapses (default 2x)')
    ap.add_argument('--timelapse-len', type=float, default=10.0, help='max output seconds per auto time-lapse (default 10)')
    ap.add_argument('--force', action='store_true', help='re-render cached segments')
    ap.add_argument('--client-secret', default='~/.config/ride-video/client_secret.json')
    ap.add_argument('--privacy', default='private', choices=['private', 'unlisted', 'public'])
    a = ap.parse_args()
    root = Path(a.folder).expanduser().resolve()
    work = root / WORKDIR
    work.mkdir(exist_ok=True)
    pf = work / 'plan.json'
    if a.cmd in ('plan', 'auto'):
        plan = build_plan(root, work, a)
        pf.write_text(json.dumps(plan, indent=1))
        tot = sum(s['dur'] for s in plan['segments']) + 4
        log(f'plan: {len(plan["segments"])} segments, ~{tot / 60:.1f} min -> {pf}')
    plan = json.loads(pf.read_text(encoding='utf-8'))
    if a.cmd == 'sheet':
        sheets(root, work, plan)
    if a.cmd in ('render', 'auto'):
        render(root, work, plan, a)
    if a.cmd == 'upload':
        upload(root, work, plan, a)


if __name__ == '__main__':
    main()
