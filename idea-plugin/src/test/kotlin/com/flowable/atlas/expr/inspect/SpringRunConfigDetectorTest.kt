package com.flowable.atlas.expr.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringRunConfigDetectorTest {

    private fun hints(vm: String? = null, program: String? = null, env: Map<String, String> = emptyMap(), field: String? = null) =
        SpringRunConfigDetector.fromParameters(vm, program, env, field)

    @Test fun readsProfileAndPortFromVmOptions() {
        val h = hints(vm = "-Dspring.profiles.active=dev -Dserver.port=8090")
        assertEquals(listOf("dev"), h.profiles)
        assertEquals("8090", h.port)
    }

    @Test fun readsProfilePortContextFromProgramArgs() {
        val h = hints(program = "--spring.profiles.active=dev,test --server.port=9000 --server.servlet.context-path=/flowable-work")
        assertEquals(listOf("dev", "test"), h.profiles)
        assertEquals("9000", h.port)
        assertEquals("/flowable-work", h.contextPath)
    }

    @Test fun readsFromEnvironment() {
        val h = hints(env = mapOf("SPRING_PROFILES_ACTIVE" to "prod", "SERVER_PORT" to "8443"))
        assertEquals(listOf("prod"), h.profiles)
        assertEquals("8443", h.port)
    }

    @Test fun readsActiveProfilesField() {
        val h = hints(field = "dev local")
        assertEquals(listOf("dev", "local"), h.profiles)
    }

    @Test fun mergesFieldAndParamProfilesDeduped() {
        val h = hints(vm = "-Dspring.profiles.active=dev", field = "dev,test")
        assertEquals(listOf("dev", "test"), h.profiles)
    }

    @Test fun trimsQuotedValues() {
        val h = hints(vm = "-Dserver.port=\"8090\"")
        assertEquals("8090", h.port)
    }

    @Test fun emptyWhenNoHints() {
        val h = hints()
        assertFalse(h.hasAny)
        assertTrue(h.profiles.isEmpty())
        assertNull(h.port)
        assertNull(h.contextPath)
    }
}
