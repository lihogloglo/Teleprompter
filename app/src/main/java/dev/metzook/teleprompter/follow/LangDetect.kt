package dev.metzook.teleprompter.follow

/**
 * Stopword-based script language detection. The wrong recognizer model turns
 * speech into garbage the matcher can never align, so the script itself picks
 * the model: frontmatter `lang:` wins, this detector covers the common case of
 * no frontmatter, and the configured default is only a fallback.
 *
 * Words are canonicalized first (accents stripped, elisions split), so the
 * French list is in canonical form and elision clitics (l', j', qu') count —
 * they are strong French signals. Ambiguous words (on, a, d, s, t, est…)
 * are deliberately in neither list.
 */
object LangDetect {

    private val FR = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "et", "que", "qui",
        "dans", "pour", "pas", "vous", "nous", "je", "tu", "il", "elle", "ce",
        "cette", "ces", "mais", "avec", "sur", "au", "aux", "se", "sa", "son",
        "ses", "ne", "en", "ou", "si", "plus", "tout", "tous", "etre", "avoir",
        "fait", "comme", "par", "sont", "etait", "tres", "deja", "aussi",
        "bien", "meme", "quand", "alors", "donc", "chez", "votre", "notre",
        "l", "j", "qu", "c",
    )

    private val EN = setOf(
        "the", "of", "and", "is", "are", "was", "were", "to", "in", "for",
        "with", "that", "this", "these", "those", "it", "you", "we", "they",
        "he", "she", "i", "my", "your", "our", "their", "not", "but", "at",
        "by", "from", "be", "have", "has", "had", "will", "would", "can",
        "could", "do", "does", "did", "what", "which", "who", "when", "where",
        "how", "all", "there", "about", "into", "than", "then", "them", "his",
        "her", "its", "if", "so", "just", "because",
    )

    /** Best guess for [text]'s language, or null when the signal is ambiguous. */
    fun detect(text: String): MatchLang? {
        var fr = 0
        var en = 0
        for (word in text.split(Regex("\\s+"))) {
            val c = TextNorm.canonical(word)
            if (c.isEmpty()) continue
            for (part in c.split('\'', '-')) {
                if (part in FR) fr++
                if (part in EN) en++
            }
        }
        return when {
            fr > en -> MatchLang.FR
            en > fr -> MatchLang.EN
            else -> null
        }
    }
}
