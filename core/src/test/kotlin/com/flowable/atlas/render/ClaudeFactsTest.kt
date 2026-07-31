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

    private fun render(process: List<Map<String, Any?>> = emptyList()): String {
        val result = mapOf(
            "stats" to mapOf("models" to process.size, "java" to 0),
            "graph" to mapOf(
                "nodes" to process.map {
                    mapOf("id" to "process:${it["key"]}", "type" to "process", "key" to it["key"],
                          "label" to it["key"], "file" to "processes/${it["key"]}.bpmn",
                          "data" to emptyMap<String, Any?>())
                },
                "edges" to emptyList<Any?>(),
            ),
            "processes" to process,
        )
        return ClaudeRenderer.render(result, tmp.root)
    }

    private fun proc(key: String): Map<String, Any?> = mapOf("key" to key, "name" to key)

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
        // A Gradle plugin version or a Java release must not be mistaken for the engine version.
        tmp.newFile("gradle.properties").writeText("javaVersion=21\nkotlinVersion=2.0.20\n")
        tmp.newFile("build.gradle").writeText("plugins { id 'java' }\n")
        assertTrue(render().contains("not auto-detected"))
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
        val out = render(listOf(proc("KYC-P001"), proc("KYC-P002"), proc("KYC-P003")))
        assertTrue(out, out.contains("**Key conventions:** process `KYC-P#` (e.g. `KYC-P00"))
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
}
