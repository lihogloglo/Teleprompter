# Follow-voice: audit + industry research (2026-08-02)

> In-depth audit of the current follow-voice pipeline plus web research on how commercial
> products and the literature solve script-following. Companion to SPEC §3. No code was
> changed in this pass; this is the evidence base for the follow-voice overhaul.

## Part 1 — Audit of the current implementation

### Symptom: "stops working after a while"

1. **Mic death is silent and unrecoverable.** `AsrEngine.capture()` loops
   `if (n <= 0) continue` on `AudioRecord.read`. When the record dies mid-session
   (audio server restart, app grabbing the mic, OEM power management), `read()` returns
   negative error codes forever: the loop busy-spins on a core, the overlay still says
   "listening", follow silently dies. No detection, no restart, no surfaced error.
   There is also no foreground service with `microphone` type (SPEC backlog) protecting
   long sessions on aggressive OEM builds.
2. **Decode falling behind real time silently drops audio.** One thread does
   read → accept → decode sequentially. If a 100 ms chunk takes >100 ms to decode
   (thermal throttling 15+ min into a session; FR large model on `numThreads = 2`), the
   1-second AudioRecord buffer overruns and the OS discards audio → garbled words → miss
   rate climbs → freeze → LOST. `decodeMs` is displayed but the *trend* is untracked.
3. **Dropped `Final` events lose words permanently.** The event flow is
   `DROP_OLDEST` (capacity 256), collector on the main thread. A dropped Partial is
   harmless (next carries the full list); a dropped Final is not — the next event has a
   new utterance id, `consumedWords` resets, tail words of the utterance are never fed.
4. **Anchor creep during improv poisons the rejoin.** Freeze needs 10/12 misses, and
   until it fires the forward scan stays live (`maxSkipAhead = 10`, `contentLen = 4`).
   During a long ad-lib, coincidental content words within the next 10 script tokens
   advance the anchor *and reset the freeze window*. The anchor creeps; the scroll
   drifts during improv; the rejoin then has to fight backward, and with screen-bounded
   search the reader's true position may have scrolled away → unrecoverable without a key.
5. **(From research, prime suspect)** sherpa-onnx ≥1.10 does **not** reset encoder
   states on endpoint (`Reset()` clears only decoder state; see issue #1700, #924).
   On streaming zipformer, recognition quality decays across long sessions with silence
   gaps — deletion errors after endpoints. The app never recreates the stream during a
   session, so ASR quality degrades the longer you prompt. This *looks like* the matcher
   dying when it's the recognizer.

### Symptom: "jumpy / not flowy"

6. **First-order position servo chasing a stepping target.** The drive computes
   `scrollBy(err * (1 - exp(-dt/τ)))`, τ = 0.20 s catch-up / 0.50 s ease-back
   (PrompterScreen). The target steps discretely on each committed word (2–4×/s); a
   first-order exponential covers 63 % of each step in τ then crawls → lurch–stall at
   word rate ≈ the "jumpy" feel. **No velocity state anywhere.** SPEC §3.2 promises
   rate-driven velocity between matches; the implementation only adds a static lead
   offset (`FOLLOW_LEAD_SEC`, capped at one line). Between matches the text stops.
7. **Two systematic backward tugs.** (a) The provisional advance publishes an anchor a
   word early on exact prefix match, then falls back on revision → visible backward drag
   under ease-back τ 0.5 s. (b) On freeze, the lead term (up to a line height) is removed
   instantly → the text tugs backward every time improv starts.

### Recognition quality left on the table

8. **Tiny EN model + weakest decoder mode.** `en-20M` (2023) on a Dimensity 9400 that
   could run far larger models. Decoding is default `greedy_search`; the vendored API
   exposes `modified_beam_search`, `hotwordsFile`/`hotwordsScore` and
   `createStream(hotwords)` — all unused. The script's vocabulary is known in advance;
   contextual biasing is the single biggest unused lever (SPEC §9 anticipated this).
9. **Matcher moves on single-word evidence.** Every matched token can advance the anchor.
   Industry consensus (below) is a minimum evidence quantum of 2–4 words before moving,
   with jump gating proportional to distance.

### What is sound and should be kept

Pure-Kotlin matcher + replay harness (30+ tests incl. the duplicated-phrase trap),
screen-bounded matching, forward-match-outranks-run, utterance-relative timestamps,
shared normalization through `matchTokens` on both sides, BPE merge, number expansion.
The problems are concentrated in the ASR engine's failure handling, the motion
controller, and unused recognizer levers — not the overall architecture.

---

## Part 2 — Industry research (background agent report, 2026-08-02)

### (a) Commercial products

**PromptSmart (VoiceTrack)** — the reference implementation, patented in
**US 9,953,646 B2** (Belleau Technologies): https://patents.google.com/patent/US9953646B2/en

1. *Hypothesis scoping*: each new ASR partial is diffed against the last matched one to
   isolate novel words; **a scoped hypothesis needs ≥4 words** before matching at all.
2. *Bag-of-words dot-product over a 20-word lookahead* after the last confirmed match;
   minimum score 3.0.
3. *N-gram tie-break*: if 3+ windows clear threshold, top 3 rescored with bigrams/
   trigrams, ignoring ~70 most common function words.
4. *Off-script speech is simply discarded* — no window scores → position does not move.
5. *Zone-based scroll*: keep the marker in the **middle third**; drift low → speed up,
   drift high → slow/stop. The match drives a **rate controller**, never a jump.
6. *Manual scroll re-anchors the matcher automatically.*

Known failure modes (their own support docs): muffled/low-gain mics freeze scrolling;
**room echo → "VoiceTrack will think there are two speakers"**; too many line breaks
get it stuck; pauses >1 s between short items break the 4-word accumulation.
(https://promptsmart.com/help, https://promptsmart.com/how-it-works)

**Microsoft patents US 11,902,690 B2 / US 12,167,159 B2** — ASR model + separate "text
scrolling model" predicting the reading position; on mismatch auto-scroll pauses with a
visible indicator, re-engages on resync. (https://patents.google.com/patent/US11902690B2/en)

**Elgato Prompter (Voice Sync)** — local neural ASR (requires RTX 2060+/Apple Silicon);
Studio 2.0 added a **sensitivity slider** (match-acceptance threshold). Third-party
review: reliable ~80 % of the time — even the best-funded implementations get stuck.
(https://www.elgato.com/us/en/explorer/products/teleprompter/elgato-studio-2-0-the-prompter-experience-rebuilt/,
https://bigvu.tv/blog/elgato-teleprompter)

**Speakflow (Flow)** — browser, cloud ASR; same behavior contract. **Teleprompter.com
(VoiceGlide)** — same contract; recommends short paragraphs, clean line breaks.
**BIGVU — does NOT do voice-follow at all** (WPM only): the feature is hard enough that
a major player skips it.

**Shared behavioral contract** (the de-facto industry spec):
scroll follows pace continuously (rate modulation, not word-snapping); silence → freeze;
off-script → freeze and wait, never scroll on unmatched speech; return → re-lock fast;
manual scroll always wins and re-anchors; all degrade with echo/poor mics/fast speech.

### (b) Algorithmic state of the art

- **PromptSmart patent algorithm** (above): min-evidence + bounded lookahead +
  order-free first pass, order-aware tie-break.
- **jlecomte/voice-activated-teleprompter** (`src/lib/speech-matcher.ts`, ~60 lines):
  Levenshtein over growing prefixes of a window `[last, last + 2·len + 10]`; min-distance
  prefix ends at the new position; monotonic; click-to-re-anchor.
  https://github.com/jlecomte/voice-activated-teleprompter
- **laanlabs/VoicePrompter** (`Services/TextMatcher.swift`, WhisperKit on-device) — the
  most production-shaped open matcher: window `[pos−3, pos+lookForward]`; per-word fuzzy
  tolerance; **proximity bonus** (weak nearby match beats strong far match);
  **jump gating** (≥2 matched words to move at all; larger jumps need more);
  acceptance threshold, below → stay put; three user modes trading fuzz/window/threshold
  (strict 1/12/0.55, mix 2/20/0.40, loose 3/35/0.25); sub-500 ms speech-to-scroll.
  https://github.com/laanlabs/VoicePrompter
- **ReverentGeek write-up** — bag-of-words multiset overlap window matching + Unity
  SmoothDamp scrolling; manual input yields auto-scroll for a few seconds.
  https://reverentgeek.com/teaching-my-teleprompter-to-listen/
- **Online Time Warping (Dixon 2005, MATCH)** — the score-following classic: accumulated
  cost vector over the reference, banded search centered on expected position, argmin =
  current position; linear time/space; principled handling of insertions (ad-libs),
  deletions (skips), drift. https://arxiv.org/pdf/2205.03247 (overview),
  https://arxiv.org/abs/2401.09200 (lyrics).
- **Smith-Waterman local alignment** for wide-window/whole-script re-acquisition
  (lost mode) with tunable error weights.
- **Reading-tracker research** (pointer network, https://arxiv.org/abs/2310.11486):
  even end-to-end models reach only ~88 % word-level position accuracy → **the scroll
  layer must tolerate position noise**; word-exact tracking is a mirage.
- **Consensus rules**: unmatched speech is discarded (freeze); skips followed only on
  multi-word high-confidence in-window match; small look-back (~3 tokens) for re-reads;
  **never move on a single matched word** (PromptSmart 4, VoicePrompter 2).

### (c) sherpa-onnx operational guidance

- **Canonical lifecycle**: one long-lived `OnlineStream`; 100 ms chunks;
  `while (isReady) decode`; on `isEndpoint` → final text + `reset(stream)`. Do not
  recreate recognizer per utterance.
- **Critical**: since v1.10.0 `Reset()` does **not** reset encoder states (the line is
  commented out; #924 added it in 1.9.26, later disabled). Issue **#1700** documents
  deletion errors / quality decay after endpoints in long sessions on zipformer2.
  Mitigations: periodically hard-recreate the stream at natural endpoints
  (`stream.release(); recognizer.createStream()`), every N segments or after long
  silences; or rebuild with the reset line enabled.
  https://github.com/k2-fsa/sherpa-onnx/issues/1700
- **Endpointing**: rule1 2.4 s, rule2 1.2–1.4 s, rule3 20 s (docs). For a prompter:
  **rule2 ≈ 0.6–0.8 s** so segments finalize during natural pauses; rule3 forces resets
  during continuous reading. https://k2-fsa.github.io/sherpa/onnx/websocket/online-websocket.html
- **Partials for tracking, finals for commitment.** Copy whisper-streaming's
  **LocalAgreement**: a token is stable once two consecutive partials agree on it; only
  stable tokens move position. https://arxiv.org/html/2307.14743
- **Hotwords**: transducer + `modified_beam_search` only (greedy ignores them);
  `hotwordsFile`/`hotwordsScore` (default 1.5), per-stream via `createStream(hotwords)`,
  per-word boost `WORD :2.5`, Aho-Corasick token-level. Feed the script's *distinctive*
  words (names, jargon), not the whole script. Benchmark maxActivePaths=4 CPU cost.
  https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
- **Models**: streaming zipformer family; `en-2023-06-26` (full) vs `en-20M` (mobile).
  Encoder state is fixed-size — no inherent memory growth on CPU/Android.
  https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
- **VAD**: bundled Silero VAD can run in parallel purely as a fast "speaking right now?"
  signal for freeze/resume — faster than ASR silence detection.

### (d) Scroll motion control

Unanimous: **never position-snap; convert the noisy word-index into a target and chase
it with a rate controller.**

1. Two layers: tracker (noisy anchor, few Hz) / motion (every frame).
2. **Zone-based deadband** (PromptSmart): marker in the middle third → no correction at
   all inside the zone; speed up / slow down at the edges. Kills micro-jitter.
3. **Critically damped spring with velocity state** (SmoothDamp, Game Programming Gems 4;
   Firefox scroll uses a damping-ratio ≥1 spring). Frame-rate-independent half-life
   form; in Compose drive via `withFrameNanos`, never restart `animateScrollTo` per
   estimate. smoothTime ≈ 0.3–0.6 s. "Double damping" (damp the target too) fits a
   word-stepping target. https://www.alexisbacot.com/blog/the-art-of-damping
4. **WPM feed-forward**: advance the target at the measured rate between matches;
   matches only *correct* it. This is what makes commercial products flowy — motion with
   corrections, not motion made of corrections.
5. **Pause semantics**: freeze = decay velocity to zero over ~300 ms (not instant), on
   re-match ease back in. Freeze on "no new *matched* words", not "no audio".
6. Manual override wins instantly; auto resumes after a grace period and re-anchors.

### (e) End-to-end composite (how the best implementations work)

State: `committedAnchor`, `wpmEstimate` (EMA), `mode ∈ {FOLLOWING, WAITING, LOST}`,
scroll position + spring velocity.

Per chunk: long-lived stream (+ periodic recreation, #1700) → stabilize partials
(LocalAgreement) → diff to novel stable tokens → match in `[anchor−3, anchor+20..35]`
with normalization, per-word fuzz, proximity bonus, jump gating (≥2 words to move, ≥4
for far jumps), threshold else discard → lost mode after ~5 s of unmatched speech runs
wide-window Smith-Waterman with a high bar (≥5–6 words) + visible "tap to re-sync"
affordance → motion layer: deadband + damped spring + WPM feed-forward; velocity → 0 on
WAITING; touch overrides and re-anchors.

**Why implementations get jumpy/sticky** (checklist): matching every partial instead of
novel stable tokens; snapping/re-triggered animations instead of persistent spring +
deadband; no jump gate (single-word teleports); no lost mode / no manual re-anchor;
encoder-state decay over long sessions (#1700) starving the matcher; number/punctuation
normalization gaps; environment (echo, low mic gain) — worth an input-level meter.

---

## Part 3 — Prioritized fix plan

> Status 2026-08-02: items 1–4, 6–7, 9–11 shipped in v0.9.0 (SPEC "M5 slice 5"); item 5
> (foreground service) deferred — the mic-death recovery covers the observed failures and
> the app is always foreground with the screen held on; item 8 (hotwords) deferred — no
> `bpe.vocab` in our model repos (would need to be generated and hosted); item 10 solved
> via the kroko 2025 models instead of en-2023-06-26; item 12 (OLTW) remains the long-term
> option if the gated heuristics still misbehave on device.

**P0 — reliability ("stops working"):**
1. AsrEngine: detect `read() < 0`, tear down + recreate AudioRecord (bounded retries),
   surface the error state in the overlay; stop the silent spin.
2. Periodic stream recreation at endpoints (every N utterances / after long silence) to
   dodge #1700 encoder-state decay.
3. Track decode-lag trend (EMA of decodeMs vs 100 ms budget); warn/raise `numThreads`
   (2 → 4 on the 9400) when sustained over budget; consider decoupling capture thread
   from decode thread with a ring buffer.
4. Make `Final` events lossless (Channel with suspend emit, or replay-on-drop).
5. Foreground service (`microphone` type) for session lifetime.

**P1 — flow ("jumpy"):**
6. Rewrite the drive as: WPM feed-forward velocity + eyeline-zone deadband + critically
   damped spring **with velocity state** (SmoothDamp form). Freeze = decay velocity over
   ~300 ms (kill the instant lead-drop tug); rejoin = ease-in.
7. Let the deadband absorb provisional fallbacks instead of springing on them.

**P2 — recognition quality:**
8. `modified_beam_search` + per-script hotwords (rare/distinctive words) via
   `createStream(hotwords)`; benchmark CPU on device.
9. Endpoint rule2 1.2 → ~0.7 s.
10. Try the larger EN streaming zipformer (2023-06-26) — the 9400 can take it.

**P3 — matcher structure:**
11. Minimum-evidence gating: single-word advance only for the immediate next tokens;
    skips/jumps need ≥2–4 word runs (partially exists in the run tracker — extend to the
    forward scan to kill improv anchor creep).
12. Longer term: replace the heuristic stack with a banded OLTW cost vector (score-
    following style) for principled insert/delete/drift handling; Smith-Waterman for
    LOST recovery.
