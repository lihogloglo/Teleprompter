package dev.metzook.teleprompter.follow

import kotlin.math.min
import kotlin.math.roundToInt

enum class SessionEventType { FREEZE, LOST, RETAKE }

/** A notable moment in the take; feeds the end screen now, the rehearsal
 *  heatmap and marker log later (SPEC §6). */
data class SessionEvent(val type: SessionEventType, val wallMs: Long, val token: Int)

data class SectionStat(
    val title: String,
    val activeMs: Long,
    val wpm: Int,
)

data class SessionStats(
    /** Time actually delivering (playing), pauses excluded. */
    val activeMs: Long,
    val wordsRead: Int,
    val avgWpm: Int,
    /** Furthest point reached, 0..1 of spoken tokens. */
    val progress: Float,
    val freezes: Int,
    val losts: Int,
    val retakes: Int,
    /** Heading-delimited sections that got reading time. */
    val sections: List<SectionStat>,
    val events: List<SessionEvent>,
)

/**
 * Accumulates the end-screen statistics (SPEC §5.2.4) from matcher state
 * snapshots. Pure Kotlin: the clock only enters through wallMs parameters.
 *
 * Word counting reads anchor deltas: +1..12 is speech, larger forward moves
 * are jumps (skip/rejoin, not reading), drops of more than 3 are retakes.
 */
class SessionStatsBuilder(
    private val totalTokens: Int,
    /** (title, first token index) per heading-delimited section, ascending. */
    private val sectionStarts: List<Pair<String, Int>> = emptyList(),
) {
    private var playing = false
    private var lastTickMs = 0L
    private var activeMs = 0L
    private var wordsRead = 0
    private var freezes = 0
    private var losts = 0
    private var retakes = 0
    private var furthest = -1
    private var lastState: FollowState? = null
    private var currentSection = -1
    private val sectionMs = LongArray(sectionStarts.size)
    private val sectionWords = IntArray(sectionStarts.size)
    private val events = ArrayList<SessionEvent>()

    /** Enough happened that an end screen is worth showing. */
    fun meaningful(nowMs: Long, minActiveMs: Long = 10_000): Boolean {
        val pending = if (playing) (nowMs - lastTickMs).coerceAtLeast(0) else 0
        return activeMs + pending >= minActiveMs && furthest >= 0
    }

    fun onPlay(nowMs: Long) {
        if (playing) return
        playing = true
        lastTickMs = nowMs
    }

    fun onPause(nowMs: Long) {
        if (!playing) return
        flush(nowMs)
        playing = false
    }

    fun onState(state: FollowState, nowMs: Long) {
        if (playing) flush(nowMs)
        val prev = lastState
        lastState = state
        val section = sectionStarts.indexOfLast { it.second <= state.anchor }
        if (prev != null) {
            val delta = state.anchor - prev.anchor
            if (delta in 1..MAX_SPEECH_STEP) {
                wordsRead += delta
                if (section >= 0) sectionWords[section] += delta
            } else if (delta < -RETAKE_DROP) {
                retakes++
                events += SessionEvent(SessionEventType.RETAKE, nowMs, state.anchor)
            }
            if (state.mode == FollowMode.FROZEN && prev.mode == FollowMode.FOLLOWING) {
                freezes++
                events += SessionEvent(SessionEventType.FREEZE, nowMs, state.anchor)
            }
            if (state.mode == FollowMode.LOST && prev.mode != FollowMode.LOST) {
                losts++
                events += SessionEvent(SessionEventType.LOST, nowMs, state.anchor)
            }
        }
        if (state.anchor > furthest) furthest = state.anchor
        currentSection = section
    }

    private fun flush(nowMs: Long) {
        val dt = (nowMs - lastTickMs).coerceAtLeast(0)
        lastTickMs = nowMs
        activeMs += dt
        if (currentSection >= 0) sectionMs[currentSection] += dt
    }

    fun build(nowMs: Long): SessionStats {
        if (playing) flush(nowMs)
        val minutes = activeMs / 60_000.0
        val sections = sectionStarts.mapIndexedNotNull { i, (title, _) ->
            val ms = sectionMs[i]
            if (ms <= 0) null
            else SectionStat(
                title = title,
                activeMs = ms,
                wpm = (sectionWords[i] / (ms / 60_000.0)).roundToInt(),
            )
        }
        return SessionStats(
            activeMs = activeMs,
            wordsRead = wordsRead,
            avgWpm = if (minutes > 0) min((wordsRead / minutes).roundToInt(), 999) else 0,
            progress = if (totalTokens > 0) (furthest + 1).toFloat() / totalTokens else 0f,
            freezes = freezes,
            losts = losts,
            retakes = retakes,
            sections = sections,
            events = events.toList(),
        )
    }

    private companion object {
        const val MAX_SPEECH_STEP = 12
        const val RETAKE_DROP = 3
    }
}
