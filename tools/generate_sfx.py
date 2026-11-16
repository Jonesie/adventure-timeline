#!/usr/bin/env python3
"""Synthesises the incident sound effects in app/src/main/res/raw/.

Every sound is generated from noise and simple tones here, except the scream: that is trimmed from an
ElevenLabs-generated clip in tools/sfx_source/ (see the NOTICE there for its licensing). The app has both that
and a synthesised scream, and picks between them at random. Needs numpy, scipy and ffmpeg (with libvorbis).

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


RECORDED_SCREAM = pathlib.Path(__file__).resolve().parent / "sfx_source" / "scream_elevenlabs.mp3"
SCREAM_SECONDS = 1.2


def recorded_scream():
    """The ElevenLabs scream trimmed to its first [SCREAM_SECONDS]."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(RECORDED_SCREAM), "-f", "s16le", "-ac", "1", "-ar", str(RATE), "-"],
        check=True, capture_output=True,
    ).stdout
    x = np.frombuffer(raw, dtype=np.int16).astype(float)[: int(SCREAM_SECONDS * RATE)]
    ramp = int(0.15 * RATE)
    x[-ramp:] *= np.linspace(1, 0, ramp)  # the clip is cut mid-scream, so ease it out
    return x


def scream():
    return synthetic_scream(noise(1.5))


def synthetic_scream(breath):
    """A scream: a high, wavering voice that jumps up, hangs on, and gives out. Crude, but it reads as one."""
    d = 0.9
    tt = t(d)
    breath = breath[:len(tt)]
    pitch = 520 + 420 * (1 - np.exp(-tt * 9)) - 220 * np.clip(tt - 0.5, 0, None)
    pitch *= 1 + 0.03 * np.sin(2 * np.pi * 6.5 * tt) * np.minimum(tt / 0.4, 1)
    phase = 2 * np.pi * np.cumsum(pitch) / RATE
    voice = sum(np.sin(h * phase) / h for h in range(1, 25))
    voice = voice * (1 + 0.35 * np.sin(2 * np.pi * 70 * tt)) + bandpass(breath, 1500, 6000) * 0.35  # rasp and breath
    vowel = bandpass(voice, 700, 1000) + 0.7 * bandpass(voice, 1100, 1400) + 0.45 * bandpass(voice, 2500, 3100)
    env = np.minimum(tt / 0.04, 1) * np.minimum((d - tt) / 0.2, 1) * (1 - 0.25 * tt / d)
    return np.tanh(2.2 * vowel / max(1e-9, np.abs(vowel).max())) * env


# --- Transport sounds -------------------------------------------------------------------------------------------
# These play on a loop while a leg travels, so each one is built to repeat without a click: tones use a whole number
# of cycles per loop, noise is cross-faded end to start, and events wrap round the end.

LOOP_FADE = int(0.12 * RATE)


def looped_noise(seconds, shape):
    """Filtered noise that joins up with itself. [shape] is a function that filters a noise array."""
    n, f, settle = int(seconds * RATE), LOOP_FADE, int(0.3 * RATE)
    x = shape(rng.standard_normal(n + f + settle))[settle:]
    ramp = np.linspace(0, 1, f)
    out = x[:n].copy()
    out[:f] = x[:f] * np.sqrt(ramp) + x[n:] * np.sqrt(1 - ramp)
    return out


def mix_loop(seconds, *layers):
    """Like [mix], but anything running past the end carries on at the start of the loop."""
    n = int(seconds * RATE)
    out = np.zeros(n)
    for start, x in layers:
        np.add.at(out, (int(start * RATE) + np.arange(len(x))) % n, x)
    return out


def engine(seconds, hz, harmonics, rolloff, cutoff):
    """An engine note: the firing frequency and its harmonics, with a slow wobble in loudness."""
    hz = round(hz * seconds) / seconds
    tt = t(seconds)
    note = sum(np.sin(2 * np.pi * h * hz * tt + h * 0.7) / h ** rolloff for h in range(1, harmonics + 1))
    wobble = 1 + 0.12 * np.sin(2 * np.pi * (1 / seconds) * tt * 2)
    # Filter three laps and keep the last, so the filter has settled and the loop's two ends match.
    return lowpass(np.tile(note * wobble, 3), cutoff, 2)[2 * len(tt):]


def walk():
    """Footsteps at a walking pace, one foot a little heavier than the other."""
    steps = []
    for i in range(4):
        thud = decay(np.sin(2 * np.pi * 95 * t(0.25)), 40) * 0.8
        scuff = decay(lowpass(noise(0.25), 1100), 30)
        steps.append((i * 0.5 + rng.uniform(-0.01, 0.01), (thud + scuff) * (1.0 if i % 2 == 0 else 0.75)))
    bed = looped_noise(2.0, lambda x: lowpass(x, 600)) * 0.04
    return mix_loop(2.0, (0, bed), *steps)


def bike():
    """Tyres humming on the road and the freewheel ticking over."""
    hum = looped_noise(2.0, lambda x: bandpass(x, 1500, 5000)) * 0.25
    ticks = [(k / 9.0, decay(bandpass(noise(0.03), 3000, 8000), 160) * rng.uniform(0.5, 0.9)) for k in range(18)]
    return mix_loop(2.0, (0, hum), *ticks)


def motorbike():
    """A twin's throb with wind rushing past."""
    note = engine(2.0, 33, 24, 0.8, 1400) + 0.6 * engine(2.0, 16.5, 12, 0.9, 700)
    return note * 0.6 + looped_noise(2.0, lambda x: lowpass(x, 900)) * 0.25


def car():
    """A smooth engine hum over road noise."""
    return engine(2.0, 25, 14, 1.2, 500) * 0.8 + looped_noise(2.0, lambda x: lowpass(x, 350)) * 0.5


def four_wd():
    """A lower, rougher engine, rumbling over gravel."""
    gravel = [(rng.uniform(0, 2.0), decay(bandpass(noise(0.02), 1500, 5000), 220) * rng.uniform(0.2, 0.6)) for _ in range(160)]
    return (engine(2.0, 20, 18, 1.0, 600) * 0.7 + looped_noise(2.0, lambda x: lowpass(x, 250)) * 0.7
            + mix_loop(2.0, *gravel) * 0.5)


def bus():
    """A diesel idling and rattling."""
    tt = t(2.0)
    idle = engine(2.0, 12, 30, 0.7, 450)
    rattle = looped_noise(2.0, lambda x: bandpass(x, 200, 900)) * (0.5 + 0.5 * np.sin(2 * np.pi * 12 * tt))
    return idle * 0.7 + rattle * 0.4 + looped_noise(2.0, lambda x: lowpass(x, 2500)) * 0.04


def train():
    """The clickety-clack of wheels over rail joints, over a steady rumble."""
    clacks = []
    for pair in range(4):
        for k, strength in enumerate((1.0, 0.75)):
            hit = decay(bandpass(noise(0.12), 700, 3500), 45) + 0.7 * decay(np.sin(2 * np.pi * 80 * t(0.12)), 35)
            clacks.append((pair * 0.6 + k * 0.12, hit * strength))
    rumble = looped_noise(2.4, lambda x: lowpass(x, 180)) * 0.6
    whine = looped_noise(2.4, lambda x: bandpass(x, 2000, 4000)) * 0.05
    return mix_loop(2.4, (0, rumble), (0, whine), *clacks)


def plane():
    """A cabin's steady engine drone, with the faintest swell."""
    tt = t(3.0)
    drone = looped_noise(3.0, lambda x: lowpass(x, 1100, 2)) * 0.8
    tones = sum(np.sin(2 * np.pi * f * tt) * a for f, a in ((120, 0.25), (240, 0.12), (360, 0.06)))
    whine = looped_noise(3.0, lambda x: bandpass(x, 1800, 2600)) * 0.1
    return (drone + tones + whine) * (1 + 0.1 * np.sin(2 * np.pi * tt / 3.0))


def boat():
    """A slow engine chug and water slapping the hull as it rises and falls."""
    tt = t(3.0)
    chug = engine(3.0, 9, 8, 1.5, 300)
    swell = 0.5 + 0.5 * np.sin(2 * np.pi * 2 * tt / 3.0)
    water = looped_noise(3.0, lambda x: lowpass(x, 1200)) * swell * 0.6
    slaps = [(rng.uniform(0, 3.0), decay(lowpass(noise(0.1), 800), 40) * rng.uniform(0.2, 0.5)) for _ in range(9)]
    return mix_loop(3.0, (0, chug * 0.7), (0, water), *slaps)


def fog_horn():
    """Two close, low notes: the long moan of a ship's horn."""
    d = 2.4
    tt = t(d)
    horn = sum(np.sin(2 * np.pi * f * h * tt) / h for f in (98, 103) for h in range(1, 21))
    env = np.minimum(tt / 0.15, 1) * np.minimum((d - tt) / 0.7, 1)
    return lowpass(horn, 900, 2) * env


def ship_bell():
    """A ship's bell struck twice: ding-ding."""
    ratios = (1.0, 2.0, 2.4, 3.0, 4.2, 5.4)
    amps = (1.0, 0.6, 0.5, 0.4, 0.25, 0.15)

    def strike(level):
        bt = t(1.7)
        return sum(np.sin(2 * np.pi * 660 * r * bt) * a * np.exp(-bt * 2.2 * (1 + 0.6 * i)) for i, (r, a) in enumerate(zip(ratios, amps))) * level
    return mix(2.6, (0, strike(1.0)), (0.9, strike(0.85)))


# --- Finish sounds ----------------------------------------------------------------------------------------------


def highpass(x, hz, order=3):
    b, a = butter(order, hz / (RATE / 2), "high")
    return lfilter(b, a, x)


def brass(freqs, seconds, vibrato=0.0):
    """A bright, brassy chord: harmonics of every note, rolled off above the top."""
    tt = t(seconds)
    voice = np.zeros_like(tt)
    for f in freqs:
        phase = 2 * np.pi * np.cumsum(f * (1 + vibrato * np.sin(2 * np.pi * 5.5 * tt))) / RATE
        voice += sum(np.sin(h * phase) / h ** 1.1 for h in range(1, 11))
    return lowpass(voice, 2600, 2)


def shaped(x, attack, release):
    n = len(x)
    ramp_in = np.minimum(np.arange(n) / (attack * RATE), 1)
    ramp_out = np.minimum((n - np.arange(n)) / (release * RATE), 1)
    return x * ramp_in * ramp_out


class seeded:
    """Gives a sound its own random numbers, so changing it can't change the sounds made after it."""

    def __init__(self, seed):
        self.seed = seed

    def __enter__(self):
        global rng
        self.saved, rng = rng, np.random.default_rng(self.seed)

    def __exit__(self, *exc):
        global rng
        rng = self.saved


def unit(x):
    return x / max(1e-9, np.abs(x).max())


def squash(x, drive=2.5):
    """Soft limiting: the drum and cymbal transients set the peak, so ease them down and the chord comes up."""
    return np.tanh(drive * unit(x)) / np.tanh(drive)


def ensemble(freqs, seconds, voices, detune_cents, vibrato):
    """Several slightly out-of-tune, wavering voices on every note: the thickness of a section playing together."""
    tt = t(seconds)
    out = np.zeros_like(tt)
    for f in freqs:
        for _ in range(voices):
            voice_hz = f * 2 ** (rng.uniform(-detune_cents, detune_cents) / 1200)
            wobble = 1 + vibrato * np.sin(2 * np.pi * rng.uniform(4.8, 5.8) * tt + rng.uniform(0, 6.28))
            phase = 2 * np.pi * np.cumsum(voice_hz * wobble) / RATE
            out += sum(np.sin(h * phase) / h for h in range(1, 13))
    return out


def brass_section(freqs, seconds):
    """Brass: dark at the start of the note, opening up to a bright blare."""
    raw = ensemble(freqs, seconds, 3, 5, 0.003)
    opening = 1 - np.exp(-t(seconds) / 0.12)
    return unit(lowpass(raw, 700, 2) * (1 - opening) + lowpass(raw, 3500, 2) * opening)


def string_section(freqs, seconds):
    return unit(lowpass(ensemble(freqs, seconds, 5, 9, 0.005), 4500, 2))


def timpani():
    """A big drum hit: a low note that drops in pitch, with the slap of the stick."""
    tt = t(1.0)
    phase = 2 * np.pi * np.cumsum(65 + 70 * np.exp(-tt * 14)) / RATE
    return decay(np.sin(phase), 4.5) + decay(lowpass(noise(1.0), 400), 25) * 0.5


def cymbal(seconds):
    return decay(highpass(noise(seconds), 4500), 2.4)


def orchestral_chord(seconds):
    """The big final chord: brass blaring over swelling strings, in a wide C major."""
    brass = shaped(brass_section([130.81, 196.0, 261.63, 329.63, 392.0, 523.25], seconds), 0.03, seconds * 0.45)
    strings = shaped(string_section([261.63, 329.63, 392.0, 523.25, 659.25, 784.0, 1046.5], seconds), 0.35, seconds * 0.4)
    return brass * 0.85 + strings * 0.6


def ta_da():
    """An orchestral fanfare: a short brass hit on the timpani, then the whole orchestra on one long chord. Ta-DAAH."""
    with seeded(11):
        ta = shaped(brass_section([196.0, 261.63, 329.63, 392.0], 0.28), 0.015, 0.08)
        chord = orchestral_chord(2.1)
        return squash(mix(2.7, (0, ta * 0.7), (0, timpani() * 0.6), (0.32, chord), (0.32, cymbal(2.0) * 0.4), (0.32, timpani() * 0.9)))


def flourish():
    """A harp runs up the scale into the same big chord, with a cymbal swell."""
    with seeded(12):
        def pluck(f):
            pt = t(1.0)
            return (np.sin(2 * np.pi * f * pt) + 0.4 * np.sin(4 * np.pi * f * pt)) * np.exp(-pt * 5)

        notes = (261.63, 329.63, 392.0, 523.25, 659.25, 784.0, 1046.5, 1318.5)
        run = [(i * 0.075, pluck(f) * 0.5) for i, f in enumerate(notes)]
        return squash(mix(2.9, *run, (0.75, orchestral_chord(2.0)), (0.75, cymbal(2.0) * 0.4), (0.75, timpani() * 0.9)))


def drum_roll():
    """A snare roll that speeds up and swells, ending on a cymbal crash."""
    d = 1.5
    layers, when = [], 0.0
    while when < d:
        level = 0.35 + 0.65 * when / d
        layers.append((when, decay(bandpass(noise(0.07), 180, 3500), 45) * level * rng.uniform(0.7, 1.0)))
        when += 1.0 / (16 + 20 * when / d)
    cymbal = decay(highpass(noise(1.5), 3500), 2.8) * 1.0
    thump = decay(np.sin(2 * np.pi * 60 * t(0.4)), 12) * 0.9
    return mix(d + 1.5, *layers, (d, cymbal), (d, thump))


def crowd_cheer():
    """A crowd clapping, and ten voices whooping up into a 'yay'."""
    d = 3.0
    n = int(d * RATE)
    kernels = [decay(bandpass(noise(0.03), lo, hi), 140) for lo, hi in ((800, 3000), (1200, 4500), (600, 2500), (1500, 6000))]
    claps = np.zeros(n)
    for pos, amp in zip(rng.beta(2.0, 2.4, 1400) * (d - 0.05), rng.uniform(0.2, 1.0, 1400)):
        k = kernels[rng.integers(len(kernels))]
        i = int(pos * RATE)
        claps[i:i + len(k)] += k[: n - i] * amp
    voices = np.zeros(n)
    for _ in range(10):
        length = 1.8
        vt = t(length)
        glide = 1 + 0.25 * (1 - np.exp(-vt / 0.25)) + 0.02 * np.sin(2 * np.pi * rng.uniform(4.5, 6.5) * vt)
        phase = 2 * np.pi * np.cumsum(rng.uniform(170, 330) * glide) / RATE
        voice = sum(np.sin(h * phase) / h for h in range(1, 21))
        vowel = bandpass(voice, 400, 550) + 0.8 * bandpass(voice, 1800, 2300) + 0.4 * bandpass(voice, 700, 900)
        start = int((0.35 + rng.uniform(0, 0.25)) * RATE)
        voices[start:start + len(vt)] += shaped(vowel, 0.12, 0.9)[: n - start]
    claps /= max(1e-9, np.abs(claps).max())
    voices /= max(1e-9, np.abs(voices).max())
    swell = np.sin(np.pi * np.clip(t(d) / d, 0, 1)) ** 0.6
    return (claps * 0.8 + voices * 0.6) * swell


def siren():
    """An emergency siren: a wail that sweeps up and down, twice."""
    d = 2.4
    tt = t(d)
    freq = 1050 + 400 * np.sin(2 * np.pi * tt / 1.2 - np.pi / 2)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    wave = np.sin(phase) + 0.4 * np.sin(2 * phase) + 0.2 * np.sin(3 * phase)
    return shaped(np.tanh(1.6 * wave), 0.05, 0.25)


_splash_parts = {}


def splash_parts():
    """The breath noise and the splash, drawn once so both takes of the water incident share them."""
    if not _splash_parts:
        _splash_parts["breath"] = noise(1.5)
        _splash_parts["splash"] = splash()
    return _splash_parts


def level(x, peak):
    return x / max(1e-9, np.abs(x).max()) * peak


def scream_splash():
    """A fall into water: the recorded scream, cut off by a splash. Each at its own full level first, or the splash is lost."""
    return mix(2.4, (0, level(recorded_scream(), 0.7)), (1.0, level(splash_parts()["splash"], 0.9)))


def scream_splash_synth():
    """The same with the synthesised scream, which is shorter."""
    parts = splash_parts()
    return mix(2.1, (0, level(synthetic_scream(parts["breath"]), 0.7)), (0.7, level(parts["splash"], 0.9)))


# Name -> (how to make it, whether it loops). Loops are left unfaded so they join up without a dip. Keep new
# sounds at the end: the random generator is shared, so adding in the middle would change every sound after it.
SOUNDS = {
    "sfx_crash": (crash, False),
    "sfx_screech": (screech, False),
    "sfx_splash": (splash, False),
    "sfx_sputter": (sputter, False),
    "sfx_thunder": (thunder, False),
    "sfx_alert": (alert, False),
    "sfx_scream_synth": (scream, False),
    "sfx_scream": (recorded_scream, False),
    "amb_walk": (walk, True),
    "amb_bike": (bike, True),
    "amb_motorbike": (motorbike, True),
    "amb_car": (car, True),
    "amb_fourwd": (four_wd, True),
    "amb_bus": (bus, True),
    "amb_train": (train, True),
    "amb_plane": (plane, True),
    "amb_boat": (boat, True),
    "sfx_fog_horn": (fog_horn, False),
    "sfx_ship_bell": (ship_bell, False),
    "fin_ta_da": (ta_da, False),
    "fin_drum_roll": (drum_roll, False),
    "fin_flourish": (flourish, False),
    "fin_crowd_cheer": (crowd_cheer, False),
    "sfx_siren": (siren, False),
    "sfx_scream_splash": (scream_splash, False),
    "sfx_scream_splash_synth": (scream_splash_synth, False),
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, (make, loops) in SOUNDS.items():
            x = make()
            if not loops:
                x = fade(x)
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
