# Teleprompter

A voice-following teleprompter for Android. The prompter adapts to the speaker — it follows
your pace, waits when you improvise, and catches up when you return to the script. Scripts are
authored in Obsidian and read from the synced vault. All speech processing happens on-device.

**Status:** M0 — classic prompter (timed smooth scrolling, appearance controls, mirror mode,
volume-key speed nudge). Voice-following lands in M3–M4.

See [SPEC.md](SPEC.md) for the full specification, architecture, and roadmap.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`. Install with
`adb install -r app-debug.apk`.
