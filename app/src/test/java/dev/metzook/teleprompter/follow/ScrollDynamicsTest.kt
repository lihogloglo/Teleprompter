package dev.metzook.teleprompter.follow

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion controller is what "flowy" lives in: these tests replay the exact
 * on-device scenarios that felt bad (word-stepped targets, freezes, provisional
 * fallbacks) and assert on the *shape* of the motion, not just the endpoint.
 */
class ScrollDynamicsTest {

    private val LINE = 90f // px, a typical line height at 40sp
    private val DEADBAND = LINE * 0.35f
    private val MAX_V = 3000f
    private val MAX_BACK = LINE * 0.6f

    private fun ScrollDynamics.run(
        seconds: Float,
        hz: Int = 60,
        start: Float = 0f,
        target: (t: Float) -> Float,
        feed: (t: Float) -> Float = { 0f },
        onFrame: ((t: Float, pos: Float, v: Float) -> Unit)? = null,
    ): Float {
        var pos = start
        val dt = 1f / hz
        var t = 0f
        val frames = (seconds * hz).toInt()
        repeat(frames) {
            t += dt
            pos += step(dt, pos, target(t), feed(t), DEADBAND, MAX_V, MAX_BACK)
            onFrame?.invoke(t, pos, velocity)
        }
        return pos
    }

    @Test
    fun `word-stepped target with feed-forward produces smooth continuous motion`() {
        // Reader at 2.5 tokens/s, ~6 tokens per line: target steps by a word
        // (LINE/6) every 400 ms; feed-forward matches the average rate.
        val pxPerSec = 2.5f * (LINE / 6f)
        val d = ScrollDynamics()
        var minV = Float.MAX_VALUE
        var maxV = Float.MIN_VALUE
        d.run(
            seconds = 8f,
            target = { t -> (t * 2.5f).toInt() * (LINE / 6f) },
            feed = { pxPerSec },
            onFrame = { t, _, v ->
                if (t > 2f) { // after spin-up
                    minV = minOf(minV, v)
                    maxV = maxOf(maxV, v)
                }
            },
        )
        // Flowy = velocity never stops, never reverses, never doubles: the
        // word steps live inside the dead zone and the ride stays continuous.
        assertTrue("velocity dipped to $minV (stall)", minV > pxPerSec * 0.5f)
        assertTrue("velocity peaked at $maxV (lurch)", maxV < pxPerSec * 1.75f)
    }

    @Test
    fun `tracks a steady reader without accumulating error`() {
        val pxPerSec = 2.5f * (LINE / 6f)
        val d = ScrollDynamics()
        val end = d.run(
            seconds = 10f,
            target = { t -> (t * 2.5f).toInt() * (LINE / 6f) },
            feed = { pxPerSec },
        )
        val targetEnd = (10f * 2.5f).toInt() * (LINE / 6f)
        assertTrue(
            "ended ${end - targetEnd} px from target",
            abs(end - targetEnd) < DEADBAND + LINE * 0.5f,
        )
    }

    @Test
    fun `freeze decays to a stop quickly and never pulls back`() {
        val d = ScrollDynamics()
        // Reading, then improv: target and feed freeze at t=2s.
        val frozenTarget = (2f * 2.5f).toInt() * (LINE / 6f)
        var posAtFreeze = -1f
        var minAfter = Float.MAX_VALUE
        var stoppedAt = -1f
        d.run(
            seconds = 5f,
            target = { t -> if (t < 2f) (t * 2.5f).toInt() * (LINE / 6f) else frozenTarget },
            feed = { t -> if (t < 2f) 2.5f * (LINE / 6f) else 0f },
            onFrame = { t, pos, v ->
                if (t >= 2f) {
                    if (posAtFreeze < 0f) posAtFreeze = pos
                    minAfter = minOf(minAfter, pos)
                    if (stoppedAt < 0f && v == 0f) stoppedAt = t
                }
            },
        )
        assertTrue("never stopped", stoppedAt > 0f)
        assertTrue("took ${stoppedAt - 2f}s to stop", stoppedAt - 2f < 0.8f)
        assertTrue("pulled back ${posAtFreeze - minAfter}px", minAfter >= posAtFreeze - 1f)
    }

    @Test
    fun `provisional fallback (small backward target step) barely moves the text`() {
        val d = ScrollDynamics()
        // Settled at 500, then the target regresses by one word (a provisional
        // anchor revision) for 300 ms before recovering.
        val word = LINE / 6f
        var minPos = Float.MAX_VALUE
        d.run(
            seconds = 2f,
            start = 500f,
            target = { t -> if (t in 0.5f..0.8f) 500f - word else 500f },
            onFrame = { _, pos, _ -> minPos = minOf(minPos, pos) },
        )
        // One word fits inside the dead zone: no visible backward motion at all.
        assertTrue("moved back ${500f - minPos}px on a one-word fallback", minPos > 499f)
    }

    @Test
    fun `far rejoin glides fast but respects the velocity cap`() {
        val d = ScrollDynamics()
        var peakV = 0f
        val end = d.run(
            seconds = 3f,
            start = 0f,
            target = { 2000f },
            onFrame = { _, _, v -> peakV = maxOf(peakV, v) },
        )
        assertTrue("peak $peakV exceeded cap", peakV <= MAX_V + 1f)
        assertTrue("didn't arrive (at $end)", end > 2000f - DEADBAND - 1f)
        assertTrue("overshot to $end", end < 2000f + LINE)
    }

    @Test
    fun `large backward correction eases at the backward cap`() {
        val d = ScrollDynamics()
        var minV = 0f
        d.run(
            seconds = 2f,
            start = 1000f,
            target = { 400f },
            onFrame = { _, _, v -> minV = minOf(minV, v) },
        )
        assertTrue("backward speed $minV exceeded the gentle cap", minV >= -MAX_BACK - 1f)
    }

    @Test
    fun `frame rate does not change the trajectory`() {
        fun runAt(hz: Int): Float {
            val d = ScrollDynamics()
            return d.run(
                seconds = 4f,
                hz = hz,
                target = { t -> (t * 2.5f).toInt() * (LINE / 6f) },
                feed = { 2.5f * (LINE / 6f) },
            )
        }
        val at60 = runAt(60)
        val at120 = runAt(120)
        assertEquals("60Hz=$at60 vs 120Hz=$at120", at60, at120, LINE * 0.25f)
    }

    @Test
    fun `reset parks the drive`() {
        val d = ScrollDynamics()
        d.step(0.016f, 0f, 1000f, 0f, DEADBAND, MAX_V, MAX_BACK)
        assertTrue(d.velocity > 0f)
        d.reset()
        assertEquals(0f, d.velocity, 0f)
    }
}
