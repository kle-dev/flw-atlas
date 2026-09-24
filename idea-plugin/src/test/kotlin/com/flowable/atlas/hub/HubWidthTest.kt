package com.flowable.atlas.hub

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * The Hub fits a side stripe. It used to be laid out at the width its widest row asked for — 567 px with
 * an ordinary environment name — so the stripe had to be dragged half across the screen before nothing
 * was cut off. This lays it out at [HubLayout.MIN_WIDTH] with long names everywhere a name can go and
 * holds three things: nothing reaches past the right edge, nothing can scroll sideways, and a text that
 * had to be cut still has a tooltip saying all of it.
 */
class HubWidthTest : BasePlatformTestCase() {

    fun testEverythingFitsTheNarrowStripeWithLongNames() {
        val catalog = AtlasEnvironments.getInstance()
        val long = catalog.addEnvironment("DEMO-ACCEPTANCE-EU-WEST")
        val gone = catalog.addEnvironment("DEMO-GONE")
        val roots = AtlasProjectRootService.getInstance(project)
        val panel = AtlasHubPanel(project)
        try {
            // First: the environment pointers are stored per sub-project.
            roots.setActiveSubProject("DEMO-customer-onboarding-and-offboarding")
            val design = catalog.addConnection(gone, ConnectionKind.DESIGN, "https://design.gone.example.com")!!
            val work = catalog.addConnection(long, ConnectionKind.WORK, "https://work.acceptance.example.com")!!
            catalog.addConnection(long, ConnectionKind.DESIGN, "https://design.acceptance.example.com")
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, work)
            // A removed environment puts the attention line up — the one row that comes and goes.
            catalog.removeEnvironment(gone)
            // The health row at its longest: four-digit counts, and stale, so *Analyze Again* is beside them.
            myFixture.addFileToProject("models/DEMO-P001.bpmn", """<definitions><process id="DEMO-P001"/></definitions>""")
            project.getService(com.flowable.atlas.index.FlowableModelIndexService::class.java).index()
            fun f(check: String, i: Int) = mapOf<String, Any?>("check" to check, "severity" to "warning", "node" to "process:DEMO-$i")
            val findings = (1..1280).map { f("missingRefs", it) } + (1..1041).map { f("unusedForms", it) }
            val root = java.io.File(project.basePath!!).toPath()
            com.flowable.atlas.findings.AtlasFindingsService.getInstance(project)
                .seedForTest(com.flowable.atlas.findings.AtlasFindingsService.Analysis(root, root, findings, atMillis = 1L))
            panel.refreshForTest()
            assertNotNull("the attention line is part of what has to fit", panel.viewForTest().attention)
            assertEquals("1,280 defects · 1,041 advice · Analyze Again", panel.viewForTest().health)

            val width = JBUI.scale(HubLayout.MIN_WIDTH)
            panel.setSize(width, JBUI.scale(900))
            layout(panel)

            val scroll = UIUtil.findComponentsOfType(panel, JScrollPane::class.java)
                .first { it.viewport.view !is javax.swing.JList<*> }
            assertEquals("the Hub never scrolls sideways", ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
            val viewport = scroll.viewport
            assertEquals("the content is as wide as the stripe", viewport.width, viewport.view.width)

            val overflowing = leaves(viewport.view as Container)
                .filter { visible(it, viewport) }
                .mapNotNull { c ->
                    val right = SwingUtilities.convertRectangle(c.parent, c.bounds, viewport).let { it.x + it.width }
                    if (right > viewport.width) "${describe(c)} ends at $right px" else null
                }
            assertEquals("components past the right edge at $width px", emptyList<String>(), overflowing)

            val cutWithoutTooltip = leaves(viewport.view as Container)
                .filter { visible(it, viewport) }
                .filter { (it is JLabel || it is AbstractButton) && it.width in 1 until it.preferredSize.width }
                .filter { (it as JComponent).toolTipText.isNullOrBlank() }
                .map(::describe)
            assertEquals("texts cut short with nothing to hover for the rest", emptyList<String>(), cutWithoutTooltip)
        } finally {
            com.flowable.atlas.findings.AtlasFindingsService.getInstance(project).seedForTest(null)
            roots.setActiveSubProject("")
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(long)
            panel.dispose()
        }
    }

    /** Folding a block is remembered for the next time the Hub is built. */
    fun testAFoldedBlockStaysFolded() {
        val first = AtlasHubPanel(project)
        try {
            first.foldForTest("design", expanded = false)
        } finally {
            first.dispose()
        }
        val second = AtlasHubPanel(project)
        try {
            assertEquals(listOf("design"), second.viewForTest().foldedSections)
            second.foldForTest("design", expanded = true)
            assertEquals(emptyList<String>(), second.viewForTest().foldedSections)
        } finally {
            second.dispose()
        }
    }

    private fun layout(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layout)
    }

    private fun leaves(root: Container): List<Component> =
        root.components.flatMap { c ->
            if (c is Container && c.componentCount > 0 && c !is AbstractButton && c !is JLabel &&
                c !is javax.swing.JComboBox<*> && c !is JScrollPane && c !is javax.swing.text.JTextComponent
            ) leaves(c) else listOf(c)
        }

    /** Visible all the way up to the viewport — a hidden row's components keep stale bounds. */
    private fun visible(c: Component, stop: Component): Boolean {
        var cur: Component? = c
        while (cur != null && cur !== stop) {
            if (!cur.isVisible) return false
            cur = cur.parent
        }
        return true
    }

    private fun describe(c: Component): String = when (c) {
        is AbstractButton -> "${c.javaClass.simpleName} '${c.text}'"
        is JLabel -> "${c.javaClass.simpleName} '${c.text}'"
        else -> c.javaClass.simpleName
    }
}
