package dev.metzook.teleprompter.follow

import dev.metzook.teleprompter.vault.MarkdownParser
import dev.metzook.teleprompter.vault.sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {

    private fun state(anchor: Int, mode: FollowMode = FollowMode.FOLLOWING) =
        FollowState(anchor = anchor, mode = mode)

    @Test
    fun `clean take - duration words and wpm`() {
        val b = SessionStatsBuilder(totalTokens = 100)
        b.onPlay(0)
        // 100 tokens over 40 s (2.5 tokens/s), one state per token.
        var t = 0L
        for (anchor in 0..99) {
            t = anchor * 400L
            b.onState(state(anchor), t)
        }
        b.onPause(t)
        val stats = b.build(t + 60_000) // idle time after pause must not count
        assertEquals(39_600, stats.activeMs)
        assertEquals(99, stats.wordsRead) // first state sets the baseline
        assertEquals(150, stats.avgWpm)
        assertEquals(1f, stats.progress, 1e-6f)
        assertEquals(0, stats.freezes)
        assertEquals(0, stats.retakes)
    }

    @Test
    fun `freeze retake and lost are counted with events`() {
        val b = SessionStatsBuilder(totalTokens = 100)
        b.onPlay(0)
        b.onState(state(10), 1_000)
        b.onState(state(10, FollowMode.FROZEN), 2_000)
        b.onState(state(10, FollowMode.LOST), 3_000)
        b.onState(state(11), 4_000) // rejoined
        b.onState(state(4), 5_000) // retake: jumped back
        b.onState(state(30), 6_000) // forward jump: not counted as words
        b.onPause(6_000)

        val stats = b.build(6_000)
        assertEquals(1, stats.freezes)
        assertEquals(1, stats.losts)
        assertEquals(1, stats.retakes)
        assertEquals(1, stats.wordsRead) // only the 10→11 step was speech
        assertEquals(
            listOf(SessionEventType.FREEZE, SessionEventType.LOST, SessionEventType.RETAKE),
            stats.events.map { it.type },
        )
        assertEquals(31 / 100f, stats.progress, 1e-6f) // furthest = 30
    }

    @Test
    fun `pauses exclude time and meaningful needs real activity`() {
        val b = SessionStatsBuilder(totalTokens = 100)
        b.onPlay(0)
        b.onState(state(0), 2_000)
        b.onPause(5_000)
        assertFalse(b.meaningful(nowMs = 500_000)) // only 5 s active
        b.onPlay(100_000)
        b.onState(state(1), 106_000)
        assertTrue(b.meaningful(nowMs = 106_000)) // 5 + 6 s ≥ 10 s
        assertEquals(11_000, b.build(106_000).activeMs)
    }

    @Test
    fun `time and words split per section`() {
        val b = SessionStatsBuilder(
            totalTokens = 40,
            sectionStarts = listOf("Intro" to 0, "Part two" to 20),
        )
        b.onPlay(0)
        b.onState(state(0), 0)
        b.onState(state(19), 10_000) // Intro: 10 s — 19-token jump counts as jump, not words
        b.onState(state(20), 12_000) // crossing into Part two: the 2 s belong to Intro's tail
        b.onState(state(30), 20_000)
        b.onPause(20_000)

        val stats = b.build(20_000)
        assertEquals(2, stats.sections.size)
        assertEquals("Intro", stats.sections[0].title)
        assertEquals(12_000, stats.sections[0].activeMs)
        assertEquals("Part two", stats.sections[1].title)
        assertEquals(8_000, stats.sections[1].activeMs)
        assertEquals(11, stats.wordsRead) // 19→20 (1) + 20→30 (10)
    }

    @Test
    fun `sections come from headings with token starts`() {
        val md = "# Intro\n\nOne two three.\n\n# Part **two**\n\nFour five six."
        val parsed = MarkdownParser.parse(md)
        val index = Replay.indexOf(md)
        val sections = parsed.sections().map { it.title to index.firstTokenAtOrAfter(it.charStart) }
        assertEquals(listOf("Intro" to 0, "Part two" to 3), sections)
    }

    @Test
    fun `scripts without headings have no sections`() {
        assertTrue(MarkdownParser.parse("Just some text.").sections().isEmpty())
    }
}
