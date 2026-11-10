"""Synthesize the bench clips with sherpa-onnx piper voices.

clips.json: the command ("Commande recul") alone or inside narration, said plainly,
with a comma, or as two words with a 0.9 s pause; cmd_end marks where it ends.
neg.json: narration only, full of near-miss words."""
import json, os
import numpy as np, sherpa_onnx, soundfile as sf
from common import MODELS, SR

# fr_FR-gilles-low is excluded: its phoneme table lacks the nasal tilde, so
# "commande" comes out as "commade".
VOICES = [("siwis-medium", 0), ("upmc-medium", 0), ("upmc-medium", 1), ("tom-medium", 0), ("miro-high", 0)]
LEAD = "Et c'est exactement à ce moment-là que tout a basculé pour nous."
TAIL = "Alors reprenons depuis le début de cette histoire."
STYLES = {"plain": ["Commande recul."], "comma": ["Commande, recul !"], "pause": ["Commande.", "Recul."]}
NEG = [
    "Comment reculer sans perdre la face devant ses clients ?",
    "J'ai passé commande hier soir, et la commande est arrivée ce matin.",
    "Avec le recul, je pense que c'était une erreur de débutant.",
    "Le commandant a donné l'ordre de reculer avant la nuit.",
    "Comment de temps faut-il pour apprendre à coder un jeu vidéo ?",
    "La commande reçue était incomplète, alors on a recommencé.",
    "On ne recule devant rien, c'est comme ça qu'on avance.",
    "Comment le recul change notre façon de voir les choses.",
    LEAD + " " + TAIL,
]


def tts_for(name):
    d = os.path.join(MODELS, f"vits-piper-fr_FR-{name}")
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
    os.makedirs("clips", exist_ok=True)
    pos, neg, cache = [], [], {}
    for vname, sid in VOICES:
        tts = cache.setdefault(vname, tts_for(vname))
        for speed in (0.9, 1.0, 1.2):
            for ctx in ("alone", "embedded"):
                for style, parts in STYLES.items():
                    segs = [sil(0.5)]
                    if ctx == "embedded":
                        segs += [synth(tts, LEAD, sid, speed), sil(0.35)]
                    for i, p in enumerate(parts):
                        if i:
                            segs.append(sil(0.9))
                        segs.append(synth(tts, p, sid, speed))
                    cmd_end = sum(len(s) for s in segs) / SR
                    if ctx == "embedded":
                        segs += [sil(0.35), synth(tts, TAIL, sid, speed)]
                    segs.append(sil(2.0))
                    fn = f"clips/{vname}-{sid}-{speed}-{ctx}-{style}.wav"
                    sf.write(fn, np.concatenate(segs), SR)
                    pos.append(dict(file=fn, voice=f"{vname}#{sid}", speed=speed, ctx=ctx, cmd=style, cmd_end=cmd_end))
        for k, text in enumerate(NEG):
            fn = f"clips/neg-{vname}-{sid}-{k}.wav"
            sf.write(fn, np.concatenate([sil(0.5), synth(tts, text, sid, 1.0), sil(2.0)]), SR)
            neg.append(dict(file=fn, voice=f"{vname}#{sid}", text=text))
    json.dump(pos, open("clips.json", "w"), indent=1)
    json.dump(neg, open("neg.json", "w"), indent=1)
    print(len(pos), "command clips,", len(neg), "narration clips")
