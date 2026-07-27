package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript replays against a known script (SPEC §3.2): clean reading,
 * misrecognitions, improv freeze, rejoin, retakes, skips, French.
 */
class MatcherReplayTest {

    // Spoken tokens: para1 = 0..19, para2 = 20..34, para3 = 35..52.
    private val script = """
        # Intro

        Welcome to the channel. Today we are going to talk about the ancient city of Rome and its many secrets.

        The Colosseum was built almost two thousand years ago. [gesture to the model] It could hold fifty thousand people.

        Gladiators fought here for the glory of the empire, and the crowd decided who lived and who died.
    """.trimIndent()

    private val para1 =
        "welcome to the channel today we are going to talk about the ancient city of rome and its many secrets"
    private val para2 =
        "the colosseum was built almost two thousand years ago it could hold fifty thousand people"
    private val para3 =
        "gladiators fought here for the glory of the empire and the crowd decided who lived and who died"

    private fun matcher() = Matcher(Replay.indexOf(script))

    @Test
    fun `script indexes the expected token count`() {
        assertEquals(53, Replay.indexOf(script).tokens.size)
    }

    @Test
    fun `clean read follows to the end`() {
        val m = matcher()
        val state = Replay.feed(m, para1, para2, para3)
        assertEquals(52, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertEquals(1f, state.progress, 1e-6f)
    }

    @Test
    fun `misrecognized words do not stall progress`() {
        val m = matcher()
        val state = Replay.feed(
            m,
            Replay.corrupt(para1, 5),
            Replay.corrupt(para2, 5),
            Replay.corrupt(para3, 5),
        )
        assertEquals(52, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `distant-mic conditions - every third word lost - never freezes`() {
        val m = matcher()
        val state = Replay.feed(
            m,
            Replay.corrupt(para1, 3),
            Replay.corrupt(para2, 3),
            Replay.corrupt(para3, 3),
        )
        assertTrue("anchor was ${state.anchor}", state.anchor >= 50)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `a missed run of words resumes on the next content word`() {
        val m = matcher()
        Replay.feed(m, para1)
        // Mic missed "the colosseum was built almost two": reading resumes
        // six tokens ahead on a content word — no freeze, no rejoin needed.
        val state = Replay.feedUtterance(
            m,
            1,
            Replay.wordsOf("thousand years ago it could"),
        )
        assertEquals(30, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `rejoin works even with mispronounced words in the rejoin phrase`() {
        val m = matcher()
        Replay.feed(m, para1)
        Replay.feedUtterance(
            m,
            1,
            Replay.wordsOf("let me tell you something completely different about my weekend plans instead"),
        )
        assertEquals(FollowMode.FROZEN, m.state.mode)

        // First word garbled, rest imperfect: near-rejoin still lands.
        val state = Replay.feedUtterance(m, 2, Replay.wordsOf(Replay.corrupt(para2, 4)))
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertEquals(34, state.anchor)
    }

    @Test
    fun `growing last word advances the published anchor provisionally`() {
        val m = matcher()
        val st = m.onWords(0, Replay.wordsOf("welcome to"), final = false)
        assertEquals(1, st.anchor) // "to" exactly matches → provisional advance
        assertEquals(0, m.state.anchor) // committed state still holds it back
        m.onWords(0, Replay.wordsOf("welcome to"), final = true)
        assertEquals(1, m.state.anchor)
    }

    @Test
    fun `growing last word that mismatches stays put and revisions recover`() {
        val m = matcher()
        // "tomato" could be a half-recognized word: no provisional movement.
        val st = m.onWords(0, Replay.wordsOf("welcome tomato"), final = false)
        assertEquals(0, st.anchor)
        assertEquals(0, m.state.anchor)
        // The recognizer revises it to the real words: confirm + provisional.
        val st2 = m.onWords(0, Replay.wordsOf("welcome to the"), final = false)
        assertEquals(2, st2.anchor) // "to" confirmed (1), "the" provisional (2)
        assertEquals(1, m.state.anchor)
    }

    @Test
    fun `no provisional advance while frozen`() {
        val m = matcher()
        Replay.feed(m, para1)
        Replay.feedUtterance(
            m,
            1,
            Replay.wordsOf("let me tell you something completely different about my weekend plans instead"),
        )
        assertEquals(FollowMode.FROZEN, m.state.mode)
        val frozenAnchor = m.state.anchor
        // "the" would exact-match ahead, but frozen requires a rejoin run.
        val st = m.onWords(2, Replay.wordsOf("the"), final = false)
        assertEquals(frozenAnchor, st.anchor)
        assertEquals(FollowMode.FROZEN, st.mode)
    }

    @Test
    fun `improv freezes and staying frozen through the riff`() {
        val m = matcher()
        Replay.feed(m, para1)
        assertEquals(19, m.state.anchor)

        val state = Replay.feedUtterance(
            m,
            1,
            Replay.wordsOf(
                "by the way i completely forgot to mention what my cat did yesterday it was hilarious honestly",
            ),
        )
        assertEquals(FollowMode.FROZEN, state.mode)
        // Function-word creep is tolerated, but the anchor must essentially hold.
        assertTrue("anchor crept to ${state.anchor}", state.anchor in 19..23)
    }

    @Test
    fun `rejoin after improv resumes from the script`() {
        val m = matcher()
        Replay.feed(m, para1)
        Replay.feedUtterance(
            m,
            1,
            Replay.wordsOf("let me tell you something completely different about my weekend plans instead"),
        )
        assertEquals(FollowMode.FROZEN, m.state.mode)

        val state = Replay.feedUtterance(m, 2, Replay.wordsOf(para2))
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertEquals(34, state.anchor)
    }

    @Test
    fun `retake jumps back and re-follows`() {
        val m = matcher()
        Replay.feed(m, para1, para2)
        assertEquals(34, m.state.anchor)

        // Redo the last sentence: three matched words re-anchor backwards…
        Replay.feedUtterance(m, 2, Replay.wordsOf("it could hold"))
        assertEquals(31, m.state.anchor)
        assertEquals(FollowMode.FOLLOWING, m.state.mode)

        // …and reading continues seamlessly across the paragraph boundary.
        val state = Replay.feedUtterance(
            m,
            3,
            Replay.wordsOf("fifty thousand people gladiators fought here"),
        )
        assertEquals(37, state.anchor)
    }

    @Test
    fun `skipping a paragraph re-anchors ahead`() {
        val m = matcher()
        Replay.feed(m, para1)
        val state = Replay.feedUtterance(m, 1, Replay.wordsOf(para3))
        assertEquals(52, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `silence between utterances does not freeze`() {
        val m = matcher()
        Replay.feedUtterance(m, 0, Replay.wordsOf(para1))
        // Long silence = endpoint fired, next utterance much later; no words, no state change.
        val state = Replay.feedUtterance(m, 5, Replay.wordsOf(para2))
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertEquals(34, state.anchor)
    }

    @Test
    fun `speech while paused can be skipped without matching`() {
        val m = matcher()
        Replay.feed(m, para1)
        m.skip(1, Replay.wordsOf(para2))
        m.onWords(1, Replay.wordsOf(para2), final = true)
        assertEquals(19, m.state.anchor) // paused speech ignored

        val state = Replay.feedUtterance(m, 2, Replay.wordsOf(para2))
        assertEquals(34, state.anchor)
    }

    @Test
    fun `rate estimate tracks the speaking rate`() {
        val m = matcher()
        val state = Replay.feed(m, para1, para2, para3) // wordsOf default 2.5 wps
        assertTrue(
            "rate was ${state.tokensPerSec}",
            state.tokensPerSec in 2.0f..3.0f,
        )
    }

    @Test
    fun `manual reanchor resets cleanly`() {
        val m = matcher()
        Replay.feed(m, para1, para2)
        m.reanchor(-1)
        assertEquals(-1, m.state.anchor)
        val state = Replay.feedUtterance(m, 7, Replay.wordsOf(para1))
        assertEquals(19, state.anchor)
    }

    // --- French ---

    private val frenchScript = """
        J'ai visité l'école ce matin. Les enfants étaient déjà là.

        Nous avons parlé pendant 80 minutes de littérature française.
    """.trimIndent()

    @Test
    fun `french elisions diacritics and numbers all match`() {
        val index = Replay.indexOf(frenchScript, MatchLang.FR)
        assertEquals(22, index.tokens.size)

        val m = Matcher(index)
        val state = Replay.feed(
            m,
            "j'ai visité l'école ce matin les enfants étaient déjà là",
            "nous avons parlé pendant quatre-vingts minutes de littérature française",
        )
        assertEquals(21, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }

    @Test
    fun `french matches even when asr drops diacritics or splits elisions`() {
        val m = Matcher(Replay.indexOf(frenchScript, MatchLang.FR))
        // Recognizer emitting bare words and split clitics still aligns.
        val state = Replay.feed(
            m,
            "j ai visite l ecole ce matin les enfants etaient deja la",
        )
        assertEquals(11, state.anchor)
        assertEquals(FollowMode.FOLLOWING, state.mode)
    }
}
