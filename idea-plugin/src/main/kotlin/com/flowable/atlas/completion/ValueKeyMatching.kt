package com.flowable.atlas.completion

import com.flowable.atlas.settings.FlowableAtlasSettings

/**
 * Value-based model-key recognition. When enabled, any Java string literal whose value equals a known
 * model key in the index is treated as a reference to that model — so the diagram gutter icon,
 * navigation, Find Usages and hover work on the literal even when it is NOT at a catalogued Flowable
 * API call site (which is how [SiteMatching] otherwise gates key recognition).
 *
 * Opt-in ([FlowableAtlasSettings.recognizeModelKeysAnywhere], default off): it matches on value alone,
 * so a literal that coincidentally equals a short/common real key would light up too. This object only
 * decides *whether* to attempt a value match and screens out implausibly short values; the actual
 * "is this a key?" test is a plain O(1) index hit (`FlowableIndex.find(value)`) done by each consumer,
 * so the exact set of real model keys is the real filter.
 */
object ValueKeyMatching {

    /** Shortest literal value considered — guards against single-character coincidental matches. */
    private const val MIN_LEN = 2

    fun enabled(): Boolean = FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere

    fun plausible(value: String): Boolean = value.length >= MIN_LEN && value.isNotBlank()
}
