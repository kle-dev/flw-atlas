package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.eval.PayloadScopePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PayloadJsonPathsTest : BasePlatformTestCase() {

    private fun pathAtCaret(json: String): PayloadScopePath? {
        val file = myFixture.configureByText("payload.json", json)
        return PayloadJsonPaths.pathAt(file, myFixture.caretOffset)
    }

    fun testCaretInsideArrayItemValue() {
        val path = pathAtCaret("""{"orders":[{"a":1},{"items":[{"qty":<caret>3}]}]}""")
        assertEquals("orders[1].items[0].qty", path!!.format())
    }

    fun testCaretInsideItemObjectMapsToTheItem() {
        val path = pathAtCaret("""{"orders":[{"a":1},{ <caret> "items":[]}]}""")
        assertEquals("orders[1]", path!!.format())
    }

    fun testCaretOnPropertyKeyMapsToItsValue() {
        val path = pathAtCaret("""{"orders":[{"it<caret>ems":[{"qty":3}]}]}""")
        assertEquals("orders[0].items", path!!.format())
    }

    fun testCaretAtTopLevelIsRoot() {
        val path = pathAtCaret("""{<caret>"orders":[]}""")
        assertTrue(path!!.isRoot)
    }

    fun testRangeOfRoundTrip() {
        val json = """{"orders":[{"a":1},{"items":[{"qty":<caret>3}]}]}"""
        val file = myFixture.configureByText("payload.json", json)
        val path = PayloadJsonPaths.pathAt(file, myFixture.caretOffset)!!
        val range = PayloadJsonPaths.rangeOf(file, path)!!
        assertEquals("3", range.substring(file.text))

        val itemPath = (PayloadScopePath.parse("orders[1].items[0]") as PayloadScopePath.ParseResult.Ok).path
        val itemRange = PayloadJsonPaths.rangeOf(file, itemPath)!!
        assertEquals("""{"qty":3}""", itemRange.substring(file.text))
    }

    fun testRangeOfStalePathIsNull() {
        val file = myFixture.configureByText("payload.json", """{"orders":[]}""")
        val missingKey = (PayloadScopePath.parse("missing[0]") as PayloadScopePath.ParseResult.Ok).path
        assertNull(PayloadJsonPaths.rangeOf(file, missingKey))
        val outOfBounds = (PayloadScopePath.parse("orders[2]") as PayloadScopePath.ParseResult.Ok).path
        assertNull(PayloadJsonPaths.rangeOf(file, outOfBounds))
    }
}
