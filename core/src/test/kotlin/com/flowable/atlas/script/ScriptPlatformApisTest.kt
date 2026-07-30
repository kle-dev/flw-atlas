package com.flowable.atlas.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spot checks of the generated platform-bean surfaces and their catalog wiring. */
class ScriptPlatformApisTest {

    @Test
    fun generatedSurfacesLookSane() {
        // documented in the official master-data howto
        assertTrue(ScriptPlatformApis.DATA_OBJECT_RUNTIME_SERVICE.containsKey("addGroupIdentityLink"))
        assertTrue(ScriptPlatformApis.DATA_OBJECT_RUNTIME_SERVICE.containsKey("deleteGroupIdentityLink"))
        // the inheritance merge: CoreContentService must carry the OSS ContentService base methods
        assertTrue(ScriptPlatformApis.CONTENT_SERVICE.containsKey("createContentItemQuery"))
        assertTrue(ScriptPlatformApis.PLATFORM_IDENTITY_SERVICE.containsKey("createUserQuery"))
        assertTrue(ScriptPlatformApis.PLATFORM_FORM_SERVICE.size > 5)
        val all = listOf(
            ScriptPlatformApis.DATA_OBJECT_RUNTIME_SERVICE, ScriptPlatformApis.DATA_OBJECT_REPOSITORY_SERVICE,
            ScriptPlatformApis.CONTENT_SERVICE, ScriptPlatformApis.DOCUMENT_REPOSITORY_SERVICE,
            ScriptPlatformApis.TEMPLATE_SERVICE, ScriptPlatformApis.TEMPLATE_REPOSITORY_SERVICE,
            ScriptPlatformApis.SEQUENCE_SERVICE, ScriptPlatformApis.PLATFORM_RUNTIME_SERVICE,
            ScriptPlatformApis.PLATFORM_IDENTITY_SERVICE, ScriptPlatformApis.ACTION_RUNTIME_SERVICE,
            ScriptPlatformApis.SERVICE_REGISTRY_RUNTIME_SERVICE, ScriptPlatformApis.AUDIT_SERVICE,
        )
        for (api in all) {
            assertTrue("surface must not be empty", api.isNotEmpty())
            assertFalse("no blank member names", api.keys.any { it.isBlank() })
            assertFalse("no stray generics in parameter names", api.values.any { '<' in it || '>' in it })
        }
    }

    @Test
    fun beansAreWiredIntoEveryConcreteContext() {
        for (ctx in listOf(ScriptContext.BPMN_SCRIPT_TASK, ScriptContext.CMMN_SCRIPT_TASK, ScriptContext.ACTION_BOT)) {
            val root = ScriptBindingsCatalog.rootsFor(ctx)["dataObjectRuntimeService"]
            assertTrue("dataObjectRuntimeService must be bound in $ctx", root != null)
            assertEquals(true, root?.bean)
        }
        val bpmn = ScriptBindingsCatalog.rootsFor(ScriptContext.BPMN_SCRIPT_TASK)
        // contentService is advertised; its second Spring name stays a hidden alias
        assertEquals(false, bpmn["contentService"]?.hidden)
        assertEquals(true, bpmn["coreContentService"]?.hidden)
        assertEquals(bpmn["contentService"]?.members, bpmn["coreContentService"]?.members)
        assertTrue("dataObjectRuntimeService" in ScriptBindingsCatalog.ALL_ROOT_NAMES)
    }

    @Test
    fun beanMemberTyposGetADidYouMean() {
        val p = ScriptValidator.validate(
            "dataObjectRuntimeService.addGroupIdentityLnk(lookupId, key, groupId, type)",
            "groovy", context = ScriptContext.BPMN_SCRIPT_TASK).single()
        assertEquals(ScriptProblemKind.UNKNOWN_MEMBER, p.kind)
        assertEquals("addGroupIdentityLink", p.quickFix)
        // bean reads never count as heuristic variable reads
        assertFalse("dataObjectRuntimeService" in
            com.flowable.atlas.parsing.ScriptVars.analyze("dataObjectRuntimeService.findById(id)", "groovy").reads)
    }
}
