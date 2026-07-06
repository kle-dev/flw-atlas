package com.flowable.keys

import com.flowable.keys.inspection.FlowableValueFieldInspection
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for the Java completion: stub Flowable API interfaces + real model fixtures,
 * then assert the right keys / operations / value fields are offered.
 */
class FlowableCompletionTest : BasePlatformTestCase() {

    private fun addFlowableStubs() {
        myFixture.addFileToProject(
            "org/flowable/cmmn/api/runtime/CaseInstanceBuilder.java",
            "package org.flowable.cmmn.api.runtime; " +
                "public interface CaseInstanceBuilder { CaseInstanceBuilder caseDefinitionKey(String key); }",
        )
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerQuery.java",
            "package com.flowable.dataobject.api.runtime; " +
                "public interface DataObjectInstanceVariableContainerQuery { " +
                "DataObjectInstanceVariableContainerQuery definitionKey(String key); " +
                "DataObjectInstanceVariableContainerQuery operation(String operation); " +
                "DataObjectInstanceVariableContainerQuery value(String name, Object value); }",
        )
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerBuilder.java",
            "package com.flowable.dataobject.api.runtime; " +
                "public interface DataObjectInstanceVariableContainerBuilder { " +
                "DataObjectInstanceVariableContainerBuilder definitionKey(String key); " +
                "DataObjectInstanceVariableContainerBuilder operation(String operation); " +
                "DataObjectInstanceVariableContainerBuilder value(String name, Object value); }",
        )
    }

    fun testCaseKeyCompletion() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-C002.cmmn",
            """<definitions><case id="DEMO-C002" name="Issue Case"/></definitions>""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected DEMO-C001 among $strings", strings.contains("DEMO-C001"))
        assertTrue("expected DEMO-C002 among $strings", strings.contains("DEMO-C002"))
    }

    fun testCaseKeyCompletionWithoutQuotes() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-C002.cmmn",
            """<definitions><case id="DEMO-C002" name="Issue Case"/></definitions>""",
        )
        // No quotes typed — caret at the bare argument position.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(<caret>); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected DEMO-C001 offered without quotes among $strings", strings.contains("DEMO-C001"))
        assertTrue("expected DEMO-C002 offered without quotes among $strings", strings.contains("DEMO-C002"))
    }

    fun testBareKeyInsertsQuotedString() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        // Type a valid-identifier prefix that uniquely matches one key so completion auto-inserts it.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(DEMO<caret>); } }",
        )
        myFixture.completeBasic()
        // The bare argument must be replaced by a quoted string literal.
        assertTrue(myFixture.file.text.contains("caseDefinitionKey(\"DEMO-C001\")"))
    }

    fun testCaseKeySearchableByName() {
        addFlowableStubs()
        // Two cases whose names both contain "Periodic" (>=2 keeps the popup open rather than
        // auto-inserting a single match).
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-C009.cmmn",
            """<definitions><case id="DEMO-C009" name="Periodic Audit"/></definitions>""",
        )
        // Type part of the NAME ("Periodic"); expect the keys to still be offered.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"Periodic<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected DEMO-C001 matched by name among $strings", strings.contains("DEMO-C001"))
        assertTrue("expected DEMO-C009 matched by name among $strings", strings.contains("DEMO-C009"))
    }

    fun testDataObjectOperationCascade() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Shopping List","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","name":"Source","operations":[""" +
                """{"key":"findAll","name":"Find All","type":"search","inputParameters":[]},""" +
                """{"key":"create","name":"Create","type":"create","inputParameters":[{"name":"label","type":"string"}]}]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected findAll among $strings", strings.contains("findAll"))
        assertTrue("expected create among $strings", strings.contains("create"))
    }

    fun testOperationCascadeResolvesConstant() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "app/ModelConstants.java",
            "package app; public final class ModelConstants { public static final String SHOPPING_LIST = \"DEMO-D010\"; }",
        )
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","operations":[{"key":"findAll","inputParameters":[]},{"key":"create","inputParameters":[]}]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(app.ModelConstants.SHOPPING_LIST).operation(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected findAll (via constant) among $strings", strings.contains("findAll"))
        assertTrue("expected create (via constant) among $strings", strings.contains("create"))
    }

    fun testValueFieldCascade() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","operations":[{"key":"create","inputParameters":[""" +
                """{"name":"label","type":"string"},{"name":"region","type":"string"}]}]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"create\").value(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected label among $strings", strings.contains("label"))
        assertTrue("expected region among $strings", strings.contains("region"))
    }

    fun testDataObjectValueBuilderDefinitionKey() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Shopping List","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/data-object-DEMO-D017.data",
            """{"key":"DEMO-D017","name":"Crew Leader","referencedServiceDefinitionModelKey":"DEMO-S017"}""",
        )
        // The `createDataObjectValueInstanceBuilder()` builder type (regression from the screenshot).
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerBuilder b) { b.definitionKey(<caret>); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected DEMO-D010 among $strings", strings.contains("DEMO-D010"))
        assertTrue("expected DEMO-D017 among $strings", strings.contains("DEMO-D017"))
    }

    fun testBuilderOperationCascade() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","operations":[{"key":"findAll","inputParameters":[]},{"key":"create","inputParameters":[]}]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerBuilder b) { " +
                "b.definitionKey(\"DEMO-D010\").operation(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected findAll on builder among $strings", strings.contains("findAll"))
        assertTrue("expected create on builder among $strings", strings.contains("create"))
    }

    fun testShowAllKeysOnSecondInvocation() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/form-DEMO-F001.form",
            """{"metadata":{"key":"DEMO-F001","name":"Create Document"}}""",
        )
        // A plain string literal, not a Flowable API argument.
        myFixture.configureByText("T.java", "class T { String s = \"<caret>\"; }")
        myFixture.complete(CompletionType.BASIC, 2) // second invocation → all keys
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected case key DEMO-C001 among all-keys $strings", strings.contains("DEMO-C001"))
        assertTrue("expected form key DEMO-F001 among all-keys $strings", strings.contains("DEMO-F001"))
    }

    fun testFlowableKeysRankedFirst() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-C002.cmmn",
            """<definitions><case id="DEMO-C002" name="Issue Case"/></definitions>""",
        )
        // `caseId` is a type-matching String param that would normally rank at the top.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b, String caseId) { b.caseDefinitionKey(<caret>); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        val key = strings.indexOf("DEMO-C001")
        val local = strings.indexOf("caseId")
        assertTrue("DEMO-C001 must be offered: $strings", key >= 0)
        if (local >= 0) {
            assertTrue("Flowable key must rank before local 'caseId' (key=$key local=$local): $strings", key < local)
        }
    }

    fun testCaseKeyRankedAboveFields() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-C002.cmmn",
            """<definitions><case id="DEMO-C002" name="Issue Case"/></definitions>""",
        )
        // Mirror the screenshot: several type-matching String fields compete.
        myFixture.configureByText(
            "T.java",
            "class T { String onDemandPropertyConfigId; String controlType; String caseId; " +
                "void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(<caret>); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        val key = strings.indexOf("DEMO-C001")
        val field = strings.indexOf("onDemandPropertyConfigId")
        assertTrue("DEMO-C001 must be offered: $strings", key >= 0)
        if (field >= 0) {
            assertTrue("DEMO-C001 (@$key) must rank before field onDemandPropertyConfigId (@$field): $strings", key < field)
        }
    }

    fun testPrefixInPlainStringOffersMatchingKeys() {
        addFlowableStubs()
        myFixture.addFileToProject("models/form-DEMO-F001.form", """{"metadata":{"key":"DEMO-F001","name":"Doc"}}""")
        myFixture.addFileToProject("models/form-DEMO-F002.form", """{"metadata":{"key":"DEMO-F002","name":"Doc2"}}""")
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        // Plain string literal (not a Flowable API arg) with a typed prefix.
        myFixture.configureByText("T.java", "class T { String s = \"DEMO-F<caret>\"; }")
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected DEMO-F001 for prefix DEMO-F: $strings", strings.contains("DEMO-F001"))
        assertTrue("expected DEMO-F002 for prefix DEMO-F: $strings", strings.contains("DEMO-F002"))
        assertFalse("DEMO-C001 must not match prefix DEMO-F: $strings", strings.contains("DEMO-C001"))
    }

    fun testValueFieldInspectionFlagsUnknownField() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","operations":[{"key":"create","inputParameters":[{"name":"label","type":"string"}]}]}""",
        )
        myFixture.enableInspections(FlowableValueFieldInspection::class.java)
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"create\").value(\"x\", \"\"); } }",
        )
        val infos = myFixture.doHighlighting()
        assertTrue(
            "expected a warning for invalid value 'x'",
            infos.any { (it.description ?: "").contains("is not an input value") },
        )
    }

    fun testValueFieldInspectionAcceptsValidField() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/data-object-DEMO-D010.data",
            """{"key":"DEMO-D010","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/service-DEMO-S010.service",
            """{"key":"DEMO-S010","operations":[{"key":"create","inputParameters":[{"name":"label","type":"string"}]}]}""",
        )
        myFixture.enableInspections(FlowableValueFieldInspection::class.java)
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"create\").value(\"label\", \"\"); } }",
        )
        val infos = myFixture.doHighlighting()
        assertFalse(
            "valid field 'label' must not be flagged",
            infos.any { (it.description ?: "").contains("is not an input value") },
        )
    }

    fun testNoCompletionForUnknownMethod() {
        addFlowableStubs()
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn",
            """<definitions><case id="DEMO-C001" name="Periodic Review"/></definitions>""",
        )
        // A non-Flowable method must NOT get key completion.
        myFixture.configureByText(
            "T.java",
            "class T { void m() { String s = String.valueOf(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertFalse("must not offer keys for unrelated methods: $strings", strings.contains("DEMO-C001"))
    }
}
