package dev.metzook.teleprompter.follow

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays real kroko decoder output for every page-back phrase through the
 * follow command detector (docs/follow-voice-commands.md, "Measured").
 *
 * Each fixture is the exact event stream AsrEngine would emit (100 ms chunks,
 * same endpoint rules, greedy search) for synthetic clips × clean/noisy:
 * several TTS voices, 3 speeds, the command alone or inside narration, said
 * plainly, with a comma, or as two halves separated by a pause. Plus
 * narration-only clips full of near-miss words ("comment reculer", "au retour,
 * retournez", "back and forth"). Regenerate with tools/voice-command-bench.
 *
 * TTS is not a person and white noise is not a room: these numbers rank
 * detectors against the real model's confusions; they are not field accuracy.
 */
class VoiceCommandBenchTest {

    private class Clip(
        val positive: Boolean,
        val phrase: String,
        val noisy: Boolean,
        val commandEnd: Float,
        val label: String,
    ) {
        val events = ArrayList<Event>()
    }

    private class Event(val atSec: Float, val utterance: Int, val final: Boolean, val words: List<TimedWord>)

    private fun load(lang: MatchLang): List<Clip> {
        val name = "/voice-commands/kroko-${lang.name.lowercase()}-synthetic.tsv.gz"
        val stream = javaClass.getResourceAsStream(name) ?: error("fixture missing: $name")
        val clips = LinkedHashMap<String, Clip>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                val f = line.split('\t')
                when (f[0]) {
                    "C" -> clips[f[1]] = Clip(
                        positive = f[2] == "pos",
                        phrase = f[6].substringBefore('/'),
                        noisy = f[7] == "noisy",
                        commandEnd = f[8].toFloat(),
                        label = f.drop(3).joinToString(" "),
                    )
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

    private class Score(val hitClean: Double, val hitNoisy: Double, val medianLatency: Float) {
        override fun toString() =
            "clean ${pct(hitClean)}, noisy ${pct(hitNoisy)}, median ${"%.2f".format(medianLatency)} s"
    }

    private fun score(positives: List<Clip>, make: () -> (Event) -> Int): Score {
        fun rate(noisy: Boolean): Double {
            val set = positives.filter { it.noisy == noisy }
            return set.count { fires(it, make()).isNotEmpty() }.toDouble() / set.size
        }
        val latencies = positives.mapNotNull { fires(it, make()).firstOrNull() }.sorted()
        return Score(rate(false), rate(true), latencies.getOrElse(latencies.size / 2) { Float.NaN })
    }

    private fun current(lang: MatchLang): () -> (Event) -> Int = {
        val d = FollowVoiceCommands(lang)
        val f: (Event) -> Int = { e -> d.onWords(e.utterance, e.words, e.final).pagesBack }
        f
    }

    private fun legacy(lang: MatchLang): () -> (Event) -> Int = {
        val d = LegacyFollowVoiceCommands(lang)
        val f: (Event) -> Int = { e -> d.onWords(e.utterance, e.words, e.final) }
        f
    }

    private fun report(lang: MatchLang): Map<String, Score> {
        val clips = load(lang)
        val byPhrase = clips.filter { it.positive }.groupBy { it.phrase }
        val now = byPhrase.mapValues { (_, p) -> score(p, current(lang)) }
        val any = score(clips.filter { it.positive }, current(lang))
        val before = score(clips.filter { it.positive }, legacy(lang))
        println("follow command bench, $lang:")
        now.forEach { (phrase, s) -> println("  $phrase: $s") }
        println("  any phrase: $any   (v0.9.4: $before)")

        val falseFires = clips.filter { !it.positive && fires(it, current(lang)()).isNotEmpty() }
        assertEquals("false fires on narration", emptyList<String>(), falseFires.map { it.label })
        val doubled = clips.filter { it.positive && fires(it, current(lang)()).size > 1 }
        assertEquals("one spoken command must page back once", emptyList<String>(), doubled.map { it.label })
        return now
    }

    @Test
    fun fixturesAreComplete() {
        for (lang in MatchLang.entries) {
            val clips = load(lang)
            assertTrue(lang.name, clips.count { it.positive } >= 120)
            assertTrue(lang.name, clips.count { !it.positive } >= 100)
            // A few noisy clips decode to nothing at all; most must carry events.
            val positives = clips.filter { it.positive }
            assertTrue(lang.name, positives.count { it.events.isNotEmpty() } >= positives.size * 0.95)
        }
    }

    @Test
    fun frenchPhrasesFireOnRealDecoderOutputWithoutFalseFires() {
        val s = report(MatchLang.FR)
        // Measured: 76.6% / 54.4% / 1.20 s and 70.0% / 60.0% / 1.52 s.
        floor(s.getValue("commande-recul"), clean = 0.72, noisy = 0.50, latency = 1.3f)
        floor(s.getValue("retour-retour"), clean = 0.65, noisy = 0.55, latency = 1.7f)
    }

    @Test
    fun englishPhrasesFireOnRealDecoderOutputWithoutFalseFires() {
        val s = report(MatchLang.EN)
        // Measured: 74.6% / 58.7% / 1.17 s and 90.4% / 84.9% / 1.46 s.
        floor(s.getValue("command-rewind"), clean = 0.70, noisy = 0.53, latency = 1.3f)
        floor(s.getValue("go-back-go-back"), clean = 0.85, noisy = 0.80, latency = 1.7f)
    }

    private fun floor(s: Score, clean: Double, noisy: Double, latency: Float) {
        assertTrue("clean $s", s.hitClean >= clean)
        assertTrue("noisy $s", s.hitNoisy >= noisy)
        assertTrue("latency $s", s.medianLatency <= latency)
    }

    /** v0.9.4's detector, verbatim in behaviour: exact words, one utterance, waits for a boundary. */
    private class LegacyFollowVoiceCommands(private val lang: MatchLang) {
        private val prefix = if (lang == MatchLang.FR) "commande" else "command"
        private val endings = if (lang == MatchLang.FR) setOf("recul", "recule") else setOf("rewind")
        private var utterance = -1
        private var firedThrough = 0

        fun onWords(id: Int, words: List<TimedWord>, final: Boolean): Int {
            if (id != utterance) {
                utterance = id
                firedThrough = 0
            }
            val norms = words.map { TextNorm.matchTokens(it.text, lang).singleOrNull().orEmpty() }
            val stable = if (final) words.size else (words.size - 1).coerceAtLeast(0)
            var fired = 0
            var i = firedThrough
            while (i + 1 < stable) {
                if (norms[i] == prefix && norms[i + 1] in endings) {
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

    private companion object {
        fun pct(x: Double) = "${(x * 1000).toInt() / 10.0}%"
    }
}
