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
 * `${x.method()}` is a bean call only when something says `x` is a bean — Java, a bare delegate
 * expression, the platform, or a bean-shaped name. Otherwise it is a read of the variable `x`: the
 * variable gets its node, nothing is reported as an unresolved bean, and a service task reading a
 * variable is not a call out of the engine.
 */
class VariableCallGraphTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-variable-call-test").toFile()
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                     <process id="p">
                       <serviceTask id="a" flowable:expression="${'$'}{requesterData.getName()}"/>
                       <serviceTask id="b" flowable:expression="${'$'}{orderService.place(order)}"/>
                       <serviceTask id="c" flowable:delegateExpression="${'$'}{notifier}"/>
                       <serviceTask id="d" flowable:expression="${'$'}{true}"/>
                       <sequenceFlow id="f" sourceRef="a" targetRef="b">
                         <conditionExpression xsi:type="tFormalExpression">${'$'}{issues.size() > 0 &amp;&amp; notifier.enabled() &amp;&amp; userService.isOpen(order)}</conditionExpression>
                       </sequenceFlow>
                     </process>
                   </definitions>""")
            val java = File(dir, "src/main/java/com/example").apply { mkdirs() }
            File(java, "OrderService.java").writeText(
                """package com.example;
                   import org.springframework.stereotype.Component;
                   @Component
                   public class OrderService { public void place(Object o) {} }""")
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

    private fun ids() = graph("nodes").map { it["id"] as String }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun unresolvedBeans() = (result["unresolvedRefs"] as List<Map<String, Any?>>)
        .filter { it["kind"] == "bean" }.map { it["value"] as String }.toSet()

    @Test
    fun aMethodCallOnAVariableReadsTheVariable() {
        val ids = ids()
        assertTrue("requesterData is the variable it always was", "variable:requesterData" in ids)
        assertTrue("issues is read by the condition", "variable:issues" in ids)
        assertFalse("requesterData is not a bean of the project's", "external:requesterData" in ids)
        assertFalse("issues is not a bean of the project's", "external:issues" in ids)
        assertTrue(unresolvedBeans().toString(), unresolvedBeans().none { it == "requesterData" || it == "issues" })
        val reads = graph("nodes").single { it["id"] == "variable:issues" }["data"] as Map<*, *>
        assertTrue("the condition is a read site", (reads["reads"] as List<*>).isNotEmpty())
    }

    @Test
    fun aBeanStaysABeanWhenSomethingSaysSo() {
        val ids = ids()
        // Java declares it: the expression resolves to the class
        assertTrue(graph("edges").any { it["s"] == "process:p" && it["t"] == "java:com.example.OrderService" && it["rel"] == "serviceTask-expression" })
        assertFalse("orderService is a bean, not a variable", "variable:orderService" in ids)
        // a delegate expression names it bare, so its method call in the condition is a bean call too
        assertTrue("external:notifier" in ids)
        assertFalse("variable:notifier" in ids)
        // nothing declares it, but it is named the way beans are named
        assertTrue("userService" in unresolvedBeans())
        assertFalse("variable:userService" in ids)
    }

    @Test
    fun aLiteralIsNotABean() {
        assertFalse("external:true" in ids())
        assertFalse("true" in unresolvedBeans())
    }

    @Test
    fun aServiceTaskReadingAVariableIsNotACallOut() {
        @Suppress("UNCHECKED_CAST")
        val unguarded = (result["findings"] as List<Map<String, Any?>>)
            .filter { it["check"] == "unguardedTasks" }.map { it["element"] }
        // `b` calls a bean Java declares, `c` a bean named bare by a delegate; `a` reads a variable
        assertEquals(listOf("b", "c"), unguarded)
    }
}
