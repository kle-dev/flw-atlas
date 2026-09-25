package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What a form component calls is what the Work forms runtime calls with it. A link goes where it points,
 * a data table's row link is where a row click goes, and a setting of a data source the component no
 * longer uses is a leftover — none of them is a call, and each one used to be one.
 */
class FormComponentCallsTest {

    private val controller = """
        package com.example;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/demo-api")
        public class DemoCustomerController {
            @GetMapping("/customers") public Object list() { return null; }
            @GetMapping("/customers/{id}") public Object one(@PathVariable String id) { return null; }
            @GetMapping("/customers/{id}/report") public Object report(@PathVariable String id) { return null; }
            @GetMapping("/countries") public Object countries() { return null; }
        }"""

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-form-calls").toFile()
        try {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.edges(rel: String): Set<Pair<Any?, Any?>> =
        ((this["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)
            .filter { it["rel"] == rel }.map { it["s"] to it["t"] }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.node(id: String) =
        ((this["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).single { it["id"] == id }["data"] as Map<String, Any?>

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aLinkGoesWhereItPointsAndOnlyARestButtonOrADownloadCalls() {
        val r = extract(mapOf(
            "src/main/java/com/example/DemoCustomerController.java" to controller,
            "forms/demo.form" to """{"metadata":{"key":"DEMO-F005","name":"Customer links","modelType":"form"},"rows":[[
                {"id":"openCustomer","type":"linkButton","value":"Open","extraSettings":{"url":"/demo-api/customers/{{customerId}}"}},
                {"id":"help","type":"link","value":"Help","extraSettings":{"url":"https://www.example.com/help"}},
                {"id":"customers","type":"dataTable","label":"Customers",
                 "extraSettings":{"dataSource":"Rest","queryUrl":"/demo-api/customers","url":"/demo-api/customers/{{${'$'}item.id}}/report"}},
                {"id":"pdf","type":"link","value":"PDF","extraSettings":{"url":"/demo-api/customers/{{customerId}}/report","asFileDownload":true}},
                {"id":"load","type":"restButton","extraSettings":{"url":"/demo-api/customers/{{customerId}}"}}
            ]]}""",
        ))
        val f = "form:DEMO-F005"
        assertEquals(setOf(
            f to "endpoint:GET /demo-api/customers",                 // the table's data source
            f to "endpoint:GET /demo-api/customers/{id}/report",     // the download link fetches it
            f to "endpoint:GET /demo-api/customers/{id}",            // the REST button
        ), r.edges("rest-call"))
        assertEquals(setOf(
            f to "endpoint:GET /demo-api/customers/{id}",            // the link button opens it
            f to "endpoint:GET /demo-api/customers/{id}/report",     // a row click opens it
            f to "external:https://www.example.com/help",
        ), r.edges("navigates-to"))
        // the form's own list of calls — what its Calls table shows — holds the two that call
        val calls = (r.node(f)["restCalls"] as List<Map<String, Any?>>).map { it["where"] }.toSet()
        assertEquals(setOf("pdf", "load"), calls)
        // a link is no REST URL the project calls
        assertTrue(r.node("external:https://www.example.com/help")["external_url"] == null)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aSettingOfADataSourceTheComponentNoLongerUsesIsNoReference() {
        val r = extract(mapOf(
            "src/main/java/com/example/DemoCustomerController.java" to controller,
            "data/md.data" to """{"key":"DEMO-MD1","name":"Colors","dataObjectType":"masterData","variables":{"code":"Code"}}""",
            "forms/demo.form" to """{"metadata":{"key":"DEMO-F002","name":"Stale data sources","modelType":"form"},"rows":[[
                {"id":"country","type":"select","label":"Country",
                 "extraSettings":{"dataSource":"Static","items":[{"id":"ch","text":"CH"}],
                   "queryUrl":"/demo-api/countries","lookupUrl":"/demo-api/customers/{{${'$'}id}}"}},
                {"id":"color","type":"select","label":"Color",
                 "extraSettings":{"dataSource":"Rest","queryUrl":"/demo-api/customers","tableKey":"DEMO-MD1"}},
                {"id":"pick","type":"dataObjectSelect","label":"Pick",
                 "extraSettings":{"dataSource":"Static","dataObjectDefinitionKey":"DEMO-DO9","dataObjectOperationKey":"search"}}
            ]]}""",
            "data/do.data" to """{"key":"DEMO-DO9","name":"Picked","dataObjectType":"lookup","fieldMappings":[]}""",
        ))
        val f = "form:DEMO-F002"
        // the static select reads its items, not the URLs it had while it was a REST select
        assertEquals(setOf(f to "endpoint:GET /demo-api/customers"), r.edges("rest-call"))
        // the REST select does not show the master-data table it once did
        assertFalse(r.edges("field-masterData").any { it.first == f })
        // a data-object select reads its data object whatever `dataSource` says — the runtime sets it
        assertTrue(r.edges("field-dataObject").contains(f to "dataObject:DEMO-DO9"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aDataTableActionThatIsSwitchedOffCallsNothing() {
        val r = extract(mapOf(
            "data/demo.data" to """{"key":"DEMO-DO1","name":"Item","dataObjectType":"service",
                "referencedServiceDefinitionModelKey":"DEMO-SVC1","fieldMappings":[{"name":"name","type":"string"}]}""",
            "services/demo.service" to """{"key":"DEMO-SVC1","name":"Item service","type":"database","tableName":"DEMO_ITEM",
                "operations":[{"key":"search","type":"search"},{"key":"purge","type":"custom"},{"key":"archive","type":"custom"}]}""",
            "forms/edit.form" to """{"metadata":{"key":"DEMO-F004","name":"Edit item","modelType":"form"},"rows":[]}""",
            "forms/demo.form" to """{"metadata":{"key":"DEMO-F003","name":"Read-only table","modelType":"form"},"rows":[[
                {"id":"items","type":"dataTable","label":"Items",
                 "extraSettings":{"dataSource":"DataObject","dataObjectDefinitionKey":"DEMO-DO1","dataObjectOperationKey":"search",
                   "dataObjectDataTableEnableEdit":false,"dataObjectDataTableEditOperationKey":"purge",
                   "dataObjectDataTableEditFormKey":"DEMO-F004",
                   "dataObjectDataTableEnableDelete":true,"dataObjectDataTableDeleteOperationKey":"archive"}}
            ]]}""",
        ))
        fun usedBy(op: String) = r.node("serviceOperation:DEMO-SVC1#$op")["usedBy"] as? List<*> ?: emptyList<Any?>()
        assertTrue(usedBy("search").contains("form:DEMO-F003"))
        assertTrue("an action that is switched on is a call", usedBy("archive").contains("form:DEMO-F003"))
        assertFalse("the edit action is off: ${usedBy("purge")}", usedBy("purge").contains("form:DEMO-F003"))
        assertFalse(r.edges("dataObjectDataTableEditFormKey").any { it.first == "form:DEMO-F003" })
    }
}
