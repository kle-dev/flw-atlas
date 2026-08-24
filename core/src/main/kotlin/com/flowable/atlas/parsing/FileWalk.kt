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
     */
    fun files(root: File, enterDir: (File) -> Boolean = { true }): Sequence<File> = sequence {
        if (root.isDirectory && !enterDir(root)) return@sequence
        if (root.isFile) yield(root) else yieldAll(descend(root, enterDir))
    }

    private fun descend(dir: File, enterDir: (File) -> Boolean): Sequence<File> = sequence {
        // listFiles() returns null for an unreadable directory (permissions, or it vanished mid-walk);
        // skipping it matches walkTopDown's default behaviour of not failing the traversal.
        val children = dir.listFiles()?.sortedBy { it.name } ?: return@sequence
        for (child in children) {
            if (child.isDirectory) {
                if (enterDir(child)) yieldAll(descend(child, enterDir))
            } else {
                yield(child)
            }
        }
    }
}
