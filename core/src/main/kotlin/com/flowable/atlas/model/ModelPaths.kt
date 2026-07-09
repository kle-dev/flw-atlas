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

    /** True if [name] ends with a business-archive extension (case-insensitive). */
    fun isArchive(name: String): Boolean {
        val low = name.lowercase()
        return ARCHIVE_EXTS.any { low.endsWith(it) }
    }
}
