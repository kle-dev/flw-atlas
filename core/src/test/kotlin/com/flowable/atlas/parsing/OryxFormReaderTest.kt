package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The legacy editor's form body — `childShapes`, `stencil.id`, hyphenated `properties` — is rewritten
 * into the shape [ModelParsers.parseForm] reads, so a Design-workspace export yields the same fields,
 * subforms, references and REST calls a current export does.
 */
class OryxFormReaderTest {

    @Suppress("UNCHECKED_CAST")
    private fun fixture(): Map<String, Any?> {
        val url = javaClass.classLoader.getResource("miniproject/form-models/DEMO-LF001.json")
            ?: error("fixture missing")
        val wrapper = MiniJson.parse(File(url.toURI()).readText()) as Map<String, Any?>
        return wrapper["editorJson"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsed(): Pair<Map<String, Any?>, Ctx> {
        val body = fixture()
        assertTrue(OryxFormReader.isOryx(body))
        val doc = LinkedHashMap<String, Any?>(OryxFormReader.toModern(body))
        doc["metadata"] = mapOf("key" to "DEMO-LF001", "name" to "Legacy order lookup", "modelType" to "form")
        val ctx = Ctx()
        return ModelParsers.parseForm(MiniJson.stringify(doc).toByteArray(), ctx, "form-models/DEMO-LF001.json") to ctx
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun componentsBecomeFieldsWithTheTypesDesignWritesToday() {
        val (form, _) = parsed()
        val fields = form["fields"] as List<Map<String, Any?>>
        val byId = fields.associateBy { it["id"] }
        assertEquals(setOf("panel1", "legacyOrderId", "orderSub", "customerPick", "loadOrder", "runCheck", "orderRows"), byId.keys)
        assertEquals("text", byId["legacyOrderId"]!!["type"])
        assertEquals("subform", byId["orderSub"]!!["type"])
        assertEquals("restButton", byId["loadOrder"]!!["type"])
        assertEquals("workAction", byId["runCheck"]!!["type"])
        assertEquals("dataTable", byId["orderRows"]!!["type"])
        // the expression variant of a flag wins over its boolean twin
        assertEquals("{{legacyMode == 'strict'}}", byId["legacyOrderId"]!!["required"])
        // a button's caption may live only in a localised override
        assertEquals("Run the check", byId["runCheck"]!!["label"])
        assertEquals(listOf("orderForm"), form["subforms"])
        val outcomes = form["outcomes"] as List<Map<String, Any?>>
        assertEquals(listOf("save"), outcomes.map { it["value"] })
    }

    @Test
    fun referencesAndCallsAreTheSameAsFromACurrentExport() {
        val (form, ctx) = parsed()
        val refs = ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()
        assertTrue(refs.toString(), Triple("subform", "form", "orderForm") in refs)
        assertTrue(refs.toString(), Triple("outcome-form", "form", "orderForm") in refs)
        assertTrue(refs.toString(), Triple("field-dataObject", "dataObject", "customerDO") in refs)
        assertTrue(refs.toString(), Triple("triggers-action", "action", "notifyCustomerAction") in refs)
        assertTrue(ctx.opUse.toString(), ctx.opUse.any { it["targetKey"] == "customerDO" && it["op"] == "findAll" })
        @Suppress("UNCHECKED_CAST")
        val calls = (form["restCalls"] as List<Map<String, Any?>>).map { it["method"].toString() + " " + it["url"] }
        // the REST button's endpoint and the table's query; a row-click link is navigation, not a call
        assertEquals(listOf("GET /api/orders/{{legacyOrderId}}"), calls)
        assertTrue(ctx.restCalls.toString(), ctx.restCalls.any { it["kind"] == "form-query" && it["url"] == "/api/orders?customer={{customerPick}}" })
        assertTrue(ctx.restCalls.toString(), ctx.restCalls.none { (it["url"] as String).startsWith("#/") })
        // the action button's payload reaches the action with its callee
        @Suppress("UNCHECKED_CAST")
        val io = form["ioParameters"] as List<Map<String, Any?>>
        assertTrue(io.toString(), io.any { it["element"] == "runCheck" && it["refKey"] == "notifyCustomerAction" && it["target"] == "email" })
    }
}
