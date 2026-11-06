package com.flowable.atlas.cli

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.parsing.ModelKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract tests for the standalone CLI (`run(argv)`), mirroring Python's `tests/test_cli.py`:
 * `--all` writes exactly the six `<name>.*` artifacts (and the ER page, for a project with tables); the Markdown artifacts match the committed
 * goldens (the renderers are already golden-verified, so `--all` output + the trailing newline the
 * golden test appends is byte-identical); `graph.json` is the golden projection, minified; a
 * `--summary --stdout` run returns 0 without touching disk; a missing path exits 2.
 */
class CliTest {

    @Test
    fun allWritesExactlySixArtifactsAndAGitignore() {
        val out = tempDir()
        val code = run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q"))
        assertEquals("run(--all) exit code", 0, code)

        val expected = setOf(
            "miniproject.summary.md", "miniproject.overview.md", "miniproject.graph.json",
            "miniproject.explorer.html", "miniproject.CLAUDE.md",
            // The agent's guide to driving Flowable Work in a browser — written for every project, in two
            // files: what a skill loads whole, and the project map it reads on demand.
            "miniproject.playwright.md", "miniproject.playwright-map.md",
            // The ER diagram designer, because the fixture has a table: a project without one gets no such page.
            "miniproject.erd.html",
            // The drawings: the fixture has no diagram layout, so only its forms and pages are drawn —
            // as the wireframes the explorer and the IDE preview show.
            "miniproject.diagrams",
            // Not an artifact: it is what lets the folder be committed for the sake of waivers.json
            // without ever carrying an analysis into a repository.
            ".gitignore",
        )
        assertEquals("exactly the six artifacts (the guide in two files), the drawings and the gitignore", expected,
            out.listFiles()!!.map { it.name }.toSet())
        for (f in out.listFiles()!!) assertTrue("${f.name} is empty", f.length() > 0)
        val drawings = File(out, "miniproject.diagrams").listFiles()!!.map { it.name }.toSet()
        assertTrue("the order form is drawn: $drawings", "orderForm.svg" in drawings)
        assertTrue("no process of the fixture has a layout to draw: $drawings", "orderProcess.svg" !in drawings)
        // The .gitignore names what Atlas wrote and nothing else — every artifact, by its suffix.
        val patterns = ignorePatterns(out)
        for (f in expected - ".gitignore") assertTrue("$f is ignored by $patterns", ignored(patterns, f))
        assertFalse("waivers.json is not", ignored(patterns, "waivers.json"))
        // The guide points into its map, and CLAUDE.md names both.
        val guide = File(out, "miniproject.playwright.md").readText()
        assertTrue("the guide points at its map", guide.contains("miniproject.playwright-map.md"))
        assertTrue("the map holds the case", File(out, "miniproject.playwright-map.md").readText().contains("#### Case `reviewCase`"))
        assertFalse("the details are not repeated in the guide", guide.contains("#### Case `reviewCase`"))
        val claude = File(out, "miniproject.CLAUDE.md").readText()
        assertTrue(claude.contains("miniproject.playwright.md") && claude.contains("miniproject.playwright-map.md"))
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
     * The written `graph.json` is the golden, apart from `_generated` (the time and the Atlas version) and
     * the layout: the file is minified, the golden is laid out one token per line. `GoldenExtractionTest`
     * pins the projection in `:core` and says where a difference is; this pins that the CLI writes exactly
     * that projection. The contract assertions after it repeat part of what the comparison proves, to say
     * what the shape is for: a model body is stored once and found through `dataIn`, and `usedBy` agrees
     * with the edges.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun allGraphJsonIsTheGoldenProjection() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))

        val text = File(out, "miniproject.graph.json").readText()
        assertTrue("graph.json must be minified by default", !text.contains("\n  \""))
        val written = MiniJson.parse(text) as Map<String, Any?>
        assertEquals("graph.json must describe its own shape first", "_schema", written.keys.first())
        assertTrue("graph.json must say when and by which Atlas it was generated", written.containsKey("_generated"))
        // Compact again before laying it out: a parsed number is a Double, which the compact writer prints
        // as `64` and the indented one as `64.0`.
        val content = LinkedHashMap(written).apply { remove("_generated") }
        assertEquals(
            "miniproject.graph.json differs from the golden — GoldenExtractionTest names the line and its path",
            goldenFile("miniproject.graph.json").readText(),
            MiniJson.reindent(MiniJson.stringify(content), 2) + "\n",
        )

        // Every node of a type that owns a bucket points into it, and resolves there by its key. Asserted
        // for every such node, not only for those that happen to carry `dataIn`: a node that carries a
        // second copy of its body instead is exactly the growth this file must not have.
        val nodes = (written["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        val bucketTypes = ModelKinds.MODEL_KINDS.map { it.nodeType }.toSet()
        var resolved = 0
        for (n in nodes.filter { it["type"] in bucketTypes }) {
            val data = n["data"] as? Map<String, Any?>
            assertEquals("node ${n["id"]} carries its body instead of pointing at it", setOf("dataIn"), data?.keys)
            val bucket = data!!["dataIn"] as String
            val entries = written[bucket] as? List<Map<String, Any?>>
                ?: error("node ${n["id"]} points at missing bucket '$bucket'")
            assertTrue("node ${n["id"]} does not resolve in bucket '$bucket'", entries.any { it["key"] == n["key"] })
            resolved++
        }
        assertTrue("no model node used the dataIn indirection at all", resolved > 0)
        // The reverse index is present and agrees with the edges — plus, for the nodes no edge points at
        // (variables, expressions, operations…), the models their own data names, moved up to the node.
        val edges = (written["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>
        val expectedUsers = edges.filter { it["rel"] != "contains" }
            .groupBy({ it["t"] }, { it["s"] }).mapValues { it.value.distinct().toSet() }
        val goldenNodes = ((golden["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] }
        for (n in nodes) {
            val declared = (n["usedBy"] as? List<*>)?.toSet() ?: emptySet()
            val goldenData = goldenNodes[n["id"]]?.get("data") as? Map<String, Any?>
            val own = if ((n["data"] as? Map<*, *>)?.containsKey("dataIn") == true) emptySet()
                else (goldenData?.get("usedBy") as? List<*>)?.toSet() ?: emptySet()
            assertEquals("usedBy of ${n["id"]}", (expectedUsers[n["id"]] ?: emptySet<Any?>()) + own, declared)
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
        // …and so does what an agent editing a model knows first: its file, or the key in another case.
        for (target in listOf("processes/order.bpmn", "ORDERPROCESS")) {
            assertTrue(target, stdoutOf { assertEquals(target, 0, run(arrayOf(fixtureDir().path, "--slice", target, "-q"))) }
                .contains("# `process:orderProcess`"))
        }
        // A near miss names the id it was probably meant to be, instead of an example that does not exist.
        var code = -1
        val err = stderrOf { code = run(arrayOf(fixtureDir().path, "--slice", "ordrProcess", "--stdout", "-q")) }
        assertEquals(2, code)
        assertTrue(err, err.contains("did you mean") && err.contains("process:orderProcess"))
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

    /**
     * Alone, the summary and the report have nothing written beside them, so they name no sibling: the
     * summary handed to an agent used to send it to `<name>.overview.md` and `<name>.graph.json`, which
     * only `--all` writes. They say how to produce them instead.
     */
    @Test
    fun aSingleSummaryOrReportNamesNoFileItDidNotWrite() {
        val out = tempDir()
        val summary = File(out, "s.md")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--summary", "-o", summary.path, "-q")))
        val s = summary.readText()
        for (sibling in listOf("miniproject.overview.md", "miniproject.graph.json", "miniproject.explorer.html")) {
            assertFalse("the summary names $sibling", s.contains(sibling))
        }
        assertTrue(s, s.contains("--all -o <out-dir>"))
        val report = File(out, "o.md")
        assertEquals(0, run(arrayOf(fixtureDir().path, "-o", report.path, "-q")))
        val o = report.readText()
        assertFalse("the report names a diagrams folder it did not write", o.contains("miniproject.diagrams/"))
        assertFalse("the report names a graph it did not write", o.contains("miniproject.graph.json"))
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
        assertEquals("the artifacts are written before the verdict", 9, out.listFiles()!!.count { it.isFile })
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "parseIssues")))
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on=warning,missingRefs")))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "nosuchcheck")))
    }

    /** A red run names what made it red, one finding per line; it used to print only counts per check. */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aFailedGateNamesItsFindings() {
        val golden = MiniJson.parse(goldenFile("miniproject.graph.json").readText()) as Map<String, Any?>
        val missing = (golden["findings"] as List<Map<String, Any?>>).filter { it["check"] == "missingRefs" }
        assertTrue("the fixture has a dangling reference", missing.isNotEmpty())
        var code = -1
        val err = stderrOf { code = run(arrayOf(fixtureDir().path, "--summary", "--stdout", "--fail-on", "missingRefs")) }
        assertEquals(1, code)
        val rows = err.lines().filter { it.startsWith("  ") && "missingRefs" in it }
        assertEquals(err, missing.size, rows.size)
        // each row says where the finding is — its file and line, else its node id — and its message
        fun where(f: Map<String, Any?>) =
            f["file"]?.let { file -> f["line"]?.let { "$file:${(it as Number).toInt()}" } ?: file.toString() } ?: f["node"].toString()
        for (f in missing) assertTrue(err, rows.any { it.contains(where(f)) && it.contains(f["message"].toString()) })
        assertFalse("-q keeps stderr for errors", stderrOf {
            run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "missingRefs"))
        }.contains("missingRefs"))
    }

    /**
     * `--baseline` gates the change, not the backlog: the fixture's own errors are in the base, so only the
     * dangling call a change adds fails the run — from the base's sources or from the graph.json it wrote.
     */
    @Test
    fun aBaselineGatesOnlyWhatIsNew() {
        val base = projectCopy()
        val head = projectCopy()
        File(head, "processes/demo-extra.bpmn20.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://www.example.com/demo">
              <process id="DEMO-extra" name="DEMO extra">
                <startEvent id="s"/>
                <callActivity id="c" calledElement="DEMO-nowhere"/>
              </process>
            </definitions>
        """.trimIndent())
        var code = -1
        val err = stderrOf { code = run(arrayOf(head.path, "--summary", "--stdout", "--baseline", base.path, "--fail-on", "missingRefs")) }
        assertEquals(err, 1, code)
        assertTrue(err, err.contains("--fail-on missingRefs: 1 new finding(s)") &&
            err.contains("processes/demo-extra.bpmn20.xml:5") && err.contains("`DEMO-nowhere`"))
        assertFalse("what the base had is not listed: $err", err.contains("courierProcess"))

        assertEquals("the fixture fails a plain gate on its own errors", 1, run(arrayOf(base.path, "--summary", "--stdout", "-q", "--fail-on", "error")))
        val same = stderrOf { code = run(arrayOf(base.path, "--summary", "--stdout", "--baseline", base.path, "--fail-on", "error")) }
        assertEquals("…and passes against itself", 0, code)
        assertTrue(same, same.contains("no new finding") && same.contains("in the baseline, not gating"))

        val out = tempDir()
        assertEquals(0, run(arrayOf(base.path, "--all", "-o", out.path, "-q")))
        val graph = File(out, "miniproject.graph.json").path
        assertEquals("the graph.json the base wrote is a baseline too", 1,
            run(arrayOf(head.path, "--summary", "--stdout", "-q", "--baseline", graph, "--fail-on", "missingRefs")))
        // Read before the run writes anything: regenerating the graph.json it compares against is the local loop.
        assertEquals(0, run(arrayOf(base.path, "--all", "-o", out.path, "-q", "--baseline", graph, "--fail-on", "any")))
        val info = stderrOf { code = run(arrayOf(head.path, "--summary", "--stdout", "--baseline", graph)) }
        assertEquals("without a gate it only says what is new", 0, code)
        assertTrue(info, Regex("--baseline: \\d+ new finding\\(s\\)").containsMatchIn(info) && info.contains("processes/demo-extra.bpmn20.xml:5"))

        File(out, "old.json").writeText("""{"_generated":{"atlas":"0.0.1"},"findings":[]}""")
        val old = stderrOf { run(arrayOf(base.path, "--summary", "--stdout", "--baseline", File(out, "old.json").path, "--fail-on", "missingRefs")) }
        assertTrue(old, old.contains("baseline written by Atlas 0.0.1"))

        File(out, "not-ours.json").writeText("""{"nodes":[]}""")
        for (bad in listOf(File(out, "nowhere").path, File(out, ".gitignore").path, File(out, "not-ours.json").path, "")) {
            val e = stderrOf { code = run(arrayOf(head.path, "--summary", "--stdout", "--baseline", bad, "--fail-on", "error")) }
            assertEquals("$bad: $e", 2, code)
            assertTrue(e, e.startsWith("error: argument --baseline"))
        }
    }

    /**
     * `--changes` says what a change did — here archive against archive, the base under another name as
     * `git show "$BASE:app.zip" > base.zip` writes it: the task and the dangling call the change added, the
     * field it renamed, and nothing about what both versions have.
     */
    @Test
    fun theChangesReportSaysWhatAChangeDid() {
        val out = tempDir()
        val base = zip(fixtureDir(), File(out, "base.zip"))
        val head = zip(projectV2(), File(tempDir(), "app.zip"))
        var code = -1
        val text = stdoutOf { code = run(arrayOf(head.path, "--changes", "--stdout", "-q", "--baseline", base.path)) }
        assertEquals(text, 0, code)
        assertTrue(text, text.startsWith("# What changed — `app`"))
        assertTrue(text, text.contains("`checkStock`") && text.contains("removed: `internalNote`") && text.contains("`external:DEMO-nowhere`"))
        assertFalse("an archive's own name does not make its unparseable file new: $text", text.contains("parseIssues"))
        assertTrue(stdoutOf { run(arrayOf(base.path, "--changes", "--stdout", "-q", "--baseline", base.path)) }
            .contains("No model, reference or finding changed"))

        // The gate of the same run fails on what the report calls new — and the report is written first.
        val report = File(out, "changes.md")
        assertEquals(1, run(arrayOf(head.path, "--changes", "-o", report.path, "-q", "--baseline", base.path, "--fail-on", "missingRefs")))
        assertTrue(report.readText().contains("**New (1)**"))

        // Without -o, into the project, as every single artifact.
        val project = projectV2()
        assertEquals(0, run(arrayOf(project.path, "--changes", "-q", "--baseline", fixtureDir().path)))
        assertTrue(File(project, "APP_OVERVIEW.changes.md").readText().contains("`form:stockForm`"))
    }

    /** `--all` writes the report beside the rest when it has a baseline, and leaves no older one behind when not. */
    @Test
    fun allWritesTheChangesReportWithABaseline() {
        val out = tempDir()
        val head = projectV2()
        assertEquals(0, run(arrayOf(head.path, "--all", "-o", out.path, "-q", "--baseline", fixtureDir().path)))
        val changes = File(out, "miniproject.changes.md")
        assertTrue(changes.readText().contains("`process:orderProcess`"))
        assertTrue("the output .gitignore keeps it out of git", ignored(ignorePatterns(out), changes.name))
        assertEquals(0, run(arrayOf(head.path, "--all", "-o", out.path, "-q")))
        assertFalse("a comparison this run did not make", changes.exists())
    }

    @Test
    fun theChangesReportNeedsABaselineAndTheWholeProject() {
        var code = -1
        val err = stderrOf { code = run(arrayOf(fixtureDir().path, "--changes", "--stdout")) }
        assertEquals(2, code)
        assertTrue(err, err.startsWith("error: argument --changes: needs --baseline"))
        val base = arrayOf("--baseline", fixtureDir().path, "-q")
        assertEquals("one node is not what changed", 2, run(arrayOf(fixtureDir().path, "--changes", "--slice", "orderProcess", *base)))
        assertEquals("a format of its own", 2, run(arrayOf(fixtureDir().path, "--changes", "--summary", *base)))
    }

    /**
     * `--junit` writes in every mode, for a red run as for a green one, and is red exactly where the exit code
     * is; without a gate its split is `defects`.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun theJunitReportAgreesWithTheExitCode() {
        val out = tempDir()
        val report = File(out, "reports/atlas.junit.xml")
        fun failures() = Regex("<testsuites [^>]*failures=\"(\\d+)\"").find(report.readText())!!.groupValues[1].toInt()
        val golden = (MiniJson.parse(goldenFile("miniproject.graph.json").readText()) as Map<String, Any?>)["findings"] as List<Map<String, Any?>>

        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "error", "--junit", report.path)))
        assertEquals(golden.count { it["severity"] == "error" }, failures())
        report.delete()
        assertEquals("without a gate the run stays green", 0, run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--junit", report.path)))
        assertEquals("…and the report splits on defects", golden.count { com.flowable.atlas.graph.CheckCatalog.gates(listOf("defects"), it) }, failures())

        val err = stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", File(out, "all").path, "--junit", report.path)) }
        assertTrue("listed with the artifacts: $err", err.contains("✓ ${report.path}"))
        assertEquals(2, run(arrayOf("--claude-template", "--junit", report.path, "-q")))
        assertEquals("a report that cannot be written is refused before the analysis", 2,
            run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--junit", out.path)))
    }

    /** `--annotate github` turns the same findings into workflow commands on stdout, which GitHub shows on the PR. */
    @Test
    fun annotationsAreGithubWorkflowCommands() {
        val out = tempDir()
        var code = -1
        val stdout = stdoutOf {
            code = run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "s.md").path, "-q", "--fail-on", "error", "--annotate", "github"))
        }
        assertEquals(1, code)
        val commands = stdout.lines().filter { it.isNotBlank() }
        assertTrue(stdout, commands.isNotEmpty() && commands.all { it.startsWith("::error ") })
        // Relative to the working directory — the checkout — not to the project Atlas was pointed at.
        val broken = File(".").absoluteFile.normalize().toPath()
            .relativize(File(fixtureDir(), "broken.form").absoluteFile.normalize().toPath()).toString().replace(File.separatorChar, '/')
        assertTrue(stdout, commands.any { it.contains("file=$broken,") && it.contains("title=Atlas parseIssues::") })
        assertEquals("no misuse is silent", 2, run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "s.md").path, "-q", "--fail-on", "error", "--annotate", "gitlab")))
        assertEquals("nothing to annotate without a gate", 2, run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "s.md").path, "-q", "--annotate", "github")))
        assertEquals("stdout cannot hold the artifact and the commands", 2,
            run(arrayOf(fixtureDir().path, "--summary", "--stdout", "-q", "--fail-on", "error", "--annotate", "github")))
    }

    /**
     * Run from inside a project as `.`, the files are named after the directory — and so are the siblings
     * they name: a CLAUDE.md pointing at `..playwright.md` points at nothing.
     */
    @Test
    fun aProjectGivenAsDotNamesItsSiblingsAfterTheDirectory() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(".", "--all", "-o", out.path, "-q")))
        val name = File(".").absoluteFile.normalize().name
        for (suffix in listOf("CLAUDE.md", "playwright.md", "playwright-map.md")) {
            val text = File(out, "$name.$suffix").readText()
            assertFalse("$suffix names a dot file", text.contains("..graph.json") || text.contains("..playwright"))
        }
        // …and the project is called by that name inside them too: the explorer of every `.` project was
        // called `.`, and its browser storage — accepted findings, tabs — was shared with every other one.
        val explorer = File(out, "$name.explorer.html").readText()
        assertTrue("the explorer names the directory", explorer.contains("\"project\":\"$name\""))
        assertFalse("not `.`", explorer.contains("\"project\":\".\""))
        assertTrue(File(out, "$name.summary.md").readText().startsWith("# Flowable project — `$name`"))
    }

    /**
     * The Playwright guide alone: a skill-shaped file with no graph beside it, so its lookups say how to make
     * one. Asked for with another output, it is a misuse like any other pair of formats.
     */
    @Test
    fun thePlaywrightGuideIsItsOwnArtifact() {
        val out = tempDir()
        val guide = File(out, "work.playwright.md")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--playwright", "-o", guide.path, "-q")))
        val text = guide.readText()
        assertTrue("it opens as a skill", text.startsWith("---\nname: flowable-work-ui\n"))
        assertTrue("alone, it says how to get the graph it queries", text.contains("--json -o miniproject.graph.json"))
        assertTrue("the case of the fixture is mapped, inline: one artifact, one file", text.contains("#### Case `reviewCase`"))
        assertEquals("nothing beside it", listOf("work.playwright.md"), out.listFiles()!!.map { it.name })
        assertEquals(2, run(arrayOf(fixtureDir().path, "--playwright", "--claude", "-q")))
    }

    /**
     * The ER diagram designer is an artifact of its own: `--erd` writes it alone, `--all` beside the explorer
     * it links into, and the explorer carries none of it. Asked for with another output, it is a misuse.
     */
    @Test
    fun theErPageIsItsOwnArtifact() {
        val out = tempDir()
        val erd = File(out, "model.erd.html")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--erd", "-o", erd.path, "-q")))
        assertTrue("the designer's page", erd.readText().contains("window.ATLAS_ERD="))
        assertTrue("alone, it links to no explorer", erd.readText().contains("\"explorer\":null"))
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", File(out, "all").path, "-q")))
        assertTrue("beside the explorer, it links into it",
            File(out, "all/miniproject.erd.html").readText().contains("\"explorer\":\"miniproject.explorer.html\""))
        assertTrue("the explorer carries none of it", !File(out, "all/miniproject.explorer.html").readText().contains("ATLAS_ERD"))
        assertEquals(2, run(arrayOf(fixtureDir().path, "--erd", "--html", "-q")))
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

    /**
     * What is wrong with the waiver file reaches the log: a `--waivers` path that is not there (still not
     * fatal — it is how a pipeline says "no waivers"), and a file in graph.json's shape, which waived nothing.
     */
    @Test
    fun aWaiverFileThatWaivesNothingSaysWhy() {
        val out = tempDir()
        var code = -1
        val missing = stderrOf {
            code = run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "report.md").path, "--fail-on", "error",
                "--waivers", File(out, "nowhere.json").path))
        }
        assertEquals("a missing file waives nothing, and fails nothing by itself", 1, code)
        assertTrue(missing, missing.contains("nowhere.json") && missing.contains("not found"))
        assertFalse("the default stays silent: most projects have no file",
            stderrOf { run(arrayOf(fixtureDir().path, "--summary", "-o", File(out, "report.md").path)) }.contains("not found"))

        File(out, "waivers.json").writeText("""{"version":1,"rules":[{"check":"unusedForms","node":"form:DEMO-NOPE","reason":"x"}]}""")
        val shape = stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", out.path)) }
        assertTrue(shape, shape.lines().any { it.contains("waiver") && it.contains("\"rules\"") })
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

    /**
     * A malformed XML model printed the parser's own `[Fatal Error] :2:3: …` to stderr even under `-q`, and
     * `-v` named the file without the line. Run on a fresh thread: the parser's default handler would capture
     * the stderr in place when its builder is created, so a builder an earlier test made would hide the noise.
     */
    @Test
    fun aMalformedModelIsQuietUnderQAndNamedWithItsLineUnderV() {
        val project = tempDir()
        File(project, "DEMO-P1.bpmn20.xml").writeText(
            "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"DEMO-P1\"><startEvent id=\"s\">\n</definitions>\n")
        val out = File(tempDir(), "s.md")
        fun onFreshThread(args: Array<String>): String = stderrOf {
            val t = Thread { run(args) }
            t.start(); t.join()
        }
        assertEquals("", onFreshThread(arrayOf(project.path, "--summary", "-o", out.path, "-q")))
        val verbose = onFreshThread(arrayOf(project.path, "--summary", "-o", out.path, "-v"))
        assertTrue(verbose, verbose.contains("parse DEMO-P1.bpmn20.xml:2: (bpmn)"))
        assertFalse(verbose, verbose.contains("[Fatal Error]"))
    }

    /**
     * `--claude` alone writes a drop-in `CLAUDE.md` into the project — and used to replace the team's own
     * without a word. It regenerates only a file Atlas wrote; a file the user names with -o is theirs to name.
     */
    @Test
    fun aHandWrittenClaudeMdIsNeverReplaced() {
        val project = projectCopy()
        val claudeMd = File(project, "CLAUDE.md").apply { writeText("# Team rules\nAlways run the tests.\n") }
        val before = claudeMd.readBytes()
        var code = -1
        val err = stderrOf { code = run(arrayOf(project.path, "--claude", "-q")) }
        assertEquals(2, code)
        assertTrue(err, err.startsWith("error: ") && err.contains("not written by Atlas"))
        assertTrue("the team's file is untouched", before.contentEquals(claudeMd.readBytes()))

        claudeMd.delete()
        assertEquals(0, run(arrayOf(project.path, "--claude", "-q")))
        assertTrue(claudeMd.readText().startsWith("# CLAUDE.md — "))
        assertEquals("Atlas's own file is regenerated", 0, run(arrayOf(project.path, "--claude", "-q")))

        claudeMd.writeText("# Team rules\n")
        assertEquals("a file named with -o is the user's call", 0, run(arrayOf(project.path, "--claude", "-o", claudeMd.path, "-q")))
        assertTrue(claudeMd.readText().startsWith("# CLAUDE.md — "))
    }

    /**
     * The .gitignore names Atlas's artifacts and nothing else, so a folder it lands in stays safe to commit.
     * It used to say `*`: written into a project folder, every model added there afterwards was invisible to git.
     */
    @Test
    fun theGitignoreHidesOnlyWhatAtlasWrote() {
        val out = tempDir()
        File(out, "x.bpmn").writeText("<definitions/>")
        File(out, "sub").mkdirs()
        File(out, "sub/notes.summary.md").writeText("mine")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))
        val patterns = ignorePatterns(out)
        assertTrue("no catch-all: $patterns", patterns.none { it == "*" || it.startsWith("!") })
        assertTrue("every pattern is an artifact suffix anchored to the folder: $patterns", patterns.all { it.startsWith("/*.") })
        assertFalse(ignored(patterns, "x.bpmn"))
        // The real thing, when git is here to ask.
        val git = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (git) {
            fun gitIn(vararg a: String) = ProcessBuilder(listOf("git", "-C", out.path) + a).redirectErrorStream(true).start().waitFor()
            assertEquals(0, gitIn("init", "-q"))
            assertEquals("a model of the user's is not ignored", 1, gitIn("check-ignore", "-q", "x.bpmn"))
            assertEquals("nor a file of theirs that only looks like an artifact", 1, gitIn("check-ignore", "-q", "sub/notes.summary.md"))
            assertEquals("nor the waivers", 1, gitIn("check-ignore", "-q", "waivers.json"))
            assertEquals("the analysis is", 0, gitIn("check-ignore", "-q", "miniproject.summary.md"))
            assertEquals("and the drawings", 0, gitIn("check-ignore", "-q", "miniproject.diagrams/orderForm.svg"))
        }
    }

    /** Without -o, `--all` writes into `atlas-output/<name>/` like the launcher — not into the working directory. */
    @Test
    fun allWithoutOutputWritesIntoItsOwnFolder() {
        val cwd = tempDir()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.flowable.atlas.cli.MainKt",
            fixtureDir().path, "--all", "-q").directory(cwd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assertEquals(log, 0, p.waitFor())
        assertEquals("the working directory holds only the folder", listOf("atlas-output"), cwd.list()!!.toList())
        assertTrue(File(cwd, "atlas-output/miniproject/miniproject.explorer.html").isFile)
        assertTrue(File(cwd, "atlas-output/miniproject/.gitignore").isFile)
    }

    /** A single model file is a project of one, as --help always said; anything else is not a project at all. */
    @Test
    fun aSingleModelFileIsAnalysed() {
        val summary = stdoutOf {
            assertEquals(0, run(arrayOf(File(fixtureDir(), "processes/order.bpmn").path, "--summary", "--stdout", "-q")))
        }
        assertTrue(summary, summary.contains("1 models") && summary.contains("orderProcess"))
        // With a layout, it is drawn — the bytes are read back from the file itself.
        val out = tempDir()
        assertEquals(0, run(arrayOf(File(repoRoot(), "core/src/test/resources/diagram/DEMO-onboarding.bpmn20.xml").path,
            "--all", "-o", out.path, "-q")))
        assertTrue(File(out, "DEMO-onboarding.bpmn20.diagrams/DEMO-onboarding.svg").isFile)
        var code = -1
        val err = stderrOf { code = run(arrayOf(File(repoRoot(), "README.md").path, "--summary", "--stdout")) }
        assertEquals(2, code)
        assertTrue(err, err.contains("not a project directory, a model file or a .zip/.bar archive"))
    }

    /** A run that finds nothing is the wrong directory, not a clean project — and must not pass a CI gate. */
    @Test
    fun aPathWithNothingToAnalyseIsAMisuse() {
        var code = -1
        val err = stderrOf { code = run(arrayOf(tempDir().path, "--summary", "--stdout", "--fail-on", "error", "-q")) }
        assertEquals(2, code)
        assertTrue(err, err.contains("no Flowable models, archives or Java sources"))
        val javaOnly = tempDir().also { File(it, "Foo.java").writeText("package com.example; public class Foo {}") }
        val warn = stderrOf { code = run(arrayOf(javaOnly.path, "--summary", "--stdout")) }
        assertEquals("Java alone is a project — one that deploys its models from elsewhere", 0, code)
        assertTrue(warn, warn.contains("no Flowable models found"))
    }

    /**
     * Without -o a single artifact lands in the project, so that is where its waivers.json is read — the
     * folder the explorer's advice sends a CLI user to. It used to be the working directory, whatever it was.
     */
    @Test
    fun aRunWithoutOutputReadsTheWaiversBesideTheFileItWrites() {
        val project = projectCopy()
        val scratch = tempDir()
        assertEquals(1, run(arrayOf(project.path, "--all", "-o", scratch.path, "-q", "--fail-on", "error")))
        waiveEvery(scratch) { it["severity"] == "error" }
        File(scratch, "waivers.json").copyTo(File(project, "waivers.json"))
        assertEquals("the project's waivers are read", 0, run(arrayOf(project.path, "--summary", "--fail-on", "error", "-q")))
        assertTrue(File(project, "APP_OVERVIEW.summary.md").isFile)
        assertEquals("stdout writes nowhere, so it reads the working directory", 1,
            run(arrayOf(project.path, "--summary", "--stdout", "--fail-on", "error", "-q")))
    }

    /**
     * An output that cannot be written is said in one line, before the analysis where it can be (exit 2), and
     * as a failed run where it cannot (exit 3) — never as a stack trace with exit 1, the code that means "a
     * finding matched, the report is written". Every case here fails for root as well as for a user.
     */
    @Test
    fun aRunThatCannotWriteIsAnErrorNotAFinding() {
        val dir = tempDir()
        val nested = File(dir, "new/sub/x.md")
        assertEquals("missing folders of a single file are created", 0, run(arrayOf(fixtureDir().path, "--summary", "-o", nested.path, "-q")))
        assertTrue(nested.isFile)

        val blocker = File(dir, "blocker").apply { writeText("a file") }
        for (args in listOf(arrayOf("--all", "-o", blocker.path), arrayOf("--summary", "-o", File(blocker, "x.md").path),
            arrayOf("--json", "-o", dir.path), arrayOf("--summary", "--output="))) {
            var code = -1
            val err = stderrOf { code = run(arrayOf(fixtureDir().path, *args, "-q", "--fail-on", "error")) }
            assertEquals(args.joinToString(" "), 2, code)
            assertTrue(err, err.startsWith("error: ") && !err.contains("Exception") && err.trim().lines().size == 1)
        }

        // Past the checks, a write that fails is a failed run: here an artifact's name is taken by a folder.
        val out = tempDir().also { File(it, "miniproject.summary.md").mkdirs() }
        var code = -1
        val err = stderrOf { code = run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q", "--fail-on", "error")) }
        assertEquals(3, code)
        assertTrue(err, err.startsWith("error: could not write the output") && err.trim().lines().size == 1)
    }

    /** Both gates are reported before either decides: a stale waiver used to hide the --fail-on tally. */
    @Test
    fun staleWaiversFailTheRunOnlyWithTheFlag() {
        val out = tempDir()
        File(out, "waivers.json").writeText("""{"version":1,"waivers":[{"check":"unusedForms","node":"form:DEMO-NOPE","reason":"x"}]}""")
        val report = File(out, "report.md").path
        assertEquals(0, run(arrayOf(fixtureDir().path, "--summary", "-o", report, "-q")))
        assertEquals(1, run(arrayOf(fixtureDir().path, "--summary", "-o", report, "-q", "--fail-on-stale-waivers")))
        val err = stderrOf { run(arrayOf(fixtureDir().path, "--summary", "-o", report, "--fail-on-stale-waivers", "--fail-on", "error")) }
        assertTrue(err, err.contains("matched nothing: unusedForms on form:DEMO-NOPE"))
        assertTrue(err, err.contains("--fail-on-stale-waivers: 1 stale waiver(s)"))
        assertTrue(err, err.contains("--fail-on error:"))
    }

    /** What each flag silently did instead of saying no — every one of these is a misuse now. */
    @Test
    fun flagMisuseIsAnErrorNotAPreference() {
        val out = tempDir()
        val fixture = fixtureDir().path
        assertEquals(2, run(arrayOf(fixture, "--all", "--stdout", "-o", out.path, "-q")))
        assertTrue("--all --stdout wrote nothing", out.list()!!.isEmpty())
        // An unset CI variable must not switch the gate off.
        assertEquals(2, run(arrayOf(fixture, "--summary", "--stdout", "-q", "--fail-on", "")))
        assertEquals(2, run(arrayOf(fixture, "--summary", "--stdout", "-q", "--fail-on", ",")))
        // An option's value is never the next option: this used to write a file called `--stdout`.
        assertEquals(2, run(arrayOf(fixture, "--summary", "-o", "--stdout", "-q")))
        assertEquals(2, run(arrayOf(fixture, "--all=foo", "-q")))
        assertEquals(2, run(arrayOf(fixture, "--summary", "--stdout=false", "-q")))
        // `--slice "$NODE"` with the variable unset names no node.
        assertEquals(2, run(arrayOf(fixture, "--slice", "", "--stdout", "-q")))
        assertEquals(2, run(arrayOf(fixture, "--slice= ", "--stdout", "-q")))
        // The forms that are right stay right.
        assertEquals(0, run(arrayOf(fixture, "--summary", "-o${File(out, "a.md").path}", "-q")))
        assertEquals(0, run(arrayOf(fixture, "--summary", "--output=${File(out, "b.md").path}", "-q")))
    }

    /** Re-running into the same folder leaves no drawing of a model that is gone; files of the user's stay. */
    @Test
    fun allRemovesTheDrawingsOfModelsThatAreGone() {
        val out = tempDir()
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))
        val drawings = File(out, "miniproject.diagrams")
        File(drawings, "gone.svg").writeText("<svg/>")
        File(drawings, "notes.txt").writeText("mine")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-q")))
        assertFalse(File(drawings, "gone.svg").exists())
        assertTrue(File(drawings, "orderForm.svg").isFile)
        assertTrue(File(drawings, "notes.txt").isFile)
    }

    /**
     * The status line is the thing to read, so it ends the run: it used to come first and scroll away under
     * one line per drawing — 250 on a large project. The drawings are one line, unless -v asks for each.
     */
    @Test
    fun theStatusLineEndsTheRun() {
        val out = tempDir()
        val lines = stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", out.path)) }.lines().filter { it.isNotBlank() }
        assertTrue(lines.joinToString("\n"), lines.last().startsWith("Flowable Atlas — miniproject:") && lines.last().contains("defects"))
        val drawings = lines.filter { ".diagrams" in it }
        assertEquals(lines.joinToString("\n"), 1, drawings.size)
        assertTrue(drawings.single(), drawings.single().endsWith("(4 SVGs)"))
        val verbose = stderrOf { run(arrayOf(fixtureDir().path, "--all", "-o", out.path, "-v")) }.lines()
        val status = verbose.indexOfFirst { it.startsWith("Flowable Atlas — ") }
        assertEquals("-v lists each drawing", 4, verbose.count { ".diagrams/" in it && it.endsWith(".svg") })
        assertTrue("and still ends the list with the status line", status > verbose.indexOfLast { it.trimStart().startsWith("✓") })
    }

    /** `--open` on Windows: `start` is a cmd.exe builtin no PATH probe finds, so it never opened anything. */
    @Test
    fun theOpenerFitsThePlatform() {
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", "C:\\a b\\x.html"),
            openerCommand("Windows 11", "C:\\a b\\x.html") { false })
        assertEquals(listOf("open", "/x.html"), openerCommand("Mac OS X", "/x.html") { it == "open" })
        assertEquals(listOf("xdg-open", "/x.html"), openerCommand("Linux", "/x.html") { it == "xdg-open" })
        assertEquals(null, openerCommand("Linux", "/x.html") { false })
    }

    // ---- flags no test exercised: each one decides an artifact or an exit code ----

    @Test
    fun theAllowlistIsCommaSeparatedAndTrimmed() {
        val out = tempDir()
        @Suppress("UNCHECKED_CAST")
        fun bogus(vararg extra: String): Boolean {
            val g = File(out, "g.json")
            assertEquals(0, run(arrayOf(fixtureDir().path, "--json", "-o", g.path, "-q", *extra)))
            val findings = (MiniJson.parse(g.readText()) as Map<String, Any?>)["findings"] as List<Map<String, Any?>>
            return findings.any { it["message"] == "Unknown function 'vars:bogus'" }
        }
        assertTrue("the fixture calls an unknown function", bogus())
        assertFalse(bogus("--expr-allowlist", " vars:bogus , other"))
        assertFalse(bogus("--expr-allowlist=vars:bogus"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun customFunctionsComeFromTheNamedPathUnlessTurnedOff() {
        val out = tempDir()
        val g = File(out, "g.json")
        val source = File(repoRoot(), "site/flowable-demo/frontend/customisation.ts").path
        assertEquals(0, run(arrayOf(fixtureDir().path, "--json", "-o", g.path, "-q", "--custom-functions", source)))
        val cf = (MiniJson.parse(g.readText()) as Map<String, Any?>)["customFunctions"] as? Map<String, Any?>
        assertTrue("$cf", (cf?.get("namespaces") as? Map<String, Any?>)?.containsKey("demofns") == true)
        assertEquals(0, run(arrayOf(fixtureDir().path, "--json", "-o", g.path, "-q", "--custom-functions", source, "--no-custom-functions")))
        assertEquals("turned off, there is nothing to report", null, (MiniJson.parse(g.readText()) as Map<String, Any?>)["customFunctions"])
    }

    @Test
    fun theWaiverAuthorReachesThePage() {
        val page = File(tempDir(), "x.html")
        assertEquals(0, run(arrayOf(fixtureDir().path, "--html", "-o", page.path, "-q", "--waiver-author", "DEMO-team")))
        assertTrue(page.readText().contains("\"waiverAuthor\":\"DEMO-team\""))
    }

    @Test
    fun theClaudeTemplateNeedsNoPath() {
        val t = File(tempDir(), "t.md")
        assertEquals(0, run(arrayOf("--claude-template", "-o", t.path, "-q")))
        assertEquals(File(repoRoot(), "CLAUDE.template.md").readText(), t.readText())
    }

    @Test
    fun doubleDashEndsOptions() {
        assertEquals(0, run(arrayOf("--summary", "--stdout", "-q", "--", fixtureDir().path)))
        assertEquals("after --, a flag is a second path", 2, run(arrayOf("--summary", "--stdout", "-q", "--", fixtureDir().path, "--all")))
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

    private fun stdoutOf(block: () -> Unit): String {
        val buf = java.io.ByteArrayOutputStream()
        val prev = System.out
        System.setOut(java.io.PrintStream(buf, true, "UTF-8"))
        try { block() } finally { System.setOut(prev) }
        return buf.toString("UTF-8")
    }

    // ---- helpers ----

    // A rule, not Files.createTempDirectory: each `--all` run writes about a megabyte, and directories
    // nobody deleted piled up in $TMPDIR at a dozen per build.
    @get:Rule val tmp = TemporaryFolder()

    private fun tempDir(): File = tmp.newFolder()

    /** The fixture copied to a folder of its own, for a test that writes into the project. */
    private fun projectCopy(): File = File(tempDir(), "miniproject").also { fixtureDir().copyRecursively(it) }

    /** The fixture's second version: `miniproject-v2` holds the files its change touches, laid over a copy. */
    private fun projectV2(): File = projectCopy().also {
        File(repoRoot(), "core/src/test/resources/miniproject-v2").copyRecursively(it, overwrite = true)
    }

    /** [dir]'s files as the archive [zip], as Design exports an app. */
    private fun zip(dir: File, zip: File): File {
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }

    /** The pattern lines of the `.gitignore` in [dir]. */
    private fun ignorePatterns(dir: File): List<String> =
        File(dir, ".gitignore").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    // Whether a top-level [name] in that folder matches one of Atlas's patterns: a suffix anchored to the folder.
    private fun ignored(patterns: List<String>, name: String): Boolean =
        patterns.any { p -> p.startsWith("/*") && name.endsWith(p.removePrefix("/*").removeSuffix("/")) }

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
}
