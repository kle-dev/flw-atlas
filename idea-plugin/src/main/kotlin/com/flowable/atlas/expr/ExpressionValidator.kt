package com.flowable.atlas.expr

import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.ExpressionLexer
import com.flowable.atlas.expr.lang.Tok
import com.flowable.atlas.expr.lang.TokType
import com.flowable.atlas.expr.parse.ExprParser
import com.flowable.atlas.inspection.Suggestions

enum class ExprSeverity { ERROR, WARNING }

/** A problem found in an expression body; offsets are relative to the body text passed to [ExpressionValidator.validate]. */
data class ExprProblem(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val severity: ExprSeverity,
    /** If set, the flagged range can be replaced with this text via a "did you mean" quick fix. */
    val quickFix: String? = null,
)

/**
 * A dependency-free validator over the token stream of an expression *body* (the text inside the
 * `${…}` / `{{…}}` delimiters). It reports:
 *  - structural syntax errors (unbalanced `()`/`[]`, unterminated strings, stray characters, an
 *    expression that ends on an operator, empty argument slots);
 *  - semantic warnings that are *safe* because the surrounding syntax signals intent — an unknown
 *    function in a known namespace (`date:noww()` → "did you mean 'now'?"), an unknown `flw.*`
 *    member, and dialect misuse (`|>` in a backend expression, `prefix:fn()` in a frontend one).
 *
 * It deliberately does NOT flag unknown bare identifiers as errors — those are almost always process
 * variables or form fields, whose names are open-ended and only known to the index.
 */
object ExpressionValidator {

    fun validate(body: String, dialect: ExpressionDialect): List<ExprProblem> {
        // Be forgiving in the playground: the field wants the expression *body*, but users naturally
        // type the wrapper too (`{{ … }}` / `${ … }`). Validate the inner part when a full wrapper is
        // present. Injected fragments are already delimiter-free, so this never strips them.
        val (inner, shift) = stripOuterWrapper(body)
        // An empty interpolation (`{{}}`, `${}`) or blank body is a runtime no-op — the frontend
        // silently ignores an expression that yields nothing — so there is nothing to flag.
        if (inner.isBlank()) return emptyList()
        val toks = ExpressionLexer.tokenize(inner)
        val problems = ArrayList<ExprProblem>()
        // Layer 1 — structural syntax, via the real per-dialect parser (single source of truth for
        // what is a syntax error). Function existence & dialect fit are separate semantic checks below.
        ExprParser.parse(inner, dialect).error?.let {
            problems += ExprProblem(it.start, it.end, it.message, ExprSeverity.ERROR)
        }
        // Layer 2 — semantic, token-based: dialect-only operators and the function catalog.
        checkDialectOperators(toks, dialect, problems)
        checkFunctions(toks, dialect, problems)
        return problems
            .map { if (shift == 0) it else it.copy(startOffset = it.startOffset + shift, endOffset = it.endOffset + shift) }
            .sortedBy { it.startOffset }
    }

    /** If [body] is entirely wrapped in a `{{ … }}` / `${ … }` / `#{ … }` pair, return the inner text
     *  and its start offset in [body]; otherwise return ([body], 0). */
    private fun stripOuterWrapper(body: String): Pair<String, Int> {
        val start = body.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return body to 0
        val end = body.indexOfLast { !it.isWhitespace() } + 1
        val core = body.substring(start, end)
        for ((open, close) in WRAPPERS) {
            if (core.length >= open.length + close.length && core.startsWith(open) && core.endsWith(close)) {
                val innerStart = start + open.length
                val innerEnd = end - close.length
                return body.substring(innerStart, innerEnd) to innerStart
            }
        }
        return body to 0
    }

    private val WRAPPERS = listOf("{{" to "}}", "\${" to "}", "#{" to "}")

    private fun checkDialectOperators(toks: List<Tok>, dialect: ExpressionDialect, out: MutableList<ExprProblem>) {
        if (dialect == ExpressionDialect.BACKEND) {
            for (t in toks) if (t.type == TokType.PIPE) {
                out += warning(t, "'|>' is a frontend-only pipe operator")
            }
        }
    }

    private fun checkFunctions(toks: List<Tok>, dialect: ExpressionDialect, out: MutableList<ExprProblem>) {
        for (i in toks.indices) {
            // Backend namespaced call: IDENT ':' IDENT '('
            if (toks[i].type == TokType.IDENT &&
                toks.getOrNull(i + 1)?.type == TokType.COLON &&
                toks.getOrNull(i + 2)?.type == TokType.IDENT &&
                toks.getOrNull(i + 3)?.type == TokType.LPAREN
            ) {
                val prefix = toks[i]
                val name = toks[i + 2]
                if (dialect == ExpressionDialect.FRONTEND) {
                    if (FlowableExpressionCatalog.resolvePrefix(prefix.text) != null) {
                        out += warning(prefix.start, name.end,
                            "'${prefix.text}:${name.text}' is backend function syntax; frontend expressions use flw.${name.text}(…)")
                    }
                    continue
                }
                val canonical = FlowableExpressionCatalog.resolvePrefix(prefix.text)
                if (canonical == null) {
                    val suggestion = Suggestions.closest(prefix.text, FlowableExpressionCatalog.backendPrefixes())
                    out += warning(prefix, "Unknown function namespace '${prefix.text}'${hint(suggestion)}", suggestion)
                } else if (!FlowableExpressionCatalog.isBackendFunction(prefix.text, name.text)) {
                    val names = FlowableExpressionCatalog.backendFunctionsForPrefix(prefix.text).flatMap { it.allNames }
                    val suggestion = Suggestions.closest(name.text, names)
                    out += warning(name, "Unknown function '${prefix.text}:${name.text}'${hint(suggestion)}", suggestion)
                }
                continue
            }
            // Frontend member call: flw '.' IDENT
            if (dialect == ExpressionDialect.FRONTEND &&
                toks[i].type == TokType.IDENT && toks[i].text == FlowableExpressionCatalog.FRONTEND_NS &&
                toks.getOrNull(i + 1)?.type == TokType.DOT &&
                toks.getOrNull(i + 2)?.type == TokType.IDENT
            ) {
                val member = toks[i + 2]
                if (!FlowableExpressionCatalog.isFrontendMember(member.text)) {
                    val names = FlowableExpressionCatalog.frontendMembers().map { it.name }
                    val suggestion = Suggestions.closest(member.text, names)
                    out += warning(member, "Unknown flw function 'flw.${member.text}'${hint(suggestion)}", suggestion)
                }
            }
        }
    }

    private fun hint(suggestion: String?): String = suggestion?.let { " — did you mean '$it'?" } ?: ""

    private fun warning(t: Tok, message: String, quickFix: String? = null) =
        ExprProblem(t.start, t.end, message, ExprSeverity.WARNING, quickFix)
    private fun warning(start: Int, end: Int, message: String, quickFix: String? = null) =
        ExprProblem(start, end, message, ExprSeverity.WARNING, quickFix)
}
