package com.flowable.atlas.settings

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The environment editor works on a draft and only touches the catalog on Apply — which is what makes
 * *Cancel* mean something after a removal or a rename. The light fixture reuses one application, and
 * the catalog is application-level, so every test clears it again.
 */
class EnvironmentsConfigurableTest : BasePlatformTestCase() {

    private val catalog get() = AtlasEnvironments.getInstance()

    override fun tearDown() {
        try {
            catalog.environments().forEach { catalog.removeEnvironment(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun testThePageBuildsWithNoEnvironmentsAndIsNotModified() {
        val page = EnvironmentsConfigurable(project)
        try {
            page.createComponent()
            page.reset()
            assertFalse("a freshly opened page has nothing to apply", page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }

    fun testAnEnvironmentWithOnlyOneKindApplies() {
        // The state the maintainer named: QA has a running app and no Design server.
        val id = catalog.addEnvironment("QA")
        catalog.addConnection(id, ConnectionKind.WORK, "https://work-qa.example.com")
        val page = EnvironmentsConfigurable(project)
        try {
            page.createComponent()
            page.reset()
            page.apply()
            assertEquals(listOf("QA"), catalog.environments().map { it.name })
            assertNull("no Design connection is a valid state, not a half-finished one",
                catalog.connection(id, ConnectionKind.DESIGN))
        } finally {
            page.disposeUIResources()
        }
    }

    /**
     * Control and Hub are addresses, not servers: they round-trip through the editor with a URL and
     * nothing else, and no project ever points at one — which is the part that had to be made
     * explicit, because the pointer used to be "Design, or else Work" and would have swallowed them.
     */
    fun testALinkOnlyConnectionAppliesAndIsNeverPointedAt() {
        val id = catalog.addEnvironment("DEV1")
        catalog.addConnection(id, ConnectionKind.CONTROL, "http://localhost:8081/flowable-control/")
        val page = EnvironmentsConfigurable(project)
        try {
            page.createComponent()
            page.reset()
            page.apply()
        } finally {
            page.disposeUIResources()
        }
        val control = catalog.connection(id, ConnectionKind.CONTROL)!!
        assertEquals("http://localhost:8081/flowable-control", control.baseUrl)
        assertEquals("", control.username)

        AtlasConnectionSelection.select(project, ConnectionKind.CONTROL, control.id)
        assertNull(
            "a link has no pointer — storing one would have landed in the Work pointer",
            AtlasConnectionSelection.storedId(project, ConnectionKind.CONTROL),
        )
        assertNull(
            "and it must not have leaked into another kind's pointer either",
            AtlasConnectionSelection.storedId(project, ConnectionKind.WORK),
        )
        assertEquals(
            AtlasConnectionSelection.Resolution.NotSet,
            AtlasConnectionSelection.resolution(project, ConnectionKind.CONTROL),
        )
    }

    fun testTheCatalogIsUntouchedUntilApply() {
        val id = catalog.addEnvironment("DEV1")
        catalog.addConnection(id, ConnectionKind.DESIGN, "https://design-dev1.example.com")
        val page = EnvironmentsConfigurable(project)
        try {
            page.createComponent()
            page.reset()
            // Whatever the editor does to its draft, closing the dialog without Apply must change
            // nothing — a removal that already happened cannot be cancelled.
            assertEquals(1, catalog.environments().size)
        } finally {
            page.disposeUIResources()
        }
        assertEquals(1, catalog.environments().size)
        assertEquals("https://design-dev1.example.com", catalog.connection(id, ConnectionKind.DESIGN)?.baseUrl)
    }
}
