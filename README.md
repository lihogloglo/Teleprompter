# Teleprompter

A voice-following teleprompter for Android. The prompter adapts to the speaker — it follows
your pace, waits when you improvise, and catches up when you return to the script. Scripts are
authored in Obsidian and read from the synced vault. All speech processing happens on-device.

**Status:** M3 — voice-following is in: the script matcher (pure Kotlin, transcript-replay
tested, French + English) aligns the on-device ASR stream against the script and drives the
scroll. Freezes on improv, rejoins on return, handles retakes. Toggle "Follow voice" in the
prompter controls; classic timed scrolling stays as fallback. Robustness tuning lands in M4.

See [SPEC.md](SPEC.md) for the full specification, architecture, and roadmap.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`. Install with
`adb install -r app-debug.apk`.
