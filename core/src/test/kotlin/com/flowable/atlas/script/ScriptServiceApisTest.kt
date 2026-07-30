package com.flowable.atlas.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spot checks of the generated service surfaces — guards the extraction, not the engine. */
class ScriptServiceApisTest {

    @Test
    fun generatedSurfacesLookSane() {
        assertEquals("processDefinitionKey, businessKey, variables",
            ScriptServiceApis.RUNTIME_SERVICE["startProcessInstanceByKey"])
        assertTrue(ScriptServiceApis.TASK_SERVICE.containsKey("complete"))
        assertTrue(ScriptServiceApis.TASK_SERVICE.containsKey("saveTask"))
        assertTrue(ScriptServiceApis.REPOSITORY_SERVICE.containsKey("createDeployment"))
        assertTrue(ScriptServiceApis.IDENTITY_SERVICE.containsKey("createUserQuery"))
        assertTrue(ScriptServiceApis.CMMN_RUNTIME_SERVICE.containsKey("createCaseInstanceBuilder"))
        assertTrue(ScriptServiceApis.CMMN_TASK_SERVICE.containsKey("complete"))
        for (api in listOf(
            ScriptServiceApis.RUNTIME_SERVICE, ScriptServiceApis.TASK_SERVICE,
            ScriptServiceApis.REPOSITORY_SERVICE, ScriptServiceApis.HISTORY_SERVICE,
            ScriptServiceApis.MANAGEMENT_SERVICE, ScriptServiceApis.FORM_SERVICE,
            ScriptServiceApis.IDENTITY_SERVICE, ScriptServiceApis.CMMN_RUNTIME_SERVICE,
            ScriptServiceApis.CMMN_TASK_SERVICE, ScriptServiceApis.CMMN_HISTORY_SERVICE,
            ScriptServiceApis.CMMN_MANAGEMENT_SERVICE,
        )) {
            assertTrue("surface must not be empty", api.isNotEmpty())
            assertFalse("no blank member names", api.keys.any { it.isBlank() })
            assertFalse("no stray generics in parameter names",
                api.values.any { '<' in it || '>' in it })
        }
    }

    @Test
    fun servicesAreWiredIntoTheContexts() {
        val bpmn = ScriptBindingsCatalog.rootsFor(ScriptContext.BPMN_SCRIPT_TASK)
        assertEquals(ScriptServiceApis.RUNTIME_SERVICE, bpmn["runtimeService"]?.members)
        // identityService is the advertised name; the engine's legacy alias stays valid but hidden
        assertEquals(ScriptServiceApis.IDENTITY_SERVICE, bpmn["identityService"]?.members)
        assertEquals(false, bpmn["identityService"]?.hidden)
        assertEquals(true, bpmn["identityServiceKey"]?.hidden)
        assertEquals(ScriptServiceApis.IDENTITY_SERVICE, bpmn["identityServiceKey"]?.members)
        // in a CMMN context the same binding name resolves to the CMMN service
        val cmmn = ScriptBindingsCatalog.rootsFor(ScriptContext.CMMN_SCRIPT_TASK)
        assertEquals(ScriptServiceApis.CMMN_RUNTIME_SERVICE, cmmn["runtimeService"]?.members)
        // typo check now covers services too
        val p = ScriptValidator.validate("taskService.compleet(taskId)", "groovy",
            context = ScriptContext.BPMN_SCRIPT_TASK).single()
        assertEquals(ScriptProblemKind.UNKNOWN_MEMBER, p.kind)
        assertEquals("complete", p.quickFix)
    }
}
