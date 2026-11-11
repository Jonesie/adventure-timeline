#!/usr/bin/env python3
"""Synthesises the incident sound effects in app/src/main/res/raw/.

Every sound is generated from noise and simple tones here, so there are no third-party samples and
nothing to attribute or relicense. Needs numpy, scipy and ffmpeg (with libvorbis).

    python3 tools/generate_sfx.py

The output is deterministic (fixed random seed), so re-running only changes files if this script does.
"""
import pathlib
import subprocess
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter

RATE = 44100
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
rng = np.random.default_rng(7)


def t(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def noise(seconds):
    return rng.standard_normal(int(seconds * RATE))


def lowpass(x, hz, order=3):
    b, a = butter(order, hz / (RATE / 2), "low")
    return lfilter(b, a, x)


def bandpass(x, lo, hi, order=2):
    b, a = butter(order, [lo / (RATE / 2), hi / (RATE / 2)], "band")
    return lfilter(b, a, x)


def decay(x, rate):
    return x * np.exp(-rate * t(len(x) / RATE))


def fade(x, ms=8):
    n = int(RATE * ms / 1000)
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def mix(length, *layers):
    out = np.zeros(int(length * RATE))
    for start, x in layers:
        i = int(start * RATE)
        if i < len(out):
            out[i:i + len(x)] += x[: len(out) - i]
    return out


def crash():
    """A heavy thud, a burst of crunch, then bits of metal rattling."""
    thump = decay(np.sin(2 * np.pi * (45 + 90 * np.exp(-t(0.6) * 14)) * t(0.6)), 6) * 1.2
    crunch = decay(lowpass(noise(0.8), 4500), 7)
    bits = [(0.08 + 0.45 * rng.random(), decay(bandpass(noise(0.25), f, f * 1.4), 22) * 0.5)
            for f in rng.uniform(900, 4200, 9)]
    return mix(1.1, (0, thump), (0, crunch * 1.3), *bits)


def screech():
    """A tyre squealing: a wobbling, harsh high tone that ends in a thump."""
    d = 0.9
    tt = t(d)
    freq = 1250 + 280 * np.sin(2 * np.pi * 9 * tt) - 380 * tt
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    tone = np.sign(np.sin(phase)) * 0.4 + np.sin(phase * 2) * 0.3 + bandpass(noise(d), 1500, 3500) * 0.5
    env = np.minimum(tt / 0.05, 1) * np.minimum((d - tt) / 0.15, 1)
    thud = decay(np.sin(2 * np.pi * 70 * t(0.3)), 14)
    return mix(1.2, (0, tone * env * 0.7), (0.85, thud))


def splash():
    """Water: a rush of noise that closes down from bright to dull, then bubbles."""
    d = 1.1
    tt = t(d)
    rush = noise(d)
    parts = [lowpass(rush, hz) for hz in (7000, 3000, 1200, 500)]
    sweep = np.zeros_like(rush)
    for i, p in enumerate(parts):
        edges = np.clip((tt - i * 0.15) / 0.25, 0, 1)
        sweep += p * np.sin(np.pi * edges)  # each band swells then fades in turn
    sweep *= np.exp(-tt * 3.2) * np.minimum(tt / 0.01, 1)
    bubbles = []
    for start in np.sort(rng.uniform(0.2, 1.0, 10)):
        f0 = rng.uniform(400, 900)
        bt = t(0.09)
        bubbles.append((start, np.sin(2 * np.pi * (f0 + 1800 * bt) * bt) * np.exp(-bt * 35) * 0.25))
    return mix(1.3, (0, sweep * 0.9), *bubbles)


def sputter():
    """An engine that coughs, catches, coughs again, and dies."""
    layers = []
    when, gap, amp = 0.0, 0.11, 1.0
    for _ in range(9):
        pt = t(0.09)
        pulse = (np.sign(np.sin(2 * np.pi * 85 * pt)) * 0.5 + np.sin(2 * np.pi * 42 * pt)) * np.exp(-pt * 40)
        layers.append((when, lowpass(pulse, 700) * amp))
        when += gap
        gap *= 1.22
        amp *= 0.86
    hiss = decay(bandpass(noise(0.5), 2500, 7000), 9) * 0.25
    return mix(3.2, *layers, (when, hiss))


def thunder():
    """A sharp crack, then a long low rumble."""
    crack = decay(bandpass(noise(0.3), 800, 5000), 18) * 1.4
    d = 2.6
    tt = t(d)
    rumble = lowpass(noise(d), 160, 4)
    rumble = rumble * (0.4 + 0.6 * np.abs(lowpass(noise(d), 4, 2) * 14)) * np.exp(-tt * 1.1) * 4.5
    return mix(d, (0, crack), (0.05, rumble * np.minimum(tt / 0.2, 1)))


def alert():
    """A two-note 'uh-oh': a bright tone, then a lower one."""
    def note(f, d):
        nt = t(d)
        return (np.sin(2 * np.pi * f * nt) + 0.35 * np.sin(2 * np.pi * 2 * f * nt)) * np.exp(-nt * 7)
    return mix(0.75, (0, note(700, 0.3)), (0.25, note(466, 0.5)))


SOUNDS = {
    "sfx_crash": crash,
    "sfx_screech": screech,
    "sfx_splash": splash,
    "sfx_sputter": sputter,
    "sfx_thunder": thunder,
    "sfx_alert": alert,
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, make in SOUNDS.items():
            x = fade(make())
            x = x / max(1e-9, np.abs(x).max()) * 0.85
            wav = pathlib.Path(tmp) / f"{name}.wav"
            wavfile.write(wav, RATE, (x * 32767).astype(np.int16))
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis", "-q:a", "2",
                 "-map_metadata", "-", "-fflags", "+bitexact", str(OUT / f"{name}.ogg")],
                check=True,
            )
            print(f"{name}.ogg  {x.size / RATE:.2f}s  {(OUT / f'{name}.ogg').stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
