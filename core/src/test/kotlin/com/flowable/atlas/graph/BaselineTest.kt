package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [Baseline.load] beyond the graph.json case CliTest covers: an archive of the base, and keeping all of it. */
class BaselineTest {

    private val dir: File = Files.createTempDirectory("atlas-baseline").toFile()

    private fun archive(name: String) = File(dir, name).apply { writeBytes(ByteArray(0)) }

    private fun parseIssue(prefix: String) = linkedMapOf<String, Any?>(
        "check" to "parseIssues", "severity" to "error", "node" to null,
        "label" to "${prefix}broken.form", "file" to "${prefix}broken.form", "message" to "parse: (form) DEMO",
    )

    /**
     * `git show "$BASE:apps/app.zip" > base.zip` is the way to the base of a pull request, and an archive names
     * everything in it after itself: read as it is, the base's unparseable file is not the head's.
     */
    @Test
    fun anArchiveUnderAnotherNameIsReadAsTheHeadNamesIt() {
        val base = archive("base.zip")
        val head = archive("app.zip")
        val loaded = Baseline.load(base, head = head, whole = true) { mapOf("findings" to listOf(parseIssue("base.zip!"))) }
        val f = loaded.findings.single()
        assertEquals("app.zip!broken.form", f["file"])
        assertEquals("app.zip!broken.form", f["label"])
        assertTrue("the same finding, so not new", Findings.newSince(loaded.findings, listOf(parseIssue("app.zip!"))).isEmpty())
        assertSame("the whole baseline carries the same findings", loaded.findings, loaded.result!!["findings"])

        val asIs = Baseline.load(base, head = File(dir, "project")) { mapOf("findings" to listOf(parseIssue("base.zip!"))) }
        assertEquals("a head that is no archive names nothing after one", "base.zip!broken.form", asIs.findings.single()["file"])
    }

    @Test
    fun theWholeBaselineIsKeptOnlyWhenAskedFor() {
        val result = mapOf("findings" to emptyList<Any?>(), "processes" to emptyList<Any?>())
        assertNull(Baseline.load(dir) { result }.result)
        assertSame(result, Baseline.load(dir, whole = true) { result }.result)
        val json = File(dir, "graph.json").apply { writeText("""{"_generated":{"atlas":"0.0.1"},"findings":[],"processes":[]}""") }
        assertNull(Baseline.load(json) { error("a graph.json is read, not analysed") }.result)
        assertEquals(listOf<Any?>(), Baseline.load(json, whole = true) { error("read, not analysed") }.result!!["processes"])
    }
}
