package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    private fun words(vararg ws: String): List<TimedWord> =
        ws.mapIndexed { i, w -> TimedWord(w, i * 0.3f) }

    @Test
    fun firesOnCommandWord() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(0, words("please", "rewind")),
        )
    }

    @Test
    fun normalizesCaseAndPunctuation() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("REWIND!")))
    }

    @Test
    fun firesOncePerOccurrenceAcrossGrowingPartials() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("okay")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("okay", "rewind")))
        // The partial repeats the utterance so far: the same occurrence must not re-fire.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("okay", "rewind")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("okay", "rewind", "now")))
    }

    @Test
    fun firesAgainOnASecondOccurrence() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("rewind")))
        assertEquals(
            listOf(VoiceCommand.GO_BACK),
            d.onWords(0, words("rewind", "rewind")),
        )
    }

    @Test
    fun shortCommandWordsMatchExactlyOnly() {
        val d = VoiceCommandDetector(MatchLang.EN)
        // "rewind" is under the fuzzy length: one-edit neighbours must not fire.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("remind", "me")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(1, words("rewinding", "it")))
    }

    @Test
    fun frenchCommandToleratesInflectionsAndDiacritics() {
        val d = VoiceCommandDetector(MatchLang.FR)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("rembobine")))
        // ≥ 8 chars → fuzzy: decoder slips and inflections still fire.
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(1, words("rembobinez")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(2, words("rembobiné")))
        // But a genuinely different word does not.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(3, words("bobine")))
    }

    @Test
    fun frenchCommandToleratesDecoderSlips() {
        val d = VoiceCommandDetector(MatchLang.FR)
        // The field report: the decoder heard "rambobine" — one edit away.
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("rambobine")))
    }

    @Test
    fun commandSplitIntoFragmentsStillFires() {
        val d = VoiceCommandDetector(MatchLang.FR)
        // A rare word is exactly what a decoder fragments into small words.
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("ram", "bobine")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(1, words("rem", "bobine")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(2, words("rambo", "bine")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(3, words("rem", "bo", "bine")))
        // English too: exact short commands reassemble from exact fragments.
        val e = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), e.onWords(0, words("re", "wind")))
    }

    @Test
    fun splitFragmentsFireOnceAcrossGrowingPartials() {
        val d = VoiceCommandDetector(MatchLang.FR)
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("ram")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("ram", "bobine")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("ram", "bobine")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("ram", "bobine", "ok")))
    }

    @Test
    fun unrelatedNeighboursDoNotConcatenateIntoACommand() {
        val d = VoiceCommandDetector(MatchLang.FR)
        // "la bobine" joins to "labobine" — three edits away, must not fire.
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("la", "bobine")))
        assertEquals(emptyList<VoiceCommand>(), d.onWords(1, words("une", "bobine", "ici")))
    }

    @Test
    fun multiTokenPhrasesStillSupported() {
        val d = VoiceCommandDetector(
            MatchLang.EN,
            mapOf(VoiceCommand.GO_BACK to listOf("go back")),
        )
        assertEquals(emptyList<VoiceCommand>(), d.onWords(0, words("go", "backpack")))
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("go", "backpack", "go", "back")))
    }

    @Test
    fun newUtteranceResetsFiredPositions() {
        val d = VoiceCommandDetector(MatchLang.EN)
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(0, words("rewind")))
        // Same words, new utterance: a genuinely new occurrence.
        assertEquals(listOf(VoiceCommand.GO_BACK), d.onWords(1, words("rewind")))
    }

    @Test
    fun hintNamesThePrimaryWord() {
        assertEquals("rewind", VoiceCommandDetector(MatchLang.EN).hint)
        assertEquals("rembobine", VoiceCommandDetector(MatchLang.FR).hint)
    }
}
