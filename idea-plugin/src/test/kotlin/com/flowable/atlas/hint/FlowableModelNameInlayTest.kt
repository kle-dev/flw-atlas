package com.flowable.atlas.hint

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

/**
 * The model-name hint inside model files: a cross-reference key is followed by the referenced model's
 * name; the file's own key, a name that repeats the key and an unknown key get nothing.
 */
class FlowableModelNameInlayTest : DeclarativeInlayHintsProviderTestCase() {

    private fun addTargets() {
        myFixture.addFileToProject(
            "models/DEMO-P100.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P100" name="Order fulfilment"/></definitions>""",
        )
        myFixture.addFileToProject("models/DEMO-P101.bpmn20.xml", """<definitions><process id="DEMO-P101" name="DEMO-P101"/></definitions>""")
        myFixture.addFileToProject("models/customerService.service", """{"key":"customerService","name":"Customer service","operations":[]}""")
        myFixture.addFileToProject("models/DEMO-E1.event", """{"key":"DEMO-E1","name":"Order placed"}""")
        project.service<FlowableModelIndexService>().index()
    }

    fun testAnXmlCrossReferenceIsLabelledWithTheModelsName() {
        addTargets()
        doTestProvider(
            "caller.bpmn20.xml",
            """
            <definitions xmlns:flowable="http://flowable.org/bpmn">
              <process id="CALLER" name="Caller">
                <callActivity id="a" calledElement="DEMO-P100"/*<# Order fulfilment #>*/ />
                <callActivity id="b" calledElement="DEMO-P101"/>
                <callActivity id="c" calledElement="DEMO-P999"/>
                <startEvent id="s"><extensionElements><flowable:eventType><![CDATA[DEMO-E1/*<# Order placed #>*/]]></flowable:eventType></extensionElements></startEvent>
              </process>
            </definitions>
            """.trimIndent(),
            FlowableModelNameInlayProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }

    fun testAJsonCrossReferenceIsLabelledAndTheOwnKeyIsNot() {
        addTargets()
        doTestProvider(
            "customer.data",
            """{"key": "customerDO", "name": "Customer", "referencedServiceDefinitionModelKey": "customerService"/*<# Customer service #>*/}""",
            FlowableModelNameInlayProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }
}
