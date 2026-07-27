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

    // Paragraph tokens: [0..2], [3..6], [7] — headings/rules don't start one.
    private val paragraphed = Replay.indexOf(
        "One two three.\n\n# Heading\n\nFour five six seven.\n\n---\n\nEight.",
    )

    @Test
    fun `paragraph starts skip headings and rules`() {
        assertEquals(listOf(0, 3, 7), paragraphed.paragraphStarts.toList())
    }

    @Test
    fun `jump back re-anchors to paragraph start, then previous`() {
        // Mid second paragraph → its start (anchor 2 = "next expected is token 3").
        assertEquals(2, paragraphed.paragraphBackAnchor(5))
        // Barely entered → previous paragraph instead (track-skip idiom).
        assertEquals(-1, paragraphed.paragraphBackAnchor(3))
        // Before any match → top of script.
        assertEquals(-1, paragraphed.paragraphBackAnchor(-1))
    }

    @Test
    fun `jump forward re-anchors to the next paragraph`() {
        assertEquals(2, paragraphed.paragraphForwardAnchor(0))
        // Already at the start of a paragraph → skip to the one after it.
        assertEquals(6, paragraphed.paragraphForwardAnchor(2))
        // Nothing after the last paragraph: stay put.
        assertEquals(7, paragraphed.paragraphForwardAnchor(7))
    }
}
