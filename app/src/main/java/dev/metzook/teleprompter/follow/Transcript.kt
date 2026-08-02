package dev.metzook.teleprompter.follow

/** One recognized word with its utterance-relative timestamp (seconds). */
data class TimedWord(val text: String, val timeSec: Float)

private const val BPE_MARK = '▁' // "▁", word-start marker in sherpa BPE vocabularies
private val SPECIAL = Regex("^<.*>$") // <unk>, <blk>, …

/**
 * True if [piece] opens a new word instead of continuing the current one.
 *
 * A word start reaches us one of two ways and both must be honoured:
 *  - the raw BPE marker '▁' (U+2581), as it appears in the model's tokens.txt;
 *  - a leading ASCII space, because sherpa-onnx's SymbolTable substitutes one
 *    for '▁' when it loads the vocabulary, so the JNI hands Kotlin " de" where
 *    the vocab holds "▁de".
 *
 * Reading the boundary *before* trimming is the whole point: trimming first
 * erases the only signal that separates "▁f an" (one word, "fan") from two.
 */
private fun isWordStart(piece: String): Boolean =
    piece.isNotEmpty() && (piece[0] == BPE_MARK || piece[0].isWhitespace())

/** The piece's contribution to the word text, without its word-start marker. */
private fun pieceBody(piece: String): String =
    (if (isWordStart(piece)) piece.substring(1) else piece).trim()

/**
 * Merge streaming-recognizer subword pieces into words, keeping the first
 * piece's timestamp per word. Falls back to piece-per-word for vocabularies
 * that mark no word starts at all (character models).
 */
fun mergeBpeTokens(pieces: List<String>, timestamps: FloatArray): List<TimedWord> {
    fun timeAt(i: Int) = if (i < timestamps.size) timestamps[i] else timestamps.lastOrNull() ?: 0f

    if (pieces.none { isWordStart(it) }) {
        return pieces.mapIndexedNotNull { i, p ->
            val text = p.trim()
            if (text.isEmpty() || SPECIAL.matches(text)) null else TimedWord(text, timeAt(i))
        }
    }

    val words = ArrayList<TimedWord>()
    val sb = StringBuilder()
    var start = 0f
    fun flush() {
        if (sb.isNotEmpty()) {
            words += TimedWord(sb.toString(), start)
            sb.clear()
        }
    }
    pieces.forEachIndexed { i, piece ->
        if (SPECIAL.matches(piece.trim())) return@forEachIndexed
        if (isWordStart(piece)) {
            flush()
            start = timeAt(i)
        } else if (sb.isEmpty()) {
            start = timeAt(i)
        }
        sb.append(pieceBody(piece))
    }
    flush()
    return words
}
