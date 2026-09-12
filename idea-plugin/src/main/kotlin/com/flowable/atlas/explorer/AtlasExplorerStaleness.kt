package com.flowable.atlas.explorer

import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Is a generated explorer older than the models it describes — and which models?
 *
 * The signal used to be the last Design pull alone, so a `git pull`, an unzipped export or a hand
 * edit never flagged anything, and a team that gets its models through git never saw the hint. The
 * model index already visits every model file and archive; the newest of their modification times is
 * the honest answer, and the pull timestamp stays in as the second source because the pull writes
 * files an instant before the index catches up. The index also keeps every file's time, so the banner
 * and the Hub can name what changed instead of saying *stale*.
 */
object AtlasExplorerStaleness {

    /** When the models in scope last changed, per what Atlas knows — null before the index exists. */
    fun latestModelChange(project: Project): Long? =
        listOfNotNull(
            DesignPullService.lastPullMillis(project),
            project.service<FlowableModelIndexService>().newestModelMtimeOrNull(),
        ).maxOrNull()

    /** Stale when the newest artifact predates [changedAt]; never stale without artifacts or a change time. */
    fun isStale(artifactMtimes: List<Long>, changedAt: Long?): Boolean =
        changedAt != null && artifactMtimes.isNotEmpty() && artifactMtimes.max() < changedAt

    /**
     * The keys of the models whose file changed after [artifactMtime], sorted — a file the index holds no
     * key for (an unreadable one) is named by its file name. Empty before the index exists.
     */
    fun changedSince(project: Project, artifactMtime: Long): List<String> =
        project.service<FlowableModelIndexService>().cachedOrNull()?.let { changedSince(it, artifactMtime) }.orEmpty()

    fun changedSince(index: FlowableIndex, artifactMtime: Long): List<String> {
        val changedFiles = index.fileMtimes.filterValues { it > artifactMtime }.keys
        if (changedFiles.isEmpty()) return emptyList()
        val byContainer = index.allEntries().groupBy { containerOf(it.file) }
        val names = sortedSetOf<String>()
        for (file in changedFiles) {
            val entries = byContainer[file].orEmpty()
            if (entries.isEmpty()) names.add(file.name) else entries.mapTo(names) { it.key }
        }
        return names.toList()
    }

    /** The scanned file an entry belongs to: the archive for a packed model, the file itself otherwise. */
    private fun containerOf(file: VirtualFile): VirtualFile =
        if (file.fileSystem is JarFileSystem) JarFileSystem.getInstance().getVirtualFileForJar(file) ?: file else file

    /**
     * `3 models changed since this page was generated: DEMO-F002, DEMO-P001, DEMO-P007` — the first
     * [shown] names, then `+N more`; the bare *models changed* when nothing can be named.
     */
    fun changedSummary(names: List<String>, shown: Int = 5): String {
        if (names.isEmpty()) return "Models changed since this explorer was generated."
        val head = names.take(shown).joinToString(", ")
        val more = (names.size - shown).takeIf { it > 0 }?.let { " +$it more" } ?: ""
        val noun = if (names.size == 1) "model" else "models"
        return "${names.size} $noun changed since this page was generated: $head$more"
    }
}
