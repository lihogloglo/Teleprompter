package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormTest {

    @Test
    fun `diacritics are stripped`() {
        assertEquals("ecole", TextNorm.canonical("École"))
        assertEquals("ca", TextNorm.canonical("ça"))
        assertEquals("etaient", TextNorm.canonical("étaient"))
        assertEquals("noel", TextNorm.canonical("Noël"))
    }

    @Test
    fun `ligatures expand`() {
        assertEquals("coeur", TextNorm.canonical("cœur"))
    }

    @Test
    fun `punctuation is dropped`() {
        assertEquals(listOf("bonjour"), TextNorm.matchTokens("Bonjour,", MatchLang.FR))
        assertEquals(listOf("world"), TextNorm.matchTokens("world!?", MatchLang.EN))
        assertEquals(listOf("quote"), TextNorm.matchTokens("«quote»", MatchLang.FR))
    }

    @Test
    fun `french elisions split into clitic plus word`() {
        assertEquals(listOf("j", "ai"), TextNorm.matchTokens("j'ai", MatchLang.FR))
        assertEquals(listOf("l", "ecole"), TextNorm.matchTokens("l'école", MatchLang.FR))
        // Curly typographic apostrophe (Obsidian smart quotes) works too.
        assertEquals(listOf("l", "ecole"), TextNorm.matchTokens("l’école", MatchLang.FR))
        assertEquals(listOf("jusqu", "au"), TextNorm.matchTokens("jusqu'au", MatchLang.FR))
    }

    @Test
    fun `hyphenated compounds split`() {
        assertEquals(listOf("peut", "etre"), TextNorm.matchTokens("peut-être", MatchLang.FR))
        assertEquals(listOf("dix", "sept"), TextNorm.matchTokens("dix-sept", MatchLang.FR))
    }

    @Test
    fun `english contractions split consistently`() {
        assertEquals(listOf("don", "t"), TextNorm.matchTokens("don't", MatchLang.EN))
        assertEquals(listOf("world", "s"), TextNorm.matchTokens("world's", MatchLang.EN))
    }

    @Test
    fun `english numbers expand`() {
        assertEquals(listOf("forty", "two"), TextNorm.matchTokens("42", MatchLang.EN))
        assertEquals(
            listOf("one", "hundred", "fifty"),
            TextNorm.matchTokens("150", MatchLang.EN),
        )
        assertEquals(
            listOf("two", "thousand", "twenty", "six"),
            TextNorm.matchTokens("2026", MatchLang.EN),
        )
    }

    @Test
    fun `french numbers expand with their quirks`() {
        assertEquals(listOf("dix", "sept"), TextNorm.matchTokens("17", MatchLang.FR))
        assertEquals(listOf("vingt", "et", "un"), TextNorm.matchTokens("21", MatchLang.FR))
        assertEquals(listOf("soixante", "et", "onze"), TextNorm.matchTokens("71", MatchLang.FR))
        assertEquals(listOf("quatre", "vingts"), TextNorm.matchTokens("80", MatchLang.FR))
        assertEquals(listOf("quatre", "vingt", "un"), TextNorm.matchTokens("81", MatchLang.FR))
        assertEquals(
            listOf("quatre", "vingt", "dix", "sept"),
            TextNorm.matchTokens("97", MatchLang.FR),
        )
        assertEquals(listOf("deux", "cents"), TextNorm.matchTokens("200", MatchLang.FR))
        assertEquals(listOf("mille", "neuf"), TextNorm.matchTokens("1009", MatchLang.FR))
    }

    @Test
    fun `fuzzy matches tolerate small edits but keep short words exact`() {
        assertTrue(Fuzzy.matches("cents", "cent"))
        assertTrue(Fuzzy.matches("recognise", "recognize"))
        assertTrue(Fuzzy.matches("colosseum", "coliseum"))
        assertFalse(Fuzzy.matches("vous", "nous"))
        assertFalse(Fuzzy.matches("les", "des"))
        assertFalse(Fuzzy.matches("cat", "car"))
    }

    @Test
    fun `bpe pieces merge into timed words`() {
        val words = mergeBpeTokens(
            listOf("▁THE", "▁CA", "T", "▁SAT"),
            floatArrayOf(0.1f, 0.5f, 0.6f, 0.9f),
        )
        assertEquals(listOf("THE", "CAT", "SAT"), words.map { it.text })
        assertEquals(0.5f, words[1].timeSec)
        assertEquals(0.9f, words[2].timeSec)
    }

    @Test
    fun `plain word pieces fall back to piece-per-word and specials are skipped`() {
        val words = mergeBpeTokens(listOf("hello", "<unk>", "world"), floatArrayOf(0f, 1f, 2f))
        assertEquals(listOf("hello", "world"), words.map { it.text })
    }
}
