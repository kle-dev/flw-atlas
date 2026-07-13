package com.flowable.atlas.expr.inspect

/**
 * Parses a Flowable Work browser URL into the pieces the playground's "Evaluate against app" form
 * needs: the app **base URL** (which is also the Inspect REST base — `inspect-api` is served under
 * the same app context path), the **scope type**, and the **instance id(s)**.
 *
 * The Work UI is a react-router SPA that uses hash routing by default, so a URL copied from the
 * address bar looks like:
 * ```
 * https://host/flowable-work/#/work/all/case/CAS-9f3a
 * ```
 * The base URL is everything before `#`; the scope keyword (`case`/`process`/`task`) and the id
 * live in the hash route (`#/{app}/{filter}/{elementType}/{elementId}[/{subType}/{subId}]`). Also
 * handled: the SSO query form (`?app=&type=&id=`), the standalone-component query
 * (`?caseInstanceId=…` / `?taskInstanceId=…` / `?taskId=…`), the `/case-view/…` routes, and plain
 * path routing (no `#`).
 *
 * Scope mapping (from `flowable-work-api/model/scopeType.ts`): `case → CMMN`, `process → BPMN`,
 * `task → TASK`.
 *
 * Pure and unit-tested — no IDE, PSI, network or EDT dependency.
 */
object WorkUrlParser {

    data class Parsed(
        val baseUrl: String?,
        val scopeType: InspectClient.ScopeType?,
        val scopeId: String?,
        val subScopeId: String?,
    ) {
        val hasAny: Boolean get() = baseUrl != null || scopeId != null
    }

    private val EMPTY = Parsed(null, null, null, null)

    /** Route/keyword tokens that carry an instance scope. */
    private val ELEMENT_TYPES = setOf("case", "process", "task")

    fun parse(raw: String): Parsed {
        val input = raw.trim()
        // Only act on something that is actually a URL — a bare id or partial typing must leave the
        // form untouched.
        if (input.isEmpty() || !input.contains("://")) return EMPTY

        val hashIdx = input.indexOf('#')
        if (hashIdx >= 0) {
            // Hash routing (the default): base = everything before '#'; scope lives in the fragment.
            val before = input.substring(0, hashIdx)
            val fragment = input.substring(hashIdx + 1)
            val base = before.substringBefore('?').trimEnd('/').ifBlank { null }
            var hit = scopeFromSegments(segmentsOf(fragment.substringBefore('?')))
            if (hit.id == null) hit = scopeFromQuery(fragment.substringAfter('?', ""))
            if (hit.id == null) hit = scopeFromQuery(before.substringAfter('?', ""))
            return Parsed(base, hit.type, hit.id, hit.subId)
        }

        // No hash: query routing (SSO / embedded component) first, then plain path routing.
        val path = input.substringBefore('?')
        val qHit = scopeFromQuery(input.substringAfter('?', ""))
        if (qHit.id != null) {
            return Parsed(path.trimEnd('/').ifBlank { null }, qHit.type, qHit.id, qHit.subId)
        }
        val (authority, segs) = splitPath(path)
        val pHit = scopeFromSegments(segs)
        // Path routing can't be told apart from the context path reliably, so cut the base right
        // before the matched scope keyword (best effort — the user reviews the base URL field).
        val base = when {
            pHit.id != null && pHit.matchIndex != null -> {
                val prefix = segs.take(pHit.matchIndex).joinToString("/")
                if (prefix.isEmpty()) authority else "$authority/$prefix"
            }
            else -> path.trimEnd('/')
        }.ifBlank { null }
        return Parsed(base, pHit.type, pHit.id, pHit.subId)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private data class ScopeHit(
        val type: InspectClient.ScopeType? = null,
        val id: String? = null,
        val subId: String? = null,
        val matchIndex: Int? = null,
    )
    private val NO_HIT = ScopeHit()

    private fun segmentsOf(pathLike: String): List<String> =
        pathLike.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** Split `scheme://authority/a/b/c` into the authority (`scheme://authority`) and path segments. */
    private fun splitPath(urlNoQuery: String): Pair<String, List<String>> {
        val schemeSep = urlNoQuery.indexOf("://")
        if (schemeSep < 0) return urlNoQuery.trimEnd('/') to emptyList()
        val slash = urlNoQuery.indexOf('/', schemeSep + 3)
        if (slash < 0) return urlNoQuery.trimEnd('/') to emptyList()
        return urlNoQuery.substring(0, slash) to segmentsOf(urlNoQuery.substring(slash))
    }

    /**
     * Finds the first `case`/`process`/`task` keyword in the path segments; the id is the next
     * segment. A second keyword+id pair after it (e.g. `…/case/CAS-1/task/TSK-2`) becomes the
     * sub-scope; a trailing `/tab/…` is ignored. Also handles the
     * `/case-view/{caseInstanceId}[/task/{taskId}]` form (always a case → CMMN).
     */
    private fun scopeFromSegments(segs: List<String>): ScopeHit {
        // `/case-view/…` is always a case (CMMN); handle it before the generic keyword scan so a
        // trailing `/task/{id}` sub-segment isn't mistaken for the primary scope.
        val cv = segs.indexOfFirst { it.equals("case-view", ignoreCase = true) }
        if (cv >= 0 && cv + 1 < segs.size) {
            val next = segs[cv + 1]
            if (next.lowercase() in ELEMENT_TYPES) {
                // explicit form: case-view/case/{id}
                if (cv + 2 < segs.size) {
                    val subId = idAfterKeyword(segs, cv + 3, ELEMENT_TYPES)
                    return ScopeHit(scopeTypeOf(next), segs[cv + 2], subId, matchIndex = cv)
                }
            } else {
                // short form: case-view/{caseInstanceId}[/task/{taskId}]
                val subId = idAfterKeyword(segs, cv + 2, setOf("task"))
                return ScopeHit(InspectClient.ScopeType.CMMN, next, subId, matchIndex = cv)
            }
        }
        // generic: first case/process/task keyword; the id is the next segment.
        val i = segs.indexOfFirst { it.lowercase() in ELEMENT_TYPES }
        if (i >= 0 && i + 1 < segs.size) {
            val subId = idAfterKeyword(segs, i + 2, ELEMENT_TYPES)
            return ScopeHit(scopeTypeOf(segs[i]), segs[i + 1], subId, matchIndex = i)
        }
        return NO_HIT
    }

    /** The segment following the first keyword (from [keywords]) at or after [from], or null. */
    private fun idAfterKeyword(segs: List<String>, from: Int, keywords: Set<String>): String? {
        if (from >= segs.size) return null
        val rel = segs.subList(from, segs.size).indexOfFirst { it.lowercase() in keywords }
        if (rel < 0) return null
        val abs = from + rel
        return if (abs + 1 < segs.size) segs[abs + 1] else null
    }

    private fun scopeFromQuery(query: String): ScopeHit {
        if (query.isBlank()) return NO_HIT
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=').trim().lowercase()
            val value = decode(pair.substringAfter('=', "").trim())
            if (key.isNotEmpty() && value.isNotEmpty()) params[key] = value
        }
        val task = params["taskinstanceid"] ?: params["taskid"]
        params["caseinstanceid"]?.let { return ScopeHit(InspectClient.ScopeType.CMMN, it, task) }
        params["processinstanceid"]?.let { return ScopeHit(InspectClient.ScopeType.BPMN, it, task) }
        if (task != null) return ScopeHit(InspectClient.ScopeType.TASK, task)
        val id = params["id"]
        if (id != null) return ScopeHit(scopeTypeOf(params["type"]), id)
        return NO_HIT
    }

    private fun scopeTypeOf(token: String?): InspectClient.ScopeType? = when (token?.lowercase()) {
        "case", "cmmn" -> InspectClient.ScopeType.CMMN
        "process", "bpmn" -> InspectClient.ScopeType.BPMN
        "task" -> InspectClient.ScopeType.TASK
        else -> null
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
}
