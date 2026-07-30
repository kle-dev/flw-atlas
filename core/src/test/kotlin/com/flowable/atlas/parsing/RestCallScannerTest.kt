package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestCallScannerTest {

    private val D = "$"

    /** The substring [text] actually points at for a scanned call — proves the offset range is right. */
    private fun RestCallScanner.RestCall.slice(text: String) = text.substring(range.first, range.last + 1)

    @Test
    fun nestedStringWithCdata() {
        val text = """
            <serviceTask id="t" flowable:type="http">
              <extensionElements>
                <flowable:field name="requestUrl">
                  <flowable:string><![CDATA[$D{baseUrl}/api/things]]></flowable:string>
                </flowable:field>
              </extensionElements>
            </serviceTask>
        """.trimIndent()
        val calls = RestCallScanner.scan(text)
        assertEquals(listOf("$D{baseUrl}/api/things"), calls.map { it.url })
        assertEquals("$D{baseUrl}/api/things", calls.single().slice(text))
    }

    @Test
    fun nestedExpressionWithoutCdata() {
        val text = """<flowable:field name="requestUrl"><flowable:expression>/api/orders</flowable:expression></flowable:field>"""
        val calls = RestCallScanner.scan(text)
        assertEquals(listOf("/api/orders"), calls.map { it.url })
        assertEquals("/api/orders", calls.single().slice(text))
    }

    @Test
    fun attributeForm() {
        val text = """<flowable:field name="requestUrl" stringValue="/api/customers/42" />"""
        assertEquals(listOf("/api/customers/42"), RestCallScanner.scan(text).map { it.url })
    }

    @Test
    fun jsonDirectAndFieldObject() {
        val direct = """{ "requestUrl": "https://host/api/things?x=1" }"""
        assertEquals(listOf("https://host/api/things?x=1"), RestCallScanner.scan(direct).map { it.url })

        val fieldObj = """{ "name": "requestUrl", "stringValue": "/api/things" }"""
        assertEquals(listOf("/api/things"), RestCallScanner.scan(fieldObj).map { it.url })
    }

    @Test
    fun multipleCallsDedupedAndOrdered() {
        val text = """
            <flowable:field name="requestUrl"><flowable:string>/api/a</flowable:string></flowable:field>
            <flowable:field name="requestUrl"><flowable:string>/api/b</flowable:string></flowable:field>
        """.trimIndent()
        assertEquals(listOf("/api/a", "/api/b"), RestCallScanner.scan(text).map { it.url })
    }

    @Test
    fun scannedUrlMatchesControllerEndpointViaMatchRest() {
        // The real pipeline: a model's requestUrl (with an EL base) resolves to a controller path.
        val bpmn = """
            <serviceTask id="t" flowable:type="http">
              <extensionElements>
                <flowable:field name="requestUrl">
                  <flowable:string><![CDATA[$D{baseUrl}/api/things]]></flowable:string>
                </flowable:field>
              </extensionElements>
            </serviceTask>
        """.trimIndent()
        val url = RestCallScanner.scan(bpmn).single().url
        val endpoint = listOf(mapOf<String, Any?>("http" to "GET", "path" to "/api/things"))
        assertTrue(JavaParser.matchRest(url, endpoint).isNotEmpty())
        assertTrue(JavaParser.matchRest(url, listOf(mapOf("path" to "/api/other"))).isEmpty())
    }

    @Test
    fun ignoresOtherFieldsAndPlainText() {
        val text = """
            <flowable:field name="requestMethod"><flowable:string>GET</flowable:string></flowable:field>
            <documentation>requestUrl is documented here</documentation>
        """.trimIndent()
        assertTrue(RestCallScanner.scan(text).isEmpty())
    }

    @Test
    fun capturesTheNearbyRequestMethod() {
        val text = """
            <serviceTask id="t" flowable:type="http">
              <extensionElements>
                <flowable:field name="requestMethod"><flowable:string>get</flowable:string></flowable:field>
                <flowable:field name="requestUrl"><flowable:string>/api/customers/42</flowable:string></flowable:field>
              </extensionElements>
            </serviceTask>
        """.trimIndent()
        val call = RestCallScanner.scan(text).single()
        assertEquals("/api/customers/42", call.url)
        assertEquals("GET", call.method)
        assertEquals(setOf(RestCallScanner.RestRef("/api/customers/42", "GET")), RestCallScanner.refs(text))
    }

    @Test
    fun jsonFieldObjectValueBeforeName() {
        // key order must not matter — {"stringValue":…,"name":"requestUrl"} is as valid as name-first
        val text = """{ "stringValue": "/api/things", "name": "requestUrl" }"""
        assertEquals(listOf("/api/things"), RestCallScanner.scan(text).map { it.url })
    }

    @Test
    fun urlWithoutAMethodHasNullMethod() {
        val text = """<flowable:field name="requestUrl" stringValue="/api/orders" />"""
        assertEquals(null, RestCallScanner.scan(text).single().method)
    }

    @Test
    fun xmlAttributeFormWithValueBeforeName() {
        // the JSON shapes always accepted either key order; the XML attribute form did not
        val text = """<flowable:field stringValue="/api/orders" name="requestUrl" />"""
        assertEquals(listOf("/api/orders"), RestCallScanner.scan(text).map { it.url })
    }

    @Test
    fun formRestButtonUrlIsFound() {
        // The shape that made this whole class insufficient: a page/form REST button keeps its endpoint
        // on `extraSettings.url`, so scanning only `requestUrl` left the IDE blind to it.
        val page = """
            {"id":"rest-button2","type":"restButton","extraSettings":{
               "text":"Can edit?","method":"get",
               "url":"{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}"}}
        """.trimIndent()
        val call = RestCallScanner.scan(page).single()
        assertEquals("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}", call.url)
        assertEquals("url", call.field)
        assertEquals("GET", call.method)
        assertEquals("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}", call.slice(page))
    }

    @Test
    fun buttonUrlResolvesToAPathVariableEndpoint() {
        // End to end, the case a user hit: a `{{modelVar}}` in the URL against Spring's `{pathVar}`.
        val page = """{"extraSettings":{"url":"{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}"}}"""
        val url = RestCallScanner.scan(page).single().url
        assertTrue(JavaParser.matchRest(url, listOf(mapOf("path" to "/myEndpoint/canEdit/{caseId}"))).isNotEmpty())
        assertTrue(JavaParser.matchRest(url, listOf(mapOf("path" to "/myEndpoint/canDelete/{caseId}"))).isEmpty())
    }

    @Test
    fun dataSourceQueryAndLookupUrlsAreFound() {
        val page = """{"extraSettings":{"queryUrl":"/api/customers","lookupUrl":"/api/customers/{{id}}"}}"""
        assertEquals(
            listOf("/api/customers" to "queryUrl", "/api/customers/{{id}}" to "lookupUrl"),
            RestCallScanner.scan(page).map { it.url to it.field },
        )
    }

    @Test
    fun serviceOperationUrlIsFound() {
        val service = """{"operations":[{"key":"findById","config":{"url":"leads(${D}{leadid})","method":"GET"}}]}"""
        val call = RestCallScanner.scan(service).single()
        assertEquals("leads($D{leadid})", call.url)
        assertEquals("GET", call.method)
    }

    @Test
    fun aCapitalisedUrlKeyIsNotMistakenForAnEndpoint() {
        // `url` is a common key, but the regexes anchor on the opening quote and are case-sensitive, so
        // `iconUrl`/`navigationUrl`/`requestUrl` never leak into the plain-`url` pass.
        val page = """{"extraSettings":{"iconUrl":"/img/x.svg","navigationUrl":"#/tasks","linkUrl":"/x"}}"""
        assertTrue(RestCallScanner.scan(page).isEmpty())
    }

    @Test
    fun aNeighbouringButtonsVerbIsNotAttachedToThisOne() {
        // Two buttons in one form: the proximity window for `extraSettings` is tight, so the second
        // button's URL does not inherit the first button's method. An unknown verb matches any endpoint
        // verb, so null is the safe answer — a wrong verb would reject a real match.
        val page = """
            {"rows":[
              [{"id":"a","type":"restButton","extraSettings":{"method":"post","url":"/api/a","text":"A",
                 "sendPayloadMapping":[{"name":"x","expression":"{{x}}"},{"name":"y","expression":"{{y}}"}],
                 "responsePayloadMapping":[{"name":"r","expression":"{{${'$'}response.r}}"}]}}],
              [{"id":"b","type":"restButton","extraSettings":{"url":"/api/b","text":"B"}}]
            ]}
        """.trimIndent()
        val byUrl = RestCallScanner.scan(page).associate { it.url to it.method }
        assertEquals("POST", byUrl["/api/a"])
        assertEquals(null, byUrl["/api/b"])
    }
}
