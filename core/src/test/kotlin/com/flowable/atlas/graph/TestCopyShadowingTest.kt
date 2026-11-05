package com.flowable.atlas.graph

import com.flowable.atlas.model.ModelPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One key, two copies: an empty fixture under `src/test/resources` and the process the app's `.bar`
 * deploys. Discovery lists loose files before archives and the first copy of a key is the one the graph
 * shows, so the fixture used to stand in for the real process — every reader was told the app's main
 * process had no user task. The deployed copy is the model; the test copy is recorded beside it.
 */
class TestCopyShadowingTest {

    private fun bpmn(id: String, userTask: Boolean) =
        """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="$id" name="Claim">""" +
            (if (userTask) """<userTask id="assess" name="Assess claim"/>""" else """<serviceTask id="noop" name="No-op"/>""") +
            "</process></definitions>"

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theDeployedCopyWinsOverATestCopyWithTheSameKey() {
        val dir = Files.createTempDirectory("atlas-test-copy").toFile()
        try {
            File(dir, "src/test/resources/processes").mkdirs()
            File(dir, "src/test/resources/processes/claimProcess.bpmn20.xml").writeText(bpmn("claimProcess", userTask = false))
            File(dir, "src/main/resources/apps").mkdirs()
            ZipOutputStream(File(dir, "src/main/resources/apps/DEMO-app.bar").outputStream()).use { z ->
                z.putNextEntry(ZipEntry("claimProcess.bpmn"))
                z.write(bpmn("claimProcess", userTask = true).toByteArray())
                z.closeEntry()
            }
            val result = Atlas.extract(dir)
            val processes = result["processes"] as List<Map<String, Any?>>
            val p = processes.single { it["key"] == "claimProcess" }
            assertEquals("src/main/resources/apps/DEMO-app.bar!claimProcess.bpmn", p["file"])
            assertEquals(listOf("assess"), (p["userTasks"] as List<Map<String, Any?>>).map { it["id"] })

            val nodes = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
            val node = nodes.single { it["id"] == "process:claimProcess" }
            assertEquals("src/main/resources/apps/DEMO-app.bar!claimProcess.bpmn", node["file"])
            val index = result["modelIndex"] as Map<String, Any?>
            assertEquals("src/main/resources/apps/DEMO-app.bar!claimProcess.bpmn", index["process:claimProcess"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aModelFileUnderATestSourceSetIsTold() {
        assertTrue(ModelPaths.isTestSourceModel("app/src/test/resources/processes/claim.bpmn20.xml"))
        assertTrue("an archive's own path decides", ModelPaths.isTestSourceModel("src/integrationTest/resources/DEMO.bar!claim.bpmn"))
        assertTrue("Windows separators", ModelPaths.isTestSourceModel("app\\src\\test\\resources\\claim.cmmn"))
        assertFalse(ModelPaths.isTestSourceModel("src/main/resources/DEMO.bar!src/test/claim.bpmn"))
        assertFalse(ModelPaths.isTestSourceModel("models/claim.cmmn"))
        assertFalse(ModelPaths.isTestSourceModel(null))
    }
}
