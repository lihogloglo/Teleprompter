package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    private fun words(vararg ws: String): List<TimedWord> =
        ws.mapIndexed { i, w -> TimedWord(w, i * 0.3f) }

    @Test
    fun firesOnCompletedPhrase() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(0, words("please", "go", "back")),
        )
    }

    @Test
    fun normalizesCaseAndPunctuation() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(0, words("Go", "BACK!")),
        )
    }

    @Test
    fun firesOncePerOccurrenceAcrossGrowingPartials() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("go")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("go", "back")))
        // The partial repeats the utterance so far: the same occurrence must not re-fire.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("go", "back")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("go", "back", "now")))
    }

    @Test
    fun firesAgainOnASecondOccurrence() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("go", "back")))
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(0, words("go", "back", "go", "back")),
        )
    }

    @Test
    fun bothOccurrencesInOneEvent() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(
            listOf(VoiceCommand.GO_BACK, VoiceCommand.GO_BACK),
            d.onWords(0, words("go", "back", "uh", "go", "back")),
        )
    }

    @Test
    fun ignoresEmbeddedAndPartialWords() {
        val d = VoiceCommandDetector(MatchLang.EN)
        // "backpack" is not "back"; "go" alone is not the phrase.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("go", "backpack")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("lets", "go", "home")))
    }

    @Test
    fun alternatePhraseFires() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("jump", "back")))
    }

    @Test
    fun frenchPhraseWithDiacritics() {
        val d = VoiceCommandDetector(MatchLang.FR)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("retour", "arrière")))
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(1, words("reviens", "en", "arriere")),
        )
    }

    @Test
    fun newUtteranceResetsFiredPositions() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("go", "back")))
        // Same words, new utterance: a genuinely new occurrence.
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(1, words("go", "back")))
    }

    @Test
    fun hintNamesThePrimaryPhrase() {
        assertEquals("go back", VoiceCommandDetector(MatchLang.EN).hint)
        assertEquals("retour arrière", VoiceCommandDetector(MatchLang.FR).hint)
    }
}
