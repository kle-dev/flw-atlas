package com.flowable.atlas.generate.dto

import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.JsonUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * One app a data object belongs to. [synthetic] marks a *guessed* grouping — no `.app` model listed
 * the data object, so it was grouped by the archive / folder it was indexed from. The preview shows
 * [label] verbatim, so a guess is never presented as if the app model had said so.
 */
data class AppRef(val key: String, val name: String, val synthetic: Boolean = false) {

    val label: String
        get() = when {
            synthetic -> "— ($key)"
            name.isBlank() || name == key -> key
            else -> "$name ($key)"
        }
}

/**
 * Groups indexed data objects by the app that owns them.
 *
 * The authoritative source is the `.app` model's own member list (`extension.design.childModels`,
 * read via [JsonUtil.readAppChildKeys]) — it works for both a packed app export and an unpacked
 * Design workspace folder. Data objects no app claims fall back to the archive (or folder) they were
 * indexed from, flagged [AppRef.synthetic] so the UI can say so.
 *
 * Reads model file contents — call inside a read action.
 */
object DataObjectApps {

    private const val CHILD_TYPE_DATA_OBJECT = "dataObject"

    /** data-object key → owning apps, in app-key order. Every key of [dataObjects] gets an entry. */
    fun group(apps: List<ModelEntry>, dataObjects: List<ModelEntry>): Map<String, List<AppRef>> {
        val owned = HashMap<String, MutableList<AppRef>>()
        for (app in apps.sortedBy { it.key }) {
            val ref = AppRef(app.key, app.name)
            for (childKey in JsonUtil.readAppChildKeys(app.file, CHILD_TYPE_DATA_OBJECT)) {
                val refs = owned.getOrPut(childKey) { ArrayList() }
                if (refs.none { it.key == ref.key }) refs.add(ref)
            }
        }
        val result = LinkedHashMap<String, List<AppRef>>()
        for (entry in dataObjects) {
            val refs = owned[entry.key]
            result[entry.key] = if (!refs.isNullOrEmpty()) refs else listOfNotNull(fallbackFor(entry.file))
        }
        return result
    }

    /** The archive (or folder) a model was indexed from, as a guessed app. Null when neither exists. */
    private fun fallbackFor(file: VirtualFile): AppRef? {
        val archive = JarFileSystem.getInstance().getVirtualFileForJar(file)
        val name = archive?.let { stripArchiveExtension(it.name) } ?: file.parent?.name
        return name?.takeIf { it.isNotBlank() }?.let { AppRef(it, it, synthetic = true) }
    }

    private fun stripArchiveExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
