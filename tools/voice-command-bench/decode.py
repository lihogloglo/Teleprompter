"""Decode every clip clean and noisy (10 dB white noise, gain 0.3: a far, quiet
speaker) into an event cache:  python decode.py greedy|mbs|hotwords  -> ev_<name>.json"""
import json, sys
import soundfile as sf
from common import recognizer, stream

CONFIGS = {
    "greedy": dict(),
    "mbs": dict(method="modified_beam_search"),
    # Hotword biasing: see make_hotwords.py. Measured worse for narration; kept for reproduction.
    "hotwords": dict(method="modified_beam_search", hotwords_file="hot.txt", hotwords_score=1.5, bpe_vocab="bpe.vocab"),
}

if __name__ == "__main__":
    name = sys.argv[1]
    rec = recognizer(**CONFIGS[name])
    out = {"pos": [], "neg": []}
    for kind, f in (("pos", "clips.json"), ("neg", "neg.json")):
        for c in json.load(open(f)):
            x, _ = sf.read(c["file"], dtype="float32")
            for cond, nd, g in (("clean", None, 1.0), ("noisy", 10, 0.3)):
                out[kind].append(dict(c, cond=cond, events=stream(rec, x, nd, g)))
    json.dump(out, open(f"ev_{name}.json", "w"))
    print("wrote", f"ev_{name}.json")
