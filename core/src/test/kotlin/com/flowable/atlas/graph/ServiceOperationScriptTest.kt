package com.flowable.atlas.graph

import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptProblemKind
import com.flowable.atlas.script.ScriptValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A script-type service operation keeps its body in `config.script`, which the service registry evaluates
 * only when something calls the operation. It was neither on the record nor validated, so a body that could
 * never run was found by the first caller in production.
 */
class ServiceOperationScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun service(script: String, inputs: String = """[{"name":"q","type":"string"}]""") =
        """{"key":"DEMO-script-service","name":"Script service","type":"script","operations":[
            {"key":"search","name":"Search","type":"search","inputParameters":$inputs,
             "config":{"scriptLanguage":"groovy","script":${com.flowable.atlas.model.MiniJson.stringify(script)}}}]}"""

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().map { it as Map<String, Any?> }

    private fun extract(script: String): Map<String, Any?> {
        val root = tmp.newFolder()
        File(root, "sc.service").writeText(service(script))
        return Atlas.extract(root)
    }

    @Test
    fun aBrokenBodyIsAScriptFindingOnItsOperation() {
        val result = extract("def x = input.get('q'\nreturn [x]")
        val f = maps(result["findings"]).filter { it["check"] == "scriptIssues" }
        assertEquals(f.toString(), 1, f.size)
        assertEquals("service:DEMO-script-service", f[0]["node"])
        assertEquals("search", f[0]["element"])
        assertEquals(1, ((result["stats"] as Map<*, *>)["scriptIssues"] as Number).toInt())
        val op = maps(maps(result["services"]).single()["operations"]).single()
        assertEquals("groovy", op["scriptLanguage"])
        assertTrue(op["script"].toString().startsWith("def x"))
    }

    @Test
    fun aValidBodyIsSilentAndNamesNoVariables() {
        val result = extract("def r = q.trim()\nflw.setOutput('r', r)")
        assertTrue(maps(result["findings"]).none { it["check"] == "scriptIssues" })
        @Suppress("UNCHECKED_CAST")
        val ids = maps((result["graph"] as Map<String, Any?>)["nodes"]).map { it["id"] }
        assertFalse("an operation's input is a parameter of the call, no variable", "variable:q" in ids)
        assertFalse("and so is its output", "variable:r" in ids)
    }

    @Test
    fun anOperationSeesNoExecutionButItsInputsByName() {
        val problems = ScriptValidator.validate("def x = execution.getVariable('x')", "groovy",
            context = ScriptContext.SERVICE_OPERATION)
        assertTrue(problems.toString(), problems.any { it.kind == ScriptProblemKind.WRONG_CONTEXT_ROOT })
        // an input that happens to be called `task` is that input
        val info = ModelParsers.parseService(service("def t = task.trim()\nflw.setOutput('t', t)", """[{"name":"task"}]""").toByteArray(),
            Ctx(), "sc.service")
        val op = maps(info["operations"]).single()
        assertEquals(null, op["scriptProblems"])
    }
}
