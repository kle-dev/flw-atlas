package com.flowable.atlas.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AtlasConnectionIdsTest {

    @Test
    fun `an id is the slugified name`() {
        assertEquals("dev1", AtlasConnectionIds.newId("DEV1", emptySet()))
        assertEquals("acme-qa", AtlasConnectionIds.newId("Acme  QA", emptySet()))
    }

    @Test
    fun `two environments named the same get distinct ids`() {
        val first = AtlasConnectionIds.newId("QA", emptySet())
        val second = AtlasConnectionIds.newId("QA", setOf(first))
        val third = AtlasConnectionIds.newId("QA", setOf(first, second))
        assertEquals(listOf("qa", "qa-2", "qa-3"), listOf(first, second, third))
    }

    @Test
    fun `a name of only punctuation still yields a usable id`() {
        assertEquals("connection", AtlasConnectionIds.newId("///", emptySet()))
        assertEquals("connection-2", AtlasConnectionIds.newId("", setOf("connection")))
    }

    @Test
    fun `a connection id names its environment and itself`() {
        assertEquals("dev1-design", AtlasConnectionIds.newConnectionId("DEV1", "Design", emptySet()))
    }

    @Test
    fun `an id never contains the key separator`() {
        // AtlasScopedKeys appends "@<id>" to a property key; a slug that could contain '@' would make
        // that key ambiguous.
        val id = AtlasConnectionIds.newId("a@b.example.com", emptySet())
        assertFalse(id.contains(AtlasConnectionIds.SEPARATOR))
        assertEquals("a-b-example-com", id)
    }
}
