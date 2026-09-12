package com.flowable.atlas.hub

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/** A model the developer looked at recently: its file, and what the index knows it as. */
internal data class RecentModel(val file: VirtualFile, val key: String, val type: ModelType?, val name: String, val entry: ModelEntry?)

/**
 * The model files opened or brought to the front in this project, newest first — the Hub's *Recent
 * Models* list. Fed by the editor: every way a model is reached (Ctrl+click, Search Everywhere, the
 * gutter, the Project view) ends in an editor tab, so the editor is the one place to listen. Kept in
 * the workspace file, so the list survives a restart; a file that is gone is dropped when read.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableAtlasRecentModels", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RecentModelsService(private val project: Project) : PersistentStateComponent<RecentModelsService.State> {

    class State {
        var urls: MutableList<String> = ArrayList()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    /** Moves [file] to the front when it is a model; anything else is ignored. */
    fun record(file: VirtualFile) {
        if (ModelFiles.typeOf(file) == null || ModelFiles.isExcluded(file.path)) return
        val url = file.url
        val urls = state.urls
        if (urls.firstOrNull() == url) return
        urls.remove(url)
        urls.add(0, url)
        while (urls.size > CAPACITY) urls.removeAt(urls.size - 1)
        AtlasEvents.recentModelsChanged(project)
    }

    /** The list, resolved against [index] for key, type and name — a file the index does not know is named by its file. */
    internal fun recent(index: FlowableIndex?): List<RecentModel> {
        val vfm = VirtualFileManager.getInstance()
        val byFile = index?.allEntries()?.groupBy { it.file }.orEmpty()
        return state.urls.mapNotNull { url ->
            val file = vfm.findFileByUrl(url)?.takeIf { it.isValid } ?: return@mapNotNull null
            val entry = byFile[file]?.firstOrNull()
            RecentModel(file, entry?.key ?: bareName(file), entry?.type ?: ModelFiles.typeOf(file), entry?.name ?: "", entry)
        }
    }

    /** `DEMO-P001` for `DEMO-P001.bpmn20.xml` — the compound extension off, not only the last one. */
    private fun bareName(file: VirtualFile): String {
        val lower = file.name.lowercase()
        val compound = ModelType.COMPOUND_EXTENSIONS.firstOrNull { lower.endsWith(it) }
        return if (compound != null) file.name.dropLast(compound.length) else file.nameWithoutExtension
    }

    /** For tests and the Hub's context menu. */
    fun clear() {
        state.urls.clear()
        AtlasEvents.recentModelsChanged(project)
    }

    /** The editor hook: opened, or brought to the front. Registered in plugin.xml as a project listener. */
    class Listener(private val project: Project) : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = project.service<RecentModelsService>().record(file)
        override fun selectionChanged(event: FileEditorManagerEvent) {
            event.newFile?.let { project.service<RecentModelsService>().record(it) }
        }
    }

    companion object {
        const val CAPACITY = 8
        fun getInstance(project: Project): RecentModelsService = project.service()
    }
}
