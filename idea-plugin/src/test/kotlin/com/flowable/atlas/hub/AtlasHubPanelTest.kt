package com.flowable.atlas.hub

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.SharedEnvironments
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hub smoke: the panel builds and refreshes, and every section says the right thing in every state. */
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
            assertTrue(panel.viewForTest().connectionLines.getValue(ConnectionKind.DESIGN).contains("DEV1"))
            assertTrue(panel.viewForTest().connectionLines.getValue(ConnectionKind.WORK).contains("QA"))
            assertEquals("Pull from DEV1", panel.viewForTest().pullText)
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
            assertTrue(panel.viewForTest().connectionLines.getValue(ConnectionKind.DESIGN).contains("was removed"))
            assertEquals("Pull from Flowable Design", panel.viewForTest().pullText)
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(prod)
            panel.dispose()
        }
    }

    /**
     * Switching environment has to *land* in the two pickers below it: each environment's workspace and
     * apps are its own, and showing the previous one's is worse than showing none — it reads as the
     * selection having carried over, which is exactly what a pull must never do.
     */
    fun testSwitchingEnvironmentSwapsTheWorkspaceAndAppSelection() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEV")
        val qa = catalog.addEnvironment("QA")
        val panel = AtlasHubPanel(project)
        try {
            val devDesign = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev.example.com")!!
            val qaDesign = catalog.addConnection(qa, ConnectionKind.DESIGN, "http://design-qa.example.com")!!
            settings.pullTarget("DEV").also {
                it.workspaceKey = "dev-ws"
                it.appKeys = mutableListOf("alpha", "beta")
            }
            settings.pullTarget("QA").also {
                it.workspaceKey = "qa-ws"
                it.appKeys = mutableListOf("gamma")
            }

            select(devDesign)
            panel.refreshForTest()
            assertEquals("dev-ws", panel.viewForTest().workspaceKey)
            assertEquals(listOf("alpha", "beta"), panel.viewForTest().appKeys)

            select(qaDesign)
            panel.refreshForTest()
            assertEquals("qa-ws", panel.viewForTest().workspaceKey)
            assertEquals(listOf("gamma"), panel.viewForTest().appKeys)

            // …and back, because "remembered per environment" is the whole claim.
            select(devDesign)
            panel.refreshForTest()
            assertEquals("dev-ws", panel.viewForTest().workspaceKey)
            assertEquals(listOf("alpha", "beta"), panel.viewForTest().appKeys)
        } finally {
            listOf("DEV", "QA").forEach {
                settings.pullTarget(it).also { target ->
                    target.workspaceKey = ""
                    target.appKeys = mutableListOf()
                }
            }
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(dev)
            catalog.removeEnvironment(qa)
            panel.dispose()
        }
    }

    /**
     * "Not set" is an answer, not a pause: what the pickers below show has to go with it.
     *
     * With **one** environment — the case this broke in. Picking *not set* used to unset the pointer,
     * which let the single-connection fallback answer with that same environment, so the panel kept
     * showing its workspace and its ticked apps under a picker reading *not set*.
     */
    fun testChoosingNotSetEmptiesTheWorkspaceAndAppPickers() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val catalog = AtlasEnvironments.getInstance()
        val dev = catalog.addEnvironment("DEV")
        val panel = AtlasHubPanel(project)
        try {
            val devDesign = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev.example.com")!!
            settings.pullTarget("DEV").also {
                it.workspaceKey = "dev-ws"
                it.appKeys = mutableListOf("alpha")
            }
            select(devDesign)
            panel.refreshForTest()
            assertEquals("dev-ws", panel.viewForTest().workspaceKey)
            assertEquals(listOf("alpha"), panel.viewForTest().appKeys)

            panel.chooseNoEnvironmentForTest(ConnectionKind.DESIGN)
            panel.refreshForTest()
            assertNull("no environment, no workspace", panel.viewForTest().workspaceKey)
            assertTrue("no environment, no apps", panel.viewForTest().appKeys.isEmpty())
            assertEquals("not set", panel.viewForTest().connectionLines.getValue(ConnectionKind.DESIGN))
            // The stored selection is untouched — saying "not set" is not deleting the settings, and
            // choosing DEV again has to bring its workspace and apps back.
            assertEquals("dev-ws", settings.pullTargetOrNull("DEV")!!.workspaceKey)

            select(devDesign)
            panel.refreshForTest()
            assertEquals("dev-ws", panel.viewForTest().workspaceKey)
            assertEquals(listOf("alpha"), panel.viewForTest().appKeys)
        } finally {
            settings.pullTarget("DEV").also {
                it.workspaceKey = ""
                it.appKeys = mutableListOf()
            }
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            catalog.removeEnvironment(dev)
            panel.dispose()
        }
    }

    /**
     * The Design section is the same four rows whatever its state. It used to grow and shrink by four rows
     * — no environments, an environment, a workspace, apps — so everything below it moved with its mood.
     */
    fun testTheDesignSectionKeepsItsHeightWithoutEnvironments() {
        val catalog = AtlasEnvironments.getInstance()
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            val before = panel.viewForTest()
            assertFalse(before.hasEnvironments)
            assertEquals("no environments yet", before.connectionLines.getValue(ConnectionKind.DESIGN))

            val dev = catalog.addEnvironment("DEV")
            try {
                val design = catalog.addConnection(dev, ConnectionKind.DESIGN, "http://design-dev.example.com")!!
                AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, design)
                panel.refreshForTest()
                val after = panel.viewForTest()
                assertTrue(after.hasEnvironments)
                assertEquals("the app list reserves the same one row", before.listRows, after.listRows)
            } finally {
                AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
                catalog.removeEnvironment(dev)
            }
        } finally {
            panel.dispose()
        }
    }

    /** Nothing to act on, nothing said: the attention line is for the reader's next move, not for status. */
    fun testTheAttentionLineIsEmptyOnACleanFixture() {
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            assertNull(panel.viewForTest().attention)
        } finally {
            panel.dispose()
        }
    }

    /** Select a Design connection and tell the panel, the way any other surface would. */
    private fun select(connectionId: String) {
        AtlasConnectionSelection.select(project, ConnectionKind.DESIGN, connectionId)
        project.messageBus.syncPublisher(AtlasEvents.TOPIC).connectionSelectionChanged(ConnectionKind.DESIGN)
    }

    /**
     * The row has to look like the choice it is. It read as a status line with a *Change…* link on a
     * second line below it — and the link was hidden whenever nothing had been detected yet, so in a
     * repository with several Flowable projects the answer to "can I pick one?" was a blank space.
     */
    /**
     * "No explorer generated yet" was a claim the panel cannot make: *Generate…* writes wherever you
     * point it, and the search is scoped to the active Flowable project's output folder — so a page
     * saved elsewhere is invisible here, and the old wording called that "not generated". Naming the
     * folder turns a wrong claim into a findable mismatch.
     */
    fun testTheEmptyExplorerLineNamesTheFolderItSearched() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val rootService = AtlasProjectRootService.getInstance(project)
        val panel = AtlasHubPanel(project)
        val previous = settings.atlasOutputDir
        try {
            settings.atlasOutputDir = "atlas-output"
            panel.refreshForTest()
            assertEquals("No explorer in atlas-output/ yet", panel.viewForTest().explorerHint)

            // Scoped to a sub-project, the folder it searched is inside that sub-project — which is the
            // whole reason someone's generated page can be missing from the list.
            rootService.setActiveSubProject("orders-app")
            panel.refreshForTest()
            assertEquals("No explorer in orders-app/atlas-output/ yet", panel.viewForTest().explorerHint)
        } finally {
            rootService.setActiveSubProject("")
            settings.atlasOutputDir = previous
            panel.dispose()
        }
    }

    fun testTheProjectPickerAlwaysOffersWholeProjectAndKeepsTheActiveOne() {
        val rootService = AtlasProjectRootService.getInstance(project)
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            assertEquals("whole project is always a choice", listOf(""), panel.viewForTest().projectItems)

            panel.chooseProjectForTest("apps/demo")
            panel.refreshForTest()
            assertEquals("apps/demo", rootService.activeSubProject())
            // Detection has nothing to say about this fixture, so without carrying the active one over
            // the row would read "Whole project" while Atlas was scoped to apps/demo.
            assertTrue(panel.viewForTest().projectItems.contains("apps/demo"))

            panel.chooseProjectForTest("")
            panel.refreshForTest()
            assertEquals("", rootService.activeSubProject())
        } finally {
            rootService.setActiveSubProject("")
            panel.dispose()
        }
    }

    /**
     * The Hub shares one narrow stripe between five sections, so a list that reserves eight rows for
     * content it does not have is the panel's height spent on nothing. Both lists are sized to what
     * they hold — the ordinary project has exactly one generated explorer.
     */
    fun testTheListsAreSizedToTheirContent() {
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            assertEquals(1 to 1, panel.viewForTest().listRows)
        } finally {
            panel.dispose()
        }
    }

    /**
     * The point of the committed file: a colleague clones the repository and the team's stages are
     * simply there — offered, selectable, and marked so nobody wonders why they cannot edit the URL.
     */
    fun testAnEnvironmentTheProjectSharesIsOfferedLikeAnyOther() {
        val shared = SharedEnvironments.getInstance(project)
        val panel = AtlasHubPanel(project)
        try {
            shared.share(
                "QA", requireConfirmation = false,
                listOf(
                    AtlasConnection(
                        "ignored", ConnectionKind.DESIGN, "https://design-qa.example.com", "",
                        AuthMode.BASIC, "e", "QA", false,
                    ),
                ),
            )
            panel.refreshForTest()
            assertTrue("nothing is defined in this IDE, and yet there is an environment", panel.viewForTest().hasEnvironments)

            AtlasConnectionSelection.select(
                project, ConnectionKind.DESIGN, SharedEnvironments.connectionIdOf("QA", ConnectionKind.DESIGN),
            )
            panel.refreshForTest()
            assertEquals("QA (project)", panel.viewForTest().connectionLines.getValue(ConnectionKind.DESIGN))
            assertEquals("Pull from QA", panel.viewForTest().pullText)
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.DESIGN)
            shared.unshare("QA")
            panel.dispose()
        }
    }

    fun testWithNoEnvironmentsTheSectionNamesTheNextStep() {
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            assertFalse("nothing defined yet is its own state, not an empty row", panel.viewForTest().hasEnvironments)
        } finally {
            panel.dispose()
        }
    }
}
