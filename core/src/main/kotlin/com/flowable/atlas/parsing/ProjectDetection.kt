package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import java.io.File

/**
 * Detects the distinct Flowable sub-projects that live *underneath* a scan root — the multi-project
 * (monorepo) case where one folder holds several independently deployable Flowable apps/modules.
 *
 * [Discovery]/[com.flowable.atlas.graph.Atlas] intentionally flatten everything under a single root
 * into one result; this is the complementary, purely-heuristic step that answers "which sub-folders
 * are their own projects?" so a caller can scope its work to one of them. It is pure and I/O-only on
 * [File] (no platform dependency), so both the IDE plugin and the CLI can reuse it and it is unit
 * testable.
 *
 * A sub-project root is the **innermost build module** (a directory holding one of [BUILD_MARKERS])
 * that contains at least one Flowable model, or — when no build files exist (a plain Design-export
 * layout) — the **first-level directory** under the root that contains models. Models that sit at the
 * root itself do not make the root "a sub-project of itself" and are ignored here (that is just the
 * ordinary whole-project case). The exact rule is deliberately kept in this one function so it can be
 * tuned against real projects without touching callers.
 */
object ProjectDetection {

    /** A detected sub-project: [relPath] is '/'-separated and relative to the scan root. */
    data class SubProject(val relPath: String, val appCount: Int, val modelCount: Int)

    /** Files whose presence marks a directory as a build module / project root. */
    val BUILD_MARKERS: Set<String> = setOf(
        "pom.xml", "build.gradle", "build.gradle.kts",
        "settings.gradle", "settings.gradle.kts", "package.json",
    )

    /**
     * Sub-projects found under [root], sorted by path. May be empty (no models, or models only at
     * [root]) or hold a single entry — the *caller* decides whether a choice is needed (a size of
     * < 2 normally means "just use the whole root").
     */
    fun detect(root: File): List<SubProject> {
        if (!root.isDirectory) return emptyList()

        val buildDirs = HashSet<File>()
        val stats = HashMap<File, IntArray>()   // model-bearing dir -> [appCount, modelCount] directly in it

        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }
            .filter { it.isFile }
            .forEach { f ->
                val parent = f.parentFile ?: return@forEach
                val type = ModelType.byExtension(f.name)
                when {
                    f.name in BUILD_MARKERS -> buildDirs.add(parent)
                    type != null -> {
                        val s = stats.getOrPut(parent) { IntArray(2) }
                        if (type == ModelType.APP) s[0]++
                        s[1]++
                    }
                    // A packed archive can hold an entire app; treat its folder as model-bearing.
                    ModelPaths.isArchive(f.name) -> stats.getOrPut(parent) { IntArray(2) }[1]++
                }
            }

        val agg = LinkedHashMap<File, IntArray>()
        for ((dir, s) in stats) {
            val projectRoot = resolveRoot(dir, root, buildDirs)
            if (projectRoot == root) continue   // models at the root itself → whole-project, not a sub-project
            val a = agg.getOrPut(projectRoot) { IntArray(2) }
            a[0] += s[0]
            a[1] += s[1]
        }

        return agg.entries
            .map { (dir, counts) -> SubProject(relPath(root, dir), counts[0], counts[1]) }
            .sortedBy { it.relPath }
    }

    /**
     * The project root owning [dir]: its innermost build-module ancestor (inclusive, bounded by
     * [root]); or, when there is no build marker up the chain, the first-level directory under [root].
     */
    private fun resolveRoot(dir: File, root: File, buildDirs: Set<File>): File {
        var cur: File? = dir
        while (cur != null) {
            if (cur in buildDirs) return cur
            if (cur == root) break
            cur = cur.parentFile
        }
        // No build marker: climb to the first-level directory directly under [root].
        if (dir == root) return root
        var child = dir
        var parent = dir.parentFile
        while (parent != null && parent != root) {
            child = parent
            parent = parent.parentFile
        }
        return if (parent == root) child else root
    }

    private fun relPath(root: File, dir: File): String =
        root.toPath().relativize(dir.toPath()).toString().replace(File.separatorChar, '/')
}
