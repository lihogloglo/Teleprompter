"""Shared paths and the AsrEngine emulation for the voice-command bench."""
import os
import numpy as np
import sherpa_onnx

MODELS = os.environ.get("VCB_MODELS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))
ASR = {lang: os.path.join(MODELS, f"sherpa-onnx-streaming-zipformer-{lang}-kroko-2025-08-06")
       for lang in ("fr", "en")}
SR = 16000
CHUNK = 1600  # AsrEngine.CHUNK: 100 ms per decode step


def recognizer(lang="fr", method="greedy_search", hotwords_file="", hotwords_score=1.5, bpe_vocab=""):
    """Same settings as AsrEngine.capture() (endpoint rules, threads, model type)."""
    d = ASR[lang]
    kw = dict(tokens=f"{d}/tokens.txt", encoder=f"{d}/encoder.onnx",
              decoder=f"{d}/decoder.onnx", joiner=f"{d}/joiner.onnx",
              num_threads=4, sample_rate=SR, feature_dim=80, enable_endpoint_detection=True,
              rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.7,
              rule3_min_utterance_length=20.0, decoding_method=method, model_type="zipformer2")
    if hotwords_file:
        kw.update(hotwords_file=hotwords_file, hotwords_score=hotwords_score,
                  modeling_unit="bpe", bpe_vocab=bpe_vocab)
    return sherpa_onnx.OnlineRecognizer.from_transducer(**kw)


def merge(tokens, ts):
    """Python twin of follow/Transcript.mergeBpeTokens (word start = '▁' or leading space)."""
    words = []
    for i, p in enumerate(tokens):
        if p.startswith("<"):
            continue
        start = p[:1] in ("▁", " ")
        body = (p[1:] if start else p).strip()
        if start or not words:
            words.append([body, ts[i] if i < len(ts) else 0])
        else:
            words[-1][0] += body
    return [w for w in words if w[0]]


def stream(rec, x, noise_db=None, gain=1.0, seed=0):
    """Feed audio like AsrEngine.pump(); return [(audio_t, utterance, final, [[word, t], ...])].
    Partials are emitted only when the word list changes, finals at each endpoint."""
    if noise_db is not None:
        rng = np.random.default_rng(seed)
        p = np.mean(x[np.abs(x) > 0.01] ** 2)
        x = x + rng.normal(0, np.sqrt(p / 10 ** (noise_db / 10)), len(x)).astype(np.float32)
    # 3 s of trailing silence: the kroko chunk (1.28 s) + 0.7 s endpoint must elapse.
    x = np.concatenate([x * gain, np.zeros(SR * 3, dtype=np.float32)]).astype(np.float32)
    s = rec.create_stream()
    events, utt, last = [], 0, None
    for i in range(0, len(x), CHUNK):
        s.accept_waveform(SR, x[i:i + CHUNK])
        while rec.is_ready(s):
            rec.decode_stream(s)
        t = min(i + CHUNK, len(x)) / SR
        r = rec.get_result_all(s)
        words = merge(r.tokens, r.timestamps)
        if rec.is_endpoint(s):
            if words:
                events.append((t, utt, True, words))
            utt += 1
            last = None
            rec.reset(s)
        elif words and [w[0] for w in words] != last:
            last = [w[0] for w in words]
            events.append((t, utt, False, words))
    return events
