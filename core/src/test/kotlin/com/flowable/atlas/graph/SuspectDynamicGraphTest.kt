package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Graph-level behavior of the audit fixes: an incompatible cross-type key fallback yields a
 * SUSPECT edge (not a clean one), a compatible one (process↔case) stays clean but tagged,
 * expression-valued references surface as DYNAMIC edges to a placeholder node, and
 * signal-throw/catch plus external-worker topics meet in shared named nodes.
 */
class SuspectDynamicGraphTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-suspect-test").toFile()
            // a form whose key collides with a callActivity's target process key → incompatible fallback
            File(dir, "customerForm.form").writeText(
                """{"metadata":{"key":"customerForm","name":"Customer","modelType":"form"},"rows":[]}""")
            // a case whose key is called from a callActivity → compatible fallback (process↔case)
            File(dir, "reviewCase.cmmn").writeText(
                """<definitions><case id="reviewCase" name="Review"><casePlanModel id="plan"/></case></definitions>""")
            File(dir, "caller.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <signal id="sig1" name="cancelAll"/>
                     <process id="callerProcess">
                       <callActivity id="a" calledElement="customerForm"/>
                       <callActivity id="b" calledElement="reviewCase"/>
                       <callActivity id="c" calledElement="${'$'}{dynamicKey}"/>
                       <intermediateThrowEvent id="t"><signalEventDefinition signalRef="sig1"/></intermediateThrowEvent>
                       <serviceTask id="w" flowable:type="external-worker" flowable:topic="orders"/>
                     </process>
                   </definitions>""")
            File(dir, "catcher.bpmn").writeText(
                """<definitions>
                     <signal id="sigX" name="cancelAll"/>
                     <process id="catcherProcess">
                       <startEvent id="s"><signalEventDefinition signalRef="sigX"/></startEvent>
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
    private fun edges(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    private fun edge(s: String, t: String, rel: String): Map<String, Any?>? =
        edges().firstOrNull { it["s"] == s && it["t"] == t && it["rel"] == rel }

    @Test
    fun incompatibleCrossTypeFallbackIsSuspect() {
        val e = edge("process:callerProcess", "form:customerForm", "callActivity")
        assertTrue("expected the cross-type callActivity edge to exist", e != null)
        assertEquals("a process→form callActivity edge must be flagged suspect", true, e!!["suspect"])
    }

    @Test
    fun compatibleCrossTypeFallbackStaysClean() {
        val e = edge("process:callerProcess", "case:reviewCase", "callActivity")
        assertTrue("expected the process→case callActivity edge to exist", e != null)
        assertNull("process→case is a compatible fallback — no suspect flag", e!!["suspect"])
    }

    @Test
    fun dynamicReferenceBecomesDashedPlaceholderEdge() {
        val e = edges().firstOrNull {
            it["s"] == "process:callerProcess" && it["rel"] == "callActivity" && it["dynamic"] == true
        }
        assertTrue("expected a dynamic callActivity edge for the \${dynamicKey} target", e != null)
        assertTrue("dynamic edge targets the expression placeholder",
            (e!!["t"] as String).startsWith("external:"))
    }

    @Test
    fun signalThrowAndCatchMeetInOneNode() {
        val sig = nodes().firstOrNull { it["id"] == "signal:cancelAll" }
        assertTrue("expected a shared signal node", sig != null)
        assertTrue(edge("process:callerProcess", "signal:cancelAll", "throws-signal") != null)
        assertTrue(edge("process:catcherProcess", "signal:cancelAll", "catches-signal") != null)
    }

    @Test
    fun externalWorkerTopicBecomesANode() {
        assertTrue(nodes().any { it["id"] == "topic:orders" })
        assertTrue(edge("process:callerProcess", "topic:orders", "external-topic") != null)
    }

    @Test
    fun statsCountSuspectAndDynamicEdges() {
        @Suppress("UNCHECKED_CAST")
        val stats = result["stats"] as Map<String, Any?>
        assertTrue((stats["suspectEdges"] as Number).toInt() >= 1)
        assertTrue((stats["dynamicEdges"] as Number).toInt() >= 1)
    }
}
