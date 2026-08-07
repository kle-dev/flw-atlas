package com.flowable.atlas.navigation.se

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.text.Matcher
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders a "Flowable Model" result the way the platform's own tabs do: what you matched on the
 * left, the **file name** right-aligned at the far edge — two [ColoredListCellRenderer]s in a
 * [BorderLayout], the same shape as the platform's text-search and run-configuration renderers.
 *
 * Left is the model **key** (bold, typed fragment highlighted) or, for a full-text hit, the matched
 * line with the found fragment highlighted. Right is the bare file name and nothing else; the
 * archive-qualified path lives in the item's description rather than in the row.
 *
 * Search Everywhere has no grouped list model (results are one flat, weight-sorted list), so the two
 * kinds of row are told apart by their icon rather than by section headers.
 *
 * Typed on `Any` because the platform reuses the renderer for its own synthetic rows.
 */
internal class FlowableModelSeRenderer(
    private val matcher: () -> Matcher?,
) : JPanel(BorderLayout()), ListCellRenderer<Any> {

    private val main = MainRenderer()
    private val fileName = FileNameRenderer()

    init {
        // CENTER, not WEST: BorderLayout grants WEST its full preferred width, so a long matched line
        // would be laid out straight underneath the right-hand file name and the two would overlap.
        // CENTER gets whatever is left over and clips instead.
        add(main, BorderLayout.CENTER)
        add(fileName, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        main.getListCellRendererComponent(list, value, index, selected, hasFocus)
        fileName.getListCellRendererComponent(list, value, index, selected, hasFocus)
        background = if (selected) list.selectionBackground else list.background
        return this
    }

    /** The matched thing: a model key, or the line a full-text hit sits on. */
    private inner class MainRenderer : ColoredListCellRenderer<Any>() {
        override fun customizeCellRenderer(
            list: JList<out Any>,
            value: Any?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            when (val item = value as? FlowableSeItem) {
                is FlowableSeItem.Model -> {
                    icon = MODEL_ICON
                    val m = matcher()
                    if (m == null) {
                        append(item.entry.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    } else {
                        // Ranges come from the matcher's camel-hump half, so a purely infix hit
                        // (`0061` in `KYC-DO-0061`) renders unhighlighted — it still matched.
                        SpeedSearchUtil.appendColoredFragmentForMatcher(
                            item.entry.key, this, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, m, null, selected,
                        )
                    }
                }
                is FlowableSeItem.TextHit -> {
                    icon = TEXT_ICON
                    appendLine(item)
                }
                null -> {}
            }
        }

        /** The matched line with the found fragment highlighted, so the eye lands on it directly. */
        private fun appendLine(hit: FlowableSeItem.TextHit) {
            val end = hit.matchStart + hit.matchLength
            if (hit.matchStart < 0 || end > hit.lineText.length) {
                append(hit.lineText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            append(hit.lineText.substring(0, hit.matchStart), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(hit.lineText.substring(hit.matchStart, end), MATCH_ATTRIBUTES)
            append(hit.lineText.substring(end), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /** The file name — right-aligned and nothing else, so the names line up down the list. */
    private class FileNameRenderer : ColoredListCellRenderer<Any>() {

        init {
            ipad = JBUI.insetsRight(RIGHT_GAP)
        }

        override fun customizeCellRenderer(
            list: JList<out Any>,
            value: Any?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val item = value as? FlowableSeItem ?: return
            append(item.file.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        val MODEL_ICON: Icon = IconLoader.getIcon("/META-INF/atlas-hub.svg", FlowableModelSeRenderer::class.java)
        val TEXT_ICON: Icon = AllIcons.Actions.Find

        /** The platform's search-match styling — the same highlight the other tabs use. */
        val MATCH_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, null)
        const val RIGHT_GAP = 8
    }
}
