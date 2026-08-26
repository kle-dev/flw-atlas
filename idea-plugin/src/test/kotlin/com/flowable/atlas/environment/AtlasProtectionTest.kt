package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasProtectionTest {

    private fun connection(id: String, env: String, url: String, kind: ConnectionKind, protected: Boolean) =
        AtlasConnection(id, kind, url, "demo", AuthMode.BASIC, "e-$id", env, protected)

    private val prodWork = connection("prod-work", "PROD", "https://work.example.com", ConnectionKind.WORK, true)
    private val devWork = connection("dev-work", "DEV", "http://localhost:8080", ConnectionKind.WORK, false)

    @Test
    fun `a url pointing into a protected environment is guarded even when another one is selected`() {
        // The rule that cannot be walked around: the playground lets the user type a base URL, so a
        // guard keyed on the *selected* connection would be bypassed by typing PROD's URL while DEV
        // is picked.
        assertEquals(
            prodWork,
            AtlasProtection.protecting("https://work.example.com/", ConnectionKind.WORK, listOf(devWork, prodWork)),
        )
    }

    @Test
    fun `an unprotected environment is not guarded`() {
        assertNull(AtlasProtection.protecting("http://localhost:8080", ConnectionKind.WORK, listOf(devWork, prodWork)))
    }

    @Test
    fun `a url matching nothing in the catalog is not guarded`() {
        assertNull(AtlasProtection.protecting("https://elsewhere.example.com", ConnectionKind.WORK, listOf(prodWork)))
    }

    @Test
    fun `the kind is part of the lookup`() {
        val prodDesign = connection("prod-design", "PROD", "https://work.example.com", ConnectionKind.DESIGN, true)
        assertNull(AtlasProtection.protecting("https://work.example.com", ConnectionKind.WORK, listOf(prodDesign)))
    }

    @Test
    fun `both messages name the environment and what is about to happen`() {
        assertTrue(AtlasProtection.evaluateMessage(prodWork).contains("PROD"))
        assertTrue(AtlasProtection.evaluateMessage(prodWork).contains("bean methods"))
        val pull = AtlasProtection.pullMessage(prodWork, "flowable-models")
        assertTrue(pull.contains("PROD"))
        assertTrue("say which files are at stake", pull.contains("flowable-models/"))
    }
}
