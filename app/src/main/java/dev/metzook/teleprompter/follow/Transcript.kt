package dev.metzook.teleprompter.follow

/** One recognized word with its utterance-relative timestamp (seconds). */
data class TimedWord(val text: String, val timeSec: Float)

private const val BPE_MARK = '▁' // "▁", word-start marker in sherpa BPE vocabularies
private val SPECIAL = Regex("^<.*>$") // <unk>, <blk>, …

/**
 * Merge streaming-recognizer subword pieces into words, keeping the first
 * piece's timestamp per word. Falls back to piece-per-word for vocabularies
 * without the BPE word-start marker.
 */
fun mergeBpeTokens(pieces: List<String>, timestamps: FloatArray): List<TimedWord> {
    fun timeAt(i: Int) = if (i < timestamps.size) timestamps[i] else timestamps.lastOrNull() ?: 0f

    if (pieces.none { it.startsWith(BPE_MARK) }) {
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
        if (SPECIAL.matches(piece)) return@forEachIndexed
        if (piece.startsWith(BPE_MARK)) {
            flush()
            start = timeAt(i)
            sb.append(piece, 1, piece.length)
        } else {
            if (sb.isEmpty()) start = timeAt(i)
            sb.append(piece)
        }
    }
    flush()
    return words
}
