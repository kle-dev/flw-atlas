package com.flowable.atlas.cli

import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract tests for the standalone CLI (`run(argv)`), mirroring Python's `tests/test_cli.py`:
 * `--all` writes exactly the five `<name>.*` artifacts; the Markdown artifacts match the committed
 * goldens (the renderers are already golden-verified, so `--all` output + the trailing newline the
 * golden test appends is byte-identical); `graph.json` parses and normalizes to the golden graph; a
 * `--summary --stdout` run returns 0 without touching disk; a missing path exits 2.
 */
class CliTest {

    @Test
    fun allWritesExactlyFiveArtifacts() {
        val out = tempDir()
        val code = run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q"))
        assertEquals("run(--all) exit code", 0, code)

        val expected = setOf(
            "miniproject.summary.md", "miniproject.overview.md", "miniproject.graph.json",
            "miniproject.explorer.html", "miniproject.CLAUDE.md",
        )
        assertEquals("exactly the five artifacts", expected, out.listFiles()!!.map { it.name }.toSet())
        for (f in out.listFiles()!!) assertTrue("${f.name} is empty", f.length() > 0)
    }

    @Test
    fun allMarkdownArtifactsMatchGoldens() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))

        // Python `--all` writes `render(...)` with no trailing newline; the golden is `render(...) + "\n"`
        // (see tests/test_golden.py). So the written file plus that newline must equal the golden byte-for-byte.
        for (kind in listOf("summary", "overview")) {
            val written = File(out, "miniproject.$kind.md").readText()
            val golden = goldenFile("miniproject.$kind.md").readText()
            assertEquals("miniproject.$kind.md differs from golden", golden, written + "\n")
        }
    }

    /**
     * The written `graph.json` is the *projection* of the extract result (`GraphJsonRenderer`), not the
     * result verbatim: a model node's body is stored once in its bucket and the node points at it with
     * `dataIn`, nodes carry a `usedBy` reverse index, a `_schema` block describes the shape, and the file
     * is minified. So this asserts the projection's contract against the golden result rather than byte
     * equality with it — losing a bucket, or a node whose body can no longer be found, fails here.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun allGraphJsonIsTheProjectionOfTheGoldenResult() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))

        val text = File(out, "miniproject.graph.json").readText()
        assertTrue("graph.json must be minified by default", !text.contains("\n  \""))
        val written = MiniJson.parse(text) as Map<String, Any?>
        val golden = MiniJson.parse(goldenFile("miniproject.graph.json").readText()) as Map<String, Any?>

        assertTrue("graph.json must describe its own shape", written.containsKey("_schema"))
        assertTrue("graph.json must say when and by which Atlas it was generated", written.containsKey("_generated"))
        assertEquals(
            "graph.json top-level keys differ from the golden result (plus _schema and _generated)",
            (golden.keys + "_schema" + "_generated").sorted(), written.keys.sorted(),
        )
        // Every top-level section except `graph` is passed through untouched.
        for (key in golden.keys) {
            if (key == "graph") continue
            assertEquals("section '$key' differs", canonical(golden[key]), canonical(written[key]))
        }
        // Every model node resolves back to exactly one bucket entry via `dataIn` + type/key.
        val nodes = (written["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        var resolved = 0
        for (n in nodes) {
            val data = n["data"] as? Map<String, Any?> ?: continue
            val bucket = data["dataIn"] as? String ?: continue
            val entries = written[bucket] as? List<Map<String, Any?>>
                ?: error("node ${n["id"]} points at missing bucket '$bucket'")
            val hits = entries.filter { it["key"] == n["key"] }
            assertTrue("node ${n["id"]} does not resolve in bucket '$bucket'", hits.isNotEmpty())
            resolved++
        }
        assertTrue("no model node used the dataIn indirection at all", resolved > 0)
        // The reverse index is present and agrees with the edges.
        val edges = (written["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>
        val expectedUsers = edges.filter { it["rel"] != "contains" }
            .groupBy({ it["t"] }, { it["s"] }).mapValues { it.value.distinct().toSet() }
        for (n in nodes) {
            val declared = (n["usedBy"] as? List<*>)?.toSet() ?: emptySet()
            assertEquals("usedBy of ${n["id"]}", expectedUsers[n["id"]] ?: emptySet<Any?>(), declared)
        }
    }

    @Test
    fun prettyRestoresTheIndentedForm() {
        val out = tempDir()
        val f = File(out, "graph.json")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--json", "--pretty", "-o", f.path, "-q")))
        assertTrue("--pretty must indent", f.readText().contains("\n  \""))
    }

    @Test
    fun sliceRendersOneNodeInBothDirections() {
        val out = tempDir()
        val f = File(out, "slice.md")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--slice", "process:orderProcess", "-o", f.path, "-q")))
        val text = f.readText()
        assertTrue(text, text.startsWith("# `process:orderProcess`"))
        assertTrue("a slice must state what the node uses", text.contains("## Uses"))
        assertTrue("a slice must state who uses the node", text.contains("## Used by"))
        // A bare key works too — a reader should not have to know Atlas calls it a `process`.
        assertEquals(0, run(arrayOf(fixtureDir().path, "--slice", "orderProcess", "--stdout", "-q")))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--slice", "nope:nothing", "--stdout", "-q")))
    }

    @Test
    fun summaryStdoutReturnsZeroAndWritesNothing() {
        // In --stdout mode the tool prints and returns before writing any file. Run with a fresh,
        // empty output dir as cwd-analogue and assert it stays empty (no APP_OVERVIEW.* produced).
        val out = tempDir()
        val code = run(arrayOf("--summary", "--stdout", fixtureDir().path, "-o", File(out, "unused.md").path))
        assertEquals(0, code)
        assertTrue("stdout mode must not write to disk", out.listFiles()!!.isEmpty())
    }

    @Test
    fun missingPathExitsTwo() {
        assertEquals(2, run(arrayOf("/no/such/path")))
    }

    /**
     * `--fail-on` is what makes Atlas usable as a CI gate: the fixture carries a deliberately broken
     * form (a parse *error*), so `--fail-on error` returns 1 — with every artifact still written, because
     * a pipeline wants the report as well as the red build. A check nobody has heard of is a misuse.
     */
    @Test
    fun failOnTurnsFindingsIntoExitOneButStillWrites() {
        val out = tempDir()
        assertEquals(1, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error")))
        assertEquals("the artifacts are written before the verdict", 5, out.listFiles()!!.count { it.isFile })
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "parseIssues")))
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on=warning,missingRefs")))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "nosuchcheck")))
    }

    @Test
    fun theJarAnswersHelpAndRefusesAllWithSlice() {
        assertEquals(0, run(arrayOf("--help")))
        assertEquals(0, run(arrayOf("-h")))
        // `--all` used to win over `--slice` silently; every other flag conflict was already an error.
        assertEquals(2, run(arrayOf(fixtureDir().path, "--all", "--slice", "orderProcess", "-q")))
        // -v is read now (it lists the parse issues); it must not change the exit code
        assertEquals(0, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-v")))
    }

    // ---- helpers ----

    private fun tempDir(): File = Files.createTempDirectory("atlas-cli-test").toFile()

    private fun fixtureDir(): File =
        File(repoRoot(), "core/src/test/resources/miniproject").also {
            check(it.isDirectory) { "fixture not found at $it" }
        }

    private fun goldenFile(name: String): File =
        File(repoRoot(), "core/src/test/resources/golden/$name").also { check(it.isFile) { "golden not found at $it" } }

    /** Walk up from the test working dir (the module dir under Gradle) to the repo root. */
    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        return d ?: error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
    }

    /** Replicates `com.flowable.atlas.graph.GoldenNormalize`: sort object keys + sort lists by their
     *  canonical JSON, then round-trip through MiniJson for type-consistent structural comparison. */
    private fun canonical(v: Any?): Any? = MiniJson.parse(MiniJson.stringify(normalize(v)))

    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries
            .sortedBy { it.key.toString() }
            .associateTo(LinkedHashMap()) { it.key.toString() to normalize(it.value) }
        is Collection<*> -> v.map { normalize(it) }.sortedBy { MiniJson.stringify(it) }
        else -> v
    }
}
