package com.flowable.atlas.explorer

import com.flowable.atlas.graph.Waivers
import com.flowable.atlas.hub.HubHeader
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Writes `waivers.json` into an analysis output folder — the one file in it meant to be kept. Shared by
 * the explorer page's Save and the findings tool window's Accept, so both merge the same way: the rules
 * the caller started from ([base], by sort key) may be dropped or edited, anything the file gained
 * meanwhile is kept. Must be called on the EDT.
 */
internal object WaiverFileWriter {

    /** The file written, and how many rules and notes the merge kept that [page] did not have. */
    data class Written(val file: VirtualFile, val kept: Int)

    fun write(dir: VirtualFile, page: Waivers.Set, base: Collection<String>): Written =
        WriteAction.compute<Written, Exception> {
            val t = dir.findChild(Waivers.FILE_NAME) ?: dir.createChildData(this, Waivers.FILE_NAME)
            // An open, edited waivers.json is flushed first, or the document and the file would fight.
            FileDocumentManager.getInstance().getDocument(t)?.let { FileDocumentManager.getInstance().saveDocument(it) }
            // Last-write-wins against a snapshot deleted whatever the file gained meanwhile; the merge keeps
            // a rule the caller never saw.
            val disk = Waivers.load(t.toNioPath().toFile())
            val merged = Waivers.merge(page, disk, base)
            val kept = merged.waivers.size + merged.notes.size - (page.waivers.size + page.notes.size)
            // UTF-8 explicitly: every other Atlas writer pins it, and :core reads the file as UTF-8.
            t.setBinaryContent(Waivers.serialize(merged, HubHeader.atlasVersion()).toByteArray(Charsets.UTF_8))
            Written(t, kept)
        }
}
