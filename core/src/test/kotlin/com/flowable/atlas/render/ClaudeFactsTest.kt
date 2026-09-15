package com.flowable.atlas.render

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
        assertTrue(out, out.contains("`java -jar <atlas-cli>.jar <project-dir> --slice process:<key> --stdout`"))
        assertFalse(out, out.contains("`atlas <project-dir> --slice"))
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
