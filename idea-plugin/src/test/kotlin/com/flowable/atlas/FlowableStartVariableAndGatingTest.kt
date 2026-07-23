package com.flowable.atlas

import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for two completion behaviours:
 *
 *  - AC-1: start-variable completion at `ProcessInstanceBuilder.variable(...)` /
 *    `CaseInstanceBuilder.variable(...)` / `.transientVariable(...)`, scoped to the sibling
 *    `processDefinitionKey`/`caseDefinitionKey` in the same builder chain (else the project-wide union).
 *  - AC-4: the vocabulary / member domains are offered by default once a non-empty prefix is typed
 *    (regardless of the `extraCompletions` setting), while that setting only gates the aggressive
 *    empty-prefix listing.
 *
 * [FlowableAtlasSettings] is an application service, so the flag is saved/restored around each test.
 */
class FlowableStartVariableAndGatingTest : BasePlatformTestCase() {

    private val settings get() = FlowableAtlasSettings.getInstance()
    private var originalExtraCompletions = true

    override fun setUp() {
        super.setUp()
        originalExtraCompletions = settings.extraCompletions
    }

    override fun tearDown() {
        try {
            settings.extraCompletions = originalExtraCompletions
        } finally {
            super.tearDown()
        }
    }

    private fun addBuilderStubs() {
        myFixture.addFileToProject(
            "org/flowable/engine/runtime/ProcessInstanceBuilder.java",
            "package org.flowable.engine.runtime; " +
                "public interface ProcessInstanceBuilder { " +
                "ProcessInstanceBuilder processDefinitionKey(String key); " +
                "ProcessInstanceBuilder variable(String name, Object value); " +
                "ProcessInstanceBuilder transientVariable(String name, Object value); }",
        )
        myFixture.addFileToProject(
            "org/flowable/cmmn/api/runtime/CaseInstanceBuilder.java",
            "package org.flowable.cmmn.api.runtime; " +
                "public interface CaseInstanceBuilder { " +
                "CaseInstanceBuilder caseDefinitionKey(String key); " +
                "CaseInstanceBuilder variable(String name, Object value); " +
                "CaseInstanceBuilder transientVariable(String name, Object value); }",
        )
    }

    private fun addQueryStubs() {
        myFixture.addFileToProject(
            "org/flowable/engine/runtime/ProcessInstanceQuery.java",
            "package org.flowable.engine.runtime; " +
                "public interface ProcessInstanceQuery { " +
                "ProcessInstanceQuery processDefinitionKey(String key); " +
                "ProcessInstanceQuery variableValueEquals(String name, Object value); }",
        )
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/MasterDataInstanceQuery.java",
            "package com.flowable.dataobject.api.runtime; " +
                "public interface MasterDataInstanceQuery { " +
                "MasterDataInstanceQuery definitionKey(String key); " +
                "MasterDataInstanceQuery variableValueEquals(String name, Object value); }",
        )
    }

    // Two dataObjects so the completion popup stays open (a lone match would auto-insert).
    private fun addProcess(key: String, vararg variables: String) {
        val objects = variables.mapIndexed { i, v -> """<dataObject id="do$i" name="$v"/>""" }.joinToString("")
        myFixture.addFileToProject(
            "models/$key.bpmn20.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                <process id="$key" name="$key">$objects</process>
                </definitions>""",
        )
    }

    // ---------------------------------------------------------------- AC-1: builder start variables

    fun testProcessBuilderVariableScopedCompletion() {
        settings.extraCompletions = true
        addBuilderStubs()
        addProcess("DEMO-P001", "orderId", "orderTotal")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) { " +
                "b.processDefinitionKey(\"DEMO-P001\").variable(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected orderId among $strings", strings.contains("orderId"))
        assertTrue("expected orderTotal among $strings", strings.contains("orderTotal"))
    }

    fun testProcessBuilderTransientVariableScopedCompletion() {
        settings.extraCompletions = true
        addBuilderStubs()
        addProcess("DEMO-P002", "amount", "amountCurrency")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) { " +
                "b.processDefinitionKey(\"DEMO-P002\").transientVariable(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected amount among $strings", strings.contains("amount"))
    }

    fun testCaseBuilderVariableScopedCompletion() {
        settings.extraCompletions = true
        addBuilderStubs()
        myFixture.addFileToProject(
            "models/DEMO-C020.cmmn",
            """<definitions><case id="DEMO-C020" name="Demo Case"><casePlanModel>""" +
                """<dataObject id="do1" name="applicantName"/><dataObject id="do2" name="applicantAge"/>""" +
                """</casePlanModel></case></definitions>""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { " +
                "b.caseDefinitionKey(\"DEMO-C020\").variable(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected applicantName among $strings", strings.contains("applicantName"))
        assertTrue("expected applicantAge among $strings", strings.contains("applicantAge"))
    }

    fun testProcessBuilderVariableFallsBackToUnionWithoutSiblingKey() {
        settings.extraCompletions = true
        addBuilderStubs()
        addProcess("DEMO-P003", "riskScore", "riskLevel")
        // No processDefinitionKey sibling → project-wide variable union.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) { b.variable(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected riskScore among $strings", strings.contains("riskScore"))
    }

    // ---------------------------------------------------------------- AC-4: default-on gating

    fun testVocabularyFiresAtNonEmptyPrefixWhenSettingOff() {
        settings.extraCompletions = false
        addQueryStubs()
        addProcess("DEMO-P010", "orderId", "orderTotal")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceQuery q) { " +
                "q.processDefinitionKey(\"DEMO-P010\").variableValueEquals(\"order<caret>\", \"x\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("vocab must be offered at a non-empty prefix even with the setting off: $strings", strings.contains("orderId"))
        assertTrue("vocab must be offered at a non-empty prefix even with the setting off: $strings", strings.contains("orderTotal"))
    }

    fun testVocabularySuppressedAtEmptyPrefixWhenSettingOff() {
        settings.extraCompletions = false
        addQueryStubs()
        addProcess("DEMO-P011", "orderId", "orderTotal")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceQuery q) { " +
                "q.processDefinitionKey(\"DEMO-P011\").variableValueEquals(\"<caret>\", \"x\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertFalse("vocab must be suppressed at an empty prefix when the setting is off: $strings", strings.contains("orderId"))
    }

    fun testVocabularyFiresAtEmptyPrefixWhenSettingOn() {
        settings.extraCompletions = true
        addQueryStubs()
        addProcess("DEMO-P012", "orderId", "orderTotal")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceQuery q) { " +
                "q.processDefinitionKey(\"DEMO-P012\").variableValueEquals(\"<caret>\", \"x\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("vocab must be listed at an empty prefix when the setting is on: $strings", strings.contains("orderId"))
    }

    fun testMemberSiteFiresAtNonEmptyPrefixWhenSettingOff() {
        settings.extraCompletions = false
        addQueryStubs()
        myFixture.addFileToProject(
            "models/DEMO-MD1.masterdata",
            """{"key":"DEMO-MD1","name":"Country","variables":{"alpha2Code":"a2","alpha3Code":"a3"}}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.MasterDataInstanceQuery q) { " +
                "q.definitionKey(\"DEMO-MD1\").variableValueEquals(\"alpha<caret>\", \"x\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("master-data field must be offered at a non-empty prefix even with the setting off: $strings", strings.contains("alpha2Code"))
        assertTrue("master-data field must be offered at a non-empty prefix even with the setting off: $strings", strings.contains("alpha3Code"))
    }
}
