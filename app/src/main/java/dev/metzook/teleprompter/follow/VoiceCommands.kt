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
 *  - Phrases and speech are both normalized through [TextNorm], so casing,
 *    punctuation and diacritics never matter.
 *  - Each occurrence fires exactly once: growing partials repeat the whole
 *    utterance every event, so fired positions are remembered per utterance.
 *  - The still-growing last partial word takes part: for a command, latency
 *    beats the rare word that grows past a command token ("back" → "backs").
 *  - No wake word: a phrase ≥ 2 tokens keeps coincidences rare, but a script
 *    that literally contains the phrase will trigger it — the status overlay
 *    always shows the active phrase for this reason.
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
                if ((start until end).all { tokens[it] == phrase[it - start] }) {
                    fired += cmd
                    firedThrough = end
                    break
                }
            }
        }
        return fired
    }

    companion object {
        fun defaultPhrases(lang: MatchLang): Map<VoiceCommand, List<String>> = when (lang) {
            MatchLang.FR -> mapOf(
                VoiceCommand.GO_BACK to listOf("retour arrière", "reviens en arrière"),
            )
            MatchLang.EN -> mapOf(
                VoiceCommand.GO_BACK to listOf("go back", "jump back"),
            )
        }
    }
}
