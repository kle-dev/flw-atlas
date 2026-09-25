package com.flowable.atlas.parsing

/**
 * Regex-based Java source analysis — a port of `parse_java`, `match_rest` and their helpers in
 * `flowable_atlas.py` (~lines 1092-1244). Extracts the package/class, Spring bean names, implemented
 * "glue" interfaces, `@*Mapping` REST endpoints, declared methods, dependency types, the class role,
 * a Flowable bot key, process/case variable accesses and string literals — everything needed to draw
 * model↔code references.
 */
object JavaParser {

    // Java and Kotlin share one pass: the annotations Spring and Flowable care about are spelled the
    // same, so the regexes only have to tolerate Kotlin's spellings of the surrounding syntax — no `;`
    // after the package, `object`/`enum class`, a supertype list after the primary constructor, `fun`.
    private val PKG_RE = Regex("""^\s*package\s+([\w.]+)\s*;?""", RegexOption.MULTILINE)
    private val TYPE_RE = Regex("""\b(?:(?:public|final|abstract|open|data|internal|sealed|private)\s+)*(class|interface|enum\s+class|enum|object)\s+(\w+)""")
    private val BEAN_ANN_RE = Regex("""@(Component|Service|Repository|Named)\s*(?:\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\))?""")
    // `@Bean` / `@Bean("name")` / `@Bean(name = "name")` on a factory method: the bean is what the method
    // returns, named after the method unless the annotation says otherwise. Beans declared this way —
    // in a @Configuration class, for a delegate one does not own — were invisible: every model that
    // named one resolved to nothing.
    private val BEAN_METHOD_ANN_RE = Regex("""@Bean\b(?:\s*\((?:[^)"]*?(?:name|value)\s*=\s*)?\{?\s*"([^"]+)"[^)]*\))?""")
    private val ANNOTATION_RE = Regex("""@\w+(?:\.\w+)*(?:\s*\([^)]*\))?""")
    private val FIRST_CALLABLE_RE = Regex("""\b(\w+)\s*\(""")
    private val IMPLEMENTS_RE = Regex("""\bimplements\s+([\w.,\s<>]+?)\s*\{""")
    // Kotlin: `class X(…) : A, B(), C<T> {` — the supertype list after the primary constructor.
    private val KT_SUPERTYPES_RE = Regex("""\b(?:class|object)\s+\w+(?:<[^>]*>)?(?:\s*\([^)]*\))?\s*:\s*([\w.,\s<>()]+?)\s*\{""")
    private val KT_METHOD_RE = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(([^)]*)\)""")
    private val KT_PROP_RE = Regex("""\b(?:val|var)\s+\w+\s*:\s*([A-Z]\w+)""")
    private val MAPPING_RE = Regex("""@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(?:\(([^)]*)\))?""")
    private val CONTROLLER_RE = Regex("""@(RestController|Controller)\b""")
    private val METHOD_RE = Regex("""(?:public|protected|private)\s+(?:[\w${'$'}<>\[\].,]+\s+)+?(\w+)\s*\(([^;{)]*)\)\s*(?:throws[\w.,\s]+)?\{""")
    private val FIELD_RE = Regex("""(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([A-Z]\w+)(?:<[^>]*>)?\s+[a-z]\w*\s*[;=]""")
    // The verb is captured, not just the name: `setVariable` proves a write and `getVariable` a read,
    // which is what lets a variable be reported as written-but-never-consumed. `has`/`remove` decide
    // nothing.
    private val JAVA_VAR_RE = Regex("""\b(set|get|has|remove)Variable(?:Local)?\s*\(\s*(?:[^,"]+,\s*)?"([A-Za-z_]\w*)"""")

    // A map the code hands to the engine as variables: `startProcessInstanceByKey(k, vars)`,
    // `.variables(vars)`, `taskService.complete(id, vars)`, `setVariables(vars)`. Its `vars.put("x", …)`
    // writes `x` exactly as `setVariable("x", …)` does, and was invisible — every such name looked
    // written by nobody (the models read it) or read by nobody.
    private val VARS_HANDOFF_RE = Regex("""\b(?:startProcessInstanceBy\w*|complete|setVariables(?:Local)?|variables|transientVariables|trigger)\s*\(([^;{}]*)\)""")
    private val IDENT_ARG_RE = Regex("""(?<![\w."])([a-z]\w*)(?=\s*(?:,|$))""")

    /** `execution.getVariables()` — a read of the whole map, so no variable of a scope this class
     *  touches can be proven unread. Only the no-argument forms; the name-collection overloads are
     *  already covered name-by-name by [JAVA_VAR_RE]. */
    private val JAVA_VARS_ALL_RE = Regex("""\b(?:get|has)Variable(?:Instance)?s(?:Local)?\s*\(\s*\)""")

    // A Flowable EL expression inside a Java string literal — `resolveValue(task, "${vars:get(flagReturn)}")`.
    // A listener evaluating EL against the instance is ordinary Flowable code, and the names it reads are
    // real variable reads that neither the setVariable/getVariable scan nor the model-side expression
    // harvest can see: the expression lives in .java, and the variable name is not a quoted argument.
    private val JAVA_EL_RE = Regex("""[#$]\{([^}"]*)}""")
    private val EL_ROOT_RE = Regex("""(?<![\w.$'"])([A-Za-z_]\w*)(?!\s*[(\w:])""")
    private val JAVA_STR_RE = Regex("""\"([^"\\\n]{2,80})\"""")
    private val REQUEST_METHOD_RE = Regex("""RequestMethod\.(\w+)""")
    private val PATH_ATTR_RE = Regex("""(?:value|path)\s*=\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
    private val CONST_REF_RE = Regex("""(?:\w+\.)*[A-Za-z_]\w*""")
    private val HANDLER_RE = Regex("""\b(\w+)\s*\(""")

    // `static final String NAME = "value";` constant declarations — used to resolve a data-object key
    // referenced by a constant (e.g. a generated model-keys class field) back to its literal value.
    private val STRING_CONST_RE = Regex("""\b(?:static\s+final\s+String|const\s+val)\s+(\w+)\s*(?::\s*String)?\s*=\s*"([^"]+)"""")

    // A string literal that is the first argument of a method call — `method("literal"` — so the
    // literal's call context is known. Literals passed to a known key-taking Flowable API produce a
    // confident CODE→MODEL edge; any other literal that happens to equal a model key only a suspect one.
    private val STR_CTX_RE = Regex("""\b(\w+)\s*\(\s*"([^"\\\n]{2,80})"""")
    // The same position holding a constant instead of a literal — `startProcessInstanceByKey(MAIN_CASE)`,
    // `.caseDefinitionKey(ModelConstants.MAIN_CASE)`: an UPPER_SNAKE name, optionally qualified. Resolved
    // against the project's `static final String` constants once every source is read (a generated
    // model-keys class is the usual home); one real project's whole Java layer is written this way and
    // had no code → model edge at all.
    private val IDENT_CTX_RE = Regex("""\b(\w+)\s*\(\s*((?:\w+\.)*[A-Z][A-Z0-9_]{2,})\s*[,)]""")
    /**
     * Engine and platform methods whose (first) String argument is a model key, with the model types that
     * key can name — an empty set for the untyped `key(…)`. The type is what keeps the reference honest:
     * `caseDefinitionKey("X")` names the case `X`, not the process or form that happens to share the key
     * (a key shared by two types is common). Signal and message names, and operation keys, are no model
     * keys at all and are not listed: `signalEventReceived("X")` was a clean reference to a process `X`.
     */
    private val KEY_API_KINDS: Map<String, Set<String>> = buildMap {
        fun put(kinds: Set<String>, vararg methods: String) = methods.forEach { put(it, kinds) }
        put(setOf("process"), "startProcessInstanceByKey", "startProcessInstanceByKeyAndTenantId",
            "processDefinitionKey", "processDefinitionKeyLike", "processDefinitionKeyLikeIgnoreCase")
        put(setOf("case"), "caseDefinitionKey", "caseDefinitionKeyLike", "caseDefinitionKeyLikeIgnoreCase")
        put(setOf("decision"), "decisionKey")
        put(setOf("form", "page"), "formDefinitionKey", "getFormModelByKey", "getFormModelWithVariablesByKey",
            "getFormInstanceModelByKey", "taskFormKey", "formKey")
        put(setOf("dataObject", "masterData"), "definitionKey", "dataObjectDefinitionKey")
        put(setOf("event"), "eventDefinitionKey", "getEventModelByKey")
        put(setOf("channel"), "channelDefinitionKey", "getChannelModelByKey")
        put(setOf("service"), "serviceKey", "getServiceDefinitionModelByKey", "getServiceDefinitionByKey")
        put(setOf("action"), "actionDefinitionKey", "getActionDefinitionModelByKey", "getActionDefinitionByKey")
        put(setOf("template"), "templateKey", "processTemplate", "mainContentTemplate")
        put(setOf("agent"), "agentDefinitionKey")
        put(setOf("securityPolicy"), "getPolicyModelByKey")
        put(setOf("user"), "userDefinitionKey")
        put(emptySet(), "key")
    }

    // Flowable data-object runtime builder chain: `.definitionKey(<expr>) … .operation("<literal>")`.
    private val DEFINITION_KEY_RE = Regex("""\.definitionKey\(\s*([^)]+?)\s*\)""")
    // The operation as written — a literal or a constant (`.operation(Ops.FIND_ALL)`), resolved by the caller.
    private val OPERATION_CALL_RE = Regex("""\.operation\(\s*("[^"]+"|(?:\w+\.)*[A-Z][A-Z0-9_]*)\s*\)""")
    // Service-registry invocations: `createServiceInvocationBuilder().serviceKey(<expr>).operationKey("op")`.
    private val SERVICE_KEY_CALL_RE = Regex("""\.serviceKey\(\s*([^)]+?)\s*\)""")
    private val OPERATION_KEY_CALL_RE = Regex("""\.operationKey\(\s*("[^"]+"|(?:\w+\.)*[A-Z][A-Z0-9_]*)\s*\)""")
    // External-worker subscriptions/queries: `.topic("orders")` — links the class to the topic node.
    private val TOPIC_CALL_RE = Regex("""\.topic\(\s*"([^"]+)"\s*\)""")

    private val CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "return", "new")
    private val DELEGATE_INTERFACES = setOf(
        "JavaDelegate", "PlanItemJavaDelegate", "JavaDelegatePlanItem",
        "ActivityBehavior", "PlanItemActivityBehavior", "DelegatePlanItemActivityBehavior",
    )
    private val GLUE_INTERFACES = setOf(
        "JavaDelegate", "ExecutionListener", "TaskListener", "ActivityBehavior",
        "PlanItemJavaDelegate", "PlanItemActivityBehavior", "CaseInstanceLifecycleListener",
        "PlanItemInstanceLifecycleListener", "DelegatePlanItemActivityBehavior",
        "AbstractServiceTask", "JavaDelegatePlanItem", "FlowableEventListener",
    )

    private fun decap(name: String): String = if (name.isEmpty()) name else name[0].lowercaseChar() + name.substring(1)

    /**
     * Replace comment bodies with spaces (newlines preserved) so scans skip commented-out code.
     *
     * A scanner, not a regex: string and character literals are stepped over, so the block-comment
     * opener inside a mapping like `"/files/` + `**"` or the `//` in `"http://svc"` does not open a
     * comment that swallows the mappings, beans and key literals after them. Text blocks and Kotlin raw strings (`"""…"""`) have no
     * escapes; a Kotlin `${…}` template holding a quote is rare enough to read as a plain string.
     */
    internal fun blankComments(text: String): String {
        val out = StringBuilder(text)
        val n = text.length
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until to) if (text[k] != '\n') out.setCharAt(k, ' ') }
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    blank(i, end); i = end
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    blank(i, end); i = end
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) n else end + 3
                }
                c == '"' || c == '\'' -> {
                    var k = i + 1
                    while (k < n && text[k] != c && text[k] != '\n') k += if (text[k] == '\\') 2 else 1
                    i = k + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** Marks a constant a mapping path names that this file does not declare; the resolver has the
     *  project's constants and replaces it, or flags the endpoint `pathUnresolved`. */
    const val PATH_CONST = '\u0001'

    /**
     * The paths a mapping annotation's arguments name: the unnamed first argument or `value =`/`path =`,
     * one per element of an array, each a literal or a `+` of literals and constants. A constant this file
     * declares is resolved here; another file's is left marked ([PATH_CONST]). `produces = "…"` and the other
     * attributes name no path — reading the first string of the arguments made one `/application/json`.
     */
    private fun mappingPaths(args: String?, consts: Map<String, String>): List<String> {
        if (args.isNullOrBlank()) return listOf("")
        val parts = topLevel(args, ',')
        val expr = parts.firstNotNullOfOrNull { p -> PATH_ATTR_RE.matchEntire(p.trim())?.groupValues?.get(1) }
            ?: parts.firstOrNull()?.trim()?.takeIf { topLevel(it, '=').size == 1 && it.isNotEmpty() }
            ?: return listOf("")
        val e = expr.trim()
        val elements = if (e.length >= 2 && (e.first() == '{' && e.last() == '}' || e.first() == '[' && e.last() == ']')) {
            topLevel(e.substring(1, e.length - 1), ',').map { it.trim() }.filter { it.isNotEmpty() }
        } else listOf(e)
        return elements.map { el ->
            topLevel(el, '+').joinToString("") { piece ->
                val t = piece.trim()
                when {
                    t.length >= 2 && t.first() == '"' && t.last() == '"' -> t.substring(1, t.length - 1)
                    CONST_REF_RE.matches(t) -> t.substringAfterLast('.').let { n -> consts[n] ?: "$PATH_CONST$n$PATH_CONST" }
                    else -> "${PATH_CONST}?$PATH_CONST"
                }
            }
        }.ifEmpty { listOf("") }
    }

    /** [s] split at every [sep] outside quotes, braces, brackets and parentheses. */
    private fun topLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0; var quote: Char? = null; var start = 0; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> { if (c == '\\') i++ else if (c == quote) quote = null }
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> depth--
                c == sep && depth == 0 -> { out.add(s.substring(start, i)); start = i + 1 }
            }
            i++
        }
        out.add(s.substring(start))
        return out
    }

    /** The index of the `}` closing the `{` at [open], skipping string and char literals; the text's end
     *  when it never closes. */
    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0; var i = open; var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> { if (c == '\\') i++ else if (c == quote) quote = null }
                c == '"' || c == '\'' -> quote = c
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return text.length
    }

    fun parseJava(rawText: String, ffile: String): Map<String, Any?> {
        val text = blankComments(rawText)
        val kotlin = ffile.lowercase().endsWith(".kt")
        fun lineOf(idx: Int): Int = text.substring(0, idx).count { it == '\n' } + 1

        val pkg = PKG_RE.find(text)?.groupValues?.get(1) ?: ""
        val types = TYPE_RE.findAll(text).map { it.groupValues[2] }.toList()
        val primary = types.firstOrNull() ?: ffile.substringAfterLast('/').substringBeforeLast('.')

        val beanNames = LinkedHashSet<String>()
        for (m in BEAN_ANN_RE.findAll(text)) {
            beanNames.add(m.groupValues[2].ifEmpty { decap(primary) })
        }
        // Factory beans: the method after the annotation (other annotations in between skipped).
        val beanMethods = LinkedHashMap<String, Int>()
        for (m in BEAN_METHOD_ANN_RE.findAll(text)) {
            val tail = text.substring(m.range.last + 1, minOf(m.range.last + 1 + 400, text.length))
            val method = FIRST_CALLABLE_RE.find(ANNOTATION_RE.replace(tail, " "))?.groupValues?.get(1)
                ?.takeIf { it !in CONTROL_KEYWORDS } ?: continue
            val name = m.groupValues[1].ifEmpty { method }
            beanNames.add(name)
            beanMethods.putIfAbsent(name, lineOf(m.range.first))
        }
        val interfaces = LinkedHashSet<String>()
        val supertypeLists = IMPLEMENTS_RE.findAll(text).map { it.groupValues[1] } +
            (if (kotlin) KT_SUPERTYPES_RE.findAll(text).map { it.groupValues[1] } else emptySequence())
        for (list in supertypeLists) {
            for (it in list.split(",")) {
                // `AbstractServiceTask()` — a Kotlin superclass constructor call — is the type without the parens
                interfaces.add(Regex("<.*?>").replace(it, "").replace("()", "").trim().substringAfterLast('.'))
            }
        }

        val isController = CONTROLLER_RE.containsMatchIn(text)
        val endpoints = ArrayList<Map<String, Any?>>()
        // The controller is the type declared after the annotation, its base mapping sits between the two,
        // and its handlers are the mappings inside that type's body. Taking the file's first `class ` as the
        // boundary let a data class or an enum before the controller swallow the base mapping, which then
        // served `ANY /base` as a handler of its own while every real handler lost its base.
        val ctlAnn = CONTROLLER_RE.find(text)
        val ctlDecl = ctlAnn?.let { TYPE_RE.find(text, it.range.last + 1) }
        if (isController && ctlAnn != null && ctlDecl != null) {
            val consts = stringConstants(rawText)
            val bases = MAPPING_RE.findAll(text.substring(ctlAnn.range.first, ctlDecl.range.first))
                .firstOrNull { it.groupValues[1] == "Request" }
                ?.let { mappingPaths(it.groups[2]?.value, consts) } ?: listOf("")
            val open = text.indexOf('{', ctlDecl.range.last + 1)
            val close = if (open < 0) text.length else closingBrace(text, open)
            for (m in MAPPING_RE.findAll(text)) {
                if (m.range.first < open || m.range.first > close) continue
                val verb = m.groupValues[1]
                val args = m.groups[2]?.value
                var http = if (verb == "Request") "ANY" else verb.uppercase()
                if (verb == "Request" && !args.isNullOrEmpty()) {
                    http = REQUEST_METHOD_RE.find(args)?.groupValues?.get(1) ?: "ANY"
                }
                // the handler is the method after the mapping — past any other annotation on it
                val tail = text.substring(m.range.last + 1, minOf(m.range.last + 1 + 400, text.length))
                val handler = HANDLER_RE.findAll(ANNOTATION_RE.replace(tail, " ")).map { it.groupValues[1] }
                    .firstOrNull { it !in CONTROL_KEYWORDS } ?: "?"
                for (base in bases) for (path in mappingPaths(args, consts)) {
                    val full = "/" + (base + "/" + path).split("/").filter { it.isNotEmpty() }.joinToString("/")
                    endpoints.add(linkedMapOf("http" to http, "path" to full, "handler" to handler,
                        "line" to lineOf(m.range.first), "controller" to ctlDecl.groupValues[2]))
                }
            }
        }

        val methods = ArrayList<Map<String, Any?>>()
        val seenM = HashSet<String>()
        for (m in (if (kotlin) KT_METHOD_RE else METHOD_RE).findAll(text)) {
            val nm = m.groupValues[1]
            if (nm in CONTROL_KEYWORDS) continue
            val params = m.groupValues[2].trim()
            val arity = if (params.isEmpty()) 0 else params.count { it == ',' } + 1
            val sig = "$nm/$arity"
            if (!seenM.add(sig)) continue
            methods.add(linkedMapOf("name" to nm, "params" to arity, "line" to lineOf(m.range.first)))
        }

        val deps = LinkedHashSet<String>()
        if (kotlin) {
            // properties and primary-constructor parameters alike: `val repo: ScoreRepo`
            KT_PROP_RE.findAll(text).forEach { deps.add(it.groupValues[1]) }
        } else {
            FIELD_RE.findAll(text).forEach { deps.add(it.groupValues[1]) }
            val ctorRe = Regex("""(?:public|protected)\s+""" + Regex.escape(primary) + """\s*\(([^)]*)\)""")
            val ctorParamRe = Regex("""\b([A-Z]\w+)(?:<[^>]*>)?\s+\w+""")
            for (cm in ctorRe.findAll(text)) {
                ctorParamRe.findAll(cm.groupValues[1]).forEach { deps.add(it.groupValues[1]) }
            }
        }

        val roles = LinkedHashSet<String>()
        if (isController) roles.add("controller")
        if (Regex("""@Service\b""").containsMatchIn(text)) roles.add("service")
        if (Regex("""@Repository\b""").containsMatchIn(text)) roles.add("repository")
        if (Regex("""@Configuration\b""").containsMatchIn(text)) roles.add("configuration")
        if (Regex("""@Component\b""").containsMatchIn(text)) roles.add("component")
        if (interfaces.any { it in DELEGATE_INTERFACES }) roles.add("delegate")
        if (interfaces.any { it.endsWith("Listener") }) roles.add("listener")
        var botKey: String? = null
        if (interfaces.any { it == "BotService" || it.endsWith("Bot") || it.endsWith("BotService") }) {
            roles.add("bot")
            botKey = Regex("""getKey\s*\(\s*\)[^{]*\{[^{}]*?return\s+"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)
        }
        if (roles.isEmpty()) roles.add("other")

        // literals whose call context is a known key-taking Flowable API — confident model refs
        // and the model types each can name (`keyedKinds`, empty for the untyped `key(…)`)
        val keyedStrings = LinkedHashSet<String>()
        val keyedKinds = LinkedHashMap<String, MutableSet<String>>()
        for (m in STR_CTX_RE.findAll(text)) {
            val kinds = KEY_API_KINDS[m.groupValues[1]] ?: continue
            keyedStrings.add(m.groupValues[2])
            keyedKinds.getOrPut(m.groupValues[2]) { sortedSetOf() }.addAll(kinds)
        }
        // constants at the same positions, by simple name — the resolver has the values
        val keyedIdents = LinkedHashSet<String>()
        val keyedIdentKinds = LinkedHashMap<String, MutableSet<String>>()
        for (m in IDENT_CTX_RE.findAll(text)) {
            val kinds = KEY_API_KINDS[m.groupValues[1]] ?: continue
            val ident = m.groupValues[2].substringAfterLast('.')
            keyedIdents.add(ident)
            keyedIdentKinds.getOrPut(ident) { sortedSetOf() }.addAll(kinds)
        }

        // Variable accesses, split by verb. `vars` stays the union so every existing consumer is
        // unaffected; the three buckets are what the unused-variable check reads.
        val varWrites = sortedSetOf<String>()
        val varReads = sortedSetOf<String>()
        val varsUndecided = sortedSetOf<String>()
        for (m in JAVA_VAR_RE.findAll(text)) {
            val bucket = when (m.groupValues[1]) {
                "set" -> varWrites
                "get" -> varReads
                else -> varsUndecided
            }
            bucket.add(m.groupValues[2])
        }
        val handedOff = VARS_HANDOFF_RE.findAll(text)
            .flatMap { h -> IDENT_ARG_RE.findAll(h.groupValues[1].trim()).map { it.groupValues[1] } }.toSet()
        for (map in handedOff) {
            for (m in Regex("""\b${Regex.escape(map)}\s*\.\s*put\(\s*"([A-Za-z_]\w*)"""").findAll(text)) varWrites.add(m.groupValues[1])
        }
        // Names an embedded EL expression reads. `${vars:get(flagReturn)}` names its variable as a bare
        // identifier, not as a quoted argument, so nothing else picks it up.
        for (m in JAVA_EL_RE.findAll(text)) {
            // `@Value("${flamingo.mail.from}")` is a property placeholder: `flamingo` is no variable
            if (Constants.isJavaConfigPlaceholder(m.groupValues[1])) continue
            for (r in EL_ROOT_RE.findAll(m.groupValues[1])) {
                val name = r.groupValues[1]
                if (name !in Constants.FLOWABLE_CONTEXT && name !in Constants.JAVA_LITERALS) varReads.add(name)
            }
        }

        val out = linkedMapOf<String, Any?>(
            "file" to ffile, "package" to pkg, "primary" to primary,
            "fqn" to (if (pkg.isNotEmpty()) "$pkg.$primary" else primary),
            "types" to types, "beanNames" to beanNames, "interfaces" to interfaces, "roles" to roles,
            "isController" to isController, "isGlue" to interfaces.any { it in GLUE_INTERFACES },
            "endpoints" to endpoints, "methods" to methods, "deps" to deps, "botKey" to botKey,
            "vars" to (varWrites + varReads + varsUndecided).toSortedSet().toList(),
            "varWrites" to varWrites.toList(), "varReads" to varReads.toList(),
            "varsUndecided" to varsUndecided.toList(),
            "readsAllVariables" to JAVA_VARS_ALL_RE.containsMatchIn(text),
            "strings" to JAVA_STR_RE.findAll(text).map { it.groupValues[1] }.toCollection(LinkedHashSet()),
            "keyedStrings" to keyedStrings,
            "keyedKinds" to keyedKinds,
            "keyedIdents" to keyedIdents,
            "keyedIdentKinds" to keyedIdentKinds,
            "topics" to TOPIC_CALL_RE.findAll(text).map { it.groupValues[1] }.toSortedSet().toList(),
            "line" to text.indexOf("class ").let { if (it != -1) lineOf(it) else 1 },
        )
        // bean name → line of its @Bean factory method, so a reference lands on the method, not the class
        if (beanMethods.isNotEmpty()) out["beanMethods"] = beanMethods
        return out
    }

    /** A `static final String NAME = "value"` constant declared in the source (name → value). */
    fun stringConstants(rawText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in STRING_CONST_RE.findAll(blankComments(rawText))) out.putIfAbsent(m.groupValues[1], m.groupValues[2])
        return out
    }

    /** Flowable data-object operation invocations: each `.operation(op)` paired with the nearest
     *  preceding `.definitionKey(expr)` in the same statement (no `;` between). `def` and `op` are kept
     *  raw — a quoted literal or a constant reference (e.g. a generated model-keys class field) — for the
     *  caller to resolve. Returns `{def, op}` maps. */
    fun dataObjectOpCalls(rawText: String): List<Map<String, String>> {
        val text = blankComments(rawText)
        val defKeys = DEFINITION_KEY_RE.findAll(text).map { it.range.first to it.groupValues[1].trim() }.toList()
        val out = ArrayList<Map<String, String>>()
        for (m in OPERATION_CALL_RE.findAll(text)) {
            val def = defKeys.lastOrNull { it.first < m.range.first } ?: continue
            if (text.substring(def.first, m.range.first).contains(';')) continue
            out.add(linkedMapOf("def" to def.second, "op" to m.groupValues[1]))
        }
        return out
    }

    /**
     * Service-registry invocations in the source: every `.serviceKey(expr)` and `.operationKey(expr)`
     * argument, raw — a quoted literal or a constant, for the caller to resolve. Per class, not per
     * statement: a helper usually builds the invocation with its service key and a lambda elsewhere in the
     * class names the operation — `request(SERVICE_KEY, b -> b.operationKey("getContact"))`.
     */
    fun serviceInvocations(rawText: String): Pair<List<String>, List<String>> {
        val text = blankComments(rawText)
        val keys = SERVICE_KEY_CALL_RE.findAll(text).map { it.groupValues[1].trim() }.distinct().toList()
        val ops = OPERATION_KEY_CALL_RE.findAll(text).map { it.groupValues[1] }.distinct().toList()
        return keys to ops
    }

    private val SCHEME_HOST_RE = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)""")
    private val PLACEHOLDER_RE = Regex("""[#$]\{[^}]*\}|\{\{[^}]*\}\}|\{[^}]*\}""")
    private val PLATFORM_ENDPOINT_RE = Regex("""\{\{\s*endpoints\.([A-Za-z0-9_$]+)\s*\}\}/*""")
    private val LOCAL_HOST_RE = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1])(?::\d+)?""", RegexOption.IGNORE_CASE)

    /**
     * The REST roots Flowable Work's frontend resolves `{{endpoints.<id>}}` to — `defaultWorkEndpoints` in
     * the platform's `flowable-work-api/endpoints.ts`. A model URL built on one of them calls Flowable's own
     * API (`{{endpoints.idm}}/users/{{$id}}` is `idm-api/users/…`), never a controller of the project, and
     * resolving the placeholder is what lets the matcher see that. Read as a mere placeholder it matched
     * any project endpoint ending in a variable — 24 forms of one real project "called" five unrelated
     * controllers through `{{endpoints.idm}}/users?…`.
     */
    private val PLATFORM_ENDPOINTS = mapOf(
        "action" to "action-api", "engage" to "engage-api", "form" to "form-api", "idm" to "idm-api",
        "report" to "platform-api/reports", "cmmn" to "cmmn-api", "core" to "core-api", "license" to "core-api",
        "process" to "process-api", "platform" to "platform-api", "agent" to "agent-api",
        "design" to "platform-design-api", "login" to "auth/login", "logout" to "auth/logout", "auth" to "auth",
        "content" to "content-api", "dmn" to "dmn-api", "dataobject" to "dataobject-api", "audit" to "platform-api",
        "actuator" to "actuator", "template" to "template-api", "tutorial" to "tutorial-api", "inspect" to "inspect-api",
    )

    /** The first path segment of every Flowable REST API — a context path never looks like one. */
    private val PLATFORM_ROOTS = PLATFORM_ENDPOINTS.values.map { it.substringBefore('/') }.toSet() + "hub-api"

    /**
     * What a model URL calls, as path segments — a literal, or `null` for a segment a placeholder fills —
     * plus whether a context path may precede the endpoint's own path. `null` when the URL cannot be shown
     * to reach this project: a client-side route (`#/…`), an absolute URL on a host other than the local
     * one, or a URL built on a base nothing names (`${crmUrl}/…`, a custom `{{endpoints.x}}`, a placeholder
     * host). Every such base on the real projects was another system; matched by its path it looked like a
     * call of the project's own endpoint of that shape. `{{endpoints.baseUrl}}` is the application itself.
     */
    private class CallPath(val segs: List<String?>, val prefixAllowed: Boolean)

    /** What a placeholder becomes before the URL is cut up: one mark, so a `?` or `#` inside `{{c ? a : b}}`
     *  is not taken for the query string or the fragment. */
    private const val PH = "\u0002"

    private fun callPath(url: String?): CallPath? {
        var u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        u = PLATFORM_ENDPOINT_RE.replace(u) { m ->
            val id = m.groupValues[1]
            when {
                id == "baseUrl" -> ""
                id in PLATFORM_ENDPOINTS -> PLATFORM_ENDPOINTS.getValue(id) + "/"
                else -> m.value
            }
        }
        u = PLACEHOLDER_RE.replace(u, PH)
        if (u.startsWith("#") || "#/" in u) return null
        var prefixAllowed = u.startsWith("/")
        SCHEME_HOST_RE.find(u)?.let { m ->
            if (!LOCAL_HOST_RE.matches(m.groupValues[1])) return null
            u = u.substring(m.range.last + 1)
            prefixAllowed = true
        }
        u = u.substringBefore('?').substringBefore('#')
        val raw = u.split('/').filter { it.isNotEmpty() }
        if (raw.firstOrNull()?.contains(PH) == true) return null
        return CallPath(raw.map { if (PH in it) null else it }, prefixAllowed)
    }

    /** True when [url] calls Flowable's own REST API: a platform `{{endpoints.<id>}}` base, or a URL whose
     *  path starts at one of its roots (`platform-api/…`). A custom `{{endpoints.x}}` is not one of them. */
    fun callsFlowableApi(url: String?): Boolean =
        callPath(url)?.segs?.firstOrNull()?.let { it in PLATFORM_ROOTS } == true

    /** An endpoint path as segments, `null` for a path variable (`{id}`, `{id:\d+}`, a `*` pattern). */
    private fun endpointPath(path: String?): List<String?> =
        path.orEmpty().substringBefore('?').split('/').filter { it.isNotEmpty() }
            .map { if ('{' in it || '*' in it) null else it }

    /**
     * REST endpoints whose path matches [url].
     *
     * A call reaches an endpoint when the endpoint's path is the tail of the call's path, segment by
     * segment: an endpoint literal needs the same literal in the call, an endpoint path variable takes any
     * one segment. What went before the tail is a context path — allowed only where the URL is rooted or
     * absolute on the local host, never a relative URL (`api/x` is resolved against the application
     * itself), and never one of Flowable's own REST roots (`platform-api/audit-trail` is not the project's
     * `/audit-trail`). At least one literal has to agree, so a URL that is nothing but placeholders matches
     * nothing, and a URL on an unnamed base matches nothing at all (see [callPath]).
     *
     * A placeholder in the call is not evidence for an endpoint literal: `/api/orders/{{n}}` is not
     * `POST /api/orders/archive`, though `n` might be `archive` at run time. Treating it as a wildcard
     * turned `{{base}}/{{x}}` into a clean match of every endpoint with two segments, and a `#/…/case/{{id}}`
     * route into a call of every one-segment endpoint. The shared-last-literal-segment fallback is gone for
     * the same reason: on the real projects it only ever linked a call to the wrong endpoint.
     *
     * Among the hits, a handler whose path spells out more of the URL's literal segments wins, as it does
     * in Spring's routing: `/orders/archive` is served by `POST /orders/archive`, not by `GET /orders/{id}`
     * whose variable would also take `archive`.
     *
     * With a [method] the caller states for certain, a handler for another verb is not a match. A path hit
     * with the wrong verb is dropped when a handler with the right verb also matches, and otherwise kept
     * with `loose=true, methodMismatch=true` — the call does reach that path, and a PUT against a POST-only
     * handler is a defect worth seeing. The method is unknown when it is null, blank, `?` or an expression;
     * a handler mapped to `ANY` takes every verb.
     */
    fun matchRest(url: String?, codeEndpoints: List<Map<String, Any?>>, method: String? = null): List<Map<String, Any?>> {
        val call = callPath(url) ?: return emptyList()
        if (call.segs.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Map<String, Any?>, Int>>()
        for (ep in codeEndpoints) {
            // a path a constant or a property decides that nothing in the project resolves is unknown
            if (ep["pathUnresolved"] == true) continue
            val e = endpointPath(ep["path"] as? String)
            if (e.isEmpty() || e.size > call.segs.size) continue
            val prefix = call.segs.subList(0, call.segs.size - e.size)
            if (prefix.isNotEmpty() && (!call.prefixAllowed || prefix.any { it == null || it in PLATFORM_ROOTS })) continue
            val tail = call.segs.subList(call.segs.size - e.size, call.segs.size)
            var literals = 0
            val fits = e.indices.all { i ->
                val want = e[i]
                when {
                    want == null -> true
                    tail[i] == want -> { literals++; true }
                    else -> false
                }
            }
            if (fits && literals > 0) hits.add(ep to literals)
        }
        val verb = knownVerb(method)
        fun fits(ep: Map<String, Any?>): Boolean { val hv = knownVerb(ep["http"] as? String); return verb == null || hv == null || hv == verb }
        // the verb first — a GET call is not answered by the POST handler that happens to spell more of the
        // path — then, among the handlers for the right verb, the most literal path
        val right = hits.filter { fits(it.first) }
        val best = right.maxOfOrNull { it.second }
        if (right.isNotEmpty()) return right.filter { it.second == best }.map { it.first }
        val bestOther = hits.maxOfOrNull { it.second }
        return hits.filter { it.second == bestOther }.map { it.first + mapOf("loose" to true, "methodMismatch" to true) }
    }

    /** An HTTP verb stated for certain, upper-cased — null for none, `?`, `ANY` or an expression. */
    private fun knownVerb(m: String?): String? {
        val v = m?.trim()?.uppercase() ?: return null
        return if (VERB_RE.matches(v) && v != "ANY") v else null
    }
    private val VERB_RE = Regex("[A-Z]+")
}
