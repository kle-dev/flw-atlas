package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.Dyn
import com.flowable.atlas.model.MiniJson
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The explorer page carries a form's wireframe as it carries a process's diagram — the same picture the
 * IDE's preview draws — within a budget, so a project with hundreds of forms does not double its page.
 * A decision drawn only as a table is not embedded: its page renders the rules as HTML. DEMO-* names.
 */
class ExplorerWireframeTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        private fun form(key: String, fields: Int) =
            """{"metadata":{"key":"$key","name":"$key","modelType":"form"},"rows":[""" +
                (1..fields).joinToString(",") { """{"cols":[{"id":"f$it","type":"text","label":"Field $it","size":12}]}""" } + "]}"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-explorer-wireframe-test").toFile()
            File(dir, "small.form").writeText(form("DEMO-small", 1).replace("\"f1\"", "\"amount\""))
            File(dir, "large.form").writeText(form("DEMO-large", 20))
            File(dir, "table.dmn").writeText(
                """<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"><decision id="DEMO-D1" name="D">
                  |<decisionTable hitPolicy="FIRST"><input label="T"><inputExpression><text>t</text></inputExpression></input>
                  |<output name="ok"/><rule id="r1"><inputEntry><text>1</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>
                  |</decisionTable></decision></definitions>""".trimMargin())
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(budget: ExplorerHtmlRenderer.WireframeBudget = ExplorerHtmlRenderer.WireframeBudget()): Map<String, Map<String, Any?>> {
        val copy = MiniJson.parse(MiniJson.stringify((result["graph"] as Map<String, Any?>)["nodes"])) as List<Any?>
        ExplorerHtmlRenderer.attachDiagrams(copy, dir, budget)
        return copy.associate { n -> val m = Dyn.mapOrNull(n)!!; m["id"] as String to (Dyn.mapOrNull(m["data"]) as Map<String, Any?>) }
    }

    @Test
    fun aFormCarriesItsWireframe() {
        val d = nodes().getValue("form:DEMO-small")
        assertEquals("wireframe", d["diagramKind"])
        assertTrue(d["diagram"].toString().contains("data-el=\"amount\""))
    }

    @Test
    fun aDecisionDrawnOnlyAsATableIsNotEmbedded() {
        val d = nodes().getValue("decision:DEMO-D1")
        assertNull(d["diagram"])
        assertNull(d["diagramKind"])
    }

    @Test
    fun overTheBudgetTheLargestFormsSayTheyAreLeftOut() {
        val small = (nodes().getValue("form:DEMO-small")["diagram"] as String).length
        val n = nodes(ExplorerHtmlRenderer.WireframeBudget(perPicture = 64_000, total = small + 10))
        assertTrue("the smaller one fits", n.getValue("form:DEMO-small")["diagram"] != null)
        val large = n.getValue("form:DEMO-large")
        assertNull(large["diagram"])
        assertTrue(large["diagramOmitted"].toString().contains("not embedded"))
    }

    @Test
    fun theExtractResultIsLeftAlone() {
        nodes()
        @Suppress("UNCHECKED_CAST")
        val raw = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).single { it["id"] == "form:DEMO-small" }
        assertFalse((raw["data"] as Map<*, *>).containsKey("diagram"))
    }
}
