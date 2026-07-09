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

    @Test
    fun allGraphJsonNormalizesToGolden() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))

        val written = MiniJson.parse(File(out, "miniproject.graph.json").readText())
        val golden = MiniJson.parse(goldenFile("miniproject.graph.json").readText())
        assertEquals(
            "graph.json (normalized) differs from tests/golden/miniproject.graph.json",
            canonical(golden), canonical(written),
        )
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

    // ---- helpers ----

    private fun tempDir(): File = Files.createTempDirectory("atlas-cli-test").toFile()

    private fun fixtureDir(): File =
        File(repoRoot(), "core/src/test/resources/miniproject").also {
            check(it.isDirectory) { "fixture not found at $it" }
        }

    private fun goldenFile(name: String): File =
        File(repoRoot(), "tests/golden/$name").also { check(it.isFile) { "golden not found at $it" } }

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
