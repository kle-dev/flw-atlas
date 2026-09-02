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
}
