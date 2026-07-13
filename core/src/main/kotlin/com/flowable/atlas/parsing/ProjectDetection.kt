package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import java.io.File

/**
 * Detects the distinct Flowable **project folders** that live *underneath* a scan root — the
 * multi-project (monorepo) case where one folder holds several independent Flowable projects, each in
 * its own subfolder (e.g. `repo/order-app`, `repo/billing-app`).
 *
 * [Discovery]/[com.flowable.atlas.graph.Atlas] intentionally flatten everything under a single root
 * into one result; this is the complementary step that answers "which subfolders are their own
 * projects?" so a caller can scope its work to one of them. It is pure and I/O-only on [File] (no
 * platform dependency), so both the IDE plugin and the CLI can reuse it and it is unit testable.
 *
 * Granularity is the **first-level directory** under the root: a project folder is a whole unit even
 * when it is internally split into build modules (`myproject/work`, `myproject/models`, …) — those
 * submodules are grouped under `myproject`, never surfaced separately.
 *
 * Crucially, this only fires for a **container** of projects. If the root *itself* is a project — it
 * directly holds a build file (`pom.xml`/`settings.gradle`/…) or a Flowable model — it is treated as
 * one whole project and detection returns empty, so a single multi-module build at the IntelliJ root
 * is never split into its modules.
 */
object ProjectDetection {

    /** A detected project folder: [relPath] is the first-level directory name under the scan root. */
    data class SubProject(val relPath: String, val appCount: Int, val modelCount: Int)

    /** A build file directly in a directory marks it as its own (possibly multi-module) project. */
    private val BUILD_MARKERS: Set<String> = setOf(
        "pom.xml", "build.gradle", "build.gradle.kts",
        "settings.gradle", "settings.gradle.kts", "package.json",
    )

    /**
     * Project folders found under [root], sorted by name. Empty when the root is itself a single
     * project (root-level build file / model), when nothing Flowable is found, or when there is only
     * one candidate — the *caller* decides whether a choice is needed (a size of < 2 normally means
     * "just use the whole root").
     */
    fun detect(root: File): List<SubProject> {
        if (!root.isDirectory) return emptyList()

        // The root is itself a project (a single, possibly multi-module, build) — don't split it.
        val rootChildren = root.listFiles() ?: return emptyList()
        val rootIsProject = rootChildren.any { child ->
            child.isFile && (child.name in BUILD_MARKERS ||
                ModelType.byExtension(child.name) != null || ModelPaths.isArchive(child.name))
        }
        if (rootIsProject) return emptyList()

        val rootPath = root.toPath()
        val agg = LinkedHashMap<String, IntArray>()   // first-level dir name -> [appCount, modelCount]

        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }
            .filter { it.isFile }
            .forEach { f ->
                val type = ModelType.byExtension(f.name)
                if (type == null && !ModelPaths.isArchive(f.name)) return@forEach   // not a Flowable model
                val top = firstLevelDir(rootPath, f) ?: return@forEach              // directly at root → whole project
                val counts = agg.getOrPut(top) { IntArray(2) }
                if (type == ModelType.APP) counts[0]++
                counts[1]++
            }

        return agg.entries
            .map { (name, counts) -> SubProject(name, counts[0], counts[1]) }
            .sortedBy { it.relPath }
    }

    /** The first path segment of [file] relative to [rootPath], or null when the file sits in the root. */
    private fun firstLevelDir(rootPath: java.nio.file.Path, file: File): String? {
        val rel = runCatching { rootPath.relativize(file.toPath()) }.getOrNull() ?: return null
        return if (rel.nameCount >= 2) rel.getName(0).toString() else null
    }
}
