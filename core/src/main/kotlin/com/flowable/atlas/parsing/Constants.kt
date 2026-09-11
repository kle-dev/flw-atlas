package com.flowable.atlas.parsing

/**
 * Shared name sets ported from `flowable_atlas.py` (~lines 86-118). Grown as parsers that need more
 * of them are ported; for now the two consulted by variable/reference collection.
 */
object Constants {

    /** Implicit roots the engine provides — never treated as a project variable/bean. */
    val FLOWABLE_CONTEXT = setOf(
        "execution", "task", "caseInstance", "planItemInstance", "processInstance",
        "variableContainer", "authenticatedUserId", "authenticatedUser", "currentUserId",
        "loggedInUser", "dateUtil", "date", "currentTime", "now", "initiator",
        "loopCounter", "variables", "vars", "var", "entityManagerFactory", "environment",
        "cmmnRuntimeService", "runtimeService", "taskService", "repetitionCounter",
        "root", "self", "parent", "caseInstanceId", "processInstanceId",
    )

    /** EL keywords / literals that are never variable names. */
    val JAVA_LITERALS = setOf(
        "true", "false", "null", "empty", "and", "or", "not", "div", "mod",
        "instanceof", "gt", "lt", "ge", "le", "eq", "ne", "new",
    )

    /**
     * Beans the Flowable platform provides (engine-provided, not project source): the delegate Design
     * writes into every task type's `delegateExpression`, and the `flw*Utils` expression helpers the
     * platform registers (`PlatformExpressionsAutoConfiguration`). One set for every surface: the graph
     * marks these `platform`, the reports list them apart from the project's own beans, and the runtime
     * checks know that a call into one of them stays inside the engine. It used to be declared three
     * times, once per renderer, which is how a bean could be "platform" on one page and "review" on the next.
     */
    val FLOWABLE_PLATFORM_BEANS = setOf(
        "initVariablesService", "dataObjectServiceTask", "generateDocumentService",
        "createDocumentService", "serviceRegistryService", "agentService",
        "sendEventServiceTask", "auditLogService", "decisionServiceTask",
        "caseServiceTask", "httpServiceTask", "scriptServiceTask", "mailServiceTask",
        "flwCollectionUtils", "flwJsonUtils", "flwFormatUtils", "flwLocaleUtils", "flwMathUtils",
        "flwStringUtils", "flwTimeUtils", "flwDateFunctionUtils", "flwIOUtils", "flwAuthTokenUtils",
        "flwBase64Utils", "flwContentItem", "propertyConfigurationService",
    )

    /**
     * Model keys the platform ships itself (the `.event` files under `com/flowable/design/system/` in the platform
     * and engage palettes — `*` in a KDoc path would open a nested comment). A project consumes them without defining them, so a reference to one is a
     * platform-provided external, not a missing model.
     */
    val FLOWABLE_PLATFORM_MODEL_KEYS = setOf(
        "_flowableMailEvent",
        "_flowableEngageExternalMessageReceived",
        "_flowableEngageMessageReceivedNoAccount", "_flowableEngageMessageReceivedNoAccountAndTenant",
        "_flowableEngageMessageReceivedInactiveAccount",
        "_flowableEngageReactionReceivedNoAccount", "_flowableEngageReactionReceivedNoAccountAndTenant",
        "_flowableEngageReactionReceivedInactiveAccount",
    )

    // Harvesting regexes — ported from flowable_atlas.py (~lines 69-72, 1296).
    // A backslash before the `$`/`#` is the author saying "literal, do not evaluate" — in a Groovy
    // GString, in a Java string, in a JSON body — so `\${x}` is not an expression and is not harvested;
    // it used to be validated like one and could only ever come out wrong.
    val EXPR_RE = Regex("(?<!\\\\)[#$]\\{[^}]*\\}")
    val MUSTACHE_RE = Regex("\\{\\{[^}]*\\}\\}")
    val METHOD_CALL_FULL_RE = Regex("(?<![\\w.\$])([A-Za-z_][\\w]*)\\s*\\.\\s*([A-Za-z_][\\w]*)\\s*\\(")
    val DELEGATE_CLASS_RE = Regex("(?:flowable|activiti):class=\"([^\"]+)\"")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )
    private val ENTITY_RE = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);")

    /** Minimal HTML/XML entity unescape mirroring Python `html.unescape` for the entities models use. */
    fun htmlUnescape(s: String): String = ENTITY_RE.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED_ENTITIES[body] ?: m.value
        }
    }
}
