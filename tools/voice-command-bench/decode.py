"""Decode every clip clean and noisy (10 dB white noise, gain 0.3: a far, quiet
speaker) into an event cache:  python decode.py fr|en [greedy|mbs|hotwords]  -> ev_<lang>_<cfg>.json"""
import json, sys
import soundfile as sf
from common import recognizer, stream

CONFIGS = {
    "greedy": dict(),
    "mbs": dict(method="modified_beam_search"),
    # Hotword biasing (fr): see make_hotwords.py. Measured worse for narration.
    "hotwords": dict(method="modified_beam_search", hotwords_file="hot.txt", hotwords_score=1.5, bpe_vocab="bpe.vocab"),
}

if __name__ == "__main__":
    lang = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 else "greedy"
    rec = recognizer(lang, **CONFIGS[name])
    out = {"pos": [], "neg": []}
    for kind, f in (("pos", f"clips_{lang}.json"), ("neg", f"neg_{lang}.json")):
        for c in json.load(open(f)):
            x, _ = sf.read(c["file"], dtype="float32")
            for cond, nd, g in (("clean", None, 1.0), ("noisy", 10, 0.3)):
                out[kind].append(dict(c, cond=cond, events=stream(rec, x, nd, g)))
    json.dump(out, open(f"ev_{lang}_{name}.json", "w"))
    print("wrote", f"ev_{lang}_{name}.json")
