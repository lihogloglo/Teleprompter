package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Word reassembly from recognizer subword pieces.
 *
 * Regression: trace follow-20260802-161621 showed the matcher being fed
 * "Je suis pas f an des l if e ac t u ce s de vi e" for "Je suis pas fan des
 * life astuces de vie" — 44% of its tokens were single characters. The vocab
 * (sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06) does mark word starts
 * with '▁', but sherpa's SymbolTable substitutes an ASCII space for it before
 * the JNI hands the tokens to Kotlin, so the old '▁'-only check missed every
 * boundary, fell back to piece-per-word, and trim() erased the last of the
 * evidence. Both conventions must reassemble identically.
 */
class TranscriptTest {

    private fun texts(pieces: List<String>) =
        mergeBpeTokens(pieces, FloatArray(pieces.size) { it * 0.1f }).map { it.text }

    /** The failing utterance, as the vocabulary actually tokenizes it. */
    private val marked = listOf(
        "▁Je", "▁suis", "▁pas", "▁f", "an", "▁des", "▁l", "if", "e",
        "▁ac", "t", "u", "ce", "s", "▁de", "▁vi", "e",
    )

    /** The same pieces after SymbolTable turns '▁' into ' '. */
    private val spaced = marked.map { if (it.startsWith("▁")) " " + it.substring(1) else it }

    private val expected =
        listOf("Je", "suis", "pas", "fan", "des", "life", "actuces", "de", "vie")

    @Test
    fun `BPE-marked pieces merge into words`() {
        assertEquals(expected, texts(marked))
    }

    @Test
    fun `space-marked pieces merge identically`() {
        assertEquals(expected, texts(spaced))
    }

    @Test
    fun `a word start carries the timestamp of its first piece`() {
        val words = mergeBpeTokens(spaced, FloatArray(spaced.size) { it * 0.1f })
        // "fan" starts at piece index 3 -> 0.3s; "life" at index 6 -> 0.6s.
        assertEquals(0.3f, words[3].timeSec, 1e-6f)
        assertEquals(0.6f, words[5].timeSec, 1e-6f)
    }

    @Test
    fun `a bare word-start marker opens a word without adding text`() {
        // Token id 7 in the French vocab is a standalone "▁".
        assertEquals(listOf("de", "an"), texts(listOf("▁de", "▁", "an")))
        assertEquals(listOf("de", "an"), texts(listOf(" de", " ", "an")))
    }

    @Test
    fun `special tokens never become words`() {
        assertEquals(listOf("de", "vie"), texts(listOf("▁de", "<unk>", "▁vi", "e")))
        assertEquals(listOf("de", "vie"), texts(listOf(" de", "<blk>", " vi", "e")))
    }

    @Test
    fun `punctuation attaches to its word and normalizes away`() {
        assertEquals(listOf("vie,", "Cependant"), texts(listOf(" vi", "e", ",", " C", "e", "p", "end", "ant")))
        // TextNorm strips it again, so the matcher still sees a clean token.
        assertEquals(listOf("vie"), TextNorm.matchTokens("vie,", MatchLang.FR))
    }

    @Test
    fun `character vocabularies without any marker still yield one word per piece`() {
        assertEquals(listOf("你", "好"), texts(listOf("你", "好")))
    }

    @Test
    fun `merged words never contain whitespace`() {
        // FollowTrace encodes words space-separated; an interior space would
        // silently corrupt every trace line it appeared on.
        for (w in mergeBpeTokens(spaced, FloatArray(spaced.size))) {
            assertEquals(w.text, w.text.filterNot { it.isWhitespace() })
        }
    }

    @Test
    fun `empty input is not a crash`() {
        assertEquals(emptyList<String>(), texts(emptyList()))
        assertEquals(emptyList<String>(), texts(listOf("", " ")))
    }
}
