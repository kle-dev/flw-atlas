package com.flowable.atlas.model

/**
 * Canonical directory- and archive-classification rules, shared by the batch discovery walk
 * (`:core` [parsing.Discovery]) and the IDE's live VFS index (`:idea-plugin`).
 *
 * Pure and I/O-free — it operates on names and `/`-separated path strings, so both a `java.nio`
 * walk and an IntelliJ VFS walk can call it. Ported from the original `flowable_atlas`
 * `EXCLUDE_DIRS` / `ARCHIVE_EXTS`; this is the single source for both.
 */
object ModelPaths {

    /** Build-output / tooling directories pruned from any model walk. */
    val EXCLUDE_DIRS: Set<String> = setOf(
        "target", "build", "node_modules", ".git", ".idea", ".gradle", "dist",
        "out", "bin", ".mvn", "coverage", "__pycache__", ".vscode", "test-classes",
    )

    /** Business-archive extensions (a `.bar`/`.zip` may pack many models). */
    val ARCHIVE_EXTS: List<String> = listOf(".zip", ".bar")

    /** True if any segment of the `/`-separated [path] is an excluded directory. */
    fun isExcluded(path: String): Boolean = path.split('/').any { it in EXCLUDE_DIRS }

    /**
     * True if the `/`-separated [path] lies in a test source set — `src/test/…`, `src/integrationTest/…`,
     * `src/testFixtures/…` (a `src` segment followed by one that mentions `test`). Test code is not the
     * project: a JUnit class calling `setVariable("foo", …)` is not a production write, and a test
     * `@RestController` is not an endpoint the models can reach. Models under `src/test/resources` are
     * deliberately *not* affected — a test process is still a model somebody has to keep in step.
     */
    fun isTestSource(path: String): Boolean {
        val segs = path.split('/')
        for (i in 0 until segs.size - 1) {
            if (segs[i] == "src" && segs[i + 1].lowercase().contains("test")) return true
        }
        return false
    }

    /**
     * [isTestSource] for a model's `file` as Atlas records it: a project-relative path, or
     * `archive.bar!entry` for a model inside an archive, where the archive's own path decides. Such a
     * model is deployed by the project's tests only — never with the app, so Work never offers it — and
     * when it shares a key with a deployed copy, the deployed copy is the model (see `Atlas.extract`).
     */
    fun isTestSourceModel(file: String?): Boolean =
        file != null && isTestSource(file.substringBefore('!').replace('\\', '/'))

    /** True if [name] ends with a business-archive extension (case-insensitive). */
    fun isArchive(name: String): Boolean {
        val low = name.lowercase()
        return ARCHIVE_EXTS.any { low.endsWith(it) }
    }
}
