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
    fun allWritesExactlyFiveArtifactsAndAGitignore() {
        val out = tempDir()
        val code = run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q"))
        assertEquals("run(--all) exit code", 0, code)

        val expected = setOf(
            "miniproject.summary.md", "miniproject.overview.md", "miniproject.graph.json",
            "miniproject.explorer.html", "miniproject.CLAUDE.md",
            // The drawings: the fixture has no diagram layout, so only its forms and pages are drawn —
            // as the wireframes the explorer and the IDE preview show.
            "miniproject.diagrams",
            // Not an artifact: it is what lets the folder be committed for the sake of waivers.json
            // without ever carrying an analysis into a repository.
            ".gitignore",
        )
        assertEquals("exactly the five artifacts, the drawings and the gitignore", expected,
            out.listFiles()!!.map { it.name }.toSet())
        for (f in out.listFiles()!!) assertTrue("${f.name} is empty", f.length() > 0)
        val drawings = File(out, "miniproject.diagrams").listFiles()!!.map { it.name }.toSet()
        assertTrue("the order form is drawn: $drawings", "orderForm.svg" in drawings)
        assertTrue("no process of the fixture has a layout to draw: $drawings", "orderProcess.svg" !in drawings)
        val ignore = File(out, ".gitignore").readText()
        assertTrue("everything is ignored", ignore.lineSequence().any { it.trim() == "*" })
        assertTrue("except the waivers", ignore.contains("!waivers.json"))
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
        assertEquals("the artifacts are written before the verdict", 6, out.listFiles()!!.count { it.isFile })
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "parseIssues")))
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on=warning,missingRefs")))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "nosuchcheck")))
    }

    /**
     * An explorer extension is in the page only when it was asked for — and a name that is not one, or a
     * mode without an explorer page, is a misuse rather than a run that quietly produced something else.
     */
    @Test
    fun anExtensionIsInThePageOnlyWhenNamed() {
        val out = tempDir()
        val plain = File(out, "plain.html"); val erd = File(out, "erd.html")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--html", "-o", plain.path, "-q")))
        assertEquals(0, run(arrayOf(fixtureDir().path, "--html", "-o", erd.path, "-q", "--extension", "erd")))
        assertTrue("the designer registers itself", erd.readText().contains("ATLAS_EXT.erd="))
        assertTrue("…and only when named", !plain.readText().contains("ATLAS_EXT.erd="))
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", File(out, "all").path, "-q", "--extension=erd")))
        assertTrue(File(out, "all/miniproject.explorer.html").readText().contains("ATLAS_EXT.erd="))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--html", "-o", erd.path, "-q", "--extension", "nosuchpart")))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--extension", "erd")))
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

    /**
     * The round trip the feature exists for: a decision written into the output folder is read back on
     * the next run, keeps the artifacts honest, and takes the finding out of the gate — while
     * `--no-waivers` still shows it. If any one of those three drifts the feature is worse than absent,
     * because a team would believe a gate that is not there.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aWaiverInTheOutputFolderSurvivesToTheNextRunAndTheGate() {
        val out = tempDir()
        assertEquals(1, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error")))

        val graph = MiniJson.parse(File(out, "miniproject.graph.json").readText()) as Map<String, Any?>
        val errors = (graph["findings"] as List<Map<String, Any?>>).filter { it["severity"] == "error" }
        assertTrue("the fixture must produce an error to waive", errors.isNotEmpty())
        val waivers = errors.joinToString(",\n") { f ->
            val node = (f["node"] ?: f["file"]).toString()
            """{"check": ${'"'}${f["check"]}${'"'}, "node": ${MiniJson.stringify(node)}, "reason": "known, accepted"}"""
        }
        File(out, "waivers.json").writeText("""{"version": 1, "waivers": [$waivers]}""")

        assertEquals("every error is accepted, so the gate passes", 0,
            run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error")))

        val after = MiniJson.parse(File(out, "miniproject.graph.json").readText()) as Map<String, Any?>
        val checks = after["checks"] as Map<String, Any?>
        val all = after["findings"] as List<Map<String, Any?>>
        val waived = all.filter { it["waived"] != null }

        // The invariant that matters: the count and the list say the same thing. A badge that disagrees
        // with the page under it is how a team stops trusting either.
        assertEquals("the waived count matches the marked findings",
            waived.size, (checks["waived"] as Number).toInt())
        assertEquals("and the open count matches the rest", all.size - waived.size, (checks["open"] as Number).toInt())
        assertTrue("nothing was dropped", waived.size >= errors.size)
        assertTrue("no error is left open", all.none { it["severity"] == "error" && it["waived"] == null })
        // A rule without `element`/`subject` covers every finding of that check on that node, so it can
        // legitimately cover more than the errors that prompted it. That is documented, and visible here.
        assertTrue("every waived finding carries its reason",
            waived.all { (it["waived"] as Map<String, Any?>)["reason"] == "known, accepted" })

        assertEquals("--no-waivers shows what the file is hiding", 1,
            run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error", "--no-waivers")))
    }

    /**
     * `-o report.md` names a file, and the waiver file that counts is the one beside it — not one that
     * would have to live *inside* `report.md`. The default used to be the output path itself, which in
     * every single-artifact mode found nothing and silently reported every finding.
     */
    @Test
    fun waiversDefaultToTheOutputFilesFolder() {
        val out = tempDir()
        assertEquals(1, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error")))
        waiveEvery(out) { it["severity"] == "error" }
        assertEquals("the summary written into that folder reads the folder's waivers", 0,
            run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "report.md").path, "-q", "--fail-on", "error")))
        assertEquals("--waivers still wins over the default", 1,
            run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "report.md").path, "-q", "--fail-on", "error",
                "--waivers", File(out, "nowhere.json").path)))
    }

    /** The status line counted parse issues from the raw diagnostics, which know nothing about waivers. */
    @Test
    fun aWaivedParseIssueLeavesTheStatusLine() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))
        assertTrue("the fixture must carry a parse issue", stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", out.path)) }
            .contains("parse issue(s)"))
        waiveEvery(out) { it["check"] == "parseIssues" }
        val line = stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", out.path)) }
        assertTrue("the line no longer counts what the file accepted: $line", !line.contains("parse issue(s)"))
        assertTrue("and says how many are waived", line.contains("waived"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun waiveEvery(out: File, which: (Map<String, Any?>) -> Boolean) {
        val graph = MiniJson.parse(File(out, "miniproject.graph.json").readText()) as Map<String, Any?>
        val picked = (graph["findings"] as List<Map<String, Any?>>).filter(which)
        assertTrue("the fixture must produce a finding to waive", picked.isNotEmpty())
        val rules = picked.joinToString(",\n") { f ->
            val node = (f["node"] ?: f["file"]).toString()
            """{"check": ${'"'}${f["check"]}${'"'}, "node": ${MiniJson.stringify(node)}, "reason": "known, accepted"}"""
        }
        File(out, "waivers.json").writeText("""{"version": 1, "waivers": [$rules]}""")
    }

    private fun stderrOf(block: () -> Unit): String {
        val buf = java.io.ByteArrayOutputStream()
        val prev = System.err
        System.setErr(java.io.PrintStream(buf, true, "UTF-8"))
        try { block() } finally { System.setErr(prev) }
        return buf.toString("UTF-8")
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
