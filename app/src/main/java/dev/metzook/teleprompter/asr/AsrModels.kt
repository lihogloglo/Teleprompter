package dev.metzook.teleprompter.asr

/**
 * On-device streaming ASR models (sherpa-onnx zipformer transducers, SPEC §3).
 * File names match the official sherpa-onnx Android model registry.
 */
data class AsrModel(
    val id: String,
    val label: String,
    val lang: String,
    val repoBaseUrl: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String = "tokens.txt",
    val modelType: String = "zipformer",
) {
    val files: List<String> get() = listOf(encoder, decoder, joiner, tokens)
}

object AsrModels {
    // Kroko streaming zipformers (2025): two years newer training than the
    // 2023 models below, at ~71 MB each — the accuracy upgrade the follow
    // matcher lives on (docs/voice-follow-research.md). Listed first per
    // language: the preferred-model pick takes the first installed match.
    val EN_KROKO = AsrModel(
        id = "zipformer-en-kroko",
        label = "English · accurate, 2025 (≈71 MB)",
        lang = "en",
        repoBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main",
        encoder = "encoder.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.onnx",
        modelType = "zipformer2",
    )

    val FR_KROKO = AsrModel(
        id = "zipformer-fr-kroko",
        label = "French · accurate, 2025 (≈71 MB)",
        lang = "fr",
        repoBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main",
        encoder = "encoder.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.onnx",
        modelType = "zipformer2",
    )

    val EN_SMALL = AsrModel(
        id = "zipformer-en-20M",
        label = "English · small & fast, 2023 (≈46 MB)",
        lang = "en",
        repoBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main",
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
    )

    val FR = AsrModel(
        id = "zipformer-fr",
        label = "French · large, 2023 (≈130 MB)",
        lang = "fr",
        repoBaseUrl =
            "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main",
        encoder = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        decoder = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
        joiner = "joiner-epoch-29-avg-9-with-averaged-model.onnx",
    )

    val ALL = listOf(EN_KROKO, FR_KROKO, EN_SMALL, FR)

    fun byId(id: String?): AsrModel = ALL.firstOrNull { it.id == id } ?: EN_SMALL
}
