package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowVoiceCommandsTest {
    private fun words(text: String, start: Float = 0f) = text.split(' ').mapIndexed { i, word ->
        TimedWord(word, start + i * 0.3f)
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

    /** Forms kroko-fr really produced for spoken "commande recul" (tools/voice-command-bench). */
    @Test
    fun frenchDecoderVariantsFire() {
        val heard = listOf(
            "Comment de recul", "Comment te recule ?", "Comment trecule ?", "Comment recul",
            "La commande recule", "Je commande recul", "commandes recul", "commande reculent",
            "Commande cul.", "commande de recul", "commande reculer", "commande re cul",
            "nous, commande. Recul. Alors reprenons",
        )
        heard.forEachIndexed { id, text ->
            assertEquals(text, 1, FollowVoiceCommands(MatchLang.FR).onWords(id, words(text), true).pagesBack)
        }
    }

    /** Forms kroko-en really produced for spoken "command rewind". */
    @Test
    fun englishDecoderVariantsFire() {
        listOf("A man rewind", "Amand rewind", "demand rewind", "mand rewind", "command rewinds")
            .forEachIndexed { id, text ->
                assertEquals(text, 1, FollowVoiceCommands(MatchLang.EN).onWords(id, words(text), true).pagesBack)
            }
        val d = FollowVoiceCommands(MatchLang.EN)
        listOf("a man rewinds the tape", "the man who rewound it", "they demand rewinding")
            .forEachIndexed { id, text -> assertEquals(text, 0, d.onWords(id, words(text), true).pagesBack) }
    }

    @Test
    fun everyLanguageOffersADoubledPhraseToo() {
        assertEquals(listOf("Commande recul", "Retour retour"), FollowVoiceCommands(MatchLang.FR).hints)
        assertEquals(listOf("Command rewind", "Go back, go back"), FollowVoiceCommands(MatchLang.EN).hints)
        val fr = FollowVoiceCommands(MatchLang.FR)
        val en = FollowVoiceCommands(MatchLang.EN)
        listOf(
            "Retour retour", "Retour, retour !", "retours retour", "retour autour", "votre tour retour",
            "photo retour", "retour au retour",
        ).forEachIndexed { id, text ->
            assertEquals(text, 1, fr.onWords(id, words(text), true).pagesBack)
        }
        listOf("Go back, go back!", "go back go back", "Go back, back.", "Boe back, go back").forEachIndexed { id, text ->
            assertEquals(text, 1, en.onWords(id, words(text), true).pagesBack)
        }
    }

    /** "retour…" / "back…" may still grow into narration ("retournez", "backwards"). */
    @Test
    fun doubledPhrasesWaitForAStableEnding() {
        val fr = FollowVoiceCommands(MatchLang.FR)
        val held = fr.onWords(0, words("alors retour retour"), false)
        assertEquals(0, held.pagesBack)
        assertEquals(listOf("alors"), held.words.map { it.text })
        assertEquals(1, fr.onWords(0, words("alors retour retour je"), false).pagesBack)

        val en = FollowVoiceCommands(MatchLang.EN)
        assertEquals(0, en.onWords(0, words("go back go back"), false).pagesBack)
        assertEquals(1, en.onWords(0, words("go back go back so"), false).pagesBack)
    }

    @Test
    fun narrationAroundTheDoubledPhrasesDoesNotFire() {
        val fr = FollowVoiceCommands(MatchLang.FR)
        listOf(
            "Au retour, retournez la carte", "Le retour du héros, c'est le retour de l'espoir",
            "un aller-retour à Lyon", "retour à la case départ", "au retour du voyage",
            "faites le tour du lac", "autour de la table", "une photo du retour",
        ).forEachIndexed { id, text -> assertEquals(text, 0, fr.onWords(id, words(text), true).pagesBack) }
        val en = FollowVoiceCommands(MatchLang.EN)
        listOf(
            "go back and forth", "go back, go backwards", "we had back to back meetings",
            "back and forth, back and forth", "come back, go back", "turn back, back off", "back, back",
            "look back, go back to sleep",
        ).forEachIndexed { id, text -> assertEquals(text, 0, en.onWords(id, words(text), true).pagesBack) }
    }

    @Test
    fun ordinaryNarrationAndNearMissesDoNotFire() {
        val cases = mapOf(
            MatchLang.EN to listOf("rewind", "go back", "page up", "command remind",
                "command please rewind", "come and rewind", "commander rewound"),
            MatchLang.FR to listOf("rembobine", "recule", "page precedente", "commande retour",
                "commande recue", "la commande reçue était incomplète", "comme un recul",
                "Comment reculer sans perdre la face ?", "Avec le recul, je pense que non",
                "Comment le recul change notre façon de voir", "Tout le monde recule",
                "Comment de temps faut-il", "On ne recule devant rien", "comment reculez-vous"),
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
    fun strongCommandFiresOnThePartialWithoutWaitingForABoundary() {
        val d = FollowVoiceCommands(MatchLang.EN)
        for (text in listOf("com", "command", "command re")) {
            val result = d.onWords(0, words(text), false)
            assertEquals(text, 0, result.pagesBack)
            assertTrue(text, result.words.isEmpty())
        }
        assertEquals(1, d.onWords(0, words("command rewind"), false).pagesBack)
        assertEquals(0, d.onWords(0, words("command rewind"), true).pagesBack)
    }

    @Test
    fun frenchCommandFiresOnTheVeryPartialThatCompletesIt() {
        val d = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, d.onWords(0, words("et voilà commande"), false).pagesBack)
        assertEquals(1, d.onWords(0, words("et voilà commande recul"), false).pagesBack)
    }

    @Test
    fun anEndingThatKeepsGrowingStillCountsOnce() {
        val d = FollowVoiceCommands(MatchLang.EN)
        assertEquals(1, d.onWords(0, words("command rewind"), false).pagesBack)
        val result = d.onWords(0, words("command rewinding the tape"), true)
        assertEquals(0, result.pagesBack)
        assertEquals(listOf("", "", "the", "tape"), result.words.map { it.text })
    }

    @Test
    fun weakPrefixWaitsUntilItsEndingIsStable() {
        val d = FollowVoiceCommands(MatchLang.FR)
        // "comment recul…" may still grow into "comment reculer": hold, don't fire.
        val held = d.onWords(0, words("alors comment recul"), false)
        assertEquals(0, held.pagesBack)
        assertEquals(listOf("alors"), held.words.map { it.text })
        val narration = d.onWords(0, words("alors comment reculer sans"), false)
        assertEquals(0, narration.pagesBack)
        assertEquals(listOf("alors", "comment", "reculer", "sans"), narration.words.map { it.text })

        val e = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, e.onWords(0, words("comment de recul"), false).pagesBack)
        assertEquals(1, e.onWords(0, words("comment de recul alors"), false).pagesBack)
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

    /** "Commande… recul" with a pause: the endpoint lands between the two words. */
    @Test
    fun commandMaySpanOneEndpoint() {
        val d = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, d.onWords(0, words("tout a basculé pour nous commande"), true).pagesBack)
        val head = d.onWords(1, words("re", 0.4f), false)
        assertEquals(0, head.pagesBack)
        assertTrue("possible ending is held", head.words.isEmpty())
        val done = d.onWords(1, words("recul alors", 0.4f), false)
        assertEquals(1, done.pagesBack)
        assertEquals(listOf("", "alors"), done.words.map { it.text })
        assertEquals(0, d.onWords(1, words("recul alors reprenons", 0.4f), true).pagesBack)

        val en = FollowVoiceCommands(MatchLang.EN)
        assertEquals(0, en.onWords(0, words("command"), true).pagesBack)
        assertEquals(1, en.onWords(1, words("rewind"), true).pagesBack)
    }

    @Test
    fun weakPrefixSpanningAnEndpointWaitsForAStableEnding() {
        val d = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, d.onWords(0, words("pour nous comment"), true).pagesBack)
        val held = d.onWords(1, words("recule", 0.5f), false)
        assertEquals(0, held.pagesBack)
        assertTrue(held.words.isEmpty())
        assertEquals(1, d.onWords(1, words("recule alors", 0.5f), false).pagesBack)
    }

    @Test
    fun endpointSpanIsBoundedInTimeAndToTheNextUtterance() {
        val late = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, late.onWords(0, words("commande"), true).pagesBack)
        assertEquals(0, late.onWords(1, words("recul", 2.5f), true).pagesBack)

        val skipped = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, skipped.onWords(0, words("commande"), true).pagesBack)
        assertEquals(0, skipped.onWords(1, words("alors"), true).pagesBack)
        assertEquals(0, skipped.onWords(2, words("recul"), true).pagesBack)

        val notFirst = FollowVoiceCommands(MatchLang.FR)
        assertEquals(0, notFirst.onWords(0, words("commande"), true).pagesBack)
        assertEquals(0, notFirst.onWords(1, words("avec le recul"), true).pagesBack)
    }

    @Test
    fun scriptThatPrintsAVariantOnlyAcceptsTheExactPhraseWhileVisible() {
        val script = "il se demande comment de recul on a besoin puis on continue la lecture"
        val index = ScriptIndex.build(listOf(SpokenSegment(script, true)), MatchLang.FR)
        val hit = index.tokens.indexOfFirst { it.norm == "comment" }
        val onScreen = (hit - 3)..(hit + 6)
        val offScreen = (hit + 7)..(hit + 9)

        val d = FollowVoiceCommands(index)
        assertEquals(0, d.onWords(0, words("comment de recul on"), true, onScreen).pagesBack)
        assertEquals(1, d.onWords(1, words("commande recul"), true, onScreen).pagesBack)
        assertEquals(1, d.onWords(2, words("comment de recul"), true, offScreen).pagesBack)
        assertEquals(1, d.onWords(3, words("comment de recul"), true, null).pagesBack)
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
        val first = d.onWords(0, words("juliet kilo lima command"), false)
        matcher.onWords(0, first.words, false)
        val command = d.onWords(0, words("juliet kilo lima command rewind"), false)
        assertEquals(1, command.pagesBack)
        matcher.reanchor(0) // The UI reanchors to the earlier screen before feeding.
        assertEquals(0, matcher.onWords(0, command.words, false).anchor)
        val resumed = d.onWords(0, words("juliet kilo lima command rewind bravo charlie delta"), true)
        assertEquals(0, resumed.pagesBack)
        assertEquals(3, matcher.onWords(0, resumed.words, true).anchor)
        assertEquals(FollowMode.FOLLOWING, matcher.state.mode)
    }

    @Test
    fun commandWordsNeverProvisionallyAdvanceMatchingScript() {
        val matcher = Matcher(ScriptIndex.build(listOf(SpokenSegment("command rewind finish", true)), MatchLang.EN))
        val d = FollowVoiceCommands(MatchLang.EN)
        var pages = 0
        for (text in listOf("com", "command", "command re", "command rewind")) {
            val routed = d.onWords(0, words(text), false)
            pages += routed.pagesBack
            assertEquals(-1, matcher.onWords(0, routed.words, false).anchor)
        }
        assertEquals(1, pages)
        val routed = d.onWords(0, words("command rewind"), true)
        assertEquals(0, routed.pagesBack)
        assertEquals(-1, matcher.onWords(0, routed.words, true).anchor)
    }

    @Test
    fun blankedWordsKeepTimestampsAndTraceReplayCompatible() {
        val routed = FollowVoiceCommands(MatchLang.EN)
            .onWords(0, words("command rewind alpha"), false)
        assertEquals(1, routed.pagesBack)
        assertEquals(listOf("", "", "alpha"), routed.words.map { it.text })
        assertEquals(listOf(0f, 0.3f, 0.6f), routed.words.map { it.timeSec })
        val event = FollowTrace.Event.Words(0f, 0, false, routed.words)
        assertEquals(event, FollowTrace.decode(FollowTrace.encode(event)))
    }
}
