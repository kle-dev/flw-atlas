package com.flowable.atlas.environment

import com.flowable.atlas.design.DesignAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkConnectionMatcherTest {

    private fun work(id: String, url: String) =
        AtlasConnection(id, ConnectionKind.WORK, url, "demo", DesignAuthMode.BASIC, "e-$id", id.uppercase(), false)

    private fun design(id: String, url: String) =
        AtlasConnection(id, ConnectionKind.DESIGN, url, "demo", DesignAuthMode.BASIC, "e-$id", id.uppercase(), false)

    private val qa = work("qa", "https://work-qa.example.com/flowable-work")
    private val prod = work("prod", "https://work.example.com/flowable-work")

    @Test
    fun `an exact url matches, ignoring a trailing slash and host case`() {
        assertEquals(qa, WorkConnectionMatcher.match("https://Work-QA.example.com/flowable-work/", listOf(qa, prod)))
    }

    @Test
    fun `the default port is the same server as no port`() {
        val plain = work("dev", "http://work-dev.example.com")
        assertEquals(plain, WorkConnectionMatcher.match("http://work-dev.example.com:80", listOf(plain)))
    }

    @Test
    fun `a different port is a different app`() {
        val eight = work("a", "http://localhost:8080")
        assertNull(WorkConnectionMatcher.match("http://localhost:9090", listOf(eight)))
    }

    @Test
    fun `http and https are not the same app`() {
        assertNull(WorkConnectionMatcher.match("http://work-qa.example.com/flowable-work", listOf(qa)))
    }

    @Test
    fun `a context path added by the pasted link still matches its host-only connection`() {
        // The common shape: the connection was saved as the bare host, the pasted Work link carries
        // the app's context path.
        val bare = work("dev", "https://work-dev.example.com")
        assertEquals(bare, WorkConnectionMatcher.match("https://work-dev.example.com/flowable-work", listOf(bare)))
    }

    @Test
    fun `a prefix only counts at a path boundary`() {
        val app = work("a", "https://host.example.com/app")
        assertNull("…/app must not swallow …/app2", WorkConnectionMatcher.match("https://host.example.com/app2", listOf(app)))
    }

    @Test
    fun `the most specific connection wins on a host serving two apps`() {
        val host = work("host", "https://host.example.com")
        val app = work("app", "https://host.example.com/flowable-work")
        assertEquals(app, WorkConnectionMatcher.match("https://host.example.com/flowable-work/x", listOf(host, app)))
    }

    @Test
    fun `design connections are never matched`() {
        assertNull(WorkConnectionMatcher.match("https://design.example.com", listOf(design("d", "https://design.example.com"))))
    }

    @Test
    fun `a blank or unknown url matches nothing`() {
        assertNull(WorkConnectionMatcher.match(null, listOf(qa)))
        assertNull(WorkConnectionMatcher.match("", listOf(qa)))
        assertNull(WorkConnectionMatcher.match("https://elsewhere.example.com", listOf(qa)))
    }
}
