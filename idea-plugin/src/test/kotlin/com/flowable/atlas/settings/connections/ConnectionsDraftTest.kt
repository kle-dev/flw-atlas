package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsDraftTest {

    private fun draft(): ConnectionsDraft = ConnectionsDraft.from(AtlasEnvironments())

    private fun withQaAndProd(): ConnectionsDraft = draft().apply {
        val qa = addEnvironment("QA")
        addConnection(qa.id, ConnectionKind.WORK)!!.baseUrl = "https://work-qa.example.com"
        val prod = addEnvironment("PROD")
        prod.requireConfirmation = true
        addConnection(prod.id, ConnectionKind.DESIGN)!!.baseUrl = "https://design.example.com"
        addConnection(prod.id, ConnectionKind.WORK)!!.baseUrl = "https://work.example.com"
    }

    @Test
    fun `an environment with only one kind is valid and offers the other`() {
        val d = withQaAndProd()
        val qa = d.environments.first { it.name == "QA" }
        assertNull(d.validate())
        assertEquals(listOf(ConnectionKind.DESIGN), d.freeKinds(qa.id))
        assertTrue(d.anyEnvironmentLacks(ConnectionKind.DESIGN))
        assertFalse(d.anyEnvironmentLacks(ConnectionKind.WORK))
    }

    @Test
    fun `a second connection of the same kind is refused`() {
        val d = withQaAndProd()
        val prod = d.environments.first { it.name == "PROD" }
        assertNull(
            "the slot is taken, and a silent no-op would look like a lost click",
            d.addConnection(prod.id, ConnectionKind.WORK),
        )
        assertEquals(emptyList<ConnectionKind>(), d.freeKinds(prod.id))
    }

    @Test
    fun `a blank name and a duplicate name are both rejected`() {
        val d = draft()
        d.addEnvironment("QA").name = ""
        assertEquals("An environment needs a name.", d.validate())
        d.environments.first().name = "QA"
        d.environments.add(ConnectionsDraft.Env("qa-2", "qa", false))
        assertTrue("names collide case-insensitively", d.validate()!!.contains("already an environment"))
    }

    @Test
    fun `a connection without a url is rejected, naming which one`() {
        val d = draft()
        val dev = d.addEnvironment("DEV")
        d.addConnection(dev.id, ConnectionKind.DESIGN)
        val problem = d.validate()!!
        assertTrue(problem.contains("Design"))
        assertTrue(problem.contains("DEV"))
    }

    @Test
    fun `two environments may point at the same server`() {
        // One Design server with a DEV workspace and a QA workspace. Forbidding this because the two
        // would share a saved password was a rule derived from how credentials are keyed, not from
        // anything about the user's world.
        val d = draft()
        val a = d.addEnvironment("DEV1")
        d.addConnection(a.id, ConnectionKind.DESIGN)!!.baseUrl = "https://design.example.com/flowable-design"
        val b = d.addEnvironment("QA")
        d.addConnection(b.id, ConnectionKind.DESIGN)!!.baseUrl = "https://design.example.com/flowable-design/"
        assertNull(d.validate())
    }

    @Test
    fun `the same url under different kinds is fine`() {
        val d = draft()
        val local = d.addEnvironment("Local")
        d.addConnection(local.id, ConnectionKind.DESIGN)!!.baseUrl = "http://localhost:8080"
        d.addConnection(local.id, ConnectionKind.WORK)!!.baseUrl = "http://localhost:8080"
        assertNull(d.validate())
    }

    @Test
    fun `copying an environment brings its connections and a unique name`() {
        val d = withQaAndProd()
        val prod = d.environments.first { it.name == "PROD" }
        val copy = d.copyEnvironment(prod.id)!!
        assertEquals("PROD (2)", copy.name)
        assertTrue("protection is part of what you are cloning", copy.requireConfirmation)
        assertEquals(
            listOf(ConnectionKind.DESIGN, ConnectionKind.WORK),
            d.connectionsOf(copy.id).map { it.kind },
        )
        // The URLs come along, so the copy immediately works against the same servers — which is what
        // makes it a useful starting point for the next stage rather than an empty shell.
        assertNull(d.validate())
        assertEquals("https://design.example.com", d.connectionsOf(copy.id).first().baseUrl)
    }

    @Test
    fun `removing an environment removes its connections`() {
        val d = withQaAndProd()
        val prod = d.environments.first { it.name == "PROD" }
        d.removeEnvironment(prod.id)
        assertEquals(listOf("QA"), d.environments.map { it.name })
        assertEquals(1, d.connections.size)
    }

    @Test
    fun `environments can be reordered, because the list is a pipeline`() {
        val d = withQaAndProd()
        val prod = d.environments.first { it.name == "PROD" }
        assertTrue(d.moveEnvironment(prod.id, -1))
        assertEquals(listOf("PROD", "QA"), d.environments.map { it.name })
        assertFalse("already at the top", d.moveEnvironment(prod.id, -1))
    }

    @Test
    fun `the snapshot changes with every kind of edit`() {
        val d = withQaAndProd()
        val before = d.snapshot()
        d.environments.first().name = "QA renamed"
        assertFalse(before == d.snapshot())
    }

    @Test
    fun `connections of an environment come out in a fixed order`() {
        val d = draft()
        val dev = d.addEnvironment("DEV")
        d.addConnection(dev.id, ConnectionKind.WORK)!!.baseUrl = "http://work.example.com"
        d.addConnection(dev.id, ConnectionKind.DESIGN)!!.baseUrl = "http://design.example.com"
        assertEquals(
            "the tree must not reshuffle depending on what was added first",
            listOf(ConnectionKind.DESIGN, ConnectionKind.WORK),
            d.connectionsOf(dev.id).map { it.kind },
        )
    }
}
