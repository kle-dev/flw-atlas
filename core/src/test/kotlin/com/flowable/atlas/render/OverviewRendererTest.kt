package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Byte-exact golden gate for the full Markdown report, and the shapes the fixture does not have. */
class OverviewRendererTest {

    @Test
    fun overviewGolden() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        GoldenFiles.assertMatches("miniproject.overview.md", OverviewRenderer.render(result, fixtureDir) + "\n")
    }

    private fun render(result: Map<String, Any?>): String =
        OverviewRenderer.render(mapOf("stats" to mapOf("models" to 1)) + result, File("proj"))

    private fun call(url: String, source: String, where: String, vararg matches: String) = mapOf(
        "method" to "GET", "url" to url, "source" to source, "where" to where, "kind" to "form-query",
        "matches" to matches.toList(), "looseMatches" to emptyList<String>(),
    )

    /**
     * Every unmatched call was "no matching controller in project (external or unimplemented)" — on a real
     * project 128 of the 174 flagged were calls to Flowable's own API, one line per user picker. The warning
     * is for a call on the application's own base that nothing serves; the platform's API and other systems
     * are listed apart, once per URL.
     */
    @Test
    fun restCallsAreGroupedAndOnlyAnOwnCallNothingServesIsAWarning() {
        val out = OverviewRenderer.restCallsSection(listOf(
            call("{{endpoints.idm}}/users/{{\$id}}", "DEMO-a", "assignee"),
            call("{{endpoints.idm}}/users/{{\$id}}", "DEMO-b", "owner"),
            call("https://crm.example.com/api/x", "DEMO-a", "crm"),
            call("api/x", "DEMO-a", "mine"),
            call("api/y", "DEMO-a", "served", "GET /api/y -> Ctl#y (Ctl.java:3)"),
            call("api/y", "DEMO-b", "served"),
        ))
        assertEquals(out.joinToString("\n"), 1, out.count { "⚠" in it })
        val warned = out.indexOfFirst { "⚠" in it }
        assertTrue(out[warned - 1], out[warned - 1].startsWith("- `GET` `api/x`"))
        val idm = out.single { "endpoints.idm" in it }
        assertTrue(idm, idm.contains("2 callers: form-query `DEMO-a/assignee` · form-query `DEMO-b/owner`"))
        assertTrue(out.indexOfFirst { it.startsWith("#### Flowable REST API used") } < out.indexOf(idm))
        val crm = out.single { "crm.example.com" in it }
        assertTrue(out.indexOfFirst { it.startsWith("#### Other systems") } < out.indexOf(crm))
        // one line for a URL two models call, with both of them
        assertEquals(1, out.count { it.startsWith("- `GET` `api/y`") })
        assertTrue(out.single { it.startsWith("- `GET` `api/y`") }.contains("`DEMO-a/served` · form-query `DEMO-b/served`"))
    }

    /** An archive's directory was repeated in every heading of every model inside it. */
    @Test
    fun anArchiveIsNamedOnceAndItsModelsByItsFileName() {
        val text = render(mapOf(
            "processes" to listOf(mapOf("key" to "p", "name" to "P", "file" to "mod/src/main/resources/apps/A-bar.zip!p.bpmn")),
            "decisions" to listOf(mapOf("key" to "d", "file" to "mod/src/main/resources/apps/A-bar.zip!d.dmn")),
            // two archives of one name in different places stay spelled out in full
            "forms" to listOf(
                mapOf("key" to "f1", "name" to "F1", "file" to "one/B.zip!f.form"),
                mapOf("key" to "f2", "name" to "F2", "file" to "two/B.zip!f.form"),
            ),
        ))
        assertTrue(text, text.contains("### P (`p`) — `A-bar.zip!p.bpmn`"))
        assertTrue(text, text.contains("— `A-bar.zip!d.dmn`"))
        assertEquals(1, text.lines().count { it.contains("mod/src/main/resources/apps/A-bar.zip") })
        assertTrue(text, text.lines().single { it.contains("A-bar.zip` = ") }.contains("`A-bar.zip` = `mod/src/main/resources/apps/A-bar.zip`"))
        assertTrue(text, text.contains("— `one/B.zip!f.form`") && text.contains("— `two/B.zip!f.form`"))
    }

    @Test
    fun anAppsMembershipAndItsServicesAreNotListedTwice() {
        val text = render(mapOf(
            "services" to listOf(mapOf("key" to "s", "name" to "S", "type" to "rest", "file" to "s.service", "operations" to emptyList<Any?>())),
            "resolvedRefs" to listOf(
                mapOf("from" to "app", "rel" to "contains", "kind" to "model:bpmn", "value" to "p", "target" to "p.bpmn", "targetType" to "model"),
                mapOf("from" to "p", "rel" to "callActivity", "kind" to "process", "value" to "q", "target" to "q.bpmn", "targetType" to "model"),
            ),
        ))
        assertFalse(text, text.contains("—contains→"))
        assertTrue(text, text.contains("`p` —callActivity→ process `q`"))
        assertFalse("§12 does not repeat §7's service list", text.substringAfter("## 12.").contains("Service models"))
    }

    /**
     * A decision without a table, a database operation and a class with none of the glue interfaces used
     * to print `(None, None rules) — in:  → out:`, `` `None` None `` and `implements []`; a script button
     * setting a variable to the literal `true` printed `= True`.
     */
    @Test
    fun absentValuesLeaveNoTrace() {
        val text = render(mapOf(
            "decisions" to listOf(mapOf("key" to "drd", "name" to "DRD", "file" to "d.dmn")),
            "services" to listOf(mapOf("key" to "db", "name" to "DB", "type" to "database", "file" to "db.service",
                "operations" to listOf(mapOf("key" to "findById", "type" to "lookup", "params" to listOf(mapOf("name" to "customerId")))))),
            "javaGlue" to listOf(mapOf("fqn" to "com.example.Helper", "interfaces" to emptyList<String>(), "file" to "Helper.java", "line" to 3)),
            "forms" to listOf(mapOf("key" to "f", "name" to "F", "file" to "f.form", "fields" to listOf(
                mapOf("id" to "flag", "type" to "scriptButton", "label" to "Set flag", "value" to "{{done}}",
                    "settings" to mapOf("script" to true, "visible" to false)),
                mapOf("id" to "load", "type" to "scriptButton", "label" to "Load defaults", "value" to "{{data}}",
                    "settings" to mapOf("script" to mapOf("regions" to listOf("north")), "visible" to false)),
                // `visible: true` is the default, not a condition
                mapOf("id" to "name", "type" to "text", "label" to "Name", "settings" to mapOf("visible" to true, "enabled" to "{{editable}}")),
            ))),
        ))
        assertTrue(text, text.contains("- `drd` (no decision table) — `d.dmn`"))
        assertTrue(text, text.contains("    - op `findById` (lookup) · in: `customerId`"))
        assertTrue(text, text.contains("- `com.example.Helper` — `Helper.java:3`"))
        assertTrue(text, text.contains("- `flag` [scriptButton] Set flag ← `{{done}}` = `true` *(hidden)*"))
        assertTrue(text, text.contains("- `load` [scriptButton] Load defaults ← `{{data}}` = `{\"regions\":[\"north\"]}` *(hidden)*"))
        assertTrue(text, text.lines().contains("- `name` [text] Name enabled when `{{editable}}`"))
        assertFalse(text, text.contains("None") || text.contains("[]") || text.contains("True"))
    }

    /**
     * Past the cap the report lists the variables shared by the most models — the alphabetically first 60
     * said nothing about why those — and says that is how they were chosen.
     */
    @Test
    fun theVariablesListedAreTheOnesSharedByTheMostModels() {
        fun variable(key: String, usedBy: List<String>, writes: List<Map<String, Any?>> = emptyList()) = mapOf(
            "id" to "variable:$key", "type" to "variable", "key" to key,
            "data" to mapOf("usedBy" to usedBy, "writes" to writes, "reads" to emptyList<Any?>()),
        )
        val nodes = (0 until 60).map { variable("a%02d".format(it), listOf("process:p")) } +
            (0 until 10).map { variable("zz$it", listOf("process:p", "case:c", "form:f"),
                listOf(mapOf("element" to "calc", "elementName" to "Calculate", "model" to "process:p", "via" to "resultVariable"))) }
        val text = render(mapOf("graph" to mapOf("nodes" to nodes, "edges" to emptyList<Any?>())))
        assertTrue(text, text.contains("**Variables (70)** — the 60 shared by the most models;"))
        for (i in 0 until 10) assertTrue("zz$i", text.contains("- `zz$i`"))
        assertTrue(text, text.contains("set by: `Calculate` (resultVariable)"))
        // and they are listed by name, so the list stays scannable
        val listed = text.lines().filter { it.startsWith("- `a") || it.startsWith("- `zz") }
        assertEquals(listed.sorted(), listed)
    }
}
