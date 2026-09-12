package com.flowable.atlas.parsing

/**
 * Shared name sets ported from `flowable_atlas.py` (~lines 86-118). Grown as parsers that need more
 * of them are ported; for now the two consulted by variable/reference collection.
 */
object Constants {

    /**
     * Implicit roots the engine provides — never treated as a project variable/bean. `planItemInstances`
     * is the CMMN query root (`CmmnVariableScopeELResolver.PLAN_ITEM_INSTANCES_KEY`,
     * `${planItemInstances.definitionId('x').active().count()}`), `currentTenantId` comes from
     * `VariableContainerELResolver`; both were read as beans of the project's own before.
     */
    val FLOWABLE_CONTEXT = setOf(
        "execution", "task", "caseInstance", "planItemInstance", "planItemInstances", "processInstance",
        "variableContainer", "authenticatedUserId", "authenticatedUser", "currentUserId", "currentTenantId",
        "loggedInUser", "dateUtil", "date", "currentTime", "now", "initiator",
        "loopCounter", "variables", "vars", "var", "entityManagerFactory", "environment",
        "cmmnRuntimeService", "runtimeService", "taskService", "repetitionCounter",
        "root", "self", "parent", "caseInstanceId", "processInstanceId",
    )

    /**
     * Whether a name is shaped the way Spring beans are named — `orderService`, `pdfGeneratorTask`,
     * `flwTimeUtils` — for a root no declaration vouches for. `${x.method()}` reads like a bean call and
     * like a method on a variable's value, and a Design export carries no Java to resolve against: a
     * project's own `${userProfileDataService.getUserProfile(x)}` and a variable read such as
     * `${requesterData.getName()}` or `${attachments.size()}` look the same to the harvest. The suffix is
     * what separates them. Measured over four real projects: all 25 variable roots misread as beans fail
     * this test, every project bean in the same lists passes it.
     */
    private val BEAN_NAME_SUFFIX = Regex(
        "(?:Service|Task|Bean|Delegate|Utils|Helper|Repository|Client|Mapper|Handler|Provider|Factory|" +
            "Resolver|Listener|Manager|Facade|Gateway|Adapter|Api|Dao|Component|Controller|Endpoint|Registry|" +
            "Publisher|Sender|Validator|Converter|Processor|Executor|Generator|Builder)$",
    )
    fun looksLikeBeanName(name: String): Boolean = name.length > 4 && BEAN_NAME_SUFFIX.containsMatchIn(name)

    /**
     * The roots of a `{{…}}` binding that are the form runtime's own scratch space, never a process or
     * case variable: `$temp`, `$response`, a table's `$row`, the router's `$route`, a list's `$item` and
     * `$index`… Written with or without the `$`, since both spellings occur. One set for the two places
     * that decide what a binding reads (it used to be declared twice, and a root added to one copy was
     * missing from the other).
     */
    val FRONTEND_SCRATCH_ROOTS = setOf(
        "endpoints", "item", "index", "itemParent", "ctx", "root", "parent", "event", "self",
        "first", "last", "start", "pageSize", "flw", "payload", "temp", "filter",
        "sortColumn", "sortDirection", "orderBy", "sortBy", "total", "response",
        "page", "size", "data", "value", "params", "row", "route", "formValid", "lang", "scope", "original",
        "path", "column", "rowIndex",
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
     *
     * The names are read off the platform's own auto-configuration, not guessed — regenerate from
     * the platform's `starters` module: `TasksAutoConfiguration` (the task delegates Design writes),
     * `EngageTaskAutoConfiguration` (the Engage task delegates), `PlatformEngineServicesAutoConfiguration`
     * and `PlatformServiceAutoConfiguration` (the platform services an expression may call),
     * `PlatformExpressionsAutoConfiguration` (the `flw*Utils` helpers). `flw` itself is the platform's EL
     * root for its scripting API (`FlwApiELResolver`): `${flw.setOutput(…)}` in a task listener is
     * platform API, not a bean of the project's — 36 "unresolved beans" on one real project said otherwise.
     * `jacksonObjectMapper` is Spring Boot's `ObjectMapper` bean and `environmentEndpoint` the actuator's;
     * the platform starter brings both.
     */
    val FLOWABLE_PLATFORM_BEANS = setOf(
        "initVariablesService", "dataObjectServiceTask", "generateDocumentService",
        "createDocumentService", "serviceRegistryService", "agentService",
        "sendEventServiceTask", "auditLogService", "decisionServiceTask",
        "caseServiceTask", "httpServiceTask", "scriptServiceTask", "mailServiceTask",
        "mergeDocumentService", "convertDocumentToPDFService", "housekeepingServiceTask",
        "generateSequenceServiceTask", "triggerIntentEvaluationServiceTask", "flowablePlatformAbbyyService",
        "processCreateConversationTask", "caseCreateConversationTask", "processModifyConversationTask",
        "caseModifyConversationTask", "processSendMessageTask", "caseSendMessageTask",
        "whatsAppInteractiveMessageTask", "engageConversationService", "engageMessageService",
        "flwCollectionUtils", "flwJsonUtils", "flwFormatUtils", "flwLocaleUtils", "flwMathUtils",
        "flwStringUtils", "flwTimeUtils", "flwDateFunctionUtils", "flwIOUtils", "flwAuthTokenUtils",
        "flwBase64Utils", "flwContentItem", "propertyConfigurationService", "flw",
        "commentService", "translationService", "encryptionService", "platformCommentService",
        "platformTaskService", "platformCaseInstanceService", "platformProcessInstanceService",
        "platformContentItemService", "coreContentService", "platformFormService", "queryService",
        "jacksonObjectMapper", "environmentEndpoint",
        // the engine's own services, exposed as beans and called from expressions
        // (`${dataObjectRuntimeService.addUserIdentityLink(…)}`): engine API, not code of the project's
        "dataObjectRuntimeService", "dataObjectRepositoryService", "platformIdentityService", "idmIdentityService",
        "formService", "contentService", "templateService", "flowablePlatformTemplateService", "sequenceService",
        "historyService", "managementService", "repositoryService", "cmmnRepositoryService", "cmmnTaskService",
        "cmmnHistoryService", "eventRegistry", "eventRepositoryService", "actionRuntimeService",
        "actionRepositoryService", "dashboardService",
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
