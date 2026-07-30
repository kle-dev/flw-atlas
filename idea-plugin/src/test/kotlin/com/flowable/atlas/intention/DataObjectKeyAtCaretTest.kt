package com.flowable.atlas.intention

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What the DTO quick action recognizes as a data-object key: the argument of a data-object API call —
 * inline literal **or** constant reference, the shape "Generate Model Constants" produces — and, beyond
 * call sites, any string literal/constant whose value is an indexed data-object key. An unrelated
 * literal must stay silent. DEMO-* placeholder keys — this repo is public.
 */
class DataObjectKeyAtCaretTest : BasePlatformTestCase() {

    private fun addModelAndStubs() {
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerQuery.java",
            "package com.flowable.dataobject.api.runtime; public interface DataObjectInstanceVariableContainerQuery { " +
                "DataObjectInstanceVariableContainerQuery definitionKey(String key); }",
        )
        myFixture.addFileToProject(
            "models/DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Customer","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"label","type":"STRING"},{"name":"count","type":"LONG"}]}""",
        )
        project.service<FlowableModelIndexService>().refresh()
    }

    /** The key the resolver sees at the caret of the configured file. */
    private fun keyAtCaret(): String? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        return DataObjectKeyAtCaret.resolve(project, element)
    }

    private fun configure(body: String) {
        myFixture.configureByText(
            "T.java",
            "import com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery;\n" +
                "class Keys { static final String CUSTOMER = \"DEMO-D010\"; }\n" +
                "class T { void m(DataObjectInstanceVariableContainerQuery q) { $body } }",
        )
    }

    fun testLiteralAtTheKeySite() {
        addModelAndStubs()
        configure("q.definitionKey(\"DEMO-D0<caret>10\");")

        assertEquals("DEMO-D010", keyAtCaret())
    }

    fun testConstantAtTheKeySite() {
        addModelAndStubs()
        configure("q.definitionKey(Keys.CUS<caret>TOMER);")

        assertEquals("a constant argument resolves like a literal", "DEMO-D010", keyAtCaret())
    }

    fun testConstantAtTheKeySiteResolvesWithACaretOnTheQualifier() {
        addModelAndStubs()
        configure("q.definitionKey(Ke<caret>ys.CUSTOMER);")

        assertEquals("DEMO-D010", keyAtCaret())
    }

    fun testKeySiteDoesNotNeedTheIndex() {
        addModelAndStubs()
        configure("q.definitionKey(Keys.CUS<caret>TOMER);")
        project.service<FlowableModelIndexService>().invalidate()

        assertEquals("the call site alone is proof of intent", "DEMO-D010", keyAtCaret())
    }

    fun testPlainLiteralThatIsADataObjectKey() {
        addModelAndStubs()
        configure("String k = \"DEMO-D0<caret>10\";")

        assertEquals("a key literal is recognized outside any call site", "DEMO-D010", keyAtCaret())
    }

    fun testConstantOutsideACallSite() {
        addModelAndStubs()
        configure("String k = Keys.CUS<caret>TOMER;")

        assertEquals("DEMO-D010", keyAtCaret())
    }

    fun testUnrelatedLiteralIsNotAKey() {
        addModelAndStubs()
        configure("String k = \"cust<caret>omer\";")

        assertNull("only an exact data-object key match counts", keyAtCaret())
    }

    fun testValueMatchNeedsTheBuiltIndex() {
        addModelAndStubs()
        configure("String k = \"DEMO-D0<caret>10\";")
        project.service<FlowableModelIndexService>().invalidate()

        assertNull("availability must never trigger a project scan", keyAtCaret())
    }

    fun testIntentionGeneratesTheDtoFromAConstant() {
        addModelAndStubs()
        configure("q.definitionKey(Keys.CUS<caret>TOMER);")

        val intentions = myFixture.filterAvailableIntentions("Generate Java DTO for this Flowable data object")
        assertFalse("the DTO intention is offered on a constant", intentions.isEmpty())

        TestDialogManager.setTestInputDialog { _ -> "CustomerDto" }
        try {
            myFixture.launchAction(intentions.first())
        } finally {
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        }

        val dto = myFixture.findFileInTempDir("CustomerDto.java")
        assertNotNull("the DTO file is created", dto)
        val text = String(dto!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("typed field: $text", text.contains("private String label;"))
        assertTrue("mapper: $text", text.contains("fromContainer(DataObjectInstanceVariableContainer container)"))
    }
}
