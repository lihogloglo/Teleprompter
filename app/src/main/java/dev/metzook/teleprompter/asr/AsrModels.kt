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
    // One model per language, the best available: kroko streaming zipformers
    // (2025), ~71 MB each. [ALL] is what the settings screen offers; [LEGACY]
    // holds superseded models that stay usable if already installed and are
    // deleted automatically once their replacement is downloaded.
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

    // Superseded (2023): not offered for download anymore.
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

    /** Offered in settings: exactly one model per language. */
    val ALL = listOf(EN_KROKO, FR_KROKO)

    /** Superseded models: usable while installed, cleaned up on upgrade. */
    val LEGACY = listOf(EN_SMALL, FR)

    fun byId(id: String?): AsrModel =
        (ALL + LEGACY).firstOrNull { it.id == id } ?: EN_KROKO

    /** Models this one replaces (same language, different id). */
    fun supersededBy(model: AsrModel): List<AsrModel> =
        (ALL + LEGACY).filter { it.lang == model.lang && it.id != model.id }
}
