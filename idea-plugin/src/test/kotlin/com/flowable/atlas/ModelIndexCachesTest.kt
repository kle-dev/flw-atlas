package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What the JSON model files say is read once per index snapshot, not once per literal: the key
 * inspections, the value-field inspection and the Liquibase coverage inspection all ask on every
 * highlighting pass.
 */
class ModelIndexCachesTest : BasePlatformTestCase() {

    private fun addModels() {
        myFixture.addFileToProject("models/customer.service", """
            {"key":"customerService","name":"Customer service","type":"database",
             "tableName":"ACT_CUSTOMER",
             "columns":[{"name":"name","columnName":"NAME","type":"string"}],
             "operations":[{"key":"findAll","name":"Find all","inputParameters":[{"name":"limit"}]}]}
        """.trimIndent())
        myFixture.addFileToProject("models/customer.data", """
            {"key":"customer","name":"Customer","referencedServiceDefinitionModelKey":"customerService"}
        """.trimIndent())
    }

    fun testServiceTablesAndOperationsAreMemoisedPerSnapshot() {
        addModels()
        val service = project.service<FlowableModelIndexService>()
        service.refresh()

        val tables1 = service.allServiceTables()
        assertTrue("the service model was read", tables1.any { it.key == "customerService" })
        assertSame("a second ask reuses the snapshot's list", tables1, service.allServiceTables())

        val ops1 = service.operationsOf("customer")
        assertEquals(listOf("findAll"), ops1.map { it.key })
        assertSame("operations are memoised behind the backing-service lookup", ops1, service.operationsOf("customer"))

        service.invalidate()
        service.refresh()
        assertNotSame("an invalidation drops the memo with the index", tables1, service.allServiceTables())
    }

    fun testAnIndexAnswersKeySetsWithoutRebuildingThem() {
        addModels()
        val index = project.service<FlowableModelIndexService>().refresh()
        val keys = index.keySetOf(ModelType.SERVICE)
        assertEquals(setOf("customerService"), keys)
        assertSame("the set is computed once per snapshot", keys, index.keySetOf(ModelType.SERVICE))
        assertTrue(index.keySetOf(ModelType.PROCESS).isEmpty())
    }
}
