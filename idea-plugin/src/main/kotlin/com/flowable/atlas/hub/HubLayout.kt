package com.flowable.atlas.hub

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListModel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * What keeps the Hub narrow: it lives in a side stripe, and it used to be laid out at the width its
 * widest row *asked* for — a header, a button pair and an environment row that each wanted 450–570 px —
 * so the stripe had to be dragged half across the screen before nothing was cut off. The rule now is the
 * other way round: the panel is as wide as the stripe, every row is built to fit 280 px, and whatever
 * width there is beyond that goes to the names.
 *
 * Three parts make that hold: the content tracks the viewport's width ([WidthTracking]), anything that
 * stretches asks for a small width and fills the rest ([narrow]), and a row of two facts gives the name
 * the room and cuts the second one short first ([NameMetaRow]).
 */
internal object HubLayout {

    /** The width the Hub is built for; `HubWidthTest` holds every row to it. */
    const val MIN_WIDTH = 280

    /** The width a stretching control asks for before `AlignX.FILL` hands it the rest of the row. */
    private const val NARROW = 60

    /** The content, as wide as the stripe — never wider, so no row can push a horizontal scrollbar in. */
    fun scroll(content: JComponent): JBScrollPane =
        JBScrollPane(WidthTracking(content)).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }

    /** A control that stretches: it asks for little, so the row never overflows, and fills what is there. */
    fun <T : JComponent> narrow(c: T): T = c.apply {
        preferredSize = Dimension(JBUI.scale(NARROW), preferredSize.height)
        minimumSize = Dimension(JBUI.scale(NARROW), minimumSize.height)
    }

    /**
     * A list inside the Hub: as wide as the panel rather than as wide as its longest row, so a long name
     * is cut with `…` (its tooltip has it whole) instead of widening the section or growing a scrollbar.
     */
    fun <T> list(model: ListModel<T>): JBList<T> = object : JBList<T>(model) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }

    fun listScroll(list: JList<*>): JBScrollPane = narrow(JBScrollPane(list).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    })

    /** Whether the section called [id] is folded — remembered per project, in the workspace file. */
    fun expanded(project: Project, id: String): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(key(id), true)

    fun rememberExpanded(project: Project, id: String, expanded: Boolean) =
        PropertiesComponent.getInstance(project).setValue(key(id), expanded, true)

    private fun key(id: String) = "flowable.atlas.hub.section.$id.expanded"

    private class WidthTracking(content: JComponent) : JPanel(BorderLayout()), Scrollable {
        init {
            add(content, BorderLayout.CENTER)
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}

/**
 * One list row of two facts — a name on the left, a grey fact on the right (an age, a file name) — the
 * shape the Search Everywhere row has. The two used to be a `BorderLayout` pair, which hands the right
 * side its full width first: in a narrow stripe a long file name ate the key it was supposed to follow.
 * Here the right side gets at most [META_SHARE] of the row, and both are labels, which end in `…` when cut.
 */
internal class NameMetaRow<T>(private val describe: (T) -> Parts) : JPanel(), ListCellRenderer<T> {

    /** What one row shows; [tooltip] carries what did not fit, or the detail nobody scans for. */
    data class Parts(val icon: Icon?, val name: String, val meta: String, val tooltip: String?)

    private val name = JBLabel().apply { font = font.deriveFont(java.awt.Font.BOLD) }
    private val meta = JBLabel().apply { horizontalAlignment = SwingConstants.RIGHT }

    init {
        layout = Layout()
        add(name)
        add(meta)
        isOpaque = true
        border = JBUI.Borders.empty(1, 4, 1, 8)
    }

    override fun getListCellRendererComponent(list: JList<out T>, value: T, index: Int, selected: Boolean, focus: Boolean): Component {
        val parts = describe(value)
        name.icon = parts.icon
        name.text = parts.name
        meta.text = parts.meta
        background = if (selected) list.selectionBackground else list.background
        name.foreground = if (selected) list.selectionForeground else list.foreground
        meta.foreground = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
        toolTipText = parts.tooltip
        return this
    }

    private inner class Layout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit
        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, preferredLayoutSize(parent).height)
        override fun preferredLayoutSize(parent: Container): Dimension {
            val i = parent.insets
            val n = name.preferredSize
            val m = meta.preferredSize
            return Dimension(i.left + n.width + GAP + m.width + i.right, i.top + maxOf(n.height, m.height) + i.bottom)
        }

        override fun layoutContainer(parent: Container) {
            val i = parent.insets
            val w = parent.width - i.left - i.right
            val h = parent.height - i.top - i.bottom
            val metaWidth = if (meta.text.isNullOrEmpty()) 0 else minOf(meta.preferredSize.width, (w * META_SHARE).toInt())
            meta.setBounds(i.left + w - metaWidth, i.top, metaWidth, h)
            name.setBounds(i.left, i.top, (w - metaWidth - GAP).coerceAtLeast(0), h)
        }
    }

    private companion object {
        const val META_SHARE = 0.45
        val GAP get() = JBUI.scale(8)
    }
}

/**
 * A line of text that wraps to the Hub's width instead of setting it — an empty state, the attention
 * line, a failed fetch. [text] is plain text: a folder name the user typed goes in as it is.
 *
 * Not the UI DSL's word-wrapping comment, although that is what it looks like: a wrapped editor pane
 * reports the width it was last laid out at as the width it prefers, so once the stripe had been dragged
 * wide, dragging it back left the whole panel at the old width, cut off on the right. This one always
 * asks for [HubLayout]'s narrow width and wraps into whatever the row gives it; its height follows.
 */
internal class HubText(comment: Boolean = true) {

    val component: JTextArea = object : JTextArea() {
        override fun getPreferredSize(): Dimension =
            Dimension(JBUI.scale(NARROW_TEXT), super.getPreferredSize().height)
        override fun getMinimumSize(): Dimension = preferredSize
    }.apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        font = if (comment) JBFont.medium() else JBFont.label()
        foreground = if (comment) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
    }

    var text: String
        get() = component.text
        set(value) {
            component.text = value
        }

    fun place(row: Row): Cell<JTextArea> = row.cell(component)

    private companion object {
        const val NARROW_TEXT = 60
    }
}

/**
 * A block's title that folds it: chevron, bold title, a rule to the right edge — what the UI DSL's
 * collapsible group looks like. Not that group itself: its title reports the width it was last laid out
 * at as its *minimum*, so a Hub dragged wide once could never be dragged narrow again (the grid will not
 * shrink a row below a minimum). This one's minimum is its title.
 */
internal class FoldHeader(title: String, expanded: Boolean, private val onToggle: (Boolean) -> Unit) : JPanel(BorderLayout()) {

    private val label = JBLabel(title).apply { font = JBFont.label().asBold() }

    var expanded: Boolean = expanded
        set(value) {
            field = value
            label.icon = if (value) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
            onToggle(value)
        }

    init {
        isOpaque = false
        add(label, BorderLayout.WEST)
        label.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true
        getAccessibleContext().accessibleName = title
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                this@FoldHeader.expanded = !this@FoldHeader.expanded
                requestFocusInWindow()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> this@FoldHeader.expanded = !this@FoldHeader.expanded
                    KeyEvent.VK_LEFT -> if (this@FoldHeader.expanded) this@FoldHeader.expanded = false
                    KeyEvent.VK_RIGHT -> if (!this@FoldHeader.expanded) this@FoldHeader.expanded = true
                    else -> return
                }
                e.consume()
            }
        })
    }

    override fun getMinimumSize(): Dimension = Dimension(label.preferredSize.width, preferredSize.height)
    override fun getPreferredSize(): Dimension = Dimension(label.preferredSize.width, label.preferredSize.height + JBUI.scale(2))

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val x = label.x + label.width + JBUI.scale(6)
        if (x >= width) return
        g.color = JBColor.border()
        val y = height / 2
        g.drawLine(x, y, width - 1, y)
        if (isFocusOwner) {
            g.color = JBUI.CurrentTheme.Focus.focusColor()
            g.drawRect(0, 0, label.x + label.width, height - 1)
        }
    }

    override fun processFocusEvent(e: java.awt.event.FocusEvent) {
        super.processFocusEvent(e)
        repaint()
    }
}
