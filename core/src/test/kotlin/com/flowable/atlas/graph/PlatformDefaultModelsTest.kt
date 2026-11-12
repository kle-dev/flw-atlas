package com.flowable.atlas.graph

import com.flowable.atlas.parsing.PlatformDefaultModels
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The models the platform's default-models modules ship — default forms, permission decisions,
 * templates, actions — are referenced by projects that never define them: a copied
 * `deactivate-user.action` keeps the platform form `F07-deactivateUser`. Those resolve at runtime, so
 * they are platform-provided, not missing; a misspelling of one, and the same key under another kind,
 * still are.
 */
class PlatformDefaultModelsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-platform-defaults-test").toFile()
            File(dir, "deactivate.action").writeText(
                """{"key":"DEMO-deactivate","name":"Deactivate","formKey":"F07-deactivateUser"}""")
            File(dir, "typo.action").writeText(
                """{"key":"DEMO-deactivate-typo","name":"Deactivate (typo)","formKey":"F07-deactivateUsr"}""")
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="DEMO-p">
                       <startEvent id="s" flowable:formKey="F01_userInitFormDefault"/>
                       <callActivity id="call" calledElement="F07-deactivateUser"/>
                     </process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun data(id: String) = nodes().single { it["id"] == id }["data"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun missing(): List<String> =
        (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "missingRefs" }.map { it["node"].toString() }.sorted()

    @Test
    fun aPlatformDefaultFormIsProvidedNotMissing() {
        val form = nodes().filter { it["key"] == "F07-deactivateUser" && it["type"] == "external" }
            .map { it["data"] as Map<*, *> }.single { it["kind"] == "form" }
        assertEquals(true, form["platform"])
        assertTrue(form["missingModel"] == null)
        assertEquals(true, data("external:F01_userInitFormDefault")["platform"])
    }

    @Test
    fun aTypoAndTheSameKeyOfAnotherKindAreStillMissing() {
        // the platform ships a *form* of that key, not a process: the call activity names nothing
        val call = nodes().filter { it["key"] == "F07-deactivateUser" && it["type"] == "external" }
            .single { (it["data"] as Map<*, *>)["kind"] == "process" }
        assertEquals(true, (call["data"] as Map<*, *>)["missingModel"])
        assertEquals(listOf(call["id"].toString(), "external:F07-deactivateUsr").sorted(), missing())
    }

    @Test
    fun theLookupIsByKind() {
        // a ref's kind as the resolver normalises it, whatever the model spelled
        fun provides(kind: String, key: String) = PlatformDefaultModels.provides(ReferenceResolver.modelKind(kind), key)
        assertTrue(provides("form", "F07-deactivateUser"))
        assertTrue(provides("model:form", "F07-deactivateUser"))
        assertTrue(provides("page", "F07-deactivateUser"))
        assertTrue(provides("dmn", "R01_defaultUserPermissions"))
        assertTrue(provides("security", "basic-security-policy"))
        assertTrue(provides("event", "_flowableMailEvent"))
        assertFalse(provides("process", "F07-deactivateUser"))
        assertFalse(provides("form", "DEMO-form"))
    }
}
