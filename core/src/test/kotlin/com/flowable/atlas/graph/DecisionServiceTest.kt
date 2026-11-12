package com.flowable.atlas.graph

import com.flowable.atlas.render.ClaudeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A decision service and the decision tables it runs, in both shapes Design exports a DRD in: a deployment
 * names each copied table in `flowable:decisionKey` on a decision with a generated id; an older Design
 * export only in the service's JSON twin. Either way the service calls the tables — none of them is
 * unused, no generated id becomes a decision of its own, and a table's outputs are written once.
 */
class DecisionServiceTest {

    private fun table(key: String, input: String, output: String) = """
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="def_$key" namespace="http://www.flowable.org/dmn">
          <decision id="$key" name="$key">
            <decisionTable id="dt_$key" hitPolicy="FIRST">
              <input label="In"><inputExpression id="ie_$key" typeRef="string"><text>$input</text></inputExpression></input>
              <output id="o_$key" label="Out" name="$output" typeRef="string"/>
              <rule><inputEntry id="ri_$key"><text>-</text></inputEntry><outputEntry id="ro_$key"><text>"x"</text></outputEntry></rule>
            </decisionTable>
          </decision>
        </definitions>
    """.trimIndent()

    /** A deployment's DRD: generated ids, each copy naming its table — the copies' own output ids differ. */
    private val barDrd = """
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:flowable="http://flowable.org/dmn" id="def_ds" namespace="http://www.flowable.org/dmn">
          <decision id="decisionElement_1" name="Table" flowable:decisionKey="DEMO-table">
            <informationRequirement><requiredDecision href="#decisionElement_2"/></informationRequirement>
            <decisionTable id="dt_c1" hitPolicy="FIRST">
              <input label="In"><inputExpression id="ie_c1" typeRef="string"><text>level</text></inputExpression></input>
              <output id="o_c1" label="Out" name="risk" typeRef="string"/>
              <rule><inputEntry id="ri_c1"><text>-</text></inputEntry><outputEntry id="ro_c1"><text>"x"</text></outputEntry></rule>
            </decisionTable>
          </decision>
          <decision id="decisionElement_2" name="Input" flowable:decisionKey="DEMO-input">
            <decisionTable id="dt_c2" hitPolicy="FIRST">
              <input label="In"><inputExpression id="ie_c2" typeRef="string"><text>amount</text></inputExpression></input>
              <output id="o_c2" label="Out" name="level" typeRef="string"/>
              <rule><inputEntry id="ri_c2"><text>-</text></inputEntry><outputEntry id="ro_c2"><text>"x"</text></outputEntry></rule>
            </decisionTable>
          </decision>
          <decisionService id="DEMO-ds" name="DEMO service">
            <outputDecision href="#decisionElement_1"/>
            <encapsulatedDecision href="#decisionElement_2"/>
          </decisionService>
        </definitions>
    """.trimIndent()

    private val caller = """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://flowable.org/demo">
          <process id="DEMO-p" name="DEMO p">
            <startEvent id="s"/>
            <serviceTask id="t" name="Decide" flowable:type="dmn">
              <extensionElements>
                <flowable:field name="decisionServiceReferenceKey"><flowable:string>DEMO-ds</flowable:string></flowable:field>
              </extensionElements>
            </serviceTask>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
            <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
          </process>
        </definitions>
    """.trimIndent()

    /** [files] loose, and [archive] — entries in this order — as `DEMO-app.zip` beside them. */
    private fun extract(
        files: Map<String, String>,
        archive: List<Pair<String, String>> = emptyList(),
        check: (File, Map<String, Any?>) -> Unit,
    ) {
        val dir = Files.createTempDirectory("atlas-decision-service-test").toFile()
        try {
            for ((rel, text) in files) File(dir, rel).apply { parentFile.mkdirs(); writeText(text) }
            if (archive.isNotEmpty()) ZipOutputStream(File(dir, "DEMO-app.zip").outputStream()).use { zip ->
                for ((name, text) in archive) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            check(dir, Atlas.extract(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(result: Map<String, Any?>) =
        (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun edges(result: Map<String, Any?>) =
        ((result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>).map { Triple(it["s"], it["rel"], it["t"]) }

    @Suppress("UNCHECKED_CAST")
    private fun findings(result: Map<String, Any?>, check: String) =
        (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == check }

    private fun decisionNodes(result: Map<String, Any?>) =
        nodes(result).filter { it["type"] == "decision" }.map { it["key"] }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun writeCount(result: Map<String, Any?>, variable: String) =
        (nodes(result).first { it["id"] == "variable:$variable" }["data"] as Map<String, Any?>)["writeCount"]

    /** The service, its tables and their requirement, whichever shape linked them. */
    private fun assertJoined(result: Map<String, Any?>) {
        assertEquals(setOf("DEMO-ds", "DEMO-table", "DEMO-input"), decisionNodes(result))
        val e = edges(result)
        assertTrue(e.toString(), Triple("decision:DEMO-ds", "contains-decision", "decision:DEMO-table") in e)
        assertTrue(e.toString(), Triple("decision:DEMO-ds", "contains-decision", "decision:DEMO-input") in e)
        assertTrue(e.toString(), Triple("decision:DEMO-table", "requires", "decision:DEMO-input") in e)
        assertEquals(findings(result, "unusedDecisions").toString(), 0, findings(result, "unusedDecisions").size)
        assertEquals(findings(result, "missingRefs").toString(), 0, findings(result, "missingRefs").size)
        // each table's output once: the copy writes it through ids of its own
        assertEquals(1, writeCount(result, "risk"))
        assertEquals(1, writeCount(result, "level"))
        // the node is the table's own, not the copy's
        assertEquals("tables/DEMO-table.dmn", nodes(result).first { it["id"] == "decision:DEMO-table" }["file"])
        @Suppress("UNCHECKED_CAST")
        val ds = (result["decisions"] as List<Map<String, Any?>>).first { it["key"] == "DEMO-ds" }
        assertEquals(listOf("DEMO-table", "DEMO-input"), ds["decisions"])
    }

    @Test
    fun aDeploymentDrdRunsTheTablesItsDecisionKeysName() {
        // the DRD sorts first, so its copies are read before the tables they copy
        val drd = barDrd.replace("<decisionService ", "<!-- TODO DEMO confirm the thresholds -->\n  <decisionService ")
        extract(mapOf(
            "a-service.dmn" to drd,
            "tables/DEMO-table.dmn" to table("DEMO-table", "level", "risk"),
            "tables/DEMO-input.dmn" to table("DEMO-input", "amount", "level"),
            "DEMO-p.bpmn20.xml" to caller,
        )) { _, result ->
            assertJoined(result)
            // the file's first decision merged into its table: the marker belongs to the service, not to nothing
            assertEquals(listOf("decision:DEMO-ds"), findings(result, "leftoverMarkers").map { it["node"] })
        }
    }

    @Test
    fun anOlderDesignExportRunsTheTablesItsJsonTwinNames() {
        // ids `decision1`, no decisionKey: only the service's twin says which table each one is — and it
        // comes after the DRD in the archive, so the link cannot be made while the DRD is parsed
        val drd = barDrd.replace(" flowable:decisionKey=\"DEMO-table\"", "").replace(" flowable:decisionKey=\"DEMO-input\"", "")
            .replace("decisionElement_", "decision")
        val twin = """
            {"id": "m1", "key": "DEMO-ds", "name": "DEMO service", "editorJson": {
              "resourceId": "canvas", "stencil": {"id": "DMNDiagram"}, "childShapes": [
                {"resourceId": "expandedDecisionService1", "stencil": {"id": "ExpandedDecisionService"}, "childShapes": [
                  {"resourceId": "outputDecisions1", "childShapes": [
                    {"resourceId": "decision1", "stencil": {"id": "Decision"}, "childShapes": [],
                     "properties": {"decisiontablereference": {"id": "DMN_MODEL-1", "key": "DEMO-table"}}}]},
                  {"resourceId": "encapsulatedDecisions1", "childShapes": [
                    {"resourceId": "decision2", "stencil": {"id": "Decision"}, "childShapes": [],
                     "properties": {"decisiontablereference": {"id": "DMN_MODEL-2", "key": "DEMO-input"}}}]}]}]}}
        """.trimIndent()
        extract(
            mapOf(
                "tables/DEMO-table.dmn" to table("DEMO-table", "level", "risk"),
                "tables/DEMO-input.dmn" to table("DEMO-input", "amount", "level"),
                "DEMO-p.bpmn20.xml" to caller,
            ),
            archive = listOf(
                "decision-service-models/DEMO-ds.dmn" to drd,
                "decision-service-models/DEMO-ds.json" to twin,
            ),
        ) { _, result -> assertJoined(result) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCopyWhoseTableIsAbsentStaysTheServicesDecision() {
        extract(mapOf("a-service.dmn" to barDrd, "DEMO-p.bpmn20.xml" to caller)) { dir, result ->
            // what the service runs is that content, and nothing is missing at run time
            assertEquals(setOf("DEMO-ds", "decisionElement_1", "decisionElement_2"), decisionNodes(result))
            assertTrue(edges(result).toString(),
                Triple("decision:DEMO-ds", "contains-decision", "decision:decisionElement_1") in edges(result))
            assertEquals(0, findings(result, "missingRefs").size)
            assertEquals(0, findings(result, "unusedDecisions").size)
            val copy = (result["decisions"] as List<Map<String, Any?>>).first { it["key"] == "decisionElement_1" }
            assertEquals("DEMO-ds", copy["embeddedIn"])
            assertEquals("DEMO-table", copy["copyOf"])
            // two generated ids are no key convention to hand an agent
            val md = ClaudeRenderer.render(result, dir)
            assertFalse(md, md.contains("decisionElement"))
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCopyThatStaysKeepsItsOwnFileWhenAnotherDrdsCopyOfTheSameIdMerges() {
        // every DRD numbers its decisions from 1: here the one read last merges, the other one stays
        val other = barDrd.replace("DEMO-ds", "DEMO-ds2").replace("\"DEMO-table\"", "\"DEMO-absent\"")
            .replace("\"DEMO-input\"", "\"DEMO-absent-input\"")
        extract(mapOf(
            "a-other.dmn" to other,
            "b-service.dmn" to barDrd,
            "tables/DEMO-table.dmn" to table("DEMO-table", "level", "risk"),
            "tables/DEMO-input.dmn" to table("DEMO-input", "amount", "level"),
        )) { _, result ->
            assertEquals("a-other.dmn", (result["modelIndex"] as Map<String, Any?>)["decision:decisionElement_1"])
            val ref = (result["resolvedRefs"] as List<Map<String, Any?>>)
                .single { it["from"] == "DEMO-ds2" && it["value"] == "decisionElement_1" }
            assertEquals("a-other.dmn", ref["target"])
            assertTrue(edges(result).toString(),
                Triple("decision:DEMO-ds", "contains-decision", "decision:DEMO-table") in edges(result))
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aHandWrittenDrdKeepsItsDecisions() {
        // no decisionKey and no twin: the decisions are the service's own, named by hand
        val drd = barDrd.replace(" flowable:decisionKey=\"DEMO-table\"", "").replace(" flowable:decisionKey=\"DEMO-input\"", "")
        extract(mapOf("a-service.dmn" to drd, "tables/DEMO-table.dmn" to table("DEMO-table", "level", "risk"))) { _, result ->
            assertEquals(setOf("DEMO-ds", "decisionElement_1", "decisionElement_2", "DEMO-table"), decisionNodes(result))
            val own = (result["decisions"] as List<Map<String, Any?>>).first { it["key"] == "decisionElement_1" }
            assertEquals("DEMO-ds", own["embeddedIn"])
            assertNull(own["copyOf"])
            assertNotNull(findings(result, "unusedDecisions").singleOrNull { it["node"] == "decision:DEMO-table" })
        }
    }
}
