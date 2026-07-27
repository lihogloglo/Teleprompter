package dev.metzook.teleprompter.follow

import dev.metzook.teleprompter.vault.MarkdownParser
import dev.metzook.teleprompter.vault.toDisplaySegments

/**
 * Transcript-replay harness (SPEC §3.2 testability requirement): drives a
 * Matcher the same way AsrEngine does at runtime — growing partials per
 * utterance, then a final at the endpoint.
 */
object Replay {

    fun indexOf(markdown: String, lang: MatchLang = MatchLang.EN): ScriptIndex =
        ScriptIndex.build(
            MarkdownParser.parse(markdown).toDisplaySegments().map {
                SpokenSegment(it.text, it.spoken)
            },
            lang,
        )

    /** Timed words from plain text at a steady speaking rate. */
    fun wordsOf(text: String, startSec: Float = 0f, wps: Float = 2.5f): List<TimedWord> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .mapIndexed { i, w -> TimedWord(w, startSec + i / wps) }

    /** Feed one utterance like the engine: 1-word, 2-word, … partials, then final. */
    fun feedUtterance(matcher: Matcher, utterance: Int, words: List<TimedWord>): FollowState {
        for (n in 1..words.size) matcher.onWords(utterance, words.take(n), final = false)
        return matcher.onWords(utterance, words, final = true)
    }

    /** Feed several utterances of plain text, restarting timestamps per utterance. */
    fun feed(matcher: Matcher, vararg utterances: String): FollowState {
        var state = matcher.state
        utterances.forEachIndexed { i, text ->
            state = feedUtterance(matcher, i, wordsOf(text))
        }
        return state
    }

    /** Replace every [every]-th word to simulate ASR misrecognitions. */
    fun corrupt(text: String, every: Int, replacement: String = "blorp"): String =
        text.trim().split(Regex("\\s+"))
            .mapIndexed { i, w -> if (i % every == every - 1) replacement else w }
            .joinToString(" ")
}
