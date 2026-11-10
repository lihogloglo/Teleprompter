#!/usr/bin/env python3
"""Cached bench events -> gzipped TSV replay fixture for the JVM tests.
Lines:  C <tab> clip <tab> kind <tab> voice <tab> speed <tab> ctx <tab> style <tab> cond <tab> cmd_end <tab> text
        E <tab> clip <tab> t <tab> utterance <tab> F|P <tab> words(space) <tab> times(space)"""
import gzip, json, sys
data = json.load(open(sys.argv[1])); out = gzip.open(sys.argv[2], "wt", encoding="utf-8", compresslevel=9)
n = 0
for kind in ("pos", "neg"):
    for c in data[kind]:
        cid = f"c{n}"; n += 1
        out.write("\t".join(["C", cid, kind, c["voice"], str(c.get("speed", 1.0)), c.get("ctx", "narration"),
                             c.get("cmd", "-"), c["cond"], f'{c.get("cmd_end", -1):.3f}', c.get("text", "")]) + "\n")
        for (t, utt, final, words) in c["events"]:
            assert all(" " not in w[0] and "\t" not in w[0] for w in words)
            out.write("\t".join(["E", cid, f"{t:.2f}", str(utt), "F" if final else "P",
                                 " ".join(w[0] for w in words), " ".join(f"{w[1]:.2f}" for w in words)]) + "\n")
out.close(); print(n, "clips")
