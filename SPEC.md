# Teleprompter — Specification

> A state-of-the-art, voice-following teleprompter for Android. The prompter adapts to the
> speaker — not the other way around. Scripts live in Obsidian; the phone is the prompter.
>
> This document is the source of truth across sessions. Update it as decisions are made.

## 1. Vision

Every existing Android prompter falls into one of two camps: fixed-speed scrollers (you chase
the text) or closed-source subscription apps with voice tracking (PromptSmart, Teleprompter.com,
VoicePrompter, Beast). There is **no open-source, on-device, voice-following teleprompter for
Android**. We're building it.

The defining behavior: the app **listens** while you speak. It scrolls at *your* pace, **freezes
when you improvise**, and **catches up when you rejoin the script** — including when you jump
back to redo a line. All speech processing happens on-device; audio never leaves the phone.

### Design principles

1. **The text glides, never teleports.** Scroll position is always smoothly animated toward the
   anchor. No jumps, no jitter — jitter destroys reading.
2. **Never touch the phone mid-take.** Everything that matters during delivery is automatic or
   controllable by voice/remote key.
3. **Every automatic behavior has a manual override.** Voice-follow can always fall back to
   classic timed scrolling; any parameter can be pinned.
4. **Offline-first, private by default.** No network at runtime. Mic audio is processed
   on-device and discarded.
5. **Scripts are authored in Obsidian, not in the app.** The app is a *reader* of the vault.
   In-app editing is out of scope (small "quick-fix" edits are a maybe, much later).

## 2. Decisions made (2026-07-27)

| Decision | Choice | Rationale |
|---|---|---|
| Tech stack | **Native Kotlin + Jetpack Compose** | Low-latency audio, first-class ASR bindings, smooth scroll animation, SAF, foreground service |
| Speech engine | **sherpa-onnx streaming Zipformer** (Vosk as fallback candidate) | On-device, streaming partials, actively maintained, official Android/Kotlin API |
| Obsidian bridge (v1) | **Read the synced vault folder directly** | Vault is synced to phone (Syncthing / Obsidian Sync); app points at folder/tag. Zero infrastructure, fully offline |
| Recording | **Pure prompter first** | Voice-following UX is the hard part; camera/overlay comes later |
| Distribution | **Personal tool first** (sideloaded APK) | Fast iteration; open-sourcing/F-Droid possible later |
| Min SDK | Android 10 (API 29)+, target latest | Modern SAF + Compose baseline; user's own device is the real target |

## 3. Core feature: voice-following ("Follow Mode")

### 3.1 Pipeline

```
AudioRecord (16 kHz mono)
  → streaming ASR (sherpa-onnx zipformer, partial results ~every 100–300 ms)
  → token normalizer (lowercase, strip punctuation, expand numbers/contractions)
  → script matcher (fuzzy alignment against tokenized script, windowed)
  → anchor position + confidence + speaking rate
  → scroll controller (critically damped animation toward anchor + look-ahead)
  → Compose UI
```

### 3.2 Matcher behavior (the heart of the app)

The script is pre-tokenized into **spoken tokens** (stage directions, headings, and comments are
excluded from matching but rendered visually). The matcher maintains an **anchor** = index of the
last confidently-spoken token.

- **Normal reading:** partial ASR results are fuzzy-matched (token-level, Levenshtein-tolerant)
  against a window ahead of the anchor (~20–40 tokens). Matches advance the anchor. Skipped
  filler words, minor misreads, and ASR errors must not stall progress.
- **Improvisation (off-script):** when incoming words stop matching the window for a short
  hysteresis period (~1.5–3 s of unmatched speech), the prompter **freezes** with a subtle
  visual state change (e.g., eyeline marker dims). It stays frozen while you riff.
- **Rejoin:** while frozen, every partial is searched against a wider window around the anchor
  (configurable, e.g., ±150 tokens or whole script for short ones). A run of ≥N consecutive
  matching tokens (N≈3–4) re-anchors and resumes scrolling. This handles both **skipping
  ahead** and **jumping back to redo a line** (retake flow).
- **The screen bounds every search:** a reader can only speak words they can see, so all
  matching — forward scan, rejoin runs, and LOST recovery — is clamped to the tokens currently
  visible on screen. An off-screen match is always a coincidence (a duplicated phrase), never
  the reader; retakes/skips beyond the visible page are remote-key re-anchors. Additionally, a
  successful forward match at the anchor outranks a completed run elsewhere.
- **Silence:** no speech → freeze (trivially). Resume on matched speech. Optional "start on
  voice": session begins scrolling only when you start reading.
- **Rate estimation:** rolling words-per-minute from match timestamps drives the scroll
  velocity between anchor updates, so motion stays continuous rather than stepping per word.

**Testability requirement:** the matcher is pure Kotlin (no Android deps), driven by a stream of
(token, timestamp) events. Unit tests replay recorded/simulated ASR transcripts — including
misrecognitions, improv segments, and retakes — against known scripts. This is the most
test-worthy code in the app; build the test harness *with* the matcher, not after.

### 3.3 Latency budget

Mic → visible scroll response under ~500 ms. Partial results every ~100–300 ms from the
streaming model; matching is trivial CPU work. If a model can emit word-level timestamps, use
them to sharpen rate estimation, but don't depend on it.

### 3.4 Languages

Model-per-language, downloaded/bundled once (streaming zipformer models are ~50–300 MB).
Per-script model selection (the wrong model = unmatchable garbage, so the script decides):
frontmatter `lang: fr` > stopword auto-detection of the script text > configured default —
installed models only. The active language is shown in the session overlay so a mismatch is
visible, never a silent failure to follow. Matching must be Unicode/diacritic-aware.

## 4. Obsidian bridge

### 4.1 v1 — vault folder access

- User picks the vault (or a subfolder like `Scripts/`) via SAF folder picker; persistable
  permission.
- Library view lists `.md` files filtered by: subfolder, and/or tag (`#script`), and/or
  frontmatter key (`prompter: true`). Sorted by recency; favorites pinnable.
- File watcher / refresh-on-open so edits made on desktop (arriving via Syncthing or Obsidian
  Sync) appear immediately.
- Scripts are **read-only** in the app.

### 4.2 Markdown rendering rules

| Markdown | Prompter behavior |
|---|---|
| Headings `#`–`######` | Section markers: shown styled, **not matched as speech**, used for quick-nav and per-section stats |
| Bold / italic / highlight `==` | Rendered as emphasis (spoken normally) |
| `%%comment%%` and `[bracketed]` text | **Stage directions**: rendered dimmed/italic, excluded from matching |
| `[[wikilinks]]` / `[md links](url)` | Render link text only |
| Embeds `![[...]]`, images, code blocks | Stripped (v1) |
| Frontmatter | Hidden; parsed for per-script settings |
| `---` horizontal rule | Visual break + optional auto-pause point |

### 4.3 Per-script frontmatter settings (all optional)

```yaml
---
prompter: true          # include in library (if tag-filtering by frontmatter)
lang: en                # ASR model language
font-scale: 1.2         # relative to app default
wpm: 150                # fallback speed for manual mode
mirror: false
---
```

### 4.4 Later (v2+)

- Desktop push: companion Obsidian plugin or Local REST API → send active note to phone over
  LAN ("present this note now").
- Write-back: session stats (takes, duration, stumble spots) appended to the note's
  frontmatter or a sidecar note.

## 5. Prompter UI/UX

### 5.1 Reading surface

- Immersive fullscreen, black background default, high-contrast text. Portrait *and* landscape,
  switched by a **one-press flip** in the controls (shipped M5 s3). The app-requested
  orientation overrides the system rotation lock, so a phone pinned to portrait still prompts
  in landscape; the setting is prompter-only and the choice persists. Rotation is handled
  in-process (`configChanges`) — a take is never restarted by turning the phone — and the
  reading position is carried across the relayout as a fraction of the script.
- **Reading frame** (shipped M5 s3): the phone is often bigger than the prompter glass it sits
  behind, so the reading area is inset by a per-side margin (0–35 % of width/height). Text,
  eyeline, countdown, status and progress all live inside the frame. Margins are stored *per
  orientation* — the same rig crops portrait and landscape by very different fractions — and
  are set by dragging the frame edges ("Frame" chip, or any gesture/remote key mapped to
  *Adjust frame*), with the cropped-away area dimmed while adjusting.
- **Eyeline indicator**: fixed reading band in the upper third (configurable position); the
  anchor line is held inside it. Text outside the band optionally dimmed (focus gradient).
- Current position feedback: subtle — e.g., already-spoken text slightly dimmed. **No
  per-word karaoke highlighting** by default (distracting); available as a debug/practice option.
- Typography: size, line height, letter spacing, margins, weight; all live-adjustable via
  pinch/drag on the reading surface. Optional dyslexia-friendly font.
- **Mirror mode** (horizontal/vertical flip) for beam-splitter rigs.
- Follow-state indicator that's glanceable but not distracting: following (normal) / frozen-improv
  (dimmed marker) / lost (marker pulses; tap a paragraph to re-anchor manually).
- Progress: thin edge progress bar + estimated time remaining **based on measured live WPM**.
- Keep-screen-on; optional auto-DND during session; battery-conscious rendering.

### 5.2 Session flow

1. Pick script from library (shows estimated read time at your historical WPM).
2. Pre-roll screen: countdown (0–10 s) *or* start-on-voice.
3. Deliver. The phone sits on the rig, out of reach — **mid-take control is voice or remote
   only** (principle 2). In follow mode the jump keys re-anchor by paragraph: back = retake
   from the current paragraph's start (again = previous paragraph); forward = skip ahead.
   Touch (tap = pause/menu) is for handheld use only; nothing mid-take may require it.
4. End screen: duration, average WPM, per-section times, improv/freeze count. (Data feeds
   rehearsal mode.)

### 5.3 Controls that never require looking

- Volume keys: pause/resume, or manual nudge back/forward (configurable).
- **Bluetooth device support is a requirement** (2026-07-27): remotes, page-turner clickers,
  foot pedals, keyboards, camera shutters. Most present as **HID keyboards** (arrow/page/enter
  keys — sometimes volume keys, which is why volume mapping must stay flexible); some music
  remotes use **AVRCP media keys**, which need a MediaSession to receive. All such keys are
  mappable slots like touch gestures: play/pause, ±speed, jump back/forward, restart, text size.
  v1 ships HID key mapping (center/prev/next slots); MediaSession for AVRCP-only remotes later.
- All actions available as mappable commands (mirroring obsidian-teleprompter's
  everything-is-a-command approach).
- **Page back during follow voice** (v0.9.4, 2026-09-06; retuned on measured decoder output
  v0.9.5, 2026-11-10): say **"Commande recul"** or **"Retour retour"** (French), or
  **"Command rewind"** or **"Go back, go back"** (English). The doubled phrases transcribe
  far more reliably (bench: 70%/60% and 90%/85% clean/noisy), are never said in narration,
  and wait for a stable ending ("retour…" may grow into "retournez").
  Always available in follow mode, playing, armed or paused. Moves back one reading
  viewport, clamped to the top, and resets tracking to the new eyeline. Holds there
  until reading resumes and preserves play/pause/armed state. A green "↑ Commande recul"
  pill confirms each jump for 1.2 s.
  v0.9.5 accepts the forms kroko-fr really emits for the spoken phrase. The /d/ of
  *commande* often slides onto the next word, so "Comment de recul" / "Comment te recule"
  are accepted, plus homophone endings and a pause that splits the phrase across one
  endpoint. A strong prefix fires on the partial the moment its ending appears. The weak
  "comment" waits for a stable ending, since "comment reculer" is ordinary French.
  Command-like wording printed in the visible script only accepts the exact phrase.
  Measured on real decoder output for synthetic speech: hit rate 44% → 79% clean and
  28% → 48% noisy, median delay 1.54 s → 1.09 s, still 0 false fires on narration. The
  ~1 s floor is kroko-fr's 1.28 s decode chunk. Hotword biasing works without a shipped
  `bpe.vocab` (generated minimal vocab) but needs `modified_beam_search`, which raised
  narration WER 5.3% → 6.3–9.5%, so it is not adopted. Touch/fling and the session
  summary take priority. Research, numbers and the full contract:
  `docs/follow-voice-commands.md`; bench: `tools/voice-command-bench`.
- **Standalone voice command mode** (shipped M5 s4): a spoken command = jump back, riding the same ASR
  pipeline as follow mode. A mode of its own, mutually exclusive with follow-voice (one
  standalone mode at a time; follow now has its own page-back phrases); works paused or playing. The command is **one rare,
  phonetically distinctive word per language** — en **"rewind"**, fr **"rembobine"** — because
  multi-word phrases ("retour arrière") decode unreliably and common words collide with script
  text (device feedback 2026-07-30). Normalized like script tokens; command tokens ≥ 8 chars
  match fuzzily so inflections/decoder slips still fire ("rembobinez"), shorter ones exactly.
  A rare command word is also exactly what a decoder *fragments*: "rembobine" is OOV for the
  fr model and can come out as several small words ("ram bobine"), so each command token may
  consume up to 3 adjacent spoken tokens joined before the comparison (device feedback
  2026-08-02 — "rambobine" didn't fire). Decoder-level hotword biasing (sherpa-onnx
  `hotwords_file` + `modified_beam_search`) is possible with a generated minimal
  `bpe.vocab` (2026-11-10), but beam search measurably hurts narration recognition (see
  the page-back entry above). No wake word, so a script containing the
  word (or a near-joining neighbour pair) will trigger it — the status overlay always shows
  the active word. Detector is pure Kotlin (`follow/VoiceCommands.kt`), fires once per
  occurrence, replay-tested.
- **The mic runs only while a voice mode is on** (M5 s4): untoggling the last voice mode stops
  the ASR engine — the live-transcript overlay is display-only and never keeps the mic hot.
  Engine swaps serialize mic ownership (stop doesn't join the capture thread; the next engine
  waits), so a quick off→on retoggle can't race two AudioRecords into "Microphone unavailable".
- Touch gestures are mappable too (shipped M1): double-tap and long-press each map to any
  prompter action; pinch resizes text (toggleable). Single tap = play/pause stays fixed.

### 5.4 Classic mode (manual scrolling) & the hand-wins invariant

Full-featured timed scroller as fallback and for scripted-to-the-word delivery: WPM-based speed,
tap to pause, swipe to seek, speed ramp. Voice-follow is a mode, not a requirement.

**The hand always wins** (shipped M5 s4, from device feedback): in *every* mode and state, a
finger on the reading surface owns the scroll. Touching the text halts the drive (grab), drags
and flings run free — the drive must never cancel a fling or pull the text back — and wherever
the reader lets the text settle becomes the new truth: classic mode resumes its timed scroll
from there, and follow mode **re-anchors the matcher to the line under the eyeline**. The same
re-anchor runs whenever follow-voice is toggled on, so follow always picks up from what's on
screen, never from the top. Long-press ships unmapped by default for the same reason: holding
a finger on the text is the grab gesture, not a command.

Scroll gestures are handled **outside the mirror layer**, in screen space, via an explicit
`scrollable` with `reverseDirection = !mirrorV`; the mirrored `graphicsLayer` only draws
(M5 s4c, device-confirmed): a graphicsLayer transforms its children's pointer input too, so a
scrollable inside a negative-`scaleY` layer received flipped/garbled touch coordinates —
vertical mirror broke flicking while horizontal (x-only) didn't. Input must never pass through
a negative-scale transform.

## 6. Quality-of-life features (brainstormed backlog)

- **Onboarding / feature discovery** (noted 2026-11-10, priority): follow-voice is the app's
  headline feature but is only reachable through Settings, so new users meet a plain
  auto-scroller. Needs a first-run flow (or at minimum a prominent entry point on the
  prompter / library screen) that offers follow-voice, downloads the speech model and
  grants mic permission, and teaches the voice commands ("Retour retour" / "Go back, go
  back"). Research prior art (PromptSmart, BIGVU, Teleprompter Premium first-run flows)
  before designing, per CLAUDE.md.
- **Rehearsal mode**: run the script off-camera; app records per-section timing and marks
  "stumble hotspots" (where you froze/rewound most). Heatmap over the script.
- **Retake flow**: one-tap "back to last section start"; the rejoin logic makes redoing a line
  seamless without touching anything.
- **Marker log for editing**: during a session, log wall-clock timestamps of section starts /
  freezes / retakes to a file — lines up with external camera footage in the edit.
- **Audio input selection**: built-in mic vs. wired/BT lav. (BT mic latency may affect the
  budget; needs testing.)
- **Session audio recording** (optional, off by default): keep the take's audio for review.
- **Loop mode** for memorization practice.
- **Search within script**; jump-to-heading sheet.
- **Per-script + global appearance profiles** ("studio rig", "handheld selfie", "desk").
- **Estimated read time** in library, from personal WPM history.
- Foreground service so the session survives screen dimming/other-app peeks.

### 6.1 Distance-adaptive text size (experimental track)

Idea (2026-07-27): text size adapts to how far the speaker is from the phone. The phone lies
flat as a prompter, so the camera can't see the speaker — but the mic can *hear* distance.

**Physics & signals (honest assessment):**

- Raw level falls ~−6 dB per doubling of distance in free field, but indoor reverberation
  flattens this beyond ~1 m, and vocal effort is a huge confound (people speak louder when
  farther — partially self-defeating for level-based estimation).
- More robust single-mic cues, both computable on frames the ASR already flags as speech:
  - **Direct-to-reverberant ratio (DRR)** — reverb-tail proportion grows with distance and is
    far less sensitive to vocal effort than raw level.
  - **Spectral tilt** — high frequencies attenuate faster with distance/head orientation.
- **Absolute** distance from one mic is research-hard; **relative change from a calibrated
  baseline** is tractable. The pre-roll is a natural calibration moment.
- Hard requirement (already aligned with ASR needs): capture with AGC/noise-suppression off
  (`UNPROCESSED` / `VOICE_RECOGNITION` audio source). AGC would erase the level cue entirely.

**Feature ladder — each rung useful on its own:**

1. **Distance→size calculator (deterministic, M5).** User enters/slides approximate distance;
   app sets font size from a target visual angle (comfortable reading ≈ a target arc-minute
   x-height; expose as "legibility" slider, not math). Saved per appearance profile
   ("desk", "across the room").
2. **Pre-roll voice calibration (semi-auto).** "Read the first line from where you'll stand" —
   app measures the acoustic baseline and picks the size step automatically. One-shot, robust,
   no mid-session risk.
3. **Continuous tracking (experimental toggle).** Slow relative estimate (level + DRR fusion,
   speech-gated, 10–20 s smoothing) drives size across ~3 quantized steps with strong
   hysteresis. **Resize only during freezes/pauses**, anchor line held stable — reflowing text
   mid-sentence is unacceptable.
4. **Ultrasonic sonar (research).** Capture at 48 kHz and band-split: >17 kHz for inaudible
   chirp-echo ranging via the phone speaker, <8 kHz downsampled to 16 kHz for ASR — the chirps
   are invisible to the recognizer by construction. Prior art: FingerIO, LLAP-style acoustic
   sensing. Only worth attempting once rungs 1–3 ship; a hot speaker emitting chirps may also
   bleed into external recordings — needs testing.

**Confounds to design around:** head orientation (turning away mimics moving away), room
change between sessions (calibration is per-session), BT/external mics (feature only valid on
the phone's own mic — auto-disable otherwise), and multi-person rooms.

## 7. Architecture

Single-module Android app to start. Clean seams, not ceremony:

```
app/
  ui/          Compose screens: Library, Prompter, PreRoll, Settings, SessionSummary
  prompter/    ScrollController (anchor+rate → animated offset), reading-surface widgets
  follow/      PURE KOTLIN: tokenizer, normalizer, Matcher (anchor/freeze/rejoin), rate estimator
  asr/         AsrEngine interface; SherpaOnnxEngine impl; AudioRecord pipeline; model manager
  vault/       SAF access, Markdown parser → ScriptModel (display spans + spoken-token index map)
  session/     foreground service, session state machine, stats collection
  settings/    DataStore-backed prefs, per-script overrides from frontmatter
```

Key invariants:

- `follow/` has **zero Android dependencies** → exhaustively unit-tested with transcript replays.
- `ScriptModel` maintains a bidirectional map between spoken-token indices and character/layout
  positions, so the matcher's anchor translates to a pixel offset cheaply.
- `AsrEngine` is an interface: sherpa-onnx first; Vosk (or others) swappable behind it. Also
  enables a `FakeAsrEngine` for UI development and demos without a mic.

## 8. Milestones

**Status (2026-07-27):** M0 shipped and installed on device. M1 shipped (SAF vault picker with
the picked folder acting as the filter; tag/frontmatter filtering and favorites deferred).
Landed early from later milestones: customizable gesture mapping (double-tap, long-press,
volume keys → actions) + pinch-to-resize, per-user countdown control (off/1/2/3/5 s + "start
now" skip), Markdown parser with unit tests (the parser is the seed of M3's ScriptModel).
**M2 shipped and validated on device** (2026-07-27): sherpa-onnx v1.13.4 integrated (vendored
Kotlin API `com.k2fsa.sherpa.onnx` + arm64 JNI libs in `app/src/main/jniLibs/`), in-app model
downloader (en-20M small English, French large; HuggingFace), AsrEngine mic→decode loop with
live-transcript debug overlay — user confirmed live transcription works with the English model.
Bluetooth/HID remote keys landed (REMOTE_CENTER/PREV/NEXT mappable slots). Recognizer results
include token-level timestamps — use them in M3's matcher. Branding done: `assets/branding/logo.svg`
master + adaptive/monochrome launcher icon (eyeline-as-play-button mark).
**M3 shipped** (2026-07-27, pending on-device validation): `follow/` package (pure Kotlin) —
TextNorm (diacritic strip, French elision + hyphen + contraction splitting, number expansion
en/fr incl. quatre-vingts/soixante-et-onze), ScriptIndex (spoken tokens with char offsets over
the exact rendered text via shared `DisplaySegment` flattening in vault/ScriptModel), and the
Matcher: greedy fuzzy forward scan (Levenshtein-tolerant, short words exact) + an always-on
consecutive-run rejoin tracker over a ±250-token window (one mechanism covers improv-rejoin,
retakes and skip-ahead) + a sliding match-quality window for the improv freeze; rolling
tokens/sec EMA from word timestamps. Transcript-replay harness + 29 unit tests (clean read,
misrecognitions, freeze, rejoin, retake, skip, French incl. degraded ASR output). Wiring:
AsrEngine emits word-level `AsrEvent`s (BPE pieces merged, utterance-scoped timestamps);
PrompterScreen "Follow voice" chip (persisted; model auto-picked by script `lang`) drives a
critically-damped scroll toward the anchor line with rate feed-forward; eyeline marker dims
while frozen; live remaining-time from measured rate. Timed scrolling remains the fallback.
**M3 validated on device** (2026-07-27, EN + FR) — follow works but first cut felt laggy
(~1–1.5 s). **M4 reactivity pass landed**: the still-growing last partial word now advances the
published anchor provisionally on exact match (committed state untouched — a revision just
falls back); scroll target interpolates *inside* the line (no more line-height hops) and leads
the anchor by 0.45 s of measured rate; asymmetric spring (catch-up τ 0.20 s, ease-back 0.50 s).
Feel constants live at the top of PrompterScreen.kt, matcher knobs in MatcherConfig.
**M4 slice 2** (2026-07-27, from device feedback): script-language **auto-detection**
(stopword scoring in `follow/LangDetect`, frontmatter override, configured model as fallback,
installed-only) — fixes silent wrong-model sessions (EN script + leftover FR default = no
follow); active language shown in the overlay. **Touch-free re-anchor**: jump keys re-anchor
the matcher by paragraph in follow mode (back = retake paragraph, again = previous; forward =
skip) — long-press re-anchor dropped, phone-on-rig means touch is never a mid-take control.
**M4 slice 3 — distant-mic tolerance** (2026-07-27, device feedback: froze on improv far too
eagerly; phone sits meters away, mic misses words, model mishears): freeze now needs 10 of the
last 12 tokens unmatched (spurious freeze is worse than a late one — FROZEN disables the
forward scan); rejoin runs continue through *fuzzy* matches, may start on fuzzy matches near
the anchor while frozen, and near the anchor need only 2 consecutive hits (far jumps still 3);
skip window 8→10 but long skips must land on a content word (≥4 chars) so coincidental
function words can't drag the anchor during improv; fuzzy 2-edit threshold at length ≥8.
Replay tests cover 33% word loss, mid-gap resume on a content word, and rejoin with garbled
words. **Validated on device — follow mode works well** (2026-07-27).
**M4 complete** with the final slice: **start-on-voice** (countdown option "Voice": session
arms, first matched words start the scroll — no touch, no timer; tap or "Start now" as manual
outs) and the **lost state** (20+ unmatched tokens while frozen → marker pulses, rejoin search
widens to the whole script, so reading any line recovers; jump keys/restart also rescue).
**M5 slice 1 — session end screen** (2026-07-27): pure-Kotlin `SessionStatsBuilder` (fed
matcher state snapshots + play/pause; word counting from anchor deltas — 1–12 = speech,
bigger = jump, −4 or more = retake) collects active time, words read, avg live WPM, progress,
freeze/lost/retake counts, per-section time+WPM (sections from headings via
`ParsedScript.sections()`), and a timestamped event list (feeds rehearsal heatmap + marker log
later). Exit/back after a ≥10 s take shows the summary overlay first ("Back to script" /
"Done"); follow-mode sessions only.
**Repo made public** (2026-07-27): README rewritten for outside readers, MIT license, release
APK (v0.5.0, debug-key signed) published on GitHub Releases.
**M5 slice 2 — screen-bounded matching + library cache** (2026-07-28, from device feedback:
follow jumped to a far duplicate phrase; library re-walked the vault on every visit).
Matcher: (1) forward match now outranks a completed rejoin run — reading a phrase that repeats
elsewhere in the script no longer teleports the anchor; (2) new `Matcher.visibleTokens`:
PrompterScreen reports the on-screen token range (layout lines → char offsets →
`ScriptIndex.tokenRangeForChars`) before each feed, and every search (forward scan, rejoin
window, LOST whole-script recovery) is clamped to it — a reader can only speak what they can
see. Touch-free pass: a key press during countdown/armed now *starts* (the on-screen "Start
now" is unreachable on a rig; cancel = start-then-pause), and any key on the session summary
dismisses it instead of leaking into the prompter. Vault library: listing cached in memory +
`vault-listing.tsv` on disk, shown instantly on entry (ebook-library pattern); the SAF walk
revalidates in the background, is skipped when <60 s old, never lets an empty/failed walk
clobber a good cache; ⋮ Refresh forces it. Deleting a local script no longer re-walks the vault.
**M5 slice 3 — fitting the rig** (2026-07-30, user request): two setup-time QoL features.
(1) **Reading frame** — margins inset the whole reading surface (text, eyeline, countdown,
ASR status, progress bar) so the prompt fits a glass smaller than the screen; symmetric per
side, capped at 35 %, stored per orientation, adjusted by dragging any of four edge handles
with the cropped area dimmed. Also a mappable action (*Adjust frame*), and reset from Settings.
(2) **Orientation flip** — one chip press swaps portrait/landscape via `requestedOrientation`,
which beats the system rotation lock; persisted, prompter-scoped, with `Auto` in Settings and
a mappable *Rotate screen* action. `android:configChanges` keeps rotation from recreating the
activity mid-take (scroll, matcher anchor and ASR engine all survive), and the scroll position
is restored proportionally after the relayout. 180°-rotated rigs are covered by both mirrors.
**M5 slice 4 — the hand always wins + voice command** (2026-07-30, from device feedback:
manual scrolling in the prompter felt broken — flicks died instantly and follow mode dragged
the screen back; toggling follow-voice restarted from the top). Root causes: the drive loop
issued a same-priority `scrollBy` every frame (killing any user fling within one frame); the
follow spring re-targeted a stale anchor no gesture ever updated; toggling follow never
re-anchored (and could rebuild the matcher), leaving anchor −1 whose screen-bounded search
window ∩ visible tokens = ∅ mid-script — unrecoverable until LOST; and long-press defaulted to
RESTART, so the natural hold-to-grab gesture teleported to the top. Fixes (§5.4): new
`UserScrollControl` (pointer-down + nested-scroll fling tracking + stuck-fling watchdog) parks
the drive while the hand or its fling owns the scroll; on settle — and on every follow-voice
toggle — the matcher re-anchors to the line under the eyeline (`reanchorToEyeline`). The
re-anchor is consumed *by the drive loop itself* before its first post-release tick — a
snapshot-flow watcher alone loses the race while playing (the drive's own scrolls keep
isScrollInProgress flapping and starve it), which let the elastic pull survive; long-press
default → *Nothing* (still mappable). New **voice command mode** (§5.3): pure-Kotlin
`VoiceCommandDetector` on the same ASR stream — say "rewind" (fr "rembobine") → jump back;
chip-toggled, exclusive with follow-voice, works paused or playing; single rare word after
"retour arrière" decoded poorly on device. ASR lifecycle fixed the same day: the engine now
runs only while a voice mode is on (overlay is display-only) and mic ownership is serialized
across engine swaps — untap genuinely stops listening, fast retoggles no longer kill the mic.
**M5 slice 4c** (2026-07-30, user diagnosed it on device): the remaining "elastic/confused
flick" was *mirror-dependent* — only with vertical mirror on. Scroll gestures lived inside the
`graphicsLayer` that flips the reading surface, and a layer transforms its children's pointer
input along with the drawing, so a negative `scaleY` fed the scrollable flipped/garbled touch
coordinates (horizontal mirror only touches x, which vertical scrolling never reads — hence no
bug). Fix: gestures moved outside the mirror to an explicit screen-space `scrollable`
(`reverseDirection = !mirrorV`); the Column keeps `verticalScroll(enabled = false)` for
layout/offset only, and the mirror layer now draws and nothing else. v0.8.2.
**M5 slice 4d — paused controls compaction + command-word fragments** (2026-08-02, device
feedback: the paused panel ate the landscape screen — the tap-to-resume zone was a sliver and
mis-taps landed on the speed slider; "rembobine" decoded as "rambobine" and never fired).
Landscape now docks the controls as a 340 dp side sheet (portrait keeps the bottom sheet), the
slider label/value moved inline into the slider rows, and the redundant wpm/pinch hint text is
gone — the whole left of a landscape screen is now the resume tap zone. Voice commands match
across decoder fragments (§5.3 join rule). v0.8.3.
**M5 slice 5 — follow-voice overhaul** (2026-08-02, from user feedback: follow was jumpy, not
flowy, stopped working after a while — untrustworthy; overhaul driven by an in-depth audit +
industry research, both in `docs/voice-follow-research.md`; reuse-first working rule added to
CLAUDE.md). Four layers changed, pending on-device validation:
(1) **Engine reliability** ("stops working"): a dead AudioRecord (negative `read()` — audio
server restart, OEM power management) is now detected and the mic reopened with backoff +
"reconnecting" status, instead of silently spinning while the overlay claimed to listen; the
sherpa stream is **rebuilt, not just reset**, after silence-only segments and every 6
utterances (sherpa-onnx#1700: `reset()` keeps stale zipformer encoder states → recognition
decays over long sessions); decode lag is an EMA surfaced in the overlay (near 100 ms = the
mic buffer is overrunning); `numThreads` 2→4; event buffer 256→1024 (a dropped Final loses
words forever); unchanged partials are no longer re-emitted 10×/s. Endpoint rule2 1.2→0.7 s
(pauses commit words fast), rule3 30→20 s.
(2) **Motion** ("jumpy"): the first-order position spring (lurch–stall at word rate, backward
tugs from provisional fallbacks and freeze lead-removal) is replaced by a pure-Kotlin
**velocity servo** (`follow/ScrollDynamics`, 8 shape-of-motion tests): feed-forward at the
measured reading rate so the text *coasts* between matches, a ±0.35-line dead zone that
swallows word-level anchor noise, correction only outside the zone, velocity smoothing
(bounded acceleration), backward speed hard-capped at a gentle drift, coast decays 0.9 s
after the last anchor advance. Industry-consensus design (PromptSmart's patented zone-based
rate control, SmoothDamp-style damping) — motion with corrections, not made of corrections.
(3) **Matcher evidence gating** (anchor creep): a single matched token now only advances the
anchor contiguously (≤ smallSkip); longer skips go through the run tracker (≥2 consecutive
near / 3 far) and, while FOLLOWING, a re-anchoring run must contain a content word — improv
can no longer drag the anchor forward word by coincidental word and then poison the rejoin.
Frozen/lost rejoins stay cheap (healing beats caution). All 20 replay tests pass unchanged.
(4) **Models**: kroko streaming zipformers (2025, ~71 MB each) added for EN and FR and
preferred when installed (`zipformer2`); language pick now prefers *installed* models. The
2023 models remain as fallbacks. Hotword biasing investigated and deferred: sherpa-onnx needs
a `bpe.vocab` none of our model repos ship (future slice: generate + host one, then
`modified_beam_search` + per-script rare-word hints). v0.9.0.
**M5 slice 6 — follow diagnostics + one model per language** (2026-08-02, user feedback:
two downloadable models per language is confusing; and the playhead still stopped following
in a session where the *transcript stayed correct* — so the failure is in the matcher /
visible-window / UI layer, not recognition, and must be debugged from data, not guesses).
(1) **Follow-session tracing**: whenever follow-voice is on, every matcher input and output
is recorded to a TSV trace file — ASR words as fed (partials + finals), the visible-token
window at each feed, every state change (anchor/mode/rate), re-anchors, play/pause edges,
skipped-while-paused counts, and a 1 Hz scroll-vs-target snapshot. Format + parser + replayer
are pure Kotlin (`follow/FollowTrace`, locale-proof encoding, corrupt-line tolerant);
`FollowTrace.replay()` drives a fresh Matcher through a pulled trace exactly as the live
session did — a replay-equality test pins that recorded sessions reproduce state-for-state.
Writer (`session/FollowTraceWriter`) is off-UI-thread, flushes per line, keeps the last 5
sessions in `Android/data/…/files/traces/` (adb/USB-pullable); Settings gains "Share latest
follow trace" (FileProvider). The status overlay now shows **"⚠ anchor off-screen"** live in
following/frozen/lost states — the suspected stuck-state signature (anchor outside the
visible window clamps every search to a range the reader isn't in) is visible the moment it
happens. Debug loop: reproduce once → share/pull the trace → replay it as a JVM test →
fix → the trace becomes a regression test.
(2) **One model per language**: settings now offer only the two kroko models; the 2023
models moved to a hidden LEGACY list — still used if installed (nothing breaks before the
upgrade is downloaded), auto-deleted when the same-language replacement finishes
downloading. v0.9.1.
**M5 slice 7 — the actual cause: words were never reassembled** (2026-08-02). The slice-6
hypothesis above ("the failure is in the matcher / visible-window / UI layer, not
recognition") was **wrong**, and the first pulled trace said so in one line. The matcher was
being fed `Je suis pas f an des l if e ac t u ce s de vi e` for *"Je suis pas fan des life
astuces de vie"*: **44% of its input tokens were single characters**, 186 distinct values out
of a 653-token BPE vocabulary. Recognition was fine all along — `result.text` read correctly
on screen, which is exactly why this survived months of matcher-heuristic patching.
Cause: `mergeBpeTokens` detected word starts by looking for the raw BPE marker `▁` (U+2581).
sherpa-onnx's `SymbolTable` substitutes an **ASCII space** for `▁` when it loads tokens.txt,
so the JNI hands Kotlin `" de"` where the vocab holds `"▁de"`. No piece ever started with
`▁`, the function took its "vocabulary has no markers" fallback — one word per subword piece
— and `trim()` then erased the leading space, destroying the last evidence of the boundary.
Short function words (`de`, `des`, `je`, `vous`) survived intact because they are single BPE
tokens, so follow *appeared* to work and then lost the thread the moment a content word
arrived. Fix: treat both `▁` and a leading space as a word start, and read the boundary
*before* trimming (`follow/Transcript`, 9 tests built from the real vocabulary).
Measured on the same script, same reader: single-char tokens **44.0% → 1.2%**, mean token
length **1.96 → 5.06**, FOLLOWING→FROZEN transitions **10 → 0**, LOST escalations **6 → 0**.
Also settled, from the same trace: the long-standing "it always stops at the end of the
visible page" report is a **detection artefact, not a cause** — all 10 freezes began at
9–42% down the page (median 27%, i.e. the eyeline), none in the bottom 10%, and the anchor
never came within 19 tokens of `vis.last`. The freeze starts at the eyeline and the scroll
stops with it; the reader simply keeps reading the frozen page and only notices on running
out of text. (A real sharp edge does exist there — at `anchor == vis.last` the forward scan's
range `[anchor+1, min(anchor+smallSkip, vis.last)]` goes empty — but it cannot deadlock: the
scroll target is derived from the anchor's *line position*, not from it advancing, so the
page keeps easing forward and revives the scan. Left unchanged deliberately; the trace
analyzer reports the runway, so evidence would show up if it ever mattered.)
Tooling: ASR health (`listening`, decode-lag EMA, mic error) now goes into the trace, so a
single session distinguishes matcher-side from mic-death / thermal / encoder-decay causes;
`tools/trace-report.py` renders the timeline and the verdict, `tools/pull-traces.ps1` pulls
and reports in one command over adb. v0.9.3.
**M5 slice 8 — page-back command, measured instead of guessed** (2026-11-10, user feedback:
"commande recul" often needs saying several times, and lags even when it works). A
reproducible bench (`tools/voice-command-bench`) feeds piper-TTS French through kroko-fr
exactly as AsrEngine does. Its first run explained both complaints. Misses: the decoder
writes "Comment de recul" for the phrase about as often as "commande recul", and v0.9.4
matched exact spellings only. Lag: kroko-fr decodes in 1.28 s chunks, and v0.9.4 then
waited for one more word or the endpoint. The detector was rebuilt around measured
output (tolerant phrase grammar, fire-on-partial for strong prefixes, one-endpoint span,
script guard, §5.3). The real decoder event stream is checked in as a replay fixture
(`VoiceCommandBenchTest`): clean 44% → 79%, noisy 28% → 48%, median delay 1.54 s → 1.09 s,
0 false fires. Hotword biasing (generated bpe.vocab) and beam search were measured and
rejected, since both hurt narration WER. On-screen confirmation of each jump stops repeats
that would page back twice. Then, on the user's pick, second phrases measured the same
way: "Retour retour" (it transcribed word-exact 73% vs 13–17% for "Commande recul") and
"Go back, go back" for English (88% vs 55% for "Command rewind"). "Command rewind" got
the measured tolerance too ("a man rewind", "amand rewind"): 43% → 75% clean. Per-phrase
floors over both languages' fixtures, 0 false fires. CI (`.github/workflows/android.yml`)
now runs the unit tests and builds the APK on every push, and publishes a release from
main when versionName is new. It signs with the existing debug key from a repository
secret, so updates install in place. v0.9.5.
**To do — next feature: distance→size calculator** (§6.1 rungs 1–2, noted 2026-07-27). Then:
appearance profiles, marker-log file export, MediaSession for AVRCP remotes, rehearsal mode.
Battery/thermal over a 30+ min session still unmeasured (§9 risk).

- **M0 — Scaffold + classic prompter.** Repo, CI-less local build, Compose app: paste/open a
  text, timed smooth scrolling, appearance controls, mirror, keep-awake. *Usable on day one.*
- **M1 — Obsidian library.** SAF vault picker, tag/folder filtering, Markdown rendering rules,
  frontmatter settings, favorites/recents.
- **M2 — Ears.** sherpa-onnx integrated; live transcript debug overlay proves latency and
  accuracy on-device; model download/management.
- **M3 — Follow Mode MVP.** Matcher + scroll controller: follows clean reading end-to-end.
  Transcript-replay test suite in place.
- **M4 — Robustness.** Improv freeze/rejoin, retakes, silence handling, rate smoothing, manual
  re-anchor, follow-state UI. *This is the milestone that makes it state-of-the-art.*
- **M5 — QoL & polish.** Remote/HID mapping, session stats + rehearsal mode, marker log,
  profiles, theming, battery pass, distance→size calculator + pre-roll voice calibration
  (§6.1 rungs 1–2).
- **M6 — Stretch.** Floating overlay mode (prompt over any camera app), LAN push from desktop
  Obsidian, built-in recording, write-back stats, continuous distance tracking and sonar
  experiments (§6.1 rungs 3–4), F-Droid packaging.

## 9. Risks & open questions

- **ASR accuracy on proper nouns/jargon** (game-dev terms, names): matcher must tolerate
  mismatches on rare words; consider per-script custom-vocabulary hints if the engine supports
  hotwords.
- **Patent noise:** PromptSmart markets "patented VoiceTrack". For a personal open-source tool
  the practical risk is negligible, but worth a look before any commercial distribution.
- **BT microphone latency/codec** may degrade the latency budget — test early (M2).
- **Model size vs. accuracy** per language: pick after on-device benchmarks in M2.
- **Thermal/battery** over 30+ min sessions with hot mic + bright screen: measure in M4.
- **Distance estimation may not beat calibration.** §6.1 rungs 3–4 are genuinely experimental;
  ship rungs 1–2 first and only keep continuous tracking if it's reliably better than the
  one-shot pre-roll calibration. Requires AGC-off capture, which the ASR path needs anyway.
- ~~Open: which language(s)?~~ → **French primary, English also required** (resolved 2026-07-27).
  Both models ship in-app (fr large + en-20M). M3 matcher must handle French properly:
  diacritics, and apostrophe elisions (j'ai, l'école) in the tokenizer/normalizer.
- ~~Open: exact phone model~~ → **vivo V2405A (X200), Android 16 (API 36), Dimensity 9400** —
  flagship SoC; ASR model size/latency will not be a constraint. (Resolved 2026-07-27.)

## 10. Reference projects

| Project | Why it matters |
|---|---|
| [jlecomte/voice-activated-teleprompter](https://github.com/jlecomte/voice-activated-teleprompter) | MIT, web-based; closest prior art for the matching algorithm (window matching, click-to-re-anchor, bracketed hints) |
| [lumetrium/obsidian-teleprompter](https://github.com/lumetrium/obsidian-teleprompter) | UX reference: everything-is-a-command, eyeline, minimap; proves the Obsidian-scripts workflow |
| [Cuperino/QPrompt-Teleprompter](https://github.com/Cuperino/QPrompt-Teleprompter) | Most polished FOSS prompter (C++/Qt); prompting UX conventions; its Android build is not production-ready |
| [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On-device streaming ASR engine of choice; Android APK samples + Kotlin API |
| [Vosk](https://alphacephei.com/vosk/) | Fallback ASR: lighter models, native streaming, broad language coverage |
| [TiefPrompt (F-Droid)](https://f-droid.org/packages/io.github.tiefseetauchner.tiefprompt/) | Baseline of what basic FOSS Android prompters offer |
| Commercial bar: PromptSmart, [Teleprompter.com](https://www.teleprompter.com/), VoicePrompter, Beast | Feature bar for voice tracking on mobile; all closed + subscription |
