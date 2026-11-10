package dev.metzook.teleprompter.follow

/** Speech routed to script tracking, with newly confirmed page-back commands. */
data class FollowVoiceInput(val words: List<TimedWord>, val pagesBack: Int)

/**
 * One page-back phrase as the recognizer actually transcribes it:
 * `PREFIX [FILLER] ENDING` (docs/follow-voice-commands.md).
 *
 * Built from measured kroko output, not from the spelling: spoken "commande
 * recul" comes back as "commande recul", "la commande reculent", but also (the
 * /d/ of commande sliding onto the next word) as "comment de recul".
 */
internal class CommandGrammar(
    val hint: String,
    /** Command-word variants that never start ordinary narration before an ending. */
    val prefixes: Set<String>,
    /** Mishearings of the command word that are also ordinary words ("comment"). */
    val weakPrefixes: Set<String> = emptySet(),
    /** Short residue the decoder splits off the prefix ("comment *de* recul"). */
    val fillers: Set<String> = emptySet(),
    /** Accepted endings after a strong prefix. */
    val endings: Regex,
    /** Stricter endings after a weak prefix (no verb forms: "comment reculer" is French). */
    val weakEndings: Regex = NOTHING,
    /** The exact phrase; the only form that fires while the script itself shows a variant. */
    val canonicalPrefix: String,
    val canonicalEndings: Set<String>,
    /** The filler the exact phrase itself contains ("back *go* back"), if any. */
    val canonicalFiller: String? = null,
    /** Endings the decoder may split in two ("re" + "cul"): first piece → second-piece pattern. */
    val splitEnding: Pair<String, Regex>? = null,
    /** Partial words that may still grow into an ending, so they're held from tracking. */
    val endingStarts: List<String>,
    /**
     * True when an ending, still growing, could become narration even after a
     * strong prefix ("retour retour…" vs "au retour, retournez"): it must then
     * be stable before firing, like a weak prefix's ending.
     */
    val endingMayGrowIntoNarration: Boolean = false,
    /** Whether a prefix closing one utterance may be completed by the next. */
    val spansEndpoint: Boolean = true,
    /** If set, a phrase *without* its filler needs one of these right before ("*go* back back"). */
    val leadIns: Set<String> = emptySet(),
    /** Words that, right before the prefix, make it narration ("*come* back, go back"). */
    val vetoBefore: Set<String> = emptySet(),
) {
    val allPrefixes: Set<String> = prefixes + weakPrefixes

    fun ending(token: String, strong: Boolean): Boolean =
        (if (strong) endings else weakEndings).matches(token)

    fun maybeEnding(token: String): Boolean =
        token.isNotEmpty() && endingStarts.any { it.startsWith(token) }

    fun leadInOk(before: String?, filler: Boolean) =
        before !in vetoBefore && (filler || leadIns.isEmpty() || before in leadIns)

    fun canonical(prefix: String, filler: String?, ending: String) =
        prefix == canonicalPrefix && filler == canonicalFiller && ending in canonicalEndings

    /** May an ending fire while it is still the growing last word of a partial? */
    fun firesUnstable(strong: Boolean) = strong && !endingMayGrowIntoNarration

    companion object {
        private val NOTHING = Regex("(?!)")

        fun forLang(lang: MatchLang): List<CommandGrammar> = when (lang) {
            MatchLang.FR -> listOf(
                CommandGrammar(
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
                ),
                // Measured: the decoder swaps "retour" with "autour", "tour" and
                // "photo" ("retour autour", "votre tour retour", "photo retour")
                // and hears "retour au retour". A bare "au retour" is everyday
                // French, so "au" only counts between two halves.
                CommandGrammar(
                    hint = "Retour retour",
                    prefixes = setOf("retour", "retours"),
                    weakPrefixes = setOf("autour", "tour", "photo", "poto"),
                    fillers = setOf("au", "a"),
                    endings = Regex("retours?|autour"),
                    weakEndings = Regex("retours?"),
                    canonicalPrefix = "retour",
                    canonicalEndings = setOf("retour"),
                    endingStarts = listOf("retour"),
                    endingMayGrowIntoNarration = true,
                ),
            )
            MatchLang.EN -> listOf(
                // Measured on kroko-en: the start of "command" is swallowed as
                // often as not ("a man rewind", "amand rewind", "demand
                // rewind"). Real words among those are weak: exact, stable
                // "rewind" only, so "a man rewinds the tape" stays narration.
                CommandGrammar(
                    hint = "Command rewind",
                    prefixes = setOf("command", "commands", "commanded", "amand", "mand", "haman", "fermand"),
                    weakPrefixes = setOf("man", "demand"),
                    endings = Regex("rewind(s|ing|ed)?|rewound|bewind"),
                    weakEndings = Regex("rewind"),
                    canonicalPrefix = "command",
                    canonicalEndings = setOf("rewind"),
                    splitEnding = "re" to Regex("wind.*"),
                    endingStarts = listOf("rewind"),
                ),
                // "Go back, go back": the leading "go" just reaches tracking as
                // one more word; "back [go] back" is the part that never occurs
                // in narration. "back" may still grow into "backwards".
                CommandGrammar(
                    hint = "Go back, go back",
                    prefixes = setOf("back"),
                    fillers = setOf("go"),
                    endings = Regex("back"),
                    canonicalPrefix = "back",
                    canonicalEndings = setOf("back"),
                    canonicalFiller = "go",
                    endingStarts = listOf("back"),
                    endingMayGrowIntoNarration = true,
                    // Measured: the first "go" is often misheard ("boe back, go
                    // back", "still back, go back"), so "back go back" fires on
                    // its own unless a phrasal verb owns the first "back"
                    // ("come back, go back"). Bare "back back" needs "go" first.
                    leadIns = setOf("go"),
                    vetoBefore = setOf(
                        "come", "came", "comes", "coming", "turn", "turned", "turning", "look",
                        "looked", "looking", "step", "stepped", "get", "got", "getting", "bring",
                        "brought", "call", "called", "give", "gave", "hold", "held", "fight",
                        "fought", "sit", "sat", "lie", "lay", "pay", "paid", "cut", "set", "put",
                        "think", "thought", "write", "wrote", "talk", "talked", "fall", "fell",
                        "run", "ran", "way", "pull", "pulled", "push", "pushed", "hit", "move",
                        "moved", "send", "sent", "head", "headed", "lean", "leaned",
                    ),
                ),
            )
        }
    }
}

/**
 * Page-back commands spoken during narration, detected in the same word stream
 * the [Matcher] consumes. Each language accepts every phrase of
 * [CommandGrammar.forLang].
 *
 * Tuned on real decoder output (docs/follow-voice-commands.md):
 *  - **Tolerant phrase**: each [CommandGrammar] accepts the forms the model
 *    really emits for its phrase. A weak prefix ("comment") only fires once
 *    its ending is stable, because "comment recul…" can still grow into
 *    "comment reculer", ordinary French.
 *  - **Fires on the partial**: a strong prefix fires the moment its ending
 *    appears, even as the still-growing last word. Greedy transducer output
 *    only ever extends, and every growth of "commande recul…" is still the
 *    command. Waiting for the next word or the endpoint cost a full 1.28 s
 *    decoder chunk (or 0.7 s of silence plus a chunk). Phrases whose ending can
 *    grow into narration opt out ([CommandGrammar.endingMayGrowIntoNarration]).
 *  - **Spans one endpoint**: "Commande… recul" with a pause lands in two
 *    utterances. A final that ends on a prefix arms the next utterance, whose
 *    first word (within [CROSS_UTTERANCE_SEC]) may complete it.
 *  - **Script guard**: if the visible script text itself contains a variant
 *    (e.g. "comment de recul"), only exact canonical phrases fire while it's on
 *    screen, so reading the script can't page back.
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

    private val grammars = CommandGrammar.forLang(lang)

    /** Every accepted phrase, primary first, for UI labels. */
    val hints: List<String> = grammars.map { it.hint }
    val hint: String = hints.first()

    /** Script token positions where a (non-canonical) command form is printed. */
    private val scriptHits: IntArray = scanScript(scriptTokens)

    private var utterance = -1
    /** Words of [utterance] before this index are consumed (fired or pre-fire). */
    private var firedThrough = 0
    /** Utterance whose final ended on a bare prefix, waiting for its ending. */
    private var pendingUtterance = Int.MIN_VALUE
    private var pendingGrammar: CommandGrammar? = null
    private var pendingStrong = false
    private var pendingNeedsFiller = false

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
        val pg = pendingGrammar
        if (pg != null && !strict && pendingUtterance == id - 1 && firedThrough == 0 && toks.isNotEmpty()) {
            var j = 0
            if (toks[j].text in pg.fillers && toks.size > 1) j++
            val end = toks[j]
            if ((j > 0 || !pendingNeedsFiller) &&
                pg.ending(end.text, pendingStrong) && words[end.word].timeSec <= CROSS_UTTERANCE_SEC &&
                (pg.firesUnstable(pendingStrong) || stable(end.word))
            ) {
                pagesBack++
                firedThrough = end.word + 1
                pendingUtterance = Int.MIN_VALUE
            }
        }

        var i = 0
        scan@ while (i < toks.size) {
            val t = toks[i]
            if (t.word < firedThrough) {
                i++
                continue
            }
            for (g in grammars) {
                val strong = t.text in g.prefixes
                if (!strong && t.text !in g.weakPrefixes) continue
                var j = i + 1
                val filler = j < toks.size && toks[j].text in g.fillers
                if (filler) j++
                if (j >= toks.size) continue
                if (!g.leadInOk(toks.getOrNull(i - 1)?.text, filler)) continue
                val end = toks[j]
                val canonical = g.canonical(t.text, if (filler) toks[i + 1].text else null, end.text)
                if (g.ending(end.text, strong) && (g.firesUnstable(strong) || stable(end.word)) &&
                    (!strict || canonical)
                ) {
                    pagesBack++
                    firedThrough = end.word + 1
                    i = j + 1
                    continue@scan
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
        for (g in grammars) {
            if (!g.spansEndpoint) continue
            var k = toks.lastIndex
            val endsOnFiller = toks[k].text in g.fillers && k > 0
            if (endsOnFiller) k--
            val t = toks[k]
            if (t.word < firedThrough) continue
            val before = toks.getOrNull(k - 1)?.text
            if (!g.leadInOk(before, filler = true)) continue
            val strong = t.text in g.prefixes
            if (strong || t.text in g.weakPrefixes) {
                pendingUtterance = id
                pendingGrammar = g
                pendingStrong = strong
                // Without a filler yet, the next utterance must supply one
                // unless the lead-in is already satisfied.
                pendingNeedsFiller = !endsOnFiller && !g.leadInOk(before, filler = false)
                return
            }
        }
    }

    /**
     * First word index held back from tracking in a partial: a trailing
     * possible-command (prefix, filler, an ending still growing or not yet
     * stable), a growing word that may still become a prefix ("com…"), or the
     * head of an utterance armed by [armNextUtterance]. Everything before it is
     * safe narration.
     */
    private fun holdFrom(id: Int, toks: List<Tok>, wordCount: Int): Int {
        var hold = wordCount
        val n = toks.size
        for (g in grammars) {
            if (n > 0 && toks[n - 1].word >= firedThrough && toks[n - 1].word == wordCount - 1) {
                val last = toks[n - 1].text
                if (last.length >= 3 && g.allPrefixes.any { it.startsWith(last) }) {
                    hold = minOf(hold, toks[n - 1].word)
                }
            }
            for (start in maxOf(0, n - 3) until n) {
                val t = toks[start]
                if (t.word < firedThrough) continue
                if (t.text !in g.allPrefixes) continue
                if (!g.leadInOk(toks.getOrNull(start - 1)?.text, filler = true)) continue
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
        }
        val pg = pendingGrammar
        if (pg != null && pendingUtterance == id - 1 && firedThrough == 0 && n in 1..2) {
            val first = toks[0].text
            val head = first in pg.fillers || pg.maybeEnding(first) || pg.ending(first, pendingStrong)
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
        val splits = grammars.mapNotNull { it.splitEnding }
        if (splits.isEmpty()) return raw
        val out = ArrayList<Tok>(raw.size)
        var i = 0
        while (i < raw.size) {
            val next = raw.getOrNull(i + 1)
            if (next != null && splits.any { (first, rest) -> raw[i].text == first && rest.matches(next.text) }) {
                out += Tok(raw[i].text + next.text, next.word)
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
            for (g in grammars) {
                val strong = p in g.prefixes
                if (!strong && p !in g.weakPrefixes) continue
                var j = i + 1
                val filler = j < toks.size && toks[j].text in g.fillers
                if (filler) j++
                if (j >= toks.size || !g.ending(toks[j].text, strong)) continue
                if (!g.leadInOk(toks.getOrNull(i - 1)?.text, filler)) continue
                val canonical = g.canonical(p, if (filler) toks[i + 1].text else null, toks[j].text)
                if (!canonical) hits += toks[i].word
            }
        }
        return hits.toIntArray()
    }

    companion object {
        /** Latest utterance-relative start for the ending that completes an armed prefix. */
        const val CROSS_UTTERANCE_SEC = 2.0f
    }
}
