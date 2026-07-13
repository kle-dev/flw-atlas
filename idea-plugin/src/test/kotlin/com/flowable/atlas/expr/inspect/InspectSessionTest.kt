package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectSessionTest {

    @Test fun storesAndReadsBackByBaseUrl() {
        InspectSession.set("https://host/flowable-work", "SESSION=abc; XSRF=def")
        assertEquals("SESSION=abc; XSRF=def", InspectSession.get("https://host/flowable-work"))
    }

    @Test fun normalizesTrailingSlashAndWhitespace() {
        InspectSession.set("https://host/app/", "SESSION=1")
        assertEquals("SESSION=1", InspectSession.get("  https://host/app  "))
    }

    @Test fun blankHeaderClearsTheEntry() {
        InspectSession.set("https://host/clear-me", "SESSION=x")
        InspectSession.set("https://host/clear-me", "")
        assertNull(InspectSession.get("https://host/clear-me"))
    }

    @Test fun clearRemovesTheEntry() {
        InspectSession.set("https://host/drop", "SESSION=y")
        InspectSession.clear("https://host/drop")
        assertNull(InspectSession.get("https://host/drop"))
    }

    @Test fun blankBaseUrlIsInert() {
        InspectSession.set("", "SESSION=z")
        assertNull(InspectSession.get(""))
    }

    @Test fun unknownBaseUrlReturnsNull() {
        assertNull(InspectSession.get("https://never/seen-${'$'}{}"))
    }
}
