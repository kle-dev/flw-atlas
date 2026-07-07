package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectConnectionDetectorTest {

    @Test fun buildsBaseUrlFromPortAndContextPath() {
        val c = InspectConnectionDetector.detect(
            listOf("server.port=8090\nserver.servlet.context-path=/flowable-work"),
        )
        assertEquals("http://localhost:8090/flowable-work", c.baseUrl)
    }

    @Test fun readsIdmAdminCredentials() {
        val c = InspectConnectionDetector.detect(
            listOf("flowable.common.app.idm-admin.user=admin\nflowable.common.app.idm-admin.password=test"),
        )
        assertEquals("admin", c.username)
        assertEquals("test", c.password)
    }

    @Test fun fallsBackToRestAdminCredentials() {
        val c = InspectConnectionDetector.detect(
            listOf("flowable.rest.app.admin.user-id=rest-admin\nflowable.rest.app.admin.password=test"),
        )
        assertEquals("rest-admin", c.username)
        assertEquals("test", c.password)
    }

    @Test fun laterFilesOverrideEarlier() {
        val c = InspectConnectionDetector.detect(
            listOf(
                "flowable.common.app.idm-admin.user=admin\nserver.port=8080",   // defaults
                "server.port=9999",                                              // application.properties
            ),
        )
        assertEquals("http://localhost:9999", c.baseUrl)
        assertEquals("admin", c.username)
    }

    @Test fun placeholderPasswordIsNotUsed() {
        val c = InspectConnectionDetector.detect(
            listOf("flowable.common.app.idm-admin.user=admin\nflowable.common.app.idm-admin.password=\${ADMIN_PW}"),
        )
        assertEquals("admin", c.username)
        assertNull("secret/env password must not be surfaced", c.password)
    }

    @Test fun toleratesColonSeparatorAndComments() {
        val c = InspectConnectionDetector.detect(
            listOf("# admin\nflowable.common.app.idm-admin.user: admin\n! note\nserver.port : 8080"),
        )
        assertEquals("admin", c.username)
        assertEquals("http://localhost:8080", c.baseUrl)
    }

    @Test fun emptyWhenNothingRelevant() {
        val c = InspectConnectionDetector.detect(listOf("spring.application.name=demo"))
        assertNull(c.baseUrl)
        assertNull(c.username)
        assertNull(c.password)
    }

    @Test fun runConfigPortOverrideWinsOverConfigFiles() {
        val c = InspectConnectionDetector.detect(
            listOf("server.port=8080\nserver.servlet.context-path=/work"),
            portOverride = "9090",
        )
        assertEquals("http://localhost:9090/work", c.baseUrl)
    }

    @Test fun runConfigContextPathOverrideApplies() {
        val c = InspectConnectionDetector.detect(
            listOf("server.port=8080"),
            contextPathOverride = "flowable-work",   // no leading slash → normalised
        )
        assertEquals("http://localhost:8080/flowable-work", c.baseUrl)
    }

    @Test fun portOverrideAloneProducesBaseUrl() {
        val c = InspectConnectionDetector.detect(emptyList(), portOverride = "8888")
        assertEquals("http://localhost:8888", c.baseUrl)
    }
}
