package com.flowable.atlas.environment

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name prefilled when a URL is known but the environment is not named yet. */
class EnvironmentNamesTest {

    @Test
    fun `a stage token in the host's first label becomes the name`() {
        assertEquals("QA", EnvironmentNames.suggest("https://qa-design.example.com/flowable-design"))
        assertEquals("DEV1", EnvironmentNames.suggest("https://design-dev1.example.com/flowable-design"))
        assertEquals("PROD", EnvironmentNames.suggest("https://production.example.com/x"))
        assertEquals("UAT", EnvironmentNames.suggest("https://uat.example.com/x"))
        assertEquals("STAGE", EnvironmentNames.suggest("https://staging.example.com/x"))
    }

    @Test
    fun `a stage word buried in a later label is never mistaken for the stage`() {
        // A wrongly authoritative "PROD" on a dev server is worse than a neutral host name.
        assertEquals(
            "design.acme-prod-services.example.com",
            EnvironmentNames.suggest("https://design.acme-prod-services.example.com/x"),
        )
    }

    @Test
    fun `loopback is Local and an unparsable url falls back to a neutral name`() {
        assertEquals("Local", EnvironmentNames.suggest("http://localhost:8888/flowable-design"))
        assertEquals("Local", EnvironmentNames.suggest("http://127.0.0.1:8080"))
        assertEquals("New Environment", EnvironmentNames.suggest("nonsense"))
    }

    @Test
    fun `a host with no stage token is suggested as itself`() {
        assertEquals("design.example.com", EnvironmentNames.suggest("https://design.example.com/flowable-design"))
    }

    @Test
    fun `a name already in use gets a numbered suffix`() {
        assertEquals("QA (2)", EnvironmentNames.suggest("https://qa.example.com/x", setOf("QA")))
        assertEquals("QA (3)", EnvironmentNames.suggest("https://qa.example.com/x", setOf("QA", "QA (2)")))
    }
}
