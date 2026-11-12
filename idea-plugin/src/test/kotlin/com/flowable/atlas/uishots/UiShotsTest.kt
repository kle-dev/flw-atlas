package com.flowable.atlas.uishots

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.expr.toolwindow.FlowableExpressionToolWindowFactory
import com.flowable.atlas.findings.AtlasFindingsPanel
import com.flowable.atlas.findings.AtlasFindingsService
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.hub.AtlasHubPanel
import com.flowable.atlas.settings.EnvironmentsConfigurable
import com.flowable.atlas.settings.ExpressionsConfigurable
import com.flowable.atlas.settings.FlowableAtlasConfigurable
import com.flowable.atlas.settings.GenerationConfigurable
import com.flowable.atlas.settings.GenerationConstantsConfigurable
import com.flowable.atlas.settings.GenerationDtoConfigurable
import com.flowable.atlas.settings.GenerationLiquibaseConfigurable
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.UIManager

/**
 * Pictures of the plugin's Swing panels, painted headless — so a layout change can be looked at before
 * anyone starts an IDE. Off unless asked for:
 *
 *     ./gradlew :idea-plugin:test --tests '*UiShotsTest*' -Patlas.uiShots=build/ui-shots
 *
 * The panels are the real ones, laid out by the real Kotlin UI DSL, under the Darcula look-and-feel: the
 * test application has no installed themes to switch between, and `IntelliJLaf` without its theme file
 * paints Darcula's colours anyway, so a light picture would be a false one. What the pictures are good
 * for is layout — what fits at which width, what is cut off, what lines up — and each one prints how
 * wide its content wants to be, which is the number a narrow tool window has to accommodate. Colours and
 * the New UI chrome still need a look in a real IDE (`runIdeLocal`).
 */
class UiShotsTest : BasePlatformTestCase() {

    private val out: File? = System.getProperty("atlas.uiShots")?.let(::File)

    override fun setUp() {
        super.setUp()
        if (out == null) return
        UIManager.setLookAndFeel(DarculaLaf())
        @Suppress("DEPRECATION") JBColor.setDark(true)
    }

    fun testHub() {
        val dir = out ?: return
        val catalog = AtlasEnvironments.getInstance()
        val env = catalog.addEnvironment("DEMO-ACCEPTANCE-EU-WEST")
        val gone = catalog.addEnvironment("DEMO-GONE")
        try {
            val work = catalog.addConnection(env, ConnectionKind.WORK, "https://work.acceptance.example.com")!!
            val design = catalog.addConnection(env, ConnectionKind.DESIGN, "https://design.acceptance.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, work)
            val panel = AtlasHubPanel(project)
            try {
                panel.refreshForTest()
                for (w in listOf(280, 360, 480)) shoot(dir, "hub-$w", panel, w, 760)
                // Narrowed again after being wide: what dragging the stripe back does.
                shoot(dir, "hub-280-again", panel, 280, 760)
                // …and with the attention line up: a removed environment is the first thing it says.
                val doomed = catalog.addConnection(gone, ConnectionKind.DESIGN, "https://design.gone.example.com")!!
                AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, doomed)
                catalog.removeEnvironment(gone)
                panel.refreshForTest()
                shoot(dir, "hub-280-attention", panel, 280, 760)
            } finally {
                panel.dispose()
            }
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(env)
        }
    }

    fun testFindings() {
        val dir = out ?: return
        val demo = demoProject() ?: return
        @Suppress("UNCHECKED_CAST")
        val findings = (Atlas.extract(demo)["findings"] as? List<Map<String, Any?>>).orEmpty()
        val service = AtlasFindingsService.getInstance(project)
        service.seedForTest(AtlasFindingsService.Analysis(demo.toPath(), demo.toPath().resolve("atlas-output"), findings))
        val panel = AtlasFindingsPanel(project)
        try {
            shoot(dir, "findings-1100", panel, 1100, 360)
            // A finding selected: the pane beside the tree explains its check.
            val tree = panel.tree
            com.intellij.util.ui.tree.TreeUtil.expandAll(tree)
            (0 until tree.rowCount).map { tree.getPathForRow(it) }
                .firstOrNull { (it.lastPathComponent as javax.swing.tree.DefaultMutableTreeNode).userObject is com.flowable.atlas.findings.FindingsTree.FindingItem }
                ?.let { tree.selectionPath = it }
            shoot(dir, "findings-1100-selected", panel, 1100, 360)
        } finally {
            Disposer.dispose(panel)
            service.seedForTest(null)
        }
    }

    fun testPlayground() {
        val dir = out ?: return
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        for (content in toolWindow.contentManager.contents) {
            val c = content.component
            val tag = content.displayName.lowercase()
            shoot(dir, "playground-$tag-bottom", c, 1100, 340)
            shoot(dir, "playground-$tag-side", c, 380, 760)
        }
    }

    fun testSettings() {
        val dir = out ?: return
        val pages: List<Configurable> = listOf(
            FlowableAtlasConfigurable(),
            ExpressionsConfigurable(project),
            GenerationConfigurable(project),
            GenerationConstantsConfigurable(project),
            GenerationLiquibaseConfigurable(project),
            GenerationDtoConfigurable(project),
            EnvironmentsConfigurable(project),
        )
        try {
            pages.forEach { page ->
                val c = page.createComponent() ?: return@forEach
                page.reset()
                shoot(dir, "settings-${page.javaClass.simpleName.removeSuffix("Configurable").lowercase()}", c as JComponent, 760, 620)
            }
        } finally {
            pages.forEach { it.disposeUIResources() }
        }
    }

    private fun demoProject(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "site/flowable-demo") }
            .firstOrNull { it.isDirectory }

    private fun shoot(dir: File, name: String, c: JComponent, w: Int, h: Int) {
        dir.mkdirs()
        c.setSize(w, h)
        layout(c)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, w, h)
        c.printAll(g)
        g.dispose()
        ImageIO.write(img, "png", File(dir, "$name.png"))
        // The width the content asks for, against the width it got: anything over is cut off or scrolls.
        val wanted = UIUtil.findComponentsOfType(c, JScrollPane::class.java)
            .mapNotNull { it.viewport?.view?.preferredSize?.width }
            .maxOrNull() ?: c.preferredSize.width
        println("UISHOT $name: ${w}px given, content wants ${wanted}px" + if (wanted > w) "  <-- overflows" else "")
        if (wanted > w) UIUtil.findComponentsOfType(c, JComponent::class.java)
            .filter { it.isVisible && it.preferredSize.width > w - 20 }
            .forEach { println("UISHOT   wide: ${it.javaClass.name} pref=${it.preferredSize} min=${it.minimumSize}") }
        if (wanted > w) UIUtil.findComponentsOfType(c, com.intellij.openapi.ui.DialogPanel::class.java).forEach { dp ->
            dp.components.forEach { ch -> println("UISHOT   child ${ch.javaClass.simpleName} vis=${ch.isVisible} pref=${ch.preferredSize.width} min=${ch.minimumSize.width} w=${ch.width}" + ((ch as? javax.swing.JLabel)?.text?.let { " '" + it + "'" } ?: "")) }
        }
    }

    /** What a resize does: every cached size is dropped, then the tree is laid out again, top down. */
    private fun layout(c: Component) {
        // Twice: a wrapping text learns its width in the first pass and asks for its height in the second,
        // which is what the revalidation after a real resize does.
        repeat(2) {
            invalidateTree(c)
            layoutTree(c)
        }
    }

    private fun invalidateTree(c: Component) {
        c.invalidate()
        if (c is Container) c.components.forEach(::invalidateTree)
    }

    private fun layoutTree(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layoutTree)
    }
}
