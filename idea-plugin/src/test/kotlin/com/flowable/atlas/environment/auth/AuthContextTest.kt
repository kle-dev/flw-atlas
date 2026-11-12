package com.flowable.atlas.environment.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpRequest
import java.util.Base64

/**
 * The header rules every Flowable request now shares. They were duplicated — and only half-implemented
 * on the Design side — so they are asserted here once rather than at two call sites.
 */
class AuthContextTest {

    private fun headersOf(auth: AuthContext): Map<String, List<String>> =
        auth.apply(HttpRequest.newBuilder().uri(URI.create("https://host/x"))).GET().build().headers().map()

    @Test fun `basic auth becomes one Authorization header`() {
        val headers = headersOf(AuthContext.basic("u", "p"))
        assertEquals(
            listOf("Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray())),
            headers["Authorization"],
        )
    }

    @Test fun `a token is sent as a bearer, however it was pasted`() {
        assertEquals(listOf("Bearer T0K"), headersOf(AuthContext.token("  Bearer T0K "))["Authorization"])
        assertEquals(listOf("Bearer T0K"), headersOf(AuthContext.token("\"T0K\""))["Authorization"])
    }

    @Test fun `a credential and a captured session travel together`() {
        // The case that makes this a context rather than an enum: an SSO-fronted Flowable wants the
        // cookie, and may still want basic auth behind it. The server's chain picks.
        val headers = headersOf(
            AuthContext.basic("u", "p", mapOf("Cookie" to "SESSION=abc", "X-XSRF-TOKEN" to "t")),
        )
        assertEquals(listOf("SESSION=abc"), headers["Cookie"])
        assertEquals(listOf("t"), headers["X-Xsrf-Token"] ?: headers["X-XSRF-TOKEN"])
        assertEquals(1, headers["Authorization"]?.size)
    }

    @Test fun `a captured Authorization wins, and is never doubled`() {
        // `HttpRequest.Builder.header` appends, so sending both is two Authorization headers — which is
        // a 400 from the server, not a fallback. The capture is the more specific answer: the user just
        // copied a request that worked.
        val headers = headersOf(
            AuthContext.basic("u", "p", mapOf("authorization" to "Bearer FROM-CURL", "Cookie" to "S=1")),
        )
        assertEquals(listOf("Bearer FROM-CURL"), headers["Authorization"])
    }

    @Test fun `blank values are not sent`() {
        val headers = headersOf(AuthContext.basic("", "", mapOf("Cookie" to "  ")))
        assertNull(headers["Authorization"])
        assertNull(headers["Cookie"])
    }

    @Test fun `emptiness is what tells a caller to go and ask for credentials`() {
        assertTrue(AuthContext().isEmpty)
        assertTrue(AuthContext.basic("", "").isEmpty)
        assertTrue("a blank token authenticates nothing", AuthContext.token("   ").isEmpty)
        assertFalse(AuthContext.basic("u", "").isEmpty)
        // The point of the whole change: a captured cookie is enough on its own, so a server behind an
        // identity provider is reachable with no stored secret at all.
        assertFalse(AuthContext.session(mapOf("Cookie" to "S=1")).isEmpty)
        assertTrue(AuthContext.session(mapOf("Cookie" to "")).isEmpty)
    }

    @Test fun `a blank credential produces no header rather than an empty one`() {
        assertNull(AuthContext.basic("", "").authorizationHeader())
        assertNull(AuthContext.token(" ").authorizationHeader())
        assertNull(AuthContext().authorizationHeader())
    }
}
