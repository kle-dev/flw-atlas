package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A model's `${bean}` resolves to a `@Bean` factory method (at the method's line) and to a Kotlin
 * class; a bean name two classes both claim resolves, but suspect.
 */
class KotlinAndFactoryBeanTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-kotlin-bean-test").toFile()
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("src/main/kotlin/com/acme/Delegates.kt", """
                package com.acme
                @Configuration
                class Delegates {
                    @Bean
                    fun approveOrder(): JavaDelegate = JavaDelegate { }
                }
            """)
            put("src/main/kotlin/com/acme/ScoreService.kt", """
                package com.acme
                @Component
                class ScoreService(private val repo: ScoreRepo) : JavaDelegate {
                    override fun execute(execution: DelegateExecution) { execution.setVariable("score", 1) }
                    fun compute(): Int = 1
                }
            """)
            put("src/main/java/com/acme/DupOne.java", """
                package com.acme;
                @Component("dup") public class DupOne {}
            """)
            put("src/main/java/com/acme/DupTwo.java", """
                package com.acme;
                @Component("dup") public class DupTwo {}
            """)
            put("p.bpmn", """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <process id="p">
                    <serviceTask id="a" flowable:delegateExpression="${'$'}{approveOrder}"/>
                    <serviceTask id="b" flowable:delegateExpression="${'$'}{scoreService}"/>
                    <serviceTask id="c" flowable:expression="${'$'}{scoreService.compute()}"/>
                    <serviceTask id="d" flowable:delegateExpression="${'$'}{dup}"/>
                    <serviceTask id="e" flowable:class="com.acme.DupOne"/>
                    <serviceTask id="f" flowable:class="org.flowable.lib.DupTwo"/>
                  </process>
                </definitions>
            """)
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolved(value: String): Map<String, Any?>? =
        (result["resolvedRefs"] as List<Map<String, Any?>>).firstOrNull { it["kind"] == "bean" && it["value"] == value }

    @Test
    fun aFactoryBeanResolvesToItsMethodLine() {
        val r = resolved("approveOrder")
        assertNotNull("approveOrder was not resolved", r)
        assertEquals("com.acme.Delegates", r!!["targetFqn"])
        // the @Bean annotation sits on line 4 of the file, the class header on line 3
        assertTrue("target should point at the factory method: ${r["target"]}",
            r["target"].toString().startsWith("src/main/kotlin/com/acme/Delegates.kt:4 "))
    }

    @Test
    fun aKotlinClassIsABeanAndADelegate() {
        assertEquals("com.acme.ScoreService", resolved("scoreService")?.get("targetFqn"))
        @Suppress("UNCHECKED_CAST")
        val nodes = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        val svc = nodes.single { it["id"] == "java:com.acme.ScoreService" }
        @Suppress("UNCHECKED_CAST")
        assertTrue("delegate" in ((svc["data"] as Map<String, Any?>)["roles"] as List<String>))
        assertTrue(nodes.any { it["id"] == "method:com.acme.ScoreService#compute" })
        assertEquals(4, (result["stats"] as Map<*, *>)["java"])
    }

    @Test
    fun aBeanNameTwoClassesClaimResolvesButSuspect() {
        val r = resolved("dup")
        assertNotNull(r)
        assertEquals(true, r!!["suspect"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolvedClass(value: String): Map<String, Any?>? =
        (result["resolvedRefs"] as List<Map<String, Any?>>).firstOrNull { it["kind"] == "class" && it["value"] == value }

    /**
     * A file declaring more than one type: a Kotlin DTO above the `@Component` the file is about, a second
     * class named by `flowable:class`, a nested `@Component`. Each reference lands on the type it names —
     * on the file's node, at that type's declaration — instead of the file's first type or nowhere.
     */
    @Suppress("UNCHECKED_CAST")
    private fun multiTypeResult(): Map<String, Any?> {
        val d = Files.createTempDirectory("atlas-multi-type-test").toFile()
        try {
            fun put(rel: String, text: String) = File(d, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("src/main/kotlin/com/example/OrderDelegate.kt", """
                package com.example

                data class OrderDto(val id: String, val total: Int)

                @Component("orderDelegate")
                class OrderDelegate : JavaDelegate {
                    override fun execute(execution: DelegateExecution) {}
                }
            """)
            put("src/main/kotlin/com/example/Delegates.kt", """
                package com.example

                @Component("ktBean")
                class KtBean : JavaDelegate {
                    override fun execute(execution: DelegateExecution) {}
                }

                class KtDelegate : JavaDelegate {
                    override fun execute(execution: DelegateExecution) {}
                }
            """)
            put("src/main/java/com/example/WorkflowConfig.java", """
                package com.example;

                @Configuration
                public class WorkflowConfig {

                    @Component("approveDelegate")
                    public static class ApproveDelegate implements JavaDelegate {
                        public void execute(DelegateExecution execution) {}
                    }
                }
            """)
            put("p.bpmn", """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <process id="DEMO-multi">
                    <serviceTask id="a" flowable:delegateExpression="${'$'}{orderDelegate}"/>
                    <serviceTask id="b" flowable:class="com.example.KtDelegate"/>
                    <serviceTask id="c" flowable:delegateExpression="${'$'}{approveDelegate}"/>
                    <serviceTask id="d" flowable:class="com.example.WorkflowConfig${'$'}ApproveDelegate"/>
                    <serviceTask id="e" flowable:class="com.example.WorkflowConfig.ApproveDelegate"/>
                  </process>
                </definitions>
            """)
            return Atlas.extract(d)
        } finally {
            d.deleteRecursively()
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aReferenceLandsOnTheTypeItNamesInAMultiTypeFile() {
        val r = multiTypeResult()
        val refs = r["resolvedRefs"] as List<Map<String, Any?>>
        fun ref(kind: String, value: String) = refs.single { it["kind"] == kind && it["value"] == value }

        // the file's own type, not the DTO declared above it
        assertEquals("com.example.OrderDelegate", ref("bean", "orderDelegate")["targetFqn"])
        val nodes = (r["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        assertTrue("no node for the DTO", nodes.none { it["id"] == "java:com.example.OrderDto" })

        // the second class of a Kotlin file resolves, on the file's node, at its own line
        val kt = ref("class", "com.example.KtDelegate")
        assertEquals("src/main/kotlin/com/example/Delegates.kt:8 (com.example.KtDelegate)", kt["target"])
        assertEquals("com.example.KtBean", kt["targetFqn"])
        assertEquals(null, kt["suspect"])

        // a nested component: its declaration, by its name — and its binary name as a flowable:class
        assertEquals("src/main/java/com/example/WorkflowConfig.java:7 (com.example.WorkflowConfig.ApproveDelegate)",
            ref("bean", "approveDelegate")["target"])
        val nested = ref("class", "com.example.WorkflowConfig\$ApproveDelegate")
        assertEquals("src/main/java/com/example/WorkflowConfig.java:7 (com.example.WorkflowConfig.ApproveDelegate)", nested["target"])
        assertEquals("com.example.WorkflowConfig", nested["targetFqn"])
        assertEquals(null, nested["suspect"])
        // the source spelling finds the same type, but Class.forName would not load it by that name
        val dotted = ref("class", "com.example.WorkflowConfig.ApproveDelegate")
        assertEquals(nested["target"], dotted["target"])
        assertEquals(true, dotted["suspect"])
        assertTrue("nothing left unresolved", (r["unresolvedRefs"] as List<*>).isEmpty())
    }

    /**
     * A Kotlin string template that happens to name a process variable is the compiler's, not a read of
     * it: the variable a script task writes and nothing reads stays reported, and the Kotlin parameter in
     * the other template is no variable at all.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aKotlinStringTemplateReadsNoVariable() {
        val d = Files.createTempDirectory("atlas-kotlin-template-test").toFile()
        try {
            fun put(rel: String, text: String) = File(d, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("src/main/kotlin/com/example/Report.kt", """
                package com.example
                class Report {
                    fun line(orderTotal: Int) = "Order total: ${'$'}{orderTotal}"
                    fun notify(customer: String) = println("Notifying ${'$'}{customer}")
                }
            """)
            put("models/DEMO-P002.bpmn", """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <process id="DEMO-P002">
                    <scriptTask id="s" scriptFormat="groovy"><script>execution.setVariable("orderTotal", 10)</script></scriptTask>
                  </process>
                </definitions>
            """)
            val r = Atlas.extract(d)
            val unused = (r["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedVars" }.map { it["node"] }
            assertEquals(listOf("variable:orderTotal"), unused)
            val nodes = (r["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
            assertTrue("a Kotlin parameter is no variable", nodes.none { it["id"] == "variable:customer" })
        } finally {
            d.deleteRecursively()
        }
    }

    @Test
    fun aQualifiedClassFromAnotherPackageResolvesOnlyAsSuspect() {
        assertEquals(null, resolvedClass("com.acme.DupOne")!!["suspect"])
        val lib = resolvedClass("org.flowable.lib.DupTwo")
        assertEquals("com.acme.DupTwo", lib!!["targetFqn"])
        assertEquals(true, lib["suspect"])
    }
}
