package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson

/**
 * Kotlin twin of the Python golden test's `normalize()` (tests/conftest.py): recursively sort object
 * keys and sort every list by its canonical JSON. This removes discovery/hash-order noise so the
 * `:core` extract output can be compared against the same committed `tests/golden/miniproject.graph.json`
 * that the Python suite uses.
 */
object GoldenNormalize {

    fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries
            .sortedBy { it.key.toString() }
            .associateTo(LinkedHashMap()) { it.key.toString() to normalize(it.value) }
        is Collection<*> -> v.map { normalize(it) }.sortedBy { MiniJson.stringify(it) }
        else -> v
    }

    /**
     * Round-trip a value through MiniJson so numbers land as [Double] and maps/lists as the same
     * concrete types the golden file parses into — making structural `==` comparison type-consistent.
     */
    fun canonicalTree(v: Any?): Any? = MiniJson.parse(MiniJson.stringify(normalize(v)))
}
