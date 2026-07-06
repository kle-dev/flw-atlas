package com.flowable.keys

import com.flowable.keys.inspection.FlowableBrokenKeyInspection
import com.flowable.keys.liquibase.LiquibaseCoverageInspection
import com.intellij.psi.PsiFile
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
        assertTrue("reference must resolve to the model file", target is PsiFile && (target as PsiFile).name == "DEMO-C001.cmmn")
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
        assertTrue("expected BOGUS_ flagged", infos.any { (it.description ?: "").contains("is not mapped") })
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
        assertTrue("expected physical column ID_ among $strings", strings.contains("ID_"))
        assertTrue("expected physical column LABEL_ among $strings", strings.contains("LABEL_"))
    }

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
}
