package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The cross-language golden harness for the Kotlin generator port (Phase 1).
 *
 * Runs `:core`'s [Atlas.extract] over the same `miniproject` fixture the Python suite uses and asserts
 * each **implemented** result section matches the committed `core/src/test/resources/golden/miniproject.graph.json`
 * (compared through [GoldenNormalize], exactly as Python's `test_graph_golden` does). Sections are
 * added to [IMPLEMENTED_SECTIONS] as their parsers are ported, so the suite stays green while the port
 * grows and immediately flags any regression in an already-ported section.
 *
 * Python stays the authoritative oracle until every section is covered here (see the migration plan).
 */
class GoldenExtractionTest {

    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir()) }
    private val golden: Map<String, Any?> by lazy { loadGolden() }

    @Test
    fun implementedSectionsMatchGolden() {
        for (section in IMPLEMENTED_SECTIONS) {
            val mine = GoldenNormalize.canonicalTree(result[section])
            val expected = golden[section]
            assertEquals("result section '$section' differs from core/src/test/resources/golden/miniproject.graph.json", expected, mine)
        }
    }

    /** The whole `extract()` result must equal the whole committed golden — the same total gate as
     *  Python's `test_graph_golden` (every top-level key, normalized and compared). Includes
     *  `diagnostics`/`warnings`: `:core`'s [com.flowable.atlas.model.MiniJson] now emits Python-`json`
     *  -style parse-error messages, so even the broken-file diagnostic text matches. */
    @Test
    fun wholeResultMatchesGolden() {
        val mine = GoldenNormalize.canonicalTree(result)
        assertEquals("full result differs from core/src/test/resources/golden/miniproject.graph.json", golden, mine)
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadGolden(): Map<String, Any?> {
        val text = javaClass.classLoader.getResourceAsStream("golden/miniproject.graph.json")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("golden not on the test classpath")
        return MiniJson.parse(text) as Map<String, Any?>
    }

    companion object {
        /**
         * Result sections whose parsers are ported and expected to match the golden. Grow this as the
         * port advances. Includes buckets that must stay empty for this fixture, guarding against a
         * parser wrongly populating them.
         */
        private val IMPLEMENTED_SECTIONS = listOf(
            // parsed model buckets (apps/processes/cases/forms/dataObjects now carry `_uses`)
            "apps", "processes", "cases", "decisions", "forms", "dataObjects",
            "events", "policies", "services",
            // reference-resolution outputs
            "resolvedRefs", "unresolvedRefs", "beans", "beanIndex", "delegateClasses",
            "endpoints", "access", "groups", "variables", "expressions",
            "placeholders", "dynamicRefs", "javaControllers", "javaByRole", "modelIndex",
            "customFunctions", "restCalls",
            // liquibase coverage
            "liquibase",
            // graph + stats produced by `_build_graph`
            "graph", "stats",
            // diagnostics/warnings — match now that MiniJson emits Python-json-style messages
            "diagnostics", "warnings",
            // buckets that must remain empty for this fixture
            "actions", "agents", "channels", "dictionaries", "others", "javaBeans", "javaGlue",
        )
    }
}
