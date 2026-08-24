package com.flowable.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A document that states which version it describes must state the version this build *is*.
 *
 * `FEATURES.md` opens with *"IntelliJ IDEA plugin, v&lt;version&gt;"*, and it had been reading 0.13.0 for
 * a release — a stale number nobody notices, because a features list stays plausible while it goes out
 * of date. That is worse than no number at all: a reader takes it as a statement about what they have
 * installed.
 *
 * Only *this* claim is checked. `README.md` saying "versions below 0.13.0 were never published" and the
 * site's plugin reference citing "the 0.13.0 release notes" are history, and history does not follow the
 * version. The distinction is whether the sentence describes the current build.
 *
 * Same shape as [com.flowable.atlas.render.ClaudeTemplateSyncTest] and
 * [com.flowable.atlas.render.ChangelogSyncTest]: one source of truth, a test that fails on divergence.
 * The source of truth is the Gradle `version`, read from the root build script rather than from
 * [AtlasBuildInfo] — the latter falls back to `"dev"` when the version resource has not been generated,
 * which would make this pass for the wrong reason in an IDE run.
 */
class DocsVersionSyncTest {

    private val gradleVersion: String by lazy {
        val build = File(GoldenFiles.repoRoot, "build.gradle.kts")
        val m = Regex("""^\s*version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(build.readText())
        assertTrue("cannot find `version = \"…\"` in build.gradle.kts — did the declaration move?", m != null)
        m!!.groupValues[1]
    }

    @Test
    fun featuresDocumentsThisVersion() {
        val features = File(GoldenFiles.repoRoot, "FEATURES.md")
        assertTrue("missing FEATURES.md", features.isFile)
        val stated = Regex("""IntelliJ IDEA plugin, v([0-9][^.\s]*(?:\.[^.\s]+)*?)\.\*""")
            .find(features.readText())
        assertTrue(
            "FEATURES.md no longer opens with \"*IntelliJ IDEA plugin, v<version>.*\" — keep the claim " +
                "or drop it, but do not leave it in a shape nothing can check",
            stated != null,
        )
        assertEquals(
            "FEATURES.md says it describes v${stated!!.groupValues[1]}, but this build is $gradleVersion. " +
                "Bump the line in FEATURES.md together with the version in build.gradle.kts.",
            gradleVersion,
            stated.groupValues[1],
        )
    }
}
