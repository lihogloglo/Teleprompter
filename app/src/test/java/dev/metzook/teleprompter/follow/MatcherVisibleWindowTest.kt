package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen is the search space: a reader can only speak words they can see
 * (SPEC §3.2). These replays cover the duplicated-phrase false jump and the
 * [Matcher.visibleTokens] constraint on rejoin, skip and LOST recovery.
 */
class MatcherVisibleWindowTest {

    // Spoken tokens: para1 = 0..11, para2 = 12..36, para3 = 37..50.
    // "check the system status" appears at 2..5 AND 39..42 (the trap).
    private val script = """
        First we check the system status and then we move on quickly.

        Here is a much longer middle paragraph that keeps the reader busy for a while with plenty of things to say before anything interesting happens.

        Later we check the system status again to be sure everything still works fine.
    """.trimIndent()

    private val para1 = "first we check the system status and then we move on quickly"
    private val para2 =
        "here is a much longer middle paragraph that keeps the reader busy for a " +
            "while with plenty of things to say before anything interesting happens"
    private val improv =
        "let me tell you about something completely unrelated because yesterday " +
            "my neighbor decided to repaint his fence bright orange and honestly " +
            "everyone came outside to watch him argue with his ladder for twenty minutes"

    private val index = Replay.indexOf(script)

    @Test
    fun `script indexes the expected shape`() {
        assertEquals(51, index.tokens.size)
        assertEquals(listOf(0, 12, 37), index.paragraphStarts.toList())
    }

    @Test
    fun `reading a phrase that repeats later in the script never jumps away`() {
        val m = Matcher(index)
        // "we check the system status" completes a run at its duplicate
        // (38..42) while every word also forward-matches at the anchor: the
        // forward match must win at *every* step — the end state can look fine
        // even when the anchor teleported mid-phrase and ping-ponged back.
        val words = Replay.wordsOf(para1)
        for (n in 1..words.size) {
            m.onWords(0, words.take(n), final = false)
            assertTrue(
                "anchor ${m.state.anchor} left para1 after word $n",
                m.state.anchor <= 11,
            )
        }
        val state = m.onWords(0, words, final = true)
        assertEquals(11, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `a run landing outside the visible window cannot move the anchor`() {
        val m = Matcher(index)
        m.visibleTokens = 0..11 // only para1 on screen
        Replay.feed(m, para1)
        assertEquals(11, m.state.anchor)

        // "everything still works fine" exists only in off-screen para3: a
        // reader cannot be reading it, so nothing may move.
        val state = Replay.feedUtterance(m, 1, Replay.wordsOf("everything still works fine"))
        assertEquals(11, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `skip-ahead to a visible paragraph still re-anchors`() {
        val m = Matcher(index)
        m.visibleTokens = 0..50 // whole script on screen
        Replay.feed(m, para1)
        val state = Replay.feedUtterance(m, 1, Replay.wordsOf("everything still works fine"))
        assertEquals(50, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `retake within the visible window still jumps back`() {
        val m = Matcher(index)
        m.visibleTokens = 0..50
        Replay.feed(m, para1, para2)
        assertEquals(36, m.state.anchor)

        val state = Replay.feedUtterance(m, 2, Replay.wordsOf("here is a much longer"))
        assertEquals(16, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `lost recovery only lands on visible lines`() {
        val m = Matcher(index)
        m.visibleTokens = 0..25
        Replay.feed(m, para1)
        Replay.feedUtterance(m, 1, Replay.wordsOf(improv))
        assertEquals(FollowMode.LOST, m.state.mode)

        // Off-screen para3 words: stays lost — the reader can't see that line.
        Replay.feedUtterance(m, 2, Replay.wordsOf("everything still works fine"))
        assertEquals(FollowMode.LOST, m.state.mode)

        // Re-reading a visible line recovers.
        val state = Replay.feedUtterance(m, 3, Replay.wordsOf("first we check the system"))
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertTrue("anchor was ${state.anchor}", state.anchor in 0..5)
    }

    @Test
    fun `tokenRangeForChars maps display slices to token ranges`() {
        assertEquals(0..50, index.tokenRangeForChars(0, Int.MAX_VALUE))
        assertNull(index.tokenRangeForChars(0, 0))
        val t0 = index.tokens[0]
        assertEquals(0..0, index.tokenRangeForChars(t0.charStart, t0.charEnd))
    }
}
