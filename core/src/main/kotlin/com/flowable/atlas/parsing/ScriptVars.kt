package com.flowable.atlas.parsing

/**
 * What variables a script body touches.
 *
 * Flowable puts the surrounding scope's variables straight into a script's binding, so a Groovy/JS
 * script *reads* them as bare identifiers (`emailId`) and *writes* them through the API
 * (`execution.setVariable('x', …)`). Only the API calls were harvested before, which made every read a
 * script performs invisible: searching for the variable found the form that declares it but never the
 * script that consumes it.
 *
 * The two findings are kept apart on purpose. [api] names come from an explicit call and are as good as
 * a declaration; [reads] come from a heuristic over the script's identifiers and are a good guess, which
 * is how callers must present them.
 */
data class ScriptVarUse(val api: Set<String>, val reads: Set<String>) {
    val isEmpty: Boolean get() = api.isEmpty() && reads.isEmpty()

    companion object {
        val EMPTY = ScriptVarUse(emptySet(), emptySet())
    }
}

object ScriptVars {

    /** `execution.setVariable('x', …)`, `flw.getInput('x')`, … — the Flowable scripting APIs. */
    private val API_RE = Regex(
        "\\b(?:(?:get|set)(?:Transient)?(?:Input|Output)" +
            "|(?:set|get|has|remove)(?:Transient)?Variable(?:Local)?)" +
            "\\s*\\(\\s*['\"]([A-Za-z_]\\w*)['\"]")

    /** `variables.put('x', …)` / `vars['x']` — the variable map, addressed directly. */
    private val VAR_MAP_RE = Regex(
        "\\b(?:variables|transientVariables|vars)\\s*(?:\\.\\s*(?:put|get|remove|containsKey)\\s*\\(|\\[)" +
            "\\s*['\"]([A-Za-z_]\\w*)['\"]")

    /** `execution.setVariables([foo: 1, 'bar': 2])` — a whole map of writes in one call. */
    private val SET_VARS_MAP_RE = Regex("\\bset(?:Transient)?Variables\\s*\\(\\s*\\[([^\\]]{0,4000})]")
    private val MAP_KEY_RE = Regex("(?:^|[,\\[{(])\\s*(?:'([A-Za-z_]\\w*)'|\"([A-Za-z_]\\w*)\"|([A-Za-z_]\\w*))\\s*:")

    /** Locally declared names — `def x`, `var x`, closure/function/`for`/`catch` parameters. */
    private val DECL_RE = Regex("\\b(?:var|def|let|const|final)\\s+([A-Za-z_]\\w*)")

    /** A Java/Groovy typed local — `String other = …`, `Map<String,Object> payload`, `long count = 0`. */
    private val TYPED_DECL_RE = Regex(
        "\\b(?:[A-Z][A-Za-z0-9_]*(?:<[^>\\n]{0,80}>)?(?:\\[])?" +
            "|int|long|double|float|boolean|char|byte|short)\\s+([a-z_]\\w*)\\s*(?:=[^=]|;|$|\\n)",
        RegexOption.MULTILINE)
    private val PARAM_LIST_RE = Regex("(?:\\bfunction\\s*[A-Za-z_]*\\s*|\\bdef\\s+[A-Za-z_]\\w*\\s*)\\(([^)]{0,500})\\)")
    private val CLOSURE_PARAMS_RE = Regex("\\{\\s*([A-Za-z_][\\w,\\s]{0,120}?)\\s*->")
    private val FOR_IN_RE = Regex("\\bfor\\s*\\(\\s*(?:def|var|let|final)?\\s*([A-Za-z_]\\w*)\\s+in\\b")
    private val CATCH_RE = Regex("\\bcatch\\s*\\(\\s*(?:[A-Za-z_][\\w.]*\\s+)?([A-Za-z_]\\w*)\\s*\\)")
    private val IDENT_RE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Script languages whose bare identifiers are scope variables. `juel` is an expression, not a script. */
    private val SCRIPT_LANGS = setOf("groovy", "javascript", "js", "ecmascript", "nashorn", "graal.js", "python", "jython")

    /**
     * Roots that are engine API, language builtins or the script's own plumbing — never a variable of
     * the model. Anything reached through one of them (`execution.getVariable(…)`) is already handled by
     * [API_RE], and a bare mention of the root itself means nothing.
     */
    private val NOT_A_VARIABLE = setOf(
        // Flowable scripting scope
        "execution", "task", "taskEntity", "planItemInstance", "caseInstance", "processInstance",
        "variableContainer", "variableScope", "entity", "flw", "vars", "variables", "transientVariables",
        "engine", "processEngine", "cmmnEngine", "runtimeService", "taskService", "repositoryService",
        "historyService", "cmmnRuntimeService", "cmmnTaskService", "identityService", "formService",
        "dmnEngine", "eventRegistry", "beans", "bean", "logger", "log", "out", "err", "args", "it", "this",
        // language builtins / literals
        "true", "false", "null", "undefined", "print", "println", "printf", "require", "load", "eval",
        "parseInt", "parseFloat", "isNaN", "typeof", "instanceof", "new", "return", "throw", "throws",
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try",
        "catch", "finally", "def", "var", "let", "const", "final", "static", "public", "private",
        "protected", "class", "interface", "enum", "extends", "implements", "import", "package", "as",
        "in", "assert", "function", "void", "super", "yield", "await", "async", "delete", "with",
        "length", "size", "each", "collect", "toString", "valueOf",
        // primitive type names — `for (int i = 0; …)` would otherwise report `int` as a variable
        "int", "long", "double", "float", "boolean", "char", "byte", "short",
    )

    /**
     * The variables [script] touches. [format] is the model's script language; the bare-identifier pass
     * only runs for real scripting languages (an unknown/absent format is treated as a script, which is
     * how Flowable itself defaults).
     */
    fun analyze(script: String?, format: String? = null): ScriptVarUse {
        if (script.isNullOrBlank()) return ScriptVarUse.EMPTY
        val api = LinkedHashSet<String>()
        for (m in API_RE.findAll(script)) api.add(m.groupValues[1])
        for (m in VAR_MAP_RE.findAll(script)) api.add(m.groupValues[1])
        for (m in SET_VARS_MAP_RE.findAll(script)) {
            for (k in MAP_KEY_RE.findAll(m.groupValues[1])) {
                api.add(k.groupValues.drop(1).first { it.isNotEmpty() })
            }
        }
        val lang = format?.lowercase()?.trim()
        if (lang != null && lang.isNotEmpty() && lang !in SCRIPT_LANGS) return ScriptVarUse(api, emptySet())
        return ScriptVarUse(api, bareIdentifiers(stripLiterals(script), api))
    }

    /**
     * Identifiers the script uses that can only come from the scope it runs in: not declared locally,
     * not a call (`foo(`), not a member (`.foo`), not a map key (`foo:`), not a type name (`Foo`), not
     * engine API or a language keyword. Deliberately conservative — a false variable is worse than a
     * missing one, because the variable list is what people trust.
     */
    private fun bareIdentifiers(code: String, api: Set<String>): Set<String> {
        val declared = LinkedHashSet<String>()
        for (m in DECL_RE.findAll(code)) declared.add(m.groupValues[1])
        for (m in TYPED_DECL_RE.findAll(code)) declared.add(m.groupValues[1])
        for (m in FOR_IN_RE.findAll(code)) declared.add(m.groupValues[1])
        for (m in CATCH_RE.findAll(code)) declared.add(m.groupValues[1])
        for (re in listOf(PARAM_LIST_RE, CLOSURE_PARAMS_RE)) {
            for (m in re.findAll(code)) {
                for (p in m.groupValues[1].split(',')) {
                    IDENT_RE.find(p.trim())?.let { declared.add(it.value) }
                }
            }
        }
        val out = LinkedHashSet<String>()
        for (m in IDENT_RE.findAll(code)) {
            val name = m.value
            if (name.length < 2) continue                       // loop counters, not variables
            if (name in declared || name in api || name in NOT_A_VARIABLE) continue
            if (!name[0].isLowerCase() && name[0] != '_') continue   // Foo / FOO — a type or a constant
            var b = m.range.first - 1
            while (b >= 0 && code[b].isWhitespace()) b--
            val prev = if (b >= 0) code[b] else ' '
            if (prev == '.' || prev == '?' || prev == '$') continue                             // member access
            var a = m.range.last + 1
            while (a < code.length && code[a].isWhitespace()) a++
            val next = if (a < code.length) code[a] else ' '
            if (next == '(' || next == '{') continue                                            // a call
            if (next == ':' && (a + 1 >= code.length || code[a + 1] != ':')) continue            // a map key / label
            out.add(name)
        }
        return out
    }

    /**
     * Blank out string literals and comments so words inside them are not mistaken for identifiers.
     * `${…}` inside a Groovy GString is kept — it references real variables.
     */
    private fun stripLiterals(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    while (i < s.length && s[i] != '\n') i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                    i = minOf(s.length, i + 2)
                }
                c == '\'' || c == '"' -> {
                    val quote = c
                    sb.append(' ')
                    i++
                    while (i < s.length) {
                        if (s[i] == '\\') { i += 2; continue }
                        if (s[i] == quote) { i++; break }
                        if (quote == '"' && s[i] == '$' && i + 1 < s.length && s[i + 1] == '{') {
                            i += 2
                            while (i < s.length && s[i] != '}') { sb.append(s[i]); i++ }
                            if (i < s.length) i++      // the closing brace
                            sb.append(' ')
                            continue
                        }
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
