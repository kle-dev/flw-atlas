package com.flowable.atlas.explorer

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Locates generated `*.explorer.html` pages in a project. Walks the filesystem directly (independent
 * of the VFS index) so pages written by the external `atlas` CLI are seen immediately: the configured
 * output folder (default `atlas-output/`) first, then — only if that is empty — a shallow scan of the project, skipping
 * VCS/build/dependency directories. Most-recently-modified first. Shared by the "Open Atlas Explorer"
 * action and the Atlas Explorer tool window.
 */
object AtlasExplorerFiles {

    private const val MAX_RESULTS = 50
    private const val PROJECT_SCAN_DEPTH = 6
    private val SKIP_DIRS = setOf(
        ".git", ".gradle", ".idea", ".kotlin", ".intellijPlatform",
        "node_modules", "build", "target", "out", "dist",
    )

    fun find(base: Path, outputDir: String = "atlas-output"): List<Path> {
        val found = LinkedHashSet<Path>()
        val atlasOutput = base.resolve(outputDir)
        if (Files.isDirectory(atlasOutput)) walk(atlasOutput, Int.MAX_VALUE, found)
        if (found.isEmpty()) walk(base, PROJECT_SCAN_DEPTH, found)
        return found.sortedByDescending {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        }
    }

    private fun walk(root: Path, maxDepth: Int, into: MutableSet<Path>) {
        runCatching {
            Files.walkFileTree(root, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName?.toString() in SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.fileName?.toString()?.endsWith(".explorer.html", ignoreCase = true) == true) {
                        into.add(file)
                        if (into.size >= MAX_RESULTS) return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
    }
}
