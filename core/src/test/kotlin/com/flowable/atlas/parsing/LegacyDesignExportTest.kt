package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Legacy Design exports ("typed-directory" format) wrap every model in
 * `{id, key, name, editorJson}` under `<type>-models/<name>.json`. The extractor must unwrap the
 * modern-shaped bodies (service, data object, action, …) into their real parsers, register
 * Oryx-shaped forms/pages by key, and pick the same wrappers up from a loose directory layout.
 */
class LegacyDesignExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wrapper(key: String, name: String, editorJson: String): String =
        """{"id": "MODEL-$key", "key": "$key", "name": "$name", "tenantId": "", "editorJson": $editorJson}"""

    /** The same wrapper with `editorJson` as an escaped JSON **string** — how Design actually persists
     *  it (`MODEL_EDITOR_JSON` is a CLOB), and the shape that lost every form component. */
    private fun stringWrapper(key: String, name: String, editorJson: String): String {
        val escaped = editorJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        return """{"id": "MODEL-$key", "key": "$key", "name": "$name", "editorJson": "$escaped"}"""
    }

    private val serviceJson = wrapper(
        "legacySvc", "Legacy Service",
        """{"type": "REST", "config": {"baseUrl": "https://api.example.com"},
            "operations": [{"key": "findAll", "name": "Find all", "config": {"method": "GET", "url": "/all"}}],
            "referenceKey": "legacyDO"}""",
    )
    private val dataObjectJson = wrapper(
        "legacyDO", "Legacy DO",
        """{"dataObjectType": "serviceRegistryDataObject", "sourceId": "legacySvc",
            "referencedServiceDefinitionModelKey": "legacySvc",
            "fieldMappings": [{"name": "email", "label": "Email", "type": "STRING"}]}""",
    )
    private val actionJson = wrapper(
        "legacyAction", "Legacy Action",
        """{"botKey": "bpmn-start-process-instance-bot", "signalName": "someProcess",
            "form": "legacyForm", "permissionGroups": ["sales"]}""",
    )
    /** A modern form body: `rows` of `cols` of components, exactly what Design writes today. */
    private val modernFormBody =
        """{"rows": [{"cols": [{"id": "text1", "type": "text", "label": "One Field",
                               "value": "{{oneField}}"}]}],
            "metadata": {"key": "modernForm", "name": "Modern Form",
                         "description": "Captures the one field.", "modelType": "form"}}"""

    // Oryx (old form editor) shape — only registration + binding harvest are possible
    private val formJson = wrapper(
        "legacyForm", "Legacy Form",
        """{"stencil": {"id": "XForm"}, "childShapes": [
            {"stencil": {"id": "base-text"}, "properties": {"value": "{{customerName}}"}}]}""",
    )
    private val appJson = wrapper(
        "legacyApp", "Legacy App",
        """{"models": [{"id": "MODEL-legacySvc", "name": "Legacy Service", "modelType": 20}], "theme": "theme-1"}""",
    )

    private fun assertLegacyModels(result: Map<String, Any?>, label: String) {
        fun keys(bucket: String) =
            (result[bucket] as List<*>).map { (it as Map<*, *>)["key"] }
        assertEquals("$label services", listOf("legacySvc"), keys("services"))
        assertEquals("$label dataObjects", listOf("legacyDO"), keys("dataObjects"))
        assertEquals("$label actions", listOf("legacyAction"), keys("actions"))
        assertEquals("$label forms", listOf("legacyForm"), keys("forms"))
        val action = (result["actions"] as List<*>).first() as Map<*, *>
        assertEquals("legacy `form` maps onto formKey", "legacyForm", action["formKey"])
        val svc = (result["services"] as List<*>).first() as Map<*, *>
        assertEquals("service body parsed", "https://api.example.com", svc["baseUrl"])
        // the {{…}} binding inside the Oryx form body is harvested and attributed to the form
        assertTrue("form binding harvested", (result["placeholders"] as List<*>)
            .any { it.toString().contains("customerName") })
    }

    @Test
    fun looseTypedDirectoryLayoutIsParsed() {
        val root = tmp.newFolder("legacy-workspace")
        File(root, "service-models").mkdirs()
        File(root, "data-object-models").mkdirs()
        File(root, "action-models").mkdirs()
        File(root, "form-models").mkdirs()
        File(root, "service-models/legacySvc.json").writeText(serviceJson)
        File(root, "data-object-models/legacyDO.json").writeText(dataObjectJson)
        File(root, "action-models/legacyAction.json").writeText(actionJson)
        File(root, "form-models/legacyForm.json").writeText(formJson)
        assertLegacyModels(Atlas.extract(root), "loose")
    }

    @Test
    fun zipExportWithRootAppIsParsed() {
        val root = tmp.newFolder("legacy-zip-project")
        val zip = File(root, "Legacy App.zip")
        ZipOutputStream(zip.outputStream()).use { zs ->
            for ((entry, content) in listOf(
                "service-models/legacySvc.json" to serviceJson,
                "data-object-models/legacyDO.json" to dataObjectJson,
                "action-models/legacyAction.json" to actionJson,
                "form-models/legacyForm.json" to formJson,
                "legacyApp.json" to appJson,
            )) {
                zs.putNextEntry(ZipEntry(entry))
                zs.write(content.toByteArray())
                zs.closeEntry()
            }
        }
        val result = Atlas.extract(root)
        assertLegacyModels(result, "zip")
        // the root-level wrapper is recognized as the (legacy) app model
        val apps = (result["apps"] as List<*>).map { (it as Map<*, *>)["key"] }
        assertEquals(listOf("legacyApp"), apps)
        // …and archive co-location gives it `contains` membership over the unwrapped models
        val graph = result["graph"] as Map<*, *>
        val contains = (graph["edges"] as List<*>).map { it as Map<*, *> }
            .filter { it["s"] == "app:legacyApp" && it["rel"] == "contains" }
            .map { it["t"] }
        assertTrue("app contains the legacy service", contains.contains("service:legacySvc"))
        assertTrue("app contains the legacy form", contains.contains("form:legacyForm"))
    }

    /**
     * A **modern** form body inside a workspace wrapper — the case the two tests above never covered,
     * because their form is Oryx-shaped and has no components a parser can read anyway.
     *
     * `editorJson` is an escaped JSON *string* in a real export, and a form's components are reached by
     * walking maps, so every field of every form came out empty: no ids, no labels, no descriptions,
     * and nothing for ⌘K to find. Both spellings of the wrapper are checked, because an export that
     * nests `editorJson` as an object used to be the only one that worked.
     */
    @Test
    fun aModernFormInAWorkspaceExportKeepsItsFields() {
        for ((shape, json) in listOf(
            "editorJson as a string" to stringWrapper("modernForm", "Modern Form", modernFormBody),
            "editorJson as an object" to wrapper("modernForm", "Modern Form", modernFormBody),
        )) {
            val root = tmp.newFolder("ws-" + shape.filter { it.isLetter() })
            File(root, "form-models").mkdirs()
            File(root, "form-models/modernForm.json").writeText(json)
            val forms = Atlas.extract(root)["forms"] as List<*>
            val form = forms.firstOrNull { (it as Map<*, *>)["key"] == "modernForm" } as Map<*, *>?
            assertTrue("$shape: the form is missing entirely", form != null)
            @Suppress("UNCHECKED_CAST")
            val fields = form!!["fields"] as List<Map<String, Any?>>
            assertEquals("$shape: field ids", listOf("text1"), fields.map { it["id"] })
            assertEquals("$shape: the label the modeller typed", listOf("One Field"), fields.map { it["label"] })
            assertEquals("$shape: the model description", "Captures the one field.", form["description"])
        }
    }

}
