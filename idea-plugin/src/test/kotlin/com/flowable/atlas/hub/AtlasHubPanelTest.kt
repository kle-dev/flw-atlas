package com.flowable.atlas.hub

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hub smoke: the panel builds and refreshes, and the app section stays hidden until Design is set up. */
class AtlasHubPanelTest : BasePlatformTestCase() {

    fun testPanelBuildsAndRefreshesWithoutDesignConfigured() {
        val panel = AtlasHubPanel(project)
        try {
            // the refresh gathers on a pooled thread; drain it so an exception would surface here
            panel.refreshForTest()
            assertNotNull(panel.component)
        } finally {
            panel.dispose()
        }
    }

    fun testPickingAWorkspaceWritesTheProjectSettingForThatEnvironment() {
        // One value, written where it is picked: the Hub used to store a "personal override" that the
        // settings page could not show, so the two could disagree without anyone being told.
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val catalog = AtlasEnvironments.getInstance()
        val env = catalog.addEnvironment("DEV")
        val panel = AtlasHubPanel(project)
        try {
            val design = catalog.addConnection(env, ConnectionKind.DESIGN, "http://design.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
            settings.pullTarget("DEV").also {
                it.workspaceKey = "ws-1"
                it.appKeys = mutableListOf("appA")
            }
            panel.selectWorkspaceForTest("ws-2")
            val stored = settings.pullTargetOrNull("DEV")!!
            assertEquals("ws-2", stored.workspaceKey)
            assertTrue(
                "another workspace's apps do not exist there, so nothing stays ticked",
                stored.appKeys.isEmpty(),
            )
        } finally {
            settings.pullTarget("DEV").also {
                it.workspaceKey = ""
                it.appKeys = mutableListOf()
            }
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(env)
            panel.dispose()
        }
    }

    fun testEachEnvironmentKeepsItsOwnWorkspace() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEV")
        val qa = catalog.addEnvironment("QA")
        val panel = AtlasHubPanel(project)
        try {
            val devDesign = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev.example.com")!!
            val qaDesign = catalog.addConnection(qa, ConnectionKind.DESIGN, "http://design-qa.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, devDesign)
            panel.selectWorkspaceForTest("dev-ws")
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, qaDesign)
            panel.selectWorkspaceForTest("qa-ws")
            // A workspace key belongs to one server; switching environment must not carry it across.
            assertEquals("dev-ws", settings.pullTargetOrNull("DEV")!!.workspaceKey)
            assertEquals("qa-ws", settings.pullTargetOrNull("QA")!!.workspaceKey)
        } finally {
            listOf("DEV", "QA").forEach { settings.pullTarget(it).workspaceKey = "" }
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(dev)
            catalog.removeEnvironment(qa)
            panel.dispose()
        }
    }

    fun testEnvironmentsChangedMarksTheDesignListCachesStale() {
        // Asserts the volatile flag rather than a repaint: the refresh runs behind a 300 ms SingleAlarm
        // that dispatchAllInvocationEvents() cannot fast-forward, while the listener sets the flag
        // synchronously — which is all it is allowed to do from an arbitrary publishing thread.
        val panel = AtlasHubPanel(project)
        try {
            assertFalse(panel.designCachesStaleForTest)
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).environmentsChanged()
            assertTrue(
                "a switched server's workspaces and apps are different things entirely",
                panel.designCachesStaleForTest,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testTheConnectionRowsNameTheSelectedEnvironments() {
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEV1")
        val qa = catalog.addEnvironment("QA")
        val panel = AtlasHubPanel(project)
        try {
            val design = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev1.example.com")!!
            val work = catalog.addConnection(qa, ConnectionKind.WORK, "http://work-qa.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, work)
            panel.refreshForTest()
            // A mixed pairing is an ordinary state, not a half-configured one.
            assertTrue(panel.connectionLineForTest(ConnectionKind.DESIGN).contains("DEV1"))
            assertTrue(panel.connectionLineForTest(ConnectionKind.WORK).contains("QA"))
            assertEquals("Pull from DEV1", panel.pullLinkTextForTest())
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(dev)
            catalog.removeEnvironment(qa)
            panel.dispose()
        }
    }

    fun testADeletedConnectionReadsAsRemovedAndNeverFallsBackToAnother() {
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEV1")
        val prod = catalog.addEnvironment("PROD")
        val panel = AtlasHubPanel(project)
        try {
            val devDesign = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev1.example.com")!!
            catalog.addConnection(prod, ConnectionKind.DESIGN, "http://design.example.com")
            AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, devDesign)
            catalog.removeEnvironment(dev)
            panel.refreshForTest()
            // The nightmare this prevents: deleting DEV silently promotes PROD to "the server this
            // project pulls from", and the next pull runs against it.
            assertTrue(panel.connectionLineForTest(ConnectionKind.DESIGN).contains("was removed"))
            assertEquals("Pull from Design", panel.pullLinkTextForTest())
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(prod)
            panel.dispose()
        }
    }

    fun testWithNoEnvironmentsTheSectionNamesTheNextStep() {
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            assertFalse("nothing defined yet is its own state, not an empty row", panel.hasAnyEnvironmentForTest)
        } finally {
            panel.dispose()
        }
    }
}
