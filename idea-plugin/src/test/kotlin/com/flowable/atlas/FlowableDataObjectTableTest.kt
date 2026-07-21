package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.navigation.FlowableKeyDocumentationProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A data object's physical table name is resolvable (via its backing `database` service) and shown
 * when hovering a plain data-object key string — the data behind the "table name" inlay hint.
 */
class FlowableDataObjectTableTest : BasePlatformTestCase() {

    private fun addModels() {
        myFixture.addFileToProject(
            "models/customer.data",
            """{ "key": "kyc-customer", "dataObjectType": "serviceRegistryDataObject",
                 "referencedServiceDefinitionModelKey": "kyc-customer-svc", "fieldMappings": [] }""",
        )
        myFixture.addFileToProject(
            "models/customer.service",
            """{ "key": "kyc-customer-svc", "type": "database", "tableName": "CMM_CUSTOMER", "columnMappings": [] }""",
        )
    }

    fun testDataObjectTablesMapsKeyToTable() {
        addModels()
        val service = project.service<FlowableModelIndexService>()
        service.index()
        assertEquals("CMM_CUSTOMER", service.dataObjectTables()["kyc-customer"])
    }

    fun testHoverShowsTableForPlainDataObjectKeyString() {
        addModels()
        project.service<FlowableModelIndexService>().index()
        // A bare constant — NOT a recognised Flowable API call site.
        myFixture.configureByText("A.java", "class A { static final String CUSTOMER = \"kyc-customer\"; }")
        val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLiteralExpression::class.java)
            .first { it.value == "kyc-customer" }

        val doc = FlowableKeyDocumentationProvider().generateDoc(literal, literal)
        assertNotNull("data-object key constant should get hover docs", doc)
        assertTrue("hover should show the physical table: $doc", doc!!.contains("CMM_CUSTOMER"))
    }
}
