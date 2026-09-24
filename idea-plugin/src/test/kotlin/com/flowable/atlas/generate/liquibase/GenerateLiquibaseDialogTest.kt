package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Liquibase preview on the shared dialog shape: hand-picked data objects start unticked, the file
 * name renders live from the pattern and the rename, a bad regex is named, and two rows aimed at one
 * file are refused. DEMO-* placeholder keys — this repo is public.
 */
class GenerateLiquibaseDialogTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            settings.liquibaseFileNamePattern = FlowableAtlasProjectSettings.DEFAULT_LIQUIBASE_PATTERN
            settings.liquibaseRenameFind = ""
            settings.liquibaseRenameReplace = ""
        } finally {
            super.tearDown()
        }
    }

    private fun addDataObjects() {
        for ((key, name) in listOf("DEMO-D010" to "Customer", "DEMO-D011" to "Address")) {
            myFixture.addFileToProject(
                "models/$key.data",
                """{"key":"$key","name":"$name","dataObjectType":"serviceRegistryDataObject",
                    "fieldMappings":[{"name":"label","type":"STRING"}]}""",
            )
        }
        project.service<FlowableModelIndexService>().refresh()
    }

    private fun withDialog(source: LiquibaseSource, block: (GenerateLiquibaseDialog) -> Unit) {
        val base = myFixture.tempDirFixture.findOrCreateDir(".")
        val plans = LiquibaseScaffoldService.getInstance(project).computePlans(base)
        val dialog = GenerateLiquibaseDialog(project, base, plans, source)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testHandPickedDataObjectsStartUnticked() {
        addDataObjects()
        withDialog(LiquibaseSource.DATA_OBJECTS) { dialog ->
            assertEquals(listOf("DEMO-D010", "DEMO-D011"), dialog.fileNamesForTesting().map { it.first })
            assertTrue("the user opts in", dialog.includedKeysForTesting().isEmpty())
            assertEquals("Select at least one changelog to generate.", dialog.validationMessageForTesting())
        }
    }

    fun testTheFileNameRendersLiveFromPatternAndRename() {
        addDataObjects()
        withDialog(LiquibaseSource.DATA_OBJECTS) { dialog ->
            dialog.configureForTesting("{key}-{name}")
            assertEquals(
                // The name token is file-safe lower case; every changelog carries the .changelog.xml suffix.
                listOf("DEMO-D010" to "DEMO-D010-customer.changelog.xml", "DEMO-D011" to "DEMO-D011-address.changelog.xml"),
                dialog.fileNamesForTesting(),
            )
            dialog.configureForTesting("{key}", renameFind = "^DEMO-", renameReplace = "db-")
            assertEquals(listOf("db-D010.changelog.xml", "db-D011.changelog.xml"), dialog.fileNamesForTesting().map { it.second })
        }
    }

    fun testABadRegexIsNamedAndTwoRowsMayNotShareAFile() {
        addDataObjects()
        withDialog(LiquibaseSource.DATA_OBJECTS) { dialog ->
            dialog.configureForTesting("{key}", renameFind = "(")
            assertTrue(dialog.validationMessageForTesting().orEmpty().startsWith("Invalid regex:"))

            // Every row renders to the same constant name: the duplicate check catches it before writing.
            dialog.configureForTesting("same")
            dialog.selectAllForTesting()
            assertEquals(
                "Two selected rows map to the same file name: same.changelog.xml.",
                dialog.validationMessageForTesting(),
            )
        }
    }

    /**
     * An absolute output folder is refused, like on the settings page. It used to pass: the check trimmed
     * the leading slash first, so "/Users/…" looked like a folder named "Users" inside the project.
     */
    fun testAnAbsoluteOutputFolderIsRefused() {
        addDataObjects()
        withDialog(LiquibaseSource.DATA_OBJECTS) { dialog ->
            dialog.selectAllForTesting()
            dialog.setOutputDirForTesting("/tmp/DEMO-changelogs")
            assertEquals("The output folder must be relative to the project directory.", dialog.validationMessageForTesting())
            dialog.setOutputDirForTesting("src/main/resources/liquibase")
            assertNull(dialog.validationMessageForTesting())
        }
    }
}
