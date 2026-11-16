# Sound sources

`scream_elevenlabs.mp3` is a scream generated with ElevenLabs' text-to-sound-effect tool (prompted for a classic
movie "stock scream" in the style of a man falling; it is a new recording, not a copy of any film's). It is
trimmed to 1.2 s by `tools/generate_sfx.py` and is one of the two screams the app picks between, for falls and water
mishaps (`sfx_scream.ogg` and `sfx_scream_splash.ogg`).

ElevenLabs' terms for generated sound effects depend on the account's plan (some plans restrict commercial use or
require attribution), so check the plan this was generated under before publishing a release. The other take of each
(`sfx_scream_synth.ogg`, `sfx_scream_splash_synth.ogg`) is synthesised, so dropping the recorded ones from the app
is a small change.

Every other sound in the app is synthesised by `tools/generate_sfx.py` from noise and tones.
