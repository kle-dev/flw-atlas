package com.flowable.atlas.parsing

import java.io.File

/**
 * Directory traversal with a **deterministic** order, so the same project produces the same artifacts on
 * every machine.
 *
 * Kotlin's `File.walkTopDown()` yields children in `File.listFiles()` order, which is whatever the
 * filesystem hands back: APFS/HFS+ return entries sorted, ext4 returns them in hash order. Atlas carried
 * that order all the way through — the discovery buckets feed insertion-ordered maps, so the report's REST
 * endpoint list, the Java-class counts and more came out in filesystem order. Two people running Atlas on
 * the same project got different `overview.md` and `summary.md`, and the goldens could not catch it because
 * they only ever ran on one OS. CI on Linux found it on its first run.
 *
 * Worse than cosmetic in one place: [com.flowable.atlas.expr.catalog.CustomFunctionCatalog] applies a
 * file-count cap *during* the walk, so an unstable order changed **which** files were analysed at all.
 *
 * Children are sorted by name with [String.compareTo] — deliberately not a locale-aware or
 * case-insensitive collation, since those would reintroduce machine dependence through the default locale.
 */
object FileWalk {

    /**
     * Every file under [root], depth-first, with each directory's children visited in name order.
     *
     * [enterDir] decides whether to descend into a directory and is called for [root] as well, matching
     * `walkTopDown().onEnter { … }` so call sites keep their `dir == root || …` guards unchanged.
     * [onUnreadable] hears of every directory that was entered but could not be listed.
     *
     * The walk stays inside [root]: a symbolic link — to a folder [enterDir] accepts, or to a file — whose
     * real path lies outside it is not followed, and [onLeave] is told about it. A repository can commit a
     * link (`up -> ..`, `vendor -> ../../elsewhere`), and following it put a sibling project's models and
     * Java literals into this project's graph.json, explorer and summary — or walked the whole disk for a
     * link to `/`. A link within the project is followed; the visited set still stops one that loops.
     *
     * [enterDir] stays the last parameter: every caller passes it as a trailing lambda, which would otherwise
     * bind to [onLeave] or [onUnreadable] — and compile, since a Boolean lambda is a Unit one too — and
     * silently stop pruning.
     */
    fun files(
        root: File, onLeave: (File) -> Unit = {}, onUnreadable: (File) -> Unit = {}, enterDir: (File) -> Boolean = { true },
    ): Sequence<File> = sequence {
        if (root.isDirectory && !enterDir(root)) return@sequence
        if (root.isFile) { yield(root); return@sequence }
        val rootReal = realPath(root)
        yieldAll(descend(root, enterDir, onLeave, onUnreadable, rootReal, HashSet()))
    }

    private fun realPath(f: File): java.nio.file.Path =
        runCatching { f.canonicalFile.toPath() }.getOrElse { f.absoluteFile.toPath().normalize() }

    private fun descend(
        dir: File, enterDir: (File) -> Boolean, onLeave: (File) -> Unit, onUnreadable: (File) -> Unit,
        rootReal: java.nio.file.Path, visited: MutableSet<String>,
    ): Sequence<File> = sequence {
        // `isDirectory` follows symlinks, so a link to an ancestor would re-walk the tree until the path
        // grew too long (and two such links, exponentially). Each real directory is entered once.
        val real = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        if (!visited.add(real)) return@sequence
        // listFiles() returns null for an unreadable directory (permissions, or it vanished mid-walk). The
        // walk goes on, as walkTopDown's does — but the caller hears of it: a folder of models dropped
        // without a word was indistinguishable from a folder with none.
        val children = dir.listFiles()?.sortedBy { it.name }
        if (children == null) { onUnreadable(dir); return@sequence }
        for (child in children) {
            // Only a link can lead out, so only a link pays for the canonical path; an ordinary child of a
            // directory inside the root is inside the root.
            val leaves = java.nio.file.Files.isSymbolicLink(child.toPath()) && !realPath(child).startsWith(rootReal)
            if (child.isDirectory) {
                // pruned first: a `build` or `node_modules` linked to somewhere else was never going to be
                // walked, and reporting it as a link left unfollowed is noise about nothing
                if (!enterDir(child)) continue
                if (leaves) onLeave(child) else yieldAll(descend(child, enterDir, onLeave, onUnreadable, rootReal, visited))
            } else {
                if (leaves) onLeave(child) else yield(child)
            }
        }
    }
}
