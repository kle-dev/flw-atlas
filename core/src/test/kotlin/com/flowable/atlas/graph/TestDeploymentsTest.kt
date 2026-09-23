package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A model says which tests deploy it; the test class itself stays out of the graph. DEMO-* names. */
class TestDeploymentsTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aModelKnowsTheTestsThatDeployIt() {
        val dir = Files.createTempDirectory("atlas-test-deploy").toFile()
        try {
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("app/src/main/resources/bpmn/DEMO-P001.bpmn20.xml", """<definitions><process id="DEMO-P001"/></definitions>""")
            put("app/src/test/java/com/acme/OrderIT.java", """
                package com.acme;
                class OrderIT {
                    // @Deployment(resources = "bpmn/commented-out.bpmn")
                    @Test
                    @Deployment(resources = { "bpmn/DEMO-P001.bpmn20.xml" }, tenantId = "acme")
                    void runs() { runtimeService.startProcessInstanceByKey("DEMO-P001"); }
                    @Deployment(resources = "bpmn/missing-fixture.bpmn")
                    void other() {}
                }
            """)
            val result = Atlas.extract(dir)
            val nodes = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
            val p = nodes.single { it["id"] == "process:DEMO-P001" }
            assertEquals(listOf("app/src/test/java/com/acme/OrderIT.java:5"), (p["data"] as Map<String, Any?>)["deployedByTests"])
            assertFalse("a test class is not project code", nodes.any { it["id"] == "java:com.acme.OrderIT" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
