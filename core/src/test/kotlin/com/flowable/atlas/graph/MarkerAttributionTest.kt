package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A marker belongs to the model whose element holds it, is named by where it sits, and is one finding. */
class MarkerAttributionTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-marker-test").toFile()
            File(dir, "two.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="first"><documentation>fine</documentation><userTask id="a"/></process>
                     <process id="second"><documentation>TODO wire the escalation</documentation><userTask id="b"/></process>
                   </definitions>""")
            val form = """{"metadata":{"key":"minified","name":"Minified","modelType":"form"},"rows":[{"cols":[{"id":"x","type":"text","label":"TODO"}]}]}"""
            File(dir, "minified.form").writeText(form)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("minified.form")); z.write(form.toByteArray()); z.closeEntry() }
            File(dir, "export.zip").writeBytes(out.toByteArray())
            // the same form twice, as an app's `.form` and as a Design export whose components nest — one
            // label marked "(TODO)", spelled by two different paths
            File(dir, "DEMO-F001.form").writeText(
                """{"metadata":{"key":"DEMO-F001","name":"Demo form","modelType":"form"},"rows":[{"cols":[{"id":"text1","type":"text","label":"Crew"}]},{"cols":[{"id":"text3","type":"text","label":"Current Pod (TODO)"}]}]}""")
            val design = """{"key":"DEMO-F001","name":"Demo form","modelType":"form","components":[{"id":"panel1","type":"panel","components":[{"id":"text1","type":"text","label":"Crew"},{"id":"text3","type":"text","label":"Current Pod (TODO)"}]}]}"""
            val dz = ByteArrayOutputStream()
            ZipOutputStream(dz).use { z -> z.putNextEntry(ZipEntry("form-models/DEMO-F001.json")); z.write(design.toByteArray()); z.closeEntry() }
            File(dir, "DEMO-design.zip").writeBytes(dz.toByteArray())
            // the marker words inside other words, and a model with none at all
            File(dir, "words.bpmn").writeText(
                """<definitions><process id="words"><documentation>TODOS from the HACKATHON, xFIXME</documentation></process></definitions>""")
            File(dir, "clean.bpmn").writeText("""<definitions><process id="clean"><userTask id="c"/></process></definitions>""")
            File(dir, "case.cmmn").writeText(caseXml(""))
            File(dir, "task.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="DEMO-fields">
                       <serviceTask id="t" flowable:class="com.example.X">
                         <extensionElements>
                           <flowable:field name="a"><flowable:string>TODO</flowable:string></flowable:field>
                           <flowable:field name="b"><flowable:string>TODO</flowable:string></flowable:field>
                         </extensionElements>
                       </serviceTask>
                     </process>
                   </definitions>""")
            // a marker under a `definitions` root that carries an id: the file, not an element
            File(dir, "defs.bpmn").writeText(
                """<definitions id="DEMO-defs" xmlns:flowable="http://flowable.org/bpmn">
                     <!-- TODO -->
                     <process id="DEMO-d"><userTask id="u"/></process>
                   </definitions>""")
            result = Atlas.extract(dir)
            // the same case with a line inserted above both markers
            val moved = Files.createTempDirectory("atlas-marker-moved").toFile()
            try {
                File(moved, "case.cmmn").writeText(caseXml("\n<!-- a note -->\n"))
                movedResult = Atlas.extract(moved)
            } finally {
                moved.deleteRecursively()
            }
        }

        private lateinit var movedResult: Map<String, Any?>

        /** Two sentries whose documentation is only "TODO", each guarding a plan item by its criterion. */
        private fun caseXml(above: String) = """<definitions xmlns:flowable="http://flowable.org/cmmn">$above
              <case id="DEMO-C001">
                <casePlanModel id="plan">
                  <planItem id="p1" definitionRef="h1"><entryCriterion id="crit2" sentryRef="sentry2"/></planItem>
                  <planItem id="p2" definitionRef="h2"><entryCriterion id="crit3" sentryRef="sentry3"/></planItem>
                  <sentry id="sentry2"><documentation>TODO</documentation><planItemOnPart sourceRef="p2"/></sentry>
                  <sentry id="sentry3"><documentation>TODO</documentation><planItemOnPart sourceRef="p1"/></sentry>
                  <humanTask id="h1"/><humanTask id="h2"/>
                </casePlanModel>
              </case>
            </definitions>"""

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun markers() = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "leftoverMarkers" }

    @Test
    fun aMarkerBelongsToTheProcessWhoseElementHoldsIt() {
        val todo = markers().single { it["file"] == "two.bpmn" }
        assertEquals("process:second", todo["node"])
        assertEquals("wire the escalation", todo["subject"])
    }

    @Test
    fun aMarkerInALabelNamesTheComponentAndWhatItSays() {
        val inForm = markers().filter { it["node"] == "form:DEMO-F001" }
        assertEquals("app copy + Design export = one finding: $inForm", 1, inForm.size)
        val f = inForm.single()
        assertEquals("text3", f["element"])
        assertEquals("TODO in the label: \"Current Pod (TODO)\"", f["message"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aMarkerIsAWholeWordAndAModelWithoutOneHasNone() {
        val files = (result["markers"] as List<Map<String, Any?>>).map { it["file"] }
        assertTrue(files.toString(), "words.bpmn" !in files && "clean.bpmn" !in files)
    }

    @Test
    fun aTextlessMarkerInAnXmlModelIsNamedByItsElementNotItsLine() {
        @Suppress("UNCHECKED_CAST")
        fun inCase(r: Map<String, Any?>) = (r["findings"] as List<Map<String, Any?>>)
            .filter { it["check"] == "leftoverMarkers" && it["file"] == "case.cmmn" }.sortedBy { it["subject"].toString() }
        val f = inCase(result)
        assertEquals(listOf("sentry2/documentation", "sentry3/documentation"), f.map { it["subject"] })
        // a sentry is shown by the diamond of the criterion that uses it
        assertEquals(listOf("crit2", "crit3"), f.map { it["element"] })
        assertEquals(listOf("TODO in the documentation", "TODO in the documentation"), f.map { it["message"] })
        // a line inserted above moves both lines, and neither subject
        val moved = inCase(movedResult)
        assertEquals(f.map { it["subject"] }, moved.map { it["subject"] })
        assertEquals(f.map { (it["line"] as Int) + 2 }, moved.map { it["line"] })
        // two in one element stay two, told apart by their order there
        assertEquals(listOf("t/string", "t/string#2"), markers().filter { it["file"] == "task.bpmn" }.map { it["subject"] }.sortedBy { it.toString() })
        // a text-bearing marker keeps its text as subject; on the process itself it names no element
        val todo = markers().single { it["file"] == "two.bpmn" }
        assertEquals(null, todo["element"])
        // the `definitions` root is no element to jump to, its id notwithstanding
        val root = markers().single { it["file"] == "defs.bpmn" }
        assertEquals("DEMO-defs/#comment", root["subject"])
        assertEquals(null, root["element"])
    }

    @Test
    fun aTextlessMarkerInAMinifiedModelIsNamedByItsPathAndReportedOnce() {
        val inForm = markers().filter { it["node"] == "form:minified" }
        assertEquals("loose + archived copy = one finding: $inForm", 1, inForm.size)
        assertEquals("rows[0].cols[0].label", inForm.single()["subject"])
    }
}
