package com.flowable.atlas.navigation.se

import com.flowable.atlas.icons.AtlasIcons
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.MinusculeMatcher
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
 * Left is the model **key** (bold, typed fragment highlighted), an element's **id** with its model in
 * grey (`approveTask  in DEMO-P001`) or, for a full-text hit, the matched line with the found fragment
 * highlighted. Right is the bare file name and nothing else; the
 * archive-qualified path lives in the item's description rather than in the row.
 *
 * Search Everywhere has no grouped list model (results are one flat, weight-sorted list), so the two
 * kinds of row are told apart by their icon rather than by section headers.
 *
 * Typed on `Any` because the platform reuses the renderer for its own synthetic rows.
 */
internal class FlowableModelSeRenderer(
    private val highlight: () -> SeHighlight?,
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
        // What the row leaves out — type, name, archive — one hover away.
        toolTipText = (value as? FlowableSeItem)?.description
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
                    icon = AtlasIcons.forType(item.entry.type)
                    appendKey(item.entry.key, highlight())
                }
                is FlowableSeItem.Element -> {
                    icon = item.element.kind.icon
                    appendKey(item.element.id, highlight())
                    append("  in ${item.element.owner.key}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FlowableSeItem.TextHit -> {
                    icon = TEXT_ICON
                    appendLine(item)
                }
                null -> {}
            }
        }

        /**
         * The key with the typed fragment highlighted. The minuscule matcher reports ranges for its
         * camel-hump matches; a purely infix hit (`0061` in `DEMO-DO-0061`) matched through the `*`
         * prefix but comes back without ranges, so it used to render unhighlighted — the one row the
         * reader typed for looked like the one that merely happened to be there. The ranges are painted
         * here rather than by the platform helper, whose highlighting depends on a registry flag.
         */
        private fun appendKey(key: String, h: SeHighlight?) {
            val base = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            if (h == null) return append(key, base)
            var ranges: List<TextRange> = h.matcher.match(key)?.map { TextRange(it.startOffset, it.endOffset) }.orEmpty()
            if (ranges.isEmpty()) {
                val at = key.indexOf(h.pattern, ignoreCase = true)
                if (at >= 0) ranges = listOf(TextRange(at, at + h.pattern.length))
            }
            if (ranges.isEmpty()) return append(key, base)
            var pos = 0
            for (r in ranges.sortedBy { it.startOffset }) {
                if (r.startOffset > pos) append(key.substring(pos, r.startOffset), base)
                append(key.substring(r.startOffset, r.endOffset), matchOf(base))
                pos = r.endOffset
            }
            if (pos < key.length) append(key.substring(pos), base)
        }

        /** The matched line with the found fragment highlighted, so the eye lands on it directly. */
        private fun appendLine(hit: FlowableSeItem.TextHit) {
            val end = hit.matchStart + hit.matchLength
            if (hit.matchStart < 0 || end > hit.lineText.length) {
                append(hit.lineText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            appendWithMatch(hit.lineText, hit.matchStart, hit.matchLength, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }

        /** [text] as three fragments — before, the match, after — in [base] with the match styled. */
        private fun appendWithMatch(text: String, start: Int, length: Int, base: SimpleTextAttributes) {
            val end = start + length
            append(text.substring(0, start), base)
            append(text.substring(start, end), matchOf(base))
            append(text.substring(end), base)
        }

        /** The platform's search-match styling on top of [base] — the same highlight the other tabs use. */
        private fun matchOf(base: SimpleTextAttributes) =
            SimpleTextAttributes(base.style or SimpleTextAttributes.STYLE_SEARCH_MATCH, base.fgColor)
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
        val TEXT_ICON: Icon = AllIcons.Actions.Find

        const val RIGHT_GAP = 8
    }
}

/**
 * The live pattern for the renderer: the matcher for the ranges it can report, the raw text for the
 * infix hits it cannot.
 */
internal class SeHighlight(val pattern: String, val matcher: MinusculeMatcher)
