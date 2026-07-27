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
Per-script language selection via frontmatter (`lang: fr`), falling back to app default.
Matching must be Unicode/diacritic-aware.

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

- Immersive fullscreen, black background default, high-contrast text. Portrait *and* landscape.
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
3. Deliver. Tap anywhere = pause/menu. Long-press paragraph = re-anchor there.
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
- Touch gestures are mappable too (shipped M1): double-tap and long-press each map to any
  prompter action; pinch resizes text (toggleable). Single tap = play/pause stays fixed.

### 5.4 Classic mode (manual scrolling)

Full-featured timed scroller as fallback and for scripted-to-the-word delivery: WPM-based speed,
tap to pause, swipe to seek, speed ramp. Voice-follow is a mode, not a requirement.

## 6. Quality-of-life features (brainstormed backlog)

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
**Next: M3 — the matcher** (`follow/` package, pure Kotlin, transcript-replay tests; French +
English from day one).

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
