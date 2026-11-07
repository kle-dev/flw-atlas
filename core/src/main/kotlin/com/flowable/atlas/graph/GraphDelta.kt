package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.parsing.ModelKinds
import java.security.MessageDigest

/**
 * What changed between two states of one project — the `--changes` report: models added, removed and
 * changed down to their elements, references added and removed, findings new and resolved.
 *
 * A Flowable project keeps its models in app archives, and git shows a commit to a `.zip` as a binary
 * change: a reviewer — or an agent reviewing a pull request — sees nothing of what it does to a process.
 * Atlas has read both versions already; this is the difference between the two readings.
 *
 * Either side is what [Atlas.extract] returns or a graph.json read back, and only what the two have in
 * common is compared: the model buckets, `modelCopies`, `graph.edges` and `findings`. A model node's
 * `data` is not — graph.json replaces it with a pointer into the bucket, so comparing it would call every
 * model changed.
 */
class GraphDelta(
    val modelsAdded: List<Model>,
    val modelsRemoved: List<Model>,
    val modelsChanged: List<ModelChange>,
    val edgesAdded: List<Edge>,
    val edgesRemoved: List<Edge>,
    val findingsNew: List<Map<String, Any?>>,
    val findingsResolved: List<Map<String, Any?>>,
) {
    /** A model, by its node id `<type>:<key>`, with the name and file its side gives it. */
    class Model(val id: String, val name: String?, val file: String?)

    /** An element both sides have, and its properties that differ — `to` for a flow that now goes elsewhere. */
    class ElementChange(val id: String, val fields: List<String>)

    /**
     * A model both sides have that is not the same. Its elements are whatever in it carries an `id` — a task, a
     * flow, a form field, a decision rule, a plan item, a changeset — so a renamed form field is one removed and
     * one added. [properties] are the model's own values that changed: its name, its documentation, a list
     * without ids such as its variable mappings.
     *
     * [elsewhere] is set only when all of that is empty: the copy the graph shows is the same, and the files
     * named hold another copy of the model that is not — a Design export changed beside its unchanged
     * deployment. Which elements changed there, a comparison of that archive on its own says.
     */
    class ModelChange(
        val model: Model, val added: List<String>, val removed: List<String>,
        val changed: List<ElementChange>, val properties: List<String>, val elsewhere: List<String> = emptyList(),
    )

    /** A reference as the graph has it: [s] refers to [t] as [rel]. */
    data class Edge(val s: String, val rel: String, val t: String)

    val isEmpty: Boolean
        get() = modelsAdded.isEmpty() && modelsRemoved.isEmpty() && modelsChanged.isEmpty() &&
            edgesAdded.isEmpty() && edgesRemoved.isEmpty() && findingsNew.isEmpty() && findingsResolved.isEmpty()

    companion object {

        /**
         * Where something is written, not what it is: a model that moved to another file, or an element pushed
         * down a line by the one added above it, has not changed.
         */
        private val WHERE = setOf("file", "line")

        /**
         * Not the model's own content but what the analysis concluded from it and the rest of the project: the
         * expressions and variables it uses, the problems found in a script, the literal secrets found in it,
         * the tests that deploy it, a service's coverage of the changelogs, a changelog's authority, and the
         * files its other copies are in. Most of them change when *another* file does — a test, a changelog, a
         * custom function added in Java — which would report an untouched model as changed; whatever they mean
         * for the project's health is a finding, and findings are compared on their own.
         */
        private val DERIVED = setOf(
            "_uses", "problems", "literalSecrets", "deployedByTests", "schemaCoverage", "crossedColumns", "coverage",
            "authority", "revisions", "otherCopies",
        )

        /** Both, dropped at every depth. */
        private val IGNORED = WHERE + DERIVED

        /** The delta from [base] to [head]; each is an extraction result or a parsed graph.json. */
        fun of(base: Map<String, Any?>, head: Map<String, Any?>): GraphDelta {
            val before = models(base)
            val after = models(head)
            val copiesBefore = copies(base)
            val copiesAfter = copies(head)
            val changed = after.mapNotNull { (id, h) ->
                val b = before[id] ?: return@mapNotNull null
                // The file each side shows is compared element by element; the model's other files, by what they say.
                compare(model(id, h), b, h) ?: elsewhere(
                    model(id, h),
                    copiesBefore[id].orEmpty().filterKeys { it != b["file"] },
                    copiesAfter[id].orEmpty().filterKeys { it != h["file"] },
                )
            }
            val edgesBefore = edges(base)
            val edgesAfter = edges(head)
            val byEnds = compareBy<Edge>({ it.s }, { it.t }, { it.rel })
            val findingsBefore = findings(base)
            val findingsAfter = findings(head)
            return GraphDelta(
                modelsAdded = after.filterKeys { it !in before }.map { (id, m) -> model(id, m) },
                modelsRemoved = before.filterKeys { it !in after }.map { (id, m) -> model(id, m) },
                modelsChanged = changed,
                edgesAdded = (edgesAfter - edgesBefore).sortedWith(byEnds),
                edgesRemoved = (edgesBefore - edgesAfter).sortedWith(byEnds),
                // The identity a waiver and the --baseline gate use, counted the same way — so the report and
                // the verdict of the same run name the same findings as new.
                findingsNew = Findings.newSince(findingsBefore, findingsAfter),
                findingsResolved = Findings.newSince(findingsAfter, findingsBefore),
            )
        }

        private fun model(id: String, m: Map<*, *>) = Model(id, m["name"] as? String, m["file"] as? String)

        /**
         * What a model says, as a short fingerprint: the same for two copies that differ only where they are
         * written or in what the analysis derived from them — what [compare] leaves out. [Atlas.extract] keeps one
         * per file of a model held in more than one (`modelCopies`).
         */
        internal fun fingerprint(model: Any?): String =
            MessageDigest.getInstance("SHA-256").digest(canonical(model).toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        /** `modelCopies`: model id → file → fingerprint, for the models held in more than one file. */
        private fun copies(result: Map<String, Any?>): Map<String, Map<String, String>> =
            (result["modelCopies"] as? Map<*, *>).orEmpty().entries.associate { (id, files) ->
                id.toString() to (files as? Map<*, *>).orEmpty().entries.associate { (file, print) -> file.toString() to print.toString() }
            }

        /**
         * A model whose shown copy is the same, changed by its other copies — [base] and [head] without the file
         * each side shows. Compared by what the copies say, not by where they are: an archive saved under another
         * name has not changed. Named by the head's files that say something new, or else by the base's files
         * whose copy is gone.
         */
        private fun elsewhere(model: Model, base: Map<String, String>, head: Map<String, String>): ModelChange? {
            val files = head.filterValues { it !in base.values }.keys.ifEmpty { base.filterValues { it !in head.values }.keys }
            if (files.isEmpty()) return null
            return ModelChange(model, emptyList(), emptyList(), emptyList(), emptyList(), elsewhere = files.sorted())
        }

        /**
         * Every model by node id, bucket by bucket in [ModelKinds.MODEL_BUCKETS] order, sorted within one. The
         * first of two entries with one id wins, as it does for the graph's node.
         */
        private fun models(result: Map<String, Any?>): Map<String, Map<*, *>> {
            val out = LinkedHashMap<String, Map<*, *>>()
            for (bucket in ModelKinds.MODEL_BUCKETS) {
                val inBucket = sortedMapOf<String, Map<*, *>>()
                for (e in (result[bucket] as? List<*>).orEmpty()) {
                    val m = e as? Map<*, *> ?: continue
                    val key = m["key"]?.let(::scalar) ?: continue
                    val id = "${ModelKinds.nodeType(bucket, m)}:$key"
                    if (id !in out && id !in inBucket) inBucket[id] = m
                }
                out.putAll(inBucket)
            }
            return out
        }

        /** App membership is not a reference to review: the app's own list of models says it, as a property. */
        private fun edges(result: Map<String, Any?>): Set<Edge> {
            val graph = result["graph"] as? Map<*, *> ?: return emptySet()
            return (graph["edges"] as? List<*>).orEmpty().mapNotNullTo(LinkedHashSet()) { e ->
                val m = e as? Map<*, *> ?: return@mapNotNullTo null
                val rel = m["rel"] as? String ?: return@mapNotNullTo null
                if (rel == "contains") return@mapNotNullTo null
                Edge(m["s"] as? String ?: return@mapNotNullTo null, rel, m["t"] as? String ?: return@mapNotNullTo null)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun findings(result: Map<String, Any?>): List<Map<String, Any?>> =
            (result["findings"] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()

        /** One occurrence of an element: the list (or member) it was found in, and its content without the elements nested in it. */
        private class Part(val member: String, val content: Map<String, Any?>)

        private class Body(val properties: Map<String, String>, val elements: Map<String, List<Part>>)

        private fun compare(model: Model, base: Map<*, *>, head: Map<*, *>): ModelChange? {
            val b = body(base)
            val h = body(head)
            val changed = h.elements.mapNotNull { (id, parts) ->
                val before = b.elements[id] ?: return@mapNotNull null
                differing(before, parts).takeIf { it.isNotEmpty() }?.let { ElementChange(id, it) }
            }
            val change = ModelChange(
                model,
                added = h.elements.keys.filter { it !in b.elements },
                removed = b.elements.keys.filter { it !in h.elements },
                changed = changed,
                properties = (b.properties.keys + h.properties.keys).filter { b.properties[it] != h.properties[it] }.sorted(),
            )
            return change.takeIf { it.added.isNotEmpty() || it.removed.isNotEmpty() || it.changed.isNotEmpty() || it.properties.isNotEmpty() }
        }

        private fun body(model: Map<*, *>): Body {
            val properties = sortedMapOf<String, String>()
            val elements = LinkedHashMap<String, MutableList<Part>>()
            for ((k, v) in model) {
                val key = k.toString()
                if (key in IGNORED) continue
                if (!collect(key, v, elements)) properties[key] = canonical(v)
            }
            return Body(properties, elements)
        }

        private fun elementId(v: Any?): String? = (v as? Map<*, *>)?.get("id")?.let(::scalar)

        /**
         * Gather the elements [v] holds under [member] into [into] — [v] being an element itself (a case's plan
         * model) or a list of nothing but elements; false when it is a plain value. An element's own content
         * leaves out the elements inside it, which are gathered beside it: a task added to a stage is that
         * task added, not the stage changed. An empty list holds no element and is not a value either, so a
         * list that fills up later shows as what was added to it.
         */
        private fun collect(member: String, v: Any?, into: MutableMap<String, MutableList<Part>>): Boolean {
            val items: List<Map<*, *>> = when {
                elementId(v) != null -> listOf(v as Map<*, *>)
                v is List<*> && v.all { elementId(it) != null } -> v.map { it as Map<*, *> }
                else -> return false
            }
            for (e in items) {
                val own = LinkedHashMap<String, Any?>()
                for ((k, value) in e) {
                    val key = k.toString()
                    if (key in IGNORED) continue
                    if (!collect(key, value, into)) own[key] = value
                }
                into.getOrPut(elementId(e)!!) { ArrayList() }.add(Part(member, own))
            }
            return true
        }

        /**
         * The properties in which an element's occurrences differ. An element can sit in two lists — a flow
         * with a condition is in `flows` and in `conditions` — and when it joins or leaves one, the list's name
         * is what changed.
         */
        private fun differing(before: List<Part>, after: List<Part>): List<String> {
            val out = sortedSetOf<String>()
            val b = before.groupBy { it.member }
            val a = after.groupBy { it.member }
            for (member in b.keys + a.keys) {
                val x = b[member].orEmpty()
                val y = a[member].orEmpty()
                if (x.size != y.size) { out.add(member); continue }
                for ((p, q) in x.zip(y)) {
                    for (k in p.content.keys + q.content.keys) {
                        if (canonical(p.content[k]) != canonical(q.content[k])) out.add(k)
                    }
                }
            }
            return out.toList()
        }

        /**
         * A value as comparable text: maps by sorted key, without [IGNORED]. The two sides need not hold the same
         * Kotlin types — a graph.json reads every number back as a Double — and MiniJson writes `3` for both.
         */
        private fun canonical(v: Any?): String = MiniJson.stringify(strip(v))

        private fun strip(v: Any?): Any? = when (v) {
            is Map<*, *> -> v.entries.filter { it.key.toString() !in IGNORED }.sortedBy { it.key.toString() }
                .associateTo(LinkedHashMap()) { it.key.toString() to strip(it.value) }
            is Iterable<*> -> v.map(::strip)
            is Array<*> -> v.map(::strip)
            else -> v
        }

        /** A key or id, written the same whether it was read as an Int or, from graph.json, as a Double. */
        private fun scalar(v: Any): String? = when (v) {
            is String -> v
            is Number, is Boolean -> MiniJson.stringify(v)
            else -> null
        }
    }
}
