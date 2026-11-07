package com.flowable.atlas.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The rows a failed gate prints, and the same findings as GitHub annotations: the parts a run on a project cannot pin. */
class GateReportTest {

    private fun finding(check: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("check" to check, "severity" to "error", "node" to "process:DEMO-p", "label" to "DEMO process",
            "message" to "something is wrong").apply { putAll(extra) }

    @Test
    fun theListStopsAtItsCapAndSaysWhereTheRestAre() {
        val many = (1..60).map { finding("missingRefs", "node" to "external:DEMO-$it") }
        val lines = failingLines(many, more = "see graph.json")
        assertEquals(51, lines.size)
        assertEquals("  … +10 more — see graph.json", lines.last())
        assertEquals("a short list has no tail", 3, failingLines(many.take(3), more = "x").size)
    }

    @Test
    fun errorsComeFirstThenTheCatalogsOrder() {
        val lines = failingLines(listOf(
            finding("unusedForms", "severity" to "warning", "node" to "form:DEMO-f"),
            finding("missingRefs", "node" to "external:DEMO-x"),
            finding("parseIssues", "node" to null, "file" to "forms/DEMO-broken.form", "label" to "forms/DEMO-broken.form"),
        ), more = "x")
        assertTrue(lines.joinToString("\n"), lines[0].contains("parseIssues") && lines[1].contains("missingRefs") && lines[2].contains("unusedForms"))
        // A file-less finding is where its node id says; a file's own name is not repeated as its label.
        assertTrue(lines[1], lines[1].contains("external:DEMO-x — something is wrong"))
        assertTrue(lines[0], lines[0].endsWith("forms/DEMO-broken.form — something is wrong"))
    }

    /** A script's line counts from the script, not from the model file: `file:3` would point at the wrong line. */
    @Test
    fun aScriptLineIsTheScriptsNotTheFiles() {
        val f = finding("scriptIssues", "file" to "processes/DEMO.bpmn", "line" to 3.0, "element" to "DEMO-task")
        val row = failingLines(listOf(f), more = "x").single()
        assertTrue(row, row.contains("processes/DEMO.bpmn (script line 3)  DEMO process @DEMO-task — something is wrong"))
        assertFalse(row, row.contains(":3"))
        val gh = githubAnnotations(listOf(f), File("."), File(".")).single()
        assertFalse("no line for a script finding: $gh", gh.contains("line="))
        // Another check's line is the file's own.
        val leftover = finding("parseIssues", "file" to "forms/DEMO.form", "line" to 7)
        assertTrue(failingLines(listOf(leftover), more = "x").single().contains("forms/DEMO.form:7"))
        assertTrue(githubAnnotations(listOf(leftover), File("."), File(".")).single().contains(",line=7,"))
    }

    @Test
    fun aMessageOnSeveralLinesStaysOneRow() {
        val row = failingLines(listOf(finding("invalidExpr", "message" to "first\n  second")), more = "x").single()
        assertTrue(row, row.endsWith("— first second"))
        // An expression's node id is the expression, and one can span lines.
        val multi = failingLines(listOf(finding("invalidExpr", "node" to "expression:\${a &&\n   b(}")), more = "x")
        assertEquals(multi.toString(), 1, multi.single().lines().size)
        assertTrue(multi.single(), multi.single().contains("expression:\${a && b(} — something is wrong"))
    }

    @Test
    fun annotationsEscapeWhatGithubWouldReadAsSyntax() {
        val dir = File("/repo/DEMO-project")
        val f = finding("missingRefs", "severity" to "warning", "file" to "apps/DEMO,app.zip!processes/p.bpmn", "line" to 4, "message" to "100% wrong:\nhere")
        val gh = githubAnnotations(listOf(f), dir, File("/repo")).single()
        assertTrue(gh, gh.startsWith("::warning file=DEMO-project/apps/DEMO%2Capp.zip,title=Atlas missingRefs::"))
        assertFalse("GitHub can only point at the archive, so no line: $gh", gh.contains("line="))
        assertTrue(gh, gh.endsWith("DEMO process — 100%25 wrong:%0Ahere"))
        val noFile = githubAnnotations(listOf(finding("missingRefs", "node" to "external:DEMO-x")), dir, File("/repo")).single()
        assertEquals("::error title=Atlas missingRefs::external:DEMO-x — something is wrong", noFile)
    }
}
