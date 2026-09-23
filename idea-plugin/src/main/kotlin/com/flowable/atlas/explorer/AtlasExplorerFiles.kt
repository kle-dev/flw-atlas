package com.flowable.atlas.explorer

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
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

    private val LOG = logger<AtlasExplorerFiles>()

    private const val MAX_RESULTS = 50
    private const val PROJECT_SCAN_DEPTH = 6
    private val SKIP_DIRS = setOf(
        ".git", ".gradle", ".idea", ".kotlin", ".intellijPlatform",
        "node_modules", "build", "target", "out", "dist",
    )

    /**
     * Remember which folder [html] was generated from. A page's Regenerate, and its links into the
     * sources, re-analysed whatever sub-project was active at the time — so a page for one app was
     * overwritten with another's analysis, and its links said "file not found". Kept in the workspace,
     * never in the page: the page is a file a team may share, the path is this machine's.
     */
    fun rememberRoot(project: Project, html: Path, root: Path) {
        PropertiesComponent.getInstance(project).setValue(rootKey(html), root.toAbsolutePath().normalize().toString())
    }

    /** The folder [html] was generated from in this IDE, or null for a page it never generated. */
    fun rootOf(project: Project, html: Path): Path? =
        PropertiesComponent.getInstance(project).getValue(rootKey(html))?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private fun rootKey(html: Path) = "flowable.atlas.explorerRoot:" + html.toAbsolutePath().normalize()

    /**
     * [find], remembered until a `*.explorer.html` is created, deleted, moved or renamed anywhere. The Hub
     * asks on every refresh, and with an empty output folder each ask walked the project six levels deep.
     */
    fun findCached(base: Path, outputDir: String = "atlas-output"): List<Path> {
        val key = base.toAbsolutePath().normalize().toString() + "\u0000" + outputDir
        found[key]?.let { return it }
        val gen = generation.get()
        val result = find(base, outputDir)
        if (generation.get() == gen) found[key] = result
        return result
    }

    /** Drop every remembered answer — the Hub's explicit Refresh. */
    fun forget() { generation.incrementAndGet(); found.clear() }

    private val found = java.util.concurrent.ConcurrentHashMap<String, List<Path>>()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /** Drops [findCached]'s answers when a page appears or goes. Registered in plugin.xml. */
    class Listener : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            val pageChanged = events.any { e ->
                e !is VFileContentChangeEvent && (isPage(e.path) ||
                    (e as? VFilePropertyChangeEvent)?.oldPath?.let(::isPage) == true ||
                    (e as? VFileMoveEvent)?.oldPath?.let(::isPage) == true ||
                    runCatching { e.file?.isDirectory == true }.getOrDefault(false))
            }
            if (pageChanged) forget()
        }

        private fun isPage(path: String) = path.endsWith(".explorer.html", ignoreCase = true)
    }

    fun find(base: Path, outputDir: String = "atlas-output"): List<Path> {
        val found = LinkedHashSet<Path>()
        val atlasOutput = base.resolve(outputDir)
        if (Files.isDirectory(atlasOutput)) walk(atlasOutput, Int.MAX_VALUE, found)
        if (found.isEmpty()) walk(base, PROJECT_SCAN_DEPTH, found)
        // No logging in the sort key on purpose: it is evaluated O(n log n) times, and a file whose
        // timestamp cannot be read simply sorts last, which is the right outcome anyway.
        return found.sortedByDescending {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        }
    }

    private fun walk(root: Path, maxDepth: Int, into: MutableSet<Path>) {
        // A failure here means the Atlas Hub shows "no explorer files" for a project that has them —
        // indistinguishable from "none generated yet" unless it is logged. Individual unreadable files
        // are already tolerated by visitFileFailed below, so reaching the catch means the whole walk
        // died (root vanished mid-scan, permissions, a broken symlink loop).
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
        }.onFailure { LOG.warn("Scanning $root for *.explorer.html failed", it) }
    }
}
