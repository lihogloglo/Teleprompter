package dev.metzook.teleprompter.follow

/**
 * Integer → spoken words for the normalizer (SPEC §3.1 "expand numbers").
 * Scripts write "80", speakers say "quatre-vingts"; expanding the script side
 * with the same normalization as the ASR side makes them meet in the middle.
 * Out-of-range values fall through as digits — the matcher tolerates a skip.
 */
internal object Numbers {

    fun expand(digits: String, lang: MatchLang): List<String> {
        val n = digits.toIntOrNull() ?: return listOf(digits)
        if (n > 999_999) return listOf(digits)
        return when (lang) {
            MatchLang.EN -> en(n)
            MatchLang.FR -> fr(n)
        }
    }

    private val EN_UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = listOf(
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    private fun en(n: Int): List<String> = when {
        n < 20 -> listOf(EN_UNITS[n])
        n < 100 ->
            listOf(EN_TENS[n / 10 - 2]) + if (n % 10 > 0) listOf(EN_UNITS[n % 10]) else emptyList()
        n < 1000 ->
            listOf(EN_UNITS[n / 100], "hundred") + if (n % 100 > 0) en(n % 100) else emptyList()
        else ->
            en(n / 1000) + listOf("thousand") + if (n % 1000 > 0) en(n % 1000) else emptyList()
    }

    private val FR_UNITS = listOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
        "dix-sept", "dix-huit", "dix-neuf",
    )

    private fun fr(n: Int): List<String> = when {
        n < 20 -> FR_UNITS[n].split('-')
        n < 100 -> frTens(n)
        n < 1000 -> {
            val hundreds = n / 100
            val head =
                if (hundreds == 1) listOf("cent")
                else fr(hundreds) + listOf(if (n % 100 == 0) "cents" else "cent")
            head + if (n % 100 > 0) fr(n % 100) else emptyList()
        }
        else -> {
            val thousands = n / 1000
            val head = if (thousands == 1) listOf("mille") else fr(thousands) + listOf("mille")
            head + if (n % 1000 > 0) fr(n % 1000) else emptyList()
        }
    }

    private fun frTens(n: Int): List<String> {
        val tens = n / 10
        val unit = n % 10
        return when {
            tens in 2..6 -> {
                val base = listOf("vingt", "trente", "quarante", "cinquante", "soixante")[tens - 2]
                when (unit) {
                    0 -> listOf(base)
                    1 -> listOf(base, "et", "un")
                    else -> listOf(base, FR_UNITS[unit])
                }
            }
            n in 70..79 ->
                listOf("soixante") +
                    if (n == 71) listOf("et", "onze") else FR_UNITS[n - 60].split('-')
            n == 80 -> listOf("quatre", "vingts")
            else -> listOf("quatre", "vingt") + FR_UNITS[n - 80].split('-') // 81–99
        }
    }
}
