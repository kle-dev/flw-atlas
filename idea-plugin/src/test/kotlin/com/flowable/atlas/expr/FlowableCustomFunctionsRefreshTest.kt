package com.flowable.atlas.expr

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * A function added to `externals.additionalData` is known without restarting the IDE — and the editor
 * reads the custom functions from the same place, with the same settings, as generation does.
 */
class FlowableCustomFunctionsRefreshTest : BasePlatformTestCase() {

    private val functions get() = FlowableCustomFunctions.getInstance(project)
    private val settings get() = FlowableAtlasProjectSettings.getInstance(project)
    private val roots get() = AtlasProjectRootService.getInstance(project)

    fun testAChangedFrontendSourceDropsTheCatalog() {
        val seeded = CustomFunctionCatalog(mapOf("demo" to setOf("fn")), emptySet(), emptySet(), emptyList(), emptyList())
        functions.setForTest(seeded)
        assertSame(seeded, functions.readyOrRequest()!!.catalog)

        myFixture.addFileToProject("frontend/README.md", "not a source")
        assertSame("a non-source file changes nothing", seeded, functions.readyOrRequest()!!.catalog)

        myFixture.addFileToProject("frontend/src/customFunctions.ts", "export const additionalData = {}")
        assertNotSame("the catalog is read again", seeded, functions.readyOrRequest()?.catalog)
    }

    /**
     * The settings page's browse button writes the source relative to the active sub-project, and
     * generation resolves it there. The editor resolved it against the repository root, missed it, and
     * walked every app in the repository instead — accepting another app's functions.
     */
    fun testTheSourceResolvesAgainstTheActiveSubProject() = withTwoApps {
        roots.setActiveSubProject("DEMO-orders-app")
        settings.customFunctionsPath = "frontend"
        AtlasEvents.settingsApplied(project)

        val namespaces = functions.catalog()?.namespaces.orEmpty().keys
        assertTrue("this app's functions: $namespaces", "demoorders" in namespaces)
        assertFalse("not another app's: $namespaces", "demoother" in namespaces)
    }

    /** Unticking discovery takes effect in the editor on Apply, not on the next frontend edit or restart. */
    fun testAppliedSettingsDropTheCatalog() = withTwoApps {
        roots.setActiveSubProject("DEMO-orders-app")
        assertNotNull("discovery on reads the app's functions", functions.readyOrRequest()!!.catalog)

        settings.customFunctionsEnabled = false
        AtlasEvents.settingsApplied(project)
        assertNull("discovery off: no catalog", functions.readyOrRequest()!!.catalog)
    }

    /** The settings are per sub-project, and so is the folder discovery walks. */
    fun testASubProjectSwitchReadsThatProjectsFunctions() = withTwoApps {
        roots.setActiveSubProject("DEMO-orders-app")
        assertTrue("demoorders" in functions.readyOrRequest()!!.catalog!!.namespaces)

        roots.setActiveSubProject("DEMO-other-app")
        val namespaces = functions.readyOrRequest()!!.catalog!!.namespaces.keys
        assertEquals(setOf("demoother"), namespaces)
    }

    /** The event is fired by every Atlas settings page; one that changed nothing here walks nothing. */
    fun testUnrelatedSettingsKeepTheCatalog() = withTwoApps {
        roots.setActiveSubProject("DEMO-orders-app")
        val before = functions.readyOrRequest()
        AtlasEvents.settingsApplied(project)
        assertSame(before, functions.readyOrRequest())
    }

    fun testTheExplicitSourceIsRelativeToTheGivenRoot() {
        val root = File(project.basePath!!, "DEMO-apps/orders")
        File(root, "frontend").mkdirs()
        try {
            assertEquals(File(root, "frontend"), FlowableCustomFunctions.customFunctionsSource(root, "frontend"))
            assertNull("blank means discovery", FlowableCustomFunctions.customFunctionsSource(root, " "))
            assertNull("a source that is not there means discovery", FlowableCustomFunctions.customFunctionsSource(root, "nope"))
        } finally {
            File(project.basePath!!, "DEMO-apps").deleteRecursively()
        }
    }

    /** Two apps under the project, each with its own `externals.additionalData` namespace, on real disk. */
    private fun withTwoApps(body: () -> Unit) {
        val base = File(project.basePath!!)
        app(base, "DEMO-orders-app", "demoorders")
        app(base, "DEMO-other-app", "demoother")
        functions.refresh()
        try {
            body()
        } finally {
            for (sub in listOf("DEMO-orders-app", "DEMO-other-app")) {
                roots.setActiveSubProject(sub)
                settings.customFunctionsEnabled = true
                settings.customFunctionsPath = ""
            }
            roots.setActiveSubProject("")
            File(base, "DEMO-orders-app").deleteRecursively()
            File(base, "DEMO-other-app").deleteRecursively()
            functions.refresh()
        }
    }

    private fun app(base: File, name: String, namespace: String) {
        val file = File(base, "$name/frontend/custom.ts")
        file.parentFile.mkdirs()
        file.writeText("export default {\n  additionalData: {\n    $namespace: { fn: () => 1 },\n  },\n};\n")
    }
}
