package com.flowable.atlas.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlsTest {

    @Test
    fun `a design url loses a trailing slash and a pasted design-api suffix`() {
        assertEquals(
            "http://design.example.com/flowable-design",
            BaseUrls.normalize(ConnectionKind.DESIGN, "http://design.example.com/flowable-design/design-api/"),
        )
    }

    @Test
    fun `a work url loses only the trailing slash — design-api is not its suffix`() {
        assertEquals(
            "http://work.example.com/design-api",
            BaseUrls.normalize(ConnectionKind.WORK, " http://work.example.com/design-api/ "),
        )
    }

    @Test
    fun `scheme and host compare case-insensitively, the context path does not`() {
        assertTrue(
            BaseUrls.sameUrl(ConnectionKind.WORK, "HTTP://Work.Example.com/app", "http://work.example.com/app"),
        )
        assertFalse(
            "a context path is case-sensitive on the server, so it must be here too",
            BaseUrls.sameUrl(ConnectionKind.WORK, "http://work.example.com/App", "http://work.example.com/app"),
        )
    }

    @Test
    fun `a blank url matches nothing, not even another blank one`() {
        assertFalse(BaseUrls.sameUrl(ConnectionKind.WORK, "", ""))
    }

    @Test
    fun `the host drops port, path, credentials and case`() {
        assertEquals("design.example.com", BaseUrls.host("https://design.example.com:8443/flowable-design"))
        assertEquals("design.example.com", BaseUrls.host("http://user:pw@Design.Example.com/x"))
        assertEquals("localhost", BaseUrls.host("http://localhost:8888/flowable-design"))
        assertEquals("", BaseUrls.host("not a url"))
    }

    @Test
    fun `loopback hosts are recognised so the import can call them Local`() {
        assertTrue(BaseUrls.isLoopback("localhost"))
        assertTrue(BaseUrls.isLoopback("127.0.0.1"))
        assertFalse(BaseUrls.isLoopback("design.example.com"))
    }

    @Test
    fun `dropping the scheme keeps what tells two local apps apart`() {
        assertEquals("localhost:9914", BaseUrls.withoutScheme("http://localhost:9914"))
        assertEquals("localhost:8080/flowable-work", BaseUrls.withoutScheme("http://localhost:8080/flowable-work/"))
        assertEquals("work-qa.example.com", BaseUrls.withoutScheme("  https://work-qa.example.com/  "))
        // Not a URL at all: shown as typed rather than blanked, since it is what the user has to read.
        assertEquals("nonsense", BaseUrls.withoutScheme("nonsense"))
    }
}
