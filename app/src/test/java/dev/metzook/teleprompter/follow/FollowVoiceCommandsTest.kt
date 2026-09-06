package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowVoiceCommandsTest {
    private fun words(text: String) = text.split(' ').mapIndexed { i, word ->
        TimedWord(word, i * 0.3f)
    }

    @Test
    fun completePhrasesFireInBothLanguages() {
        val en = FollowVoiceCommands(MatchLang.EN)
        val fr = FollowVoiceCommands(MatchLang.FR)
        assertEquals(1, en.onWords(0, words("COMMAND, REWIND!"), true).pagesBack)
        assertEquals(1, fr.onWords(0, words("Commande recul"), true).pagesBack)
        assertEquals(1, fr.onWords(1, words("commande recule"), true).pagesBack)
        assertEquals("Command rewind", en.hint)
        assertEquals("Commande recul", fr.hint)
    }

    @Test
    fun ordinaryNarrationAndNearMissesDoNotFire() {
        val cases = mapOf(
            MatchLang.EN to listOf("rewind", "go back", "page up", "command remind",
                "commands rewind", "command rewinding", "command please rewind", "come and rewind"),
            MatchLang.FR to listOf("rembobine", "recule", "page precedente", "commande retour",
                "commande recue", "commande reculer", "commande de recul", "comme un recul"),
        )
        for ((lang, phrases) in cases) {
            val detector = FollowVoiceCommands(lang)
            phrases.forEachIndexed { id, text ->
                val input = words(text)
                val result = detector.onWords(id, input, true)
                assertEquals(text, 0, result.pagesBack)
                assertEquals(text, input, result.words)
            }
        }
    }

    @Test
    fun growingPartialWaitsForAWordBoundaryOrFinal() {
        val d = FollowVoiceCommands(MatchLang.EN)
        for (text in listOf("command", "command re", "command rewind")) {
            val result = d.onWords(0, words(text), false)
            assertEquals(0, result.pagesBack)
            assertTrue(result.words.isEmpty())
        }
        assertEquals(1, d.onWords(0, words("command rewind"), true).pagesBack)
        assertEquals(0, d.onWords(0, words("command rewind"), true).pagesBack)
    }

    @Test
    fun provisionalRewindThatGrowsIntoRewindingNeverFires() {
        val d = FollowVoiceCommands(MatchLang.EN)
        assertEquals(0, d.onWords(0, words("command rewind"), false).pagesBack)
        val input = words("command rewinding the tape")
        val result = d.onWords(0, input, true)
        assertEquals(0, result.pagesBack)
        assertEquals(input, result.words)
    }

    @Test
    fun unrelatedContinuationReleasesHeldNarration() {
        val d = FollowVoiceCommands(MatchLang.EN)
        val matcher = Matcher(ScriptIndex.build(listOf(SpokenSegment("command respect today", true)), MatchLang.EN))
        matcher.onWords(0, d.onWords(0, words("command re"), false).words, false)
        assertEquals(-1, matcher.state.anchor)
        val result = d.onWords(0, words("command respect today"), true)
        assertEquals(0, result.pagesBack)
        assertEquals(2, matcher.onWords(0, result.words, true).anchor)
    }

    @Test
    fun repeatedPartialsAndFinalDoNotRepeatJump() {
        val d = FollowVoiceCommands(MatchLang.EN)
        val text = "old narration command rewind new"
        assertEquals(1, d.onWords(0, words(text), false).pagesBack)
        assertEquals(0, d.onWords(0, words(text), false).pagesBack)
        assertEquals(0, d.onWords(0, words("$text reading"), true).pagesBack)
        assertEquals(1, d.onWords(1, words("command rewind"), true).pagesBack)
    }

    @Test
    fun consecutiveCommandsEachJumpOnePage() {
        val d = FollowVoiceCommands(MatchLang.FR)
        assertEquals(2, d.onWords(0, words("commande recul commande recule"), true).pagesBack)
        assertEquals(0, d.onWords(0, words("commande recul commande recule"), true).pagesBack)
        assertEquals(1, d.onWords(0, words("commande recul commande recule commande recul"), true).pagesBack)
    }

    @Test
    fun commandDoesNotSpanSeparateUtterances() {
        val d = FollowVoiceCommands(MatchLang.EN)
        assertEquals(0, d.onWords(0, words("command"), true).pagesBack)
        assertEquals(0, d.onWords(1, words("rewind"), true).pagesBack)
    }

    @Test
    fun shrinkingPartialsCannotReplaySpeechBeforeRetake() {
        val d = FollowVoiceCommands(MatchLang.EN)
        assertEquals(1, d.onWords(0, words("old words command rewind"), true).pagesBack)
        val shrunk = d.onWords(0, words("old words"), false)
        assertTrue(shrunk.words.all { it.text.isEmpty() })
        val restored = d.onWords(0, words("old words command rewind now"), true)
        assertEquals(0, restored.pagesBack)
        assertEquals(listOf("", "", "", "", "now"), restored.words.map { it.text })
    }

    @Test
    fun retakeDiscardsOldSpeechAndFollowsNewReadingInSameUtterance() {
        val script = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
        val index = ScriptIndex.build(listOf(SpokenSegment(script, true)), MatchLang.EN)
        val matcher = Matcher(index)
        val d = FollowVoiceCommands(MatchLang.EN)
        matcher.reanchor(8)
        val first = d.onWords(0, words("juliet kilo lima command rewind"), false)
        matcher.onWords(0, first.words, false)
        val command = d.onWords(0, words("juliet kilo lima command rewind"), true)
        assertEquals(1, command.pagesBack)
        matcher.reanchor(0) // The UI reanchors to the earlier screen before feeding.
        assertEquals(0, matcher.onWords(0, command.words, true).anchor)
        val resumed = d.onWords(0, words("juliet kilo lima command rewind bravo charlie delta"), true)
        assertEquals(0, resumed.pagesBack)
        assertEquals(3, matcher.onWords(0, resumed.words, true).anchor)
        assertEquals(FollowMode.FOLLOWING, matcher.state.mode)
    }

    @Test
    fun commandWordsNeverProvisionallyAdvanceMatchingScript() {
        val matcher = Matcher(ScriptIndex.build(listOf(SpokenSegment("command rewind finish", true)), MatchLang.EN))
        val d = FollowVoiceCommands(MatchLang.EN)
        for (text in listOf("com", "command", "command re", "command rewind")) {
            val routed = d.onWords(0, words(text), false)
            assertEquals(-1, matcher.onWords(0, routed.words, false).anchor)
        }
        val routed = d.onWords(0, words("command rewind"), true)
        assertEquals(1, routed.pagesBack)
        assertEquals(-1, matcher.onWords(0, routed.words, true).anchor)
    }

    @Test
    fun blankedWordsKeepTimestampsAndTraceReplayCompatible() {
        val routed = FollowVoiceCommands(MatchLang.EN)
            .onWords(0, words("command rewind alpha"), false)
        assertEquals(listOf(0f, 0.3f, 0.6f), routed.words.map { it.timeSec })
        val event = FollowTrace.Event.Words(0f, 0, false, routed.words)
        assertEquals(event, FollowTrace.decode(FollowTrace.encode(event)))
    }
}
