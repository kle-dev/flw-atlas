package com.flowable.atlas

import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A golden that fails has to say where: the line, the JSON path, and both versions of it. */
class GoldenFilesTest {

    private fun laidOut(json: String) = MiniJson.reindent(json, 2)!! + "\n"

    @Test
    fun aDifferenceIsReportedWithItsLineAndItsJsonPath() {
        val golden = laidOut("""{"graph":{"nodes":[{"id":"process:a","data":{"x":1}},{"id":"form:b","data":{"fields":["f1","f2"]}}]}}""")
        val now = laidOut("""{"graph":{"nodes":[{"id":"process:a","data":{"x":1}},{"id":"form:b","data":{"fields":["f2","f1"]}}]}}""")
        val report = GoldenFiles.firstDifference(golden, now)
        assertTrue(report, report.contains("at $.graph.nodes[1 id=form:b].data.fields[0]"))
        val lines = report.lines()
        assertTrue("the golden's line is marked -: $report", lines.any { it.startsWith("- ") && it.substring(2).trim() == "\"f1\"," })
        assertTrue("the new line is marked +: $report", lines.any { it.startsWith("+ ") && it.substring(2).trim() == "\"f2\"," })
    }

    @Test
    fun aClosedObjectGivesThePathBackToItsParent() {
        val text = laidOut("""{"a":{"b":1},"c":[1,2]}""").lines()
        // line 4 is `},` closing `a`; line 5 opens `c`
        assertEquals("$.a", GoldenFiles.jsonPathAt(text, 3))
        assertEquals("$.c", GoldenFiles.jsonPathAt(text, 4))
        assertEquals("$.c[1]", GoldenFiles.jsonPathAt(text, 6))
    }

    @Test
    fun markdownHasNoJsonPath() {
        assertNull(GoldenFiles.jsonPathAt(listOf("# Title", "text"), 1))
        assertTrue(GoldenFiles.firstDifference("# T\na\nb\n", "# T\na\nc\n").startsWith("first difference at line 3:"))
    }
}
