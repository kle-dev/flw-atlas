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
 * [DesignClient] network behavior against a local JDK [HttpServer] stub: basic-auth header,
 * pagination aggregation, and the friendly error mapping.
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

    private fun conn() = DesignClient.Connection("http://127.0.0.1:${server.address.port}", "user", "secret")

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

    @Test fun mapsUnauthorizedToFriendlyMessage() {
        server.createContext("/design-api/workspaces") { ex -> respond(ex, 401, "unauthorized".toByteArray()) }

        val out = DesignClient.listWorkspaces(conn())

        assertTrue(out is DesignClient.Result.Failed)
        assertTrue((out as DesignClient.Result.Failed).message.contains("Authentication failed"))
    }

    @Test fun exportReturnsZipBytes() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 42)
        server.createContext("/design-api/workspaces/ws/apps/hr/export") { ex ->
            ex.responseHeaders.add("Content-Type", "application/zip")
            respond(ex, 200, zip)
        }

        val out = DesignClient.exportApp(conn(), "ws", "hr")

        assertTrue(out is DesignClient.Result.Success)
        assertArrayEquals(zip, (out as DesignClient.Result.Success).value)
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
