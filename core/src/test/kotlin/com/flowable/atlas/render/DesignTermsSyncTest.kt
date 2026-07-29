package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.DesignTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The vocabulary contract: **every word Atlas shows a reader is the word Flowable Design uses.**
 *
 * Two failure modes are guarded here, both of which happened before this test existed:
 *  - **Drift** — the frontend table (`DESIGN_TERMS` in `frontend/explorer.js`) and the Kotlin half
 *    ([DesignTerms], used by the Markdown renderers) disagree, so the same element is a "User task" in
 *    the explorer and a `userTask` in `overview.md`.
 *  - **Leaks** — Atlas emits an identifier nobody gave a Design term, and `term()`/`label()` fall back
 *    to the raw key: `planItemLifecycleListener` and `casePlanModel` reached real users that way.
 *
 * The coverage half runs over the `miniproject` fixture *plus* a fixed list of vocabulary only real
 * projects produce — otherwise the small fixture would happily hide the gap.
 */
class DesignTermsSyncTest {

    private val explorerJs: String by lazy {
        javaClass.getResourceAsStream("/frontend/explorer.js")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("frontend/explorer.js not on the test classpath")
    }

    /** `'<ns>:<key>': ['<Label>', …]` / `[null, …]` — the frontend term table, parsed from the resource. */
    private val jsTerms: Map<String, String> by lazy {
        val body = explorerJs.substringAfter("const DESIGN_TERMS").substringBefore("\n};")
        Regex("'([A-Za-z-]+:[^']+)'\\s*:\\s*\\[\\s*(?:null|'((?:[^'\\\\]|\\\\.)*)')")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** `<type>:['<Plural label>','<Section>']` — the explorer's node-type labels (`TM`). */
    private val jsTypeLabels: Map<String, String> by lazy {
        val body = explorerJs.substringAfter("const TM = {").substringBefore("\n};")
        Regex("(\\w+)\\s*:\\s*\\[\\s*'((?:[^'\\\\]|\\\\.)*)'")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun elementTermsAgreeWithTheFrontendTable() {
        val els = DesignTerms.keys.filter { it.startsWith("el:") }
        val missing = els.filter { it !in jsTerms }
        assertTrue(
            "DesignTerms.kt has element terms the frontend table does not know: $missing",
            missing.isEmpty(),
        )
        for (key in els) {
            assertEquals(
                "label for '$key' differs between DesignTerms.kt and frontend/explorer.js",
                jsTerms.getValue(key),
                DesignTerms.label("el", key.substringAfter(':')),
            )
        }
    }

    /**
     * The Markdown renderers count model types in the singular ("1 decision table"), the explorer labels
     * them in the plural ("Decision tables"). Both must be the same word — otherwise the summary and the
     * sidebar disagree about what a thing is called.
     */
    @Test
    fun modelTypeTermsMatchTheExplorerSidebarLabels() {
        for (key in DesignTerms.keys.filter { it.startsWith("type:") }) {
            val type = key.substringAfter(':')
            val sidebar = jsTypeLabels[type] ?: error("TM in explorer.js has no label for node type '$type'")
            assertEquals(
                "'$type': the sidebar says '$sidebar', DesignTerms pluralizes to " +
                    "'${DesignTerms.pluralize(DesignTerms.label("type", type))}'",
                sidebar.lowercase(),
                DesignTerms.pluralize(DesignTerms.label("type", type)).lowercase(),
            )
        }
    }

    @Test
    fun everyVocabularyValueAtlasEmitsHasADesignTerm() {
        val els = LinkedHashSet<String>()
        val kinds = LinkedHashSet<String>()
        val rels = LinkedHashSet<String>()

        @Suppress("UNCHECKED_CAST")
        fun map(v: Any?) = v as? Map<String, Any?> ?: emptyMap()
        fun list(v: Any?) = (v as? List<*>).orEmpty().map { map(it) }

        val result = Atlas.extract(fixtureDir())
        for (bucket in listOf("processes", "cases")) {
            for (model in list(result[bucket])) {
                for (p in list(model["ioParameters"])) {
                    (p["kind"] as? String)?.let { kinds.add(it) }
                    (p["elementType"] as? String)?.let { els.add(it) }
                }
                for (key in listOf("events", "gateways", "otherTasks", "subProcesses", "eventListeners")) {
                    for (el in list(model[key])) (el["type"] as? String)?.let { els.add(it) }
                }
                // listeners of the model and of every element record
                fun listenerKinds(holder: Map<String, Any?>) {
                    for (l in list(holder["listeners"])) (l["kind"] as? String)?.let { els.add(it) }
                }
                listenerKinds(model)
                for (key in listOf("userTasks", "serviceTasks", "scriptTasks", "ruleTasks",
                        "callActivities", "subProcesses", "events", "gateways", "otherTasks")) {
                    for (el in list(model[key])) listenerKinds(el)
                }
                fun walk(node: Map<String, Any?>) {
                    (node["type"] as? String)?.let { els.add(it) }
                    listenerKinds(node)
                    for (child in list(node["children"])) walk(child)
                }
                (model["planModel"] as? Map<*, *>)?.let { walk(map(it)) }
            }
        }
        for (edge in list(map(result["graph"])["edges"])) (edge["rel"] as? String)?.let { rels.add(it) }

        // Vocabulary the fixture cannot produce but real projects do (measured over three real graphs).
        els.addAll(listOf("casePlanModel", "planItemLifecycleListener", "taskListener", "eventListener",
            "humanTask", "processTask", "caseTask", "decisionTask", "stage", "milestone"))
        rels.addAll(listOf("createInstances", "updateInstances", "deleteInstances", "queryInstances",
            "owner", "watcher", "read", "update", "query", "task-delegate",
            "document-create-form", "document-edit-form"))

        val untermed = LinkedHashMap<String, String>()
        fun check(ns: String, values: Set<String>) {
            for (v in values) {
                if (isGenerated(ns, v)) continue
                if ("$ns:$v" !in jsTerms) untermed["$ns:$v"] = v
            }
        }
        check("el", els)
        check("kind", kinds)
        check("rel", rels)
        assertTrue(
            "no Design term for: ${untermed.keys} — add them to DESIGN_TERMS in frontend/explorer.js " +
                "(and to DesignTerms.kt if a Markdown renderer prints them)",
            untermed.isEmpty(),
        )
    }

    /**
     * Values Atlas *composes* rather than picks from a vocabulary: a `calls someMethod()` relation names
     * the method, and a listener relation carries its event (`taskListener:complete`). `term()` resolves
     * both from their stem, so they need no entry of their own.
     */
    private fun isGenerated(ns: String, value: String): Boolean = when {
        ns != "rel" -> false
        value.startsWith("calls ") && value.endsWith("()") -> true
        value.contains(':') && "el:${value.substringBefore(':')}" in jsTerms -> true
        else -> false
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }
}
