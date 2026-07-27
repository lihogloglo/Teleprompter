package dev.metzook.teleprompter.follow

import kotlin.math.abs
import kotlin.math.min

/**
 * Levenshtein-tolerant token equality (SPEC §3.2). Short function words must
 * match exactly — "vous"/"nous" and "les"/"des" are different words, while
 * "cent"/"cents" or "recognise"/"recognize" are the same reading.
 */
internal object Fuzzy {

    fun matches(a: String, b: String): Boolean {
        if (a == b) return true
        val longest = maxOf(a.length, b.length)
        val k = when {
            longest >= 9 -> 2
            longest >= 5 -> 1
            else -> return false
        }
        if (abs(a.length - b.length) > k) return false
        return withinDistance(a, b, k)
    }

    private fun withinDistance(a: String, b: String, k: Int): Boolean {
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > k) return false
            cur.copyInto(prev)
        }
        return prev[b.length] <= k
    }
}
