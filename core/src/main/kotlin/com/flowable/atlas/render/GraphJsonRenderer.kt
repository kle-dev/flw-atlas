package com.flowable.atlas.render

import com.flowable.atlas.model.MiniJson

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
 *     every edge. Each node now carries `usedBy` — the ids that reference it.
 *
 * A `_schema` block up front states the node/edge shape, the `dataIn` rule and the file's own size, so a
 * consumer can learn the format from the file instead of from documentation it does not have.
 */
object GraphJsonRenderer {

    /** Buckets a model node's `data` can live in, by node type. */
    private val BUCKET_FOR_TYPE = mapOf(
        "app" to "apps", "process" to "processes", "case" to "cases", "decision" to "decisions",
        "form" to "forms", "page" to "forms", "agent" to "agents", "service" to "services",
        "channel" to "channels", "event" to "events", "dataDictionary" to "dictionaries",
        "dataObject" to "dataObjects", "securityPolicy" to "policies", "action" to "actions",
    )

    fun render(result: Map<String, Any?>, pretty: Boolean = false): String =
        if (pretty) MiniJson.stringify(project(result), 2) else MiniJson.stringify(project(result))

    /**
     * Build the serializable projection. Nothing in [result] is mutated: the same result object is shared
     * with the other renderers in one in-process run (and with the IDE plugin's caller).
     */
    @Suppress("UNCHECKED_CAST")
    fun project(result: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["_schema"] = SCHEMA
        val graph = result["graph"] as? Map<String, Any?>
        val nodes = graph?.get("nodes") as? List<Map<String, Any?>>
        val edges = graph?.get("edges") as? List<Map<String, Any?>>

        // Identity, not equality: only a `data` that IS a bucket entry may be replaced by a pointer to
        // it. Two structurally equal maps in different buckets must both survive.
        val bucketOf = java.util.IdentityHashMap<Any, String>()
        for ((type, bucket) in BUCKET_FOR_TYPE) {
            for (entry in (result[bucket] as? List<Any?> ?: emptyList())) {
                if (entry != null) bucketOf.putIfAbsent(entry, bucket)
            }
            // `others` holds every model type without a bucket of its own (query, template, sequence, …).
            if (type == "app") {
                for (entry in (result["others"] as? List<Any?> ?: emptyList())) {
                    if (entry != null) bucketOf.putIfAbsent(entry, "others")
                }
            }
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
            if (k == "graph") continue
            out[k] = v
        }
        if (graph != null) {
            val projected = LinkedHashMap<String, Any?>()
            projected["nodes"] = (nodes ?: emptyList()).map { n ->
                val copy = LinkedHashMap<String, Any?>(n)
                val data = n["data"]
                val bucket = if (data != null) bucketOf[data] else null
                if (bucket != null) copy["data"] = linkedMapOf<String, Any?>("dataIn" to bucket)
                val refs = usedBy[n["id"]]
                if (!refs.isNullOrEmpty()) copy["usedBy"] = refs.toList()
                copy
            }
            projected["edges"] = edges ?: emptyList<Any?>()
            out["graph"] = projected
        }
        return out
    }

    /** Self-description, so the file explains its own shape to whoever opens it. */
    private val SCHEMA: Map<String, Any?> = linkedMapOf(
        "about" to "Flowable Atlas project graph. Query it — do not read it whole; a large project " +
            "runs to megabytes.",
        "node" to "{id: '<type>:<key>', type, label, key, file, data, usedBy?}",
        "edge" to "{s: <sourceNodeId>, t: <targetNodeId>, rel, suspect?, dynamic?}",
        "dataIn" to "A model node's data lives once in the named top-level bucket — find it by matching " +
            "type+key there (e.g. data.dataIn='processes' -> .processes[] | select(.key==\"<key>\")).",
        "usedBy" to "Node ids that reference this node (app 'contains' membership excluded). The " +
            "relation itself is in `edges`.",
        "findings" to "{check, severity, node, label, message, file?, element?, line?, snippet?} — what " +
            "Atlas thinks is wrong; `checks` counts them per kind.",
        "recipes" to listOf(
            "who references X:            jq '.graph.nodes[] | select(.id==\"process:X\") | .usedBy'",
            "what X references:           jq '.graph.edges[] | select(.s==\"process:X\")'",
            "a model's body:              jq '.processes[] | select(.key==\"X\")'",
            "models using variable V:     jq '.graph.nodes[] | select(.id==\"variable:V\") | .data.usedBy'",
            "controller serving a form:   jq '.restCalls[] | select(.source|test(\"X\")) | .matches'",
            "everything broken:           jq '.findings[] | select(.severity==\"error\")'",
        ),
    )
}
