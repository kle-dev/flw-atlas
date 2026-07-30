package com.flowable.atlas.design

import com.flowable.atlas.design.DesignPullSelection.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal pull override: serialization and how it layers over the configured default. */
class DesignPullSelectionTest {

    @Test
    fun serializeParseRoundtrip() {
        val s = Selection("ws-1", listOf("appA", "appB"))
        assertEquals(s, DesignPullSelection.parse(DesignPullSelection.serialize(s)))
        // an empty app list is a legitimate override ("pull nothing until I tick something")
        val empty = Selection("ws-1", emptyList())
        assertEquals(empty, DesignPullSelection.parse(DesignPullSelection.serialize(empty)))
        // keys with dashes/dots survive; blanks and separators are cleaned up
        assertEquals(Selection("ws.a-b", listOf("a.b", "c-d")),
            DesignPullSelection.parse("ws.a-b|a.b, ,c-d"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(DesignPullSelection.parse(null))
        assertNull(DesignPullSelection.parse(""))
        assertNull(DesignPullSelection.parse("no-separator"))
        assertNull(DesignPullSelection.parse("|appA"))   // no workspace
    }

    @Test
    fun effectivePrefersTheOverride() {
        val default = Selection("ws-1", listOf("appA"))
        assertEquals(default, DesignPullSelection.effective(default, null))
        val override = Selection("ws-2", listOf("appB", "appC"))
        assertEquals(override, DesignPullSelection.effective(default, override))
    }

    @Test
    fun differsIgnoresAppOrder() {
        val default = Selection("ws-1", listOf("appA", "appB"))
        assertFalse(DesignPullSelection.differsFromDefault(default, null))
        assertFalse("same apps in another order is not a deviation",
            DesignPullSelection.differsFromDefault(default, Selection("ws-1", listOf("appB", "appA"))))
        assertTrue(DesignPullSelection.differsFromDefault(default, Selection("ws-1", listOf("appA"))))
        assertTrue(DesignPullSelection.differsFromDefault(default, Selection("ws-2", listOf("appA", "appB"))))
        assertTrue("clearing every app is a deviation worth keeping",
            DesignPullSelection.differsFromDefault(default, Selection("ws-1", emptyList())))
    }
}
