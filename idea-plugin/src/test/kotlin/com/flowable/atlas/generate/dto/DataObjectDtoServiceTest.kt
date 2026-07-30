package com.flowable.atlas.generate.dto

import com.flowable.atlas.generate.dto.DataObjectDtoService.DtoWrite
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The plan + write pipeline of [DataObjectDtoService]: data objects are grouped under the app that
 * declares them (falling back to the folder/archive they were indexed from), a field-less data object
 * is listed but not generatable, and the write step creates package folders, overwrites by default and
 * keeps existing files when asked. DEMO-* placeholder keys — this repo is public.
 */
class DataObjectDtoServiceTest : BasePlatformTestCase() {

    private fun addModels() {
        myFixture.addFileToProject(
            "app/DEMO-APP.app",
            """{"key":"DEMO-APP","name":"Demo App",
                "extension":{"design":{"childModels":[{"key":"DEMO-D010","type":"dataObject"},
                                                      {"key":"DEMO-D011","type":"dataObject"},
                                                      {"key":"DEMO-P001","type":"bpmn"}]}}}""",
        )
        myFixture.addFileToProject(
            "app/DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Customer","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"label","type":"STRING"},{"name":"count","type":"LONG"}]}""",
        )
        // Declared by the app, but with nothing to map onto a class.
        myFixture.addFileToProject(
            "app/DEMO-D011.data",
            """{"key":"DEMO-D011","name":"Empty","dataObjectType":"serviceRegistryDataObject"}""",
        )
        // Declared by no app at all → grouped by the folder it was indexed from.
        myFixture.addFileToProject(
            "loose/DEMO-D012.data",
            """{"key":"DEMO-D012","name":"Address","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"street","type":"STRING"}]}""",
        )
        project.service<FlowableModelIndexService>().refresh()
    }

    private fun plans() = DataObjectDtoService.getInstance(project).computePlans()

    private fun sourceRoot(): VirtualFile = myFixture.addFileToProject("gen/.anchor", "").virtualFile.parent

    private fun read(root: VirtualFile, rel: String): String? =
        root.findFileByRelativePath(rel)?.let { VfsUtilCore.loadText(it) }

    fun testPlansGroupDataObjectsUnderTheDeclaringApp() {
        addModels()
        val plans = plans()

        assertEquals(listOf("DEMO-D010", "DEMO-D011", "DEMO-D012"), plans.items.map { it.key })
        assertEquals(
            "the app's own child list decides",
            listOf("DEMO-D010", "DEMO-D011"),
            plans.itemsOfApp("DEMO-APP").map { it.key },
        )
        val app = plans.apps.first { !it.synthetic }
        assertEquals("DEMO-APP", app.key)
        assertEquals("Demo App (DEMO-APP)", app.label)
    }

    fun testDataObjectWithoutAnAppFallsBackToItsFolder() {
        addModels()
        val loose = plans().items.first { it.key == "DEMO-D012" }

        val app = loose.apps.single()
        assertTrue("a guessed grouping is flagged", app.synthetic)
        assertEquals("loose", app.key)
        assertEquals("the label says it is a guess", "— (loose)", app.label)
    }

    fun testClassNamesUseTheModelNameAndSuffix() {
        addModels()
        val items = plans().items.associateBy { it.key }

        assertEquals("CustomerDto", items.getValue("DEMO-D010").defaultClassName)
        assertEquals("AddressDto", items.getValue("DEMO-D012").defaultClassName)
    }

    fun testFieldlessDataObjectIsListedButNotGeneratable() {
        addModels()
        val items = plans().items.associateBy { it.key }

        assertTrue(items.getValue("DEMO-D010").generatable)
        assertFalse("nothing to map → nothing to generate", items.getValue("DEMO-D011").generatable)
        assertEquals(2, items.getValue("DEMO-D010").fields.size)
    }

    fun testWriteCreatesPackageFoldersAndOverwrites() {
        val root = sourceRoot()
        val service = DataObjectDtoService.getInstance(project)

        val written = service.writeResolved(
            root,
            listOf(DtoWrite("com/acme/dto/CustomerDto.java", "package com.acme.dto;\n\npublic class CustomerDto {\n}\n")),
            skipExisting = false,
        )

        assertEquals(1, written.size)
        assertTrue(read(root, "com/acme/dto/CustomerDto.java")!!.contains("public class CustomerDto"))

        val second = service.writeResolved(
            root,
            listOf(DtoWrite("com/acme/dto/CustomerDto.java", "package com.acme.dto;\n\npublic class CustomerDto {\n    // v2\n}\n")),
            skipExisting = false,
        )

        assertEquals("the same file is overwritten, not duplicated", 1, second.size)
        assertTrue("the regenerated content wins", read(root, "com/acme/dto/CustomerDto.java")!!.contains("// v2"))
    }

    fun testSkipExistingKeepsTheCurrentContent() {
        val root = sourceRoot()
        val service = DataObjectDtoService.getInstance(project)
        val handEdited = "package com.acme.dto;\n\npublic class CustomerDto {\n    // hand-edited\n}\n"
        myFixture.addFileToProject("gen/com/acme/dto/CustomerDto.java", handEdited)

        val written = service.writeResolved(
            root,
            listOf(DtoWrite("com/acme/dto/CustomerDto.java", "package com.acme.dto;\n\npublic class CustomerDto {\n}\n")),
            skipExisting = true,
        )

        assertTrue("an existing file is not reported as written", written.isEmpty())
        assertTrue("its content is kept", read(root, "com/acme/dto/CustomerDto.java")!!.contains("// hand-edited"))
    }
}
