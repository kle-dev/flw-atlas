package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The gutter on a Spring REST handler, end to end: a handler whose full path (class `@RequestMapping`
 * base + method `@GetMapping`) is called by a model — even when the model URL carries a `{{...}}` base
 * variable — is marked; a POST-only handler on the same path is NOT marked by a GET task (verb-aware
 * matching). Mirrors [com.flowable.atlas.FlowableBotActionLineMarkerTest].
 */
class FlowableEndpointUsageLineMarkerTest : BasePlatformTestCase() {

    private fun httpTask(method: String, url: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
          <process id="p" name="P">
            <serviceTask id="t" flowable:type="http">
              <extensionElements>
                <flowable:field name="requestMethod"><flowable:string>$method</flowable:string></flowable:field>
                <flowable:field name="requestUrl"><flowable:string>$url</flowable:string></flowable:field>
              </extensionElements>
            </serviceTask>
          </process>
        </definitions>
    """.trimIndent()

    private fun markers() =
        myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Called by Flowable models") == true }

    fun testGutterOnHandlerCalledViaVariableBaseUrl() {
        myFixture.addFileToProject("models/get-customer.bpmn", httpTask("GET", "{{endpoints.baseUrl}}/api/customers/{id}"))
        project.service<FlowableModelIndexService>().index()

        myFixture.configureByText(
            "CustomerController.java",
            """
            @RestController @RequestMapping("/api/customers")
            class CustomerController {
                @GetMapping("/{id}") public String get() { return ""; }
                @PostMapping public String create() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()

        val gutters = markers()
        assertEquals("only the GET handler the model calls is marked", 1, gutters.size)
        assertTrue(
            "tooltip shows the resolved verb + full path",
            gutters.single().tooltipText!!.contains("GET /api/customers/{id}"),
        )
    }

    fun testGetTaskDoesNotMarkAPostOnlyHandler() {
        myFixture.addFileToProject("models/list-orders.bpmn", httpTask("GET", "/api/orders"))
        project.service<FlowableModelIndexService>().index()

        myFixture.configureByText(
            "OrderController.java",
            """
            @RestController @RequestMapping("/api/orders")
            class OrderController {
                @PostMapping public String create() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()

        assertEquals("a GET task must not mark a POST-only handler on the same path", 0, markers().size)
    }
}
