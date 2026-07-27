package dev.metzook.teleprompter.follow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class FollowMode {
    /** Anchor tracks speech; scroll follows. */
    FOLLOWING,

    /** Sustained off-script speech: hold position until a rejoin run lands. */
    FROZEN,
}

data class FollowState(
    /** Index of the last confidently-spoken token, -1 before the first match. */
    val anchor: Int = -1,
    val mode: FollowMode = FollowMode.FOLLOWING,
    /** Rolling speaking rate in script tokens per second. */
    val tokensPerSec: Float = 2.3f,
    /** Display-text char offset of the anchor token's end (0 if no anchor). */
    val anchorCharEnd: Int = 0,
    /** 0..1 fraction of spoken tokens consumed. */
    val progress: Float = 0f,
)

data class MatcherConfig(
    /** Forward fuzzy scan: how many script tokens a reader may skip unnoticed. */
    val maxSkipAhead: Int = 8,
    /** Re-said recent tokens (stutter/emphasis) count as on-script, no advance. */
    val backRepeat: Int = 2,
    /** Consecutive matched tokens required for a rejoin/retake jump (SPEC N≈3–4). */
    val rejoinRun: Int = 3,
    /** Rejoin search window around the anchor, in tokens (SPEC ±150). */
    val rejoinBack: Int = 250,
    val rejoinForward: Int = 250,
    /** Freeze when ≥ [freezeUnmatched] of the last [freezeWindow] tokens missed. */
    val freezeWindow: Int = 8,
    val freezeUnmatched: Int = 6,
    /** Rate EMA weight and clamps (tokens/sec). */
    val rateAlpha: Float = 0.3f,
    val minRate: Float = 0.3f,
    val maxRate: Float = 8f,
    val initialRate: Float = 2.3f,
)

/**
 * The script matcher (SPEC §3.2): fuzzy-aligns the streaming ASR partials
 * against the spoken-token index and maintains the anchor.
 *
 * Three cooperating mechanisms, all fed one normalized token at a time:
 *  - a greedy fuzzy forward scan advances the anchor during normal reading;
 *  - an always-on run tracker searches a wide window for [MatcherConfig.rejoinRun]
 *    consecutive matches — one mechanism covers improv-rejoin, retakes
 *    (jumping back) and skipping ahead;
 *  - a sliding match-quality window flips the state to FROZEN during improv.
 *
 * Pure Kotlin, event-driven, no clock: time only enters through word
 * timestamps, and only deltas within one utterance are ever compared
 * (utterance timestamps restart at endpoints).
 */
class Matcher(
    private val index: ScriptIndex,
    private val config: MatcherConfig = MatcherConfig(),
) {
    private val tokens = index.tokens

    var state = FollowState(tokensPerSec = config.initialRate)
        private set

    private var currentUtterance = -1
    private var consumedWords = 0

    // Sliding window of recent token outcomes (true = matched on-script).
    private val outcomes = ArrayDeque<Boolean>()

    // Rate estimation: last anchor advance within the current utterance.
    private var lastMatchTime = -1f
    private var lastMatchUtterance = -1
    private var lastMatchAnchor = -1
    private var rate = config.initialRate

    // Rejoin run tracker: script position → length of the consecutive run ending there.
    private var runs = HashMap<Int, Int>()
    private var runsCarriedOver = false

    /**
     * Feed one ASR partial (the utterance's words so far). Growing partials are
     * deduplicated internally; the still-changing last word never commits state
     * but may advance the *returned* anchor provisionally (see [provisional]).
     */
    fun onWords(utterance: Int, words: List<TimedWord>, final: Boolean): FollowState {
        if (utterance != currentUtterance) {
            currentUtterance = utterance
            consumedWords = 0
        }
        val limit = if (final) words.size else max(0, words.size - 1)
        consumedWords = min(consumedWords, words.size)
        for (i in consumedWords until limit) {
            val w = words[i]
            for (norm in TextNorm.matchTokens(w.text, index.lang)) {
                processToken(norm, w.timeSec, utterance)
            }
        }
        consumedWords = max(consumedWords, limit)
        if (final || words.isEmpty()) return state
        return provisional(words.last())
    }

    /**
     * The newest partial word is still growing, so it must not commit anything —
     * but if it already *exactly* matches the next expected script tokens, the
     * published anchor advances one partial early, cutting ~0.3–0.5 s of felt
     * latency. Exact-only on purpose: a growing word often prefix-matches a
     * different word, and a wrong guess costs a one-token target regression
     * (hidden by the scroll's gentle ease-back). Recomputed from committed
     * state every call, so a revised word simply falls back.
     */
    private fun provisional(word: TimedWord): FollowState {
        if (state.mode != FollowMode.FOLLOWING) return state
        val norms = TextNorm.matchTokens(word.text, index.lang)
        if (norms.isEmpty()) return state
        var pos = state.anchor
        norms.forEachIndexed { i, norm ->
            // The first token may sit a couple ahead (skipped filler); the
            // rest of a split word must be consecutive.
            val last = if (i == 0) min(pos + 3, tokens.size - 1) else min(pos + 1, tokens.size - 1)
            var found = -1
            for (j in (pos + 1)..last) {
                if (tokens[j].norm == norm) {
                    found = j
                    break
                }
            }
            if (found < 0) return state
            pos = found
        }
        return state.copy(
            anchor = pos,
            anchorCharEnd = tokens[pos].charEnd,
            progress = if (tokens.isEmpty()) 0f else (pos + 1).toFloat() / tokens.size,
        )
    }

    /** Skip past speech without matching it (e.g. words spoken while paused). */
    fun skip(utterance: Int, words: List<TimedWord>) {
        currentUtterance = utterance
        consumedWords = words.size
    }

    /** Manual re-anchor (long-press a paragraph, restart). -1 restarts from the top. */
    fun reanchor(tokenIndex: Int) {
        val idx = tokenIndex.coerceIn(-1, tokens.size - 1)
        runs.clear()
        runsCarriedOver = false
        outcomes.clear()
        lastMatchTime = -1f
        lastMatchUtterance = -1
        lastMatchAnchor = idx
        state = FollowState(
            anchor = idx,
            mode = FollowMode.FOLLOWING,
            tokensPerSec = rate,
            anchorCharEnd = if (idx >= 0) tokens[idx].charEnd else 0,
            progress = if (tokens.isEmpty()) 0f else (idx + 1).toFloat() / tokens.size,
        )
    }

    private fun processToken(norm: String, t: Float, utterance: Int) {
        val anchor = state.anchor
        val frozen = state.mode == FollowMode.FROZEN

        // Always feed the run tracker; while frozen it is the only way back.
        val runTarget = updateRuns(norm, anchor, anyPosition = frozen)

        if (frozen) {
            if (runTarget >= 0) applyMatch(runTarget, t, utterance, jump = true)
            else recordOutcome(false)
            return
        }

        // Greedy forward scan: nearest fuzzy match within a short skip range.
        var forward = -1
        val from = anchor + 1
        val to = min(tokens.size - 1, anchor + config.maxSkipAhead)
        for (j in from..to) {
            if (Fuzzy.matches(tokens[j].norm, norm)) {
                forward = j
                break
            }
        }

        // Re-saying the last words (stutter, emphasis) is on-script: no advance.
        val repeat = forward < 0 && anchor >= 0 &&
            (max(0, anchor - config.backRepeat)..anchor)
                .any { Fuzzy.matches(tokens[it].norm, norm) }

        when {
            runTarget >= 0 -> applyMatch(runTarget, t, utterance, jump = true)
            forward >= 0 -> applyMatch(forward, t, utterance, jump = false)
            repeat -> recordOutcome(true)
            else -> {
                recordOutcome(false)
                maybeFreeze()
            }
        }
    }

    private fun applyMatch(target: Int, t: Float, utterance: Int, jump: Boolean) {
        if (!jump &&
            lastMatchUtterance == utterance &&
            lastMatchAnchor in 0 until target &&
            t > lastMatchTime
        ) {
            val instant = (target - lastMatchAnchor) / (t - lastMatchTime)
            if (instant.isFinite()) {
                val clamped = instant.coerceIn(config.minRate, config.maxRate)
                rate += config.rateAlpha * (clamped - rate)
            }
        }
        lastMatchTime = t
        lastMatchUtterance = utterance
        lastMatchAnchor = target
        if (jump) outcomes.clear()
        recordOutcome(true)
        state = FollowState(
            anchor = target,
            mode = FollowMode.FOLLOWING,
            tokensPerSec = rate,
            anchorCharEnd = tokens[target].charEnd,
            progress = if (tokens.isEmpty()) 0f else (target + 1).toFloat() / tokens.size,
        )
    }

    /**
     * Track consecutive-match runs across a wide window around the anchor.
     * Returns a re-anchor position once a run of [MatcherConfig.rejoinRun]
     * lands — only for genuine jumps unless [anyPosition] (frozen) is set.
     * Tolerates one skipped script token inside a run and one unmatched
     * spoken token between run words (carried-over runs).
     */
    private fun updateRuns(norm: String, anchor: Int, anyPosition: Boolean): Int {
        val positions = index.positionsByNorm[norm]
        val lo = max(0, anchor - config.rejoinBack)
        val hi = min(tokens.size - 1, anchor + config.rejoinForward)
        val candidates = positions?.filter { it in lo..hi }.orEmpty()

        if (candidates.isEmpty()) {
            // One unmatched token (misrecognition) may interpose without killing runs.
            if (runsCarriedOver) {
                runs.clear()
                runsCarriedOver = false
            } else {
                runsCarriedOver = true
            }
            return -1
        }

        val next = HashMap<Int, Int>(candidates.size * 2)
        var best = -1
        var bestRun = 0
        for (j in candidates) {
            val run = 1 + (runs[j - 1] ?: runs[j - 2] ?: 0)
            next[j] = run
            if (run >= config.rejoinRun) {
                val isJump = j > anchor + config.maxSkipAhead || j < anchor - config.backRepeat
                if ((anyPosition || isJump) &&
                    (run > bestRun || (run == bestRun && abs(j - anchor) < abs(best - anchor)))
                ) {
                    best = j
                    bestRun = run
                }
            }
        }
        runs = next
        runsCarriedOver = false
        return best
    }

    private fun recordOutcome(matched: Boolean) {
        outcomes.addLast(matched)
        while (outcomes.size > config.freezeWindow) outcomes.removeFirst()
    }

    private fun maybeFreeze() {
        if (outcomes.size < config.freezeWindow) return
        val unmatched = outcomes.count { !it }
        if (unmatched >= config.freezeUnmatched) {
            state = state.copy(mode = FollowMode.FROZEN)
        }
    }
}
