package com.flowable.atlas.playground

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * The frame both playground tabs share: the code on one side, what it runs against and what came out
 * on the other — a fixed two-pane layout on a draggable, remembered splitter.
 *
 * The old panel flipped its splitter's orientation on every resize (`w <= h * 1.3`), so dragging the
 * tool window's edge rearranged the whole thing under the cursor. The orientation is a choice now:
 * side by side when the window is docked at the bottom, stacked when it is docked at the side, and a
 * *Stack Panels* toggle that remembers the reader's own preference per tab.
 */
class PlaygroundShell(
    project: Project,
    private val tabId: String,
    /** What the tool window's dock suggests: stacked for a side dock, side by side for the bottom. */
    private val stackedByDefault: Boolean,
) : SimpleToolWindowPanel(true, true) {

    private val props = PropertiesComponent.getInstance(project)
    private val stackedKey = "flowable.atlas.playground.$tabId.stacked"

    private val side = JBSplitter(true, "flowable.atlas.playground.$tabId.side", 0.5f).apply {
        dividerWidth = JBUI.scale(6)
        setHonorComponentsMinimumSize(true)
    }
    private val main = JBSplitter(stacked, "flowable.atlas.playground.$tabId.split", 0.55f).apply {
        dividerWidth = JBUI.scale(6)
        setHonorComponentsMinimumSize(true)
        secondComponent = side
    }

    /** Editor over context and result (true), or editor beside them (false). Remembered per tab. */
    var stacked: Boolean
        get() = props.getValue(stackedKey)?.toBooleanStrictOrNull() ?: stackedByDefault
        set(value) {
            props.setValue(stackedKey, value.toString())
            main.orientation = value
        }

    init {
        setContent(main)
    }

    fun setEditorSide(component: JComponent) { main.firstComponent = component }
    fun setContext(component: JComponent) { side.firstComponent = component }
    fun setResult(component: JComponent) { side.secondComponent = component }
}
