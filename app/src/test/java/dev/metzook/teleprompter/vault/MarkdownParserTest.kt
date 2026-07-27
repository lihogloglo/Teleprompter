package dev.metzook.teleprompter.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `frontmatter is parsed and stripped`() {
        val parsed = MarkdownParser.parse(
            """
            ---
            wpm: 160
            font-scale: 1.2
            mirror: true
            lang: fr
            ---
            Hello world.
            """.trimIndent(),
        )
        assertEquals(160, parsed.overrides.wpm)
        assertEquals(1.2f, parsed.overrides.fontScale!!, 0.001f)
        assertEquals(true, parsed.overrides.mirror)
        assertEquals("fr", parsed.overrides.lang)
        assertEquals(1, parsed.paragraphs.size)
        assertEquals("Hello world.", parsed.paragraphs[0].inlines.single().text)
    }

    @Test
    fun `no frontmatter means no overrides`() {
        val parsed = MarkdownParser.parse("Just text.")
        assertNull(parsed.overrides.wpm)
        assertEquals(2, parsed.spokenWordCount)
    }

    @Test
    fun `headings are separate paragraphs and not spoken`() {
        val parsed = MarkdownParser.parse("# Intro\n\nOne two three.\n\n## Part two\n\nFour five.")
        val kinds = parsed.paragraphs.map { it.kind }
        assertEquals(
            listOf(ParaKind.HEADING, ParaKind.BODY, ParaKind.HEADING, ParaKind.BODY),
            kinds,
        )
        assertEquals(1, parsed.paragraphs[0].level)
        assertEquals(2, parsed.paragraphs[2].level)
        assertEquals(5, parsed.spokenWordCount)
    }

    @Test
    fun `stage directions are dimmed and excluded from spoken count`() {
        val parsed = MarkdownParser.parse("Hello [pause here] world %%don't read this%% again.")
        val body = parsed.paragraphs.single()
        val stages = body.inlines.filter { it.stage }
        assertEquals(listOf("pause here", "don't read this"), stages.map { it.text })
        // spoken: Hello world again. = 3
        assertEquals(3, parsed.spokenWordCount)
    }

    @Test
    fun `links keep their text and embeds vanish`() {
        val parsed = MarkdownParser.parse(
            "See [[Some Note|the plan]] and [[OtherNote]] plus [docs](https://x.dev) here. ![[image.png]]",
        )
        val text = parsed.paragraphs.single().inlines.joinToString("") { it.text }
        assertEquals("See the plan and OtherNote plus docs here. ", text.trimEnd() + " ")
        assertFalse(text.contains("image.png"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun `bold italic and highlight map to span flags`() {
        val parsed = MarkdownParser.parse("Say **this loudly** and *this softly* and ==remember this==.")
        val inlines = parsed.paragraphs.single().inlines
        assertTrue(inlines.first { it.text == "this loudly" }.bold)
        assertTrue(inlines.first { it.text == "this softly" }.italic)
        assertTrue(inlines.first { it.text == "remember this" }.highlight)
        assertEquals(10, parsed.spokenWordCount)
    }

    @Test
    fun `code fences are stripped`() {
        val parsed = MarkdownParser.parse("Before.\n\n```kotlin\nval x = 1\n```\n\nAfter.")
        assertEquals(2, parsed.paragraphs.size)
        assertEquals(2, parsed.spokenWordCount)
    }

    @Test
    fun `horizontal rule becomes a RULE paragraph not frontmatter`() {
        val parsed = MarkdownParser.parse("First part.\n\n---\n\nSecond part.")
        assertEquals(
            listOf(ParaKind.BODY, ParaKind.RULE, ParaKind.BODY),
            parsed.paragraphs.map { it.kind },
        )
    }

    @Test
    fun `soft-wrapped lines join into one paragraph`() {
        val parsed = MarkdownParser.parse("Line one\nline two\nline three.")
        assertEquals(1, parsed.paragraphs.size)
        assertEquals("Line one line two line three.", parsed.paragraphs[0].inlines.single().text)
        assertEquals(6, parsed.spokenWordCount)
    }

    @Test
    fun `empty and blank input yields no paragraphs`() {
        assertTrue(MarkdownParser.parse("").paragraphs.isEmpty())
        assertTrue(MarkdownParser.parse("\n\n  \n").paragraphs.isEmpty())
        assertEquals(0, MarkdownParser.parse("").spokenWordCount)
    }
}
