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
