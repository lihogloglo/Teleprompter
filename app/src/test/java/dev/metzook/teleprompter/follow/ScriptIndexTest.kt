package dev.metzook.teleprompter.follow

import dev.metzook.teleprompter.vault.MarkdownParser
import dev.metzook.teleprompter.vault.toDisplaySegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptIndexTest {

    @Test
    fun `spoken tokens carry offsets into the display text`() {
        val markdown = "# Intro\n\nHello brave new world."
        val parsed = MarkdownParser.parse(markdown)
        val segments = parsed.toDisplaySegments()
        val displayText = segments.joinToString("") { it.text }
        val index = ScriptIndex.build(
            segments.map { SpokenSegment(it.text, it.spoken) },
            MatchLang.EN,
        )

        assertEquals(listOf("hello", "brave", "new", "world"), index.tokens.map { it.norm })
        for (token in index.tokens) {
            val raw = displayText.substring(token.charStart, token.charEnd)
            assertEquals(token.norm, TextNorm.canonical(raw))
        }
        // Heading text is rendered but not indexed.
        assertTrue(displayText.contains("Intro"))
    }

    @Test
    fun `stage directions and rules are excluded`() {
        val index = Replay.indexOf("One two [wave to camera] three.\n\n---\n\nFour.")
        assertEquals(listOf("one", "two", "three", "four"), index.tokens.map { it.norm })
    }

    @Test
    fun `split tokens share their word's char range`() {
        val segments = listOf(SpokenSegment("j'ai dit", true))
        val index = ScriptIndex.build(segments, MatchLang.FR)
        assertEquals(listOf("j", "ai", "dit"), index.tokens.map { it.norm })
        assertEquals(index.tokens[0].charStart, index.tokens[1].charStart)
        assertEquals(index.tokens[0].charEnd, index.tokens[1].charEnd)
        assertEquals("j'ai", "j'ai dit".substring(index.tokens[0].charStart, index.tokens[0].charEnd))
    }

    @Test
    fun `inverted index finds all positions`() {
        val index = Replay.indexOf("the cat and the dog")
        assertEquals(listOf(0, 3), index.positionsByNorm["the"]!!.toList())
    }
}
