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
}
