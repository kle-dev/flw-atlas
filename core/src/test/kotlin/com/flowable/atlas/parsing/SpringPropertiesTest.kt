package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringPropertiesTest {

    @Test fun configFilesAreApplicationAndBootstrapByProfile() {
        for (n in listOf("application.properties", "application-k8s.yml", "bootstrap.yaml", "Application-Local.PROPERTIES")) {
            assertTrue(n, SpringProperties.isConfigFile(n))
        }
        for (n in listOf("app.properties", "applications.yml", "application.json", "logback.xml")) {
            assertFalse(n, SpringProperties.isConfigFile(n))
        }
    }

    @Test fun propertiesKeysWithTheirLines() {
        val text = """
            # a comment
            crm.endpoint=https://crm
            ! also a comment
            crm.token : secret
            long.value=a \
              continued=not a key
            crm.base-url https://x
        """.trimIndent()
        assertEquals(
            listOf("crm.endpoint" to 2, "crm.token" to 4, "long.value" to 5, "crm.base-url" to 7),
            SpringProperties.keys(text, "application.properties"),
        )
    }

    @Test fun yamlKeysAreFlattenedByIndentation() {
        val text = """
            crm:
              endpoint: https://crm   # inline comment
              auth:
                token: ${'$'}{CRM_TOKEN}
            flowable:
              mail:
                body: |
                  not: a key
                  still: text
                host: smtp
              groups:
                - a
                - b
            ---
            other: 1
        """.trimIndent()
        val keys = SpringProperties.keys(text, "application.yml").map { it.first }
        assertEquals(
            listOf("crm", "crm.endpoint", "crm.auth", "crm.auth.token", "flowable", "flowable.mail",
                "flowable.mail.body", "flowable.mail.host", "flowable.groups", "other"),
            keys,
        )
        assertEquals(4, SpringProperties.keys(text, "application.yml").single { it.first == "crm.auth.token" }.second)
    }
}
