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
 * A user definition links to the forms and groups it names, a tenant setup to the user definitions and
 * groups it sets up — and a platform form the project does not contain is no finding. DEMO-* names.
 */
class IdentitySetupTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-identity-test").toFile()
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("models/DEMO-F001.form", """{"metadata":{"key":"DEMO-F001","name":"Client init","modelType":"form"},"rows":[]}""")
            put("src/main/resources/com/flowable/users/custom/demo.user.json", """[
                {"key":"DEMO-client","name":"Client","initialUserType":"client",
                 "forms":{"init":"DEMO-F001","view":"F02_userViewFormDefault"},
                 "memberGroups":["DEMO-clients"],"lookupGroups":["DEMO-advisors"]}]""")
            put("src/main/resources/com/flowable/tenant-setup/custom/default.json", """{
                "name":"Demo tenant","groups":[{"key":"DEMO-clients","name":"Clients"}],
                "users":[{"login":"alice","password":"secret","userDefinitionKey":"DEMO-client"},
                         {"login":"bob","userDefinitionKey":"DEMO-client"},
                         {"login":"root","userDefinitionKey":"user-admin"}]}""")
            put("models/DEMO-md.data", """{"key":"DEMO-md","name":"Categories","dataObjectType":"masterData"}""")
            put("src/main/resources/com/flowable/master-data/custom/categories.data.json",
                """{"dataObjectDefinitionKey":"DEMO-md","masterData":[{"key":"a"},{"key":"b"}]}""")
            put("src/main/resources/com/flowable/master-data/custom/platform.data.json",
                """{"dataObjectDefinitionKey":"platform-countries","masterData":[{"key":"CH"}]}""")
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
    private val edges get() = (graph["edges"] as List<Map<String, Any?>>).map { Triple(it["s"], it["rel"], it["t"]) }.toSet()

    @Test
    fun aUserDefinitionLinksItsProjectFormAndGroups() {
        assertTrue(Triple("userDefinition:DEMO-client", "user-init-form", "form:DEMO-F001") in edges)
        assertTrue(Triple("userDefinition:DEMO-client", "member-of", "group:DEMO-clients") in edges)
        assertTrue(Triple("userDefinition:DEMO-client", "looks-up", "group:DEMO-advisors") in edges)
        assertFalse("a platform form is no node and no edge", edges.any { it.third == "form:F02_userViewFormDefault" })
    }

    @Test
    fun aTenantSetupLinksTheDefinitionsAndGroupsItSetsUp() {
        assertTrue(Triple("tenantSetup:default", "sets-up-users-of", "userDefinition:DEMO-client") in edges)
        assertTrue(Triple("tenantSetup:default", "defines-group", "group:DEMO-clients") in edges)
        assertFalse("the platform's own user definition is not invented", edges.any { it.third == "userDefinition:user-admin" })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun nothingPersonalLeavesTheSetupAndNothingIsAFinding() {
        val setup = (graph["nodes"] as List<Map<String, Any?>>).single { it["id"] == "tenantSetup:default" }
        val data = setup["data"].toString()
        assertFalse(data, data.contains("alice") || data.contains("secret"))
        assertEquals(mapOf("DEMO-client" to 2, "user-admin" to 1), (setup["data"] as Map<String, Any?>)["usersPerDefinition"])
        val findings = (result["findings"] as List<Map<String, Any?>>).filter { f ->
            listOf("userDefinition:", "tenantSetup:").any { (f["node"] as? String)?.startsWith(it) == true }
        }
        assertTrue(findings.toString(), findings.isEmpty())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aMasterDataDefinitionKnowsTheFileThatLoadsItsRows() {
        val md = (graph["nodes"] as List<Map<String, Any?>>).single { it["id"] == "masterData:DEMO-md" }
        assertEquals(
            listOf(mapOf("file" to "src/main/resources/com/flowable/master-data/custom/categories.data.json", "rows" to 2)),
            (md["data"] as Map<String, Any?>)["loadedFrom"],
        )
    }
}
