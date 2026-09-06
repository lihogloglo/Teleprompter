package dev.metzook.teleprompter.follow

/** Speech routed to script tracking, with newly confirmed page-back commands. */
data class FollowVoiceInput(val words: List<TimedWord>, val pagesBack: Int)

/**
 * Strict commands for use during narration. Unlike the standalone command mode,
 * both words must match exactly; no fuzzy matching or fragment concatenation.
 * French "recul" and "recule" are accepted spellings of the same spoken sound.
 *
 * Like Matcher, commits all but the growing last partial word (or all final
 * words). A possible command suffix is held out of tracking until resolved.
 * Confirmed commands and earlier speech are blanked, preserving ASR word indices
 * so a cumulative partial cannot replay pre-retake speech at the new position.
 * Phrases must be spoken together within one recognizer utterance.
 */
class FollowVoiceCommands(private val lang: MatchLang) {
    val hint: String = if (lang == MatchLang.FR) "Commande recul" else "Command rewind"
    private val prefix = if (lang == MatchLang.FR) "commande" else "command"
    private val endings = if (lang == MatchLang.FR) setOf("recul", "recule") else setOf("rewind")
    private var utterance = -1
    private var firedThrough = 0

    fun onWords(id: Int, words: List<TimedWord>, final: Boolean): FollowVoiceInput {
        if (id != utterance) {
            utterance = id
            firedThrough = 0
        }
        val norms = words.map { TextNorm.matchTokens(it.text, lang).singleOrNull().orEmpty() }
        val stable = if (final) words.size else (words.size - 1).coerceAtLeast(0)
        var pagesBack = 0
        var i = firedThrough
        while (i + 1 < stable) {
            if (norms[i] == prefix && norms[i + 1] in endings) {
                pagesBack++
                firedThrough = i + 2
                i += 2
            } else {
                i++
            }
        }

        var limit = words.size
        if (!final && words.isNotEmpty()) {
            val last = words.lastIndex
            // Don't let a partial "command rew..." enter the script matcher.
            if (last - 1 >= firedThrough && norms[last - 1] == prefix &&
                endings.any { it.startsWith(norms[last]) }
            ) {
                limit = last - 1
            } else if (last >= firedThrough && norms[last].isNotEmpty() &&
                prefix.startsWith(norms[last])
            ) {
                limit = last
            }
        }
        val filtered = words.take(limit).mapIndexed { index, word ->
            if (index < firedThrough) word.copy(text = "") else word
        }
        return FollowVoiceInput(filtered, pagesBack)
    }
}
