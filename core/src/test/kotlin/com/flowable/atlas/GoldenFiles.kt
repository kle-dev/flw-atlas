package com.flowable.atlas

import java.io.File

/**
 * Locates the committed golden files **on disk** (not on the test classpath) and implements the
 * `ATLAS_UPDATE_GOLDEN=1` escape hatch.
 *
 * The deleted Python suite had exactly this: *"any intended change is made visible by regenerating the
 * goldens (`ATLAS_UPDATE_GOLDEN=1 pytest`) and reviewing the diff"*. The mechanism was lost in the
 * Kotlin port, which left hand-written throwaway tests as the only way to refresh a golden — so the
 * goldens quietly became a reason not to change the generated artifacts at all. Restoring it makes the
 * regression gate cheap to re-baseline *and* keeps the diff review that gives the gate its value.
 *
 * Usage in a test: call [assertMatches]. It asserts by default; with the env var set it overwrites the
 * source file and lets the test pass, so a full `ATLAS_UPDATE_GOLDEN=1 ./gradlew :core:test` rewrites
 * every golden in one go (`./gradlew :core:updateGoldens` does that for you).
 */
object GoldenFiles {

    /** True when the run should rewrite goldens instead of asserting against them. */
    val updating: Boolean = System.getenv("ATLAS_UPDATE_GOLDEN") == "1"

    /** The Gradle root, found by walking up from the test working directory. */
    val repoRoot: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        d ?: error("could not locate the Gradle root from ${System.getProperty("user.dir")}")
    }

    /** `core/src/test/resources/golden`. */
    val dir: File by lazy {
        File(repoRoot, "core/src/test/resources/golden").also {
            require(it.isDirectory) { "golden directory not found at $it" }
        }
    }

    fun file(name: String): File = File(dir, name)

    fun read(name: String): String = file(name).readText()

    /**
     * Assert [actual] equals the golden [name] — or, when [updating], write it and report what changed.
     *
     * Callers pass the exact bytes the artifact should have (the Markdown goldens include the trailing
     * newline the CLI's `--stdout` adds), so a rewrite is byte-for-byte what the next run will compare.
     */
    fun assertMatches(name: String, actual: String) = assertFileMatches(file(name), actual)

    /** [assertMatches] for a generated file that lives outside the golden directory. */
    fun assertFileMatches(f: File, actual: String) {
        val name = f.name
        val expected = if (f.isFile) f.readText() else null
        if (actual == expected) return
        if (updating) {
            f.writeText(actual)
            println("[golden] rewrote ${f.absolutePath} (${expected?.length ?: 0} → ${actual.length} chars)")
            return
        }
        org.junit.Assert.assertEquals(
            "$name differs — review the change, then re-baseline with `./gradlew :core:updateGoldens`",
            expected, actual,
        )
    }
}
