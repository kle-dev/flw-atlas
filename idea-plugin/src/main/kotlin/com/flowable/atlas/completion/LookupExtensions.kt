package com.flowable.atlas.completion

import com.intellij.codeInsight.lookup.LookupElementBuilder

/** Add several lookup strings at once. */
internal fun LookupElementBuilder.withLookupStrings(strings: Set<String>): LookupElementBuilder {
    var builder = this
    for (s in strings) builder = builder.withLookupString(s)
    return builder
}
