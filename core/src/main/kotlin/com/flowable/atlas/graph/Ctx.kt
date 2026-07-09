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
    val varUse = LinkedHashMap<String, MutableSet<String>>()
    val scriptVarUse = LinkedHashMap<String, MutableSet<String>>()
    val queryMeta = LinkedHashMap<String, MutableMap<String, Any?>>()

    /** Discovery counts for `result["stats"]` (Python `len(models)/len(archives)/len(javas)`),
     *  set by [com.flowable.atlas.graph.Atlas.extract] just before the graph is built. */
    var modelFileCount = 0
    var archiveFileCount = 0
    var javaFileCount = 0

    /** Record a static model→X reference; dynamic (`${…}`/`{{…}}`) values go to [dynamicRefs] instead. */
    fun addRef(frm: Any?, ftype: String, ffile: String, rel: String, kind: String, value: Any?) {
        if (value == null) return
        val v = value.toString().trim()
        if (v.isEmpty()) return
        val target = if (v.contains("\${") || v.contains("{{")) dynamicRefs else refs
        target.add(linkedMapOf(
            "from" to frm, "fromType" to ftype, "fromFile" to ffile,
            "rel" to rel, "kind" to kind, "value" to v,
        ))
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

    /** Record a plain variable identifier declared/mapped/used by a model. */
    fun addVar(modelKey: Any?, name: Any?, bucket: String = "var_use") {
        if (modelKey == null || name == null) return
        val n = name.toString().trim()
        if (IDENT.matches(n) && n !in Constants.FLOWABLE_CONTEXT && n !in Constants.JAVA_LITERALS) {
            val target = when (bucket) {
                "var_use" -> varUse
                "script_var_use" -> scriptVarUse
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

        /** Split a comma/semicolon-separated group/user string into individual ids. */
        fun splitIds(s: Any?): List<String> {
            if (s == null) return emptyList()
            return s.toString().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
