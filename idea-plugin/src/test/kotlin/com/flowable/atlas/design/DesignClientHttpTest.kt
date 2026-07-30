package com.flowable.atlas.design

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * [DesignClient] network behavior against a local JDK [HttpServer] stub: the basic-auth and bearer-token
 * headers, pagination aggregation, token creation, and the friendly error mapping.
 */
class DesignClientHttpTest {

    private lateinit var server: HttpServer

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun stop() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun conn() = DesignClient.Connection(baseUrl(), "user", "secret")

    private fun tokenConn(token: String = "T0K") = DesignClient.Connection(baseUrl(), DesignClient.Auth.Token(token))

    private fun respond(ex: HttpExchange, code: Int, body: ByteArray) {
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    @Test fun sendsBasicAuthAndAggregatesAllPages() {
        val auths = mutableListOf<String?>()
        server.createContext("/design-api/workspaces") { ex ->
            auths += ex.requestHeaders.getFirst("Authorization")
            val start = Regex("start=(\\d+)").find(ex.requestURI.query)!!.groupValues[1].toInt()
            val body = if (start == 0) {
                """{"data":[{"key":"a","name":"A"}],"total":2,"start":0,"size":1}"""
            } else {
                """{"data":[{"key":"b","name":"B"}],"total":2,"start":1,"size":1}"""
            }
            respond(ex, 200, body.toByteArray())
        }

        val out = DesignClient.listWorkspaces(conn())

        assertTrue(out is DesignClient.Result.Success)
        assertEquals(
            listOf(DesignClient.Workspace("a", "A"), DesignClient.Workspace("b", "B")),
            (out as DesignClient.Result.Success).value,
        )
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        assertEquals(2, auths.size)   // one request per page
        assertTrue(auths.all { it == expectedAuth })
    }

    /**
     * The whole header *list* is asserted, not just its first value: `HttpRequest.Builder.header()`
     * appends, so a regression that adds basic auth alongside the token would send two of them.
     */
    @Test fun sendsBearerTokenAsTheOnlyAuthorizationHeader() {
        val auths = mutableListOf<List<String>?>()
        server.createContext("/design-api/workspaces") { ex ->
            auths += ex.requestHeaders["Authorization"]
            val start = Regex("start=(\\d+)").find(ex.requestURI.query)!!.groupValues[1].toInt()
            val body = if (start == 0) {
                """{"data":[{"key":"a","name":"A"}],"total":2,"start":0,"size":1}"""
            } else {
                """{"data":[{"key":"b","name":"B"}],"total":2,"start":1,"size":1}"""
            }
            respond(ex, 200, body.toByteArray())
        }

        val out = DesignClient.listWorkspaces(tokenConn())

        assertTrue(out is DesignClient.Result.Success)
        assertEquals(2, (out as DesignClient.Result.Success).value.size)
        assertEquals(2, auths.size)                                  // one request per page
        assertTrue(auths.all { it == listOf("Bearer T0K") })         // exactly one header, no Basic
    }

    /** A pasted `Bearer …` must not become `Bearer Bearer …`. */
    @Test fun stripsBearerPrefixFromAPastedToken() {
        val auths = mutableListOf<List<String>?>()
        server.createContext("/design-api/workspaces") { ex ->
            auths += ex.requestHeaders["Authorization"]
            respond(ex, 200, """{"data":[],"total":0}""".toByteArray())
        }

        DesignClient.listWorkspaces(tokenConn("Bearer T0K"))

        assertEquals(listOf(listOf("Bearer T0K")), auths)
    }

    /** The export path builds its request separately, so it needs its own header proof. */
    @Test fun exportSendsBearerToken() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 42)
        val auths = mutableListOf<List<String>?>()
        server.createContext("/design-api/workspaces/ws/apps/hr/export") { ex ->
            auths += ex.requestHeaders["Authorization"]
            ex.responseHeaders.add("Content-Type", "application/zip")
            respond(ex, 200, zip)
        }

        val out = DesignClient.exportApp(tokenConn(), "ws", "hr")

        assertTrue(out is DesignClient.Result.Success)
        assertArrayEquals(zip, (out as DesignClient.Result.Success).value.bytes)
        assertEquals(listOf(listOf("Bearer T0K")), auths)
    }

    @Test fun tokenModeUnauthorizedMentionsExpiredToken() {
        server.createContext("/design-api/workspaces") { ex -> respond(ex, 401, "unauthorized".toByteArray()) }

        val out = DesignClient.listWorkspaces(tokenConn())

        assertTrue(out is DesignClient.Result.Failed)
        val message = (out as DesignClient.Result.Failed).message
        assertTrue(message.contains("access token"))
        assertTrue(message.contains("expired"))
    }

    @Test fun createsAccessTokenWithBasicAuthAndReturnsValue() {
        var auth: String? = null
        var body: String? = null
        server.createContext("/design-api/current-user/access-tokens") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            respond(ex, 201, """{"id":"1","name":"Atlas","expirationTime":"2027-01-01T00:00:00Z","value":"T0K"}""".toByteArray())
        }

        val out = DesignClient.createAccessToken(baseUrl(), "user", "secret", "Atlas", "P365D")

        assertTrue(out is DesignClient.Result.Success)
        val token = (out as DesignClient.Result.Success).value
        assertEquals("T0K", token.value)
        assertEquals("2027-01-01T00:00:00Z", token.expirationTime)
        // Minting is Basic-only: a Design token cannot mint its own successor.
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray()), auth)
        val sent = body.orEmpty()
        assertTrue(sent.contains("\"name\":\"Atlas\""))
        assertTrue(sent.contains("\"validFor\":\"P365D\""))
    }

    @Test fun createAccessTokenUnauthorizedPointsAtDesign() {
        server.createContext("/design-api/current-user/access-tokens") { ex ->
            respond(ex, 401, "unauthorized".toByteArray())
        }

        val out = DesignClient.createAccessToken(baseUrl(), "user", "secret", "Atlas", "P365D")

        assertTrue(out is DesignClient.Result.Failed)
        val message = (out as DesignClient.Result.Failed).message
        assertTrue(message.contains("SSO"))
        assertTrue(message.contains("create the token in Design"))
    }

    @Test fun mapsUnauthorizedToFriendlyMessage() {
        server.createContext("/design-api/workspaces") { ex -> respond(ex, 401, "unauthorized".toByteArray()) }

        val out = DesignClient.listWorkspaces(conn())

        assertTrue(out is DesignClient.Result.Failed)
        assertTrue((out as DesignClient.Result.Failed).message.contains("Authentication failed"))
    }

    @Test fun exportReturnsZipBytesAndServerFilename() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 42)
        server.createContext("/design-api/workspaces/ws/apps/hr/export") { ex ->
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"HR App.zip\"")
            respond(ex, 200, zip)
        }

        val out = DesignClient.exportApp(conn(), "ws", "hr")

        assertTrue(out is DesignClient.Result.Success)
        val export = (out as DesignClient.Result.Success).value
        assertArrayEquals(zip, export.bytes)
        assertEquals("HR App.zip", export.fileName)
    }

    @Test fun rejectsNonZipExportResponse() {
        server.createContext("/design-api/workspaces/ws/apps/hr/export") { ex ->
            ex.responseHeaders.add("Content-Type", "text/html")
            respond(ex, 200, "<html>SSO login</html>".toByteArray())
        }

        val out = DesignClient.exportApp(conn(), "ws", "hr")

        assertTrue(out is DesignClient.Result.Failed)
        val message = (out as DesignClient.Result.Failed).message
        assertTrue(message.contains("did not return a ZIP"))
        assertTrue(message.contains("text/html"))
    }
}
