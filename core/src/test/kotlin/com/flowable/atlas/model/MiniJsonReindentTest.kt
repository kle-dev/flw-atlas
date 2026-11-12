package com.flowable.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Laying out a minified model export so it can be read beside a formatted file — and the one property
 * that makes it usable for that: nothing but whitespace is allowed to change. A Design export is a
 * single 18 KB line, so the alternative to this is a comparison in which every line differs.
 */
class MiniJsonReindentTest {

    /** Everything outside a string literal is whitespace-insignificant, so stripping it from both
     *  sides catches any character that was added, dropped or rewritten. */
    private fun assertOnlyWhitespaceChanged(input: String) {
        val out = MiniJson.reindent(input)!!
        assertEquals(
            input.filterNot { it.isWhitespace() },
            out.filterNot { it.isWhitespace() },
        )
    }

    @Test fun aMinifiedObjectBecomesOneTokenPerLine() {
        assertEquals(
            """
            {
              "key": "DEMO-F001",
              "name": "Demo"
            }
            """.trimIndent(),
            MiniJson.reindent("""{"key":"DEMO-F001","name":"Demo"}"""),
        )
    }

    @Test fun nestingIndentsAndEmptyContainersStayInline() {
        assertEquals(
            """
            {
              "rows": [
                {
                  "cols": [],
                  "meta": {}
                }
              ],
              "version": 1
            }
            """.trimIndent(),
            MiniJson.reindent("""{"rows":[{"cols":[],"meta":{ }}],"version":1}"""),
        )
    }

    @Test fun numbersKeepTheirExactSpelling() {
        // The reason this is token-level and not parse-then-print: a round trip through Double turns
        // `1` into `1.0` and a long id into scientific notation.
        assertEquals(
            """
            [
              1,
              1.50,
              1e-5,
              20250612120000
            ]
            """.trimIndent(),
            MiniJson.reindent("[1,1.50,1e-5,20250612120000]"),
        )
    }

    @Test fun stringsAreCopiedThroughUntouched() {
        val input = """{"label":"a, b: {c} \"quoted\" \\ end","unicode":"Grüezi ✅"}"""
        val out = MiniJson.reindent(input)!!
        assertEquals(
            """
            {
              "label": "a, b: {c} \"quoted\" \\ end",
              "unicode": "Grüezi ✅"
            }
            """.trimIndent(),
            out,
        )
        assertOnlyWhitespaceChanged(input)
    }

    @Test fun anAlreadyFormattedFileComesBackUnchanged() {
        val once = MiniJson.reindent("""{"a":{"b":[1,"two",null,true]}}""")!!
        assertEquals(once, MiniJson.reindent(once))
    }

    @Test fun aRealisticExportChangesInWhitespaceOnly() {
        assertOnlyWhitespaceChanged(
            """{"id":"FORM_MODEL-1","name":"Demo form 6.039","key":"DEMO-F001","description":"",""" +
                """"tenantId":"default","editorJson":{"resourceId":"editor_FORM_MODEL-1",""" +
                """"stencil":{"id":"XForm"},"childShapes":[{"bounds":{"upperLeft":{"x":15,"y":25}}}]}}""",
        )
    }

    @Test fun aLeadingByteOrderMarkIsNotAnObstacle() {
        assertEquals("{\n  \"a\": 1\n}", MiniJson.reindent("\uFEFF{\"a\":1}"))
    }

    @Test fun whatItRefusesToGuessAt() {
        assertNull("an unterminated string", MiniJson.reindent("""{"a":"unclosed"""))
        assertNull("a missing closer", MiniJson.reindent("""{"a":[1,2}"""))
        assertNull("brackets left open", MiniJson.reindent("""{"a":1"""))
        assertNull("trailing content", MiniJson.reindent("""{"a":1} and then some"""))
        assertNull("a scalar, not a model", MiniJson.reindent("\"just a string\""))
        assertNull("nothing at all", MiniJson.reindent("   "))
    }
}
