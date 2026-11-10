"""How well does the decoder transcribe candidate command phrases, word for word?
(Exact normalized match in the finals; no detector tolerance.)   python phrases.py fr|en"""
import re, sys, unicodedata
import numpy as np
from gen import tts_for, synth, sil
from common import recognizer, stream
from langs import LANGS


def toks(w):
    d = unicodedata.normalize("NFD", w.lower())
    s = "".join(ch if ("a" <= ch <= "z" or "0" <= ch <= "9" or ch in "'-") else "" for ch in d)
    return [p for p in re.split(r"['\-]", s) if p]


if __name__ == "__main__":
    lang = sys.argv[1]
    L = LANGS[lang]
    rec = recognizer(lang)
    cache = {}
    for ph in L["candidates"]:
        target = [t for w in ph.split() for t in toks(w)]
        hits = {"clean": 0, "noisy": 0}
        n = {"clean": 0, "noisy": 0}
        heard = []
        for vname, sid in L["voices"]:
            tts = cache.setdefault(vname, tts_for(vname))
            for speed in (0.9, 1.0, 1.2):
                for ctx in ("alone", "embedded"):
                    segs = [sil(0.5)]
                    if ctx == "embedded":
                        segs += [synth(tts, L["lead"], sid, speed), sil(0.35)]
                    segs.append(synth(tts, ph, sid, speed))
                    if ctx == "embedded":
                        segs += [sil(0.35), synth(tts, L["tail"], sid, speed)]
                    segs.append(sil(2.0))
                    x = np.concatenate(segs)
                    for cond, nd, g in (("clean", None, 1.0), ("noisy", 10, 0.3)):
                        ev = stream(rec, x, noise_db=nd, gain=g)
                        hyp = [t for e in ev if e[2] for w in e[3] for t in toks(w[0])]
                        ok = any(hyp[i:i + len(target)] == target for i in range(len(hyp)))
                        hits[cond] += ok
                        n[cond] += 1
                        if not ok and ctx == "alone":
                            heard.append(" ".join(hyp))
        print(f"{ph:20} clean {100*hits['clean']/n['clean']:5.1f}%  noisy {100*hits['noisy']/n['noisy']:5.1f}%"
              f"   misses e.g.: {heard[:5]}", flush=True)
