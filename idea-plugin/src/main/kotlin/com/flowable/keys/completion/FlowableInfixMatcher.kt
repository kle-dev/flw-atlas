package com.flowable.keys.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher

/**
 * A prefix matcher that additionally matches on any **infix** (substring) of a candidate, so a
 * mid-key fragment like `0061` matches the model key `KYC-DO-0061` (or `DO-0061`, or a name word).
 *
 * Flowable keys are structured, not camelCase (`KYC-DO-0061`, `DEMO_P001`), so the platform's
 * default camel-hump matching does not reach a segment in the middle. This matcher keeps camel-hump
 * matching (start / word-boundary matches still rank highest via [matchingDegree]) and falls back to
 * a case-insensitive `contains` so any fragment finds its key.
 */
class FlowableInfixMatcher(prefix: String) : PrefixMatcher(prefix) {

    private val camel = CamelHumpMatcher(prefix, false)

    override fun prefixMatches(name: String): Boolean =
        prefix.isEmpty() || camel.prefixMatches(name) || name.contains(prefix, ignoreCase = true)

    /** Rank camel-hump / start matches above pure infix matches. */
    override fun matchingDegree(string: String): Int = camel.matchingDegree(string)

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = FlowableInfixMatcher(prefix)
}
