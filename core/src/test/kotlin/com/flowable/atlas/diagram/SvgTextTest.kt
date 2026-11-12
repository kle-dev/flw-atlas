package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the SVG painters write for a piece of text: escaped, and never a character XML 1.0 forbids. */
class SvgTextTest {

    @Test
    fun theFiveMarkupCharactersAreEscaped() {
        assertEquals("&amp; &lt;b&gt; &quot;x&quot; &#39;y&#39;", SvgText.esc("& <b> \"x\" 'y'"))
    }

    @Test
    fun aControlCharacterXmlForbidsBecomesASpace() {
        assertEquals("Customer name", SvgText.esc("Customer\u000Bname"))
        assertEquals("a b c", SvgText.esc("a\u0001b\u000Cc"))
        assertEquals("x y z", SvgText.esc("x\uFFFEy\uFFFFz"))
    }

    @Test
    fun theWhitespaceXmlAllowsIsKept() {
        assertEquals("a\tb\nc\rd", SvgText.esc("a\tb\nc\rd"))
    }

    @Test
    fun aSurrogatePairIsKeptAndALoneHalfIsNot() {
        assertEquals("ok 😀", SvgText.esc("ok 😀"))
        assertEquals("a b", SvgText.esc("a\uD83Db"))
        assertEquals("a b", SvgText.esc("a\uDE00b"))
        assertEquals("a ", SvgText.esc("a\uD83D"))
    }

    @Test
    fun numbersAreLocaleFreeWithTwoDecimalsAtMost() {
        assertEquals("12", SvgText.fmt(12.0))
        assertEquals("12.35", SvgText.fmt(12.345))
        assertEquals("-0.5", SvgText.fmt(-0.5))
    }
}
