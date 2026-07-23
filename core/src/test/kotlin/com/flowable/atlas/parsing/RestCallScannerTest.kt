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
}
