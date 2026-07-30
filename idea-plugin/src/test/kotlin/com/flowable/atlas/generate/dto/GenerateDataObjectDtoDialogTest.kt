package com.flowable.atlas.generate.dto

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The preview dialog's wiring: it builds against a real project without exploding (the table, combos
 * and config panel are set up in the constructor, so their order matters), an app source arrives
 * preselected while hand-picking starts empty, and the target path re-renders live from the package
 * and the per-app nesting. DEMO-* placeholder keys — this repo is public.
 */
class GenerateDataObjectDtoDialogTest : BasePlatformTestCase() {

    private fun addModels() {
        myFixture.addFileToProject(
            "app/DEMO-APP.app",
            """{"key":"DEMO-APP","name":"Demo App",
                "extension":{"design":{"childModels":[{"key":"DEMO-D010","type":"dataObject"},
                                                      {"key":"DEMO-D011","type":"dataObject"}]}}}""",
        )
        myFixture.addFileToProject(
            "app/DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Customer","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"label","type":"STRING"}]}""",
        )
        myFixture.addFileToProject(
            "app/DEMO-D011.data",
            """{"key":"DEMO-D011","name":"Address","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"street","type":"STRING"}]}""",
        )
        project.service<FlowableModelIndexService>().refresh()
    }

    private fun withDialog(source: DtoSource, block: (GenerateDataObjectDtoDialog) -> Unit) {
        val plans = DataObjectDtoService.getInstance(project).computePlans()
        val dialog = GenerateDataObjectDtoDialog(project, plans, source)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testAppSourceArrivesPreselected() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")

            assertEquals(
                "a whole app is an explicit \"all of it\" request",
                listOf("DEMO-D010", "DEMO-D011"),
                dialog.includedKeysForTesting(),
            )
            assertNull("a preselected app is immediately generatable", dialog.validationMessageForTesting())
        }
    }

    fun testHandPickingStartsEmpty() {
        addModels()
        withDialog(DtoSource.DATA_OBJECTS) { dialog ->
            assertTrue("the user opts in", dialog.includedKeysForTesting().isEmpty())
            assertEquals(
                "Select at least one data object to generate.",
                dialog.validationMessageForTesting(),
            )
        }
    }

    fun testTargetPathFollowsPackageAndPerAppNesting() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")

            dialog.configureForTesting("com.acme.dto", perApp = false)
            assertEquals(
                listOf("DEMO-D010" to "com/acme/dto/CustomerDto.java", "DEMO-D011" to "com/acme/dto/AddressDto.java"),
                dialog.previewForTesting(),
            )

            dialog.configureForTesting("com.acme.dto", perApp = true)
            assertEquals(
                listOf(
                    "DEMO-D010" to "com/acme/dto/demoapp/CustomerDto.java",
                    "DEMO-D011" to "com/acme/dto/demoapp/AddressDto.java",
                ),
                dialog.previewForTesting(),
            )

            dialog.configureForTesting("", perApp = false)
            assertEquals(
                listOf("DEMO-D010" to "CustomerDto.java", "DEMO-D011" to "AddressDto.java"),
                dialog.previewForTesting(),
            )
        }
    }

    fun testAnInvalidPackageIsReported() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.configureForTesting("com..acme", perApp = false)

            assertEquals("'com..acme' is not a valid Java package.", dialog.validationMessageForTesting())
        }
    }

    fun testTheDialogSeedsFromTheGenerationSettings() {
        addModels()
        FlowableAtlasProjectSettings.getInstance(project).dtoPackage = "com.seeded.dto"
        withDialog(DtoSource.DATA_OBJECTS) { dialog ->
            assertTrue(
                "the configured package is what the preview renders",
                dialog.previewForTesting().all { it.second.startsWith("com/seeded/dto/") },
            )
        }
    }
}
