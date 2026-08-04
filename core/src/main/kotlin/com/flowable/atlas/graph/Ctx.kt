package com.flowable.atlas.graph

import com.flowable.atlas.parsing.Constants

/**
 * The mutable extraction context threaded through every parser — a port of the `ctx` dict in
 * `flowable_atlas.py` `extract` (~line 1273) and its mutators `add_ref` / `add_access` / `add_var`
 * (~lines 236-288). Parsers record cross-model references, REST calls, access entries and variable
 * usage here while the buckets in [AtlasResult] collect the parsed models themselves.
 */
class Ctx {
    val refs = ArrayList<MutableMap<String, Any?>>()
    val dynamicRefs = ArrayList<MutableMap<String, Any?>>()
    val restCalls = ArrayList<MutableMap<String, Any?>>()
    val expr = LinkedHashSet<String>()
    val mustache = LinkedHashSet<String>()
    val delegateClasses = LinkedHashSet<String>()
    val access = ArrayList<MutableMap<String, Any?>>()
    val groups = LinkedHashSet<String>()
    val exprUse = LinkedHashMap<String, MutableSet<String>>()
    val mustacheUse = LinkedHashMap<String, MutableSet<String>>()
    /** Service-operation usages: a consumer model invokes `operationKey` on a `service` or `dataObject`.
     *  Resolved to a service (via the data object's backing service) and inverted into
     *  `serviceOperation` nodes by [GraphBuilder]. Mirrors the shape of [refs]. */
    val opUse = ArrayList<MutableMap<String, Any?>>()
    /** In/out parameter mappings that bind a caller-side variable, one entry per (variable, mapping).
     *  [GraphBuilder] turns these into the precise "used in" snippets and the `params` list on a
     *  `variable` node, which is what makes a parameter findable by its variable name. */
    val paramFlows = ArrayList<MutableMap<String, Any?>>()
    val varUse = LinkedHashMap<String, MutableSet<String>>()
    /**
     * Directional variable evidence: one record per (model, variable, direction) whose direction Atlas
     * can **prove**. Undirected evidence — the [varUse] regex sweep, an app's variable list, a data
     * object's field map — produces no record here at all, and that asymmetry is the point. The
     * "written but never read" verdict is derived from this list alone, so it can never rest on a guess.
     *
     * `scope` is the model whose variable space the name lives in, which is **not** always `model`: the
     * target of a `<flowable:in>` is a variable of the *callee*, declared by the caller. Keeping both
     * is what lets a finding name the mapping to delete and still check the right model for readers.
     */
    val varSites = ArrayList<MutableMap<String, Any?>>()
    /**
     * Names whose readers Atlas cannot see: an undecidable construct (`flowable:variableName`,
     * `hasVariable`), or a value that leaves the project (an action's response payload, a variable
     * extracted for queries). A "never read" verdict is never issued for one of these — a heuristic
     * that cannot decide stays silent.
     */
    val varReadsUnknown = LinkedHashSet<String>()
    /** Models and Java classes whose code reads the *whole* variable map (`execution.getVariables()`).
     *  Every variable of a scope they touch is potentially read, so none can be proven unread. */
    val varScopeReadsAll = LinkedHashSet<String>()
    /** One entry per (variable, script) pair: which model's script touches the name, on which element,
     *  and whether an explicit API call named it (`api = true`) or the bare-identifier heuristic did.
     *  [GraphBuilder] turns these into the precise "used in" snippets on a `variable` node and marks
     *  the variables only a heuristic read supports — a guess must never look like a declaration. */
    val scriptVarSites = ArrayList<MutableMap<String, Any?>>()
    /** Form/page field ids — in Flowable Work a field id IS the variable path the form reads and
     *  writes, so indexing it is what connects a form to the process/case variables it touches. */
    val formFieldUse = LinkedHashMap<String, MutableSet<String>>()
    val queryMeta = LinkedHashMap<String, MutableMap<String, Any?>>()

    /** Discovery counts for `result["stats"]` (Python `len(models)/len(archives)/len(javas)`),
     *  set by [com.flowable.atlas.graph.Atlas.extract] just before the graph is built. */
    var modelFileCount = 0
    var archiveFileCount = 0
    var javaFileCount = 0

    /** Record a static model→X reference; dynamic (`${…}`/`{{…}}`) values go to [dynamicRefs] instead.
     *  [suspect] marks a reference the producer already knows is uncertain (e.g. a ref-by-id where a
     *  key is expected) — it survives resolution and flags the resulting edge. */
    fun addRef(frm: Any?, ftype: String, ffile: String, rel: String, kind: String, value: Any?,
               suspect: Boolean = false) {
        if (value == null) return
        val v = value.toString().trim()
        if (v.isEmpty()) return
        val target = if (v.contains("\${") || v.contains("{{")) dynamicRefs else refs
        val entry = linkedMapOf<String, Any?>(
            "from" to frm, "fromType" to ftype, "fromFile" to ffile,
            "rel" to rel, "kind" to kind, "value" to v,
        )
        if (suspect) entry["suspect"] = true
        target.add(entry)
    }

    /** Record that [consumer] invokes operation [opKey] on a service ([targetKind] = "service") or a
     *  data object ([targetKind] = "dataObject", resolved to its backing service later). Dynamic
     *  (`${…}`/`{{…}}`) target/operation keys are skipped — they can't be tied to one operation. */
    fun addOpUse(consumer: Any?, targetKind: String, targetKey: Any?, opKey: Any?) {
        if (consumer == null || targetKey == null || opKey == null) return
        val c = consumer.toString().trim()
        val tk = targetKey.toString().trim()
        val ok = opKey.toString().trim()
        if (c.isEmpty() || tk.isEmpty() || ok.isEmpty()) return
        if (listOf(tk, ok).any { it.contains("\${") || it.contains("{{") }) return
        opUse.add(linkedMapOf("consumer" to c, "targetKind" to targetKind, "targetKey" to tk, "op" to ok))
    }

    /** Record a "who can do what" entry; literal group names feed the index. */
    fun addAccess(model: Any?, mtype: String, scope: String, action: String,
                  groups: Any? = null, users: Any? = null) {
        val g = splitIds(groups)
        val u = splitIds(users)
        if (g.isEmpty() && u.isEmpty()) return
        access.add(linkedMapOf(
            "model" to model, "modelType" to mtype, "scope" to scope,
            "action" to action, "groups" to g, "users" to u,
        ))
        this.groups.addAll(g.filter { !it.contains("\${") && !it.contains("{{") })
    }

    /**
     * Append [params] to a model's flat parameter [rollup], stamped with the element that declares them,
     * and record the caller-side variables each mapping binds.
     *
     * A model keeps **one** flat list rather than a per-element one: the element attribution travels with
     * every record, so a per-element view is a filter away, and the frontend and search only have to look
     * in a single place regardless of which of the ~10 task flavours produced the mapping.
     */
    fun addParams(
        rollup: MutableList<Map<String, Any?>>,
        model: Any?,
        elementId: Any?,
        elementName: Any?,
        elementType: String,
        params: List<Map<String, Any?>>,
        elementSubType: String? = null,
        callee: Pair<String, String>? = null,
    ) {
        for (p in params) {
            val rec = linkedMapOf<String, Any?>(
                "element" to elementId, "elementName" to elementName, "elementType" to elementType,
            )
            // `flowable:type` on a service task — "agent", "http", "service-registry", … — is what tells a
            // reader which integration the mapping actually feeds, so keep it when the model declares one.
            if (!elementSubType.isNullOrEmpty()) rec["elementSubType"] = elementSubType
            // which model the values travel to/from; `p` may carry its own (a form button resolves it from
            // extraSettings), so the explicit argument only fills a gap
            if (callee != null && p["refKey"] == null) {
                rec["refKind"] = callee.first
                rec["refKey"] = callee.second
            }
            rec.putAll(p)
            rollup.add(rec)
            val dir = p["dir"] as? String ?: continue
            val kind = p["kind"] as? String
            // The callee this mapping feeds: `p` may carry its own (a form button resolves it from
            // extraSettings), else the element-level one. It is what makes an `in` target checkable —
            // the variable it declares belongs to that model, not to this one.
            val calleeKind = p["refKind"] as? String ?: callee?.first
            val calleeKey = p["refKey"] as? String ?: callee?.second
            // An Init-Variables mapping's `value` is bare EL — `root.chaserList.add(chaserInfo)`, with no
            // `${…}` wrapper for the expression harvester to find. Every name in it is read at runtime,
            // and Atlas has no parser for this position, so they are recorded as names whose readers it
            // cannot enumerate rather than left looking unread.
            if (kind == "variableMapping" && p["type"] == "variable") {
                val body = SCOPE_PREFIX.replace(p["source"]?.toString() ?: "", "")
                for (m in IDENT_IN_VALUE.findAll(body)) markReadsUnknown(m.value)
            }
            // Writing into a container makes the mapping's `name` a field path, not a variable. Whoever
            // reads the container reads the field, and Atlas cannot see field-level reads.
            if (p["container"] != null) markReadsUnknown(p["target"])
            // Both sides of one mapping can name a variable — `<flowable:in source="orderId"
            // target="subOrderId"/>` reads one and writes another. Everything else about the two records
            // is identical, so only the side and the name it carries appear at the call.
            fun bind(side: String, variable: Any?) = addParamFlow(
                model, variable, dir, kind, side, elementId, elementName, elementType,
                p["source"], p["target"], calleeKind, calleeKey,
            )
            if ((kind in SOURCE_BINDS_VARIABLE || kind in SOURCE_ONLY_BINDS_VARIABLE) && p["expression"] != true) {
                bind("source", p["source"])
            }
            if (kind !in TARGET_IS_CONTRACT) bind("target", p["target"])
        }
    }

    /** Record that [model] binds [variable] through the in/out parameter mapping on [element].
     *  Only plain identifiers are tracked — an expression source (`${…}`) names no single variable, and
     *  its own variables are already harvested by the expression pass. */
    private fun addParamFlow(
        model: Any?, variable: Any?, dir: String, kind: String?, side: String,
        element: Any?, elementName: Any?, elementType: String?,
        source: Any?, target: Any?, calleeKind: String?, calleeKey: String?,
    ) {
        if (model == null) return
        val v = varName(variable) ?: return
        val flow = linkedMapOf<String, Any?>(
            "model" to model.toString(), "variable" to v, "dir" to dir,
            "element" to element, "source" to source, "target" to target,
        )
        if (kind != null) flow["kind"] = kind
        flow["side"] = side
        if (calleeKind != null) flow["calleeKind"] = calleeKind
        if (calleeKey != null) flow["calleeKey"] = calleeKey
        paramFlows.add(flow)

        val role = roleOf(kind, dir, side) ?: return
        if (role.readsUnknown) markReadsUnknown(v)
        // A callee-side name lives in the *callee's* variable space, so the mapping has to say which
        // model that is or nothing can be concluded about who reads it. A dynamic key (`${…}`) is still
        // recorded — it simply will not resolve to a node, which the unused check reads as "callee not in
        // this project". A mapping with no callee at all cannot even say that much.
        if (role.inCallee && calleeKey == null) markReadsUnknown(v)
        addVarSite(
            model = model, name = v, dir = role.dir, via = viaOf(kind, side),
            element = element, elementName = elementName, elementType = elementType,
            scope = if (role.inCallee) calleeKey else null,
        )
    }

    /**
     * Record that [model]'s script — the one on element [elementId] ([elementName] for reading) — touches
     * [name]. [api] separates an explicit `setVariable('x')`-style call from a bare-identifier read.
     */
    fun addScriptVar(
        model: Any?, name: Any?, api: Boolean,
        elementId: Any? = null, elementName: Any? = null, elementType: String? = null,
    ) {
        if (model == null) return
        val n = varName(name) ?: return
        scriptVarSites.add(linkedMapOf(
            "model" to model.toString(), "variable" to n, "api" to api,
            "element" to elementId, "elementName" to elementName, "elementType" to elementType,
        ))
    }

    /**
     * Record that [model] **reads** or **writes** [name] — the directional counterpart of [addVar].
     *
     * [dir] is `"read"` or `"write"`; there is deliberately no third value. Evidence whose direction is
     * undecidable calls [markReadsUnknown] instead, which silences the unused check rather than
     * guessing a half. [via] names the construct in Design's terms (`inParameter`, `resultVariable`,
     * `scriptApi`, `dataObject`, …) so a finding can say *how* the variable is written. [scope] overrides
     * whose variable space the name lives in, for a mapping that declares a variable of another model.
     * [proven] is false for the bare-identifier script heuristic — a guess must never look like a fact.
     */
    fun addVarSite(
        model: Any?, name: Any?, dir: String, via: String,
        element: Any? = null, elementName: Any? = null, elementType: String? = null,
        scope: Any? = null, proven: Boolean = true,
    ) {
        if (model == null) return
        val n = varName(name) ?: return
        val rec = linkedMapOf<String, Any?>(
            "model" to model.toString(), "variable" to n, "dir" to dir, "via" to via,
            "element" to element, "elementName" to elementName, "elementType" to elementType,
        )
        scope?.toString()?.trim()?.ifEmpty { null }?.let { rec["scope"] = it }
        if (!proven) rec["proven"] = false
        varSites.add(rec)
    }

    /** Mark [name] as one whose readers Atlas cannot see. See [varReadsUnknown]. */
    fun markReadsUnknown(name: Any?) {
        varReadsUnknown.add(varName(name) ?: return)
    }

    /** Record a plain variable identifier declared/mapped/used by a model. */
    fun addVar(modelKey: Any?, name: Any?, bucket: String = "var_use") {
        if (modelKey == null) return
        val n = varName(name) ?: return
        val target = when (bucket) {
            "var_use" -> varUse
            "form_field_use" -> formFieldUse
            "expr_use" -> exprUse
            "mustache_use" -> mustacheUse
            else -> varUse
        }
        target.getOrPut(n) { LinkedHashSet() }.add(modelKey.toString())
    }

    companion object {
        private val IDENT = Regex("^[A-Za-z_]\\w*$")
        private val SPLIT = Regex("[,;]")

        /** [name] as a project variable, or null when it is not one. A variable is a plain identifier
         *  that is neither one of the engine's own context roots nor a Java literal — everything that
         *  records variable evidence has to agree on that, so the rule lives in one place. */
        private fun varName(name: Any?): String? {
            val n = name?.toString()?.trim() ?: return null
            val known = n in Constants.FLOWABLE_CONTEXT || n in Constants.JAVA_LITERALS
            return if (IDENT.matches(n) && !known) n else null
        }

        /** Root identifiers of a bare-EL mapping value — the receiver of `a.b(c)` and each argument. A
         *  name reached through a `.` is a field of something else, not a variable; a method name is not
         *  one either. */
        private val IDENT_IN_VALUE = Regex("(?<![\\w.$'\"])[A-Za-z_]\\w*(?!\\s*[(\\w])")

        /** The engine's scope containers. `root.chaserList` *is* a variable read — the prefix only says
         *  which scope it lives in — so it is stripped before the identifiers are picked out. */
        private val SCOPE_PREFIX = Regex("\\b(?:root|self|parent)\\s*\\.\\s*")

        const val READ = "read"
        const val WRITE = "write"

        /** What one side of a mapping proves: the direction, whose scope the name lives in, and whether
         *  the value's readers are outside anything Atlas parses. */
        private data class ParamRole(val dir: String, val inCallee: Boolean, val readsUnknown: Boolean = false)

        /**
         * What one side of an in/out mapping proves about the variable named there.
         *
         * `source` is always where a value comes from and `target` where it lands
         * ([com.flowable.atlas.parsing.XmlHelpers.readIoParams]), but *whose* variable each side names
         * depends on the flavour: `<flowable:in>` moves a caller variable into a callee one and
         * `<flowable:out>` the reverse. `null` means the side names no variable Atlas can reason about —
         * a literal, an expression, a callee contract field, or a construct whose direction is not fixed.
         */
        private fun roleOf(kind: String?, dir: String, side: String): ParamRole? = when {
            kind == "in" && side == "source" -> ParamRole(READ, inCallee = false)
            kind == "in" && side == "target" -> ParamRole(WRITE, inCallee = true)
            kind == "out" && side == "source" -> ParamRole(READ, inCallee = true)
            kind == "out" && side == "target" -> ParamRole(WRITE, inCallee = false)
            // A variable handed to an event payload is read to be sent. Its target is the event's own
            // field name, which is why this kind is in TARGET_IS_CONTRACT.
            kind == "eventInParameter" && side == "source" -> ParamRole(READ, inCallee = false)
            // `flowable:signalVariableNames`: to pass a variable into the signalled instance the action
            // must first read it out of the current scope. A read, therefore — never a write.
            kind == "signalVariable" -> ParamRole(READ, inCallee = false)
            // `flw.getInput('x')` reads the action's own payload contract. `flw.setOutput('x')` writes a
            // value whose consumer is a form button's `{{$response…}}`, the Work UI or a REST client —
            // none of which Atlas can follow, so it must not call the value unread.
            kind == "flwScript" && side == "target" && dir == "in" -> ParamRole(READ, inCallee = false)
            kind == "flwScript" && side == "target" -> ParamRole(WRITE, inCallee = false, readsUnknown = true)
            side == "target" && kind in WRITES_THE_TARGET -> ParamRole(WRITE, inCallee = false)
            else -> null
        }

        /** Mapping kinds whose `target` is a variable the mapping writes in the declaring model. */
        private val WRITES_THE_TARGET = setOf(
            "outputParameter", "errorOutputParameter", "eventOutParameter", "variableMapping",
            "resultVariable", "outputVariableName",
            // a form/page button's "Store response attributes" — the call's result lands in a form
            // variable, whose readers are the `{{…}}` bindings of that same form
            "responsePayloadMapping", "errorResponsePayloadMapping",
        )

        /**
         * Kinds whose `source` names a variable although their `target` is a callee contract name, so
         * [TARGET_IS_CONTRACT] alone would drop the mapping entirely. Without this an
         * `<flowable:eventInParameter>` recorded nothing at all and a variable sent out on an event
         * payload looked unread.
         */
        private val SOURCE_ONLY_BINDS_VARIABLE = setOf("eventInParameter")

        /** Design's word for the construct a mapping side represents, for the `via` of a variable site. */
        private fun viaOf(kind: String?, side: String): String = when (kind) {
            "in" -> if (side == "source") "inParameterSource" else "inParameter"
            "out" -> if (side == "source") "outParameterSource" else "outParameter"
            "flwScript" -> if (side == "target") "flwPayload" else "flwPayloadSource"
            null -> "parameter"
            else -> kind
        }

        /**
         * Mapping kinds whose *source* attribute names a variable. Pretending every source does turns
         * service parameters and literal values into phantom variables.
         *
         * `<flowable:in>`/`<flowable:out>` are the only flavours where that holds by contract: `source`
         * is a variable name and the expression variant has its own `sourceExpression` attribute.
         * Everywhere else the source is an expression (`inputParameter value="${v}"`), a literal constant
         * (`variableMapping value="draft"`) or the callee's own field name (`outputParameter name="id"`).
         *
         * Both directions are variable-to-variable here, because `in`/`out` move data between a parent and
         * a child scope — an `out`'s source is a variable in the *child*, so it is still a real variable,
         * just not one of the declaring model's own. Atlas indexes the name against the declaring model
         * either way; that is what makes it findable.
         */
        private val SOURCE_BINDS_VARIABLE = setOf("in", "out")

        /**
         * Mapping kinds whose *target* is a callee-side contract name — a service input parameter, an
         * event payload field, a bot config key, an HTTP header — rather than a variable anyone can read
         * or write.
         *
         * A form button's `sendPayloadMapping` belongs here: its `name` is what the callee receives (and
         * what an action script reads back with `flw.getInput("…")`), while its `expression` side is a
         * `{{…}}` binding the expression pass already indexes.
         */
        private val TARGET_IS_CONTRACT = setOf(
            "inputParameter", "eventInParameter", "config",
            "sendPayloadMapping", "dataObjectDataTableCreatePayloadMapping", "header",
        )

        /** Split a comma/semicolon-separated group/user string into individual ids. */
        fun splitIds(s: Any?): List<String> {
            if (s == null) return emptyList()
            return s.toString().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
