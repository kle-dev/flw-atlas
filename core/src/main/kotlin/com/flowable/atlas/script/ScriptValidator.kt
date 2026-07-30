package com.flowable.atlas.script

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.inspection.Suggestions

/**
 * A dependency-free structural syntax check over script bodies (Groovy / JavaScript / Python
 * families). It is not a grammar: it lexes comments and string literals — including GString and
 * template-literal `${…}` interpolation, Groovy slashy strings and JS regex literals — and then
 * checks bracket structure over what remains. That is enough to catch the failures that otherwise
 * only surface when the engine runs the script (unterminated literals, unbalanced brackets, a
 * `scriptFormat` typo) while staying silent on anything it is not sure about.
 *
 * A false problem is worse than a missed one, so every heuristic fails toward silence:
 *  - a `/` that could be a slashy string / regex but has no closer on its line is read as division
 *    and never reported (multiline slashy strings are treated as division too — that misses a real
 *    error in the rare multiline regex, but can never invent one in common division code);
 *  - an unknown `scriptFormat` only warns when it is a near-miss of a known engine name
 *    (`grooy` → groovy); a distant name is a legitimate custom JSR-223 engine;
 *  - `juel` bodies are expression territory, unknown languages are not ours to judge, and Python
 *    gets no indentation checks — strings, comments and brackets only.
 */
object ScriptValidator {

    fun validate(
        script: String?, format: String?, formatRequired: Boolean = false,
        context: ScriptContext = ScriptContext.UNKNOWN,
    ): List<ScriptProblem> {
        val problems = ArrayList<ScriptProblem>()
        val raw = format?.trim()
        val fmt = raw?.lowercase()
        if (script.isNullOrBlank()) {
            if (formatRequired) {
                problems += ScriptProblem(0, 0, "Script task has an empty script body",
                    ExprSeverity.WARNING, kind = ScriptProblemKind.EMPTY_BODY)
            }
            return problems
        }
        when {
            fmt.isNullOrEmpty() -> if (formatRequired) {
                problems += ScriptProblem(0, 0,
                    "Script task has no scriptFormat — the engine cannot pick a script engine",
                    ExprSeverity.WARNING, kind = ScriptProblemKind.MISSING_FORMAT)
            }
            fmt !in ScriptLanguages.KNOWN_FORMATS -> {
                Suggestions.closest(fmt, ScriptLanguages.KNOWN_FORMATS)?.let { near ->
                    problems += ScriptProblem(0, 0,
                        "Unknown scriptFormat '$fmt' — did you mean '$near'?",
                        ExprSeverity.WARNING, quickFix = near, kind = ScriptProblemKind.UNKNOWN_FORMAT)
                }
            }
            // JSR-223 engine names are case-sensitive and un-aliased: 'GROOVY'/'Javascript' fail at
            // runtime with "Can't find scripting engine". Only fires on a case variant of a known
            // name — an unusual all-lowercase engine name is left alone.
            raw != fmt && raw !in ScriptBindingsCatalog.REGISTERED_ENGINE_NAMES -> {
                problems += ScriptProblem(0, 0,
                    "scriptFormat is case-sensitive: '$raw' is not a registered engine name — use '$fmt'",
                    ExprSeverity.WARNING, quickFix = fmt, kind = ScriptProblemKind.FORMAT_CASE)
            }
        }
        val cfg = when (ScriptLanguages.family(fmt)) {
            ScriptLanguages.Family.GROOVY -> GROOVY
            ScriptLanguages.Family.JS -> JS
            ScriptLanguages.Family.PYTHON -> PYTHON
            else -> null
        }
        if (cfg != null) {
            val scanner = Scanner(script, cfg)
            scanner.run()
            problems += scanner.problems
            checkBrackets(script, scanner.mask, problems)
            checkSemantics(scanner.mask, context, problems)
        }
        return problems.sortedBy { it.startOffset }
    }

    /**
     * [validate] as ready-to-embed dicts `{start, end, line, message, severity, quickFix?, kind,
     * snippet}` — the field vocabulary of the expression problems in the graph payload. `line` is
     * 1-based; `snippet` is the trimmed source line the problem starts on, because the substring of
     * a one-character bracket finding tells a reader nothing.
     */
    fun problemDicts(
        script: String?, format: String?, formatRequired: Boolean = false,
        context: ScriptContext = ScriptContext.UNKNOWN,
    ): List<Map<String, Any?>> =
        validate(script, format, formatRequired, context).map { p ->
            val d = LinkedHashMap<String, Any?>()
            d["start"] = p.startOffset
            d["end"] = p.endOffset
            d["line"] = lineOf(script ?: "", p.startOffset)
            d["message"] = p.message
            d["severity"] = if (p.severity == ExprSeverity.ERROR) "error" else "warning"
            if (p.quickFix != null) d["quickFix"] = p.quickFix
            d["kind"] = p.kind.name.lowercase().replace('_', '-')
            d["snippet"] = snippetAt(script ?: "", p.startOffset)
            d
        }

    // ---- scanner -------------------------------------------------------------------------------

    private class LangCfg(
        val lineComment: String,
        val blockComments: Boolean,
        val tripleQuotes: Boolean,
        val doubleQuoteInterpolates: Boolean,   // Groovy GString
        val templateLiterals: Boolean,          // JS `…`
        val slashyStrings: Boolean,             // Groovy /…/ and $/…/$
        val regexLiterals: Boolean,             // JS /…/ with [] character classes
        val regexKeywords: Set<String>,         // words a regex/slashy may directly follow
    )

    private val GROOVY = LangCfg("//", blockComments = true, tripleQuotes = true,
        doubleQuoteInterpolates = true, templateLiterals = false, slashyStrings = true, regexLiterals = false,
        regexKeywords = setOf("return", "in", "case", "assert", "throw", "if", "while", "else", "do"))

    private val JS = LangCfg("//", blockComments = true, tripleQuotes = false,
        doubleQuoteInterpolates = false, templateLiterals = true, slashyStrings = false, regexLiterals = true,
        regexKeywords = setOf("return", "in", "of", "case", "throw", "if", "while", "else", "do",
            "typeof", "instanceof", "new", "void", "delete", "yield", "await"))

    private val PYTHON = LangCfg("#", blockComments = false, tripleQuotes = true,
        doubleQuoteInterpolates = false, templateLiterals = false, slashyStrings = false, regexLiterals = false,
        regexKeywords = emptySet())

    /** Characters a regex/slashy literal may directly follow; after anything else `/` is division. */
    private const val REGEX_PREV_CHARS = "([{,;:=~!&|?+-*%^<>"

    private sealed interface Frame
    private class StringFrame(val open: Int, val closer: String, val multiline: Boolean, val interpolates: Boolean) : Frame
    private class InterpFrame(val open: Int) : Frame { var depth = 0 }

    /**
     * One pass over the source, producing a same-length [mask] in which comment and string
     * *contents* are blanked (delimiters and `${…}` interpolation code stay visible) plus the
     * lexical [problems] found on the way. All offsets are exact because the mask never shifts.
     */
    private class Scanner(val src: String, val cfg: LangCfg) {
        val mask = src.toCharArray()
        val problems = ArrayList<ScriptProblem>()
        private val stack = ArrayDeque<Frame>()
        private var i = 0

        fun run() {
            while (i < src.length) {
                when (val top = stack.lastOrNull()) {
                    is StringFrame -> stringChar(top)
                    is InterpFrame -> codeChar(top)
                    null -> codeChar(null)
                }
            }
            // EOF with open frames: one finding at the innermost opener, then blank its tail so the
            // bracket pass does not re-report the same breakage.
            when (val top = stack.lastOrNull()) {
                is StringFrame -> {
                    problems += ScriptProblem(top.open, minOf(top.open + top.closer.length, src.length),
                        "Unterminated ${describe(top)}", ExprSeverity.ERROR, kind = ScriptProblemKind.UNTERMINATED_STRING)
                    blank(top.open, src.length)
                }
                is InterpFrame -> {
                    problems += ScriptProblem(top.open, top.open + 2,
                        "'\${' interpolation is never closed", ExprSeverity.ERROR, kind = ScriptProblemKind.UNCLOSED_INTERPOLATION)
                    blank(top.open, src.length)
                }
                null -> {}
            }
        }

        private fun stringChar(f: StringFrame) {
            val c = src[i]
            when {
                // An escape blanks both chars; `\` before a newline in a single-line string is read
                // as a continuation — lenient (misses a real error, never invents one).
                c == '\\' -> { blank(i, minOf(i + 2, src.length)); i = minOf(i + 2, src.length) }
                src.startsWith(f.closer, i) -> { stack.removeLast(); i += f.closer.length }
                f.interpolates && c == '$' && at(i + 1) == '{' -> { stack.addLast(InterpFrame(i)); i += 2 }
                c == '\n' && !f.multiline -> {
                    problems += ScriptProblem(f.open, f.open + f.closer.length,
                        "Unterminated ${describe(f)}", ExprSeverity.ERROR, kind = ScriptProblemKind.UNTERMINATED_STRING)
                    stack.removeLast()
                    i++
                }
                else -> { blank(i, i + 1); i++ }
            }
        }

        private fun codeChar(frame: InterpFrame?) {
            val c = src[i]
            when {
                src.startsWith(cfg.lineComment, i) -> {
                    val e = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    blank(i, e); i = e
                }
                cfg.blockComments && c == '/' && at(i + 1) == '*' -> {
                    val e = src.indexOf("*/", i + 2)
                    if (e < 0) {
                        problems += ScriptProblem(i, i + 2, "Unterminated block comment",
                            ExprSeverity.ERROR, kind = ScriptProblemKind.UNTERMINATED_COMMENT)
                        blank(i, src.length); i = src.length
                    } else { blank(i, e + 2); i = e + 2 }
                }
                cfg.tripleQuotes && (c == '\'' || c == '"') && at(i + 1) == c && at(i + 2) == c -> {
                    stack.addLast(StringFrame(i, "$c$c$c", multiline = true,
                        interpolates = cfg.doubleQuoteInterpolates && c == '"'))
                    i += 3
                }
                c == '\'' || c == '"' -> {
                    stack.addLast(StringFrame(i, c.toString(), multiline = false,
                        interpolates = cfg.doubleQuoteInterpolates && c == '"'))
                    i++
                }
                cfg.templateLiterals && c == '`' -> {
                    stack.addLast(StringFrame(i, "`", multiline = true, interpolates = true))
                    i++
                }
                cfg.slashyStrings && c == '$' && at(i + 1) == '/' && regexContext(i) -> {
                    val e = findDollarSlashyEnd(i + 2)
                    if (e < 0) i++                              // plain '$' after all — rewind
                    else { blank(i + 2, e); i = e + 2 }
                }
                (cfg.slashyStrings || cfg.regexLiterals) && c == '/' && at(i + 1) != '=' && regexContext(i) -> {
                    val e = findRegexEnd(i + 1)
                    if (e < 0) i++                              // division — rewind, never a finding
                    else { blank(i + 1, e); i = e + 1 }
                }
                frame != null && c == '{' -> { frame.depth++; i++ }
                frame != null && c == '}' -> { if (frame.depth == 0) stack.removeLast() else frame.depth--; i++ }
                else -> i++
            }
        }

        /**
         * Whether a `/` (or `$/`) at [pos] sits where a regex/slashy literal is allowed: at the very
         * start, after an opener/operator/separator, or right after a keyword. After an identifier,
         * number, `)`, `]` or a closing string delimiter it is division. `x++ / 2` stays division.
         */
        private fun regexContext(pos: Int): Boolean {
            var j = pos - 1
            while (j >= 0 && mask[j].isWhitespace()) j--
            if (j < 0) return true
            val p = mask[j]
            if ((p == '+' || p == '-') && j > 0 && mask[j - 1] == p) return false
            if (p in REGEX_PREV_CHARS) return true
            if (p.isLetter() || p == '_') {
                var k = j
                while (k > 0 && (mask[k - 1].isLetterOrDigit() || mask[k - 1] == '_')) k--
                return mask.concatToString(k, j + 1) in cfg.regexKeywords
            }
            return false
        }

        /**
         * The closing `/` of a regex/slashy literal opened just before [from], or -1. Same-line
         * only: a `/` whose closer would sit on another line is far more likely division split
         * across lines than a multiline slashy string, and -1 always means silence.
         */
        private fun findRegexEnd(from: Int): Int {
            var j = from
            var inClass = false
            while (j < src.length) {
                when (src[j]) {
                    '\\' -> j++
                    '\n' -> return -1
                    '[' -> if (cfg.regexLiterals) inClass = true
                    ']' -> if (cfg.regexLiterals) inClass = false
                    '$' -> if (cfg.slashyStrings && at(j + 1) == '{') {   // slashy interpolation: skip ${…}
                        var depth = 1
                        var k = j + 2
                        while (k < src.length && depth > 0 && src[k] != '\n') {
                            when (src[k]) { '{' -> depth++; '}' -> depth--; '\\' -> k++ }
                            k++
                        }
                        if (depth > 0) return -1
                        j = k - 1
                    }
                    '/' -> if (!inClass) return j
                    else -> {}
                }
                j++
            }
            return -1
        }

        /** The `/` of the closing `/$` of a dollar-slashy string, or -1. `$$` and `$/` escape. */
        private fun findDollarSlashyEnd(from: Int): Int {
            var j = from
            while (j + 1 < src.length) {
                when {
                    src[j] == '/' && src[j + 1] == '$' -> return j
                    src[j] == '$' && (src[j + 1] == '$' || src[j + 1] == '/') -> j += 2
                    else -> j++
                }
            }
            return -1
        }

        private fun describe(f: StringFrame): String = when {
            f.closer.length == 3 -> "triple-quoted string literal"
            f.closer == "`" -> "template literal"
            else -> "string literal"
        }

        private fun at(j: Int): Char = if (j < src.length) src[j] else ' '

        private fun blank(from: Int, to: Int) {
            for (j in from until minOf(to, mask.size)) if (mask[j] != '\n') mask[j] = ' '
        }
    }

    // ---- semantic pass (bindings catalog) ---------------------------------------------------------

    /** `root.member(` / `root?.member(` / `root.sub.member(` — the calls the catalog can judge. */
    private val MEMBER_CALL_RE =
        Regex("""(?<![.\w])([A-Za-z_]\w*)\s*\??\.\s*([A-Za-z_]\w*)(?:\s*\??\.\s*([A-Za-z_]\w*))?\s*\(""")

    private val ROOT_USE_RE =
        Regex("""(?<![.\w])(execution|task|planItemInstance|caseInstance)\s*\??\.""")

    private val EL_ONLY_RE =
        Regex("""(?<![.\w])flw\s*\??\.\s*(base64|io|array|data|secret)\b""")

    /** A member-typo is only a typo within the classic edit distance — anything farther is assumed
     *  to be dynamic Groovy / a project extension and stays silent. */
    private const val MEMBER_TYPO_DISTANCE = 2

    /**
     * Semantic findings over the mask (strings/comments already blanked): member typos on the
     * context's known roots, scope roots used in a context that does not bind them, and `flw.*`
     * namespaces that exist only in EL. Every finding is a WARNING — bare names can always be
     * process variables or Spring beans, so silence wins every undecidable case.
     */
    private fun checkSemantics(mask: CharArray, context: ScriptContext, out: MutableList<ScriptProblem>) {
        val masked = mask.concatToString()
        val roots = ScriptBindingsCatalog.rootsFor(context)

        for (m in MEMBER_CALL_RE.findAll(masked)) {
            val rootName = m.groupValues[1]
            val root = roots[rootName] ?: continue
            if (locallyBound(masked, rootName)) continue
            val first = m.groupValues[2]
            val second = m.groupValues[3]
            when {
                second.isEmpty() -> checkMember(root, first, m.groups[2]!!.range, out)
                else -> {
                    val sub = root.subObjects[first]
                    if (sub != null) checkMember(sub, second, m.groups[3]!!.range, out)
                    else checkMember(root, first, m.groups[2]!!.range, out)
                }
            }
        }

        if (context != ScriptContext.UNKNOWN) {
            val bound = ScriptBindingsCatalog.scopeRootsFor(context)
            val reported = HashSet<String>()
            for (m in ROOT_USE_RE.findAll(masked)) {
                val name = m.groupValues[1]
                if (name in bound || !reported.add(name) || locallyBound(masked, name)) continue
                val r = m.groups[1]!!.range
                out += ScriptProblem(r.first, r.last + 1,
                    ScriptBindingsCatalog.wrongContextMessage(name, context),
                    ExprSeverity.WARNING, kind = ScriptProblemKind.WRONG_CONTEXT_ROOT)
            }
        }

        val elReported = HashSet<String>()
        for (m in EL_ONLY_RE.findAll(masked)) {
            val name = m.groupValues[1]
            if (!elReported.add(name)) continue
            val r = m.groups[1]!!.range
            out += ScriptProblem(r.first, r.last + 1,
                "'flw.$name' exists only in \${…} expressions — the script flw API has no '$name'",
                ExprSeverity.WARNING, kind = ScriptProblemKind.EL_ONLY_API)
        }
    }

    private fun checkMember(obj: ScriptRoot, name: String, range: IntRange, out: MutableList<ScriptProblem>) {
        val members = obj.members ?: return
        if (members.containsKey(name) || name in obj.subObjects) return
        val near = Suggestions.closest(name, members.keys + obj.subObjects.keys) ?: return
        if (Suggestions.levenshtein(name, near) > MEMBER_TYPO_DISTANCE) return
        out += ScriptProblem(range.first, range.last + 1,
            "'${obj.name}' has no member '$name' — did you mean '$near'?",
            ExprSeverity.WARNING, quickFix = near, kind = ScriptProblemKind.UNKNOWN_MEMBER)
    }

    /** True when the script itself (re)binds [name] — a declaration or an assignment shadows the
     *  engine binding, so the catalog must stand down for it. */
    private fun locallyBound(masked: String, name: String): Boolean =
        Regex("""\b(?:def|var|let|const|final)\s+$name\b""").containsMatchIn(masked) ||
            Regex("""\b[A-Z]\w*(?:<[^>\n]{0,60}>)?\s+$name\s*=""").containsMatchIn(masked) ||
            Regex("""(?<![.\w])$name\s*=[^=~]""").containsMatchIn(masked)

    /** The finding a parser attaches to a CMMN lifecycle listener that declares a script: the
     *  engine's listener factory has no script branch, so such a listener silently does nothing. */
    fun unsupportedLifecycleListenerDicts(): List<Map<String, Any?>> = listOf(linkedMapOf<String, Any?>(
        "start" to 0, "end" to 0, "line" to 1,
        "message" to "CMMN lifecycle listeners do not support scripts — this listener will silently do nothing " +
            "(use class, expression or delegateExpression)",
        "severity" to "warning",
        "kind" to "unsupported-script-listener",
        "snippet" to "",
    ))

    // ---- bracket pass ---------------------------------------------------------------------------

    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    /** Bracket balance over the mask; stops at the first finding so one typo is one problem. */
    private fun checkBrackets(src: String, mask: CharArray, out: MutableList<ScriptProblem>) {
        val stack = ArrayDeque<Pair<Char, Int>>()
        for (j in mask.indices) {
            val c = mask[j]
            if (OPENERS.indexOf(c) >= 0) { stack.addLast(c to j); continue }
            val ci = CLOSERS.indexOf(c)
            if (ci < 0) continue
            val top = stack.lastOrNull()
            if (top == null) {
                out += ScriptProblem(j, j + 1, "Unmatched '$c'",
                    ExprSeverity.ERROR, kind = ScriptProblemKind.UNMATCHED_CLOSER)
                return
            }
            if (OPENERS.indexOf(top.first) != ci) {
                out += ScriptProblem(j, j + 1,
                    "Found '$c' but '${top.first}' opened at line ${lineOf(src, top.second)} is still open",
                    ExprSeverity.ERROR, kind = ScriptProblemKind.MISMATCHED_CLOSER)
                return
            }
            stack.removeLast()
        }
        stack.lastOrNull()?.let { (c, open) ->
            out += ScriptProblem(open, open + 1, "'$c' is never closed",
                ExprSeverity.ERROR, kind = ScriptProblemKind.UNCLOSED_OPENER)
        }
    }

    // ---- source helpers -------------------------------------------------------------------------

    private fun lineOf(src: String, offset: Int): Int {
        var line = 1
        for (j in 0 until minOf(offset, src.length)) if (src[j] == '\n') line++
        return line
    }

    private fun snippetAt(src: String, offset: Int): String {
        if (src.isEmpty()) return ""
        val at = minOf(offset, src.length - 1)
        val start = src.lastIndexOf('\n', at) + 1
        val end = src.indexOf('\n', at).let { if (it < 0) src.length else it }
        return src.substring(start, end).trim().take(120)
    }
}
