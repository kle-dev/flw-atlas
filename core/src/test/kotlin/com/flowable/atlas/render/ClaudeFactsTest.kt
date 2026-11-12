package com.flowable.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The auto-discovered facts in `CLAUDE.md` §4, and the catalog cheatsheet in §6.
 *
 * Covers what the `miniproject` golden cannot: the fixture is not a Maven or Gradle build, and it has
 * exactly one model per type, so neither version detection nor the naming-convention line has any
 * fixture coverage — the two places most likely to state something false about a real project.
 */
class ClaudeFactsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun render(
        process: List<Map<String, Any?>> = emptyList(),
        archived: Boolean = false,
        layout: ClaudeRenderer.Layout = ClaudeRenderer.Layout(),
    ): String {
        val result = mapOf(
            "stats" to mapOf("models" to process.size, "java" to 0, "archives" to (if (archived) 1 else 0)),
            "graph" to mapOf(
                "nodes" to process.map {
                    val file = if (archived) "apps/demo.zip!processes/${it["key"]}.bpmn" else "processes/${it["key"]}.bpmn"
                    mapOf("id" to "process:${it["key"]}", "type" to "process", "key" to it["key"],
                          "label" to it["key"], "file" to file, "data" to emptyMap<String, Any?>())
                },
                "edges" to emptyList<Any?>(),
            ),
            "processes" to process,
        )
        return ClaudeRenderer.render(result, tmp.root, layout)
    }

    private fun proc(key: String): Map<String, Any?> = mapOf("key" to key, "name" to key)

    private val name: String get() = tmp.root.name

    @Test
    fun theFlowableVersionIsFoundInAGradleProperty() {
        tmp.newFile("gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\nflowableVersion=8.1.3\n")
        tmp.newFile("build.gradle.kts").writeText("plugins { java }\n")
        assertTrue(render().contains("**Flowable version:** 8.1.3"))
    }

    @Test
    fun theFlowableVersionIsFoundOnAGradleDependencyCoordinate() {
        tmp.newFile("build.gradle").writeText(
            "dependencies {\n  implementation 'com.flowable:flowable-platform-spring-boot-starter:7.4.0'\n}\n"
        )
        assertTrue(render().contains("**Flowable version:** 7.4.0"))
    }

    /**
     * The property the platform's own project template declares. Every Work project measured (3.11 to
     * 2025.1) uses it or `flowable.version` with a 3.x value, and neither used to be recognised.
     */
    @Test
    fun aPlatformVersionIsFoundInTheMavenPropertyTheTemplateDeclares() {
        tmp.newFile("pom.xml").writeText(
            "<project><properties><java.version>21</java.version>" +
                "<com.flowable.platform.version>2025.1.01.RC1</com.flowable.platform.version></properties>" +
                "<parent><groupId>com.flowable</groupId><artifactId>flowable-platform-parent</artifactId>" +
                "<version>\${com.flowable.platform.version}</version></parent></project>"
        )
        assertTrue(render().contains("**Flowable version:** 2025.1.01.RC1"))
    }

    @Test
    fun aThreeDotXPlatformVersionIsAccepted() {
        tmp.newFile("pom.xml").writeText("<project><properties><flowable.version>3.11.3</flowable.version></properties></project>")
        assertTrue(render().contains("**Flowable version:** 3.11.3"))
    }

    @Test
    fun thePlatformVersionWinsOverAnEngineVersion() {
        tmp.newFile("pom.xml").writeText(
            "<project><properties><flowable-engine.version>7.1.0</flowable-engine.version>" +
                "<com.flowable.platform.version>2026.1.4</com.flowable.platform.version></properties></project>"
        )
        assertTrue(render().contains("**Flowable version:** 2026.1.4"))
    }

    /** The browser guide is a separate file; CLAUDE.md says when to read it, and only when it was written. */
    @Test
    fun thePlaywrightGuideIsPointedToOnlyWhenItIsWritten() {
        val with = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true, playwright = true))
        assertTrue(with, with.contains("**Asked to do something in Flowable Work itself**"))
        assertTrue(with, with.contains("`$name.playwright.md`"))
        val without = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true))
        assertFalse(without, without.contains("playwright.md"))
    }

    /** The platform publishes under sub-groups; `com.flowable:` alone never matched a Work project's coordinate. */
    @Test
    fun aPlatformVersionIsFoundOnASubGroupCoordinate() {
        tmp.newFile("build.gradle.kts").writeText(
            "dependencies {\n  implementation(\"com.flowable.platform:flowable-spring-boot-starter-platform:2025.2.01\")\n}\n"
        )
        assertTrue(render().contains("**Flowable version:** 2025.2.01"))
    }

    /** Version catalog keys carry no `version` suffix — `flowable-platform = "…"` is the usual spelling. */
    @Test
    fun aPlatformVersionIsFoundInAVersionCatalog() {
        tmp.newFolder("gradle")
        tmp.newFile("gradle/libs.versions.toml").writeText("[versions]\nkotlin = \"2.0.20\"\nflowable-platform = \"2025.2.01\"\n")
        assertTrue(render().contains("**Flowable version:** 2025.2.01"))
    }

    @Test
    fun aGradlePlatformPropertyWinsOverAnEngineProperty() {
        tmp.newFile("gradle.properties").writeText("flowableVersion=7.1.0\nflowablePlatformVersion=2025.1.03\n")
        assertTrue(render().contains("**Flowable version:** 2025.1.03"))
    }

    /**
     * The pointer is phrased as the requests it is for, and names the map file when one is written —
     * an agent that only finds the index in the guide has to know where the details went.
     */
    @Test
    fun thePlaywrightPointerNamesTheRequestsAndTheMapFileWhenOneIsWritten() {
        val with = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true, playwright = true, playwrightMap = true))
        assertTrue(with, with.contains("find the open tasks of a user or group"))
        assertTrue(with, with.contains("explain why a button is missing or disabled"))
        assertTrue(with, with.contains("read **`$name.playwright.md`** (its §9 index points into **`$name.playwright-map.md`**)"))
        val single = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true, playwright = true))
        assertTrue(single, single.contains("read **`$name.playwright.md`** before the first click"))
        assertFalse(single, single.contains("playwright-map.md"))
    }

    /** Run from inside the project as `.`, the artifacts are named after the directory — and so are the links to them. */
    @Test
    fun theArtifactNameOfDotIsTheDirectorysName() {
        val dotted = java.io.File(tmp.root, ".")
        assertTrue(Fmt.artifactName(dotted) == name)
        val result = mapOf(
            "stats" to mapOf("models" to 0, "java" to 0, "archives" to 0),
            "graph" to mapOf("nodes" to emptyList<Any?>(), "edges" to emptyList<Any?>()),
        )
        val out = ClaudeRenderer.render(result, dotted, ClaudeRenderer.Layout(tmp.root, siblings = true, playwright = true))
        assertTrue(out, out.contains("`$name.playwright.md`"))
        assertFalse(out, out.contains("..playwright.md") || out.contains("..graph.json"))
    }

    @Test
    fun anImplausibleVersionIsNotReportedAsTheFlowableVersion() {
        // A Gradle plugin version or a Java release must not be mistaken for the engine version — and
        // an undetected version is no line at all, not a line about the absence of a fact.
        tmp.newFile("gradle.properties").writeText("javaVersion=21\nkotlinVersion=2.0.20\n")
        tmp.newFile("build.gradle").writeText("plugins { id 'java' }\n")
        val out = render()
        assertFalse(out, out.contains("**Flowable version:**"))
        assertFalse(out, out.contains("not auto-detected"))
    }

    @Test
    fun theInventoryAndTheGenericRunLineAreNotRepeatedHere() {
        // Counts, apps and the model inventory are the summary's, which §0 has the agent read whole; a
        // repository overview is the one kind of context-file content measured not to help.
        val out = render(listOf(proc("orderProcess")))
        for (gone in listOf("**Scale:**", "**Apps:**", "**Models:**", "**Java by role:**",
                            "**Startable entry points:**", "**Run & verify:**", "Common Flowable pitfalls")) {
            assertFalse("still emits $gone", out.contains(gone))
        }
        // The house-rules reminder is a real HTML comment (stripped before Claude sees it), not quoted text.
        assertTrue(out.contains("\n<!-- Add house rules"))
        assertFalse(out.contains("`<!-- Add house rules"))
    }

    @Test
    fun packedModelsAreDeclaredOffLimits() {
        val out = render(listOf(proc("orderProcess")), archived = true)
        assertTrue(out, out.contains("**Where to write, what not to touch:**"))
        assertTrue(out, out.contains("Design exports packed in `apps/demo.zip`"))
        assertTrue(out, out.contains("never edit the exports"))
        assertFalse(out, out.contains("check `git log`"))
    }

    @Test
    fun unpackedModelsSendTheAgentToGitLogBeforeItDecides() {
        val out = render(listOf(proc("orderProcess")))
        assertTrue(out, out.contains("**Where to write, what not to touch:** The models are unpacked files under `processes/`"))
        assertTrue(out, out.contains("check `git log`"))
        assertFalse(out, out.contains("Design exports packed"))
    }

    @Test
    fun siblingsAreNamedFromTheProjectRootWhenAtlasWritesIntoTheProject() {
        val outDir = tmp.newFolder("atlas-output", "x")
        val out = render(layout = ClaudeRenderer.Layout(outDir, siblings = true))
        assertTrue(out, out.contains("Read **`atlas-output/x/$name.summary.md`**"))
        assertTrue(out, out.contains("' atlas-output/x/$name.graph.json  # who references it"))
        assertTrue(out, out.contains("`@atlas-output/x/$name.CLAUDE.md`"))
        assertFalse(out, out.contains("The Atlas files live in"))
    }

    @Test
    fun siblingsOutsideTheProjectAreLocatedByAbsolutePath() {
        // The launcher's default output directory is next to the Atlas checkout, not in the project.
        val elsewhere = TemporaryFolder().apply { create() }
        try {
            val out = render(layout = ClaudeRenderer.Layout(elsewhere.root, siblings = true))
            assertTrue(out, out.contains("Read **`$name.summary.md`**"))
            // The path is shown with `~` for the home directory, so only its tail is asserted here.
            assertTrue(out, out.contains("The Atlas files live in `"))
            assertTrue(out, out.contains("/${elsewhere.root.name}` (as generated)"))
            assertFalse(out, out.contains("import this file"))
        } finally {
            elsewhere.delete()
        }
    }

    @Test
    fun withoutSiblingsTheFirstStepIsToGenerateThem() {
        // `--claude` alone writes only CLAUDE.md; the file must not send the agent to read a summary
        // that was never written.
        val out = render(layout = ClaudeRenderer.Layout(siblings = false))
        assertTrue(out, out.contains("1. Generate the Atlas artifacts first: `java -jar <atlas-cli>.jar <project-dir> --all -o <out-dir>`"))
        assertTrue(out, out.contains("2. Read **`$name.summary.md`**"))
        assertFalse(out, out.contains("1. Read **"))
        assertFalse(out, out.contains("copy it to the repository root"))
    }

    @Test
    fun theSliceRecipeIsTheFormThatActuallyRuns() {
        // `./atlas <project> --slice …` fails: the launcher adds `--all`, which `--slice` refuses.
        val out = render()
        assertTrue(out, out.contains("`java -jar <atlas-cli>.jar <project-dir> --slice <type>:<key> --stdout`"))
        assertFalse(out, out.contains("`atlas <project-dir> --slice"))
    }

    /**
     * The slice takes any node id, and the file says so. With `process:<key>` as its only example, agents
     * asked about a form, a case or a class had to guess that it does, and one asked about a variable read
     * the graph instead.
     */
    @Test
    fun theSliceIsOfferedForEveryKindOfNodeNotOnlyAProcess() {
        val step = render().lines().single { it.contains("--slice <type>:<key>") }
        for (id in listOf("`process:<key>`", "`case:<key>`", "`form:<key>`", "`variable:<name>`", "`java:<fully.qualified.Class>`")) {
            assertTrue("$id in: $step", step.contains(id))
        }
        val graph = render().lines().first { it.contains("stands for any node id") }
        assertTrue(graph, graph.contains("`.forms[]`") && graph.contains("`| keys`"))
    }

    /**
     * A large case's slice is tens of kilobytes, and an agent asked about one plan item read five of its
     * lines. A line of a model's contents is complete in itself, so the step says how to take one.
     */
    @Test
    fun theSliceStepSaysHowToTakeOneElementOutOfIt() {
        val step = render().lines().single { it.contains("--slice <type>:<key>") }
        assertTrue(step, step.contains("one line per step, plan item or field") && step.contains("`| grep -i '<its name>'`"))
    }

    /** An agent that edits a model is told how to see that it broke nothing: the run against the graph beside it. */
    @Test
    fun anAgentIsToldHowToCheckItsModelEdit() {
        val beside = render(layout = ClaudeRenderer.Layout(outDir = java.io.File(tmp.root, "atlas-output")))
        assertTrue(beside, beside.contains("--summary --stdout --baseline atlas-output/$name.graph.json --fail-on defects > /dev/null`"))
        val alone = render(layout = ClaudeRenderer.Layout(siblings = false))
        assertTrue("alone, the graph is the one step 1 generates", alone.contains("--baseline <out-dir>/$name.graph.json"))
        assertFalse("the generic primer knows no project's graph", ClaudeRenderer.renderGeneric().contains("--baseline"))
        val ide = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true, cli = false))
        assertFalse("the IDE wrote it: there is no jar to run the check with", ide.contains("--baseline"))
    }

    /**
     * Written by the IDE plugin, the file sends nobody to a CLI jar the user may not have or to a slice
     * the plugin does not offer: one model is read in the overview, and the IDE's menu regenerates.
     */
    @Test
    fun aFileTheIdeWroteNamesNoCliJarAndSendsOneModelToTheOverview() {
        val out = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = true, cli = false))
        assertFalse(out, out.contains("<atlas-cli>.jar") || out.contains("--slice") || out.contains("./atlas"))
        assertTrue(out, out.contains("For one model, read its entry in **`$name.overview.md`**"))
        assertTrue(out, out.contains("regenerate with *Tools → Flowable Atlas → Generate → Atlas Explorer…* in the IDE"))
        val unticked = render(layout = ClaudeRenderer.Layout(tmp.root, siblings = false, cli = false))
        assertTrue(unticked, unticked.contains("1. Generate the Atlas artifacts first: in the IDE, " +
            "*Tools → Flowable Atlas → Generate → Atlas Explorer…*"))
        assertFalse(unticked, unticked.contains("java -jar") || unticked.contains("--claude"))
    }

    @Test
    fun aSingleModelIsNotPresentedAsANamingConvention() {
        // One process cannot establish a convention: the line used to read
        // "process `orderProcess` (e.g. `orderProcess`)" — the same name twice.
        val out = render(listOf(proc("orderProcess")))
        assertFalse(out, out.contains("**Key conventions:**"))
    }

    @Test
    fun repeatedDigitPatternsDoBecomeAConvention() {
        val out = render(listOf(proc("DEMO-P001"), proc("DEMO-P002"), proc("DEMO-P003")))
        assertTrue(out, out.contains("**Key conventions:** process `DEMO-P#` (e.g. `DEMO-P00"))
    }

    /** Processes given as `key to file`, for the facts that depend on where a model lives. */
    private fun renderModels(vararg models: Pair<String, String>): String {
        val result = mapOf(
            "stats" to mapOf("models" to models.size, "java" to 0, "archives" to 0),
            "graph" to mapOf(
                "nodes" to models.map { (key, file) ->
                    mapOf("id" to "process:$key", "type" to "process", "key" to key, "label" to key, "file" to file,
                        "data" to emptyMap<String, Any?>())
                },
                "edges" to emptyList<Any?>(),
            ),
        )
        return ClaudeRenderer.render(result, tmp.root)
    }

    /** Each app prefixes its own keys: the convention is the shape after the prefix, and the prefixes are named. */
    @Test
    fun perAppKeyPrefixesAreStatedAsOnePatternWithThePrefixesSeen() {
        val out = renderModels("A-P1" to "a/p1.bpmn", "A-P2" to "a/p2.bpmn", "B-P1" to "b/p1.bpmn", "C-P4" to "c/p4.bpmn")
        val line = out.lines().single { it.startsWith("- **Key conventions:**") }
        assertTrue(line, line.contains("process `<APP>-P#` (e.g. `A-P1`)"))
        assertTrue(line, line.contains("`A`, `B`, `C`"))
        assertFalse("one app's prefix is not the project's: $line", line.contains("`A-P#`"))
    }

    /** One stray prefix among a project's own is a slip, not a second app's convention to copy. */
    @Test
    fun aStrayPrefixDoesNotTurnTheProjectsOwnIntoAPerAppPattern() {
        val keys = (1..6).map { "ORD-P$it" to "a/p$it.bpmn" } + ("ORX-P1" to "a/x.bpmn")
        val line = renderModels(*keys.toTypedArray()).lines().single { it.startsWith("- **Key conventions:**") }
        assertTrue(line, line.contains("process `ORD-P#` (e.g. `ORD-P1`)"))
        assertFalse(line, line.contains("<APP>"))
    }

    /** Two keys of six agreeing is a coincidence, not a convention. */
    @Test
    fun aPatternFewKeysFollowIsNotAConvention() {
        val out = renderModels("xSub1" to "p/1.bpmn", "xSub2" to "p/2.bpmn", "alpha" to "p/a.bpmn",
            "beta" to "p/b.bpmn", "gamma" to "p/c.bpmn", "delta" to "p/d.bpmn")
        assertFalse(out, out.contains("**Key conventions:**"))
    }

    @Test
    fun aTestFixtureIsNotWhereTheAgentWrites() {
        val out = renderModels("orderProcess" to "models/q.bpmn", "fixtureProcess" to "src/test/resources/models/p.bpmn")
        val line = out.lines().single { it.startsWith("- **Where to write, what not to touch:**") }
        assertTrue(line, line.contains("unpacked files under `models/`"))
        assertFalse(line, line.contains("src/test"))
    }

    @Test
    fun manyArchivesInOneFolderAreNamedByTheirFolderAndCount() {
        val out = renderModels(*(1..5).map { "p$it" to "apps/app$it.zip!p$it.bpmn" }.toTypedArray())
        assertTrue(out, out.contains("Design exports packed in 5 archives under `apps/`"))
        val scattered = renderModels(*(1..5).map { "p$it" to "m$it/app.zip!p$it.bpmn" }.toTypedArray())
        assertTrue(scattered, scattered.contains("`m1/app.zip`, `m2/app.zip`, `m3/app.zip` … (+2 more)"))
    }

    /** The backend to start is the module that deploys the models, not the first `*-app` alphabetically. */
    @Test
    fun theRunLineNamesTheAppModuleThatHoldsTheModels() {
        tmp.newFile("pom.xml").writeText("<project/>")
        // Both boot, as Flowable Control and Work each do: only the models tell them apart.
        for (module in listOf("a-control-app", "b-engage-app")) {
            tmp.newFolder(module, "src", "main", "java", "com", "example")
            tmp.newFile("$module/src/main/java/com/example/DemoApplication.java")
                .writeText("package com.example;\n\n@SpringBootApplication\npublic class DemoApplication {}\n")
        }
        val out = renderModels("demoProcess" to "b-engage-app/src/main/resources/apps/demo.zip!p.bpmn")
        assertTrue(out, out.contains("Spring Boot app in `b-engage-app/`"))
    }

    /** A library module can hold the models the app bundles; it starts nothing, so it is not the app to run. */
    @Test
    fun theModuleThatBootsWinsOverALibraryHoldingTheModels() {
        tmp.newFolder("x-app", "src", "main", "java", "com", "example")
        tmp.newFile("x-app/src/main/java/com/example/DemoApplication.java")
            .writeText("package com.example;\n\n@SpringBootApplication\npublic class DemoApplication {}\n")
        tmp.newFolder("x-workflow")
        val out = renderModels("demoProcess" to "x-workflow/src/main/resources/apps/demo.zip!p.bpmn")
        assertTrue(out, out.contains("Spring Boot app in `x-app/`"))
    }

    @Test
    fun withoutModelsInAnyModuleTheAdminAppIsNotTheOneToRun() {
        tmp.newFolder("x-control-app")
        tmp.newFolder("x-work-app")
        val out = renderModels("demoProcess" to "models/p.bpmn")
        assertTrue(out, out.contains("Spring Boot app in `x-work-app/`"))
    }

    private fun finding(check: String, severity: String, message: String): Map<String, Any?> = mapOf(
        "check" to check, "kind" to com.flowable.atlas.graph.CheckCatalog.kind(check), "severity" to severity,
        "node" to "process:p", "label" to "p", "message" to message,
    )

    private fun renderFindings(findings: List<Map<String, Any?>>): String {
        val open = findings.groupingBy { it["check"] as String }.eachCount()
        val kinds = findings.groupingBy { it["kind"] }.eachCount()
        val result = mapOf(
            "stats" to mapOf("models" to 0, "java" to 0, "archives" to 0,
                "defects" to (kinds["defect"] ?: 0), "advice" to (kinds["advice"] ?: 0)),
            "graph" to mapOf("nodes" to emptyList<Any?>(), "edges" to emptyList<Any?>()),
            "findings" to findings,
            "checks" to open + ("open" to findings.size),
        )
        return ClaudeRenderer.render(result, tmp.root)
    }

    /**
     * The headline counts defects by kind, so the list under it does too: a project whose only defect is
     * a warning — a gateway the engine cannot leave — used to announce "1 defect" and name nothing.
     */
    @Test
    fun aDefectThatOnlyWarnsIsNamedUnderTheKnownIssues() {
        val out = renderFindings(listOf(
            finding("gatewayNoDefault", "warning", "`gw` has 2 conditional outgoing flows and no default"),
            finding("unusedForms", "warning", "form `lonely` is never used"),
        ))
        val block = out.substringAfter("**Known issues in this project (1 defect · 1 advice)").substringBefore("- Full list")
        assertTrue(out, block.contains("- · `gw` has 2 conditional outgoing flows and no default — `p`"))
        assertFalse("advice is not a known issue: $block", block.contains("never used"))
    }

    /** Three lines name three problems: the first of each defect check before the second of any. */
    @Test
    fun theKnownIssuesTakeOneDefectPerCheckErrorsFirst() {
        val out = renderFindings(listOf(
            finding("invalidExpr", "error", "bad expression one"),
            finding("invalidExpr", "error", "bad expression two"),
            finding("invalidExpr", "error", "bad expression three"),
            finding("gatewayNoDefault", "warning", "a gateway with no way out"),
            finding("missingRefs", "error", "a key nothing answers"),
        ))
        val items = out.substringAfter("**Known issues in this project").substringBefore("- Full list")
            .lines().filter { it.startsWith("- ") }
        assertEquals(items.toString(), 3, items.size)
        assertTrue(items.toString(), items[0].startsWith("- ⚠ bad expression one"))
        assertTrue(items.toString(), items[1].startsWith("- ⚠ a key nothing answers"))
        assertTrue(items.toString(), items[2].startsWith("- · a gateway with no way out"))
    }

    @Test
    fun theBrokenRecipeSelectsOpenDefectsByKind() {
        val out = render()
        assertTrue(out, out.contains("jq '.findings[] | select(.kind==\"defect\" and (.waived|not))'"))
        assertFalse(out, out.contains("select(.severity==\"error\")"))
    }

    @Test
    fun theCheatsheetNamesTheRealFlowableNamespacesAndBindings() {
        val out = render()
        // The whole point: an agent is told what exists instead of guessing.
        assertTrue(out.contains("## 6. Expressions & scripts — what you may call"))
        for (needle in listOf("`variables:`", "`date:`", "`json:`", "`sequence:`", "`flw.`",
                             "Script task (BPMN)", "execution", "platform beans")) {
            assertTrue("cheatsheet is missing $needle", out.contains(needle))
        }
        // Plural families are folded rather than listed twice.
        assertTrue(out.contains("addCandidateGroup(s)"))
        assertFalse(out.contains("addCandidateGroup, addCandidateGroups"))
    }

    @Test
    fun theCheatsheetIsCompleteBecauseItCallsEverythingElseAHallucination() {
        // A list cut at N with "(+25 more)" under that sentence declares 25 real functions hallucinations.
        val out = render()
        val sheet = out.substringAfter("## 6. Expressions & scripts")
        assertFalse(sheet, sheet.contains(" … (+"))
        val flw = sheet.lines().first { it.startsWith("- `flw.` — ") }.removePrefix("- `flw.` — ").split(", ")
        assertTrue("flw. lists ${flw.size} names, expected the whole catalog", flw.size > 34)
        assertTrue(sheet.contains("_Needed only when you read or write"))
    }
}
