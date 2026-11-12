package com.flowable.atlas

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

/**
 * The text behind a balloon's *Show Details* — a generator log, the index's per-type breakdown — as a
 * read-only editor tab. It used to be a modal dialog when short and a tab when long, so the same button
 * did two different things depending on a character count; a tab can be read, searched and copied from
 * whatever its length, and does not block the IDE while it is.
 */
object AtlasDetails {
    fun show(project: Project, name: String, text: String) {
        if (project.isDisposed) return
        val file = LightVirtualFile(name, PlainTextFileType.INSTANCE, text).apply { isWritable = false }
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
