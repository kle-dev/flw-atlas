package com.flowable.atlas.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** *Copy Model Key* knows the key under the caret — bare — in Java and in model files, and nothing else. */
class CopyModelKeyActionTest : BasePlatformTestCase() {

    private fun keyAtCaret() = CopyModelKeyAction.keyAt(myFixture.file, myFixture.caretOffset)

    fun testAKeyLiteralAtAFlowableApiSiteInJava() {
        myFixture.addFileToProject(
            "org/flowable/engine/RuntimeService.java",
            "package org.flowable.engine; public interface RuntimeService { Object startProcessInstanceByKey(String key); }",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void f(org.flowable.engine.RuntimeService rs) { rs.startProcessInstanceByKey(\"DEMO-P0<caret>01\"); String s = \"DEMO-P002\"; } }",
        )
        assertEquals("DEMO-P001", keyAtCaret())
        myFixture.configureByText("U.java", "class U { String s = \"DEMO-P0<caret>02\"; }")
        assertNull("a literal at no site is not a key", keyAtCaret())
    }

    fun testAReferenceAndTheOwnKeyInAModelFileButNotAName() {
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="CALLER" name="Caller"><callActivity id="a" calledElement="DEMO-P<caret>100"/></process></definitions>""",
        )
        assertEquals("DEMO-P100", keyAtCaret())
        myFixture.configureByText("own.bpmn20.xml", """<definitions><process id="OW<caret>N" name="Own"/></definitions>""")
        assertEquals("OWN", keyAtCaret())
        myFixture.configureByText("customer.data", """{"key":"customerDO","name":"Cust<caret>omer","referencedServiceDefinitionModelKey":"customerService"}""")
        assertNull(keyAtCaret())
    }
}
