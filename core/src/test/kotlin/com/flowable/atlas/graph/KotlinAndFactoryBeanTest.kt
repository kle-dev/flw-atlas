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
}
