package dev.metzook.teleprompter.follow

/** A spoken control command (SPEC §5.3) — voice control without follow mode. */
enum class VoiceCommand {
    GO_BACK,
}

/**
 * Detects short command phrases ("go back") in the same word stream the
 * [Matcher] consumes, so voice commands ride the existing ASR pipeline for
 * free. Pure Kotlin, unit-tested with the transcript-replay idiom.
 *
 * Detection rules:
 *  - Command words are single, rare, phonetically distinctive words — multi-word
 *    phrases ("retour arrière") decode unreliably, and common words collide
 *    with script text. Long command tokens (≥ [FUZZY_LEN] chars) match fuzzily
 *    so inflections and decoder slips still fire ("rembobiner", "rembobinez");
 *    shorter ones must match exactly.
 *  - A rare word is exactly what a decoder fragments: "rembobine" is not in the
 *    model's vocabulary, so it often comes out as several small words
 *    ("ram bobine"). Each phrase token may therefore consume up to [MAX_JOIN]
 *    adjacent spoken tokens, joined, before the comparison — which also means
 *    neighbouring script words can concatenate into a near-miss, slightly
 *    widening the no-wake-word caveat below.
 *  - Phrases and speech are both normalized through [TextNorm], so casing,
 *    punctuation and diacritics never matter.
 *  - Each occurrence fires exactly once: growing partials repeat the whole
 *    utterance every event, so fired positions are remembered per utterance.
 *  - The still-growing last partial word takes part: for a command, latency
 *    beats the rare word that grows past a command token.
 *  - No wake word: a script that literally contains the command word will
 *    trigger it — the status overlay always shows the active word for this
 *    reason.
 */
class VoiceCommandDetector(
    private val lang: MatchLang,
    private val phrases: Map<VoiceCommand, List<String>> = defaultPhrases(lang),
) {
    /** The primary phrase, for UI labels ("say “go back”"). */
    val hint: String = phrases[VoiceCommand.GO_BACK]?.firstOrNull().orEmpty()

    private val compiled: List<Pair<VoiceCommand, List<String>>> =
        phrases.flatMap { (cmd, variants) ->
            variants.map { v -> cmd to v.split(' ').flatMap { TextNorm.matchTokens(it, lang) } }
        }.filter { it.second.isNotEmpty() }

    private var currentUtterance = -1
    private var firedThrough = 0

    /**
     * Feed one ASR event (the utterance's words so far). Returns the commands
     * newly completed by this event, oldest first — usually empty or one.
     */
    fun onWords(utterance: Int, words: List<TimedWord>): List<VoiceCommand> {
        if (utterance != currentUtterance) {
            currentUtterance = utterance
            firedThrough = 0
        }
        if (words.isEmpty()) return emptyList()
        val tokens = words.flatMap { TextNorm.matchTokens(it.text, lang) }
        val fired = ArrayList<VoiceCommand>(1)
        for (end in (firedThrough + 1)..tokens.size) {
            for ((cmd, phrase) in compiled) {
                // A word that already fired one command can't count toward
                // another (matchStart returns -1 on no match).
                if (matchStart(tokens, end, phrase) < firedThrough) continue
                fired += cmd
                firedThrough = end
                break
            }
        }
        return fired
    }

    /**
     * Matches [phrase] right-aligned so its last token ends at [end], letting
     * each phrase token consume 1..[MAX_JOIN] adjacent spoken tokens joined
     * together ("ram" + "bobine" → "rambobine"). Returns the start index of
     * the consumed span, or -1 if the phrase doesn't match here.
     */
    private fun matchStart(tokens: List<String>, end: Int, phrase: List<String>): Int {
        var pos = end
        for (i in phrase.indices.reversed()) {
            val expected = phrase[i]
            // A join longer than the target plus its edit budget can't match.
            val slack = if (expected.length >= FUZZY_LEN) 2 else 0
            var joined = ""
            var take = 0
            while (true) {
                if (take == MAX_JOIN || pos - take - 1 < 0) return -1
                joined = tokens[pos - take - 1] + joined
                take++
                if (tokenMatches(joined, expected)) break
                if (joined.length > expected.length + slack) return -1
            }
            pos -= take
        }
        return pos
    }

    private fun tokenMatches(spoken: String, expected: String): Boolean =
        if (expected.length >= FUZZY_LEN) Fuzzy.matches(expected, spoken)
        else spoken == expected

    companion object {
        /** Fuzzy only for long command tokens: at this length [Fuzzy] allows two
         *  edits, and no short everyday word can drift into range. */
        private const val FUZZY_LEN = 8

        /** Most fragments a decoder realistically splits one command word into. */
        private const val MAX_JOIN = 3

        fun defaultPhrases(lang: MatchLang): Map<VoiceCommand, List<String>> = when (lang) {
            // One rare, distinctive word per language ("rewind"): never said by
            // accident, hard for the decoder to confuse with anything else.
            MatchLang.FR -> mapOf(VoiceCommand.GO_BACK to listOf("rembobine"))
            MatchLang.EN -> mapOf(VoiceCommand.GO_BACK to listOf("rewind"))
        }
    }
}
