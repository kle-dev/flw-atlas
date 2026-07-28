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
    val scriptVarUse = LinkedHashMap<String, MutableSet<String>>()
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
            if (kind in SOURCE_BINDS_VARIABLE && p["expression"] != true) {
                addParamFlow(model, p["source"], dir, elementId, p["source"], p["target"])
            }
            if (kind !in TARGET_IS_CONTRACT) {
                addParamFlow(model, p["target"], dir, elementId, p["source"], p["target"])
            }
        }
    }

    /** Record that [model] binds [variable] through the in/out parameter mapping on [element].
     *  Only plain identifiers are tracked — an expression source (`${…}`) names no single variable, and
     *  its own variables are already harvested by the expression pass. */
    private fun addParamFlow(model: Any?, variable: Any?, dir: String, element: Any?, source: Any?, target: Any?) {
        if (model == null || variable == null) return
        val v = variable.toString().trim()
        if (!IDENT.matches(v) || v in Constants.FLOWABLE_CONTEXT || v in Constants.JAVA_LITERALS) return
        paramFlows.add(linkedMapOf(
            "model" to model.toString(), "variable" to v, "dir" to dir,
            "element" to element, "source" to source, "target" to target,
        ))
    }

    /** Record a plain variable identifier declared/mapped/used by a model. */
    fun addVar(modelKey: Any?, name: Any?, bucket: String = "var_use") {
        if (modelKey == null || name == null) return
        val n = name.toString().trim()
        if (IDENT.matches(n) && n !in Constants.FLOWABLE_CONTEXT && n !in Constants.JAVA_LITERALS) {
            val target = when (bucket) {
                "var_use" -> varUse
                "script_var_use" -> scriptVarUse
                "form_field_use" -> formFieldUse
                "expr_use" -> exprUse
                "mustache_use" -> mustacheUse
                else -> varUse
            }
            target.getOrPut(n) { LinkedHashSet() }.add(modelKey.toString())
        }
    }

    companion object {
        private val IDENT = Regex("^[A-Za-z_]\\w*$")
        private val SPLIT = Regex("[,;]")

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
