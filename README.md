<p align="center">
  <img src="assets/branding/logo.svg" width="112" alt="Teleprompter logo">
</p>

# Teleprompter

A voice-following teleprompter for Android. The prompter adapts to the speaker, not the
other way around: it listens while you read, scrolls at your pace, freezes when you
improvise, and catches up when you return to the script — including when you jump back to
redo a line. All speech processing happens on-device and audio never leaves the phone.

Scripts are written in Obsidian (or any Markdown folder) and read straight from the synced
vault. There is no editor in the app — the phone is the prompter.

## Features

Voice following

- On-device streaming speech recognition (sherpa-onnx zipformer), fully offline
- Fuzzy script matching that tolerates misread words, words the microphone missed, and
  recognition errors — built for a phone sitting meters away on a camera rig
- Freezes when you go off script, rejoins when you come back, anywhere in the script
- Retakes: jump back and redo a line, the prompter follows
- English and French, with diacritic- and elision-aware matching (j'ai, l'école) and
  spoken-number matching (80 matches "quatre-vingts")
- Script language auto-detected per file, overridable with frontmatter
- Start on voice: the scroll starts the moment you start reading
- Live reading-speed estimate drives the scroll and the remaining-time display

Scripts from Obsidian

- Pick a vault folder; its Markdown files become the script library
- Headings become section markers; `%%comments%%` and `[bracketed]` text render as dimmed
  stage directions and are never matched against speech
- Per-script frontmatter settings: `lang`, `wpm`, `font-scale`, `mirror`

Prompting

- Classic timed scrolling (WPM-based) as fallback and manual mode
- Eyeline marker with follow-state feedback: following, holding on improv, lost
- Mirror mode (horizontal and vertical) for beam-splitter rigs
- Adjustable reading frame: drag the edges to inset the text until it fits a prompter glass
  smaller than the phone screen — margins are remembered per orientation
- One-press portrait/landscape flip that overrides the system rotation lock, and rotating
  mid-session never restarts the take
- Pinch to resize text, immersive fullscreen, keep-screen-on

Hands-free control

- During voice following, say **“Go back, go back”** or **“Command rewind”** (English),
  **“Retour retour”** or **“Commande recul”** (French) to go back one screen, then resume
  reading. Works while paused too.
- Bluetooth remotes, page-turner clickers, foot pedals and keyboards (HID)
- Volume keys, double-tap and long-press are all mappable to prompter actions
- In follow mode the jump keys re-anchor by paragraph: retake or skip ahead without
  touching the phone
- Session summary on exit: delivery time, measured words per minute, per-section times,
  improv and retake counts

## Requirements

- Android 10 or newer, arm64
- A speech model, downloaded inside the app (English about 46 MB, French about 130 MB,
  fetched from Hugging Face on first use)

## Install

Download the APK from [Releases](../../releases), or build it yourself with JDK 17 and the
Android SDK (platform 36):

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Quick start

1. Open the app and pick your vault (or any folder containing `.md` files).
2. In Settings, download a speech model.
3. Open a script, enable "Follow voice" in the controls panel, press play, and read.

## Status and roadmap

Personal tool, actively developed. [SPEC.md](SPEC.md) is the full specification and
roadmap. Everything listed above works today. Next up:

- Distance-adaptive text size: set the font size from how far you stand, with a voice
  calibration step during the pre-roll
- Appearance profiles (studio rig, handheld, desk)
- Marker log export: freeze/retake timestamps to line up with camera footage in the edit
- Media-key (AVRCP) remote support
- Rehearsal mode with per-section timing history and stumble hotspots

## License

[MIT](LICENSE). On-device speech recognition by
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), whose Android bindings
and runtime libraries are vendored in this repository; speech models are downloaded at
runtime and keep their own licenses.
