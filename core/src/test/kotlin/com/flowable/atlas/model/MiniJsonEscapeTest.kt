package com.flowable.atlas.model

import com.flowable.atlas.render.ExplorerHtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The compact writer feeds the explorer's data island and `graph.json`. A raw control character is
 * invalid inside a JSON string, and one pasted description (a vertical tab from Word) left the whole
 * explorer page blank because `JSON.parse` refused it.
 */
class MiniJsonEscapeTest {

    private val nasty = "a\u000bb\u000cc\u0000d\u001fe\bf\n\t\"\\"

    @Test fun everyControlCharacterIsEscaped() {
        val out = MiniJson.stringify(mapOf("description" to nasty))
        assertFalse("raw control character in $out", out.any { it < ' ' })
        assertEquals(mapOf("description" to nasty), MiniJson.parse(out))
    }

    @Test fun theDataIslandCarriesNoLessThanSign() {
        val payload = mapOf("html" to "<!--<script>x</script>-->", "k" to nasty)
        val island = ExplorerHtmlRenderer.dataIsland(payload)
        assertFalse("a '<' can open or close a tag inside the island: $island", island.contains('<'))
        assertEquals(payload, MiniJson.parse(island))
    }
}
