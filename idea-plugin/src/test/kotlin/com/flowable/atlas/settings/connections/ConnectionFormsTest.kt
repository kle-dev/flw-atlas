package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.SharedEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The detail form builds its credential rows inside a Kotlin-UI-DSL `panel {}` block whose row handles
 * are captured in `lateinit` fields, so construction order matters — and the whole point of the
 * auth-mode dance is that neither secret is lost when the mode is switched. One class serves both
 * kinds now, so each round trip below is also a check that the *other* kind's optional controls
 * (Detect from Project, Create Token…) neither appear nor break construction. Asserted against a real
 * project, off any keychain read on the EDT.
 */
class ConnectionFormsTest : BasePlatformTestCase() {

    private fun draft(): ConnectionsDraft = ConnectionsDraft.from(AtlasEnvironments(), SharedEnvironments())

    fun testTheDesignFormRoundTripsAConnectionThroughTheDraft() {
        val d = draft()
        val env = d.addEnvironment("DEV1")
        val conn = d.addConnection(env.id, ConnectionKind.DESIGN)!!
        conn.baseUrl = "http://design-dev1.example.com/flowable-design"
        conn.username = "demo"
        conn.authMode = AuthMode.ACCESS_TOKEN
        val form = ServerConnectionForm(project, ConnectionKind.DESIGN)
        try {
            form.load(conn)
            form.flush()
            assertEquals("http://design-dev1.example.com/flowable-design", conn.baseUrl)
            assertEquals("demo", conn.username)
            assertEquals("a token-mode connection must not fall back to basic on a round trip",
                AuthMode.ACCESS_TOKEN, conn.authMode)
        } finally {
            Disposer.dispose(form)
        }
    }

    fun testTheDesignFormReportsNothingModifiedForABlankConnection() {
        val d = draft()
        val env = d.addEnvironment("DEV1")
        val conn = d.addConnection(env.id, ConnectionKind.DESIGN)!!
        val form = ServerConnectionForm(project, ConnectionKind.DESIGN)
        try {
            form.load(conn)
            assertFalse("a connection with no URL has no secrets to store", form.secretsModified())
        } finally {
            Disposer.dispose(form)
        }
    }

    fun testTheWorkFormRoundTripsAConnectionThroughTheDraft() {
        val d = draft()
        val env = d.addEnvironment("QA")
        val conn = d.addConnection(env.id, ConnectionKind.WORK)!!
        conn.baseUrl = "http://work-qa.example.com"
        conn.username = "demo"
        val form = ServerConnectionForm(project, ConnectionKind.WORK)
        try {
            form.load(conn)
            form.flush()
            assertEquals("http://work-qa.example.com", conn.baseUrl)
            assertEquals("demo", conn.username)
        } finally {
            Disposer.dispose(form)
        }
    }

    fun testTheEnvironmentFormRoundTripsNameAndProtection() {
        val d = draft()
        val env = d.addEnvironment("PROD")
        env.requireConfirmation = true
        val form = EnvironmentForm(onEditConnection = {}, onAddConnection = {})
        form.load(env, d)
        form.flush()
        assertEquals("PROD", env.name)
        assertTrue(env.requireConfirmation)
    }
}
