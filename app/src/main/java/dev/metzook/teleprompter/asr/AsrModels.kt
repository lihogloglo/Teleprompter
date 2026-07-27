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
    val EN_SMALL = AsrModel(
        id = "zipformer-en-20M",
        label = "English · small & fast (≈46 MB)",
        lang = "en",
        repoBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main",
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
    )

    val FR = AsrModel(
        id = "zipformer-fr",
        label = "French · large (≈130 MB)",
        lang = "fr",
        repoBaseUrl =
            "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main",
        encoder = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        decoder = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
        joiner = "joiner-epoch-29-avg-9-with-averaged-model.onnx",
    )

    val ALL = listOf(EN_SMALL, FR)

    fun byId(id: String?): AsrModel = ALL.firstOrNull { it.id == id } ?: EN_SMALL
}
