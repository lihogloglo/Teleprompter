"""Hotword files for kroko-fr without the sentencepiece model it doesn't ship.

sherpa-onnx (1.13.4) only needs bpe_vocab to split hotword text into pieces
(EncodeHotwords → ssentencepiece); it looks the pieces up in tokens.txt by
their raw '▁' spelling. A vocab holding just the pieces the decoder itself
emits for the phrase ('▁c om m and e ▁re cul', read off result.tokens), plus
low-scored single characters, reproduces that segmentation."""
TOKENS = "models/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/tokens.txt"
PIECES = ["▁c", "▁C", "om", "m", "and", "an", "e", "▁re", "cul", "▁", "R", "r"]
if __name__ == "__main__":
    import os
    from common import MODELS
    toks = [l.rsplit(" ", 1)[0] for l in open(os.path.join(MODELS, TOKENS.split("/", 1)[1]), encoding="utf-8").read().splitlines()]
    with open("bpe.vocab", "w", encoding="utf-8") as f:
        for t in PIECES:
            assert t in toks, t
            f.write(f"{t}\t0\n")
        for t in toks:
            if t not in PIECES and len(t) == 1:
                f.write(f"{t}\t-10\n")
    open("hot.txt", "w").write("commande recul\nCommande recul\ncommande recule\nCommande recule\n")
