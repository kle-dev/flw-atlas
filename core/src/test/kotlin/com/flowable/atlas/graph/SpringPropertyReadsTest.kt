package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A model that reads `environment.getProperty('k')` names a Spring property; the property's page says
 * where the project sets it. DEMO-* names: the repo is public.
 */
class SpringPropertyReadsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-spring-props-test").toFile()
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("models/DEMO-P001.bpmn", """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <process id="DEMO-P001">
                    <serviceTask id="call" flowable:type="http">
                      <extensionElements>
                        <flowable:field name="requestUrl">
                          <flowable:expression><![CDATA[${'$'}{environment.getProperty('crm.base-url', '')}/contacts]]></flowable:expression>
                        </flowable:field>
                        <flowable:field name="token">
                          <flowable:expression>${'$'}{propertyConfigurationService.getProperty(&quot;crm.token&quot;, &quot;&quot;)}</flowable:expression>
                        </flowable:field>
                      </extensionElements>
                    </serviceTask>
                  </process>
                </definitions>
            """)
            put("src/main/resources/application.properties", "server.port=8080\ncrm.baseUrl=https://crm\n")
            put("src/main/resources/application-k8s.yml", "crm:\n  base-url: https://crm.k8s\n")
            // a test profile is not the project's configuration
            put("src/test/resources/application-test.properties", "crm.token=test\n")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val graph get() = result["graph"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun node(id: String): Map<String, Any?> =
        (graph["nodes"] as List<Map<String, Any?>>).single { it["id"] == id }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theModelReadsBothProperties() {
        val edges = (graph["edges"] as List<Map<String, Any?>>).filter { it["rel"] == "reads-property" }
            .map { it["s"] to it["t"] }.toSet()
        assertEquals(
            setOf("process:DEMO-P001" to "property:crm.base-url", "process:DEMO-P001" to "property:crm.token"),
            edges,
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aPropertyKnowsWhereItIsSetInEveryProfileSpellingRelaxed() {
        val definedIn = (node("property:crm.base-url")["data"] as Map<String, Any?>)["definedIn"] as List<String>
        assertEquals(
            listOf("src/main/resources/application-k8s.yml:2", "src/main/resources/application.properties:2"),
            definedIn.sorted(),
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aPropertySetNowhereIsNotAFinding() {
        val definedIn = (node("property:crm.token")["data"] as Map<String, Any?>)["definedIn"] as List<String>
        assertTrue(definedIn.isEmpty())
        val findings = (result["findings"] as List<Map<String, Any?>>).filter { (it["node"] as? String)?.startsWith("property:") == true }
        assertTrue(findings.toString(), findings.isEmpty())
    }
}
