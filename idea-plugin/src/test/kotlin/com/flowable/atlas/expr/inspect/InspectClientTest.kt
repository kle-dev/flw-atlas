package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

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

    @Test
    fun `the probe treats an answering app as reachable`() {
        assertTrue(InspectClient.probeOutcome(200, "") is InspectClient.Outcome.Evaluated)
        assertTrue(InspectClient.probeOutcome(405, "") is InspectClient.Outcome.Evaluated)
    }

    @Test
    fun `the probe separates rejected credentials from a wrong context path`() {
        val unauthorized = InspectClient.probeOutcome(401, "") as InspectClient.Outcome.Failed
        assertTrue(unauthorized.message.contains("rejected these credentials"))
        val missing = InspectClient.probeOutcome(404, "") as InspectClient.Outcome.Failed
        assertTrue(missing.message.contains("context path"))
    }

    @Test
    fun `the 500 Flowable answers a bodyless GET with counts as reachable`() {
        // What the user actually saw: a healthy local app reported as an internal server error. A GET on
        // a POST-only endpoint is *supposed* to be refused; that it was refused by the endpoint at all
        // proves the URL resolves, Inspect is enabled and the credentials passed.
        val body = """{"message":"Internal server error","exception":"Request method 'GET' not supported"}"""
        assertTrue(InspectClient.probeOutcome(500, body) is InspectClient.Outcome.Evaluated)
    }

    @Test
    fun `basic auth that works is never reported as an sso bounce`() {
        // The probe asks an API path, not the app's root: a Flowable app answers its root with the
        // SPA's HTML, and calling that a login bounce told people with perfectly good credentials that
        // they had to sign in through a browser.
        assertTrue(InspectClient.probeOutcome(405, "") is InspectClient.Outcome.Evaluated)
        val bounced = InspectClient.probeOutcome(200, "<!DOCTYPE html><html><body>Sign in</body></html>")
        assertTrue("an HTML body on an API path really is a bounce", bounced is InspectClient.Outcome.Failed)
    }

    @Test
    fun `an unreadable body simply leaves the message out`() {
        assertNull(InspectClient.serverMessage("<html>nope</html>"))
        assertNull(InspectClient.serverMessage(""))
        assertEquals("Internal server error", InspectClient.serverMessage("""{"message":"Internal server error"}"""))
    }

    @Test
    fun `a very long server message is shortened rather than wrapped across the dialog`() {
        val long = "x".repeat(400)
        val shortened = InspectClient.serverMessage("""{"message":"$long"}""")!!
        assertTrue(shortened.length <= 120)
        assertTrue(shortened.endsWith("…"))
    }

    @Test
    fun `an html body decides, whatever the status code says`() {
        // An identity provider answers its login page with a plain 200.
        val bounced = InspectClient.probeOutcome(200, "<!DOCTYPE html><html><body>Sign in</body></html>")
        assertEquals(InspectClient.SSO_REDIRECT_HINT, (bounced as InspectClient.Outcome.Failed).message)
    }

    @Test
    fun `a host name is retried against every address it resolves to`() {
        // The JDK's HttpClient connects to the first resolved address and never falls back. On macOS
        // localhost resolves to 127.0.0.1 and then ::1, so an app bound to IPv6 only — a Vite/node dev
        // server, by default — was reported as "nothing is listening" while the browser had it open.
        val candidates = InspectClient.addressCandidates(URI.create("http://localhost:9914/inspect-api/evaluate-expression"))
        assertTrue("the name itself is tried first", candidates.first().host == "localhost")
        assertTrue("and every address it resolves to after it", candidates.size > 1)
        assertTrue(candidates.all { it.path == "/inspect-api/evaluate-expression" })
        assertTrue(candidates.all { it.port == 9914 })
    }

    @Test
    fun `an address literal has nothing to fall back to`() {
        assertEquals(1, InspectClient.addressCandidates(URI.create("http://127.0.0.1:9914/x")).size)
        assertEquals(1, InspectClient.addressCandidates(URI.create("http://[::1]:9914/x")).size)
    }

    @Test
    fun `a query string survives the substitution`() {
        val candidates = InspectClient.addressCandidates(URI.create("http://localhost:9914/x?a=1&b=2"))
        assertTrue(candidates.all { it.rawQuery == "a=1&b=2" })
    }
}
