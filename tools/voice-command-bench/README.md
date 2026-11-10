# Voice-command bench

Measures how the real French decoder (kroko-fr, the app's model) transcribes the
follow-mode page-back command, so `FollowVoiceCommands` can be tuned against actual
decoder output instead of guessed spellings. Output: the replay fixture
`app/src/test/resources/voice-commands/kroko-fr-synthetic.tsv.gz`, consumed by
`VoiceCommandBenchTest`. Findings: `docs/follow-voice-commands.md`.

Clips are synthesized (piper voices via sherpa-onnx TTS), so the numbers rank
detector and decoder choices against the model's real confusions. They are **not**
field accuracy. TTS is not a person, and white noise is not a room.

## Setup (once)

```sh
pip install sherpa-onnx==1.13.4 soundfile numpy   # 1.13.4 = the app's bundled JNI
mkdir models && cd models
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
curl -L $R/asr-models/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2 | tar xj
for v in siwis-medium upmc-medium tom-medium miro-high; do
  curl -L $R/tts-models/vits-piper-fr_FR-$v.tar.bz2 | tar xj
done
```

Put `models/` here, or point `VCB_MODELS` at it.

## Run

Run these from any scratch directory. Clips and caches land in the current directory.

```sh
T=path/to/tools/voice-command-bench
python3 $T/gen.py                       # clips/ + clips.json (90) + neg.json (45)
python3 $T/decode.py greedy             # ev_greedy.json: AsrEngine-identical event stream
python3 $T/export_fixture.py ev_greedy.json \
    path/to/app/src/test/resources/voice-commands/kroko-fr-synthetic.tsv.gz
```

The app's unit tests then report the detector's numbers (`VoiceCommandBenchTest`).
Piper (VITS) samples noise as it synthesizes, so every `gen.py` run produces slightly
different audio and a different fixture. An independent regeneration scored clean
48.8% → 78.8%, noisy 28.8% → 46.6%, median delay 1.51 s → 1.16 s, and 0 false fires,
in line with the checked-in fixture. Compare runs by their numbers, not their bytes.

Optional comparisons:

- `python3 $T/make_hotwords.py && python3 $T/decode.py hotwords` decodes with
  decoder-level hotword biasing, and `decode.py mbs` with plain beam search. Both
  were measured worse than greedy for narration (see the doc).
- `python3 $T/phrases.py` reports word-exact transcription rates for alternative
  command phrases.

`common.stream()` mirrors `AsrEngine.pump()`: 100 ms chunks, the same endpoint rules,
partials only when the word list changes, a final at each endpoint, and words merged
the way `mergeBpeTokens` does it.
