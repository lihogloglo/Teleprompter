#!/usr/bin/env python3
"""Triage a follow-session trace (see follow/FollowTrace.kt).

Answers one question fast: when the playhead stopped following, was the ASR
still delivering words? If yes the bug is matcher/scroll-side and replays in
the JVM harness; if no it is ASR/mic-side and no desk replay will reproduce it.

    python tools/trace-report.py traces/follow-20260802-143012.tsv
"""

import sys
from collections import Counter

# Traces carry raw ASR words; a French session prints "être" and the Windows
# console's cp1252 default would raise UnicodeEncodeError mid-report.
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

BUCKET = 10.0   # seconds per timeline row
ASR_GAP = 3.0   # a w-event gap longer than this is silence or a dead recognizer
STALL = 5.0     # anchor not advancing this long, while words arrive, is a stall
CHUNK_MS = 100  # AsrEngine.CHUNK is 100 ms of audio per decode step
LAG_WARN = 60   # decode lag this close to the budget starts dropping audio


def parse(path):
    events = []
    for raw in open(path, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        if not line.strip():
            continue
        f = line.split("\t")
        try:
            tag = f[0]
            if tag == "h":
                events.append(("h", 0.0, {"version": int(f[1]), "app": f[2],
                                          "lang": f[3], "tokens": int(f[4]),
                                          "script": f[5]}))
            elif tag == "w":
                words = []
                if len(f) > 4 and f[4]:
                    for w in f[4].split(" "):
                        at = w.rfind("@")
                        words.append((w[:at], float(w[at + 1:])))
                events.append(("w", float(f[1]), {"utt": int(f[2]),
                                                  "final": f[3] == "f",
                                                  "words": words}))
            elif tag == "v":
                events.append(("v", float(f[1]), {"lo": int(f[2]), "hi": int(f[3])}))
            elif tag == "s":
                events.append(("s", float(f[1]), {"anchor": int(f[2]),
                                                  "mode": f[3], "rate": float(f[4])}))
            elif tag == "r":
                events.append(("r", float(f[1]), {"anchor": int(f[2])}))
            elif tag == "p":
                events.append(("p", float(f[1]), {"playing": f[2] == "1"}))
            elif tag == "k":
                events.append(("k", float(f[1]), {"utt": int(f[2]), "count": int(f[3])}))
            elif tag == "a":
                events.append(("a", float(f[1]), {
                    "listening": f[2] == "1", "decodeMs": int(f[3]),
                    "error": f[4] if len(f) > 4 and f[4] else None}))
            elif tag == "n":
                events.append(("n", float(f[1]), {"scroll": int(f[2]), "max": int(f[3]),
                                                  "target": float(f[4]),
                                                  "velocity": float(f[5])}))
        except (ValueError, IndexError):
            print(f"  ! unparseable: {line[:80]}", file=sys.stderr)
    return events


def main(path):
    events = parse(path)
    if not events:
        print("empty trace")
        return
    counts = Counter(t for t, _, _ in events)
    end = max(t for _, t, _ in events)

    hdr = next((d for t, _, d in events if t == "h"), None)
    print(f"== {path}")
    if hdr:
        print(f"   app {hdr['app']}  lang {hdr['lang']}  "
              f"script {hdr['script']}  {hdr['tokens']} spoken tokens")
    print(f"   {end:.0f}s, events: " +
          "  ".join(f"{k}={v}" for k, v in sorted(counts.items())))

    # --- anchor history and the moment it froze ---------------------------
    anchors = [(t, d["anchor"]) for tag, t, d in events if tag == "s"]
    last_adv = 0.0
    peak = -1
    for t, a in anchors:
        if a > peak:
            peak, last_adv = a, t
    words_after = [(t, d) for tag, t, d in events
                   if tag == "w" and t > last_adv and d["words"]]
    plays = [(t, d["playing"]) for tag, t, d in events if tag == "p"]
    reanchors = [(t, d["anchor"]) for tag, t, d in events if tag == "r"]

    # --- timeline ---------------------------------------------------------
    print("\n   time    w(p/f)  words  anchor  mode        rate   scroll/max   vis")
    nb = int(end // BUCKET) + 1
    rows = [{"wp": 0, "wf": 0, "words": 0, "anchor": None, "mode": "",
             "rate": 0.0, "scroll": None, "max": 0, "vis": ""} for _ in range(nb)]
    for tag, t, d in events:
        i = min(int(t // BUCKET), nb - 1)
        r = rows[i]
        if tag == "w":
            r["wf" if d["final"] else "wp"] += 1
            if d["final"]:
                r["words"] += len(d["words"])
        elif tag == "s":
            r["anchor"], r["mode"], r["rate"] = d["anchor"], d["mode"], d["rate"]
        elif tag == "n":
            r["scroll"], r["max"] = d["scroll"], d["max"]
        elif tag == "v":
            r["vis"] = f"{d['lo']}-{d['hi']}" if d["lo"] >= 0 else "all"
    prev_anchor = None
    for i, r in enumerate(rows):
        a = r["anchor"]
        if a is None:
            delta = ""
            shown = "    -"
        else:
            delta = "" if prev_anchor is None else f" ({a - prev_anchor:+d})"
            shown = f"{a:5d}"
            prev_anchor = a
        scroll = f"{r['scroll']}/{r['max']}" if r["scroll"] is not None else "-"
        print(f"   {i * BUCKET:5.0f}s  {r['wp']:4d}/{r['wf']:<3d} {r['words']:5d}  "
              f"{shown}{delta:<7} {r['mode']:<11} {r['rate']:4.1f}  "
              f"{scroll:>11}   {r['vis']}")

    # --- ASR gaps ---------------------------------------------------------
    wt = [t for tag, t, d in events if tag == "w" and d["words"]]
    gaps = [(a, b) for a, b in zip(wt, wt[1:]) if b - a > ASR_GAP]
    if wt:
        print(f"\n   ASR: first word {wt[0]:.1f}s, last word {wt[-1]:.1f}s"
              f" ({end - wt[-1]:.1f}s before trace end)")
    if gaps:
        print("   gaps > %.0fs with no words:" % ASR_GAP)
        for a, b in gaps[:10]:
            print(f"     {a:7.1f}s -> {b:7.1f}s   ({b - a:.1f}s)")
        if len(gaps) > 10:
            print(f"     ... {len(gaps) - 10} more")

    # --- ASR engine health -------------------------------------------------
    asr = [(t, d) for tag, t, d in events if tag == "a"]
    peak_lag, lag_hot, errors, mic_drops = 0, [], [], []
    if asr:
        peak_lag = max(d["decodeMs"] for _, d in asr)
        lag_hot = [(t, d["decodeMs"]) for t, d in asr if d["decodeMs"] >= LAG_WARN]
        prev_err, prev_listening = None, None
        for t, d in asr:
            if d["error"] and d["error"] != prev_err:
                errors.append((t, d["error"]))
            if prev_listening is True and d["listening"] is False:
                mic_drops.append(t)
            prev_err, prev_listening = d["error"], d["listening"]
        print(f"\n   ASR engine: peak decode lag {peak_lag} ms of the "
              f"{CHUNK_MS} ms chunk budget")
        if lag_hot:
            print(f"     lag >= {LAG_WARN} ms at: " +
                  "  ".join(f"{t:.0f}s({ms})" for t, ms in lag_hot[:12]))
        for t, e in errors[:10]:
            print(f"     {t:7.1f}s  ERROR: {e}")
        if mic_drops:
            print("     listening dropped at: " +
                  "  ".join(f"{t:.0f}s" for t in mic_drops[:12]))
        if not lag_hot and not errors and not mic_drops:
            print("     no errors, no mic drops, lag well inside budget")
    else:
        print("\n   ASR engine: no 'a' events - trace predates ASR-health logging")

    if plays:
        print("   play/pause: " +
              "  ".join(f"{t:.0f}s={'play' if p else 'PAUSE'}" for t, p in plays[:20]))
    if reanchors:
        print("   re-anchors: " +
              "  ".join(f"{t:.0f}s->{a}" for t, a in reanchors[:20]))

    # --- verdict ----------------------------------------------------------
    print("\n== verdict")
    if peak < 0:
        print("   anchor never advanced — follow never engaged at all.")
        return
    quiet = end - last_adv
    print(f"   anchor peaked at {peak} at t={last_adv:.1f}s "
          f"({quiet:.1f}s before trace end)")
    if quiet < STALL:
        print("   no stall in this trace — the anchor was still moving at the end.")
    elif words_after:
        n = sum(len(d["words"]) for _, d in words_after)
        modes = {d["mode"] for tag, t, d in events if tag == "s" and t >= last_adv}
        print(f"   >>> MATCHER-SIDE: after the freeze the ASR still delivered "
              f"{n} words across {len(words_after)} events.")
        print(f"       mode(s) held after the freeze: {', '.join(sorted(modes))}")
        print( "       This replays deterministically — FollowTrace.replay in the "
               "JVM harness.")
        first_t, first_d = words_after[0]
        print(f"       first unmatched speech at {first_t:.1f}s: "
              f"{' '.join(w for w, _ in first_d['words'][:12])}")
    else:
        print( "   >>> ASR-SIDE: no words arrived after the anchor froze. The "
               "recognizer or the")
        print( "       mic stopped delivering — a desk replay of this trace will "
               "not reproduce it.")
        late_err = [(t, e) for t, e in errors if t >= last_adv - 5]
        late_drop = [t for t in mic_drops if t >= last_adv - 5]
        late_lag = [(t, ms) for t, ms in lag_hot if t >= last_adv - 5]
        if late_err or late_drop:
            print( "       CAUSE: the mic died and the engine said so —")
            for t, e in late_err[:3]:
                print(f"         {t:.1f}s  {e}")
            if late_drop:
                print(f"         listening went false at "
                      f"{', '.join(f'{t:.0f}s' for t in late_drop[:5])}")
            print( "       Look at the reopen/backoff loop in AsrEngine.capture().")
        elif late_lag:
            print(f"       CAUSE: decode lag hit {max(ms for _, ms in late_lag)} ms "
                  f"against a {CHUNK_MS} ms budget — the decoder fell behind and")
            print( "       the mic buffer overran, so audio was dropped silently "
                   "(thermal throttling).")
        elif asr:
            print( "       The engine reported healthy (listening, low lag) yet "
                   "emitted no words:")
            print( "       suspect encoder-state decay (sherpa-onnx#1700) or the "
                   "endpoint/rebuild path.")
        else:
            print( "       No ASR-health events in this trace — reinstall a build "
                   "with 'a' logging.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        main(p)
        print()
