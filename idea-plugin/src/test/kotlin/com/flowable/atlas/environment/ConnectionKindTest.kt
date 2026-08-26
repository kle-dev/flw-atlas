package com.flowable.atlas.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionKindTest {

    @Test
    fun `the persisted names are the identity, so they are pinned`() {
        // These strings are in every user's flowable-atlas-environments.xml. Renaming a constant would
        // drop their connections on the next load, silently — sanitize() throws away an unknown kind.
        assertEquals(
            listOf("DESIGN", "WORK", "CONTROL", "HUB"),
            ConnectionKind.entries.map { it.name },
        )
        ConnectionKind.entries.forEach { assertEquals(it, ConnectionKind.byName(it.name)) }
        assertEquals(ConnectionKind.CONTROL, ConnectionKind.byName("  control  "))
        assertNull("an unknown kind drops the record rather than defaulting", ConnectionKind.byName("ENGAGE"))
        assertNull(ConnectionKind.byName(null))
    }

    @Test
    fun `the servers are the kinds Atlas talks to`() {
        assertEquals(listOf(ConnectionKind.DESIGN, ConnectionKind.WORK), ConnectionKind.SERVERS)
        assertTrue(ConnectionKind.CONTROL.linkOnly)
        assertTrue(ConnectionKind.HUB.linkOnly)
        assertTrue(ConnectionKind.SERVERS.none { it.linkOnly })
    }

    @Test
    fun `the new kinds are appended, so nothing that sorts by ordinal reshuffles`() {
        // The settings tree and the browser chooser both order connections by ordinal; Design and Work
        // have to keep leading, or every environment's rows would rearrange under the user.
        assertEquals(0, ConnectionKind.DESIGN.ordinal)
        assertEquals(1, ConnectionKind.WORK.ordinal)
    }
}
