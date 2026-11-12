package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.parsing.SecretScan
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `hardcodedSecrets` names the path of a literal secret and never its value — and the artifacts printed the
 * value anyway: a field injection's map, a service's base URL, a REST call's `external:<url>` node, a Java
 * constant's literal. Every shape below carries a sentinel starting `SNTL`; no artifact may contain one,
 * while the findings that point at them stay exactly what they were.
 */
class ArtifactSecretRedactionTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-secret-redaction").toFile()
            File(dir, "secret.service").writeText(
                """{"key":"DEMO-secret-service","name":"Secret service","type":"rest",
                    "config":{"baseUrl":"https://svcuser:SNTLBASEPW@api.example.com/v1"},
                    "operations":[{"key":"things","name":"Things","config":{"method":"GET","url":"things?access_token=SNTLQTOKEN&page=1"}}]}""")
            // a script operation's body is on the record too, and a URL's credentials in it are a finding
            File(dir, "script.service").writeText(
                """{"key":"DEMO-script-service","name":"Script service","type":"script",
                    "operations":[{"key":"fetch","name":"Fetch","type":"script","config":{"scriptLanguage":"groovy",
                      "script":"def url = 'https://svc:SNTLSCRIPTPW@api.example.com/x'\nflw.setOutput('url', url)"}}]}""")
            File(dir, "secret.bpmn20.xml").writeText(
                """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                     <process id="DEMO-secret-process" name="Secret process">
                       <startEvent id="s"/>
                       <serviceTask id="del" name="Call the bank" flowable:class="com.example.SecretHolder">
                         <extensionElements>
                           <flowable:field name="password" stringValue="SNTLFIELDPW"/>
                           <flowable:field name="apiToken"><flowable:string>SNTLFIELDTOKEN</flowable:string></flowable:field>
                           <flowable:field name="endpoint" stringValue="https://u:SNTLFIELDURLPW@bank.example.com/"/>
                         </extensionElements>
                       </serviceTask>
                       <serviceTask id="http" name="Fetch orders" flowable:type="http">
                         <extensionElements>
                           <flowable:field name="requestMethod" stringValue="GET"/>
                           <flowable:field name="requestUrl" stringValue="https://user:SNTLHTTPUSERPW@api.example.com/orders?api_key=SNTLHTTPKEY"/>
                           <flowable:field name="requestHeaders"><flowable:string>Accept: application/json
Authorization: Bearer SNTLHEADER</flowable:string></flowable:field>
                           <flowable:field name="requestBody"><flowable:string>{"user": "demo", "password": "SNTLBODYPW"}</flowable:string></flowable:field>
                         </extensionElements>
                       </serviceTask>
                       <sequenceFlow id="f1" sourceRef="s" targetRef="del"/>
                       <sequenceFlow id="f2" sourceRef="del" targetRef="http"/>
                     </process>
                   </definitions>""")
            File(dir, "secret.form").writeText(
                """{"metadata":{"key":"DEMO-secret-form","name":"Secret form"},"rows":[{"cols":[
                    {"id":"call","type":"restButton","label":"Call","value":"{{result}}","extraSettings":{"url":"https://formuser:SNTLFORMPW@api.example.com/submit"}},
                    {"id":"pick","type":"select","label":"Pick","extraSettings":{"dataSource":"Rest","queryUrl":"/api/options?token=SNTLSELECTTOK"}},
                    {"id":"go","type":"link","label":"Go","value":"https://linkuser:SNTLLINKPW@portal.example.com/"},
                    {"id":"pw","type":"password","label":"Password","value":"SNTLFORMDEFAULT"},
                    {"id":"pw2","type":"password","label":"Repeat","value":"{{pw}}"}
                  ]}]}""")
            File(dir, "src/main/java/com/example").mkdirs()
            File(dir, "src/main/java/com/example/SecretHolder.java").writeText(
                """package com.example;
                   public class SecretHolder {
                       static final String API_KEY = "SNTLJAVAKEY";
                       static final String GREETING = "hello there";
                       public String key() { return API_KEY; }
                   }""")
            File(dir, "src/main/java/com/example/DemoController.java").writeText(
                """package com.example;
                   import org.springframework.web.bind.annotation.*;
                   @RestController
                   public class DemoController {
                       @GetMapping("/demo/{id}")
                       public String get(@PathVariable String id,
                                         @RequestHeader(value = "Authorization", defaultValue = "Bearer SNTLJAVADEFAULT") String auth,
                                         @RequestParam(name = "page", defaultValue = "1") int page) { return id; }
                   }""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().map { it as Map<String, Any?> }

    private fun assertClean(name: String, text: String?) {
        val t = text ?: return
        val at = t.indexOf("SNTL")
        assertTrue("$name carries a secret: …${if (at < 0) "" else t.substring((at - 80).coerceAtLeast(0), (at + 40).coerceAtMost(t.length))}…", at < 0)
    }

    @Test
    fun noArtifactCarriesASecretValue() {
        assertClean("graph.json", GraphJsonRenderer.render(result))
        assertClean("explorer", ExplorerHtmlRenderer.render(result, dir, compressAbove = Int.MAX_VALUE))
        assertClean("summary.md", SummaryRenderer.render(result, dir))
        assertClean("overview.md", OverviewRenderer.render(result, dir))
        assertClean("CLAUDE.md", ClaudeRenderer.render(result, dir))
        assertClean("playwright.md", PlaywrightRenderer.render(result, dir))
        assertClean("playwright-map.md", PlaywrightRenderer.renderMap(result, dir))
        if (ErdHtmlRenderer.hasTables(result)) assertClean("erd", ErdHtmlRenderer.render(result, dir, explorerFile = "x.explorer.html"))
        @Suppress("UNCHECKED_CAST")
        for (n in maps((result["graph"] as Map<String, Any?>)["nodes"])) {
            assertClean("--slice ${n["id"]}", SliceRenderer.render(result, n["id"].toString()))
        }
    }

    @Test
    fun theFindingsStillNameEveryPath() {
        val subjects = maps(result["findings"]).filter { it["check"] == "hardcodedSecrets" }
            .map { "${it["node"]} ${it["subject"]}" }.sorted()
        assertEquals(listOf(
            "process:DEMO-secret-process apiToken",
            "process:DEMO-secret-process endpoint",
            "process:DEMO-secret-process password",
            "process:DEMO-secret-process requestUrl",
            "service:DEMO-script-service operations[0].config.script (credentials in URL)",
            "service:DEMO-secret-service config.baseUrl (credentials in URL)",
        ), subjects)
    }

    @Test
    fun whatIsNotASecretIsLeftAsItWas() {
        val calls = maps(result["restCalls"]).map { it["url"].toString() }
        assertTrue(calls.toString(), "https://***@api.example.com/v1/things?access_token=***&page=1" in calls)
        assertTrue(calls.toString(), "https://***@api.example.com/orders?api_key=***" in calls)
        assertTrue(calls.toString(), "/api/options?token=***" in calls)
        val process = maps(result["processes"]).single()
        val http = maps(process["serviceTasks"]).single { it["id"] == "http" }
        @Suppress("UNCHECKED_CAST")
        val fields = http["fields"] as Map<String, Any?>
        assertEquals("GET", fields["requestMethod"])
        assertEquals("Accept: application/json\nAuthorization: ***", fields["requestHeaders"])
        assertEquals("""{"user": "demo", "password": "***"}""", fields["requestBody"])
        // a password field bound to a variable shows the binding; only a literal default is a password
        val form = maps(result["forms"]).single()
        assertEquals("{{pw}}", maps(form["fields"]).single { it["id"] == "pw2" }["value"])
        val java = com.flowable.atlas.parsing.JavaParser.parseJava(File(dir, "src/main/java/com/example/SecretHolder.java").readText(), "SecretHolder.java")
        assertEquals("an ordinary constant is still read, the secret one is not", listOf("hello there"),
            (java["strings"] as Collection<*>).toList())
        assertEquals(listOf("hello there"), java["literalFacts"])
        // a handler's fallback credential is shown as one, an ordinary default as it is
        val params = maps(maps(result["endpoints"]).single { it["path"] == "/demo/{id}" }["params"])
        assertEquals(SecretScan.REDACTED, params.single { it["name"] == "Authorization" }["default"])
        assertEquals("1", params.single { it["name"] == "page" }["default"])
        // the script keeps everything but the credential, and stays a valid body
        val fetch = maps(maps(result["services"]).single { it["key"] == "DEMO-script-service" }["operations"]).single()
        assertEquals("def url = 'https://***@api.example.com/x'\nflw.setOutput('url', url)", fetch["script"])
        assertEquals(null, fetch["scriptProblems"])
    }
}
