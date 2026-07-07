package com.flowable.atlas.completion

/** Shared lookup-string helpers so a key/name is findable by key OR by (part of the) name. */
object KeyLookup {

    /**
     * Key/name plus their word tokens. The key is split on its separators too
     * (`KYC-DO-0061` → `KYC`, `DO`, `0061`) so that a segment such as `0061` matches at a word start
     * (and ranks well) even before the infix fallback in [FlowableInfixMatcher] kicks in.
     */
    fun searchTokens(key: String, name: String?): Set<String> {
        val tokens = LinkedHashSet<String>()
        tokens.add(key)
        splitInto(key, tokens)
        if (!name.isNullOrBlank()) {
            tokens.add(name)
            splitInto(name, tokens)
        }
        return tokens
    }

    private fun splitInto(value: String, into: MutableSet<String>) {
        value.split(' ', '-', '_', '.', '/').filter { it.isNotBlank() }.forEach { into.add(it) }
    }
}
