package com.flowable.atlas.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DesignClientTest {

    @Test fun normalizesBaseUrl() {
        assertEquals("http://host:8888/flowable-design", DesignClient.normalizeBaseUrl("http://host:8888/flowable-design"))
        assertEquals("http://host:8888/flowable-design", DesignClient.normalizeBaseUrl("http://host:8888/flowable-design/"))
        assertEquals("http://host:8888/flowable-design", DesignClient.normalizeBaseUrl("http://host:8888/flowable-design/design-api"))
        assertEquals("http://host:8888/flowable-design", DesignClient.normalizeBaseUrl("http://host:8888/flowable-design/design-api/"))
        assertEquals("https://host", DesignClient.normalizeBaseUrl("  https://host/  "))
    }

    @Test fun buildsEndpoints() {
        assertEquals(
            "https://host/design-api/workspaces?start=0&size=200",
            DesignClient.workspacesEndpoint("https://host/"),
        )
        assertEquals(
            "https://host/design-api/workspaces/ws/apps?start=200&size=200",
            DesignClient.appsEndpoint("https://host", "ws", start = 200),
        )
        assertEquals(
            "https://host/design-api/workspaces/ws/apps/myApp/export",
            DesignClient.exportEndpoint("https://host", "ws", "myApp"),
        )
        assertEquals(
            "https://host/design-api/current-user/access-tokens",
            DesignClient.accessTokensEndpoint("https://host/design-api/"),
        )
        assertEquals(
            "http://host:8888/flowable-design/#/token-mgmt",
            DesignClient.tokenManagementUrl("http://host:8888/flowable-design/"),
        )
    }

    @Test fun normalizesPastedAccessToken() {
        assertEquals("eyJ.a.b", DesignClient.normalizeAccessToken("  eyJ.a.b \n"))
        assertEquals("eyJ.a.b", DesignClient.normalizeAccessToken("Bearer eyJ.a.b"))
        assertEquals("eyJ.a.b", DesignClient.normalizeAccessToken("bearer   eyJ.a.b"))
        assertEquals("eyJ.a.b", DesignClient.normalizeAccessToken("\"eyJ.a.b\""))
        assertEquals("", DesignClient.normalizeAccessToken("   "))
    }

    @Test fun unauthorizedMessageIsModeSpecific() {
        val basic = DesignClient.unauthorizedMessage(DesignClient.Auth.Basic("u", "p"))
        assertTrue(basic.contains("username/password"))
        val token = DesignClient.unauthorizedMessage(DesignClient.Auth.Token("t"))
        assertTrue(token.contains("access token"))
        assertTrue(token.contains("expired"))
        assertFalse(token.contains("username"))
    }

    @Test fun authorizationHeaderPerScheme() {
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray()),
            DesignClient.authorizationHeader(DesignClient.Auth.Basic("u", "p")),
        )
        // A pasted "Bearer …" must not double up.
        assertEquals("Bearer T0K", DesignClient.authorizationHeader(DesignClient.Auth.Token("Bearer T0K")))
    }

    @Test fun parsesNewToken() {
        val token = DesignClient.parseNewToken(
            """{"id":"1","name":"Atlas","expirationTime":"2027-01-01T00:00:00Z","value":"T0K"}""",
        )
        assertEquals("T0K", token?.value)
        assertEquals("Atlas", token?.name)
        assertEquals("2027-01-01T00:00:00Z", token?.expirationTime)
        assertNull(DesignClient.parseNewToken("""{"id":"1","name":"Atlas"}"""))   // no value → not usable
        assertNull(DesignClient.parseNewToken("<html>login</html>"))
    }

    @Test fun encodesUrlHostileKeysInPathSegments() {
        assertEquals(
            "https://host/design-api/workspaces/my%20ws/apps/a%2Fb/export",
            DesignClient.exportEndpoint("https://host", "my ws", "a/b"),
        )
    }

    @Test fun parsesWorkspacePage() {
        val page = DesignClient.parseWorkspacePage(
            """{"data":[{"key":"default","name":"Default Workspace"},{"key":"bare"}],"total":2,"start":0,"size":10}""",
        )
        assertEquals(2, page.total)
        assertEquals(listOf(
            DesignClient.Workspace("default", "Default Workspace"),
            DesignClient.Workspace("bare", "bare"),   // name falls back to the key
        ), page.data)
    }

    @Test fun parsesAppPageWithOptionalFields() {
        val page = DesignClient.parseAppPage(
            """{"data":[
                 {"key":"hr","name":"HR App","version":7,"lastUpdated":"2026-07-01T10:00:00.000Z"},
                 {"key":"minimal"}
               ],"total":30,"start":0,"size":2}""",
        )
        assertEquals(30, page.total)   // more pages than this one
        assertEquals(DesignClient.App("hr", "HR App", 7, "2026-07-01T10:00:00.000Z"), page.data[0])
        assertEquals("minimal", page.data[1].name)
        assertNull(page.data[1].version)
        assertNull(page.data[1].lastUpdated)
    }

    @Test fun pageTotalFallsBackToDataSize() {
        val page = DesignClient.parseWorkspacePage("""{"data":[{"key":"ws"}]}""")
        assertEquals(1, page.total)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonObjectResponse() {
        DesignClient.parseWorkspacePage("<html>login</html>")
    }

    @Test fun listWorkspacesRejectsBlankBaseUrl() {
        val out = DesignClient.listWorkspaces(DesignClient.Connection("", "u", "p"))
        assertTrue(out is DesignClient.Result.Failed)
        assertTrue((out as DesignClient.Result.Failed).message.contains("base URL", ignoreCase = true))
    }

    @Test fun exportRejectsBlankBaseUrl() {
        val out = DesignClient.exportApp(DesignClient.Connection("  ", "u", "p"), "ws", "app")
        assertTrue(out is DesignClient.Result.Failed)
    }

    /** A blank token must fail before a socket is opened — the URL here is never reachable. */
    @Test fun listWorkspacesRejectsBlankAccessToken() {
        val out = DesignClient.listWorkspaces(DesignClient.Connection("https://host", DesignClient.Auth.Token("  ")))
        assertTrue(out is DesignClient.Result.Failed)
        assertTrue((out as DesignClient.Result.Failed).message.contains("access token"))
    }

    @Test fun parsesContentDispositionFilename() {
        val f = DesignClient::parseContentDispositionFilename
        assertNull(f(null))
        assertNull(f(""))
        assertNull(f("attachment"))                                            // no filename param
        assertEquals("HR App.zip", f("""attachment; filename="HR App.zip""""))  // quoted
        assertEquals("HRApp.zip", f("attachment; filename=HRApp.zip"))          // bare token
        assertEquals("HR App.zip", f("attachment; filename*=UTF-8''HR%20App.zip"))   // RFC 5987, percent-decoded
        assertEquals("a+b.zip", f("attachment; filename*=UTF-8''a+b.zip"))      // literal '+', NOT a space
        // The extended form wins over a plain fallback (RFC 6266).
        assertEquals("real.zip", f("""attachment; filename="fallback.zip"; filename*=UTF-8''real.zip"""))
        // A non-UTF charset in the extended form is honored.
        val eAcute = String(byteArrayOf(0xE9.toByte()), Charsets.ISO_8859_1)
        assertEquals("$eAcute.zip", f("attachment; filename*=ISO-8859-1''%E9.zip"))
    }
}
