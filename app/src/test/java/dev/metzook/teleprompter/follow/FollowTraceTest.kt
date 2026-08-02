package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace is only useful if a device file replays *exactly* — these tests
 * pin the encode/decode roundtrip and prove a trace-driven matcher lands in
 * the same states as the live-driven one it recorded.
 */
class FollowTraceTest {

    private val events = listOf(
        FollowTrace.Event.Header(1, "0.9.1", "EN", 51, "vault/My Script.md"),
        FollowTrace.Event.Play(0.5f, true),
        FollowTrace.Event.Vis(1.0f, 0, 40),
        FollowTrace.Event.Words(
            1.2f, 0, false,
            listOf(TimedWord("first", 0.1f), TimedWord("we", 0.4f)),
        ),
        FollowTrace.Event.State(1.2f, 1, FollowMode.FOLLOWING, 2.31f),
        FollowTrace.Event.Words(1.9f, 0, true, listOf(TimedWord("first", 0.1f))),
        FollowTrace.Event.Skip(3.0f, 1, 4),
        FollowTrace.Event.Reanchor(4.0f, 12),
        FollowTrace.Event.Snap(5.0f, 1234, 56789, 1250.5f, 34.25f),
        FollowTrace.Event.Play(6.0f, false),
        FollowTrace.Event.Vis(7.0f, -1, -1),
        FollowTrace.Event.Asr(8.0f, true, 42, null),
        FollowTrace.Event.Asr(9.0f, false, 0, "Mic lost — reconnecting…"),
    )

    @Test
    fun `encode-decode roundtrip preserves every event`() {
        for (e in events) {
            val decoded = FollowTrace.decode(FollowTrace.encode(e))
            when (e) {
                // Floats survive at 3-decimal precision; compare fields loosely.
                is FollowTrace.Event.State -> {
                    val d = decoded as FollowTrace.Event.State
                    assertEquals(e.anchor, d.anchor)
                    assertEquals(e.mode, d.mode)
                    assertEquals(e.rate, d.rate, 0.001f)
                }
                is FollowTrace.Event.Snap -> {
                    val d = decoded as FollowTrace.Event.Snap
                    assertEquals(e.scroll, d.scroll)
                    assertEquals(e.target, d.target, 0.001f)
                    assertEquals(e.velocity, d.velocity, 0.001f)
                }
                else -> assertEquals(e, decoded)
            }
        }
    }

    @Test
    fun `parse skips corrupt and blank lines`() {
        val text = events.joinToString("\n") { FollowTrace.encode(it) } +
            "\n\nGARBAGE\tnot\ta\tline\nw\t1.0\tbroken\n"
        val parsed = FollowTrace.parse(text)
        assertEquals(events.size, parsed.size)
    }

    @Test
    fun `decode of unknown tag is null, never a crash`() {
        assertNull(FollowTrace.decode("x\t1\t2"))
        assertNull(FollowTrace.decode(""))
        assertNull(FollowTrace.decode("w"))
    }

    @Test
    fun `a recorded session replays to identical matcher states`() {
        val script = """
            First we check the system status and then we move on quickly.

            Later we check the system status again to be sure everything works.
        """.trimIndent()
        val index = Replay.indexOf(script)

        // Live session: feed a matcher and record what PrompterScreen would log.
        val live = Matcher(index)
        val log = StringBuilder()
        log.appendLine(FollowTrace.encode(FollowTrace.Event.Header(1, "test", "EN", index.tokens.size, "s")))
        var t = 0f
        val states = ArrayList<FollowState>()
        fun feed(utt: Int, text: String, vis: IntRange?) {
            live.visibleTokens = vis
            log.appendLine(
                FollowTrace.encode(FollowTrace.Event.Vis(t, vis?.first ?: -1, vis?.last ?: -1)),
            )
            val words = Replay.wordsOf(text)
            for (n in 1..words.size) {
                val final = n == words.size
                val partial = words.take(n)
                log.appendLine(FollowTrace.encode(FollowTrace.Event.Words(t, utt, final, partial)))
                states += live.onWords(utt, partial, final)
                t += 0.3f
            }
        }
        feed(0, "first we check the system status", 0..11)
        feed(1, "completely unrelated words here today friends", 0..11)
        feed(2, "and then we move on quickly", 0..11)

        // Replay the trace into a fresh matcher: states must match step for step.
        val replayed = FollowTrace.replay(
            Matcher(index),
            FollowTrace.parse(log.toString()),
        )
        assertEquals(states.size, replayed.size)
        states.zip(replayed).forEachIndexed { i, (liveState, pair) ->
            val (_, replayState) = pair
            assertEquals("anchor diverged at step $i", liveState.anchor, replayState.anchor)
            assertEquals("mode diverged at step $i", liveState.mode, replayState.mode)
        }
        assertTrue(states.last().anchor >= 10)
    }
}
