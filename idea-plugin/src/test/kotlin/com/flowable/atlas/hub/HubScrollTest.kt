package com.flowable.atlas.hub

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JList
import javax.swing.JScrollPane

/**
 * The Hub stays where it was scrolled to. Ticking an app in the Design block stores the selection and
 * refreshes the panel, and every refresh used to throw the panel back up to the Explorer block: the grey
 * lines are text areas, setting their text moves their caret, and a moved caret scrolls itself into view.
 */
class HubScrollTest : BasePlatformTestCase() {

    fun testARefreshKeepsTheScrollPosition() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEMO-DEV")
        val panel = AtlasHubPanel(project)
        try {
            val design = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev.example.com")!!
            settings.pullTarget("DEMO-DEV").also {
                it.workspaceKey = "DEMO-ws"
                it.appKeys = (1..6).map { i -> "DEMO-APP-$i" }.toMutableList()
            }
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
            panel.refreshForTest()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            // Shorter than its content, so there is somewhere to scroll to: the Design block's apps.
            panel.setSize(JBUI.scale(HubLayout.MIN_WIDTH), JBUI.scale(240))
            layout(panel)
            val viewport = UIUtil.findComponentsOfType(panel, JScrollPane::class.java)
                .first { it.viewport.view !is JList<*> }.viewport
            val bottom = viewport.view.height - viewport.height
            assertTrue("the Hub is taller than its stripe: ${viewport.view.height} px in ${viewport.height}", bottom > 0)
            viewport.viewPosition = Point(0, bottom)

            // What a tick ends in: the stored selection, then a refresh of every block.
            panel.refreshForTest()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertEquals("the Hub stays scrolled where it was", bottom, viewport.viewPosition.y)
        } finally {
            settings.pullTarget("DEMO-DEV").also {
                it.workspaceKey = ""
                it.appKeys = mutableListOf()
            }
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(dev)
            panel.dispose()
        }
    }

    private fun layout(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layout)
    }
}
