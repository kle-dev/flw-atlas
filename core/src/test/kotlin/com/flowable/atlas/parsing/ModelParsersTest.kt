package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the JSON model parsers on inline documents — a port of the relevant cases in
 * `tests/test_parsers.py`. Verifies each parser's output and the references it records in [Ctx],
 * independently of the graph builder (which is ported later).
 */
class ModelParsersTest {

    private fun refs(ctx: Ctx): Set<Triple<Any?, Any?, Any?>> =
        ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()

    @Test
    fun modelTypeFor() {
        assertEquals("bpmn", ModelKinds.modelTypeFor("x.bpmn"))
        assertEquals("bpmn", ModelKinds.modelTypeFor("x.bpmn20.xml"))
        assertEquals("cmmn", ModelKinds.modelTypeFor("x.cmmn.xml"))
        assertEquals("form", ModelKinds.modelTypeFor("x.form"))
        assertNull(ModelKinds.modelTypeFor("x.txt"))
        assertNull(ModelKinds.modelTypeFor("noext"))
        assertEquals("masterData", ModelKinds.modelTypeFor("x.masterData"))
        assertEquals("knowledgeBase", ModelKinds.modelTypeFor("x.knowledgeBase"))
        assertEquals("knowledgeBase", ModelKinds.modelTypeFor("x.KNOWLEDGEBASE"))
        assertEquals("dashboardComponent", ModelKinds.modelTypeFor("x.dashboardComponent"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun parseFormFieldsAndRest() {
        val doc = """{"metadata": {"key": "f1", "name": "F"},
                     "rows": [[{"id": "a", "type": "select", "label": "A",
                                "extraSettings": {"queryUrl": "/api/x", "formRef": "sub1"}}]]}""".toByteArray()
        val ctx = Ctx()
        val info = ModelParsers.parseForm(doc, ctx, "f.form")
        assertEquals("f1", info["key"])
        assertEquals(listOf("a"), (info["fields"] as List<Map<String, Any?>>).map { it["id"] })
        assertEquals(listOf("sub1"), info["subforms"])
        assertEquals("/api/x", ctx.restCalls[0]["url"])
        assertTrue(Triple("subform", "form", "sub1") in refs(ctx))
    }

    @Test
    fun parseFormExtractsOpUseFromDataSourceUrls() {
        // DO operation invoked via a `{{endpoints.dataobject}}` REST data-source URL (queryUrl/lookupUrl):
        // the target + operation keys are literal query params even though the host is a placeholder.
        val doc = """{"metadata": {"key": "f1", "name": "F"},
                     "rows": [[{"id": "a", "type": "select", "label": "A", "extraSettings": {
                        "queryUrl": "{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=do-order&dataObjectOperationKey=findByStatus&status={{status}}",
                        "lookupUrl": "{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=do-order&dataObjectOperationKey=findByStatus"}}]]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseForm(doc, ctx, "f.form")
        val opUses = ctx.opUse.map { Triple(it["targetKind"], it["targetKey"], it["op"]) }.toSet()
        assertTrue("DO op-use must be extracted from the data-source URL",
            Triple<Any?, Any?, Any?>("dataObject", "do-order", "findByStatus") in opUses)
        assertTrue("form→dataObject ref must be recorded from the URL",
            Triple("field-dataObject", "dataObject", "do-order") in refs(ctx))
    }

    @Test
    fun parseFormUrlOpUseIgnoresDynamicKeys() {
        // A dynamic (`{{…}}`) operation key can't be tied to one operation — it must not be recorded.
        val doc = """{"metadata": {"key": "f1", "name": "F"},
                     "rows": [[{"id": "a", "type": "select", "label": "A", "extraSettings": {
                        "queryUrl": "{{endpoints.dataobject}}/x?dataObjectDefinitionKey=do-order&dataObjectOperationKey={{op}}"}}]]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseForm(doc, ctx, "f.form")
        assertTrue("dynamic op key must not produce an op-use", ctx.opUse.isEmpty())
    }

    @Test
    fun parseServiceOperationWithoutUrlRecordsNoRestCall() {
        // A service operation with a method but no URL must NOT produce a restCall entry — a null
        // `url` would violate the restCalls invariant and later crash GraphBuilder's non-null cast.
        val doc = """{"key": "s1", "name": "S", "type": "service",
                     "operations": [{"key": "op1", "name": "Op", "config": {"method": "POST"}}]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseService(doc, ctx, "s.service")
        assertTrue("method-only operation must not record a rest call", ctx.restCalls.isEmpty())
    }

    @Test
    fun parseServiceOperationWithUrlRecordsRestCall() {
        val doc = """{"key": "s1", "name": "S", "type": "service", "config": {"baseUrl": "https://api.example.com"},
                     "operations": [{"key": "op1", "name": "Op", "config": {"method": "GET", "url": "/things"}}]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseService(doc, ctx, "s.service")
        assertEquals("https://api.example.com/things", ctx.restCalls[0]["url"])
        assertEquals("GET", ctx.restCalls[0]["method"])
    }

    @Test
    fun parseAppChildModels() {
        val doc = """{"key": "app1", "name": "A", "groupsAccess": "g1",
                     "extension": {"design": {"childModels": [{"key": "p1", "type": "bpmn"}]}}}""".toByteArray()
        val ctx = Ctx()
        val info = ModelParsers.parseApp(doc, ctx, "a.app")
        assertEquals("app1", info["key"])
        assertTrue(Triple("contains", "model:bpmn", "p1") in refs(ctx))
        assertEquals(listOf("g1"), ctx.access[0]["groups"])
    }

    @Test
    fun modelDescriptionSurvivesEveryParser() {
        // Design keeps a Description on the model, and it is usually the only sentence in the file that
        // says why the model exists. Each parser builds its own result map, so each one dropped it and
        // the explorer could search everything about a model except that.
        val d = "Why this model exists"
        fun descOf(parsed: Map<String, Any?>) = parsed["description"]
        assertEquals(d, descOf(ModelParsers.parseService(
            """{"key": "s1", "name": "S", "description": "$d"}""".toByteArray(), Ctx(), "s.service")))
        assertEquals(d, descOf(ModelParsers.parseAgent(
            """{"key": "a1", "name": "A", "description": "$d"}""".toByteArray(), Ctx(), "a.agent")))
        assertEquals(d, descOf(ModelParsers.parseAction(
            """{"key": "ac1", "name": "Ac", "description": "$d"}""".toByteArray(), Ctx(), "ac.action")))
        assertEquals(d, descOf(ModelParsers.parseChannel(
            """{"key": "c1", "name": "C", "description": "$d"}""".toByteArray(), Ctx(), "c.channel")))
        assertEquals(d, descOf(ModelParsers.parseEvent(
            """{"key": "e1", "name": "E", "description": "$d"}""".toByteArray(), Ctx(), "e.event")))
        assertEquals(d, descOf(ModelParsers.parsePolicy(
            """{"key": "p1", "name": "P", "description": "$d"}""".toByteArray(), Ctx(), "p.policy")))
        assertEquals(d, descOf(ModelParsers.parseDictionary(
            """{"key": "dd1", "name": "DD", "description": "$d"}""".toByteArray(), Ctx(), "dd.dictionary")))
        assertEquals(d, descOf(ModelParsers.parseDataObject(
            """{"key": "do1", "name": "DO", "description": "$d"}""".toByteArray(), Ctx(), "do.data")))
        assertEquals(d, descOf(ModelParsers.parseGeneric(
            """{"key": "q1", "name": "Q", "description": "$d"}""".toByteArray(), Ctx(), "q.query", "query")))
        // A form/page keeps its key and name in a `metadata` header, so look there as well as at the
        // top level — a deployment `.form` and a Design export do not agree on which one it is.
        assertEquals(d, descOf(ModelParsers.parseForm(
            """{"metadata": {"key": "f1", "name": "F", "description": "$d"}}""".toByteArray(), Ctx(), "f.form")))
        assertEquals(d, descOf(ModelParsers.parseForm(
            """{"metadata": {"key": "f1", "name": "F"}, "description": "$d"}""".toByteArray(), Ctx(), "f.form")))
        // A DMN decision carries it as a child element. `<description>` inside a `<rule>` is the row
        // annotation and must not be mistaken for the table's own description.
        val dmn = """<definitions>
                       <decision id="d1" name="D">
                         <description>$d</description>
                         <decisionTable><rule><description>row note</description></rule></decisionTable>
                       </decision>
                     </definitions>"""
        val dec = ModelParsers.parseDmn(dmn.toByteArray(), Ctx(), "d.dmn")[0]
        assertEquals(d, descOf(dec))
    }

    @Test
    fun aModelWithoutADescriptionGetsNoDescriptionKey() {
        // Not a `null` value: a key on every model of every project is a line of noise in the goldens
        // and a byte on every node of the explorer payload.
        val info = ModelParsers.parseService("""{"key": "s1", "name": "S"}""".toByteArray(), Ctx(), "s.service")
        assertFalse("empty description must not be written at all", info.containsKey("description"))
    }

}
