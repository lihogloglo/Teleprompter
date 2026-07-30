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
                val start = end - phrase.size
                // A word that already fired one command can't count toward another.
                if (start < firedThrough) continue
                if ((start until end).all { tokenMatches(tokens[it], phrase[it - start]) }) {
                    fired += cmd
                    firedThrough = end
                    break
                }
            }
        }
        return fired
    }

    private fun tokenMatches(spoken: String, expected: String): Boolean =
        if (expected.length >= FUZZY_LEN) Fuzzy.matches(expected, spoken)
        else spoken == expected

    companion object {
        /** Fuzzy only for long command tokens: at this length [Fuzzy] allows two
         *  edits, and no short everyday word can drift into range. */
        private const val FUZZY_LEN = 8

        fun defaultPhrases(lang: MatchLang): Map<VoiceCommand, List<String>> = when (lang) {
            // One rare, distinctive word per language ("rewind"): never said by
            // accident, hard for the decoder to confuse with anything else.
            MatchLang.FR -> mapOf(VoiceCommand.GO_BACK to listOf("rembobine"))
            MatchLang.EN -> mapOf(VoiceCommand.GO_BACK to listOf("rewind"))
        }
    }
}
