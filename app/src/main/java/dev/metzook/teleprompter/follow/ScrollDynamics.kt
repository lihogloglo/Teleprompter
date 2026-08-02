package dev.metzook.teleprompter.follow

import kotlin.math.abs
import kotlin.math.exp

/**
 * Velocity-servo scroll controller for follow mode (SPEC §1: glide, never jump).
 *
 * The matcher's anchor is a noisy, word-stepped estimate, so the motion layer
 * must be a *rate* controller, not a position snapper — the industry consensus
 * (docs/voice-follow-research.md §d: PromptSmart's zone-based rate control,
 * SmoothDamp-style damping). Three ideas compose:
 *
 *  - **Feed-forward**: while the reader is speaking, scroll at the measured
 *    reading rate, so motion is continuous *between* anchor updates. Matches
 *    then only trim this rate — motion with corrections, not motion made of
 *    corrections.
 *  - **Dead zone**: within ±[deadbandPx] of the target no correction applies
 *    at all; word-level anchor twitches and provisional fallbacks land inside
 *    it and produce zero visible motion.
 *  - **Velocity smoothing**: desired velocity is approached over
 *    [VELOCITY_TAU], which bounds acceleration — the step-shaped target can
 *    never produce a lurch. Backward speed is clamped hard: easing back is
 *    barely perceptible, because backward motion is the most jarring artifact
 *    a prompter can produce.
 *
 * Pure Kotlin, frame-rate independent (exponential smoothing in dt), no clock
 * of its own: the caller supplies dt.
 */
class ScrollDynamics {

    /** Current scroll velocity in px/s (positive = text moving up / advancing). */
    var velocity = 0f
        private set

    /** Park the drive (hand owns the scroll, anchor invalid, mode change). */
    fun reset() {
        velocity = 0f
    }

    /**
     * Advance one frame; returns the scroll delta in px to apply.
     *
     * @param dt frame time in seconds (already clamped by the caller)
     * @param current current scroll position (px)
     * @param target where the anchor wants the scroll to be (px)
     * @param feedForwardPxPerSec measured reading rate in px/s while the reader
     *   is actively speaking on-script; 0 when frozen, lost or silent
     * @param deadbandPx half-height of the no-correction zone around the target
     * @param maxForwardPxPerSec catch-up speed cap (rejoins/retakes glide fast
     *   but never teleport)
     * @param maxBackPxPerSec backward speed cap (a gentle drift, never a yank)
     */
    fun step(
        dt: Float,
        current: Float,
        target: Float,
        feedForwardPxPerSec: Float,
        deadbandPx: Float,
        maxForwardPxPerSec: Float,
        maxBackPxPerSec: Float,
    ): Float {
        if (dt <= 0f) return 0f
        val err = target - current
        // Error beyond the dead zone; inside it there is nothing to correct.
        val excess = when {
            err > deadbandPx -> err - deadbandPx
            err < -deadbandPx -> err + deadbandPx
            else -> 0f
        }
        val desired = (feedForwardPxPerSec + excess / CORRECTION_TAU)
            .coerceIn(-maxBackPxPerSec, maxForwardPxPerSec)
        velocity += (desired - velocity) * (1f - exp(-dt / VELOCITY_TAU))
        // Settle: once there is nothing to do, kill sub-pixel creep.
        if (desired == 0f && abs(velocity) < SETTLE_PX_PER_SEC) velocity = 0f
        return velocity * dt
    }

    companion object {
        /** Out-of-zone error is closed over roughly this horizon (P-gain). */
        private const val CORRECTION_TAU = 0.35f

        /** Acceleration bound: velocity reaches a new setpoint over ~3τ. */
        private const val VELOCITY_TAU = 0.18f

        private const val SETTLE_PX_PER_SEC = 2f
    }
}
