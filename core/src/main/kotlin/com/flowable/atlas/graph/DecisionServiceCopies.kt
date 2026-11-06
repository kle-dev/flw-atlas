package com.flowable.atlas.graph

import com.flowable.atlas.parsing.ModelKinds

/**
 * Joins a decision service to the decision tables it runs. A DRD Design exports does not point at its
 * tables: it holds a copy of each under a generated id — `<decision id="decisionElement_1"
 * flowable:decisionKey="DEMO-table">` in a deployment, `<decision id="decision1">` in an older Design
 * export, whose link to the table is only in the service's JSON twin
 * (`childShapes[].properties.decisiontablereference.key`). Read as they stand, the copies were decisions
 * of their own: phantom `decisionElement_1` nodes, the service linked to them and never to the tables,
 * every table only the service consults reported as unused — "delete it" would have broken the service —
 * and each output written twice.
 *
 * A copy whose table is in the project merges into it: the copy's record, references and variable sites
 * go, and the service's `contains-decision` reference (and a `requires` between two of its decisions)
 * names the table. A copy whose table is not in the project stays, as the service's decision, with
 * `copyOf` naming the table: that content is what the service runs, and a reference to the absent table
 * would have been a missing model where nothing is missing at run time.
 *
 * Applied once every model is read, because the JSON twin may come before or after its DRD in an archive.
 */
object DecisionServiceCopies {

    /**
     * Records the table each decision of a decision service's Design JSON twin copies, by the decision's
     * element id — the shape's `resourceId`, which is the id the DRD gives the same decision.
     */
    fun readDesignTwin(ctx: Ctx, service: String, body: Map<*, *>?) {
        fun walk(shape: Map<*, *>) {
            for (c in (shape["childShapes"] as? List<*>).orEmpty()) {
                val child = c as? Map<*, *> ?: continue
                val id = (child["resourceId"] as? String)?.trim()
                val ref = (child["properties"] as? Map<*, *>)?.get("decisiontablereference")
                // `{"id": "DMN_MODEL-…", "key": "X"}`, or the bare key in an older export
                val table = ((ref as? Map<*, *>)?.get("key") ?: ref) as? String
                if (!id.isNullOrEmpty() && !table.isNullOrBlank() && table.trim() != id) {
                    ctx.addDecisionCopy(service, id, table.trim())
                }
                walk(child)
            }
        }
        body?.let(::walk)
    }

    @Suppress("UNCHECKED_CAST")
    fun apply(
        result: Map<String, Any?>,
        ctx: Ctx,
        modelIndex: MutableMap<Pair<String, String>, String>,
        byKey: MutableMap<String, MutableList<Pair<String, String>>>,
    ) {
        if (ctx.decisionCopies.isEmpty()) return
        val decisions = result["decisions"] as? MutableList<Any?> ?: return
        val records = decisions.mapNotNull { it as? MutableMap<String, Any?> }
        // A table is a decision of its own file, not a service's: only such a one can stand for a copy.
        val tables = records.filter { it["embeddedIn"] == null && it["decisionService"] != true }
            .mapNotNull { it["key"] as? String }.toSet()
        // (DRD file, element id) → the table that copy merges into. By file, because the ids repeat from
        // one DRD to the next; the same DRD loose and inside an archive is two files and merges twice.
        val merged = HashMap<Pair<Any?, Any?>, String>()
        for (r in records) {
            val service = r["embeddedIn"] as? String ?: continue
            val id = r["key"] as? String ?: continue
            val table = ctx.decisionCopies[service]?.get(id) ?: continue
            r["copyOf"] = table
            if (table in tables) merged[r["file"] to id] = table
        }
        if (merged.isEmpty()) return
        fun into(file: Any?, id: Any?): String? = merged[file to id]

        decisions.removeAll { o -> (o as? Map<*, *>)?.let { it["embeddedIn"] != null && into(it["file"], it["key"]) != null } == true }
        for (r in records) if (r["decisionService"] == true) {
            r["decisions"] = (r["decisions"] as? List<*>).orEmpty().map { into(r["file"], it) ?: it }
        }
        fun fromDecision(r: Map<String, Any?>) =
            (r["fromType"] as? String)?.let { ModelKinds.NORMALIZE_TYPE[it] ?: it } == "decision"
        for (refs in listOf(ctx.refs, ctx.dynamicRefs)) {
            val iter = refs.iterator()
            while (iter.hasNext()) {
                val r = iter.next()
                if (!fromDecision(r)) continue
                val file = r["fromFile"]
                when (r["rel"]) {
                    "contains-decision" -> into(file, r["value"])?.let { r["value"] = it }
                    // the DRD's own structure — this table's input is that one's output — kept between the tables
                    "requires" -> {
                        into(file, r["from"])?.let { r["from"] = it }
                        into(file, r["value"])?.let { r["value"] = it }
                    }
                    // anything else the copy references, its table references too
                    else -> if (into(file, r["from"]) != null) iter.remove()
                }
            }
        }
        ctx.varSites.removeAll { s ->
            val file = s[Ctx.DRD_FILE] ?: return@removeAll false
            into(file, (s["model"] as? String)?.substringAfter(':')) != null
        }
        // The copy's index entries go with it. Another DRD's decision may still carry that id — a copy whose
        // table is not in the project — and then the index names that one's file: left at the merged copy's,
        // a reference to the one that stayed resolved to a file that no longer defines it.
        val remaining = HashMap<String, String>()
        for (o in decisions) {
            val d = o as? Map<*, *> ?: continue
            val key = d["key"] as? String ?: continue
            remaining[key] = d["file"] as? String ?: continue   // the last copy, as the index keeps it
        }
        for ((file, id) in merged.keys) {
            val key = id as String
            byKey[key]?.let { owners ->
                owners.removeAll { it.first == "decision" && it.second == file }
                if (owners.isEmpty()) byKey.remove(key)
            }
            val left = remaining[key]
            if (left == null) modelIndex.remove("decision" to key)
            else if (modelIndex["decision" to key] == file) modelIndex["decision" to key] = left
        }
    }
}
