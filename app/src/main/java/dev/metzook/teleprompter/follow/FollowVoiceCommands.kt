package dev.metzook.teleprompter.follow

/** Speech routed to script tracking, with newly confirmed page-back commands. */
data class FollowVoiceInput(val words: List<TimedWord>, val pagesBack: Int)

/**
 * The page-back phrase as the recognizer actually transcribes it:
 * `PREFIX [FILLER] ENDING` (docs/follow-voice-commands.md, "v0.9.5").
 *
 * Built from measured kroko-fr output, not from the spelling: spoken
 * "commande recul" comes back as "commande recul", "commande recule",
 * "la commande reculent", but also — the /d/ of commande sliding onto the
 * next word — "comment de recul", "comment te recule", "comment trecule".
 */
internal class CommandGrammar(
    val hint: String,
    /** Command-word variants that never start ordinary narration before an ending. */
    val prefixes: Set<String>,
    /** Mishearings of the command word that are also ordinary words ("comment"). */
    val weakPrefixes: Set<String>,
    /** Short residue the decoder splits off the prefix ("comment *de* recul"). */
    val fillers: Set<String>,
    /** Accepted endings after a strong prefix. */
    val endings: Regex,
    /** Stricter endings after a weak prefix (no verb forms: "comment reculer" is French). */
    val weakEndings: Regex,
    /** The exact phrase; the only form that fires while the script itself shows a variant. */
    val canonicalPrefix: String,
    val canonicalEndings: Set<String>,
    /** Endings the decoder may split in two ("re" + "cul"): first piece → second-piece pattern. */
    val splitEnding: Pair<String, Regex>?,
    /** Partial words that may still grow into an ending, so they're held from tracking. */
    val endingStarts: List<String>,
) {
    val allPrefixes: Set<String> = prefixes + weakPrefixes

    fun ending(token: String, strong: Boolean): Boolean =
        (if (strong) endings else weakEndings).matches(token)

    fun maybeEnding(token: String): Boolean =
        token.isNotEmpty() && endingStarts.any { it.startsWith(token) }

    companion object {
        fun forLang(lang: MatchLang): CommandGrammar = when (lang) {
            MatchLang.FR -> CommandGrammar(
                hint = "Commande recul",
                prefixes = setOf(
                    "commande", "commandes", "commander", "commandez", "commandent",
                    "commandee", "commandees", "comande", "commende",
                ),
                weakPrefixes = setOf("comment"),
                fillers = setOf("de", "te", "ne", "d"),
                endings = Regex("[dt]?(re)?cul(e|es|s|ent|er|ez|ee)?"),
                weakEndings = Regex("[dt]?recul(e|es|s)?"),
                canonicalPrefix = "commande",
                canonicalEndings = setOf("recul", "recule"),
                splitEnding = "re" to Regex("cul.*"),
                endingStarts = listOf("recul", "drecul", "trecul", "cul"),
            )
            // Not yet measured on kroko-en: inflection slips only, no weak
            // prefixes or fillers until real decoder output shows a need.
            MatchLang.EN -> CommandGrammar(
                hint = "Command rewind",
                prefixes = setOf("command", "commands", "commanded"),
                weakPrefixes = emptySet(),
                fillers = emptySet(),
                endings = Regex("rewind(s|ing|ed)?|rewound"),
                weakEndings = Regex("(?!)"), // matches nothing: no weak prefixes
                canonicalPrefix = "command",
                canonicalEndings = setOf("rewind"),
                splitEnding = "re" to Regex("wind.*"),
                endingStarts = listOf("rewind"),
            )
        }
    }
}

/**
 * Page-back commands spoken during narration ("Commande recul" / "Command
 * rewind"), detected in the same word stream the [Matcher] consumes.
 *
 * Tuned on real decoder output (docs/follow-voice-commands.md):
 *  - **Tolerant phrase**: [CommandGrammar] accepts the forms the model really
 *    emits for the command, including "comment de recul". A weak prefix
 *    ("comment") only fires once its ending is stable, because "comment
 *    recul…" can still grow into "comment reculer", ordinary French.
 *  - **Fires on the partial**: a strong prefix fires the moment its ending
 *    appears, even as the still-growing last word. Greedy transducer output
 *    only ever extends, and every growth of "commande recul…" is still the
 *    command. Waiting for the next word or the endpoint cost a full 1.28 s
 *    decoder chunk (or 0.7 s of silence plus a chunk) on top of the model's
 *    own latency.
 *  - **Spans one endpoint**: "Commande… recul" with a pause lands in two
 *    utterances. A final that ends on a prefix arms the next utterance, whose
 *    first word (within [CROSS_UTTERANCE_SEC]) may complete it.
 *  - **Script guard**: if the visible script text itself contains a variant
 *    (e.g. "comment de recul"), only the exact canonical phrase fires while it's
 *    on screen, so reading the script can't page back.
 *
 * Confirmed commands and all earlier words of the utterance are blanked (indices
 * and timestamps kept), so a cumulative partial can't replay pre-retake speech
 * at the new position. Words that may still turn out to be a command are held
 * out of tracking until resolved.
 */
class FollowVoiceCommands(
    private val lang: MatchLang,
    scriptTokens: List<String> = emptyList(),
) {
    constructor(index: ScriptIndex) : this(index.lang, index.tokens.map { it.norm })

    private val g = CommandGrammar.forLang(lang)
    val hint: String = g.hint

    /** Script token positions where a (non-canonical) command form is printed. */
    private val scriptHits: IntArray = scanScript(scriptTokens)

    private var utterance = -1
    /** Words of [utterance] before this index are consumed (fired or pre-fire). */
    private var firedThrough = 0
    /** Utterance whose final ended on a bare prefix, waiting for its ending. */
    private var pendingUtterance = Int.MIN_VALUE
    private var pendingStrong = false

    /** A spoken token and the ASR word it came from. */
    private class Tok(val text: String, val word: Int)

    fun onWords(
        id: Int,
        words: List<TimedWord>,
        final: Boolean,
        visible: IntRange? = null,
    ): FollowVoiceInput {
        if (id != utterance) {
            utterance = id
            firedThrough = 0
        }
        val strict = visible != null && scriptHits.any { it in visible }
        val toks = tokenize(words)
        // A word is stable once a later word exists or the utterance is final.
        fun stable(word: Int) = final || word < words.lastIndex
        var pagesBack = 0

        // Completion of a prefix that ended the previous utterance.
        if (!strict && pendingUtterance == id - 1 && firedThrough == 0 && toks.isNotEmpty()) {
            var j = 0
            if (toks[j].text in g.fillers && toks.size > 1) j++
            val end = toks[j]
            if (g.ending(end.text, pendingStrong) && words[end.word].timeSec <= CROSS_UTTERANCE_SEC &&
                (pendingStrong || stable(end.word))
            ) {
                pagesBack++
                firedThrough = end.word + 1
                pendingUtterance = Int.MIN_VALUE
            }
        }

        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.word < firedThrough) {
                i++
                continue
            }
            val strong = t.text in g.prefixes
            val weak = !strong && t.text in g.weakPrefixes
            if (strong || weak) {
                var j = i + 1
                val filler = j < toks.size && toks[j].text in g.fillers
                if (filler) j++
                if (j < toks.size) {
                    val end = toks[j]
                    val canonical = !filler && t.text == g.canonicalPrefix &&
                        end.text in g.canonicalEndings
                    if (g.ending(end.text, strong) && (strong || stable(end.word)) &&
                        (!strict || canonical)
                    ) {
                        pagesBack++
                        firedThrough = end.word + 1
                        i = j + 1
                        continue
                    }
                }
            }
            i++
        }

        if (final) armNextUtterance(id, toks, strict)
        // An armed prefix only reaches into the very next utterance.
        if (id > pendingUtterance + 1) pendingUtterance = Int.MIN_VALUE

        val limit = if (final) words.size else holdFrom(id, toks, words.size)
        val routed = words.take(limit).mapIndexed { index, word ->
            if (index < firedThrough) word.copy(text = "") else word
        }
        return FollowVoiceInput(routed, pagesBack)
    }

    /** A final ending on "PREFIX [FILLER]" may be completed by the next utterance. */
    private fun armNextUtterance(id: Int, toks: List<Tok>, strict: Boolean) {
        if (strict || toks.isEmpty()) return
        var k = toks.lastIndex
        if (toks[k].text in g.fillers && k > 0) k--
        val t = toks[k]
        if (t.word < firedThrough) return
        val strong = t.text in g.prefixes
        if (strong || t.text in g.weakPrefixes) {
            pendingUtterance = id
            pendingStrong = strong
        }
    }

    /**
     * First word index held back from tracking in a partial: a trailing
     * possible-command (prefix, filler, an ending still growing or, after a
     * weak prefix, not yet stable), a growing word that may still become a
     * prefix ("com…"), or the head of an utterance armed by [armNextUtterance].
     * Everything before it is safe narration.
     */
    private fun holdFrom(id: Int, toks: List<Tok>, wordCount: Int): Int {
        var hold = wordCount
        val n = toks.size
        if (n > 0 && toks[n - 1].word >= firedThrough && toks[n - 1].word == wordCount - 1) {
            val last = toks[n - 1].text
            if (last.length >= 3 && g.allPrefixes.any { it.startsWith(last) }) hold = toks[n - 1].word
        }
        for (start in maxOf(0, n - 3) until n) {
            val t = toks[start]
            if (t.word < firedThrough) continue
            if (t.text !in g.prefixes && t.text !in g.weakPrefixes) continue
            var j = start + 1
            if (j < n && toks[j].text in g.fillers) j++
            val tail = n - j // tokens after prefix [filler]
            val plausible = tail == 0 ||
                (tail == 1 && (g.maybeEnding(toks[j].text) || g.ending(toks[j].text, false)))
            if (plausible) {
                hold = minOf(hold, t.word)
                break
            }
        }
        if (pendingUtterance == id - 1 && firedThrough == 0 && n in 1..2) {
            val first = toks[0].text
            val head = first in g.fillers || g.maybeEnding(first) || g.ending(first, pendingStrong)
            if (head) hold = minOf(hold, toks[0].word)
        }
        return hold
    }

    private fun tokenize(words: List<TimedWord>): List<Tok> {
        val raw = ArrayList<Tok>(words.size + 2)
        words.forEachIndexed { w, word ->
            for (t in TextNorm.matchTokens(word.text, lang)) raw += Tok(t, w)
        }
        return joinSplitEndings(raw)
    }

    /** "re" + "cul" → "recul" (the decoder sometimes opens a word mid-ending). */
    private fun joinSplitEndings(raw: List<Tok>): List<Tok> {
        val split = g.splitEnding ?: return raw
        val out = ArrayList<Tok>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i].text == split.first && i + 1 < raw.size && split.second.matches(raw[i + 1].text)) {
                out += Tok(raw[i].text + raw[i + 1].text, raw[i + 1].word)
                i += 2
            } else {
                out += raw[i]
                i++
            }
        }
        return out
    }

    /** Positions of command forms the script itself contains (canonical excluded). */
    private fun scanScript(tokens: List<String>): IntArray {
        if (tokens.isEmpty()) return IntArray(0)
        val toks = joinSplitEndings(tokens.mapIndexed { i, t -> Tok(t, i) })
        val hits = ArrayList<Int>()
        for (i in toks.indices) {
            val p = toks[i].text
            val strong = p in g.prefixes
            if (!strong && p !in g.weakPrefixes) continue
            var j = i + 1
            val filler = j < toks.size && toks[j].text in g.fillers
            if (filler) j++
            if (j >= toks.size || !g.ending(toks[j].text, strong)) continue
            val canonical = !filler && p == g.canonicalPrefix && toks[j].text in g.canonicalEndings
            if (!canonical) hits += toks[i].word
        }
        return hits.toIntArray()
    }

    companion object {
        /** Latest utterance-relative start for the ending that completes an armed prefix. */
        const val CROSS_UTTERANCE_SEC = 2.0f
    }
}
