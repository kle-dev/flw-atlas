package com.flowable.atlas.graph

import com.flowable.atlas.parsing.ModelParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Four places a Design model names a process or case (or a form) in a shape the parsers did not read, so a
 * rename broke the model without a finding: a query's Elasticsearch template filter, a REST data table's
 * `queryParams`, a Design action's `form: {id, key}`, and a variable extractor's `fullTextVariables`.
 */
class DefinitionFilterLinksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun refs(ctx: Ctx): Set<Triple<Any?, Any?, Any?>> =
        ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().map { it as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun edges(result: Map<String, Any?>) = maps((result["graph"] as Map<String, Any?>)["edges"])

    private fun findings(result: Map<String, Any?>, check: String) = maps(result["findings"]).filter { it["check"] == check }

    /** A missing model is a finding on the placeholder node the referencing model points at. */
    private fun missing(result: Map<String, Any?>, from: String, key: String): Boolean {
        val targets = edges(result).filter { it["s"] == from }.map { it["t"].toString() }.filter { it.endsWith(":$key") }
        return findings(result, "missingRefs").any { it["node"] in targets }
    }

    private val caseXml = { key: String ->
        """<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"><case id="$key"><casePlanModel id="plan"/></case></definitions>"""
    }

    // ---- queries ------------------------------------------------------------------------------------

    /** The shape every real query has: the definition is a `term` inside the Freemarker-wrapped template. */
    private val caseQuery = """{"key":"DEMO-open-cases","name":"Open cases","sourceIndex":"case-instances",
        "templateFilter":"<#ftl output_format=\"JSON\"> { \"bool\": { \"must\": [ { \"term\": { \"caseDefinitionKey\": \"DEMO-renamed-case\" } }<#if assignee??>, { \"term\": { \"rootScopeDefinitionKey\": \"${'$'}{assignee}\" } }</#if> ] } }"}"""

    @Test
    fun aQueryTemplateFilterNamesItsDefinition() {
        val ctx = Ctx()
        val info = ModelParsers.parseQuery(caseQuery.toByteArray(), ctx, "q.query")
        val queryRefs = ctx.refs.filter { it["rel"] == "queries-process" }.map { it["kind"] to it["value"] }
        assertEquals("one literal filter, the interpolated one names nothing", listOf("case" to "DEMO-renamed-case"), queryRefs)
        assertEquals(listOf("DEMO-renamed-case"), info["definitionKeys"])
    }

    @Test
    fun aTermsArrayAndTheBareDefinitionKeyAreRead() {
        val ctx = Ctx()
        ModelParsers.parseQuery("""{"key":"q","sourceIndex":"process-instances",
            "templateContent":"{\"terms\": {\"processDefinitionKey\": [\"DEMO-a\", \"DEMO-b\"]}}"}""".toByteArray(), ctx, "q.query")
        ModelParsers.parseQuery("""{"key":"q2","sourceIndex":"case-instances",
            "templateFilter":"{\"term\": {\"definitionKey\": \"DEMO-c\"}}"}""".toByteArray(), ctx, "q2.query")
        // a task index's scope key may be either kind — the resolver tries a process, then a case
        ModelParsers.parseQuery("""{"key":"q3","sourceIndex":"tasks",
            "templateFilter":"{\"term\": {\"scopeDefinitionKey\": \"DEMO-d\"}}"}""".toByteArray(), ctx, "q3.query")
        // `definitionKey` on an index that says neither is no definition Atlas can name
        ModelParsers.parseQuery("""{"key":"q4","sourceIndex":"work-instances",
            "templateFilter":"{\"term\": {\"definitionKey\": \"DEMO-e\"}}"}""".toByteArray(), ctx, "q4.query")
        // Elasticsearch's sort shorthand spells the field the same way, with an order where a key would be
        ModelParsers.parseQuery("""{"key":"q5","sourceIndex":"process-instances",
            "templateContent":"{\"sort\": [{\"processDefinitionKey\": \"desc\"}, {\"caseDefinitionKey\": \"ASC\"}]}"}""".toByteArray(), ctx, "q5.query")
        val r = refs(ctx)
        assertTrue(Triple("queries-process", "process", "DEMO-a") in r)
        assertTrue(Triple("queries-process", "process", "DEMO-b") in r)
        assertTrue(Triple("queries-process", "case", "DEMO-c") in r)
        assertTrue(Triple("queries-process", "process", "DEMO-d") in r)
        assertFalse(r.any { it.third == "DEMO-e" })
        assertFalse("a sort order is no definition", r.any { it.third.toString().lowercase() in setOf("asc", "desc") })
    }

    @Test
    fun aRenamedCaseIsAMissingModelAndAnExistingOneAnEdge() {
        val root = tmp.newFolder("queries")
        File(root, "q.query").writeText(caseQuery)
        File(root, "c.cmmn").writeText(caseXml("DEMO-case"))
        File(root, "q2.query").writeText(caseQuery.replace("DEMO-open-cases", "DEMO-q2").replace("DEMO-renamed-case", "DEMO-case"))
        val result = Atlas.extract(root)
        assertTrue("the renamed case is reported", missing(result, "query:DEMO-open-cases", "DEMO-renamed-case"))
        assertTrue("an existing case is linked",
            edges(result).any { it["s"] == "query:DEMO-q2" && it["t"] == "case:DEMO-case" && it["rel"] == "queries-process" })
    }

    // ---- REST data tables ---------------------------------------------------------------------------

    private val tablePage = """{"metadata":{"key":"DEMO-page","name":"Demo page","modelType":"page"},"rows":[{"cols":[
        {"id":"cases","type":"dataTable","label":"Cases","extraSettings":{"dataSource":"Rest",
          "queryUrl":"{{endpoints.platform}}/platform-api/search/case-instances",
          "lookupUrl":"{{endpoints.platform}}/platform-api/case-instances/lookup",
          "queryParams":[{"name":"caseDefinitionKey","value":"DEMO-missing-case"},{"name":"searchText","value":"{{${'$'}temp.search}}","defaultValue":""},{"name":"size","value":"","defaultValue":"50"}]}},
        {"id":"rows","type":"dataTable","label":"Rows","extraSettings":{"dataSource":"Rest",
          "queryUrl":"{{endpoints.dataobject}}/dataobject-runtime/data-object-instances",
          "queryParams":[{"name":"dataObjectDefinitionKey","value":"DEMO-missing-do"},{"name":"dataObjectOperationKey","value":"search"}]}}
      ]}]}"""

    @Test
    fun aRestTablesLiteralQueryParamsJoinItsUrl() {
        val ctx = Ctx()
        ModelParsers.parseForm(tablePage.toByteArray(), ctx, "dt.page")
        fun urlsOf(where: String) = ctx.restCalls.filter { it["where"] == where }.map { it["url"] }
        assertEquals("only the literal params, value else default; the lookup URL is left as it is",
            listOf("{{endpoints.platform}}/platform-api/search/case-instances?caseDefinitionKey=DEMO-missing-case&size=50",
                "{{endpoints.platform}}/platform-api/case-instances/lookup"),
            urlsOf("cases"))
        assertEquals(listOf("{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=DEMO-missing-do&dataObjectOperationKey=search"),
            urlsOf("rows"))
        val r = refs(ctx)
        assertTrue(Triple("lists-instances", "case", "DEMO-missing-case") in r)
        assertTrue(Triple("field-dataObject", "dataObject", "DEMO-missing-do") in r)
        assertTrue(ctx.opUse.any { it["targetKey"] == "DEMO-missing-do" && it["op"] == "search" })
    }

    @Test
    fun aScopeKeyNeedsItsKindSpelledOut() {
        val ctx = Ctx()
        ModelParsers.parseForm("""{"metadata":{"key":"DEMO-f","modelType":"form"},"rows":[{"cols":[
            {"id":"t","type":"dataTable","extraSettings":{"dataSource":"Rest","queryUrl":"/platform-api/search/query-tasks",
              "queryParams":[{"name":"rootScopeDefinitionKey","value":"DEMO-unknown-kind"}]}},
            {"id":"u","type":"dataTable","extraSettings":{"dataSource":"Rest","queryUrl":"/platform-api/search/query-tasks",
              "queryParams":[{"name":"scopeDefinitionKey","value":"DEMO-proc"},{"name":"scopeType","value":"bpmn"}]}}
          ]}]}""".toByteArray(), ctx, "f.form")
        val r = refs(ctx)
        assertFalse("`lists-instances` is strict: a guessed kind would be a false missing model", r.any { it.third == "DEMO-unknown-kind" })
        assertTrue(Triple("lists-instances", "process", "DEMO-proc") in r)
    }

    @Test
    fun theTablesDefinitionsAreMissingModelsAndTheDataObjectTableIsNoExternalCall() {
        val root = tmp.newFolder("tables")
        File(root, "dt.page").writeText(tablePage)
        val result = Atlas.extract(root)
        assertTrue(missing(result, "page:DEMO-page", "DEMO-missing-case"))
        assertTrue(missing(result, "page:DEMO-page", "DEMO-missing-do"))
        @Suppress("UNCHECKED_CAST")
        val ids = maps((result["graph"] as Map<String, Any?>)["nodes"]).map { it["id"].toString() }
        assertFalse(ids.toString(), ids.any { it.startsWith("external:") && it.contains("data-object-instances") })
    }

    // ---- Design action form object ------------------------------------------------------------------

    @Test
    fun aDesignActionsFormObjectIsItsForm() {
        val root = tmp.newFolder("action")
        File(root, "action-models").mkdirs()
        val body = """{"botKey":"bpmn-start-process-instance-bot","signalName":"DEMO-process","form":{"id":"FORM_MODEL-1","key":"DEMO-form"}}"""
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"")
        File(root, "action-models/demoAction.json").writeText("""{"id":"a1","key":"DEMO-action","name":"Demo action","editorJson":"$escaped"}""")
        File(root, "f.form").writeText("""{"metadata":{"key":"DEMO-form","name":"Demo form"},"rows":[{"cols":[{"id":"comment","type":"text","value":"{{comment}}"}]}]}""")
        val result = Atlas.extract(root)
        val action = maps(result["actions"]).single()
        assertEquals("DEMO-form", action["formKey"])
        assertTrue(edges(result).any { it["s"] == "action:DEMO-action" && it["t"] == "form:DEMO-form" && it["rel"] == "action-form" })
        assertTrue("the form is used", findings(result, "unusedForms").none { it["node"] == "form:DEMO-form" })
    }

    @Test
    fun anActionFileWithAFormObjectStoresTheKey() {
        val info = ModelParsers.parseAction("""{"key":"DEMO-a","formKey":{"id":"FORM_MODEL-1","key":"DEMO-form"}}""".toByteArray(), Ctx(), "a.action")
        assertEquals("DEMO-form", info["formKey"])
    }

    // ---- full-text variables ------------------------------------------------------------------------

    private fun extractorProject(scope: String): Map<String, Any?> {
        val root = tmp.newFolder("extractor-$scope")
        File(root, "p.bpmn20.xml").writeText(
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-process">
                 <scriptTask id="s" scriptFormat="groovy"><script>execution.setVariable('customerName', 'x')</script></scriptTask>
               </process></definitions>""")
        File(root, "x.extractor").writeText(
            """{"key":"DEMO-extractor","name":"Demo extractor","sourceIndex":"process-instances","variableExtractors":[],
                "fullTextVariables":[{"scopeDefinitionKey":"$scope","name":"customerName"}]}""")
        return Atlas.extract(root)
    }

    @Test
    fun aFullTextVariableIsReadAndLinksItsScope() {
        val result = extractorProject("DEMO-process")
        assertTrue("the search reads it", findings(result, "unusedVars").none { it["node"] == "variable:customerName" })
        @Suppress("UNCHECKED_CAST")
        val node = maps((result["graph"] as Map<String, Any?>)["nodes"]).single { it["id"] == "variable:customerName" }
        @Suppress("UNCHECKED_CAST")
        val reads = maps((node["data"] as Map<String, Any?>)["reads"])
        assertTrue(reads.toString(), reads.any { it["via"] == "fullTextIndex" && it["model"] == "variableExtractor:DEMO-extractor" })
        assertTrue(edges(result).any { it["s"] == "variableExtractor:DEMO-extractor" && it["t"] == "process:DEMO-process" && it["rel"] == "extracts-from" })
    }

    @Test
    fun aFullTextVariableOfARenamedCaseIsAMissingModel() {
        val result = extractorProject("DEMO-missing-case")
        assertTrue(maps(result["unresolvedRefs"]).any { it["rel"] == "extracts-from" && it["value"] == "DEMO-missing-case" })
        assertTrue(missing(result, "variableExtractor:DEMO-extractor", "DEMO-missing-case"))
        // a bare-string entry is not a shape the platform reads: copied onto the record, and nothing more
        val ctx = Ctx()
        val info = ModelParsers.parseVariableExtractor("""{"key":"x","fullTextVariables":["a"]}""".toByteArray(), ctx, "x.extractor")
        assertEquals(listOf("a"), info["fullTextVariables"])
        assertTrue(ctx.refs.toString(), ctx.refs.isEmpty())
        assertNull(ctx.varUse["a"])
        assertTrue(ctx.varSites.isEmpty())
    }
}
