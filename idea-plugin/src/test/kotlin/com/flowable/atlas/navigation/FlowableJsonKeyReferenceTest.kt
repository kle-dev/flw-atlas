package com.flowable.atlas.navigation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A model key inside a JSON model is a reference: Ctrl+click lands on the referenced model's key.
 * The sites are the ones the CLI's parsers record, so a key the report draws an edge for is a key
 * the IDE jumps from — and a name, label or the model's own key is not.
 */
class FlowableJsonKeyReferenceTest : BasePlatformTestCase() {

    private fun addTargets() {
        myFixture.addFileToProject("models/customerService.service", """{"key":"customerService","name":"Customer service","type":"rest","operations":[]}""")
        myFixture.addFileToProject("models/orderForm.form", """{"key":"orderForm","name":"Order form","components":[]}""")
        myFixture.addFileToProject("models/DEMO-P100.bpmn20.xml", """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P100" name="Target"/></definitions>""")
    }

    private fun resolvedFileAtCaret(): String? {
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a Flowable JSON key reference", ref)
        val target = ref!!.resolve()
        assertNotNull("the reference must resolve", target)
        assertTrue("…onto the key's declaration, not the top of the file", target!!.textRange.startOffset > 0)
        return target.containingFile?.name
    }

    fun testDataObjectsBackingServiceResolvesToTheServiceModel() {
        addTargets()
        myFixture.configureByText(
            "customer.data",
            """{"key":"customerDO","name":"Customer","referencedServiceDefinitionModelKey":"customer<caret>Service","fieldMappings":[]}""",
        )
        assertEquals("customerService.service", resolvedFileAtCaret())
    }

    fun testANestedComponentsSubformResolvesThroughTheRefObject() {
        addTargets()
        myFixture.configureByText(
            "review.form",
            """{"key":"reviewForm","name":"Review","components":[{"type":"panel","components":[
                 {"type":"subform","id":"orderSub","extraSettings":{"formRef":{"id":"FORM_MODEL-1","key":"order<caret>Form"}}}]}]}""",
        )
        assertEquals("orderForm.form", resolvedFileAtCaret())
    }

    fun testADocumentsFormsAndAnActionsStartProcessResolve() {
        addTargets()
        myFixture.configureByText("contract.document", """{"key":"contract","name":"Contract","forms":{"view":"order<caret>Form","edit":{"id":"x","key":"orderForm"}}}""")
        assertEquals("orderForm.form", resolvedFileAtCaret())
        myFixture.configureByText(
            "start.action",
            """{"key":"startOrder","name":"Start order","botKey":"bpmn-start-process-instance-bot","signalName":"DEMO-P1<caret>00"}""",
        )
        assertEquals("DEMO-P100.bpmn20.xml", resolvedFileAtCaret())
    }

    fun testASignalNameOnAnOtherBotIsNotAProcessReference() {
        addTargets()
        myFixture.configureByText(
            "notify.action",
            """{"key":"notify","name":"Notify","botKey":"send-signal-bot","signalName":"DEMO-P1<caret>00"}""",
        )
        assertNull("a signal is not a model key on this bot", myFixture.getReferenceAtCaretPosition())
    }

    fun testNamesLabelsAndTheModelsOwnKeyAreNoReferences() {
        addTargets()
        myFixture.configureByText("customer.data", """{"key":"customer<caret>Service","name":"Customer","referencedServiceDefinitionModelKey":"customerService"}""")
        assertNull("the model's own key is a declaration, not a reference", myFixture.getReferenceAtCaretPosition())
        myFixture.configureByText("customer2.data", """{"key":"customerDO","name":"customer<caret>Service","referencedServiceDefinitionModelKey":"customerService"}""")
        assertNull("a name that happens to equal a key is not a reference", myFixture.getReferenceAtCaretPosition())
        myFixture.configureByText("binding.form", """{"key":"f","components":[{"type":"subform","extraSettings":{"formRef":"{{sub<caret>Key}}"}}]}""")
        assertNull("a binding is resolved at run time", myFixture.getReferenceAtCaretPosition())
    }
}
