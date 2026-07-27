package dev.metzook.teleprompter.follow

import java.text.Normalizer

/** Matching language: drives number expansion; normalization is otherwise shared. */
enum class MatchLang {
    EN, FR;

    companion object {
        fun from(tag: String?): MatchLang =
            if (tag?.trim()?.lowercase()?.startsWith("fr") == true) FR else EN
    }
}

/**
 * Text normalization shared by the script index and the ASR stream (SPEC §3.1).
 * Both sides go through [matchTokens], so any consistent scheme matches itself:
 * elisions (j'ai → j + ai), hyphen compounds (peut-être → peut + etre) and
 * contractions (don't → don + t) split the same way no matter how the
 * recognizer groups them, and diacritics never matter.
 */
object TextNorm {

    private const val APOSTROPHES = "'’‘ʼ`´"

    /** Lowercase, strip diacritics/punctuation, canonicalize apostrophes and hyphens. */
    fun canonical(word: String): String {
        val decomposed = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                ch in APOSTROPHES -> sb.append('\'')
                ch == '-' || ch == '‐' || ch == '‑' -> sb.append('-')
                ch == 'œ' -> sb.append("oe") // œ
                ch == 'æ' -> sb.append("ae") // æ
                else -> Unit // combining marks, punctuation, symbols
            }
        }
        return sb.toString().trim('\'', '-')
    }

    /** One display/ASR word → the normalized token(s) it is matched as. */
    fun matchTokens(word: String, lang: MatchLang): List<String> {
        val c = canonical(word)
        if (c.isEmpty()) return emptyList()
        val out = ArrayList<String>(2)
        for (part in c.split('\'', '-')) {
            when {
                part.isEmpty() -> Unit
                part.all { it in '0'..'9' } ->
                    Numbers.expand(part, lang).forEach { spelled ->
                        // Expansions may carry diacritics/hyphens of their own.
                        if (spelled.any { it.isDigit() }) out += spelled
                        else out += matchTokens(spelled, lang)
                    }
                else -> out += part
            }
        }
        return out
    }
}
