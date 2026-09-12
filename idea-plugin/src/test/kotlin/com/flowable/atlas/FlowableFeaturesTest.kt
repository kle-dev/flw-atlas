package com.flowable.atlas

import com.flowable.atlas.inspection.FlowableBrokenKeyInspection
import com.flowable.atlas.liquibase.LiquibaseCoverageInspection
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for the v2 features: new completion domains, the DMN cascade, the broken-key
 * inspection (+ quick fix), key navigation, and the Liquibase-coverage inspection.
 */
class FlowableFeaturesTest : BasePlatformTestCase() {

    private fun addStubs() {
        myFixture.addFileToProject(
            "org/flowable/engine/RuntimeService.java",
            "package org.flowable.engine; public interface RuntimeService { " +
                "Object startProcessInstanceByMessage(String messageName); " +
                "void signalEventReceived(String signalName); " +
                "Object getVariable(String executionId, String variableName); }",
        )
        myFixture.addFileToProject(
            "org/flowable/task/api/TaskInfoQuery.java",
            "package org.flowable.task.api; public interface TaskInfoQuery { TaskInfoQuery taskDefinitionKey(String key); }",
        )
        myFixture.addFileToProject(
            "org/flowable/dmn/api/ExecuteDecisionBuilder.java",
            "package org.flowable.dmn.api; public interface ExecuteDecisionBuilder { " +
                "ExecuteDecisionBuilder decisionKey(String key); " +
                "ExecuteDecisionBuilder variable(String name, Object value); }",
        )
        myFixture.addFileToProject(
            "org/flowable/cmmn/api/runtime/CaseInstanceBuilder.java",
            "package org.flowable.cmmn.api.runtime; public interface CaseInstanceBuilder { CaseInstanceBuilder caseDefinitionKey(String key); }",
        )
    }

    private fun addProcessWithMembers() {
        myFixture.addFileToProject(
            "models/P1.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <message id="m1" name="orderPlaced"/>
                 <signal id="s1" name="cancelSignal"/>
                 <process id="DEMO-P001" name="Proc">
                   <dataObject id="d1" name="customerName"/>
                   <userTask id="reviewTask"/>
                 </process>
               </definitions>""",
        )
    }

    fun testMessageNameCompletion() {
        addStubs(); addProcessWithMembers()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.RuntimeService s) { s.startProcessInstanceByMessage(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("orderPlaced"))
        // A message is a named definition — it wears the constant icon, like every other vocabulary item.
        assertSame(AllIcons.Nodes.Constant, iconOf("orderPlaced"))
    }

    fun testVariableNameCompletionAtArgOne() {
        addStubs(); addProcessWithMembers()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.RuntimeService s) { s.getVariable(\"exec\", \"<caret>\"); } }",
        )
        myFixture.completeBasic()
        assertTrue("expected variable customerName", myFixture.lookupElementStrings.orEmpty().contains("customerName"))
    }

    fun testTaskDefinitionKeyCompletion() {
        addStubs(); addProcessWithMembers()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.task.api.TaskInfoQuery q) { q.taskDefinitionKey(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        assertTrue("expected userTask reviewTask", myFixture.lookupElementStrings.orEmpty().contains("reviewTask"))
    }

    fun testDmnVariableCascade() {
        addStubs()
        myFixture.addFileToProject(
            "models/D1.dmn",
            """<definitions><decision id="DEMO-DM1" name="Dec"><decisionTable>
                 <input label="age"><inputExpression id="e1"><text>age</text></inputExpression></input>
                 <output name="result"/>
               </decisionTable></decision></definitions>""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.dmn.api.ExecuteDecisionBuilder b) { b.decisionKey(\"DEMO-DM1\").variable(\"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected decision variable age among $strings", strings.contains("age"))
        assertTrue("expected decision variable result among $strings", strings.contains("result"))
    }

    fun testBrokenKeyInspectionFlagsUnknown() {
        addStubs()
        myFixture.addFileToProject("models/DEMO-C001.cmmn", """<definitions><case id="DEMO-C001" name="C"/></definitions>""")
        myFixture.enableInspections(FlowableBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"DEMO-C999\"); } }",
        )
        val infos = myFixture.doHighlighting()
        assertTrue("expected unknown-key warning", infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testBrokenKeyInspectionAcceptsKnown() {
        addStubs()
        myFixture.addFileToProject("models/DEMO-C001.cmmn", """<definitions><case id="DEMO-C001" name="C"/></definitions>""")
        myFixture.enableInspections(FlowableBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"DEMO-C001\"); } }",
        )
        val infos = myFixture.doHighlighting()
        assertFalse("valid key must not be flagged", infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testBrokenKeyQuickFixReplacesWithClosest() {
        addStubs()
        myFixture.addFileToProject("models/DEMO-C001.cmmn", """<definitions><case id="DEMO-C001" name="C"/></definitions>""")
        myFixture.enableInspections(FlowableBrokenKeyInspection::class.java)
        // Caret inside the flagged literal so the quick fix is offered at the caret position.
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"DEMO-C0<caret>02\"); } }",
        )
        myFixture.doHighlighting()
        val fix = myFixture.availableIntentions.firstOrNull { it.text.contains("Replace with 'DEMO-C001'") }
        assertNotNull("expected a replace quick fix", fix)
        myFixture.launchAction(fix!!)
        assertTrue(myFixture.file.text.contains("caseDefinitionKey(\"DEMO-C001\")"))
    }

    fun testKeyReferenceResolvesToModelFile() {
        addStubs()
        myFixture.addFileToProject("models/DEMO-C001.cmmn", """<definitions><case id="DEMO-C001" name="C"/></definitions>""")
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(\"DEMO-C0<caret>01\"); } }",
        )
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a Flowable key reference", ref)
        val target = ref!!.resolve()
        assertEquals("reference must resolve into the model file", "DEMO-C001.cmmn", target?.containingFile?.name)
        // …and onto the key's declaration, not line 1: the `id` of the case element
        val text = target!!.containingFile.text
        assertEquals(text.indexOf("DEMO-C001"), target.textRange.startOffset + (target.text.indexOf("DEMO-C001").coerceAtLeast(0)))
    }

    /** A data object + its backing service, declaring the operation `create` with input `label`. */
    private fun addDataObjectOperationFixtures() {
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerQuery.java",
            "package com.flowable.dataobject.api.runtime; public interface DataObjectInstanceVariableContainerQuery { " +
                "DataObjectInstanceVariableContainerQuery definitionKey(String key); " +
                "DataObjectInstanceVariableContainerQuery operation(String operation); " +
                "DataObjectInstanceVariableContainerQuery value(String name, Object value); }",
        )
        myFixture.addFileToProject(
            "models/DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Shopping List","referencedServiceDefinitionModelKey":"DEMO-S010"}""",
        )
        myFixture.addFileToProject(
            "models/DEMO-S010.service",
            """{"key":"DEMO-S010","name":"Source","operations":[{"key":"create","inputParameters":[{"name":"label","type":"string"}]}]}""",
        )
    }

    fun testOperationReferenceResolvesToBackingServiceModel() {
        addDataObjectOperationFixtures()
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"cre<caret>ate\"); } }",
        )
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a Flowable operation reference", ref)
        val target = ref!!.resolve()
        assertEquals("operation must resolve into the backing service model", "DEMO-S010.service", target?.containingFile?.name)
    }

    fun testValueFieldReferenceResolvesToBackingServiceModel() {
        addDataObjectOperationFixtures()
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D010\").operation(\"create\").value(\"lab<caret>el\", null); } }",
        )
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a Flowable value-field reference", ref)
        val target = ref!!.resolve()
        assertEquals("value field must resolve into the backing service model", "DEMO-S010.service", target?.containingFile?.name)
    }

    private fun addDatabaseService() {
        myFixture.addFileToProject(
            "models/DEMO-S010.service",
            """{"key":"DEMO-S010","type":"database","tableName":"DB_ORDER",
                "columnMappings":[{"name":"id","columnName":"ID_","type":"STRING"},
                                  {"name":"label","columnName":"LABEL_","type":"STRING"},
                                  {"name":"count","columnName":"COUNT_","type":"LONG"}]}""",
        )
    }

    fun testLiquibaseCoverageFlagsUnmappedColumn() {
        addDatabaseService()
        myFixture.enableInspections(LiquibaseCoverageInspection::class.java)
        myFixture.configureByText(
            "order.data.changelog.xml",
            """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                 <property name="serviceDefinitionReferences" value="DEMO-S010"/>
                 <changeSet id="1" author="flowable">
                   <createTable tableName="DB_ORDER">
                     <column name="ID_" type="varchar(255)"/>
                     <column name="LABEL_" type="varchar(255)"/>
                     <column name="BOGUS_" type="varchar(255)"/>
                   </createTable>
                 </changeSet>
               </databaseChangeLog>""",
        )
        val infos = myFixture.doHighlighting()
        val finding = infos.firstOrNull { (it.description ?: "").contains("is not mapped") }
        assertNotNull("expected BOGUS_ flagged", finding)
        // The message names the service it compared against, not "the backing model".
        assertTrue(finding!!.description, finding.description.contains("'DEMO-S010'"))
    }

    fun testLiquibaseColumnCompletion() {
        addDatabaseService()
        myFixture.configureByText(
            "order.data.changelog.xml",
            """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                 <property name="serviceDefinitionReferences" value="DEMO-S010"/>
                 <changeSet id="1" author="flowable">
                   <insert tableName="DB_ORDER">
                     <column name="<caret>" value="x"/>
                   </insert>
                 </changeSet>
               </databaseChangeLog>""",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        assertSame(AllIcons.Nodes.DataColumn, iconOf(strings.first { it.endsWith("_") }))
        assertTrue("expected physical column ID_ among $strings", strings.contains("ID_"))
        assertTrue("expected physical column LABEL_ among $strings", strings.contains("LABEL_"))
    }

    /** The rendered icon of the lookup item [lookupString], or null when it is not offered. */
    private fun iconOf(lookupString: String): javax.swing.Icon? =
        myFixture.lookupElements.orEmpty().firstOrNull { it.lookupString == lookupString }
            ?.let { val p = LookupElementPresentation(); it.renderElement(p); p.icon }

    fun testLiquibaseColumnTypeCompletion() {
        addDatabaseService()
        myFixture.configureByText(
            "order.data.changelog.xml",
            """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                 <property name="serviceDefinitionReferences" value="DEMO-S010"/>
                 <changeSet id="1" author="flowable">
                   <createTable tableName="DB_ORDER">
                     <column name="COUNT_" type="<caret>"/>
                   </createTable>
                 </changeSet>
               </databaseChangeLog>""",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        // COUNT_ maps to LONG → bigint, ranked first (the palette is offered as fallback too).
        assertEquals("mapped type must rank first: $strings", "bigint", strings.firstOrNull())
        assertTrue("varchar palette entry expected too: $strings", strings.any { it.contains("varchar.type") })
    }

    fun testLiquibaseCoverageAcceptsMappedColumns() {
        addDatabaseService()
        myFixture.enableInspections(LiquibaseCoverageInspection::class.java)
        myFixture.configureByText(
            "order.data.changelog.xml",
            """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                 <property name="serviceDefinitionReferences" value="DEMO-S010"/>
                 <changeSet id="1" author="flowable">
                   <createTable tableName="DB_ORDER">
                     <column name="ID_" type="varchar(255)"/>
                     <column name="LABEL_" type="varchar(255)"/>
                   </createTable>
                 </changeSet>
               </databaseChangeLog>""",
        )
        val infos = myFixture.doHighlighting()
        assertFalse("mapped columns must not be flagged", infos.any { (it.description ?: "").contains("is not mapped") })
    }

    fun testDataObjectBeanIntentionGeneratesTypedPojo() {
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerQuery.java",
            "package com.flowable.dataobject.api.runtime; public interface DataObjectInstanceVariableContainerQuery { " +
                "DataObjectInstanceVariableContainerQuery definitionKey(String key); }",
        )
        myFixture.addFileToProject(
            "models/DEMO-D010.data",
            """{"key":"DEMO-D010","name":"Shopping List","dataObjectType":"serviceRegistryDataObject",
                "fieldMappings":[{"name":"label","type":"STRING"},{"name":"region","type":"STRING"}]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"DEMO-D0<caret>10\"); } }",
        )
        val intention = myFixture.findSingleIntention("Generate Java DTO for this Flowable data object")
        assertNotNull("DTO intention should be offered", intention)
        // The intention asks for the class name; stub the input dialog to a custom name.
        TestDialogManager.setTestInputDialog { _ -> "MyOrderDto" }
        try {
            myFixture.launchAction(intention)
        } finally {
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        }

        val bean = myFixture.findFileInTempDir("MyOrderDto.java")
        assertNotNull("bean file should be created with the chosen name", bean)
        val text = String(bean!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("bean class: $text", text.contains("public class MyOrderDto"))
        assertTrue("typed field: $text", text.contains("private String label;"))
        assertTrue("mapper: $text", text.contains("fromContainer(DataObjectInstanceVariableContainer container)"))
    }
}
