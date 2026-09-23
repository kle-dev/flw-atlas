package com.flowable.atlas.playground

import com.intellij.util.ui.JBUI
import junit.framework.TestCase

/**
 * The strip sits under the expression field, inside splitters that honour minimum sizes. A label's
 * minimum width is its whole text, so one long message or sub-expression row used to pin the divider —
 * after pasting a long `||` expression the payload pane could not be resized any more.
 */
class PlaygroundProblemsStripTest : TestCase() {

    private val longExpression = (1..40).joinToString(" || ") { "status == 'STATE_$it'" }

    fun testALongRowDoesNotWidenTheStrip() {
        val strip = PlaygroundProblemsStrip()
        strip.setRows(listOf(PlaygroundProblemsStrip.Row(true, "Unknown function in $longExpression", 0)), {})
        strip.setSubEvaluations(listOf("$longExpression = true"))
        assertTrue("the strip shows its rows", strip.isVisible)
        assertTrue("its preferred width is the long text", strip.preferredSize.width > JBUI.scale(1000))
        assertTrue("but it never demands that much room", strip.minimumSize.width <= JBUI.scale(40))
    }

    fun testManySubExpressionRowsAreCapped() {
        val strip = PlaygroundProblemsStrip()
        strip.setSubEvaluations((1..30).map { "x$it == $it = false" })
        // eight rows and one "+N more" line, not thirty rows of minimum height
        assertEquals(9, strip.componentCount)
    }
}
