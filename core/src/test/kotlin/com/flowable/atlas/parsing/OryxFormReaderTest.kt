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

    /**
     * The validation rules are spelled the palette's way in the legacy editor (`min-value`,
     * `validation-regexp`, a `{totalCount, items}` list of custom validations) and land where the modern
     * reader — and the form runtime — has them: on the component, and under `extraSettings`.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun validationRulesLandWhereTheModernFormHasThem() {
        val body = mapOf(
            "properties" to mapOf("do-not-create-outcome-variable" to true),
            "childShapes" to listOf(mapOf(
                "resourceId" to "r1", "stencil" to mapOf("id" to "Text"),
                "properties" to mapOf(
                    "id" to "claimEmail", "stencilid" to "cloud-text", "label" to "E-mail",
                    "validation-regexp" to ".+@example\\.com", "min-length" to "7", "max-length-run" to "{{maxLen}}",
                    "custom-validations" to mapOf("totalCount" to 1, "items" to listOf(
                        mapOf("expression" to "{{claimEmail != 'x'}}", "errorMsg" to "Not x"))),
                ),
            )),
        )
        val modern = OryxFormReader.toModern(body)
        assertEquals(true, modern["doNotCreateOutcomeVariable"])
        val c = (modern["components"] as List<Map<String, Any?>>).single()
        assertEquals(".+@example\\.com", c["validationRegExp"])
        assertEquals(listOf(mapOf("expression" to "{{claimEmail != 'x'}}", "errorMsg" to "Not x")), c["customValidations"])
        val es = c["extraSettings"] as Map<String, Any?>
        assertEquals("7", es["minLength"])
        assertEquals("{{maxLen}}", es["maxLength"])
        assertEquals(mapOf("pattern" to ".+@example\\.com", "minLength" to "7", "maxLength" to "{{maxLen}}",
            "custom" to listOf(mapOf("expression" to "{{claimEmail != 'x'}}", "errorMsg" to "Not x"))),
            ModelParsers.validationOf(c))
    }

    /** A subform the way an older export names it: by its Design model id, with the key in `form-id`. */
    private fun subformById(formId: String?): Pair<Map<String, Any?>, Ctx> {
        val props = linkedMapOf<String, Any?>(
            "id" to "approvers", "stencilid" to "cloud-sub-form", "value" to "{{approvers}}",
            "form-ref" to "FORM_MODEL-00000000-0000-0000-0000-000000000002",
        )
        if (formId != null) props["form-id"] = formId
        val body = mapOf("childShapes" to listOf(mapOf("stencil" to mapOf("id" to "Container"), "properties" to props)))
        val doc = LinkedHashMap<String, Any?>(OryxFormReader.toModern(body))
        doc["metadata"] = mapOf("key" to "DEMO-F001", "name" to "Request", "modelType" to "form")
        val ctx = Ctx()
        return ModelParsers.parseForm(MiniJson.stringify(doc).toByteArray(), ctx, "form-models/DEMO-F001.json") to ctx
    }

    @Test
    fun aSubformNamedByItsModelIdIsReferencedByItsKey() {
        val (form, ctx) = subformById("DEMO-F002")
        assertEquals(listOf("DEMO-F002"), form["subforms"])
        assertTrue(ctx.refs.toString(), Triple("subform", "form", "DEMO-F002") in ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) })
        // without a key beside it the id is all there is, and it stays
        val (bare, _) = subformById(null)
        assertEquals(listOf("FORM_MODEL-00000000-0000-0000-0000-000000000002"), bare["subforms"])
    }
}
