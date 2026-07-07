package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.lang.languageOf
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiLanguageInjectionHost

/** Shared helper: scan a host's value for expression segments and inject each as its dialect language. */
object ExpressionInjectionSupport {

    /**
     * Injects every [dialects] segment found inside [host]'s value text as its own fragment.
     * The value range (delimiters/quotes excluded) is resolved via the element's manipulator, so it
     * works uniformly for XML attribute values / text, JSON string literals and Java string literals.
     */
    fun inject(registrar: MultiHostRegistrar, host: PsiLanguageInjectionHost, dialects: Set<ExpressionDialect>) {
        if (!host.isValidHost) return
        val valueRange = ElementManipulators.getValueTextRange(host)
        if (valueRange.isEmpty) return
        val text = valueRange.substring(host.text)
        for (seg in ExpressionSegmentScanner.scan(text, dialects)) {
            val rangeInHost = TextRange(valueRange.startOffset + seg.innerStart, valueRange.startOffset + seg.innerEnd)
            registrar.startInjecting(languageOf(seg.dialect))
            registrar.addPlace(null, null, host, rangeInHost)
            registrar.doneInjecting()
        }
    }
}
