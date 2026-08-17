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

    /**
     * The light fixture reuses one project, so the generation settings a test writes would otherwise
     * seed the next one; hand every test the shipped defaults.
     */
    override fun tearDown() {
        try {
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            settings.dtoPackage = FlowableAtlasProjectSettings.DEFAULT_DTO_PACKAGE
            settings.dtoClassSuffix = FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_SUFFIX
            settings.dtoClassNamePattern = FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_PATTERN
            settings.dtoRenameFind = ""
            settings.dtoRenameReplace = ""
        } finally {
            super.tearDown()
        }
    }

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

    /** Data objects named the way Design projects name them: the model key in front of the real name. */
    private fun addKeyPrefixedModels() {
        myFixture.addFileToProject(
            "keyed/DEMO-D009.data",
            """{"key":"DEMO-D009","name":"DEMO-D009 Pod Member","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"label","type":"STRING"}]}""",
        )
        // The name abbreviates the key (D20 vs. D020) — the shortName rule must still see a key.
        myFixture.addFileToProject(
            "keyed/DEMO-D020.data",
            """{"key":"DEMO-D020","name":"DEMO-D20 Document Type","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"code","type":"STRING"}]}""",
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
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        settings.dtoPackage = "com.seeded.dto"
        settings.dtoClassNamePattern = "{app}{name}"
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")

            assertTrue(
                "the configured package is what the preview renders",
                dialog.previewForTesting().all { it.second.startsWith("com/seeded/dto/") },
            )
            assertEquals(
                "the configured class-name pattern names every row",
                listOf("DEMO-D010" to "DEMOAPPCustomer", "DEMO-D011" to "DEMOAPPAddress"),
                dialog.classNamesForTesting(),
            )
        }
    }

    fun testTheClassNamePatternRendersLiveIntoTheTargetFile() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")
            dialog.configureForTesting("com.acme.dto", perApp = false)

            dialog.configurePatternForTesting("Demo{name}{suffix}")
            assertEquals(
                listOf(
                    "DEMO-D010" to "com/acme/dto/DemoCustomerDto.java",
                    "DEMO-D011" to "com/acme/dto/DemoAddressDto.java",
                ),
                dialog.previewForTesting(),
            )

            dialog.configurePatternForTesting("{name}{suffix}", renameFind = "^(\\w)", renameReplace = "X$1")
            assertEquals(
                listOf("DEMO-D010" to "XCustomerDto", "DEMO-D011" to "XAddressDto"),
                dialog.classNamesForTesting(),
            )

            dialog.configurePatternForTesting("---")
            assertEquals(
                "The class name renders empty for row DEMO-D010.",
                dialog.validationMessageForTesting(),
            )
        }
    }

    fun testAnEditedClassNameOutranksThePattern() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")

            dialog.editClassNameForTesting("DEMO-D010", "HandPicked")
            dialog.configurePatternForTesting("{name}Bean")

            assertEquals(
                "only the row the user never touched follows the pattern",
                listOf("DEMO-D010" to "HandPicked", "DEMO-D011" to "AddressBean"),
                dialog.classNamesForTesting(),
            )
        }
    }

    fun testTheShortNameTokenDropsTheModelKeyFromEveryRow() {
        addKeyPrefixedModels()
        withDialog(DtoSource.DATA_OBJECTS) { dialog ->
            assertEquals(
                "the default pattern keeps the model name as Design wrote it",
                listOf("DEMO-D009" to "DEMOD009PodMemberDto", "DEMO-D020" to "DEMOD20DocumentTypeDto"),
                dialog.classNamesForTesting(),
            )

            dialog.configurePatternForTesting("{shortName}{suffix}")
            assertEquals(
                listOf("DEMO-D009" to "PodMemberDto", "DEMO-D020" to "DocumentTypeDto"),
                dialog.classNamesForTesting(),
            )
        }
    }

    fun testAnInvalidRenameRegexIsReported() {
        addModels()
        withDialog(DtoSource.APPS) { dialog ->
            dialog.selectSourceForTesting(DtoSource.APPS, "DEMO-APP")
            dialog.configurePatternForTesting("{name}{suffix}", renameFind = "(")

            assertTrue(
                "the bad find pattern is named, and the preview survives it",
                dialog.validationMessageForTesting().orEmpty().startsWith("Invalid regex:"),
            )
            assertEquals(
                listOf("DEMO-D010" to "CustomerDto", "DEMO-D011" to "AddressDto"),
                dialog.classNamesForTesting(),
            )
        }
    }
}
