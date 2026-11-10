"""Synthesize the bench clips with sherpa-onnx piper voices.   python gen.py fr|en

clips.json: each accepted command phrase (langs.py) alone or inside narration, said
plainly, with a comma, or as two words with a 0.9 s pause; cmd_end marks its end.
neg.json: narration only, full of near-miss words."""
import json, os, sys
import numpy as np, sherpa_onnx, soundfile as sf
from common import MODELS, SR
from langs import LANGS


def tts_for(name):
    d = os.path.join(MODELS, f"vits-piper-{name}")
    onnx = [f for f in os.listdir(d) if f.endswith(".onnx")][0]
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(d, onnx), tokens=os.path.join(d, "tokens.txt"),
                data_dir=os.path.join(d, "espeak-ng-data")),
            num_threads=4)))


def synth(tts, text, sid, speed):
    a = tts.generate(text, sid=sid, speed=speed)
    x = np.array(a.samples, dtype=np.float32)
    n = int(len(x) * SR / a.sample_rate)
    y = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    idx = np.where(np.abs(y) > 0.01)[0]
    return y[idx[0]:idx[-1] + 1] if len(idx) else y


def sil(sec):
    return np.zeros(int(sec * SR), dtype=np.float32)


if __name__ == "__main__":
    lang = sys.argv[1]
    L = LANGS[lang]
    os.makedirs("clips", exist_ok=True)
    pos, neg, cache = [], [], {}
    for vname, sid in L["voices"]:
        tts = cache.setdefault(vname, tts_for(vname))
        for phrase, styles in L["commands"].items():
            for speed in (0.9, 1.0, 1.2):
                for ctx in ("alone", "embedded"):
                    for style, parts in styles.items():
                        segs = [sil(0.5)]
                        if ctx == "embedded":
                            segs += [synth(tts, L["lead"], sid, speed), sil(0.35)]
                        for i, p in enumerate(parts):
                            if i:
                                segs.append(sil(0.9))
                            segs.append(synth(tts, p, sid, speed))
                        cmd_end = sum(len(s) for s in segs) / SR
                        if ctx == "embedded":
                            segs += [sil(0.35), synth(tts, L["tail"], sid, speed)]
                        segs.append(sil(2.0))
                        fn = f"clips/{lang}-{phrase}-{vname}-{sid}-{speed}-{ctx}-{style}.wav"
                        sf.write(fn, np.concatenate(segs), SR)
                        pos.append(dict(file=fn, voice=f"{vname}#{sid}", speed=speed, ctx=ctx,
                                        cmd=f"{phrase}/{style}", cmd_end=cmd_end))
        for k, text in enumerate(L["neg"]):
            fn = f"clips/{lang}-neg-{vname}-{sid}-{k}.wav"
            sf.write(fn, np.concatenate([sil(0.5), synth(tts, text, sid, 1.0), sil(2.0)]), SR)
            neg.append(dict(file=fn, voice=f"{vname}#{sid}", text=text))
    json.dump(pos, open(f"clips_{lang}.json", "w"), indent=1)
    json.dump(neg, open(f"neg_{lang}.json", "w"), indent=1)
    print(len(pos), "command clips,", len(neg), "narration clips")
