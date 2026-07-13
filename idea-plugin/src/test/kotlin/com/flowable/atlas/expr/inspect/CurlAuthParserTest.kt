package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlAuthParserTest {

    @Test fun parsesChromeBashCurlWithCookieAndCsrf() {
        val curl = """
            curl 'https://host.example.com/flowable-work/app-api/foo' \
              -H 'accept: application/json' \
              -H 'cookie: SESSION=abc123; XSRF-TOKEN=tok-9' \
              -H 'x-xsrf-token: tok-9' \
              --data-raw '{"a":1}' \
              --compressed
        """.trimIndent()
        val p = CurlAuthParser.parse(curl)
        assertTrue(p.hasAny)
        assertEquals("SESSION=abc123; XSRF-TOKEN=tok-9", p.headers["Cookie"])
        assertEquals("tok-9", p.headers["X-XSRF-TOKEN"])
        // Non-allowlisted headers are dropped.
        assertNull(p.headers["accept"])
        assertNull(p.headers["Accept"])
        // Best-effort origin (no context path).
        assertEquals("https://host.example.com", p.baseUrl)
    }

    @Test fun canonicalizesHeaderNameCasing() {
        val p = CurlAuthParser.parse("curl 'https://h/x' -H 'COOKIE: s=1' -H 'Authorization: Bearer T'")
        assertEquals("s=1", p.headers["Cookie"])
        assertEquals("Bearer T", p.headers["Authorization"])
    }

    @Test fun readsCookieFromDashBFlag() {
        val p = CurlAuthParser.parse("curl https://h/x -b 'SESSION=fromB'")
        assertEquals("SESSION=fromB", p.headers["Cookie"])
    }

    @Test fun dataRawUrlIsNotMistakenForTarget() {
        val p = CurlAuthParser.parse("curl 'https://real/x' --data-raw 'https://decoy/y' -H 'cookie: s=1'")
        assertEquals("https://real", p.baseUrl)
    }

    @Test fun parsesRawHeaderLines() {
        val text = """
            Cookie: SESSION=raw; XSRF-TOKEN=r
            X-XSRF-TOKEN: r
            Accept: application/json
        """.trimIndent()
        val p = CurlAuthParser.parse(text)
        assertEquals("SESSION=raw; XSRF-TOKEN=r", p.headers["Cookie"])
        assertEquals("r", p.headers["X-XSRF-TOKEN"])
        assertNull(p.headers["Accept"])
        assertNull(p.baseUrl)
    }

    @Test fun bareCookieStringFallback() {
        val p = CurlAuthParser.parse("SESSION=bare; OTHER=2")
        assertEquals("SESSION=bare; OTHER=2", p.headers["Cookie"])
    }

    @Test fun doesNotUrlDecodeCookieValues() {
        val p = CurlAuthParser.parse("curl https://h/x -H 'cookie: t=a+b%20c'")
        assertEquals("t=a+b%20c", p.headers["Cookie"])
    }

    @Test fun emptyInputHasNothing() {
        val p = CurlAuthParser.parse("   ")
        assertFalse(p.hasAny)
        assertNull(p.baseUrl)
    }
}
