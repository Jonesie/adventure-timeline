#!/usr/bin/env python3
"""Merge an Adventure Timeline trip export (trip.json) into a ride-video plan.json.

usage: trip_merge.py <folder> <trip.json>

Adds to every segment: time (local ISO), lat/lon, place, leg, day.
Sorts segments chronologically (opener stays first), adds day chapters, place captions
(where empty) and forces incident clips in at full audio. Stores the trip (route, stops,
legs, incidents) in plan['trip'] for the editor's map. Safe to re-run.
"""
import bisect, hashlib, json, math, os, re, struct, subprocess, sys
from datetime import datetime, timedelta, timezone

def parse_iso(s): return datetime.fromisoformat(s)

def clip_time(src, start, tz):
    b = os.path.basename(src)
    m = re.match(r'DJI_(\d{14})_', b)
    if m:
        return datetime.strptime(m.group(1), '%Y%m%d%H%M%S').replace(tzinfo=tz) + timedelta(seconds=start)
    m = re.match(r'\d{3}_(\d{8}-\d{6})', b)
    if m and not b.startswith('19040101', 4):
        return datetime.strptime(m.group(1), '%Y%m%d-%H%M%S').replace(tzinfo=tz) + timedelta(seconds=start)
    return None

def drone_gps(path):
    """First non-zero lat/lon from the SRT sidecar, else a lat/lon double pair in the djmd stream."""
    path = os.path.realpath(path)
    srt = os.path.splitext(path)[0] + '.SRT'
    if os.path.exists(srt):
        for m in re.finditer(r'latitude: (-?[\d.]+)\] \[longitude: (-?[\d.]+)', open(srt, errors='ignore', encoding='utf-8').read()):
            la, lo = float(m.group(1)), float(m.group(2))
            if la or lo: return la, lo
    out = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-map', '0:1', '-c', 'copy', '-f', 'data', '-'],
                         capture_output=True).stdout[:4000]
    for i in range(len(out) - 17):
        la = struct.unpack_from('<d', out, i)[0]
        lo = struct.unpack_from('<d', out, i + 9)[0]
        if 1 < abs(la) < 90 and 1 < abs(lo) < 180 and abs(la * 1e5 - round(la * 1e5)) > 1e-3 \
                and abs(lo * 1e5 - round(lo * 1e5)) > 1e-3:
            return la, lo
    return None

def hav(a, b):
    p = math.pi / 180
    x = math.sin((b[0]-a[0])*p/2)**2 + math.cos(a[0]*p)*math.cos(b[0]*p)*math.sin((b[1]-a[1])*p/2)**2
    return 12742 * math.asin(math.sqrt(x))

def fingerprint(path):
    path = os.path.realpath(path)
    try:
        with open(path, 'rb') as f:
            return os.path.getsize(path), hashlib.md5(f.read(1 << 20)).hexdigest()
    except OSError:
        return None

def main():
    # --keep-order: only (re)fill time/place/warnings; leave order, chapters, title and audio as the user edited them
    keep = '--keep-order' in sys.argv
    folder, tripfile = [a for a in sys.argv[1:] if not a.startswith('--')][:2]
    pf = os.path.join(folder, '_ridevideo', 'plan.json')
    plan = json.load(open(pf, encoding='utf-8')); trip = json.load(open(tripfile, encoding='utf-8'))
    tz = parse_iso(trip['trip']['startTime']).tzinfo
    # user corrections: {"<media number>": {"time","lat","lon","place"}} for incidents logged at the wrong place/time
    ovf = os.path.join(folder, '_ridevideo', 'incident_overrides.json')
    overrides = json.load(open(ovf, encoding='utf-8')) if os.path.exists(ovf) else {}
    for inc in trip.get('incidents', []):
        for mf in inc.get('mediaFiles', []):
            inc.update(overrides.get(os.path.basename(mf)[:3], {}))
            if 'time' in overrides.get(os.path.basename(mf)[:3], {}):
                inc['timeMillis'] = int(parse_iso(inc['time']).timestamp() * 1000)
    pts = trip['route']['points']                      # [lat, lon, epochMillis]
    ts = [p[2] for p in pts]
    legs = trip['legs']

    def millis(dt): return int(dt.timestamp() * 1000)
    def at_time(dt):
        i = min(max(bisect.bisect_left(ts, millis(dt)), 0), len(pts) - 1)
        return pts[i]
    def at_pos(la, lo):
        return min(pts, key=lambda p: hav((la, lo), (p[0], p[1])))
    def leg_for(ms):
        for l in legs:
            if l['startMillis'] <= ms <= l['endMillis']: return l
        return min(legs, key=lambda l: min(abs(l['startMillis']-ms), abs(l['endMillis']-ms)))
    def leg_place(l):
        a, b = l['from']['name'], l['to']['name']
        return a if a == b else f'{a} to {b}'

    incident_ms = {}
    for inc in trip.get('incidents', []):
        for mf in inc.get('mediaFiles', []):
            incident_ms[os.path.basename(mf)[:3]] = inc

    t0, t1 = parse_iso(trip['trip']['startTime']).date(), parse_iso(trip['trip']['endTime']).date()
    first_seen = {}
    for s in plan['segments']:
        b = os.path.basename(s['src']); s.pop('time_from_gps', None); s['warnings'] = []
        if b.startswith('route-map'): continue
        fp = fingerprint(os.path.join(folder, s['src']))
        if fp and s['kind'] != 'drone':
            if fp in first_seen and first_seen[fp][1] != s['src']:
                s['warnings'].append(f"Identical file to #{first_seen[fp][0]} - disabled by default so it isn't shown twice")
            elif fp not in first_seen:
                first_seen[fp] = (s['id'], s['src'])
        inc = incident_ms.get(b[:3]) if re.match(r'\d{3}_', b) else None
        dt = clip_time(s['src'], s['start'], tz)
        pos = None
        if inc:
            dt = parse_iso(inc['time']); pos = (inc['lat'], inc['lon'])
            s['incident'] = {'category': inc['categoryLabel'], 'notes': inc.get('notes', ''), 'place': inc.get('place', '')}
            if not keep: s['audio'] = 1.0
            if re.match(r'\d{3}_1904', b) or '19040101' in b:
                s['warnings'].append('File has no capture date (1904 placeholder); placed using the incident time')
        elif s['kind'] == 'drone':
            g = drone_gps(os.path.join(folder, s['src']))
            if g:
                pt = at_time(dt) if dt else None
                if pt and hav(g, (pt[0], pt[1])) < 3:
                    pos = g                      # clock agrees with the GPS fix
                else:
                    p = at_pos(*g); dt = datetime.fromtimestamp(p[2] / 1000, tz); pos = (p[0], p[1])
                    s['time_from_gps'] = True    # drone clock is off; trust the position
                    s['warnings'].append('Drone clock disagrees with its GPS; placed by position')
        if dt is None:
            s['time'] = None
            s['warnings'].append('No capture time in file name or trip data; not placed on the route')
            if s['kind'] != 'drone' and any('Identical' in w for w in s['warnings']): s['enabled'] = False
            continue
        if not (t0 <= dt.date() <= t1):
            s['warnings'].append(f"Capture time {dt.strftime('%d %b %Y %H:%M')} is outside the trip dates "
                                 f"({t0.strftime('%d %b')} - {t1.strftime('%d %b')})")
        if pos is None:
            p = at_time(dt); pos = (p[0], p[1])
        l = leg_for(millis(dt))
        s['time'] = dt.isoformat(); s['lat'], s['lon'] = round(pos[0], 5), round(pos[1], 5)
        s['leg'] = l['index']; s['place'] = leg_place(l)
        s['day'] = dt.strftime('%Y-%m-%d')
        if any('Identical' in w for w in s['warnings']) and not keep: s['enabled'] = False

    days = sorted({s['day'] for s in plan['segments'] if s.get('day')})
    if not keep:
      # chronological order, opener (first segment) stays first
      opener, rest = plan['segments'][0], plan['segments'][1:]
      rest.sort(key=lambda s: s.get('time') or '9999')
      plan['segments'] = [opener] + rest
      # day chapters
      for s in plan['segments']:
        if s.get('chapter', '').startswith('Day '): s['chapter'] = ''
      for n, d in enumerate(days, 1):
        dl = [l for l in legs if l['startTime'][:10] == d and l['transport'] == 'MOTORBIKE']
        first = next((s for s in plan['segments'] if s.get('day') == d and s['enabled']), None)
        if first and dl:
            a, z = dl[0]['from']['name'], dl[-1]['to']['name']
            first['chapter'] = f"Day {n}: {a}" if a == z else f"Day {n}: {a} to {z}"
    if not keep: plan['title'] = trip['trip']['title']
    plan['trip'] = {'title': trip['trip']['title'], 'notes': trip['trip'].get('notes', ''),
                    'companions': trip['trip'].get('companions', []), 'route': pts,
                    'stops': trip['stops'], 'legs': legs, 'incidents': trip.get('incidents', [])}
    json.dump(plan, open(pf, 'w', encoding='utf-8'), indent=1)
    print(f"merged: {sum(1 for s in plan['segments'] if s.get('time'))}/{len(plan['segments'])} segments placed, "
          f"{len(days)} day(s), {len(trip.get('incidents', []))} incident(s)")

if __name__ == '__main__':
    main()
