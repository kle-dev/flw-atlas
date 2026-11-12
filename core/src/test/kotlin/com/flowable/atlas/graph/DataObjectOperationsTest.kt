package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A data object bound to a service is served by that service's lookup/create/update/delete — the engine
 * calls them, nothing in a model names them. A search operation still has to be named by something.
 */
class DataObjectOperationsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-do-ops-test").toFile()
            File(dir, "orders.service").writeText(
                """{"key":"orders","name":"Orders","type":"database","tableName":"ORDERS_",
                    "columnMappings":[{"name":"id","type":"STRING","columnName":"ID_"}],
                    "operations":[
                      {"key":"findById","type":"lookup","name":"Lookup"},
                      {"key":"create","type":"create","name":"Create"},
                      {"key":"update","type":"update","name":"Update"},
                      {"key":"delete","type":"delete","name":"Delete"},
                      {"key":"searchAll","type":"search","name":"Search all"},
                      {"key":"searchOpen","type":"search","name":"Search open"},
                      {"key":"searchArchived","type":"search","name":"Search archived"}]}""")
            File(dir, "order.data").writeText(
                """{"key":"orderDO","name":"Order","dataObjectType":"serviceRegistryDataObject",
                    "referencedServiceDefinitionModelKey":"orders"}""")
            File(dir, "orders.page").writeText(
                """{"metadata":{"key":"ordersPage","name":"Orders","modelType":"page"},
                    "rows":[{"cols":[{"id":"tbl","type":"dataTable","extraSettings":{"dataSource":"DataObject",
                      "dataObjectDefinitionKey":"orderDO","dataObjectOperationKey":"searchAll",
                      "dataObjectDataTableEditOperationKey":"update","dataObjectDataTableEnableEdit":true}}]}]}""")
            // a select whose options come from a search operation and whose stored id resolves through a lookup
            File(dir, "pick.form").writeText(
                """{"metadata":{"key":"pickForm","name":"Pick","modelType":"form"},
                    "rows":[{"cols":[{"id":"sel","type":"selectSingle","extraSettings":{"serviceModel":{
                      "serviceModelKey":"orders","searchOperationKey":"searchOpen","lookupOperationKey":"findById"}}}]}]}""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun usedBy(op: String): List<*> =
        ((((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
            .single { it["id"] == "serviceOperation:orders#$op" }["data"] as Map<String, Any?>)["usedBy"] as List<*>)

    @Test
    fun theEngineOperationsAreUsedByTheBoundDataObject() {
        for (op in listOf("create", "delete")) assertEquals(op, listOf("dataObject:orderDO"), usedBy(op))
        // …and by whatever names them as well: the select's lookup, the table's edit action
        assertEquals(listOf("dataObject:orderDO", "form:pickForm"), usedBy("findById").sortedBy { it.toString() })
        assertEquals(listOf("dataObject:orderDO", "page:ordersPage"), usedBy("update").sortedBy { it.toString() })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aSearchOperationIsUsedOnlyWhenSomethingNamesIt() {
        assertEquals(listOf("page:ordersPage"), usedBy("searchAll"))
        assertEquals(listOf("form:pickForm"), usedBy("searchOpen"))
        assertEquals(emptyList<String>(), usedBy("searchArchived"))
        val unused = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedOps" }.map { it["node"] }
        assertEquals(listOf("serviceOperation:orders#searchArchived"), unused)
    }
}
