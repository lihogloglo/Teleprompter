# Page-back commands during voice following

Decision: 2026-09-06. English **Command rewind**, French **Commande recul**.
Revised 2026-11-10 (v0.9.5) after measuring the decoder: see *Measured* below.

## Prior art and existing evidence

- Amazon's [voice invocation design guidance](https://developer.amazon.com/en-US/docs/alexa/interaction-model-design/design-the-invocation-name-for-your-skill.html)
  recommends phonetically distinct expressions that are easy to pronounce. It warns
  about unfamiliar invented words and alternative written forms, and recommends
  repeated spoken testing.
- SPEC section 5.3 records device trouble with both multiword commands and fragmented
  recognition of `rembobine` (`ram bobine`, `rambobine`).
- Production keyword handling on streaming transducers is either contextual biasing of
  the decoder (sherpa-onnx hotwords: an Aho-Corasick context graph over BPE pieces,
  applied during `modified_beam_search`) or a separate keyword spotter. sherpa-onnx's
  `KeywordSpotter` ships only English and Chinese KWS models.

## Measured (2026-11-10)

User report: the command "doesn't always catch my voice, I often have to say it
several times", and there is "a lot of lag even when it works". Earlier versions were
tuned on guessed spellings. This round measured the real decoder. The bench
(`tools/voice-command-bench`) feeds synthetic French speech through kroko-fr with
exactly AsrEngine's chunking, endpoint rules and word merging:

- 5 piper voices × 3 speeds.
- The command alone or inside narration, in three styles: plain, with a comma, or
  "Commande. … Recul." with a pause.
- Each clip clean and with 10 dB white noise at 0.3 gain.
- 90 narration-only clips full of near-misses.

### Why it missed

What the model writes for spoken "commande recul" (greedy, clean):

| Decoder output | Why |
|---|---|
| `Commande recul`, `commande recule`, `La commande recule`, `commande reculent` | fine, or a homophone ending |
| **`Comment de recul`**, `Comment te recule`, `Comment trecule`, `Comment recul` | the /d/ of *commande* slides onto the next word |
| `Commande. | Recul.` | a pause ≥ 0.7 s puts the words in two utterances |
| `Commande cul.`, `commande re cul` | split or clipped ending |

The v0.9.4 detector required exactly `commande` + `recul|recule`, adjacent, in one
utterance. It caught **44%** of clean and **28%** of noisy clips. "Comment de recul"
alone accounts for most of the gap.

### Why it lagged

`encoder.onnx` metadata: `decode_chunk_len=128`, `T=141`. **kroko-fr decodes audio
in 1.28 s chunks** with 0.13 s of look-ahead. A word therefore surfaces 0.13–1.4 s
after it is spoken, and that cost is fixed by the export. On top of it, v0.9.4 waited
for a *word boundary*: the next word (one more 1.28 s chunk) or the endpoint (0.7 s of
silence, quantized to a chunk). Median detection was **1.54 s** after the command
ended, and around 2.2 s at p90.

### What was tried and rejected

- **Hotword biasing.** It was believed blocked because no `bpe.vocab` ships. The
  sherpa-onnx 1.13.4 source shows otherwise: the vocab is only used to split hotword
  text into pieces, which are then looked up in tokens.txt by their raw `▁`
  spelling. A generated minimal vocab holding the pieces the decoder itself emits
  (`▁c om m and e ▁re cul`) works: "Comment de recul" becomes "commande recul". But it
  requires `modified_beam_search`, which raised narration WER from 5.3% to 6.3–9.5%
  depending on boost. The tolerant detector below did as well or better on greedy
  output. Following is the main feature, so it is not adopted
  (`tools/voice-command-bench/make_hotwords.py` reproduces it).
- **A different model.** The chunking is baked into the export. Kroko is otherwise the
  better follower (SPEC M5 slice 5).
- **Firing on any `comment` + `recul…`.** "Comment reculer sans perdre la face"
  false-fired on 8 of 90 narration clips, because a growing "recul" later became
  "reculer". This is why weak prefixes must wait for a stable ending.

### Result (VoiceCommandBenchTest, same real-decoder fixture)

| | v0.9.4 | v0.9.5 |
|---|---|---|
| hit, clean | 44.4% | **78.8%** |
| hit, noisy | 27.7% | **47.7%** |
| median delay after the command ends | 1.54 s | **1.09 s** |
| false fires, 90 narration clips | 0 | **0** |
| double fires for one command | 0 | **0** |

An independently regenerated clip set (TTS sampling differs on every run) gave
48.8% → 78.8% clean, 28.8% → 46.6% noisy, 1.51 s → 1.16 s, and 0 false fires.

The remaining misses are acoustic: the model hears something else entirely ("Comment
entre-t-il ?", "Tout le monde recule"). No detector rule can recover those safely.
About 1 s of delay remains, which is the decoder's chunk.

### The phrase itself

Word-exact transcription rate by candidate phrase (`phrases.py`, 60 clips each):

| Phrase | clean | noisy |
|---|---|---|
| Commande recul | 13% | 17% |
| Retour, retour | 73% | 73% |
| Page précédente | 60% | 80% |
| Marche arrière | 57% | 47% |
| Remonte, remonte | 57% | 40% |

"Commande recul" is acoustically weak for this model. The tolerant grammar is what
makes it usable. If misses remain a problem on the device, adding "Retour retour" as a
second accepted phrase is the strongest measured option. It is not shipped yet,
pending the user's choice.

## Implementation contract (v0.9.5)

Follow voice recognizes its command while playing, armed or paused. The phrase is
`PREFIX [FILLER] ENDING` (`CommandGrammar`):

- **French.**
  - Strong prefixes: `commande(s)`, `commander`, `commandez`, `commandent`,
    `commandé(e)(s)`, `comande`, `commende`.
  - Weak prefix: `comment`.
  - Fillers: `de`, `te`, `ne`, `d`.
  - Endings after a strong prefix: `[dt]?(re)?cul(e|es|s|ent|er|ez|ee)?`.
  - Endings after a weak prefix: `[dt]?recul(e|es|s)?`. No verb forms, since
    "comment reculer" is ordinary French.
  - A split `re` + `cul…` is joined.
- **English.** `command(s|ed)` + `rewind(s|ing|ed)` or `rewound`, and `re` + `wind`
  is joined. Not measured on kroko-en yet.

When it fires:

- A strong prefix fires the moment its ending appears in a partial, even as the
  still-growing last word. Greedy transducer output only extends, and every
  extension of "commande recul…" is still the command.
- A weak prefix fires only once its ending is stable: a later word exists, or the
  utterance is final.
- A final that ends on `PREFIX [FILLER]` arms the next utterance (id + 1 only).
  Its first word, starting within 2.0 s, may complete the command.

Script guard and tracking:

- When the visible script text contains a non-canonical command form, only the
  exact `commande recul|recule` fires while it is on screen. Reading the script
  can't page back.
- Possible command words (a trailing prefix, filler, growing ending, a growing
  "com…", or the head of an armed utterance) are held out of script tracking until
  resolved. Confirmed commands and earlier utterance words are blanked, keeping
  indices and timestamps for Matcher deduplication and trace replay.

Effect, unchanged from v0.9.4:

- Move back one reading viewport per occurrence, clamped to the beginning.
- Re-anchor before the first word at the new eyeline, then hold until reading
  advances.
- Play/pause and armed state are preserved. Touch and the session summary take
  priority.
- New in v0.9.5: a green "↑ Commande recul" pill confirms the jump for 1.2 s.
  Without it, a 1 s-late command gets repeated, and that pages back twice.

## Validation and limits

JVM coverage:

- `FollowVoiceCommandsTest` covers each decoder variant, near-miss narration, weak
  versus strong timing, endpoint spanning and its time and utterance bounds, the
  script guard, deduplication, retake through the actual Matcher, and trace
  serialization.
- `VoiceCommandBenchTest` replays the real-decoder fixture and enforces the result
  table above as floors.

The fixture is synthetic. Device testing with the user's own voice, microphone
distance and room is still the real measure. A trace from a session
(Settings → share latest follow trace) shows the words the decoder produced for
each attempt.
