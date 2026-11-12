package com.flowable.atlas.render

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.parsing.ModelKinds

/**
 * The `<project>.graph.json` artifact: the extract result, projected for a consumer that has to *query*
 * it rather than hold it.
 *
 * The artifact used to be `MiniJson.stringify(result, 2)` — the whole in-memory result, pretty-printed.
 * On a real project that is **5.0 MB**, and three things made it that big for no benefit:
 *
 *  1. **Every model was serialized twice.** A model node's `data` *is* its bucket entry (same object), so
 *     `processes[3]` and the `data` of `graph.nodes[…] id=process:x` were the same bytes in two places —
 *     58 % of the file was `graph`, and most of that was the copy. Model nodes now carry
 *     `{"dataIn": "processes"}` instead: look the body up by `type`+`key` in that bucket.
 *  2. **It was pretty-printed**, at roughly +50 % for indentation a machine does not read. Minified by
 *     default; `--pretty` restores the indented form for reading by eye.
 *  3. **There was no reverse index**, although `CLAUDE.md` tells the agent that relationships are
 *     bidirectional and to "check who references it (both directions)". Answering that meant scanning
 *     every edge. Each node now carries `usedBy` — the ids that reference it: the sources of its edges
 *     and, for the nodes no edge points at (variables, expressions, bindings, strings, service operations,
 *     custom functions), the models the extract recorded in their `data.usedBy`, moved up to the node.
 *
 * The same rule — one copy of each fact — keeps the raw Java parse out of the file. `javaByRole`,
 * `javaGlue` and `javaControllers` held each class's complete parser record (imports, string literals,
 * call sites, about thirty keys) once per role: 10 % of a large model-heavy project's file and over half
 * of a Java-heavy one's, for classes whose `java:<fqn>` node already carries what a reader uses. They
 * are written as `{fqn, file, line}` references now, `javaControllers` not at all (it is
 * `javaByRole.controller`), and `expressions`/`placeholders` not at all (each entry is the key of an
 * `expression`/`binding` node). The in-memory result keeps all of it: the overview reads it in the same
 * run. `GraphSizeGuardTest` fails when a sub-tree is stored under two different paths again.
 *
 * And a member that is null is not written. `.resultVariable` answers null whether the key is there or
 * not, so a query cannot tell the difference, while the file and every record an agent cuts from it carry
 * the key: 2 to 6 % of the projects it was measured on, and 11 % of a form of plain fields, each of which
 * says `"value": null`. Only [render] leaves them out — [project] returns the members as the result has
 * them — and `GraphDelta` reads a null and a missing member as the same, so a graph.json compared with
 * the run that wrote it has not changed.
 *
 * A `_schema` block up front states the node/edge shape, the `dataIn` rule and the file's own size, so a
 * consumer can learn the format from the file instead of from documentation it does not have.
 */
object GraphJsonRenderer {

    /**
     * Result keys not written at all, because the file already holds every entry elsewhere:
     * `javaControllers` is `javaByRole.controller` (the parser gives a class that role exactly when it is
     * a controller), and each of `expressions`/`placeholders` is the `key` of an `expression`/`binding`
     * node.
     */
    private val OMITTED = setOf("javaControllers", "expressions", "placeholders")

    fun render(
        result: Map<String, Any?>,
        pretty: Boolean = false,
        generatedAt: java.time.Instant = java.time.Instant.now(),
    ): String {
        val projected = project(result, generatedAt)
        return MiniJson.stringifyWithoutNulls(projected, if (pretty) 2 else null)
    }

    /**
     * Build the serializable projection. Nothing in [result] is mutated: the same result object is shared
     * with the other renderers in one in-process run (and with the IDE plugin's caller).
     */
    fun project(result: Map<String, Any?>, generatedAt: java.time.Instant = java.time.Instant.now()): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["_schema"] = SCHEMA
        // Provenance for whatever reads the file by machine: a graph.json in a ticket or a pipeline
        // artifact could not say how old it was, nor which Atlas produced it.
        out["_generated"] = linkedMapOf("at" to generatedAt.toString(), "atlas" to com.flowable.atlas.AtlasBuildInfo.VERSION)
        val graph = Dyn.mapOrNull(result["graph"])
        val nodes = graph?.let { Dyn.maps(it["nodes"]) }
        val edges = graph?.let { Dyn.maps(it["edges"]) }

        // Identity, not equality: only a `data` that IS a bucket entry may be replaced by a pointer to
        // it. Two structurally equal maps in different buckets must both survive. The buckets come from
        // the model-kind registry (every model bucket, `liquibase` and `others`), so a new kind is
        // projected the day it is added instead of being written twice until someone notices.
        val bucketOf = java.util.IdentityHashMap<Any, String>()
        for (bucket in ModelKinds.MODEL_BUCKETS) {
            for (entry in (result[bucket] as? List<Any?> ?: emptyList())) {
                if (entry != null) bucketOf.putIfAbsent(entry, bucket)
            }
        }
        // A changelog node's data is no bucket entry but a slim copy of one: GraphBuilder leaves out
        // `serviceRefs` and the like because the explorer embeds node data and shows what it finds there.
        // The entry is a superset of the copy, so the pointer loses nothing — matched by key, which is
        // unique per changelog, and only when every field the node carries is the entry's.
        val changelogs = HashMap<Any?, Map<*, *>>()
        for (entry in (result["liquibase"] as? List<Any?> ?: emptyList())) {
            if (entry is Map<*, *>) changelogs.putIfAbsent(entry["key"], entry)
        }
        fun isChangelogCopy(n: Map<String, Any?>, data: Any?): Boolean {
            if (n["type"] != "liquibase" || data !is Map<*, *>) return false
            val entry = changelogs[n["key"]] ?: return false
            return data.all { (k, v) -> entry[k] == v }
        }

        // usedBy: who points at me. `contains` is app membership, not use, and would make every model in
        // an app look referenced — the same distinction the unused-form check makes.
        val usedBy = LinkedHashMap<String, MutableSet<String>>()
        for (e in edges ?: emptyList()) {
            if (e["rel"] == "contains") continue
            val s = e["s"] as? String ?: continue
            val t = e["t"] as? String ?: continue
            usedBy.getOrPut(t) { LinkedHashSet() }.add(s)
        }

        for ((k, v) in result) {
            when (k) {
                "graph", in OMITTED -> continue
                "javaByRole" -> out[k] = Dyn.mapOrNull(v)?.mapValues { (_, list) -> javaRefs(list) } ?: v
                "javaGlue" -> out[k] = javaRefs(v)
                else -> out[k] = v
            }
        }
        if (graph != null) {
            val projected = LinkedHashMap<String, Any?>()
            projected["nodes"] = (nodes ?: emptyList()).map { n ->
                val copy = LinkedHashMap<String, Any?>(n)
                val data = n["data"]
                val bucket = (if (data != null) bucketOf[data] else null)
                    ?: if (isChangelogCopy(n, data)) "liquibase" else null
                if (bucket != null) copy["data"] = linkedMapOf<String, Any?>("dataIn" to bucket)
                val refs = LinkedHashSet(usedBy[n["id"]] ?: emptySet<String>())
                // A variable, an expression or a service operation is never an edge's target: who uses it
                // lives in its own `data.usedBy`. Left there, `.usedBy` — the one recipe CLAUDE.md gives
                // for "who references it" — answered null for every one of them, and an agent checking
                // before a rename was told nothing did. Moved, not copied, so the file does not grow.
                // An empty list goes too: an operation nothing calls would otherwise keep `data.usedBy: []`
                // as the one node still answering the old recipe.
                val own = if (bucket == null) (data as? Map<*, *>)?.get("usedBy") as? List<*> else null
                if (own != null) {
                    own.forEach { id -> (id as? String)?.let(refs::add) }
                    copy["data"] = LinkedHashMap(data as Map<*, *>).apply { remove("usedBy") }
                }
                if (refs.isNotEmpty()) copy["usedBy"] = refs.toList()
                copy
            }
            projected["edges"] = edges ?: emptyList<Any?>()
            out["graph"] = projected
        }
        return out
    }

    /**
     * Java classes as references: where each one is, and nothing else. A whitelist on purpose — the
     * parser record grows a key whenever a check needs one more fact (it has about thirty), and a list of
     * keys to leave out would let every new one into the file unnoticed.
     */
    private fun javaRefs(list: Any?): List<Map<String, Any?>> =
        (list as? List<*>).orEmpty().mapNotNull { rec ->
            val m = rec as? Map<*, *> ?: return@mapNotNull null
            linkedMapOf<String, Any?>().apply {
                for (k in listOf("fqn", "file", "line")) m[k]?.let { put(k, it) }
            }
        }

    /** Self-description, so the file explains its own shape to whoever opens it. */
    private val SCHEMA: Map<String, Any?> = linkedMapOf(
        "about" to "Flowable Atlas project graph. Query it — do not read it whole; a large project " +
            "runs to megabytes.",
        "node" to "{id: '<type>:<key>', type, label, key, file, data, usedBy?}",
        "edge" to "{s: <sourceNodeId>, t: <targetNodeId>, rel, suspect?, dynamic?}",
        "absent" to "A key whose value would be null is not written, here and in every record below: " +
            "`.file`, `.resultVariable` and the like answer null either way, so test a value, not `has(...)`.",
        "dataIn" to "A model or changelog node's data lives once in the named top-level bucket — find it " +
            "by matching type+key there (e.g. data.dataIn='processes' -> .processes[] | select(.key==\"<key>\"), " +
            "data.dataIn='liquibase' -> .liquibase[] | select(.key==\"<key>\")).",
        "liquibase" to "A changelog lists the columns of the tables it shapes as they stand once its run is " +
            "over, each with the change set it comes `from`. A table several changelogs of one run shape is " +
            "listed under one of them, and the others name that changelog in `columnsIn` ({table: key}); a " +
            "table's columns are jq '[.liquibase[].columns[] | select(.table==\"T\")]'.",
        "java" to "javaByRole ({role: [class]}) and javaGlue list Java classes as {fqn, file, line}; what Atlas " +
            "read from a class — roles, bean names, methods, endpoints — is on the node 'java:<fqn>' (a class " +
            "Atlas gave no role, listed under 'other', mostly has none). Controllers are javaByRole.controller.",
        "usedBy" to "Node ids that reference this node: the sources of its edges (app 'contains' " +
            "membership excluded; the relation itself is in `edges`) and, for variable, expression, " +
            "binding, string, serviceOperation and customFunction nodes, the models that use them " +
            "(where and how: data.reads, data.writes, data.usages).",
        "findings" to "{check, kind, severity, node, label, message, file?, element?, subject?, line?, " +
            "scriptLine?, models?, snippet?, waived?} — what Atlas thinks is wrong. `kind` is 'defect' " +
            "(wrong now) or 'advice' (worth a look), whatever the severity; `checks` counts the open ones " +
            "per check plus a `waived` total. `subject` tells apart several findings of one check on one " +
            "node (the scope, the column), where `message` is prose that rewords. `line` is a line of " +
            "`file`: the element's declaration, the reference a missing model is named by, or the script " +
            "line a script problem sits on; `scriptLine` is a line within the script body, given when no " +
            "file line could be. `models` lists the models an expression or binding finding sits in. " +
            "`waived` carries the reason a team accepted it; see `waivers` and waivers.json.",
        "waivers" to "{rules: [{check, node, element?, subject?, reason, by?, at?, until?, matched}], " +
            "createdWith?, notes?: [{node, check?, element?, subject?, text, importance, by?, at?}], " +
            "stale?, unexplained?, problems?} — present when a waivers.json was read: what it covered, " +
            "and what is wrong with the file.",
        "modelCopies" to "{'<type>:<key>': {<file>: <fingerprint>}} — present when a model is held in " +
            "more than one file (a Design export beside its -bar.zip): every file of it, with a fingerprint " +
            "of what that copy says. The node and the bucket show the first copy only.",
        "recipes" to listOf(
            "who references X:            jq '.graph.nodes[] | select(.id==\"process:X\") | .usedBy'",
            "what X references:           jq '.graph.edges[] | select(.s==\"process:X\")'",
            "a model's body:              jq '.processes[] | select(.key==\"X\")'",
            "every expression:            jq '.graph.nodes[] | select(.type==\"expression\") | .key'",
            "models using variable V:     jq '.graph.nodes[] | select(.id==\"variable:V\") | .usedBy'",
            "controller serving a form:   jq '.restCalls[] | select(.source|test(\"X\")) | .matches'",
            "everything broken:           jq '.findings[] | select(.kind==\"defect\" and (.waived|not))'",
            "what we accepted, and why:   jq '.findings[] | select(.waived) | {check, label, reason: .waived.reason}'",
        ),
    )
}
