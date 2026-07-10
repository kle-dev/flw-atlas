package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
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
        val doc = """{"metadata": {"key": "KYC-F045", "name": "F"},
                     "rows": [[{"id": "a", "type": "select", "label": "A", "extraSettings": {
                        "queryUrl": "{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=KYC-DO2&dataObjectOperationKey=findActiveByIssueType&issueType={{issueType}}",
                        "lookupUrl": "{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=KYC-DO2&dataObjectOperationKey=findActiveByIssueType"}}]]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseForm(doc, ctx, "f.form")
        val opUses = ctx.opUse.map { Triple(it["targetKind"], it["targetKey"], it["op"]) }.toSet()
        assertTrue("DO op-use must be extracted from the data-source URL",
            Triple<Any?, Any?, Any?>("dataObject", "KYC-DO2", "findActiveByIssueType") in opUses)
        assertTrue("form→dataObject ref must be recorded from the URL",
            Triple("field-dataObject", "dataObject", "KYC-DO2") in refs(ctx))
    }

    @Test
    fun parseFormUrlOpUseIgnoresDynamicKeys() {
        // A dynamic (`{{…}}`) operation key can't be tied to one operation — it must not be recorded.
        val doc = """{"metadata": {"key": "f1", "name": "F"},
                     "rows": [[{"id": "a", "type": "select", "label": "A", "extraSettings": {
                        "queryUrl": "{{endpoints.dataobject}}/x?dataObjectDefinitionKey=KYC-DO2&dataObjectOperationKey={{op}}"}}]]}""".toByteArray()
        val ctx = Ctx()
        ModelParsers.parseForm(doc, ctx, "f.form")
        assertTrue("dynamic op key must not produce an op-use", ctx.opUse.isEmpty())
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
}
