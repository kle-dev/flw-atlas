package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A finding opens where it is: the element's declaration, the reference a missing model is named by, the
 * line of the script a script problem sits on — in a loose file and inside an archive alike.
 */
class FindingLinesTest {

    private fun line(text: String, needle: String) = text.lines().indexOfFirst { needle in it } + 1

    @Test
    fun aScriptBodyStartsWhereItsTrimmedTextDoes() {
        val bpmn = """<definitions>
            <process id="p">
              <scriptTask id="cdata" scriptFormat="groovy">
                <extensionElements>
                  <flowable:executionListener event="start"><flowable:script language="groovy">listener()</flowable:script></flowable:executionListener>
                </extensionElements>
                <script><![CDATA[
                  first()
                ]]></script>
              </scriptTask>
              <scriptTask id="inline" scriptFormat="groovy"><script>only()</script></scriptTask>
              <scriptTask id="none" scriptFormat="groovy"/>
              <serviceTask id="svc">
                <extensionElements>
                  <flowable:executionListener event="start" class="com.example.X"/>
                  <flowable:executionListener event="end">
                    <flowable:script language="groovy">
                      second()
                    </flowable:script>
                  </flowable:executionListener>
                </extensionElements>
              </serviceTask>
            </process>
          </definitions>"""
        assertEquals(line(bpmn, "first()"), FindingLines.bodyLine(bpmn, FindingLines.ScriptSite("cdata")))
        assertEquals(line(bpmn, "only()"), FindingLines.bodyLine(bpmn, FindingLines.ScriptSite("inline")))
        assertEquals(line(bpmn, "listener()"), FindingLines.bodyLine(bpmn, FindingLines.ScriptSite("cdata", "executionListener", 0)))
        // the second execution listener of the element, past the one with no script
        assertEquals(line(bpmn, "second()"), FindingLines.bodyLine(bpmn, FindingLines.ScriptSite("svc", "executionListener", 1)))
        // a task that closes itself has no body, and no line borrowed from the next one
        assertNull(FindingLines.bodyLine(bpmn, FindingLines.ScriptSite("none")))
        val cmmn = """<definitions><case id="c"><casePlanModel id="plan">
            <task id="t" flowable:type="script" flowable:scriptFormat="groovy">
              <extensionElements>
                <flowable:field name="scriptFormat"><flowable:string>groovy</flowable:string></flowable:field>
                <flowable:field name="script">
                  <flowable:string><![CDATA[
                    third()]]></flowable:string>
                </flowable:field>
              </extensionElements>
            </task>
          </casePlanModel></case></definitions>"""
        assertEquals(line(cmmn, "third()"), FindingLines.bodyLine(cmmn, FindingLines.ScriptSite("t")))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private val caller = """<definitions xmlns:flowable="http://flowable.org/bpmn">
        <process id="DEMO-caller" name="Caller">
          <startEvent id="s"/>
          <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
          <callActivity id="call" calledElement="DEMO-missing"/>
          <sequenceFlow id="f2" sourceRef="call" targetRef="gw"/>
          <exclusiveGateway id="gw"/>
          <sequenceFlow id="f3" sourceRef="gw" targetRef="e"><conditionExpression>${'$'}{x == 1}</conditionExpression></sequenceFlow>
          <scriptTask id="script" scriptFormat="groovy"><script>
            def a = 1
            execution.setVariabel('a', a)
          </script></scriptTask>
          <endEvent id="e"/>
        </process>
      </definitions>"""

    private fun extract(files: Map<String, Any>, waivers: Waivers.Set = Waivers.EMPTY): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-finding-lines").toFile()
        try {
            for ((path, content) in files) {
                val f = File(dir, path).apply { parentFile.mkdirs() }
                if (content is ByteArray) f.writeBytes(content) else f.writeText(content as String)
            }
            return Atlas.extract(dir, waivers = waivers)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.finding(check: String) =
        (this["findings"] as List<Map<String, Any?>>).single { it["check"] == check }

    @Test
    fun aFindingCarriesTheLineOfWhatItIsAbout() {
        val r = extract(mapOf("processes/caller.bpmn20.xml" to caller))
        val missing = r.finding("missingRefs")
        // reported where the key is named, by the model that names it
        assertEquals("processes/caller.bpmn20.xml", missing["file"])
        assertEquals(line(caller, "calledElement"), missing["line"])
        assertEquals("`Caller` references process `DEMO-missing`, which does not exist in this project", missing["message"])
        assertEquals(line(caller, "id=\"gw\""), r.finding("gatewayNoDefault")["line"])
        // a script problem on the line of the script it is on
        assertEquals(line(caller, "setVariabel"), r.finding("scriptIssues")["line"])
        assertNull(r.finding("scriptIssues")["scriptLine"])
    }

    @Test
    fun theLineIsFoundInsideAnArchiveAndAWaiverStillMatches() {
        val waivers = Waivers.parse("""{"version":1,"waivers":[{"check":"missingRefs","node":"external:DEMO-missing","reason":"elsewhere"}]}""")
        val r = extract(mapOf("apps/demo.zip" to zip("processes/caller.bpmn20.xml" to caller)), waivers)
        val missing = r.finding("missingRefs")
        assertEquals("apps/demo.zip!processes/caller.bpmn20.xml", missing["file"])
        assertEquals(line(caller, "calledElement"), missing["line"])
        assertTrue("the node is the identity, not the file the finding now carries", missing["waived"] != null)
    }

    /** A file Atlas chose not to read — above the size cap, a link out of the project — is not read for a line either. */
    @Test
    fun aParseIssueDoesNotReadTheFileItNames() {
        val read = ArrayList<String>()
        val skipped = linkedMapOf<String, Any?>("check" to "parseIssues", "file" to "DEMO-huge.bpmn", "message" to "skip: too large")
        val missing = linkedMapOf<String, Any?>("check" to "missingRefs", "node" to "external:DEMO-x", "file" to "DEMO-caller.bpmn")
        FindingLines.locate(listOf(skipped, missing), emptyMap(), { it.substringAfter(':') }) { file ->
            read += file
            "<definitions>\n  <callActivity id=\"c\" calledElement=\"DEMO-x\"/>\n</definitions>"
        }
        assertEquals(listOf("DEMO-caller.bpmn"), read)
        assertNull(skipped["line"])
        assertEquals(2, missing["line"])
    }
}
