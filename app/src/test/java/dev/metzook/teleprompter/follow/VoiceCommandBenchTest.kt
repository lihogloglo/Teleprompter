package dev.metzook.teleprompter.follow

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays real kroko-fr decoder output for "Commande recul" through the follow
 * command detector (docs/follow-voice-commands.md, "Measured").
 *
 * The fixture is the exact event stream AsrEngine would emit (100 ms chunks,
 * same endpoint rules, greedy search) for 90 synthetic clips × clean/noisy:
 * 5 French TTS voices, 3 speeds, the command alone or inside narration, said
 * plainly, with a comma, or as two words separated by a pause. Plus 45
 * narration-only clips × 2 full of near-miss words ("comment reculer", "la
 * commande reçue", "avec le recul"). Regenerate with tools/voice-command-bench.
 *
 * TTS is not a person and white noise is not a room: these numbers rank
 * detectors against the real model's confusions; they are not field accuracy.
 */
class VoiceCommandBenchTest {

    private class Clip(val positive: Boolean, val noisy: Boolean, val commandEnd: Float, val label: String) {
        val events = ArrayList<Event>()
    }

    private class Event(val atSec: Float, val utterance: Int, val final: Boolean, val words: List<TimedWord>)

    private fun load(): List<Clip> {
        val stream = javaClass.getResourceAsStream("/voice-commands/kroko-fr-synthetic.tsv.gz")
            ?: error("fixture missing")
        val clips = LinkedHashMap<String, Clip>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                val f = line.split('\t')
                when (f[0]) {
                    "C" -> clips[f[1]] = Clip(f[2] == "pos", f[7] == "noisy", f[8].toFloat(), f.drop(3).joinToString(" "))
                    "E" -> {
                        val texts = f[5].split(' ')
                        val times = f[6].split(' ').map { it.toFloat() }
                        clips.getValue(f[1]).events +=
                            Event(f[2].toFloat(), f[3].toInt(), f[4] == "F", texts.zip(times) { t, s -> TimedWord(t, s) })
                    }
                }
            }
        }
        return clips.values.toList()
    }

    /** Seconds after the command ended at which each fire happened. */
    private fun fires(clip: Clip, detect: (Event) -> Int): List<Float> =
        clip.events.flatMap { e -> List(detect(e)) { e.atSec - clip.commandEnd } }

    private class Score(val hitClean: Double, val hitNoisy: Double, val medianLatency: Float, val falseFires: Int)

    private fun score(clips: List<Clip>, make: () -> (Event) -> Int): Score {
        fun rate(noisy: Boolean): Double {
            val set = clips.filter { it.positive && it.noisy == noisy }
            return set.count { fires(it, make()).isNotEmpty() }.toDouble() / set.size
        }
        val latencies = clips.filter { it.positive }.mapNotNull { fires(it, make()).firstOrNull() }.sorted()
        val falseFires = clips.filter { !it.positive }.sumOf { fires(it, make()).size }
        return Score(rate(false), rate(true), latencies[latencies.size / 2], falseFires)
    }

    private fun current(): (Event) -> Int {
        val d = FollowVoiceCommands(MatchLang.FR)
        return { e -> d.onWords(e.utterance, e.words, e.final).pagesBack }
    }

    private fun legacy(): (Event) -> Int {
        val d = LegacyFollowVoiceCommands()
        return { e -> d.onWords(e.utterance, e.words, e.final) }
    }

    @Test
    fun fixtureIsComplete() {
        val clips = load()
        assertEquals(180, clips.count { it.positive })
        assertEquals(90, clips.count { !it.positive })
        assertTrue(clips.all { it.events.isNotEmpty() || !it.positive })
    }

    @Test
    fun detectsTheRealDecoderOutputWithoutFalseFires() {
        val clips = load()
        val now = score(clips, ::current)
        val before = score(clips, ::legacy)
        println(
            "follow command bench: clean ${pct(before.hitClean)} → ${pct(now.hitClean)}, " +
                "noisy ${pct(before.hitNoisy)} → ${pct(now.hitNoisy)}, " +
                "median latency ${before.medianLatency}s → ${now.medianLatency}s, " +
                "false fires ${before.falseFires} → ${now.falseFires}",
        )
        assertEquals("false fires on narration", 0, now.falseFires)
        val doubled = clips.filter { it.positive && fires(it, current()).size > 1 }.map { it.label }
        assertEquals("one spoken command must page back once", emptyList<String>(), doubled)
        assertTrue("clean hit rate ${pct(now.hitClean)}", now.hitClean >= 0.75)
        assertTrue("noisy hit rate ${pct(now.hitNoisy)}", now.hitNoisy >= 0.45)
        assertTrue("median latency ${now.medianLatency}", now.medianLatency <= 1.2f)
        assertTrue(now.hitClean > before.hitClean + 0.25 && now.hitNoisy > before.hitNoisy + 0.15)
        // Margins leave room for TTS sampling variance: an independent regeneration
        // of the clips measured 49 → 79% clean, 29 → 47% noisy, 1.51 → 1.16 s.
        assertTrue(now.medianLatency < before.medianLatency - 0.3f)
    }

    private fun pct(x: Double) = "${(x * 1000).toInt() / 10.0}%"

    /** v0.9.4's detector, verbatim in behaviour: exact words, one utterance, waits for a boundary. */
    private class LegacyFollowVoiceCommands {
        private var utterance = -1
        private var firedThrough = 0

        fun onWords(id: Int, words: List<TimedWord>, final: Boolean): Int {
            if (id != utterance) {
                utterance = id
                firedThrough = 0
            }
            val norms = words.map { TextNorm.matchTokens(it.text, MatchLang.FR).singleOrNull().orEmpty() }
            val stable = if (final) words.size else (words.size - 1).coerceAtLeast(0)
            var fired = 0
            var i = firedThrough
            while (i + 1 < stable) {
                if (norms[i] == "commande" && norms[i + 1] in setOf("recul", "recule")) {
                    fired++
                    firedThrough = i + 2
                    i += 2
                } else {
                    i++
                }
            }
            return fired
        }
    }
}
