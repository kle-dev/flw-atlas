package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiscoveryTest {

    @Test
    fun testSourceSetsAreRecognisedByTheirSrcSegment() {
        assertTrue(ModelPaths.isTestSource("src/test/java/com/acme/FooTest.java"))
        assertTrue(ModelPaths.isTestSource("work/src/integrationTest/java/Foo.java"))
        assertTrue(ModelPaths.isTestSource("src/testFixtures/java/Foo.java"))
        assertFalse(ModelPaths.isTestSource("src/main/java/com/acme/Foo.java"))
        // `test` as a business word somewhere else is not a test source set
        assertFalse(ModelPaths.isTestSource("src/main/java/com/acme/test/ContestService.java"))
        assertFalse(ModelPaths.isTestSource("modules/testing-tools/src/main/java/Foo.java"))
    }

    @Test
    fun javaUnderATestSourceSetIsNotScannedButItsModelsAre() {
        val dir = Files.createTempDirectory("atlas-discovery-test").toFile()
        try {
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text) }
            put("src/main/java/com/acme/Prod.java", "package com.acme; public class Prod {}")
            put("src/test/java/com/acme/ProdTest.java", "package com.acme; public class ProdTest {}")
            put("src/integrationTest/java/com/acme/It.java", "package com.acme; public class It {}")
            put("src/test/resources/fixture.bpmn", "<definitions><process id=\"fixture\"/></definitions>")

            val d = Discovery.discover(dir)
            assertEquals(listOf("Prod.java"), d.javas.map { it.name })
            assertEquals(listOf("fixture.bpmn"), d.models.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** One model file is a project of one — only an archive used to count, so --help's promise found nothing. */
    @Test
    fun aSingleModelFileIsDiscoveredAsAModel() {
        val bpmn = File("src/test/resources/miniproject/processes/order.bpmn")
        assertEquals(listOf("order.bpmn"), Discovery.discover(bpmn).models.map { it.name })
        val result = com.flowable.atlas.graph.Atlas.extract(bpmn)
        @Suppress("UNCHECKED_CAST")
        val ids = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"] }
        assertTrue(ids.toString(), "process:orderProcess" in ids)
    }

    /**
     * A folder that cannot be listed is said, as a warning: models may have been under it, and a dropped
     * folder used to look exactly like an empty one. Not an error — a root-owned database volume inside a
     * checkout must not fail every `--fail-on error` pipeline.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun anUnreadableFolderIsAWarningNotSilence() {
        val dir = Files.createTempDirectory("atlas-discovery-unreadable").toFile()
        val sub = File(dir, "sub")
        try {
            File(dir, "order.bpmn").writeText("<definitions><process id=\"DEMO-P001\"/></definitions>")
            sub.mkdirs()
            File(sub, "review.cmmn").writeText("<definitions><case id=\"DEMO-C001\"/></definitions>")
            sub.setReadable(false)
            org.junit.Assume.assumeFalse("this user can read any folder (root)", sub.list() != null)

            assertEquals(listOf(sub), Discovery.discover(dir).unreadable)
            val result = com.flowable.atlas.graph.Atlas.extract(dir)
            val diag = (result["diagnostics"] as List<Map<String, Any?>>).single { it["path"] == "sub" }
            assertEquals("skip", diag["kind"])
            val finding = (result["findings"] as List<Map<String, Any?>>).single { it["check"] == "parseIssues" && it["file"] == "sub" }
            assertEquals("warning", finding["severity"])
        } finally {
            sub.setReadable(true)
            dir.deleteRecursively()
        }
    }
}
