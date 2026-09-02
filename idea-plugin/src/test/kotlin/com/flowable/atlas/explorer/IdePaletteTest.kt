package com.flowable.atlas.explorer

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The colours the explorer is handed by the IDE: nine of them, all `#rrggbb`, in two safe encodings. */
class IdePaletteTest : BasePlatformTestCase() {

    fun testNineColoursInHex() {
        val p = IdePalette.current()
        assertEquals(IdePalette.KEYS, p.keys.toList())
        p.forEach { (k, v) -> assertTrue("$k=$v is not #rrggbb", Regex("^#[0-9a-f]{6}$").matches(v)) }
    }

    fun testJsAndUrlShapes() {
        val p = IdePalette.current()
        val js = IdePalette.toJs(p)
        assertTrue(js, js.startsWith("{\"bg\":\"#") && js.endsWith("\"}") && js.count { it == ':' } == 9)
        assertTrue(IdePalette.toUrlParam(p).matches(Regex("^[0-9a-f]{6}(\\.[0-9a-f]{6}){8}$")))
    }

    fun testRefusesAnythingButHex() {
        // The strings end up inside executeJavaScript and in style properties: a non-colour must never get there.
        val p = IdePalette.current().toMutableMap().apply { this["bg"] = "red" }
        try { IdePalette.toJs(p); fail("expected a refusal") } catch (e: IllegalStateException) { /* expected */ }
    }
}
