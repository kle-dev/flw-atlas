package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A constant passed where a Flowable API takes a model key resolves to the model its value names — the
 * way a project written against a generated constants class references its models.
 */
class JavaConstantRefsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-java-constants-test").toFile()
            File(dir, "order.bpmn").writeText("""<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P001" name="Order"/></definitions>""")
            File(dir, "review.cmmn").writeText("""<definitions xmlns:flowable="http://flowable.org/cmmn"><case id="DEMO-C001" name="Review"/></definitions>""")
            val java = File(dir, "src/main/java/com/example").apply { mkdirs() }
            File(java, "ModelConstants.java").writeText(
                """package com.example;
                   public final class ModelConstants {
                       public static final String ORDER_PROCESS = "DEMO-P001";
                       public static final String REVIEW_CASE = "DEMO-C001";
                       public static final String SHARED = "DEMO-P001";
                   }""")
            File(java, "OtherConstants.java").writeText(
                """package com.example;
                   public final class OtherConstants { public static final String SHARED = "somethingElse"; }""")
            File(java, "Starter.java").writeText(
                """package com.example;
                   import org.springframework.stereotype.Service;
                   @Service
                   public class Starter {
                       public void go() {
                           runtimeService.startProcessInstanceByKey(ModelConstants.ORDER_PROCESS);
                           cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey(ModelConstants.REVIEW_CASE).start();
                           runtimeService.startProcessInstanceByKey(SHARED);
                       }
                   }""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun edges() = (result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>

    @Test
    fun aConstantAtAKeyPositionIsAConfidentReference() {
        val fromStarter = edges().filter { it["s"] == "java:com.example.Starter" && it["rel"] == "references" }
        assertEquals(setOf("process:DEMO-P001", "case:DEMO-C001"), fromStarter.map { it["t"] }.toSet())
        assertTrue("resolved through a constant, as confident as the literal", fromStarter.none { it["suspect"] == true })
    }

    @Test
    fun theConstantsClassItselfIsNotACaller() {
        // its literals equal model keys, but nothing there calls the engine — a suspect edge at most
        val fromConstants = edges().filter { it["s"] == "java:com.example.ModelConstants" && it["rel"] == "references" }
        assertTrue(fromConstants.all { it["suspect"] == true })
    }
}
