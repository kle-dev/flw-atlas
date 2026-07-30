package com.flowable.atlas.script

import com.flowable.atlas.expr.ExprSeverity

/** What category of finding a script problem is. */
enum class ScriptProblemKind {
    // structural (the scanner + bracket pass)
    UNTERMINATED_STRING, UNTERMINATED_COMMENT, UNCLOSED_INTERPOLATION,
    UNMATCHED_CLOSER, MISMATCHED_CLOSER, UNCLOSED_OPENER,
    // configuration
    EMPTY_BODY, MISSING_FORMAT, UNKNOWN_FORMAT, FORMAT_CASE,
    // semantic (the bindings catalog)
    UNKNOWN_MEMBER, WRONG_CONTEXT_ROOT, EL_ONLY_API, UNSUPPORTED_SCRIPT_LISTENER,
}

/**
 * A problem found in a script body; offsets are relative to the script text passed to
 * [ScriptValidator.validate]. Deliberately its own type, not [com.flowable.atlas.expr.ExprProblem]:
 * script findings carry none of the expression catalog's semantics and must never flow through the
 * expression allowlist.
 */
data class ScriptProblem(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val severity: ExprSeverity,
    /** If set, the flagged subject (a scriptFormat typo) can be replaced with this text. */
    val quickFix: String? = null,
    val kind: ScriptProblemKind,
)
