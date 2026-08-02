package dev.metzook.teleprompter.follow

/**
 * Follow-session trace: the ground truth for debugging "the playhead stopped
 * following" without guessing. While follow-voice is on, the app records
 * every input the matcher saw (words, visible-token window, re-anchors,
 * play state) and every output it produced (state changes), plus periodic
 * scroll snapshots. A pulled trace file replays deterministically against
 * the same script in the JVM test harness — the failure reproduces on the
 * desk, gets fixed, and the trace becomes a regression test.
 *
 * Format: one event per line, tab-separated, a single-char tag first —
 * trivially parseable, greppable, and diff-able. Word texts never contain
 * whitespace (they come from whitespace-split ASR output), so words are
 * encoded as "text@timeSec" joined by spaces.
 *
 * Pure Kotlin: encode and decode both live here so the app and the tests
 * can never drift apart.
 */
object FollowTrace {

    sealed class Event {
        /** Session header: script identity + sizes, for picking the replay script. */
        data class Header(
            val version: Int,
            val app: String,
            val lang: String,
            val tokenCount: Int,
            val scriptId: String,
        ) : Event()

        /** One ASR event exactly as fed to the matcher. */
        data class Words(
            val t: Float,
            val utterance: Int,
            val final: Boolean,
            val words: List<TimedWord>,
        ) : Event()

        /** Visible token range at feed time (-1/-1 = unconstrained). */
        data class Vis(val t: Float, val lo: Int, val hi: Int) : Event()

        /** Matcher state after a feed (deduplicated by the writer). */
        data class State(
            val t: Float,
            val anchor: Int,
            val mode: FollowMode,
            val rate: Float,
        ) : Event()

        /** Manual/remote re-anchor (jump keys, eyeline re-anchor, restart). */
        data class Reanchor(val t: Float, val anchor: Int) : Event()

        /** Play/pause edges. */
        data class Play(val t: Float, val playing: Boolean) : Event()

        /** Words skipped while paused (utterance + word count). */
        data class Skip(val t: Float, val utterance: Int, val count: Int) : Event()

        /**
         * ASR engine health. A stalled playhead is either the matcher ignoring
         * good words or the recognizer having stopped delivering them; only the
         * mic error and the decoder's lag tell those apart after the fact.
         * [decodeMs] near the 100 ms chunk budget means audio is being dropped.
         */
        data class Asr(
            val t: Float,
            val listening: Boolean,
            val decodeMs: Int,
            val error: String?,
        ) : Event()

        /** ~1 Hz motion snapshot: scroll position vs the drive's target. */
        data class Snap(
            val t: Float,
            val scroll: Int,
            val max: Int,
            val target: Float,
            val velocity: Float,
        ) : Event()
    }

    fun encode(e: Event): String = when (e) {
        is Event.Header ->
            "h\t${e.version}\t${e.app}\t${e.lang}\t${e.tokenCount}\t${e.scriptId.sanitized()}"
        is Event.Words ->
            "w\t${e.t.fmt()}\t${e.utterance}\t${if (e.final) "f" else "p"}\t" +
                e.words.joinToString(" ") { "${it.text.wordSafe()}@${it.timeSec.fmt()}" }
        is Event.Vis -> "v\t${e.t.fmt()}\t${e.lo}\t${e.hi}"
        is Event.State -> "s\t${e.t.fmt()}\t${e.anchor}\t${e.mode.name}\t${e.rate.fmt()}"
        is Event.Reanchor -> "r\t${e.t.fmt()}\t${e.anchor}"
        is Event.Play -> "p\t${e.t.fmt()}\t${if (e.playing) 1 else 0}"
        is Event.Skip -> "k\t${e.t.fmt()}\t${e.utterance}\t${e.count}"
        is Event.Asr ->
            "a\t${e.t.fmt()}\t${if (e.listening) 1 else 0}\t${e.decodeMs}\t" +
                (e.error?.sanitized() ?: "")
        is Event.Snap ->
            "n\t${e.t.fmt()}\t${e.scroll}\t${e.max}\t${e.target.fmt()}\t${e.velocity.fmt()}"
    }

    /** Null for blank/unknown/corrupt lines: a trace must survive partial writes. */
    fun decode(line: String): Event? {
        val f = line.split('\t')
        return try {
            when (f[0]) {
                "h" -> Event.Header(f[1].toInt(), f[2], f[3], f[4].toInt(), f[5])
                "w" -> Event.Words(
                    t = f[1].toFloat(),
                    utterance = f[2].toInt(),
                    final = f[3] == "f",
                    words = if (f.size < 5 || f[4].isEmpty()) emptyList() else {
                        f[4].split(' ').map { w ->
                            val at = w.lastIndexOf('@')
                            TimedWord(w.substring(0, at), w.substring(at + 1).toFloat())
                        }
                    },
                )
                "v" -> Event.Vis(f[1].toFloat(), f[2].toInt(), f[3].toInt())
                "s" -> Event.State(
                    f[1].toFloat(), f[2].toInt(), FollowMode.valueOf(f[3]), f[4].toFloat(),
                )
                "r" -> Event.Reanchor(f[1].toFloat(), f[2].toInt())
                "p" -> Event.Play(f[1].toFloat(), f[2] == "1")
                "k" -> Event.Skip(f[1].toFloat(), f[2].toInt(), f[3].toInt())
                "a" -> Event.Asr(
                    t = f[1].toFloat(),
                    listening = f[2] == "1",
                    decodeMs = f[3].toInt(),
                    // A trailing empty field means "no error"; split() keeps it.
                    error = f.getOrNull(4)?.takeIf { it.isNotEmpty() },
                )
                "n" -> Event.Snap(
                    f[1].toFloat(), f[2].toInt(), f[3].toInt(), f[4].toFloat(), f[5].toFloat(),
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String): List<Event> =
        text.lineSequence().mapNotNull { if (it.isBlank()) null else decode(it) }.toList()

    /**
     * Drive a matcher through a trace exactly the way PrompterScreen does:
     * the latest Vis before a feed sets the visible window, Words feed the
     * matcher (respecting play state), Skip and Reanchor apply as recorded.
     * Returns the state after every Words event, for pinpointing where the
     * live session and a fresh matcher diverge.
     */
    fun replay(matcher: Matcher, events: List<Event>): List<Pair<Event.Words, FollowState>> {
        val out = ArrayList<Pair<Event.Words, FollowState>>()
        for (e in events) {
            when (e) {
                is Event.Vis ->
                    matcher.visibleTokens = if (e.lo < 0) null else e.lo..e.hi
                is Event.Words ->
                    out += e to matcher.onWords(e.utterance, e.words, e.final)
                is Event.Skip ->
                    matcher.skip(e.utterance, List(e.count) { TimedWord("", 0f) })
                is Event.Reanchor -> matcher.reanchor(e.anchor)
                else -> Unit
            }
        }
        return out
    }

    // Locale.ROOT: a French device would otherwise format "12,345" and break parsing.
    private fun Float.fmt(): String = "%.3f".format(java.util.Locale.ROOT, this)

    /** Tabs/newlines would corrupt the line format; free text may still hold spaces. */
    private fun String.sanitized(): String = replace('\t', ' ').replace('\n', ' ')

    /**
     * Word texts are space-joined inside one field, so *any* whitespace in a
     * word would silently desync that line's word list. Merging is supposed to
     * make that impossible (TranscriptTest pins it) — this keeps a future
     * vocabulary from corrupting a trace instead of merely looking odd in one.
     */
    private fun String.wordSafe(): String = filterNot { it.isWhitespace() }
}
