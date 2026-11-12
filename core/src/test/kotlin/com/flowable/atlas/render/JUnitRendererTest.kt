package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.Waivers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The JUnit report a CI test view reads. What matters is what a dashboard does with it: it must parse,
 * show one suite per check, name each finding the same way on the next run, and be red exactly where the
 * gate is.
 */
class JUnitRendererTest {

    private val fixtureDir: File by lazy { File(javaClass.classLoader.getResource("miniproject")!!.toURI()) }
    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir) }

    @Suppress("UNCHECKED_CAST")
    private fun findings(r: Map<String, Any?>) = r["findings"] as List<Map<String, Any?>>

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test
    fun oneSuitePerCheckAndOneCasePerFinding() {
        val root = parse(JUnitRenderer.render(result, listOf("defects")))
        val suites = root.children("testsuite")
        assertEquals("the catalog's order", CheckCatalog.ORDER.map { "atlas.$it" }, suites.map { it.getAttribute("name") })
        for (s in suites) {
            val check = s.getAttribute("name").removePrefix("atlas.")
            val n = findings(result).count { it["check"] == check }
            val cases = s.children("testcase")
            assertEquals(check, maxOf(n, 1), cases.size)
            assertEquals(check, cases.size.toString(), s.getAttribute("tests"))
            if (n == 0) assertEquals("a check that ran clean still shows", "no findings", cases.single().getAttribute("name"))
            val names = cases.map { it.getAttribute("classname") + "/" + it.getAttribute("name") }
            assertEquals("unique names in $check: $names", names.size, names.toSet().size)
        }
        val defects = findings(result).count { CheckCatalog.gates(listOf("defects"), it) }
        assertEquals("red exactly where the gate is", defects.toString(), root.getAttribute("failures"))
        assertEquals(defects, root.getElementsByTagName("failure").length)
    }

    @Test
    fun theGateDecidesWhatIsRed() {
        val errors = findings(result).count { it["severity"] == "error" }
        val root = parse(JUnitRenderer.render(result, listOf("error")))
        assertEquals(errors.toString(), root.getAttribute("failures"))
        val skipped = root.getElementsByTagName("skipped")
        assertTrue("an ungated finding is shown and says why", (0 until skipped.length).any {
            (skipped.item(it) as Element).getAttribute("message").startsWith("not gated by error: ")
        })
        // A baseline's findings are not red either.
        val none = parse(JUnitRenderer.render(result, listOf("any"), baselined = { true }))
        assertEquals("0", none.getAttribute("failures"))
    }

    @Test
    fun aFailureSaysWhatWhyAndWhatToDo() {
        val root = parse(JUnitRenderer.render(result, listOf("missingRefs")))
        val failure = root.getElementsByTagName("failure").item(0) as Element
        val body = failure.textContent
        assertEquals("error", failure.getAttribute("type"))
        for (part in listOf("Missing model refs", "why: ", "fix: ", "docs: ${CheckCatalog.DOCS_BASE}checks/#")) {
            assertTrue("$part in $body", body.contains(part))
        }
    }

    @Test
    fun aWaivedFindingIsSkippedWithItsReason() {
        val waived = Atlas.extract(fixtureDir, waivers = Waivers.Set(listOf(
            Waivers.Waiver(check = "parseIssues", node = "broken.form", reason = "DEMO pilot", by = "DEMO-team"))))
        val root = parse(JUnitRenderer.render(waived, listOf("any")))
        val parse = root.children("testsuite").single { it.getAttribute("name") == "atlas.parseIssues" }
        val skipped = parse.getElementsByTagName("skipped").item(0) as Element
        assertEquals("waived: DEMO pilot — by DEMO-team", skipped.getAttribute("message"))
    }

    @Test
    fun theSameProjectGivesTheSameReport() {
        assertEquals(JUnitRenderer.render(result, listOf("defects")), JUnitRenderer.render(Atlas.extract(fixtureDir), listOf("defects")))
    }

    /** Messages quote model text: markup must be escaped and what XML 1.0 cannot carry dropped, or the report is unreadable. */
    @Test
    fun modelTextCannotBreakTheXml() {
        val nasty = "a<b & \"c\" 'd' \u0001 \uD800 e\nf"
        val r = mapOf<String, Any?>("findings" to listOf(mapOf<String, Any?>("check" to "missingRefs", "severity" to "error",
            "node" to "external:DEMO-<x>", "label" to nasty, "message" to nasty)))
        val root = parse(JUnitRenderer.render(r, listOf("error")))
        val failure = root.getElementsByTagName("failure").item(0) as Element
        assertEquals("a<b & \"c\" 'd'   e\nf", failure.getAttribute("message"))
        assertEquals("external:DEMO-<x>", (failure.parentNode as Element).getAttribute("name"))
    }

    @Test
    fun aRepeatedIdentityGetsAnOrdinal() {
        val f = mapOf<String, Any?>("check" to "unsafeQueries", "severity" to "warning", "node" to "query:DEMO-q",
            "subject" to "templateContent", "message" to "x")
        val root = parse(JUnitRenderer.render(mapOf("findings" to listOf(f, f)), listOf("any")))
        val suite = root.children("testsuite").single { it.getAttribute("name") == "atlas.unsafeQueries" }
        assertEquals(listOf("query:DEMO-q › templateContent", "query:DEMO-q › templateContent (2)"),
            suite.children("testcase").map { it.getAttribute("name") })
    }

    @Test
    fun staleWaiversAreASuiteOfTheirOwn() {
        val r = mapOf<String, Any?>("findings" to emptyList<Any?>(),
            "waivers" to mapOf("rules" to emptyList<Any?>(), "stale" to listOf("matched nothing: unusedForms on form:DEMO-x")))
        fun suite(gate: Boolean) = parse(JUnitRenderer.render(r, listOf("defects"), staleGate = gate))
            .children("testsuite").single { it.getAttribute("name") == "atlas.waivers" }
        assertEquals("1", suite(true).getAttribute("failures"))
        assertEquals("0", suite(false).getAttribute("failures"))
        assertEquals("1", suite(false).getAttribute("skipped"))
        assertTrue("no waiver file, no suite", parse(JUnitRenderer.render(mapOf("findings" to emptyList<Any?>()), listOf("defects")))
            .children("testsuite").none { it.getAttribute("name") == "atlas.waivers" })
    }
}
