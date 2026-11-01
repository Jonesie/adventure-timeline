#!/usr/bin/env python3
"""Interactive plan editor for ride-video.

usage: editor.py <folder> [--port 8765]

Serves a local page (open it in a browser) to reorder/trim/disable segments, edit captions and
chapter titles, flag incidents, preview segments, see them on the route map, save plan.json
and start a render. Everything stays on this machine (no map tiles, no external requests).
"""
import hashlib, json, mimetypes, os, re, shutil, subprocess, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
FOLDER = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[sys.argv.index('--port') + 1]) if '--port' in sys.argv else 8765
WORK = os.path.join(FOLDER, '_ridevideo')
PLAN = os.path.join(WORK, 'plan.json')
CACHE = os.path.join(WORK, 'editor')
os.makedirs(CACHE, exist_ok=True)
render = {'state': 'idle', 'log': []}
lock = threading.Lock()

def load(): return json.load(open(PLAN, encoding='utf-8'))

def seg_by_id(i):
    return next((s for s in load()['segments'] if s['id'] == i), None)

def key(*a): return hashlib.md5('|'.join(map(str, a)).encode()).hexdigest()[:16]

def run(cmd): return subprocess.run(cmd, capture_output=True)

def thumb(s, idx):
    src = os.path.join(FOLDER, s['src'])
    out = os.path.join(CACHE, f"t_{key(s['src'], s['start'], s['dur'], s['speed'], idx)}.jpg")
    if not os.path.exists(out):
        if s['kind'] == 'photo':
            run(['ffmpeg', '-v', 'error', '-y', '-i', src, '-vf', 'scale=320:-2', '-frames:v', '1', out])
        else:
            span = s['dur'] * s['speed']
            t = s['start'] + span * (0.1, 0.5, 0.9)[idx]
            run(['ffmpeg', '-v', 'error', '-y', '-ss', f'{t:.2f}', '-i', src, '-vf', 'scale=320:-2', '-frames:v', '1', out])
    return out

def preview(s):
    src = os.path.join(FOLDER, s['src'])
    out = os.path.join(CACHE, f"p_{key(s['src'], s['start'], s['dur'], s['speed'], s['audio'])}.mp4")
    if not os.path.exists(out):
        span = s['dur'] * s['speed']
        af = []
        sp = s['speed']
        while sp > 2: af.append('atempo=2'); sp /= 2
        if sp != 1: af.append(f'atempo={sp}')
        cmd = ['ffmpeg', '-v', 'error', '-y', '-ss', f"{s['start']:.2f}", '-t', f'{span:.2f}', '-i', src,
               '-vf', f"setpts=PTS/{s['speed']},scale=854:-2,fps=30", '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '28']
        if s.get('has_audio') and s['audio'] > 0:
            cmd += ['-af', ','.join(af + [f"volume={s['audio']}"]), '-c:a', 'aac']
        else:
            cmd += ['-an']
        run(cmd + ['-movflags', '+faststart', out])
    return out

def do_render(force):
    with lock:
        if render['state'] == 'running': return
        render.update(state='running', log=[])
    def work():
        cmd = [sys.executable, os.path.join(HERE, 'ride_video.py'), 'render', FOLDER] + (['--force'] if force else [])
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in p.stdout: render['log'] = (render['log'] + [line.rstrip()])[-40:]
        render['state'] = 'done' if p.wait() == 0 else 'failed'
    threading.Thread(target=work, daemon=True).start()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype='application/json', extra=None):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        elif isinstance(body, str): body = body.encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def send_file(self, path, ctype):
        size = os.path.getsize(path); rng = self.headers.get('Range')
        start, end, code = 0, size - 1, 200
        if rng and (m := re.match(r'bytes=(\d*)-(\d*)', rng)):
            if m.group(1): start = int(m.group(1))
            if m.group(2): end = int(m.group(2))
            code = 206
        self.send_response(code)
        self.send_header('Content-Type', ctype); self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if code == 206: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        with open(path, 'rb') as f:
            f.seek(start); left = end - start + 1
            while left > 0:
                b = f.read(min(1 << 16, left))
                if not b: break
                try: self.wfile.write(b)
                except BrokenPipeError: return
                left -= len(b)

    def do_GET(self):
        u = urlparse(self.path); parts = u.path.strip('/').split('/')
        if u.path == '/': return self.send(200, PAGE, 'text/html; charset=utf-8')
        if u.path == '/api/plan': return self.send(200, load())
        if u.path == '/api/render': return self.send(200, render)
        if u.path == '/api/music':   # audio files in the working folder (or its music/ sub-folder) to pick from
            found = []
            for d in (FOLDER, os.path.join(FOLDER, 'music')):
                if os.path.isdir(d):
                    found += [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.lower().endswith(('.mp3', '.m4a', '.wav', '.ogg', '.aac', '.flac'))]
            return self.send(200, found)
        if parts[0] in ('thumb', 'preview') and len(parts) >= 2:
            s = seg_by_id(int(parts[1]))
            if not s: return self.send(404, {'error': 'no such segment'})
            # the page sends its current (possibly unsaved) values, which win over what's on disk
            q = parse_qs(u.query)
            for k in ('start', 'dur', 'speed', 'audio'):
                if k in q:
                    try: s[k] = float(q[k][0])
                    except ValueError: pass
            if parts[0] == 'thumb': return self.send_file(thumb(s, int(parts[2])), 'image/jpeg')
            if s['kind'] == 'photo': return self.send_file(thumb(s, 1), 'image/jpeg')
            return self.send_file(preview(s), 'video/mp4')
        self.send(404, {'error': 'not found'})

    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0)); body = json.loads(self.rfile.read(n) or b'{}')
        if self.path == '/api/plan':
            cur = load(); shutil.copy(PLAN, PLAN + '.bak')
            for k in ('title', 'subtitle', 'description', 'outro', 'tags', 'transitions', 'transition_len', 'music', 'music_volume'):
                if k in body: cur[k] = body[k]
            cur['segments'] = body['segments']
            json.dump(cur, open(PLAN, 'w', encoding='utf-8'), indent=1)
            return self.send(200, {'ok': True})
        if self.path == '/api/render':
            do_render(body.get('force', False)); return self.send(200, {'ok': True})
        self.send(404, {'error': 'not found'})

PAGE = open(os.path.join(HERE, 'editor.html'), encoding='utf-8').read()

if __name__ == '__main__':
    print(f'ride-video editor: http://127.0.0.1:{PORT}/  (folder: {FOLDER})', flush=True)
    ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
