package com.flowable.atlas.expr.inspect

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * [InspectClient.evaluate] network behavior against a local JDK [HttpServer] stub — specifically the
 * auth-header layering: basic auth for local dev, captured browser-session headers (Cookie / CSRF /
 * Authorization) for SSO-fronted apps, and the rule that a captured `Authorization` suppresses basic
 * (no duplicate header). Also the SSO login-redirect mapping.
 */
class InspectClientHttpTest {

    private lateinit var server: HttpServer
    private val received = HashMap<String, List<String>>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun stop() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun request(username: String = "user", password: String = "secret", session: Map<String, String>? = null) =
        InspectClient.Request(
            baseUrl = baseUrl(),
            expression = "\${amount > 5}",
            scopeType = InspectClient.ScopeType.BPMN,
            scopeId = "pi-1",
            username = username,
            password = password,
            sessionHeaders = session,
        )

    /** Stub the endpoint: record the inbound headers, then answer [code] with [body]. */
    private fun stub(code: Int, body: String) {
        server.createContext("/inspect-api/evaluate-expression") { ex: HttpExchange ->
            for (name in listOf("Authorization", "Cookie", "X-XSRF-TOKEN")) received[name] = ex.requestHeaders[name] ?: emptyList()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private val validJson = """{"valid":true,"value":true,"valueType":"boolean"}"""

    @Test fun sendsBasicAuthWhenNoCapturedSession() {
        stub(200, validJson)
        val out = InspectClient.evaluate(request(session = null))
        assertTrue(out is InspectClient.Outcome.Evaluated)
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        assertEquals(listOf(expected), received["Authorization"])
        assertTrue(received["Cookie"].isNullOrEmpty())
    }

    @Test fun sendsCapturedCookieAndCsrfAlongsideBasic() {
        stub(200, validJson)
        val out = InspectClient.evaluate(
            request(session = linkedMapOf("Cookie" to "SESSION=abc; XSRF-TOKEN=t", "X-XSRF-TOKEN" to "t")),
        )
        assertTrue(out is InspectClient.Outcome.Evaluated)
        assertEquals(listOf("SESSION=abc; XSRF-TOKEN=t"), received["Cookie"])
        assertEquals(listOf("t"), received["X-XSRF-TOKEN"])
        // No captured Authorization → basic auth is still sent (combined OAuth2-gateway + basic case).
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        assertEquals(listOf(expected), received["Authorization"])
    }

    @Test fun capturedAuthorizationSuppressesBasic() {
        stub(200, validJson)
        val out = InspectClient.evaluate(
            request(session = linkedMapOf("Authorization" to "Bearer T", "Cookie" to "s=1")),
        )
        assertTrue(out is InspectClient.Outcome.Evaluated)
        // Exactly the captured Authorization, not two headers.
        assertEquals(listOf("Bearer T"), received["Authorization"])
        assertEquals(listOf("s=1"), received["Cookie"])
    }

    @Test fun redirectSurfacesSsoHint() {
        stub(302, "<html>login</html>")
        val out = InspectClient.evaluate(request(session = null))
        assertTrue(out is InspectClient.Outcome.Failed)
        assertTrue((out as InspectClient.Outcome.Failed).message.contains("SSO/OAuth2"))
    }

    @Test fun blankBaseUrlFailsFast() {
        val out = InspectClient.evaluate(request().copy(baseUrl = ""))
        assertTrue(out is InspectClient.Outcome.Failed)
        assertNull(received["Authorization"]) // never hit the network
    }
}
