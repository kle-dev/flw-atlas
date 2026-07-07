package com.flowable.atlas.expr.catalog

import com.flowable.atlas.expr.ExpressionDialect

/**
 * A callable Flowable expression function.
 *
 *  - Backend namespaced functions carry a [prefix] (`date` in `date:now`); no-prefix backend
 *    functions (`listOf`, `mapOf`, `markdownToHtml`, IDM helpers) have `prefix == null`.
 *  - Frontend `flw.*` members have `prefix == null` and are written `flw.<name>` (see [FRONTEND_NS]).
 *
 * [aliases] are alternative local names accepted for the same function (`eq` for `equals`).
 */
data class ExprFunction(
    val prefix: String?,
    val name: String,
    val aliases: List<String> = emptyList(),
    val doc: String? = null,
) {
    /** The way the user writes the call head for [dialect] — `date:now`, `listOf`, or `flw.sum`. */
    fun label(dialect: ExpressionDialect): String = when {
        dialect == ExpressionDialect.FRONTEND -> "${FlowableExpressionCatalog.FRONTEND_NS}.$name"
        prefix != null -> "$prefix:$name"
        else -> name
    }

    /** Every local name this function answers to (canonical + aliases). */
    val allNames: List<String> get() = listOf(name) + aliases
}

/** An implicit root object available at the start of an expression (`execution`, `flw`, `$item`, …). */
data class ExprRoot(val name: String, val doc: String? = null)

/**
 * The single, hand-maintained, version-sensitive catalog of Flowable expression functions and root
 * objects — the expression counterpart of [com.flowable.atlas.completion.FlowableApiCatalog].
 *
 * Transcribed (and empirically verified) from the Flowable engine + platform sources: the
 * `FlowableFunctionDelegate` implementations (community `flowable-engine`/`-cmmn`/`-dmn` + commercial
 * `flowable-platform`), the `VariableScopeELResolver` family, and the frontend `@flowable/forms`
 * `flw.*` namespace (`Expression/functions/index.ts`). Adding support for new Flowable versions means
 * appending here — nothing else changes.
 */
object FlowableExpressionCatalog {

    /** The frontend function namespace object (`flw.sum(...)`). */
    const val FRONTEND_NS = "flw"

    // ---------------------------------------------------------------- BACKEND functions

    /** The 30 identity-link mutation functions shared by the `bpmn:` / `cmmn:` / `task:` prefixes. */
    private val IDENTITY_LINK_NAMES = listOf(
        "getAssignee", "setAssignee", "removeAssignee", "getOwner", "setOwner", "removeOwner",
        "addCandidateUser", "addCandidateUsers", "addCandidateGroup", "addCandidateGroups",
        "removeCandidateUser", "removeCandidateUsers", "removeCandidateGroup", "removeCandidateGroups",
        "addParticipantUser", "addParticipantUsers", "addParticipantGroup", "addParticipantGroups",
        "removeParticipantUser", "removeParticipantUsers", "removeParticipantGroup", "removeParticipantGroups",
        "addWatcherUser", "addWatcherUsers", "addWatcherGroup", "addWatcherGroups",
        "removeWatcherUser", "removeWatcherUsers", "removeWatcherGroup", "removeWatcherGroups",
    )

    /** Canonical prefix → its functions. `resolvePrefix` maps aliases (`vars`,`var`) to canonical. */
    private val backendByPrefix: Map<String, List<ExprFunction>> = linkedMapOf(
        "variables" to listOf(
            fn("variables", "get", doc = "value of a variable"),
            fn("variables", "getOrDefault", doc = "value, or a default if unset"),
            fn("variables", "contains", doc = "collection variable contains all values"),
            fn("variables", "containsAny", doc = "collection variable contains any value"),
            fn("variables", "containsAll", doc = "collection contains all (Platform)"),
            fn("variables", "notContains", doc = "collection does not contain (Platform)"),
            fn("variables", "notContainsAny", doc = "collection contains none (Platform)"),
            fn("variables", "notContainsAll", doc = "collection missing at least one (Platform)"),
            fn("variables", "equals", "eq", doc = "variable equals value"),
            fn("variables", "notEquals", "ne", doc = "variable does not equal value"),
            fn("variables", "exists", "exist", doc = "variable is set"),
            fn("variables", "isEmpty", "empty", doc = "variable is null/empty"),
            fn("variables", "isNotEmpty", "notEmpty", doc = "variable is set and not empty"),
            fn("variables", "lowerThan", "lessThan", "lt", doc = "variable < value"),
            fn("variables", "lowerThanOrEquals", "lessThanOrEquals", "lte", doc = "variable <= value"),
            fn("variables", "greaterThan", "gt", doc = "variable > value"),
            fn("variables", "greaterThanOrEquals", "gte", doc = "variable >= value"),
            fn("variables", "base64", doc = "variable value as base64"),
            fn("variables", "makeTransient", doc = "mark a variable transient (Platform)"),
        ),
        "date" to listOf(
            fn("date", "format", doc = "format a date value"),
            fn("date", "now", doc = "current date/time (DMN)"),
            fn("date", "toDate", doc = "coerce to a date (DMN)"),
            fn("date", "addDate", doc = "add years/months/days (DMN)"),
            fn("date", "subtractDate", doc = "subtract years/months/days (DMN)"),
        ),
        "task" to (listOf(fn("task", "get", doc = "task by id")) + identityLinkFunctions("task")),
        "bpmn" to (businessFunctions("bpmn") + listOf(
            fn("bpmn", "copyLocalVariable"), fn("bpmn", "copyLocalVariableToParent"),
            fn("bpmn", "replaceVariableInList"), fn("bpmn", "triggerCaseEvaluation"),
        ) + identityLinkFunctions("bpmn")),
        "cmmn" to (listOf(
            fn("cmmn", "isPlanItemCompleted", doc = "plan item is completed"),
            fn("cmmn", "isStageCompletable", doc = "stage is completable"),
        ) + businessFunctions("cmmn") + listOf(
            fn("cmmn", "copyVariable"), fn("cmmn", "copyLocalVariable"),
            fn("cmmn", "replaceVariableInList"), fn("cmmn", "triggerCaseEvaluation"),
        ) + identityLinkFunctions("cmmn")),
        "collection" to listOf(
            fn("collection", "allOf", doc = "collection contains all of"),
            fn("collection", "anyOf", doc = "collection contains any of"),
            fn("collection", "noneOf", doc = "collection contains none of"),
            fn("collection", "notAllOf", doc = "collection is missing at least one of"),
            fn("collection", "containsAny", doc = "deprecated — use anyOf"),
            fn("collection", "contains", doc = "deprecated — use allOf"),
            fn("collection", "notContainsAny"),
            fn("collection", "notContains"),
        ),
        "json" to listOf(
            fn("json", "object", doc = "build a JSON object"),
            fn("json", "array", doc = "build a JSON array"),
            fn("json", "arrayWithSize", doc = "build a JSON array of a size"),
            fn("json", "addToArray", doc = "append to a JSON array"),
        ),
        "content" to listOf(
            fn("content", "getContentItem"), fn("content", "getContentItemData"),
            fn("content", "getMetadataValues"), fn("content", "getMetadataValue"),
            fn("content", "getRenditionItem"), fn("content", "getRenditionByType"),
            fn("content", "getRenditionItemData"), fn("content", "getRenditionItemDataByType"),
        ),
        "userInfo" to listOf(
            fn("userInfo", "findUserInfo"),
            fn("userInfo", "findBooleanUserInfo"),
        ),
        "template" to listOf(
            fn("template", "createMessage", doc = "render a message template"),
        ),
        "conversationStatus" to listOf(
            fn("conversationStatus", "unreadCountForUser"),
            fn("conversationStatus", "unreadCountPerConversation"),
        ),
        "sequence" to listOf(
            fn("sequence", "nextNumber", doc = "next sequence number"),
            fn("sequence", "next", doc = "next formatted sequence value"),
            fn("sequence", "nextValue", doc = "next formatted sequence value"),
        ),
    )

    /** Backend functions callable without a prefix (globals + IDM helpers). */
    private val backendNoPrefix: List<ExprFunction> = listOf(
        fn(null, "listOf", doc = "build a list"),
        fn(null, "mapOf", doc = "build a map"),
        fn(null, "markdownToHtml", doc = "render markdown as HTML"),
        fn(null, "findUser", doc = "find a user (IDM)"),
        fn(null, "findUserAccount", doc = "find a user account (IDM)"),
        fn(null, "isUserInAllGroups"), fn(null, "isUserInAnyGroup"), fn(null, "isUserInNoGroup"),
        fn(null, "findGroupMemberUserIds"), fn(null, "findGroupMemberEmails"),
        fn(null, "setPlatformUserInfo"),
        fn(null, "setUserState"), fn(null, "setUserSubState"), fn(null, "setUserStateAndSubState"),
        fn(null, "setUserAccountState"), fn(null, "setUserAccountSubState"), fn(null, "setUserAccountStateAndSubState"),
    )

    /** Prefix alias → canonical prefix. */
    private val prefixAliases: Map<String, String> = mapOf(
        "variables" to "variables", "vars" to "variables", "var" to "variables",
        "date" to "date", "task" to "task", "cmmn" to "cmmn", "collection" to "collection",
        "json" to "json", "bpmn" to "bpmn", "content" to "content", "userInfo" to "userInfo",
        "template" to "template", "conversationStatus" to "conversationStatus",
        "sequence" to "sequence", "seq" to "sequence",
    )

    private val backendRoots: List<ExprRoot> = listOf(
        root("execution", "the current BPMN execution"),
        root("task", "the current task"),
        root("caseInstance", "the current CMMN case instance"),
        root("planItemInstance", "the current plan item instance"),
        root("planItemInstances", "plan item instances"),
        root("authenticatedUserId", "the logged-in user id"),
        root("currentTenantId", "the current tenant id"),
        root("variableContainer", "the variable container itself"),
        root("scope"), root("root"), root("parent"), root("self"),
        root("case"), root("parentCase"), root("process"), root("parentProcess"),
        root("definition", "the current definition"),
    )

    // ---------------------------------------------------------------- FRONTEND functions (flw.*)

    private val frontendMembers: List<ExprFunction> = listOf(
        // aggregation
        fn(null, "sum"), fn(null, "avg"), fn(null, "count"), fn(null, "min"), fn(null, "max"),
        fn(null, "dotProd"), fn(null, "join"),
        // collection
        fn(null, "mapAttr"), fn(null, "find"), fn(null, "findAll"), fn(null, "merge"), fn(null, "add"),
        fn(null, "forceCollectionSize"), fn(null, "in"), fn(null, "keys"), fn(null, "values"),
        fn(null, "remove", doc = "flw.remove.byAttr / byPos / byObj / nulls"),
        // higher-order collection & object helpers (take arrow predicates)
        fn(null, "array", doc = "flw.array.filter / map / reduce / sort / …"),
        fn(null, "data", doc = "flw.data.hasProperty / pick / merge / …"),
        // date
        fn(null, "now"), fn(null, "currentDate"), fn(null, "secondsOfDay"), fn(null, "timeZone"),
        fn(null, "parseDate"), fn(null, "formatDate"), fn(null, "formatTime"), fn(null, "dateAdd"),
        fn(null, "dateSubtract"), fn(null, "startOf"), fn(null, "isBefore"), fn(null, "isAfter"),
        fn(null, "sameDate"), fn(null, "formattedDurationFromNow"), fn(null, "formattedTimeLapseBetween"),
        fn(null, "durationBetween"),
        // math
        fn(null, "round"), fn(null, "floor"), fn(null, "ceil"), fn(null, "abs"),
        fn(null, "parseInt"), fn(null, "parseFloat"),
        // string / encoding
        fn(null, "encode"), fn(null, "encodeURI"), fn(null, "encodeURIComponent"),
        fn(null, "JSON", doc = "flw.JSON.parse / stringify"),
        fn(null, "numberFormat"), fn(null, "sanitizeHtml"), fn(null, "escapeHtml"),
        fn(null, "exists"), fn(null, "notExists"),
        // Work/platform-injected members — NOT part of the base `@flowable/forms` FunctionsFactory.
        // The Work runtime merges these onto `flw` at eval time via `additionalData.flw`
        // (`useGlobalResolver` in flowable-shared) and `Form.tsx`. They call the backend / drive the
        // running form, so they cannot be evaluated in the payload preview (see [FlwLibrary]).
        fn(null, "getUser", doc = "Work: user object for a userId (async, cached)"),
        fn(null, "getMasterDataInstance", doc = "Work: master data instance by id"),
        fn(null, "getMasterDataInstanceByKey", doc = "Work: master data instance by instance+definition key"),
        fn(null, "getDataObjectInstance", doc = "Work: data object instance by lookup"),
        fn(null, "translateWorkObject", doc = "Work: localized value of a model object"),
        fn(null, "stringify", doc = "Work: JSON.stringify a value"),
        fn(null, "validate", doc = "Work: trigger validation, return validation errors"),
        fn(null, "setActiveTab", doc = "Work: switch the active tab"),
        fn(null, "getActiveTab", doc = "Work: id of the active tab"),
    )

    /** Second-level members for the frontend objects that nest (`flw.remove.*`, `flw.JSON.*`, …). */
    private val frontendSubMembers: Map<String, List<ExprFunction>> = mapOf(
        "remove" to listOf(fn(null, "byAttr"), fn(null, "byPos"), fn(null, "byObj"), fn(null, "nulls")),
        "JSON" to listOf(fn(null, "parse"), fn(null, "stringify")),
        "array" to listOf(
            fn(null, "filter"), fn(null, "map"), fn(null, "flatMap"), fn(null, "find"), fn(null, "any"),
            fn(null, "all"), fn(null, "none"), fn(null, "count"), fn(null, "reduce"), fn(null, "indexOf"),
            fn(null, "includes"), fn(null, "first"), fn(null, "last"), fn(null, "isEmpty"), fn(null, "isNotEmpty"),
            fn(null, "sort"), fn(null, "append"), fn(null, "compact"), fn(null, "concat"), fn(null, "reverse"),
            fn(null, "slice"), fn(null, "pick"),
        ),
        "data" to listOf(
            fn(null, "hasProperty"), fn(null, "compact"), fn(null, "pick"), fn(null, "keys"),
            fn(null, "removeProperty"), fn(null, "removePropertyWithValue"), fn(null, "addProperty"), fn(null, "merge"),
        ),
    )

    /** Frontend members that nest one level further (offer `.`-completion instead of `(`). */
    val frontendNestingMembers: Set<String> = frontendSubMembers.keys

    private val frontendRoots: List<ExprRoot> = listOf(
        root("flw", "the Flowable frontend function namespace"),
        root("Object", "JS Object"),
        root("BigNumber", "bignumber.js constructor"),
        root("\$lang", "current language code"),
        root("\$currentUser", "the logged-in user (.id, .memberGroups, …)"),
        root("\$payload", "the whole form payload"),
        root("root", "the top-level form scope"),
        root("\$item", "current item in a repeat"),
        root("\$itemParent", "parent item in a repeat"),
        root("\$index", "index within a repeat"),
        root("\$formValid", "whether the whole form validates"),
        root("\$temp", "non-persisted temporary variables"),
    )

    // ---------------------------------------------------------------- public API

    fun functions(dialect: ExpressionDialect): List<ExprFunction> = when (dialect) {
        ExpressionDialect.BACKEND -> backendByPrefix.values.flatten() + backendNoPrefix
        ExpressionDialect.FRONTEND -> frontendMembers
    }

    fun roots(dialect: ExpressionDialect): List<ExprRoot> = when (dialect) {
        ExpressionDialect.BACKEND -> backendRoots
        ExpressionDialect.FRONTEND -> frontendRoots
    }

    fun rootNames(dialect: ExpressionDialect): Set<String> = roots(dialect).map { it.name }.toSet()

    // ---- backend helpers ----

    /** All accepted backend prefixes (canonical + aliases), e.g. for offering `prefix:` completion. */
    fun backendPrefixes(): Set<String> = prefixAliases.keys

    /** Canonical prefix for an alias (`vars` → `variables`), or null if unknown. */
    fun resolvePrefix(prefix: String): String? = prefixAliases[prefix]

    /** Functions of a prefix (accepts aliases). Empty if the prefix is unknown. */
    fun backendFunctionsForPrefix(prefix: String): List<ExprFunction> {
        val canonical = resolvePrefix(prefix) ?: return emptyList()
        return backendByPrefix[canonical].orEmpty()
    }

    fun backendNoPrefixFunctions(): List<ExprFunction> = backendNoPrefix

    /** True if `prefix:name` (or no-prefix `name`) is a known backend function (aliases accepted). */
    fun isBackendFunction(prefix: String?, name: String): Boolean =
        if (prefix == null) backendNoPrefix.any { name in it.allNames }
        else backendFunctionsForPrefix(prefix).any { name in it.allNames }

    // ---- frontend helpers ----

    fun frontendMembers(): List<ExprFunction> = frontendMembers

    fun frontendSubMembers(parent: String): List<ExprFunction> = frontendSubMembers[parent].orEmpty()

    /** True if `flw.<name>` is a known first-level frontend member. */
    fun isFrontendMember(name: String): Boolean = frontendMembers.any { it.name == name }

    /** True if `flw.<parent>.<name>` is a known second-level frontend member. */
    fun isFrontendSubMember(parent: String, name: String): Boolean =
        frontendSubMembers[parent]?.any { it.name == name } == true

    // ---- construction helpers ----

    private fun identityLinkFunctions(prefix: String): List<ExprFunction> = IDENTITY_LINK_NAMES.map { fn(prefix, it) }

    private fun businessFunctions(prefix: String): List<ExprFunction> = listOf(
        fn(prefix, "getBusinessKey"), fn(prefix, "setBusinessKey"),
        fn(prefix, "getBusinessStatus"), fn(prefix, "setBusinessStatus"),
    )

    private fun fn(prefix: String?, name: String, vararg aliases: String, doc: String? = null) =
        ExprFunction(prefix, name, aliases.toList(), doc)

    private fun root(name: String, doc: String? = null) = ExprRoot(name, doc)
}
