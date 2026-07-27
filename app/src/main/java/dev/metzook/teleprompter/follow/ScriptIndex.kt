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
) {
    /** norm → ascending token positions; drives the rejoin run tracker. */
    val positionsByNorm: Map<String, IntArray> = run {
        val map = HashMap<String, MutableList<Int>>()
        tokens.forEachIndexed { i, t -> map.getOrPut(t.norm) { ArrayList() }.add(i) }
        map.mapValues { (_, v) -> v.toIntArray() }
    }

    companion object {
        private val WORD = Regex("\\S+")

        fun build(segments: List<SpokenSegment>, lang: MatchLang): ScriptIndex {
            val tokens = ArrayList<ScriptToken>()
            var offset = 0
            for (seg in segments) {
                if (seg.spoken) {
                    for (m in WORD.findAll(seg.text)) {
                        for (norm in TextNorm.matchTokens(m.value, lang)) {
                            tokens += ScriptToken(
                                norm = norm,
                                charStart = offset + m.range.first,
                                charEnd = offset + m.range.last + 1,
                            )
                        }
                    }
                }
                offset += seg.text.length
            }
            return ScriptIndex(tokens, lang)
        }
    }
}
