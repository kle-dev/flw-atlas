package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMaskTest {

    @Test
    fun onlyTheInterpolationsInsideAScriptAreBlanked() {
        val raw = """{"label":"${'$'}{keep}","script":"def n = \"${'$'}{user?.firstName}\" + {{alsoKeep}}","after":"${'$'}{keepToo}"}"""
        val out = ScriptMask.mask(raw, xml = false)
        assertEquals(raw.length, out.length)
        assertTrue(out.contains("\${keep}") && out.contains("\${keepToo}") && out.contains("{{alsoKeep}}"))
        assertFalse(out.contains("user?.firstName"))
    }

    @Test
    fun aVeryLongScriptDoesNotBlowTheStack() {
        // a 40 KB Groovy body inside one JSON string: the shape that overflowed the regex engine on a real project
        val body = (1..2000).joinToString("\\n") { "def v$it = \\\"\${'$'}{x$it}\\\" + 'literal text here'" }
        val raw = """{"key":"a","config":{"scriptInfo":{"language":"groovy","script":"$body"}}}"""
        val out = ScriptMask.mask(raw, xml = false)
        assertEquals(raw.length, out.length)
        assertFalse(out.contains("\${x1999}"))
    }

    @Test
    fun xmlScriptBodiesAreBlankedToo() {
        val raw = """<process id="p"><scriptTask id="s"><script><![CDATA[def s = "${'$'}{a}"]]></script></scriptTask><userTask flowable:assignee="${'$'}{b}"/></process>"""
        val out = ScriptMask.mask(raw, xml = true)
        assertFalse(out.contains("\${a}")); assertTrue(out.contains("\${b}"))
    }
}
