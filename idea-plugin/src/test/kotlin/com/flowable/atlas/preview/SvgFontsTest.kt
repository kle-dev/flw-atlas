package com.flowable.atlas.preview

import junit.framework.TestCase

/** A font list narrows to its first installed family, because JSVG only ever tries the first. */
class SvgFontsTest : TestCase() {

    private val list = """font-family="'Segoe UI', 'Helvetica Neue', Arial, sans-serif""""

    fun testTheFirstInstalledFamilyWins() {
        assertEquals("""font-family="Arial"""", SvgFonts.resolvable(list, setOf("arial")))
        assertEquals("""font-family="Helvetica Neue"""", SvgFonts.resolvable(list, setOf("arial", "helvetica neue")))
    }

    fun testNothingInstalledFallsBackToTheGenericFamily() {
        assertEquals("""font-family="sans-serif"""", SvgFonts.resolvable(list, emptySet()))
        assertEquals("""font-family="monospace"""", SvgFonts.resolvable("""font-family="'JetBrains Mono', monospace"""", emptySet()))
        assertEquals("""font-family="sans-serif"""", SvgFonts.resolvable("""font-family="Nope"""", emptySet()))
    }

    fun testSemiBoldIsDrawnBold() {
        assertEquals("""<text font-weight="bold">""", SvgFonts.resolvable("""<text font-weight="600">""", emptySet()))
    }
}
