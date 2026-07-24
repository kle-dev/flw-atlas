package com.flowable.atlas.parsing

/**
 * Regex-based Java source analysis — a port of `parse_java`, `match_rest` and their helpers in
 * `flowable_atlas.py` (~lines 1092-1244). Extracts the package/class, Spring bean names, implemented
 * "glue" interfaces, `@*Mapping` REST endpoints, declared methods, dependency types, the class role,
 * a Flowable bot key, process/case variable accesses and string literals — everything needed to draw
 * model↔code references.
 */
object JavaParser {

    private val PKG_RE = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    private val TYPE_RE = Regex("""\b(?:public\s+|final\s+|abstract\s+)*(class|interface|enum)\s+(\w+)""")
    private val BEAN_ANN_RE = Regex("""@(Component|Service|Repository|Named)\s*(?:\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\))?""")
    private val IMPLEMENTS_RE = Regex("""\bimplements\s+([\w.,\s<>]+?)\s*\{""")
    private val MAPPING_RE = Regex("""@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(?:\(([^)]*)\))?""")
    private val CONTROLLER_RE = Regex("""@(RestController|Controller)\b""")
    private val METHOD_RE = Regex("""(?:public|protected|private)\s+(?:[\w${'$'}<>\[\].,]+\s+)+?(\w+)\s*\(([^;{)]*)\)\s*(?:throws[\w.,\s]+)?\{""")
    private val FIELD_RE = Regex("""(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([A-Z]\w+)(?:<[^>]*>)?\s+[a-z]\w*\s*[;=]""")
    private val JAVA_VAR_RE = Regex("""\b(?:set|get|has|remove)Variable(?:Local)?\s*\(\s*(?:[^,"]+,\s*)?"([A-Za-z_]\w*)"""")
    private val JAVA_STR_RE = Regex("""\"([^"\\\n]{2,80})\"""")
    private val COMMENT_RE = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
    private val NON_NEWLINE = Regex("""[^\n]""")
    private val REQUEST_METHOD_RE = Regex("""RequestMethod\.(\w+)""")
    private val VALUE_PATH_RE = Regex("""(?:value|path)\s*=\s*"([^"]*)"""")
    private val ANY_STR_RE = Regex("""\"([^"]*)\"""")
    private val HANDLER_RE = Regex("""\b(\w+)\s*\(""")

    // `static final String NAME = "value";` constant declarations — used to resolve a data-object key
    // referenced by a constant (e.g. a generated model-keys class field) back to its literal value.
    private val STRING_CONST_RE = Regex("""\bstatic\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"""")

    // A string literal that is the first argument of a method call — `method("literal"` — so the
    // literal's call context is known. Literals passed to a known key-taking Flowable API produce a
    // confident CODE→MODEL edge; any other literal that happens to equal a model key only a suspect one.
    private val STR_CTX_RE = Regex("""\b(\w+)\s*\(\s*"([^"\\\n]{2,80})"""")
    private val KEY_API_METHODS = setOf(
        // engine + platform methods whose (first) String argument is a model key
        "startProcessInstanceByKey", "startProcessInstanceByKeyAndTenantId", "startProcessInstanceByMessage",
        "processDefinitionKey", "processDefinitionKeyLike", "processDefinitionKeyLikeIgnoreCase",
        "caseDefinitionKey", "caseDefinitionKeyLike", "caseDefinitionKeyLikeIgnoreCase",
        "decisionKey", "formDefinitionKey", "getFormModelByKey", "getFormModelWithVariablesByKey",
        "getFormInstanceModelByKey", "definitionKey", "dataObjectDefinitionKey",
        "eventDefinitionKey", "channelDefinitionKey", "getEventModelByKey", "getChannelModelByKey",
        "serviceKey", "operationKey", "actionDefinitionKey", "templateKey", "processTemplate",
        "agentDefinitionKey", "getServiceDefinitionModelByKey", "getServiceDefinitionByKey",
        "getActionDefinitionModelByKey", "getActionDefinitionByKey", "getPolicyModelByKey",
        "taskFormKey", "formKey", "key", "operation", "messageName", "signalEventReceived",
        "signalEventReceivedAsync", "messageEventReceived", "messageEventReceivedAsync",
    )
    // Flowable data-object runtime builder chain: `.definitionKey(<expr>) … .operation("<literal>")`.
    private val DEFINITION_KEY_RE = Regex("""\.definitionKey\(\s*([^)]+?)\s*\)""")
    private val OPERATION_CALL_RE = Regex("""\.operation\(\s*"([^"]+)"\s*\)""")
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

    /** Replace comment bodies with spaces (newlines preserved) so scans skip commented-out code. */
    private fun blankComments(text: String): String =
        COMMENT_RE.replace(text) { m -> NON_NEWLINE.replace(m.value, " ") }

    private fun mappingPath(args: String?): String {
        if (args.isNullOrEmpty()) return ""
        val m = VALUE_PATH_RE.find(args) ?: ANY_STR_RE.find(args)
        return m?.groupValues?.get(1) ?: ""
    }

    fun parseJava(rawText: String, ffile: String): Map<String, Any?> {
        val text = blankComments(rawText)
        fun lineOf(idx: Int): Int = text.substring(0, idx).count { it == '\n' } + 1

        val pkg = PKG_RE.find(text)?.groupValues?.get(1) ?: ""
        val types = TYPE_RE.findAll(text).map { it.groupValues[2] }.toList()
        val primary = types.firstOrNull() ?: ffile.substringAfterLast('/').substringBeforeLast('.')

        val beanNames = LinkedHashSet<String>()
        for (m in BEAN_ANN_RE.findAll(text)) {
            beanNames.add(m.groupValues[2].ifEmpty { decap(primary) })
        }
        val interfaces = LinkedHashSet<String>()
        for (m in IMPLEMENTS_RE.findAll(text)) {
            for (it in m.groupValues[1].split(",")) {
                interfaces.add(Regex("<.*?>").replace(it, "").trim().substringAfterLast('.'))
            }
        }

        val isController = CONTROLLER_RE.containsMatchIn(text)
        val classDeclIdx = text.indexOf("class ")
        val endpoints = ArrayList<Map<String, Any?>>()
        if (isController) {
            var base = ""
            for (m in MAPPING_RE.findAll(text)) {
                if (classDeclIdx != -1 && m.range.first < classDeclIdx && m.groupValues[1] == "Request") {
                    base = mappingPath(m.groupValues.getOrNull(2)); break
                }
            }
            for (m in MAPPING_RE.findAll(text)) {
                if (classDeclIdx != -1 && m.range.first < classDeclIdx) continue
                val verb = m.groupValues[1]
                val args = m.groups[2]?.value
                var http = if (verb == "Request") "ANY" else verb.uppercase()
                if (verb == "Request" && !args.isNullOrEmpty()) {
                    http = REQUEST_METHOD_RE.find(args)?.groupValues?.get(1) ?: "ANY"
                }
                val path = mappingPath(args)
                val tail = text.substring(m.range.last + 1, minOf(m.range.last + 1 + 400, text.length))
                val handler = HANDLER_RE.find(tail)?.groupValues?.get(1) ?: "?"
                val full = "/" + (base + "/" + path).split("/").filter { it.isNotEmpty() }.joinToString("/")
                endpoints.add(linkedMapOf("http" to http, "path" to full, "handler" to handler, "line" to lineOf(m.range.first)))
            }
        }

        val methods = ArrayList<Map<String, Any?>>()
        val seenM = HashSet<String>()
        for (m in METHOD_RE.findAll(text)) {
            val nm = m.groupValues[1]
            if (nm in CONTROL_KEYWORDS) continue
            val params = m.groupValues[2].trim()
            val arity = if (params.isEmpty()) 0 else params.count { it == ',' } + 1
            val sig = "$nm/$arity"
            if (!seenM.add(sig)) continue
            methods.add(linkedMapOf("name" to nm, "params" to arity, "line" to lineOf(m.range.first)))
        }

        val deps = LinkedHashSet<String>()
        FIELD_RE.findAll(text).forEach { deps.add(it.groupValues[1]) }
        val ctorRe = Regex("""(?:public|protected)\s+""" + Regex.escape(primary) + """\s*\(([^)]*)\)""")
        val ctorParamRe = Regex("""\b([A-Z]\w+)(?:<[^>]*>)?\s+\w+""")
        for (cm in ctorRe.findAll(text)) {
            ctorParamRe.findAll(cm.groupValues[1]).forEach { deps.add(it.groupValues[1]) }
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
        val keyedStrings = LinkedHashSet<String>()
        for (m in STR_CTX_RE.findAll(text)) {
            if (m.groupValues[1] in KEY_API_METHODS) keyedStrings.add(m.groupValues[2])
        }

        return linkedMapOf(
            "file" to ffile, "package" to pkg, "primary" to primary,
            "fqn" to (if (pkg.isNotEmpty()) "$pkg.$primary" else primary),
            "types" to types, "beanNames" to beanNames, "interfaces" to interfaces, "roles" to roles,
            "isController" to isController, "isGlue" to interfaces.any { it in GLUE_INTERFACES },
            "endpoints" to endpoints, "methods" to methods, "deps" to deps, "botKey" to botKey,
            "vars" to JAVA_VAR_RE.findAll(text).map { it.groupValues[1] }.toSortedSet().toList(),
            "strings" to JAVA_STR_RE.findAll(text).map { it.groupValues[1] }.toCollection(LinkedHashSet()),
            "keyedStrings" to keyedStrings,
            "topics" to TOPIC_CALL_RE.findAll(text).map { it.groupValues[1] }.toSortedSet().toList(),
            "line" to (if (classDeclIdx != -1) lineOf(classDeclIdx) else 1),
        )
    }

    /** A `static final String NAME = "value"` constant declared in the source (name → value). */
    fun stringConstants(rawText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in STRING_CONST_RE.findAll(blankComments(rawText))) out.putIfAbsent(m.groupValues[1], m.groupValues[2])
        return out
    }

    /** Flowable data-object operation invocations: each `.operation("op")` paired with the nearest
     *  preceding `.definitionKey(expr)` in the same statement (no `;` between). `def` is kept raw — a
     *  quoted literal or a constant reference (e.g. a generated model-keys class field) — for the caller
     *  to resolve to a model key. Returns `{def, op}` maps. */
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

    private val SCHEME_HOST_RE = Regex("""^[a-z]+://[^/]+""")
    private val PLACEHOLDER_RE = Regex("""[#$]\{[^}]*\}|\{\{[^}]*\}\}|\{[^}]*\}""")

    private fun normPath(url: String?): List<String> {
        var p = SCHEME_HOST_RE.replace(url ?: "", "")
        p = p.substringBefore("?")
        p = PLACEHOLDER_RE.replace(p, "*")
        return p.lowercase().split("/").filter { it.isNotEmpty() }
    }

    /** REST endpoints whose path matches [url]. A segment-suffix match (placeholders `*` match any
     *  segment) is a confident hit; the legacy shared-last-literal-segment rule still matches but is
     *  annotated `loose=true` so the graph flags the edge as suspect instead of presenting it clean. */
    fun matchRest(url: String?, codeEndpoints: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val target = normPath(url)
        if (target.isEmpty()) return emptyList()
        fun segsMatch(ep: List<String>, tail: List<String>): Boolean =
            ep.size == tail.size && ep.indices.all { ep[it] == "*" || tail[it] == "*" || ep[it] == tail[it] }
        val matches = ArrayList<Map<String, Any?>>()
        for (ep in codeEndpoints) {
            val epSegs = normPath(ep["path"] as? String)
            if (epSegs.isEmpty()) continue
            val suffix = target.size >= epSegs.size &&
                segsMatch(epSegs, target.subList(target.size - epSegs.size, target.size))
            // A model URL that begins with a variable segment (`${base}`/`{{base}}` → "*") may stand for
            // a multi-segment base, so the endpoint's trailing segments still identify it even when the
            // model path is shorter than the endpoint path (the leading "*" absorbs the extra base segs).
            val varBase = !suffix && target.first() == "*" && target.size in 2..epSegs.size &&
                segsMatch(epSegs.subList(epSegs.size - (target.size - 1), epSegs.size), target.subList(1, target.size))
            val lits = epSegs.filter { it != "*" }
            when {
                suffix || varBase -> matches.add(ep)
                lits.isNotEmpty() && lits.last() in target -> matches.add(ep + mapOf("loose" to true))
            }
        }
        return matches
    }
}
