package com.flowable.atlas.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
