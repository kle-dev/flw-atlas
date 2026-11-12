package com.flowable.atlas.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled schema files and the table that maps namespaces onto them agree with each other.
 *
 * Each of these has a silent failure mode. A path that does not resolve leaves the namespace unregistered
 * and the editor exactly as it was before — no error, just no completion. A file copied into the wrong
 * format's directory resolves its `<xsd:include>` against the wrong `DC.xsd`, of which there are three.
 * And a namespace paired with a file that declares a different `targetNamespace` registers a schema that
 * describes something else entirely.
 */
class SchemaResourcesTest {

    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val resources: File by lazy { File(repoRoot, "idea-plugin/src/main/resources") }

    @Test
    fun everyRegisteredPathExists() {
        val missing = FlowableSchemas.SCHEMAS.map { it.second }.distinct().filterNot { File(resources, it).isFile }
        assertEquals("a path that does not resolve registers nothing, silently", emptyList<String>(), missing)
    }

    @Test
    fun everyFileDeclaresTheNamespaceItIsRegisteredUnder() {
        // The schemaLocation aliases point at a file whose targetNamespace is the *namespace*, so they are
        // matched on the file rather than on the URL they are registered under.
        val wrong = FlowableSchemas.SCHEMAS.mapNotNull { (url, path) ->
            if (url.endsWith(".xsd")) return@mapNotNull null
            val declared = targetNamespaceOf(File(resources, path))
            if (declared == url) null else "$path declares $declared, registered under $url"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun everyIncludeResolvesInsideItsOwnDirectory() {
        val unresolved = ArrayList<String>()
        for (path in FlowableSchemas.SCHEMAS.map { it.second }.distinct()) {
            val file = File(resources, path)
            val dir = file.parentFile
            for (ref in Regex("""schemaLocation="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }) {
                if (ref.startsWith("http")) continue
                if (!File(dir, ref).isFile) unresolved += "$path names $ref, which is not beside it"
            }
        }
        assertEquals(emptyList<String>(), unresolved)
    }

    @Test
    fun onlyTheFlowableExtensionSchemaIsModified() {
        // The OMG files are vendored byte for byte, and that is their provenance: anyone can diff them
        // against the spec. Exactly one file carries Atlas changes, and it says so in place.
        val modified = FlowableSchemas.SCHEMAS.map { it.second }.distinct()
            .filter { File(resources, it).readText().contains("MODIFIED BY FLOWABLE ATLAS") }
        assertEquals(listOf("schemas/bpmn/flowable-bpmn-extensions.xsd"), modified)
        val text = File(resources, modified.single()).readText()
        assertTrue(
            "a modified Apache-2.0 file states that it was changed, and where the reasoning lives",
            text.contains("THIS FILE HAS BEEN MODIFIED") && text.contains("idea-plugin/README.md"),
        )
    }

    private fun targetNamespaceOf(file: File): String? =
        Regex("""targetNamespace="([^"]+)"""").find(file.readText())?.groupValues?.get(1)
}
