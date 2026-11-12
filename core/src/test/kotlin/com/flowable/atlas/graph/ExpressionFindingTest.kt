package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An expression finding as a project produces it: from the condition in the model, through the graph's
 * expression node, to the finding a report lists.
 */
class ExpressionFindingTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-expr-finding-test").toFile()
            File(dir, "processes").mkdirs()
            File(dir, "processes/route.bpmn20.xml").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="DEMO-route">
                       <userTask id="decide" name="Decide"/>
                       <sequenceFlow id="f1" sourceRef="decide" targetRef="a">
                         <conditionExpression>${'$'}{vars:get('route', 'Decision 1')}</conditionExpression>
                       </sequenceFlow>
                       <sequenceFlow id="f2" sourceRef="decide" targetRef="b">
                         <conditionExpression>${'$'}{vars:equals(route, 'Decision 2')}</conditionExpression>
                       </sequenceFlow>
                       <userTask id="a"/>
                       <userTask id="b"/>
                     </process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findings(check: String) =
        (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == check }

    @Test
    fun aVariablesCallWithTheWrongArgumentCountIsAnInvalidExpression() {
        val f = findings("invalidExpr").single()
        assertEquals("expression:\${vars:get('route', 'Decision 1')}", f["node"])
        assertEquals("error", f["severity"])
        assertTrue(f["message"].toString(), f["message"].toString().contains("takes 1 argument"))
        // the well-formed call next to it is no finding at all
        assertTrue(findings("suspectExpr").isEmpty())
    }

    @Test
    fun anExpressionFindingNamesTheModelThatHoldsItAndOpensThere() {
        val f = findings("invalidExpr").single()
        assertEquals(listOf("process:DEMO-route"), f["models"])
        assertEquals("processes/route.bpmn20.xml", f["file"])
        assertEquals(5, f["line"])
    }
}
