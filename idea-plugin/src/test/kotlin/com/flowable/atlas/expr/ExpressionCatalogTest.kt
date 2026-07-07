package com.flowable.atlas.expr

import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionCatalogTest {

    @Test
    fun prefixAliasesResolveToCanonical() {
        assertEquals("variables", FlowableExpressionCatalog.resolvePrefix("vars"))
        assertEquals("variables", FlowableExpressionCatalog.resolvePrefix("var"))
        assertEquals("variables", FlowableExpressionCatalog.resolvePrefix("variables"))
        assertEquals("date", FlowableExpressionCatalog.resolvePrefix("date"))
        assertNull(FlowableExpressionCatalog.resolvePrefix("nope"))
    }

    @Test
    fun backendFunctionsAcceptAliases() {
        assertTrue(FlowableExpressionCatalog.isBackendFunction("date", "now"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("vars", "get"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("vars", "eq"))       // alias of equals
        assertTrue(FlowableExpressionCatalog.isBackendFunction("variables", "lt"))  // alias of lowerThan
        assertFalse(FlowableExpressionCatalog.isBackendFunction("vars", "noo"))
        assertFalse(FlowableExpressionCatalog.isBackendFunction("date", "nope"))
    }

    @Test
    fun noPrefixBackendFunctions() {
        assertTrue(FlowableExpressionCatalog.isBackendFunction(null, "listOf"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction(null, "markdownToHtml"))
        assertFalse(FlowableExpressionCatalog.isBackendFunction(null, "sum"))
    }

    @Test
    fun cmmnPrefixMergesCompletionAndTaskFunctions() {
        assertTrue(FlowableExpressionCatalog.isBackendFunction("cmmn", "isPlanItemCompleted"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("cmmn", "setAssignee"))
    }

    @Test
    fun frontendMembersAreKnown() {
        assertTrue(FlowableExpressionCatalog.isFrontendMember("sum"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("formatDate"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("remove"))
        assertFalse(FlowableExpressionCatalog.isFrontendMember("sim"))
        assertEquals(4, FlowableExpressionCatalog.frontendSubMembers("remove").size)
        assertEquals(2, FlowableExpressionCatalog.frontendSubMembers("JSON").size)
    }

    @Test
    fun workInjectedFrontendMembersAreKnown() {
        // Merged onto `flw` by the Work runtime (useGlobalResolver + Form.tsx), not base @flowable/forms.
        assertTrue(FlowableExpressionCatalog.isFrontendMember("getUser"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("getMasterDataInstance"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("getMasterDataInstanceByKey"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("getDataObjectInstance"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("translateWorkObject"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("validate"))
    }

    @Test
    fun newBackendPrefixesAndFunctionsAreKnown() {
        assertEquals("sequence", FlowableExpressionCatalog.resolvePrefix("seq"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("sequence", "nextNumber"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("content", "getContentItem"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("conversationStatus", "unreadCountForUser"))
        // identity-link fns span bpmn / cmmn / task; Platform variable extras present.
        assertTrue(FlowableExpressionCatalog.isBackendFunction("bpmn", "addWatcherUser"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("task", "setAssignee"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("variables", "containsAll"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction("variables", "makeTransient"))
        // IDM helpers are no-prefix globals, not under userInfo:
        assertTrue(FlowableExpressionCatalog.isBackendFunction(null, "findUser"))
        assertTrue(FlowableExpressionCatalog.isBackendFunction(null, "findUserAccount"))
    }

    @Test
    fun frontendArrayAndDataSubMembersAreKnown() {
        assertTrue(FlowableExpressionCatalog.isFrontendMember("array"))
        assertTrue(FlowableExpressionCatalog.isFrontendMember("data"))
        assertTrue(FlowableExpressionCatalog.isFrontendSubMember("array", "filter"))
        assertTrue(FlowableExpressionCatalog.isFrontendSubMember("array", "reduce"))
        assertTrue(FlowableExpressionCatalog.isFrontendSubMember("data", "hasProperty"))
        assertFalse(FlowableExpressionCatalog.isFrontendSubMember("array", "nope"))
        assertTrue("array" in FlowableExpressionCatalog.frontendNestingMembers)
        assertTrue("data" in FlowableExpressionCatalog.frontendNestingMembers)
    }

    @Test
    fun rootsArePartitionedByDialect() {
        assertTrue("execution" in FlowableExpressionCatalog.rootNames(ExpressionDialect.BACKEND))
        assertFalse("execution" in FlowableExpressionCatalog.rootNames(ExpressionDialect.FRONTEND))
        assertTrue("\$currentUser" in FlowableExpressionCatalog.rootNames(ExpressionDialect.FRONTEND))
        assertTrue("flw" in FlowableExpressionCatalog.rootNames(ExpressionDialect.FRONTEND))
    }
}
