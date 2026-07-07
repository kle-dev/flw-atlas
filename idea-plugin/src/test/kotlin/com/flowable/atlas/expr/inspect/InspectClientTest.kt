package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectClientTest {

    @Test fun buildsRequestBodyMatchingEvaluateExpressionDto() {
        val body = InspectClient.buildBody("\${amount > 5}", InspectClient.ScopeType.BPMN, "pi-1", null)
        assertEquals("""{"expression":"${'$'}{amount > 5}","scopeId":"pi-1","scopeType":"bpmn"}""", body)
    }

    @Test fun includesSubScopeIdWhenPresent() {
        val body = InspectClient.buildBody("\${x}", InspectClient.ScopeType.CMMN, "case-1", "plan-1")
        assertTrue(body.contains("\"subScopeId\":\"plan-1\""))
        assertTrue(body.contains("\"scopeType\":\"cmmn\""))
    }

    @Test fun parsesSuccessResponse() {
        val r = InspectClient.parseResponse("""{"value":true,"valueType":"boolean","valid":true}""")
        assertTrue(r.valid)
        assertEquals(true, r.value)
        assertEquals("boolean", r.valueType)
        assertNull(r.exception)
    }

    @Test fun parsesErrorResponse() {
        val r = InspectClient.parseResponse("""{"valid":false,"exception":"Unknown property used in expression"}""")
        assertFalse(r.valid)
        assertTrue(r.exception!!.contains("Unknown property"))
    }

    @Test fun endpointAppendsInspectApiPath() {
        assertEquals("https://host/inspect-api/evaluate-expression", InspectClient.endpoint("https://host/"))
        assertEquals("https://host/inspect-api/evaluate-expression", InspectClient.endpoint("https://host"))
    }

    @Test fun evaluateRejectsMissingScopeId() {
        val out = InspectClient.evaluate(
            InspectClient.Request("https://host", "\${x}", InspectClient.ScopeType.BPMN, "", null, "u", "p"),
        )
        assertTrue(out is InspectClient.Outcome.Failed)
        assertTrue((out as InspectClient.Outcome.Failed).message.contains("scope id"))
    }
}
