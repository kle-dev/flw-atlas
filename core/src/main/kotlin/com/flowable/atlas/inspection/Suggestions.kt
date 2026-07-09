package com.flowable.atlas.inspection

/** "Did you mean …?" helper — the closest candidate by edit distance, if plausibly a typo. */
object Suggestions {

    fun closest(value: String, candidates: Collection<String>): String? {
        val threshold = maxOf(2, value.length / 3)
        return candidates.asSequence()
            .map { it to levenshtein(value, it) }
            .filter { it.second <= threshold }
            .minByOrNull { it.second }
            ?.first
    }

    fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
