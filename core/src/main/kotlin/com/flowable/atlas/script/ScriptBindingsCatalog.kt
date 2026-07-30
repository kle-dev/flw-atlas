package com.flowable.atlas.script

/** Where a script runs — decides which root objects Flowable binds into it. */
enum class ScriptContext(val display: String) {
    BPMN_SCRIPT_TASK("Script task (BPMN)"),
    BPMN_EXECUTION_LISTENER("Execution listener (BPMN)"),
    BPMN_TASK_LISTENER("Task listener (BPMN)"),
    CMMN_SCRIPT_TASK("Script task (CMMN)"),
    CMMN_TASK_LISTENER("Task listener (CMMN)"),
    ACTION_BOT("Action bot"),
    UNKNOWN("Unknown context"),
}

/**
 * A root object a script context binds. [members] is the object's callable surface — member name →
 * parameter signature text (`"variableName, value"`; `""` = no parameters; `"…"` = parameters not
 * catalogued) — used by the member-typo check and by completion. `null` means "the object exists
 * but its surface is not catalogued" (engine services, the raw output container) — member checks
 * and completion are skipped there. [subObjects] are one level of navigable API objects
 * (`flw.time`, `flw.string`, …).
 */
data class ScriptRoot(
    val name: String,
    val doc: String,
    val members: Map<String, String>? = null,
    val subObjects: Map<String, ScriptRoot> = emptyMap(),
    /** Legacy aliases stay valid for the checks but are hidden from completion and the chips. */
    val hidden: Boolean = false,
    /** Resolves as a Spring bean (Work platform), not an engine binding — completion shows it as
     *  such, and the chips leave it out to stay readable. */
    val bean: Boolean = false,
)

/**
 * The hand-maintained catalog of what Flowable binds into scripts, per context — the scripting
 * counterpart of [com.flowable.atlas.expr.catalog.FlowableExpressionCatalog].
 *
 * Transcribed (2026-07) from the engine sources:
 *  - community `flowable-engine` 8.1.0-SNAPSHOT — `VariableScopeResolver` (BPMN: the scope key is
 *    `execution` for an ExecutionEntity XOR `task` for a TaskEntity — never both) and
 *    `CmmnVariableScopeResolver` (`caseInstance` / `planItemInstance` / `task`), plus the service
 *    bindings each registers; member surfaces from `VariableScope`, `DelegateExecution`,
 *    `DelegateTask`, `DelegatePlanItemInstance`/`PlanItemInstance` and `CaseInstance`;
 *  - commercial `flowable-platform` 2026.2.0-SNAPSHOT — `FlowableApiResolver` binds the script
 *    `flw` (`FlowableApiInstance`) into BPMN/CMMN/bot/service-registry scripts; the bot context
 *    (`ScriptEvaluationBot`) additionally binds `flwActionContext`.
 *
 * The member sets are the UNION across supported versions (3.17 has no `flw.error`/`flw.cmmn`);
 * combined with the typo-gate (only near-miss members are flagged) that can hide a
 * newer-version-only mistake but can never invent one. Spring beans and process/case variables
 * also resolve by bare name in every engine context, which is why unknown roots are never errors.
 */
object ScriptBindingsCatalog {

    // ------------------------------------------------------------------ shared member surfaces

    /**
     * Member name → parameter signature text; `""` = no parameters, `"…"` = not catalogued.
     * [derive] fills the mechanical cases (getters `""`, setters `"value"`); explicit overrides
     * carry the real names where they matter.
     */
    private fun derive(names: Set<String>, overrides: Map<String, String> = emptyMap()): Map<String, String> =
        names.associateWith { n ->
            overrides[n] ?: when {
                n.startsWith("get") || n.startsWith("is") || n.startsWith("has") && n.endsWith("s") -> ""
                n.startsWith("set") -> "value"
                else -> "…"
            }
        }

    /** `org.flowable.variable.api.delegate.VariableScope` (+ `VariableContainer`). */
    private val VARIABLE_SCOPE_MEMBERS: Map<String, String> = buildMap {
        for (t in listOf("", "Transient")) {
            for (l in listOf("", "Local")) {
                put("get${t}Variable$l", "variableName")
                put("set${t}Variable$l", "variableName, value")
                put("remove${t}Variable$l", "variableName")
                put("get${t}Variables$l", "")
                put("set${t}Variables$l", "variables")
                put("remove${t}Variables$l", "")
            }
        }
        for (l in listOf("", "Local")) {
            put("hasVariable$l", "variableName")
            put("hasVariables$l", "")
            put("getVariableInstance$l", "variableName")
            put("getVariableInstances$l", "")
            put("getVariableNames$l", "")
        }
        put("getTenantId", "")
    }

    /** `org.flowable.engine.delegate.DelegateExecution`'s own methods. */
    private val EXECUTION_MEMBERS = VARIABLE_SCOPE_MEMBERS + derive(setOf(
        "getId", "getProcessInstanceId", "getRootProcessInstanceId", "getEventName", "setEventName",
        "getProcessInstanceBusinessKey", "getProcessInstanceBusinessStatus", "getProcessDefinitionId",
        "getPropagatedStageInstanceId", "getParentId", "getSuperExecutionId", "getCurrentActivityId",
        "getCurrentActivityName", "getCurrentFlowElement", "setCurrentFlowElement",
        "getCurrentFlowableListener", "setCurrentFlowableListener", "snapshotReadOnly", "getParent",
        "getExecutions", "setActive", "isActive", "isEnded", "setConcurrent", "isConcurrent",
        "isProcessInstanceType", "inactivate", "isScope", "setScope", "isMultiInstanceRoot",
        "setMultiInstanceRoot",
    ), overrides = mapOf("snapshotReadOnly" to "", "inactivate" to ""))

    /** `org.flowable.task.service.delegate.DelegateTask`'s own methods. */
    private val TASK_MEMBERS = VARIABLE_SCOPE_MEMBERS + derive(setOf(
        "getId", "getName", "setName", "getDescription", "setDescription", "getPriority", "setPriority",
        "getProcessInstanceId", "getExecutionId", "getProcessDefinitionId", "getState", "getCreateTime",
        "getInProgressStartTime", "getInProgressStartedBy", "getClaimTime", "getClaimedBy",
        "getSuspendedTime", "getSuspendedBy", "getTaskDefinitionKey", "isSuspended",
        "getFormKey", "setFormKey", "getEventName", "getEventHandlerId", "getDelegationState",
        "getOwner", "setOwner", "getAssignee", "setAssignee",
        "getInProgressStartDueDate", "setInProgressStartDueDate", "getDueDate", "setDueDate",
        "getCategory", "setCategory",
        "addCandidateUser", "addCandidateUsers", "addCandidateGroup", "addCandidateGroups",
        "addUserIdentityLink", "addGroupIdentityLink", "deleteCandidateUser", "deleteCandidateGroup",
        "deleteUserIdentityLink", "deleteGroupIdentityLink", "getCandidates",
    ), overrides = mapOf(
        "setName" to "name", "setDescription" to "description", "setPriority" to "priority",
        "setFormKey" to "formKey", "setOwner" to "userId", "setAssignee" to "userId",
        "setDueDate" to "dueDate", "setCategory" to "category",
        "addCandidateUser" to "userId", "addCandidateUsers" to "candidateUsers",
        "addCandidateGroup" to "groupId", "addCandidateGroups" to "candidateGroups",
        "addUserIdentityLink" to "userId, identityLinkType",
        "addGroupIdentityLink" to "groupId, identityLinkType",
        "deleteCandidateUser" to "userId", "deleteCandidateGroup" to "groupId",
        "deleteUserIdentityLink" to "userId, identityLinkType",
        "deleteGroupIdentityLink" to "groupId, identityLinkType",
    ))

    /** `DelegatePlanItemInstance` = the `PlanItemInstance` getters + setters + `VariableScope`. */
    private val PLAN_ITEM_MEMBERS = VARIABLE_SCOPE_MEMBERS + derive(setOf(
        "getId", "getName", "getState", "getCaseDefinitionId", "getDerivedCaseDefinitionId",
        "getCaseInstanceId", "getStageInstanceId", "isStage", "getElementId", "getPlanItemDefinitionId",
        "getPlanItemDefinitionType", "getStartTime", "getCreateTime",
        "getLastAvailableTime", "getLastUnavailableTime", "getLastEnabledTime", "getLastDisabledTime",
        "getLastStartedTime", "getLastSuspendedTime", "getCompletedTime", "getOccurredTime",
        "getTerminatedTime", "getFailedTime", "getExitTime", "getEndedTime",
        "getStartUserId", "getAssignee", "getCompletedBy", "getReferenceId", "getReferenceType",
        "isCompletable", "getEntryCriterionId", "getExitCriterionId", "getFormKey", "getExtraValue",
        "getPlanItemInstanceLocalVariables", "setLocalizedName",
        "setName", "setState", "setCaseDefinitionId", "setDerivedCaseDefinitionId", "setCaseInstanceId",
        "setStageInstanceId", "setStage", "setElementId", "setPlanItemDefinitionId",
        "setPlanItemDefinitionType", "setStartTime", "setCreateTime",
        "setLastAvailableTime", "setLastUnavailableTime", "setLastEnabledTime", "setLastDisabledTime",
        "setLastStartedTime", "setLastSuspendedTime", "setCompletedTime", "setOccurredTime",
        "setTerminatedTime", "setFailedTime", "setExitTime", "setEndedTime",
        "setStartUserId", "setAssignee", "setCompletedBy", "setReferenceId", "setReferenceType",
        "setCompletable", "setEntryCriterionId", "setExitCriterionId", "setFormKey", "setExtraValue",
        "setTenantId", "getCurrentLifecycleListener", "getCurrentFlowableListener",
        "setCurrentLifecycleListener", "getPlanItem", "getPlanItemDefinition", "snapshotReadOnly",
    ), overrides = mapOf(
        "snapshotReadOnly" to "", "setLocalizedName" to "name",
        "setCurrentLifecycleListener" to "listener, sourceState, targetState",
    ))

    /** `org.flowable.cmmn.api.runtime.CaseInstance` (runtime entity also implements VariableScope). */
    private val CASE_INSTANCE_MEMBERS = VARIABLE_SCOPE_MEMBERS + derive(setOf(
        "getId", "getParentId", "getBusinessKey", "getBusinessStatus", "getName",
        "getCaseDefinitionId", "getCaseDefinitionKey", "getCaseDefinitionName",
        "getCaseDefinitionVersion", "getCaseDefinitionDeploymentId",
        "getState", "getStartTime", "getStartUserId", "getLastReactivationTime",
        "getLastReactivationUserId", "getCallbackId", "getCallbackType", "getReferenceId",
        "getReferenceType", "isCompletable", "getDueDate", "getClaimTime", "getClaimedBy",
        "getCaseVariables", "setLocalizedName",
    ), overrides = mapOf("setLocalizedName" to "name"))

    // ------------------------------------------------------------------ the platform flw API

    /** Signature overrides for the flw tree; everything not listed derives (getters/setters) or `…`. */
    private val FLW_SIGS = mapOf(
        "stringToJson" to "json", "jsonToString" to "node",
        "createObject" to "", "createArray" to "",
        "throwForbidden" to "message", "throwIllegalArgument" to "message", "throwNotFound" to "message",
        "throwBusinessError" to "errorCode, message", "throwError" to "errorCode, message",
        "throwFault" to "faultCode, message",
        "now" to "", "currentDate" to "", "currentLocalDate" to "", "currentLocalDateTime" to "",
        "newline" to "", "carriageReturn" to "",
        "parseInt" to "value", "parseDouble" to "value",
    )

    private fun flwSub(name: String, doc: String, vararg members: String) =
        ScriptRoot(name, doc, derive(members.toSet(), FLW_SIGS))

    /** Script `flw` (`FlowableApiInstance`) — NOT the EL `flw` (see [EL_ONLY_FLW_MEMBERS]). */
    private val FLW = ScriptRoot(
        "flw", "Flowable platform script API (Work/Engage)",
        members = mapOf(
            "getInput" to "name", "setOutput" to "name, value",
            "setTransientOutput" to "name, value", "setLocalOutput" to "name, value",
        ),
        subObjects = listOf(
            flwSub("json", "JSON helpers",
                "createObject", "createArray", "stringToJson", "jsonToString"),
            flwSub("error", "error signalling (2026.x)",
                "throwForbidden", "throwIllegalArgument", "throwNotFound", "throwBusinessError"),
            flwSub("bpmn", "BPMN error signalling (2026.x)", "throwError"),
            flwSub("cmmn", "CMMN fault signalling (2026.x)", "throwFault"),
            flwSub("time", "date/time helpers",
                "parseInstant", "parseLocalDate", "parseLocalDateTime", "asInstant", "asLocalDate",
                "asLocalDateTime", "asDate", "atTime", "atTimeWithTimeZone", "atTimeZone",
                "getAvailableTimeZoneIds", "getField", "isWeekend", "fullDateTimeInstant",
                "fullDateTimeDate", "plusSeconds", "plusMinutes", "plusHours", "plusDays", "plusWeeks",
                "plusMonths", "plusYears", "plusDuration", "minusSeconds", "minusMinutes", "minusHours",
                "minusDays", "minusWeeks", "minusMonths", "minusYears", "minusDuration",
                "secondsOfDuration", "currentDate", "isBefore", "isBeforeOrEqual", "isAfter",
                "isAfterOrEqual", "areEqual", "isBeforeTime", "isAfterTime", "secondsBetweenDates",
                "getFieldFromDurationBetweenDates", "secondsBetween", "minutesBetween", "hoursBetween",
                "daysBetween", "weeksBetween", "monthsBetween", "yearsBetween", "getTimeZoneOffset",
                "instantFromTimestamp", "dateFromTimestamp", "now", "currentLocalDate",
                "currentLocalDateTime"),
            flwSub("string", "string helpers",
                "toUpperCase", "toLowerCase", "capitalize", "trimWhitespace", "hasText", "contains",
                "containsIgnoreCase", "matches", "replaceAll", "equals", "equalsIgnoreCase",
                "substring", "substringFrom", "split", "join", "newline", "carriageReturn",
                "unescapeHtml", "escapeHtml"),
            flwSub("math", "math helpers",
                "sum", "average", "floor", "ceil", "round", "min", "max", "abs", "median",
                "parseDouble", "parseInt"),
            flwSub("locale", "locale helpers",
                "getLocaleForLanguageTag", "getAvailableLocales", "getDefaultLocale",
                "getAllCountryCodes", "getAllLanguageCodes", "getLanguageDisplayName",
                "getCountryDisplayName", "getAllLanguageDisplayNames", "getAllCountryDisplayNames"),
            flwSub("format", "formatting helpers",
                "formatString", "formatDate", "formatDecimal", "formatStringWithLocale",
                "formatCurrencyWithLocale"),
        ).associateBy { it.name },
    )

    private val FLW_API = FLW.copy(name = "flwApi", doc = "alias of flw")

    private val FLW_OUTPUT = ScriptRoot("flwApiOutputContainer",
        "raw output VariableContainer behind flw.setOutput",
        members = mapOf(
            "hasVariable" to "variableName", "getVariable" to "variableName",
            "setVariable" to "variableName, value", "setTransientVariable" to "variableName, value",
            "getVariableNames" to "", "getTenantId" to "",
        ))

    private val FLW_ACTION_CONTEXT = ScriptRoot(
        "flwActionContext", "bot invocation context (ScriptBotInvocationContext)",
        members = derive(setOf("getHistoricActionInstance", "getActionDefinition", "getActionDefinitionModel",
            "getPayload", "getIntent", "setIntent"), overrides = mapOf("setIntent" to "intent")),
    )

    // ------------------------------------------------------------------ contexts

    private fun service(name: String, doc: String, members: Map<String, String>? = null) =
        ScriptRoot(name, doc, members)

    /** `VariableScopeResolver` service keys. Member surfaces come generated from the engine
     *  sources ([ScriptServiceApis]). `identityServiceKey` is the engine's historical alias of
     *  `identityService` (kept for backwards compatibility) — valid, but not advertised. */
    private val BPMN_SERVICES = listOf(
        service("processEngineConfiguration", "engine configuration"),
        service("runtimeService", "BPMN runtime service", ScriptServiceApis.RUNTIME_SERVICE),
        service("taskService", "task service", ScriptServiceApis.TASK_SERVICE),
        service("repositoryService", "repository service", ScriptServiceApis.REPOSITORY_SERVICE),
        service("managementService", "management service", ScriptServiceApis.MANAGEMENT_SERVICE),
        service("historyService", "history service", ScriptServiceApis.HISTORY_SERVICE),
        service("formService", "form service", ScriptServiceApis.FORM_SERVICE),
        service("identityService", "identity service", ScriptServiceApis.IDENTITY_SERVICE),
        ScriptRoot("identityServiceKey", "legacy alias of identityService",
            ScriptServiceApis.IDENTITY_SERVICE, hidden = true),
    )

    /** `CmmnVariableScopeResolver` service keys (no repositoryService/formService in CMMN) — note
     *  `runtimeService`/`taskService`/… resolve to the CMMN services in a CMMN context. */
    private val CMMN_SERVICES = listOf(
        service("engineConfiguration", "CMMN engine configuration"),
        service("cmmnEngineConfiguration", "CMMN engine configuration"),
        service("runtimeService", "CMMN runtime service", ScriptServiceApis.CMMN_RUNTIME_SERVICE),
        service("cmmnRuntimeService", "CMMN runtime service", ScriptServiceApis.CMMN_RUNTIME_SERVICE),
        service("historyService", "CMMN history service", ScriptServiceApis.CMMN_HISTORY_SERVICE),
        service("cmmnHistoryService", "CMMN history service", ScriptServiceApis.CMMN_HISTORY_SERVICE),
        service("managementService", "CMMN management service", ScriptServiceApis.CMMN_MANAGEMENT_SERVICE),
        service("cmmnManagementService", "CMMN management service", ScriptServiceApis.CMMN_MANAGEMENT_SERVICE),
        service("taskService", "CMMN task service", ScriptServiceApis.CMMN_TASK_SERVICE),
        service("cmmnTaskService", "CMMN task service", ScriptServiceApis.CMMN_TASK_SERVICE),
    )

    private val EXECUTION = ScriptRoot("execution",
        "the current execution (DelegateExecution)", EXECUTION_MEMBERS)
    private val DELEGATE_TASK = ScriptRoot("task", "the current task (DelegateTask)", TASK_MEMBERS)
    private val PLAN_ITEM = ScriptRoot("planItemInstance",
        "the current plan item (DelegatePlanItemInstance)", PLAN_ITEM_MEMBERS)
    private val CASE_INSTANCE = ScriptRoot("caseInstance",
        "the case instance (CaseInstance)", CASE_INSTANCE_MEMBERS)

    /** In a CMMN *script task* the `task` binding is a `List<TaskEntity>` (usually empty), not a
     *  task — member checks are off for it, only its existence is catalogued. */
    private val CMMN_TASK_LIST = ScriptRoot("task",
        "List of tasks under this plan item — usually empty for a script task", members = null)

    private val FLW_ROOTS = listOf(FLW, FLW_API, FLW_OUTPUT)

    private fun bean(name: String, doc: String, members: Map<String, String>) =
        ScriptRoot(name, doc, members, bean = true)

    /**
     * The Flowable **platform** services scripts reach as Spring beans: in a Work installation the
     * `beans` map is the whole ApplicationContext, so these resolve by name in every script
     * context (BPMN, CMMN, bots). Bean names verified against the platform autoconfigurations;
     * surfaces generated in [ScriptPlatformApis]. Unavailable under sandbox strict-mode.
     */
    private val PLATFORM_BEAN_ROOTS: List<ScriptRoot> = listOf(
        bean("dataObjectRuntimeService", "data object runtime (Work)", ScriptPlatformApis.DATA_OBJECT_RUNTIME_SERVICE),
        bean("dataObjectRepositoryService", "data object repository (Work)", ScriptPlatformApis.DATA_OBJECT_REPOSITORY_SERVICE),
        bean("dataObjectManagementService", "data object management (Work)", ScriptPlatformApis.DATA_OBJECT_MANAGEMENT_SERVICE),
        bean("contentService", "content service (Work)", ScriptPlatformApis.CONTENT_SERVICE),
        ScriptRoot("coreContentService", "alias of contentService", ScriptPlatformApis.CONTENT_SERVICE,
            hidden = true, bean = true),
        bean("documentRepositoryService", "document definitions (Work)", ScriptPlatformApis.DOCUMENT_REPOSITORY_SERVICE),
        bean("renditionService", "content renditions (Work)", ScriptPlatformApis.RENDITION_SERVICE),
        bean("metadataService", "content metadata (Work)", ScriptPlatformApis.METADATA_SERVICE),
        bean("templateService", "template processing (Work)", ScriptPlatformApis.TEMPLATE_SERVICE),
        bean("templateRepositoryService", "template definitions (Work)", ScriptPlatformApis.TEMPLATE_REPOSITORY_SERVICE),
        bean("sequenceService", "sequence values (Work)", ScriptPlatformApis.SEQUENCE_SERVICE),
        bean("platformRuntimeService", "platform runtime (Work)", ScriptPlatformApis.PLATFORM_RUNTIME_SERVICE),
        bean("platformRepositoryService", "platform repository (Work)", ScriptPlatformApis.PLATFORM_REPOSITORY_SERVICE),
        bean("platformHistoryService", "platform history (Work)", ScriptPlatformApis.PLATFORM_HISTORY_SERVICE),
        bean("translationService", "translations (Work)", ScriptPlatformApis.TRANSLATION_SERVICE),
        bean("commentService", "comments (Work)", ScriptPlatformApis.COMMENT_SERVICE),
        bean("tenantVariableService", "tenant variables (Work)", ScriptPlatformApis.TENANT_VARIABLE_SERVICE),
        bean("encryptionService", "value encryption (Work)", ScriptPlatformApis.ENCRYPTION_SERVICE),
        bean("platformIdentityService", "platform identity (Work)", ScriptPlatformApis.PLATFORM_IDENTITY_SERVICE),
        bean("userDefinitionService", "user definitions (Work)", ScriptPlatformApis.USER_DEFINITION_SERVICE),
        bean("userAccountService", "user accounts (Work)", ScriptPlatformApis.USER_ACCOUNT_SERVICE),
        bean("actionRuntimeService", "action runtime (Work)", ScriptPlatformApis.ACTION_RUNTIME_SERVICE),
        bean("actionRepositoryService", "action definitions (Work)", ScriptPlatformApis.ACTION_REPOSITORY_SERVICE),
        bean("serviceRegistryRuntimeService", "service registry invocation (Work)", ScriptPlatformApis.SERVICE_REGISTRY_RUNTIME_SERVICE),
        bean("serviceRegistryRepositoryService", "service registry definitions (Work)", ScriptPlatformApis.SERVICE_REGISTRY_REPOSITORY_SERVICE),
        bean("auditService", "audit instances (Work)", ScriptPlatformApis.AUDIT_SERVICE),
        bean("notificationService", "notifications (Work)", ScriptPlatformApis.NOTIFICATION_SERVICE),
        bean("platformFormService", "form instances (Work)", ScriptPlatformApis.PLATFORM_FORM_SERVICE),
        bean("platformFormRepositoryService", "form definitions (Work)", ScriptPlatformApis.PLATFORM_FORM_REPOSITORY_SERVICE),
    )

    private val CONTEXT_ROOTS: Map<ScriptContext, Map<String, ScriptRoot>> = mapOf(
        ScriptContext.BPMN_SCRIPT_TASK to (listOf(EXECUTION) + FLW_ROOTS + BPMN_SERVICES + PLATFORM_BEAN_ROOTS),
        ScriptContext.BPMN_EXECUTION_LISTENER to (listOf(EXECUTION) + FLW_ROOTS + BPMN_SERVICES + PLATFORM_BEAN_ROOTS),
        // the scope key is task XOR execution: a task-listener script never sees `execution`
        ScriptContext.BPMN_TASK_LISTENER to (listOf(DELEGATE_TASK) + FLW_ROOTS + BPMN_SERVICES + PLATFORM_BEAN_ROOTS),
        ScriptContext.CMMN_SCRIPT_TASK to
            (listOf(PLAN_ITEM, CASE_INSTANCE, CMMN_TASK_LIST) + FLW_ROOTS + CMMN_SERVICES + PLATFORM_BEAN_ROOTS),
        ScriptContext.CMMN_TASK_LISTENER to
            (listOf(DELEGATE_TASK, PLAN_ITEM, CASE_INSTANCE) + FLW_ROOTS + CMMN_SERVICES + PLATFORM_BEAN_ROOTS),
        ScriptContext.ACTION_BOT to (FLW_ROOTS + FLW_ACTION_CONTEXT + PLATFORM_BEAN_ROOTS),
        ScriptContext.UNKNOWN to emptyList(),
    ).mapValues { (_, roots) -> roots.associateBy { it.name } }

    fun rootsFor(context: ScriptContext): Map<String, ScriptRoot> =
        CONTEXT_ROOTS[context] ?: emptyMap()

    /** Every root name any context binds — engine objects, services and platform beans. Feeds the
     *  variable heuristic's blocklist: none of these is ever a model variable. */
    val ALL_ROOT_NAMES: Set<String> = CONTEXT_ROOTS.values.flatMap { it.keys }.toSet()

    /**
     * The *scope* roots whose absence in a context is worth a warning (`execution`, `task`, …).
     * Services and `flw` are excluded on purpose: in Work they may still resolve as Spring beans,
     * so their absence from a context's resolver is not evidence of a mistake.
     */
    val SCOPE_ROOT_NAMES = setOf("execution", "task", "planItemInstance", "caseInstance")

    /** Which scope roots exist per context — the wrong-context check's ground truth. */
    fun scopeRootsFor(context: ScriptContext): Set<String> =
        rootsFor(context).keys.intersect(SCOPE_ROOT_NAMES)

    /** A pointed message for a scope root used in a context that does not bind it. */
    fun wrongContextMessage(root: String, context: ScriptContext): String {
        val available = scopeRootsFor(context)
        val hint = if (available.isEmpty()) "only the flw API is bound here"
            else "this context binds ${available.sorted().joinToString(", ") { "'$it'" }}"
        val detail = when {
            root == "execution" && context == ScriptContext.BPMN_TASK_LISTENER ->
                "task-listener scripts run against the task, not the execution"
            root == "execution" && (context == ScriptContext.CMMN_SCRIPT_TASK ||
                context == ScriptContext.CMMN_TASK_LISTENER) ->
                "CMMN scripts have no execution"
            root == "task" && (context == ScriptContext.BPMN_SCRIPT_TASK ||
                context == ScriptContext.BPMN_EXECUTION_LISTENER) ->
                "there is no task in this context"
            else -> "it is not bound in a ${context.display.lowercase()} script"
        }
        return "'$root' is not available here — $detail; $hint " +
            "(unless a variable of that name exists in the scope)"
    }

    /** The `flw.*` namespaces that exist only in `${…}` expressions, never in scripts. */
    val EL_ONLY_FLW_MEMBERS = setOf("base64", "io", "array", "data", "secret")

    /**
     * The JSR-223 engine names registered in a Flowable platform installation. Lookup is
     * case-sensitive with no aliasing (`JSR223FlowableScriptEngine` → `ScriptEngineManager
     * .getEngineByName`): `GROOVY` or `Javascript` fail at runtime with "Can't find scripting
     * engine". juel + groovy-static from Flowable's own factories; groovy from groovy-jsr223;
     * the JS names from Nashorn; python/jython from Jython where installed.
     */
    val REGISTERED_ENGINE_NAMES = setOf(
        "juel", "groovy", "Groovy", "groovy-static",
        "nashorn", "Nashorn", "js", "JS", "JavaScript", "javascript", "ECMAScript", "ecmascript",
        "python", "jython",
    )
}
