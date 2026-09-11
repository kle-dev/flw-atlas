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
                      {"key":"searchOpen","type":"search","name":"Search open"}]}""")
            File(dir, "order.data").writeText(
                """{"key":"orderDO","name":"Order","dataObjectType":"serviceRegistryDataObject",
                    "referencedServiceDefinitionModelKey":"orders"}""")
            File(dir, "orders.page").writeText(
                """{"metadata":{"key":"ordersPage","name":"Orders","modelType":"page"},
                    "rows":[{"cols":[{"id":"tbl","type":"dataTable","extraSettings":{"dataSource":"DataObject",
                      "dataObjectDefinitionKey":"orderDO","dataObjectOperationKey":"searchAll"}}]}]}""")
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
        for (op in listOf("findById", "create", "update", "delete")) assertEquals(op, listOf("dataObject:orderDO"), usedBy(op))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aSearchOperationIsUsedOnlyWhenSomethingNamesIt() {
        assertEquals(listOf("page:ordersPage"), usedBy("searchAll"))
        assertEquals(emptyList<String>(), usedBy("searchOpen"))
        val unused = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedOps" }.map { it["node"] }
        assertEquals(listOf("serviceOperation:orders#searchOpen"), unused)
    }
}
