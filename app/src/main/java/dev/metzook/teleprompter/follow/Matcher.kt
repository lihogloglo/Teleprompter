package dev.metzook.teleprompter.follow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class FollowMode {
    /** Anchor tracks speech; scroll follows. */
    FOLLOWING,

    /** Sustained off-script speech: hold position until a rejoin run lands. */
    FROZEN,

    /**
     * Frozen for a long time: the anchor is stale, so the rejoin search widens
     * to the whole script — reading any line aloud recovers, as do the jump
     * keys. UI pulses the marker.
     */
    LOST,
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
    /**
     * Near window while frozen: how far ahead of the anchor a rejoin run may
     * start on a fuzzy match (spurious-freeze healing).
     */
    val maxSkipAhead: Int = 10,
    /**
     * Contiguous reading tolerance: a single matched token may advance the
     * anchor across at most this many skipped script tokens. Anything farther
     * is a *jump* and needs a multi-token run — single words match ahead by
     * coincidence and would drag the anchor during improv (evidence gating;
     * docs/voice-follow-research.md: PromptSmart requires 4 words, most
     * implementations 2+ before moving position at all).
     */
    val smallSkip: Int = 2,
    /** A run may only re-anchor while FOLLOWING if it contains a word this long. */
    val contentLen: Int = 4,
    /** Re-said recent tokens (stutter/emphasis) count as on-script, no advance. */
    val backRepeat: Int = 2,
    /** Consecutive matched tokens required for a far rejoin/retake jump (SPEC N≈3–4). */
    val rejoinRun: Int = 3,
    /** Within this distance of the anchor a run of [nearRejoinRun] suffices —
     *  cheap if wrong, and it heals spurious freezes fast. */
    val nearRejoinDistance: Int = 10,
    val nearRejoinRun: Int = 2,
    /** Rejoin search window around the anchor, in tokens (SPEC ±150). */
    val rejoinBack: Int = 250,
    val rejoinForward: Int = 250,
    /**
     * Freeze when ≥ [freezeUnmatched] of the last [freezeWindow] tokens missed.
     * Deliberately demanding: with a distant mic 30–40% of words go missing or
     * get misheard, and a spurious freeze is far worse than a late one.
     */
    val freezeWindow: Int = 12,
    val freezeUnmatched: Int = 10,
    /** Unmatched tokens while frozen before escalating to LOST (whole-script rejoin). */
    val lostAfterUnmatched: Int = 20,
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

    /**
     * Token range currently visible on screen, kept up to date by the UI.
     * A reader can only speak words they can see, so every search — the
     * forward scan and the rejoin run tracker, including LOST recovery —
     * stays inside this range; an off-screen match is always a coincidence
     * (a duplicated phrase), never the reader. Null = unconstrained
     * (tests, or before the first layout).
     */
    var visibleTokens: IntRange? = null

    private var currentUtterance = -1
    private var consumedWords = 0

    // Sliding window of recent token outcomes (true = matched on-script).
    private val outcomes = ArrayDeque<Boolean>()

    // Rate estimation: last anchor advance within the current utterance.
    private var lastMatchTime = -1f
    private var lastMatchUtterance = -1
    private var lastMatchAnchor = -1
    private var rate = config.initialRate

    // Rejoin run tracker: script position → the consecutive run ending there.
    // [Run.content] = the run contains a content word (≥ contentLen chars);
    // while FOLLOWING, only such runs may re-anchor — runs of pure function
    // words ("and then the") complete by coincidence during improv.
    private data class Run(val len: Int, val content: Boolean)

    private var runs = HashMap<Int, Run>()
    private var runsCarriedOver = false

    // Unmatched tokens since the freeze began; escalates FROZEN → LOST.
    private var frozenMisses = 0

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
        frozenMisses = 0
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
        val frozen = state.mode != FollowMode.FOLLOWING

        // Always feed the run tracker; while frozen it is the only way back.
        val runTarget = updateRuns(norm, anchor, anyPosition = frozen)

        if (frozen) {
            if (runTarget >= 0) {
                applyMatch(runTarget, t, utterance, jump = true)
            } else {
                recordOutcome(false)
                frozenMisses++
                if (state.mode == FollowMode.FROZEN &&
                    frozenMisses >= config.lostAfterUnmatched
                ) {
                    state = state.copy(mode = FollowMode.LOST)
                }
            }
            return
        }

        // Greedy forward scan, contiguous reading only: a single token may
        // advance the anchor across at most [MatcherConfig.smallSkip] script
        // tokens. Longer skips go through the run tracker (2+ tokens of
        // evidence) — single-word skips were how improv dragged the anchor.
        var forward = -1
        var from = anchor + 1
        var to = min(tokens.size - 1, anchor + 1 + config.smallSkip)
        visibleTokens?.let { vis ->
            from = max(from, vis.first)
            to = min(to, vis.last)
        }
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

        // A forward match outranks a run jump: while the reading matches at the
        // anchor, a completed run elsewhere is a duplicated phrase, not a jump.
        // Genuine retakes/skips fail the forward scan, so the run still fires.
        when {
            forward >= 0 -> applyMatch(forward, t, utterance, jump = false)
            runTarget >= 0 -> applyMatch(runTarget, t, utterance, jump = true)
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
        frozenMisses = 0
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
     * Returns a re-anchor position once a long-enough run lands — near the
     * anchor [MatcherConfig.nearRejoinRun] suffices, farther jumps need
     * [MatcherConfig.rejoinRun]; only genuine jumps unless [anyPosition]
     * (frozen) is set. Distant-mic tolerance: runs start on exact matches but
     * *continue* through fuzzy ones, may skip one script token, survive one
     * unmatched spoken token (carried-over runs), and while frozen may even
     * start on a fuzzy match near the anchor — so a spurious freeze heals
     * within a couple of words of continued reading.
     */
    private fun updateRuns(norm: String, anchor: Int, anyPosition: Boolean): Int {
        // Lost = the anchor means nothing anymore: search everything the
        // reader can currently see (the whole script when unconstrained).
        val lost = state.mode == FollowMode.LOST
        var lo = if (lost) 0 else max(0, anchor - config.rejoinBack)
        var hi = if (lost) tokens.size - 1 else min(tokens.size - 1, anchor + config.rejoinForward)
        visibleTokens?.let { vis ->
            lo = max(lo, vis.first)
            hi = min(hi, vis.last)
        }

        val candidates = HashSet<Int>()
        index.positionsByNorm[norm]?.forEach { if (it in lo..hi) candidates += it }
        for (p in runs.keys) {
            for (jn in (p + 1)..(p + 2)) {
                if (jn in lo..hi && jn !in candidates && Fuzzy.matches(tokens[jn].norm, norm)) {
                    candidates += jn
                }
            }
        }
        if (anyPosition) {
            val nearLo = max(lo, anchor - 4)
            val nearHi = min(hi, anchor + config.maxSkipAhead)
            for (j in nearLo..nearHi) {
                if (j !in candidates && Fuzzy.matches(tokens[j].norm, norm)) candidates += j
            }
        }

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

        val next = HashMap<Int, Run>(candidates.size * 2)
        var best = -1
        var bestRun = 0
        for (j in candidates) {
            val prev = runs[j - 1] ?: runs[j - 2]
            val run = 1 + (prev?.len ?: 0)
            val content = (prev?.content ?: false) || norm.length >= config.contentLen
            next[j] = Run(run, content)
            val needed =
                if (abs(j - anchor) <= config.nearRejoinDistance) config.nearRejoinRun
                else config.rejoinRun
            if (run >= needed) {
                val isJump = j > anchor + config.smallSkip || j < anchor - config.backRepeat
                // While FOLLOWING, a jump must be anchored by a content word;
                // frozen/lost rejoins stay cheap (healing beats caution there).
                if ((anyPosition || (isJump && content)) &&
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
        if (state.mode != FollowMode.FOLLOWING) return
        if (outcomes.size < config.freezeWindow) return
        val unmatched = outcomes.count { !it }
        if (unmatched >= config.freezeUnmatched) {
            frozenMisses = 0
            state = state.copy(mode = FollowMode.FROZEN)
        }
    }
}
