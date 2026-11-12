package com.flowable.atlas.environment.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserSessionsTest {

    @Test fun storesAndReadsBackByBaseUrl() {
        BrowserSessions.set("https://host/flowable-work", mapOf("Cookie" to "SESSION=abc", "X-XSRF-TOKEN" to "def"))
        assertEquals(
            mapOf("Cookie" to "SESSION=abc", "X-XSRF-TOKEN" to "def"),
            BrowserSessions.get("https://host/flowable-work"),
        )
    }

    @Test fun normalizesTrailingSlashAndWhitespace() {
        BrowserSessions.set("https://host/app/", mapOf("Cookie" to "SESSION=1"))
        assertEquals(mapOf("Cookie" to "SESSION=1"), BrowserSessions.get("  https://host/app  "))
    }

    @Test fun emptyMapClearsTheEntry() {
        BrowserSessions.set("https://host/clear-me", mapOf("Cookie" to "x"))
        BrowserSessions.set("https://host/clear-me", emptyMap())
        assertNull(BrowserSessions.get("https://host/clear-me"))
    }

    @Test fun clearRemovesTheEntry() {
        BrowserSessions.set("https://host/drop", mapOf("Cookie" to "y"))
        BrowserSessions.clear("https://host/drop")
        assertNull(BrowserSessions.get("https://host/drop"))
    }

    @Test fun blankBaseUrlIsInert() {
        BrowserSessions.set("", mapOf("Cookie" to "z"))
        assertNull(BrowserSessions.get(""))
    }

    @Test fun unknownBaseUrlReturnsNull() {
        assertNull(BrowserSessions.get("https://never/seen-${'$'}{}"))
    }

    @Test fun basicAuthDecodesWhatThePasteDialogEncoded() {
        val encoded = java.util.Base64.getEncoder().encodeToString("demo:secret".toByteArray())
        BrowserSessions.set("https://host/basic", mapOf("Authorization" to "Basic $encoded"))
        assertEquals("demo" to "secret", BrowserSessions.basicAuth("https://host/basic"))
    }

    @Test fun basicAuthKeepsColonsInThePassword() {
        val encoded = java.util.Base64.getEncoder().encodeToString("demo:a:b:c".toByteArray())
        BrowserSessions.set("https://host/colons", mapOf("Authorization" to "Basic $encoded"))
        assertEquals("demo" to "a:b:c", BrowserSessions.basicAuth("https://host/colons"))
    }

    @Test fun basicAuthIgnoresEverythingThatIsNotBasic() {
        BrowserSessions.set("https://host/cookie", mapOf("Cookie" to "SESSION=abc"))
        assertNull("an SSO cookie has no credentials to carry over", BrowserSessions.basicAuth("https://host/cookie"))
        BrowserSessions.set("https://host/bearer", mapOf("Authorization" to "Bearer abc.def"))
        assertNull(BrowserSessions.basicAuth("https://host/bearer"))
        assertNull(BrowserSessions.basicAuth("https://host/nothing-captured"))
    }

    @Test fun basicAuthSurvivesAHeaderThatIsNotDecodable() {
        BrowserSessions.set("https://host/junk", mapOf("Authorization" to "Basic not-base64!!"))
        assertNull(BrowserSessions.basicAuth("https://host/junk"))
    }
}
