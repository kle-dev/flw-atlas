package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The gap that made a user's REST call invisible: the endpoint lives on a **page/form REST button**
 * (`extraSettings.url`), not on a BPMN HTTP task's `requestUrl`. Only `requestUrl` used to be scanned, so
 * the gutter and Find Usages stayed silent while the generated explorer happily drew the model →
 * endpoint edge.
 *
 * Also pins the path shape that made it look like a matcher bug: the model writes the path variable as a
 * `{{modelVar}}` binding where Spring declares `{pathVar}`, on top of a `{{endpoints.*}}` base.
 *
 * Companion to [FlowableEndpointUsageLineMarkerTest] (HTTP tasks). DEMO-* / generic keys — repo is public.
 */
class FlowableRestButtonEndpointUsageTest : BasePlatformTestCase() {

    private fun restButtonPage(url: String, method: String? = null) = """
        {"metadata":{"key":"DEMO-P001","name":"Review","modelType":"page"},
         "rows":[[{"id":"rest-button2","type":"restButton","extraSettings":{
           "text":"Can edit?",${if (method != null) "\"method\":\"$method\"," else ""}
           "url":"$url"}}]]}
    """.trimIndent()

    private fun controller() = """
        @RestController @RequestMapping("myEndpoint")
        class CaseController {
            @GetMapping("/canEdit/{caseId}") public String canEdit(@PathVariable String caseId) { return ""; }
            @PostMapping("/archive/{caseId}") public String archive(@PathVariable String caseId) { return ""; }
        }
    """.trimIndent()

    private fun markers() =
        myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Called by Flowable models") == true }

    fun testGutterOnHandlerCalledByARestButton() {
        myFixture.addFileToProject(
            "models/review.page",
            restButtonPage("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}"),
        )
        project.service<FlowableModelIndexService>().index()

        myFixture.configureByText("CaseController.java", controller())
        myFixture.doHighlighting()

        val gutters = markers()
        assertEquals("only the handler the REST button calls is marked", 1, gutters.size)
        assertTrue(
            "tooltip names the resolved endpoint",
            gutters.single().tooltipText!!.contains("/canEdit/{caseId}"),
        )
    }

    fun testFindUsagesLocatesTheButtonUrlInThePage() {
        val page = restButtonPage("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}")
        myFixture.addFileToProject("models/review.page", page)
        project.service<FlowableModelIndexService>().index()

        val endpoints = listOf(EndpointPsi.Endpoint("/myEndpoint/canEdit/{caseId}", "GET"))
        assertEquals(
            "the button URL resolves to the handler path despite {{…}} vs {…}",
            1,
            EndpointModelScan.usageRanges(page, endpoints).size,
        )
        val files = EndpointModelScan.affectedModelFiles(project, endpoints)
        assertTrue("the page model is reported: $files", files.any { it.name == "review.page" })
    }

    fun testAnUnrelatedEndpointIsNotReported() {
        val page = restButtonPage("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}")
        myFixture.addFileToProject("models/review.page", page)
        project.service<FlowableModelIndexService>().index()

        val other = listOf(EndpointPsi.Endpoint("/myEndpoint/canDelete/{caseId}", "GET"))
        assertTrue(EndpointModelScan.usageRanges(page, other).isEmpty())
        assertTrue(EndpointModelScan.affectedModelFiles(project, other).isEmpty())
    }

    fun testAButtonWithAnExplicitVerbDoesNotMarkTheOtherVerbsHandler() {
        // When the modeller did set `extraSettings.method`, it is honoured: a POST button leaves a
        // GET-only handler on the same path alone.
        myFixture.addFileToProject(
            "models/edit.page",
            restButtonPage("{{endpoints.baseUrl}}/myEndpoint/canEdit/{{myCaseVarX}}", method = "post"),
        )
        project.service<FlowableModelIndexService>().index()

        myFixture.configureByText(
            "CaseController.java",
            """
            @RestController @RequestMapping("myEndpoint")
            class CaseController {
                @GetMapping("/canEdit/{caseId}") public String canEdit(@PathVariable String caseId) { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()

        assertEquals("a POST button must not mark a GET-only handler", 0, markers().size)
    }

    fun testAButtonWithoutAVerbMatchesOnPathAlone() {
        // `extraSettings.method` is omitted whenever it is the palette default, which is most buttons.
        // A text scanner cannot tell which JSON object a nearby `method` belongs to, so an absent verb
        // stays unknown — and an unknown verb matches any handler verb on the path. That direction is
        // deliberate: guessing a verb here would silently drop the very call the user is looking for.
        myFixture.addFileToProject(
            "models/archive.page",
            restButtonPage("{{endpoints.baseUrl}}/myEndpoint/archive/{{myCaseVarX}}"),
        )
        project.service<FlowableModelIndexService>().index()

        myFixture.configureByText("CaseController.java", controller())
        myFixture.doHighlighting()

        assertEquals("the POST handler on that path is still found", 1, markers().size)
    }

    fun testAnIconUrlIsNotTreatedAsAnEndpoint() {
        // `url` is a common key; only the lowercase `url` is an endpoint (iconUrl/navigationUrl are not).
        val page = """
            {"metadata":{"key":"DEMO-P002","modelType":"page"},
             "rows":[[{"id":"b","type":"button","extraSettings":{
               "text":"Go","iconUrl":"/myEndpoint/canEdit/x","navigationUrl":"#/myEndpoint/canEdit/x"}}]]}
        """.trimIndent()
        myFixture.addFileToProject("models/icons.page", page)
        project.service<FlowableModelIndexService>().index()

        val endpoints = listOf(EndpointPsi.Endpoint("/myEndpoint/canEdit/{caseId}", "GET"))
        assertTrue(EndpointModelScan.usageRanges(page, endpoints).isEmpty())
    }
}
