package com.flowable.atlas.expr

import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog

/** What the caret sits at inside an expression body — drives what completion offers. */
sealed interface ExprCompletionContext {
    /** The identifier fragment being typed (may be empty). */
    val prefix: String

    /** Top level (or an argument position): offer roots, functions, and project variables/fields. */
    data class Root(override val prefix: String) : ExprCompletionContext

    /** After a backend namespace + colon (`date:`): offer that namespace's functions. */
    data class AfterNamespace(val namespace: String, override val prefix: String) : ExprCompletionContext

    /** After a `.` (`flw.`, `flw.remove.`): offer the receiver's members. */
    data class AfterDot(val receiver: String, override val prefix: String) : ExprCompletionContext
}

/**
 * Classifies the caret position inside an expression *body* by scanning the raw characters left of
 * the caret — robust against the completion machinery's dummy identifier (which sits to the right of
 * the caret and is therefore never part of the computed [prefix]).
 */
object ExpressionContext {

    fun classify(text: String, caret: Int, dialect: ExpressionDialect): ExprCompletionContext {
        val safeCaret = caret.coerceIn(0, text.length)

        // 1) the identifier fragment immediately left of the caret
        var identStart = safeCaret
        while (identStart > 0 && isIdentPart(text[identStart - 1])) identStart--
        val prefix = text.substring(identStart, safeCaret)

        // 2) the first non-whitespace character left of the fragment
        var k = identStart
        while (k > 0 && text[k - 1].isWhitespace()) k--

        if (k > 0 && text[k - 1] == '.') {
            val receiver = identBefore(text, k - 1)
            return ExprCompletionContext.AfterDot(receiver, prefix)
        }
        if (k > 0 && text[k - 1] == ':' && dialect == ExpressionDialect.BACKEND) {
            val namespace = identBefore(text, k - 1)
            // Only treat as a namespace when it is a known prefix; otherwise it's a ternary ':'.
            if (FlowableExpressionCatalog.resolvePrefix(namespace) != null) {
                return ExprCompletionContext.AfterNamespace(namespace, prefix)
            }
        }
        return ExprCompletionContext.Root(prefix)
    }

    /** The identifier ending exactly at [endExclusive] (i.e. the token just left of a `.`/`:`), or "". */
    private fun identBefore(text: String, endExclusive: Int): String {
        var end = endExclusive
        while (end > 0 && text[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && isIdentPart(text[start - 1])) start--
        return text.substring(start, end)
    }

    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
