package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A deployment XML holding two processes: what one process's element carries belongs to that process
 * alone, what sits outside both (a message at definitions level) belongs to both.
 */
class MultiModelFileTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-multi-model-test").toFile()
            File(dir, "two.bpmn20.xml").writeText(
                """<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn">
                     <bpmn:message id="m1" name="order-${'$'}{sharedTopic}"/>
                     <bpmn:process id="alpha" name="Alpha">
                       <bpmn:serviceTask id="a1" flowable:expression="${'$'}{alphaBean.run()}"/>
                       <bpmn:sequenceFlow id="f1" sourceRef="a1" targetRef="a1">
                         <bpmn:conditionExpression>${'$'}{onlyAlpha == true}</bpmn:conditionExpression>
                       </bpmn:sequenceFlow>
                     </bpmn:process>
                     <bpmn:process id="beta" name="Beta">
                       <bpmn:serviceTask id="b1" flowable:expression="${'$'}{betaBean.run()}"/>
                     </bpmn:process>
                   </bpmn:definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun graph(part: String): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)[part] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun usedBy(nodeId: String): List<String> =
        ((graph("nodes").single { it["id"] == nodeId }["data"] as Map<String, Any?>)["usedBy"] as List<String>)

    @Test
    fun anExpressionBelongsToTheProcessWhoseElementCarriesIt() {
        assertEquals(listOf("process:alpha"), usedBy("expression:\${alphaBean.run()}"))
        assertEquals(listOf("process:beta"), usedBy("expression:\${betaBean.run()}"))
        assertEquals(listOf("process:alpha"), usedBy("expression:\${onlyAlpha == true}"))
    }

    @Test
    fun whatSitsOutsideEveryProcessBelongsToAll() {
        assertEquals(listOf("process:alpha", "process:beta"), usedBy("expression:\${sharedTopic}").sorted())
    }

    @Test
    fun aBeanCallIsAnEdgeFromItsOwnProcessOnly() {
        val calls = graph("edges").filter { (it["rel"] as String).startsWith("calls ") }
        assertTrue(calls.any { it["s"] == "process:alpha" && it["t"] == "external:alphaBean" })
        assertTrue(calls.any { it["s"] == "process:beta" && it["t"] == "external:betaBean" })
        assertFalse("beta must not be credited with alpha's bean call",
            calls.any { it["s"] == "process:beta" && it["t"] == "external:alphaBean" })
    }
}
