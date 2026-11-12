package com.flowable.atlas.icons

import com.flowable.atlas.model.ModelFiles
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * A model file wears its type's icon in the Project view, the editor tabs and every file list — the same
 * glyph and colour as its explorer page. The `<fileType>` matchers in plugin.xml only make a `.form` open
 * as JSON, so every model used to carry the stock JSON or XML icon and a folder of models read as a folder
 * of config.
 *
 * Decided from the name alone ([ModelFiles.typeOf] reads the file name, its parent's name and one
 * setting) — this runs for every file the Project view paints, so it must not read content or the index.
 * A `.bar` is unambiguously a Flowable deployment archive; a `.zip` is not, and telling a Design export
 * from any other zip would need its contents, so the platform keeps it.
 */
class ModelFileIconProvider : FileIconProvider, DumbAware {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isDirectory) return null
        if (file.name.endsWith(".bar", ignoreCase = true)) return AtlasIcons.Archive
        return ModelFiles.typeOf(file)?.let(AtlasIcons::forType)
    }
}
