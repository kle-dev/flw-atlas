package com.flowable.atlas.expr

import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.ExpressionLexer
import com.flowable.atlas.expr.lang.Tok
import com.flowable.atlas.expr.lang.TokType
import com.flowable.atlas.expr.parse.ExprParser
import com.flowable.atlas.inspection.Suggestions

enum class ExprSeverity { ERROR, WARNING }

/** What category of finding a problem is — inspections use this for allowlist matching. */
enum class ExprProblemKind {
    /** Structural syntax error (unbalanced parens, unterminated string, …). Never suppressible. */
    SYNTAX,
    /** Valid syntax used in the wrong dialect (`|>` in backend, `ns:fn()` in frontend). */
    DIALECT_MISUSE,
    /** `ns:fn()` with a namespace the catalog does not know. */
    UNKNOWN_NAMESPACE,
    /** `ns:fn()` / `flw.member` where the namespace is known but the function is not. */
    UNKNOWN_FUNCTION,
    /** Codebase grounding: a root identifier that is no known variable/bean/root. */
    UNKNOWN_ROOT,
}

/** A problem found in an expression body; offsets are relative to the body text passed to [ExpressionValidator.validate]. */
data class ExprProblem(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val severity: ExprSeverity,
    /** If set, the flagged range can be replaced with this text via a "did you mean" quick fix. */
    val quickFix: String? = null,
    val kind: ExprProblemKind = ExprProblemKind.SYNTAX,
    /** The catalog subject of the finding — `ns`, `ns:fn` or `flw.member` — for allowlisting. */
    val subject: String? = null,
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

    fun validate(body: String, dialect: ExpressionDialect, custom: CustomFunctionCatalog? = null): List<ExprProblem> =
        (validateSyntax(body, dialect) + validateSemantics(body, dialect, custom)).sortedBy { it.startOffset }

    /**
     * Layer 1 — structural syntax only, via the real per-dialect parser (single source of truth for
     * what is a syntax error). These are grammar facts, never configuration: the annotator reports
     * them unconditionally (also inside the playground's [com.intellij.ui.LanguageTextField], where
     * inspections do not run).
     */
    fun validateSyntax(body: String, dialect: ExpressionDialect): List<ExprProblem> {
        // Be forgiving in the playground: the field wants the expression *body*, but users naturally
        // type the wrapper too (`{{ … }}` / `${ … }`). Validate the inner part when a full wrapper is
        // present. Injected fragments are already delimiter-free, so this never strips them.
        val (inner, shift) = stripOuterWrapper(body)
        // An empty interpolation (`{{}}`, `${}`) or blank body is a runtime no-op — the frontend
        // silently ignores an expression that yields nothing — so there is nothing to flag.
        if (inner.isBlank()) return emptyList()
        val problems = ArrayList<ExprProblem>()
        ExprParser.parse(inner, dialect).error?.let {
            problems += ExprProblem(it.start, it.end, it.message, ExprSeverity.ERROR)
        }
        return shifted(problems, shift)
    }

    /**
     * Layers 2+3 — semantic, token-based findings: dialect-only operators and the function catalog.
     * Reported through the `localInspection`s so severity, enablement, scopes and the project
     * allowlist are user-controllable (the hand-maintained catalog can lag behind a project's
     * custom JUEL functions).
     */
    fun validateSemantics(body: String, dialect: ExpressionDialect, custom: CustomFunctionCatalog? = null): List<ExprProblem> {
        val (inner, shift) = stripOuterWrapper(body)
        if (inner.isBlank()) return emptyList()
        val toks = ExpressionLexer.tokenize(inner)
        val problems = ArrayList<ExprProblem>()
        checkDialectOperators(toks, dialect, problems)
        checkFunctions(toks, dialect, problems, custom)
        return shifted(problems, shift)
    }

    private fun shifted(problems: List<ExprProblem>, shift: Int): List<ExprProblem> =
        if (shift == 0) problems
        else problems.map { it.copy(startOffset = it.startOffset + shift, endOffset = it.endOffset + shift) }

    private fun stripOuterWrapper(body: String): Pair<String, Int> = ExprWrappers.stripOuter(body)

    private fun checkDialectOperators(toks: List<Tok>, dialect: ExpressionDialect, out: MutableList<ExprProblem>) {
        if (dialect == ExpressionDialect.BACKEND) {
            for (t in toks) if (t.type == TokType.PIPE) {
                out += warning(t, "'|>' is a frontend-only pipe operator", kind = ExprProblemKind.DIALECT_MISUSE)
            }
        }
    }

    private fun checkFunctions(toks: List<Tok>, dialect: ExpressionDialect, out: MutableList<ExprProblem>,
                               custom: CustomFunctionCatalog? = null) {
        val customFlw = custom?.flw ?: emptySet()
        val customNs = custom?.namespaces ?: emptyMap()
        for (i in toks.indices) {
            // Custom namespace call: <ns> '.' IDENT '(' where <ns> was registered via
            // externals.additionalData (e.g. `flowkyc.findCommonAttribute(x)`). We know the exact
            // member set, so an unknown member with a near-match is a real typo → suspect.
            if (dialect == ExpressionDialect.FRONTEND && toks[i].type == TokType.IDENT &&
                customNs.containsKey(toks[i].text) &&
                toks.getOrNull(i + 1)?.type == TokType.DOT &&
                toks.getOrNull(i + 2)?.type == TokType.IDENT &&
                toks.getOrNull(i + 3)?.type == TokType.LPAREN
            ) {
                val ns = toks[i].text
                val member = toks[i + 2]
                val members = customNs.getValue(ns)
                if (member.text !in members) {
                    val suggestion = Suggestions.closest(member.text, members)
                    if (suggestion != null) {
                        out += warning(member, "Unknown function '$ns.${member.text}'${hint(suggestion)}", suggestion,
                            kind = ExprProblemKind.UNKNOWN_FUNCTION, subject = "$ns.${member.text}")
                    }
                }
                continue
            }
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
                            "'${prefix.text}:${name.text}' is backend function syntax; frontend expressions use flw.${name.text}(…)",
                            kind = ExprProblemKind.DIALECT_MISUSE)
                    }
                    continue
                }
                val canonical = FlowableExpressionCatalog.resolvePrefix(prefix.text)
                if (canonical == null) {
                    val suggestion = Suggestions.closest(prefix.text, FlowableExpressionCatalog.backendPrefixes())
                    out += warning(prefix, "Unknown function namespace '${prefix.text}'${hint(suggestion)}", suggestion,
                        kind = ExprProblemKind.UNKNOWN_NAMESPACE, subject = prefix.text)
                } else if (!FlowableExpressionCatalog.isBackendFunction(prefix.text, name.text)) {
                    val names = FlowableExpressionCatalog.backendFunctionsForPrefix(prefix.text).flatMap { it.allNames }
                    val suggestion = Suggestions.closest(name.text, names)
                    out += warning(name, "Unknown function '${prefix.text}:${name.text}'${hint(suggestion)}", suggestion,
                        kind = ExprProblemKind.UNKNOWN_FUNCTION, subject = "${prefix.text}:${name.text}")
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
                if (!FlowableExpressionCatalog.isFrontendMember(member.text) && member.text !in customFlw) {
                    val names = FlowableExpressionCatalog.frontendMembers().map { it.name } + customFlw
                    val suggestion = Suggestions.closest(member.text, names)
                    // A member with no near-match to any known flw function is most likely a *custom*
                    // function a project injected onto `flw` via `flowable.externals.additionalData.flw`
                    // (see useGlobalResolver / hookEvalExpression). If we extracted that source
                    // (customFlw) the name is known and validates cleanly; otherwise it's invisible to
                    // us, so we don't flag it. Only a plausible typo (`flw.sim` → `sum`) is surfaced.
                    if (suggestion != null) {
                        out += warning(member, "Unknown flw function 'flw.${member.text}'${hint(suggestion)}", suggestion,
                            kind = ExprProblemKind.UNKNOWN_FUNCTION, subject = "flw.${member.text}")
                    }
                }
            }
        }
    }

    private fun hint(suggestion: String?): String = suggestion?.let { " — did you mean '$it'?" } ?: ""

    private fun warning(t: Tok, message: String, quickFix: String? = null,
                        kind: ExprProblemKind = ExprProblemKind.SYNTAX, subject: String? = null) =
        ExprProblem(t.start, t.end, message, ExprSeverity.WARNING, quickFix, kind, subject)
    private fun warning(start: Int, end: Int, message: String, quickFix: String? = null,
                        kind: ExprProblemKind = ExprProblemKind.SYNTAX, subject: String? = null) =
        ExprProblem(start, end, message, ExprSeverity.WARNING, quickFix, kind, subject)
}
