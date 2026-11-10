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
for l in fr en; do
  curl -L $R/asr-models/sherpa-onnx-streaming-zipformer-$l-kroko-2025-08-06.tar.bz2 | tar xj
done
for v in fr_FR-siwis-medium fr_FR-upmc-medium fr_FR-tom-medium fr_FR-miro-high \
         en_US-amy-medium en_US-lessac-medium en_US-ryan-medium en_GB-alan-medium \
         en_GB-jenny_dioco-medium en_US-libritts_r-medium; do
  curl -L $R/tts-models/vits-piper-$v.tar.bz2 | tar xj
done
```

Put `models/` here, or point `VCB_MODELS` at it.

## Run

Run these from any scratch directory. Clips and caches land in the current directory.
`langs.py` holds each language's voices, accepted phrases and near-miss narration.

```sh
T=path/to/tools/voice-command-bench
for l in fr en; do
  python3 $T/gen.py $l          # clips/ + clips_$l.json + neg_$l.json
  python3 $T/decode.py $l       # ev_${l}_greedy.json: AsrEngine-identical event stream
  python3 $T/export_fixture.py ev_${l}_greedy.json \
      path/to/app/src/test/resources/voice-commands/kroko-$l-synthetic.tsv.gz
done
```

The app's unit tests then report per-phrase numbers (`VoiceCommandBenchTest`).
Piper (VITS) samples noise as it synthesizes, so every `gen.py` run produces slightly
different audio and a different fixture. An independent regeneration of the first,
French-only set scored within a few points of the checked-in one. Compare runs by
their numbers, not their bytes.

Optional comparisons:

- `python3 $T/make_hotwords.py && python3 $T/decode.py fr hotwords` decodes with
  decoder-level hotword biasing, and `decode.py fr mbs` with plain beam search. Both
  were measured worse than greedy for narration (see the doc).
- `python3 $T/phrases.py fr|en` reports word-exact transcription rates for the
  candidate phrases in `langs.py`.

`common.stream()` mirrors `AsrEngine.pump()`: 100 ms chunks, the same endpoint rules,
partials only when the word list changes, a final at each endpoint, and words merged
the way `mergeBpeTokens` does it.
