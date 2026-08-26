package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectSessionTest {

    @Test fun storesAndReadsBackByBaseUrl() {
        InspectSession.set("https://host/flowable-work", mapOf("Cookie" to "SESSION=abc", "X-XSRF-TOKEN" to "def"))
        assertEquals(
            mapOf("Cookie" to "SESSION=abc", "X-XSRF-TOKEN" to "def"),
            InspectSession.get("https://host/flowable-work"),
        )
    }

    @Test fun normalizesTrailingSlashAndWhitespace() {
        InspectSession.set("https://host/app/", mapOf("Cookie" to "SESSION=1"))
        assertEquals(mapOf("Cookie" to "SESSION=1"), InspectSession.get("  https://host/app  "))
    }

    @Test fun emptyMapClearsTheEntry() {
        InspectSession.set("https://host/clear-me", mapOf("Cookie" to "x"))
        InspectSession.set("https://host/clear-me", emptyMap())
        assertNull(InspectSession.get("https://host/clear-me"))
    }

    @Test fun clearRemovesTheEntry() {
        InspectSession.set("https://host/drop", mapOf("Cookie" to "y"))
        InspectSession.clear("https://host/drop")
        assertNull(InspectSession.get("https://host/drop"))
    }

    @Test fun blankBaseUrlIsInert() {
        InspectSession.set("", mapOf("Cookie" to "z"))
        assertNull(InspectSession.get(""))
    }

    @Test fun unknownBaseUrlReturnsNull() {
        assertNull(InspectSession.get("https://never/seen-${'$'}{}"))
    }

    @Test fun basicAuthDecodesWhatThePasteDialogEncoded() {
        val encoded = java.util.Base64.getEncoder().encodeToString("demo:secret".toByteArray())
        InspectSession.set("https://host/basic", mapOf("Authorization" to "Basic $encoded"))
        assertEquals("demo" to "secret", InspectSession.basicAuth("https://host/basic"))
    }

    @Test fun basicAuthKeepsColonsInThePassword() {
        val encoded = java.util.Base64.getEncoder().encodeToString("demo:a:b:c".toByteArray())
        InspectSession.set("https://host/colons", mapOf("Authorization" to "Basic $encoded"))
        assertEquals("demo" to "a:b:c", InspectSession.basicAuth("https://host/colons"))
    }

    @Test fun basicAuthIgnoresEverythingThatIsNotBasic() {
        InspectSession.set("https://host/cookie", mapOf("Cookie" to "SESSION=abc"))
        assertNull("an SSO cookie has no credentials to carry over", InspectSession.basicAuth("https://host/cookie"))
        InspectSession.set("https://host/bearer", mapOf("Authorization" to "Bearer abc.def"))
        assertNull(InspectSession.basicAuth("https://host/bearer"))
        assertNull(InspectSession.basicAuth("https://host/nothing-captured"))
    }

    @Test fun basicAuthSurvivesAHeaderThatIsNotDecodable() {
        InspectSession.set("https://host/junk", mapOf("Authorization" to "Basic not-base64!!"))
        assertNull(InspectSession.basicAuth("https://host/junk"))
    }
}
