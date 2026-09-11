package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `${…}` inside a script body is the script language's own string interpolation, not a Flowable
 * expression: a Groovy `"${user?.firstName}"` used to come back as an invalid backend expression, its
 * `flw`/`flwTimeUtils` helpers as beans the model calls, and its locals as variables.
 */
class ScriptBodyHarvestTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-script-body-test").toFile()
            File(dir, "greet.action").writeText(
                """{"key":"greet","name":"Greet","botKey":"platform-script-evaluation-bot","scopeType":"cmmn",
                    "config":{"scriptInfo":{"language":"groovy",
                    "script":"def name = \"${'$'}{user?.firstName ?: ''} ${'$'}{user?.lastName ?: ''}\".trim()\nflw.setOutput('greeting', flwTimeUtils.now().toString() + name)"}}}""")
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="p">
                       <scriptTask id="s" scriptFormat="groovy"><script><![CDATA[def line = "${'$'}{greeting} for ${'$'}{execution.getVariable('n')}"]]></script></scriptTask>
                       <serviceTask id="a" flowable:expression="${'$'}{realBean.run()}"/>
                       <userTask id="u" flowable:assignee="${'$'}{approver}"/>
                     </process>
                   </definitions>""")
            File(dir, "c.cmmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/cmmn">
                     <case id="c"><casePlanModel id="plan">
                       <planItem id="pi1" definitionRef="t1"/>
                       <task id="t1" flowable:type="script" flowable:scriptFormat="groovy">
                         <extensionElements><flowable:field name="script"><flowable:string><![CDATA[caseInstance.setVariable('x', "${'$'}{owner} owns it")]]></flowable:string></flowable:field></extensionElements>
                       </task>
                     </casePlanModel></case>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ids(): List<String> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"].toString() }

    @Suppress("UNCHECKED_CAST")
    private fun findings(): List<Map<String, Any?>> = result["findings"] as List<Map<String, Any?>>

    @Test
    fun scriptInterpolationIsNotAnExpression() {
        val ids = ids()
        for (gstring in listOf("user?.firstName", "\${greeting}", "\${owner}")) {
            assertFalse("`$gstring` from a script body was harvested as an expression", ids.any { it.startsWith("expression:") && it.contains(gstring) })
        }
        assertTrue("no syntax finding on a Groovy GString", findings().none { it["check"] == "invalidExpr" })
    }

    @Test
    fun theModelsOwnExpressionsAreStillHarvested() {
        val ids = ids()
        assertTrue("expression:\${realBean.run()}" in ids)
        assertTrue("expression:\${approver}" in ids)
    }

    @Test
    fun scriptHelpersAreNotBeansTheModelCalls() {
        val ids = ids()
        assertFalse("`flw` is the scripting API, not a bean the action calls", "external:flw" in ids)
        assertFalse("external:flwTimeUtils" in ids)
        assertFalse("a Groovy local is not a process variable", "variable:name" in ids)
    }
}
