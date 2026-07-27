package dev.metzook.teleprompter.follow

/**
 * A piece of display text in render order. `spoken = false` for headings,
 * stage directions, rules and separators: rendered but never matched.
 */
data class SpokenSegment(val text: String, val spoken: Boolean)

/**
 * One matchable token. [charStart]/[charEnd] index into the concatenated
 * display text (the same string the prompter renders), so the matcher's
 * anchor translates to a layout position for free. Tokens produced by
 * splitting one display word (elisions, numbers) share its char range.
 */
data class ScriptToken(
    val norm: String,
    val charStart: Int,
    val charEnd: Int,
)

/** The script's spoken-token index (SPEC §3.2). Pure Kotlin, built once per script. */
class ScriptIndex(
    val tokens: List<ScriptToken>,
    val lang: MatchLang,
    /** Token index of each paragraph's first spoken token, ascending. */
    val paragraphStarts: IntArray = intArrayOf(),
) {
    /** norm → ascending token positions; drives the rejoin run tracker. */
    val positionsByNorm: Map<String, IntArray> = run {
        val map = HashMap<String, MutableList<Int>>()
        tokens.forEachIndexed { i, t -> map.getOrPut(t.norm) { ArrayList() }.add(i) }
        map.mapValues { (_, v) -> v.toIntArray() }
    }

    /**
     * Anchor for a remote-key "retake": start of the current paragraph, or the
     * previous one when the reading barely entered this one (track-skip idiom).
     * Anchors are "last spoken token", so a paragraph starting at s → s - 1.
     */
    fun paragraphBackAnchor(anchor: Int): Int {
        if (paragraphStarts.isEmpty()) return anchor
        val current = paragraphStarts.lastOrNull { it <= anchor } ?: return -1
        val target =
            if (anchor - current <= 1) paragraphStarts.lastOrNull { it < current } ?: current
            else current
        return target - 1
    }

    /** Anchor at the start of the next unread paragraph; unchanged from the last one. */
    fun paragraphForwardAnchor(anchor: Int): Int =
        paragraphStarts.firstOrNull { it > anchor + 1 }?.let { it - 1 } ?: anchor

    /** Index of the first token at or after a display-text char offset. */
    fun firstTokenAtOrAfter(charOffset: Int): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (tokens[mid].charStart < charOffset) lo = mid + 1 else hi = mid
        }
        return lo.coerceIn(0, (tokens.size - 1).coerceAtLeast(0))
    }

    companion object {
        private val WORD = Regex("\\S+")

        fun build(segments: List<SpokenSegment>, lang: MatchLang): ScriptIndex {
            val tokens = ArrayList<ScriptToken>()
            val paragraphStarts = ArrayList<Int>()
            var offset = 0
            var paragraphBreak = true
            for (seg in segments) {
                if (seg.spoken) {
                    for (m in WORD.findAll(seg.text)) {
                        val norms = TextNorm.matchTokens(m.value, lang)
                        if (norms.isNotEmpty() && paragraphBreak) {
                            paragraphStarts += tokens.size
                            paragraphBreak = false
                        }
                        for (norm in norms) {
                            tokens += ScriptToken(
                                norm = norm,
                                charStart = offset + m.range.first,
                                charEnd = offset + m.range.last + 1,
                            )
                        }
                    }
                } else if (seg.text.contains('\n')) {
                    // Separator segments mark paragraph boundaries; inline stage
                    // directions don't split a paragraph.
                    paragraphBreak = true
                }
                offset += seg.text.length
            }
            return ScriptIndex(tokens, lang, paragraphStarts.toIntArray())
        }
    }
}
