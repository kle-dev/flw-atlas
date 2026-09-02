package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * `--slice`: one node with its context in both directions — the tier between a 3 KB summary and a
 * megabyte graph, which an agent asked to change one process actually needs.
 */
class SliceRendererTest {

    companion object {
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            result = Atlas.extract(File("src/test/resources/miniproject"))
        }
    }

    @Test
    fun aTypedIdRendersTheNodeWithBothDirections() {
        val text = SliceRenderer.render(result, "process:orderProcess")
        assertNotNull(text)
        assertTrue(text!!.startsWith("# `process:orderProcess`"))
        assertTrue("what the node uses", text.contains("## Uses"))
        assertTrue("who uses the node", text.contains("## Used by"))
        assertTrue("the form the process shows is in its context", text.contains("form:orderForm"))
    }

    @Test
    fun aBareKeyMatchesEveryTypeThatCarriesIt() {
        val text = SliceRenderer.render(result, "orderProcess")
        assertNotNull("a bare key must not require the reader to know the type", text)
        assertEquals("one process carries the key, one heading", 1, Regex("^# `", RegexOption.MULTILINE).findAll(text!!).count())
    }

    @Test
    fun anUnknownTargetIsNull() {
        assertNull(SliceRenderer.render(result, "process:doesNotExist"))
        assertNull(SliceRenderer.render(result, "nothing-has-this-key"))
    }
}
