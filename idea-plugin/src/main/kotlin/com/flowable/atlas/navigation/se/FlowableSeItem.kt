package com.flowable.atlas.navigation.se

import com.flowable.atlas.index.ModelEntry
import com.intellij.openapi.vfs.VirtualFile

/**
 * One row in the "Flowable Model" Search Everywhere tab: either an indexed model (matched by key or
 * by file path) or a live full-text hit inside a model's content.
 *
 * Both variants are `data class`es on purpose — Search Everywhere de-duplicates accumulated results
 * by `equals`, so two searches that surface the same hit must produce equal items.
 */
sealed interface FlowableSeItem {

    /** The model file — a loose file, or an entry inside a `.bar`/`.zip` (a `jar://…!/…` file). */
    val file: VirtualFile

    /**
     * `app.zip → processes/invoice.bpmn` for an archive entry, else the plain file name. Not shown in
     * the row — the row's right-hand column is the bare file name — but carried as the item's
     * description, so the archive an entry came from stays retrievable.
     */
    val displayPath: String

    data class Model(
        val entry: ModelEntry,
        override val displayPath: String,
    ) : FlowableSeItem {
        override val file: VirtualFile get() = entry.file
    }

    /**
     * [line] and [column] are 0-based, as [com.intellij.openapi.fileEditor.OpenFileDescriptor]
     * expects. [lineText] is the matched line, trimmed and windowed around the match;
     * [matchStart] is where the match sits **within [lineText]**, or -1 if it fell outside the window.
     */
    data class TextHit(
        override val file: VirtualFile,
        override val displayPath: String,
        val line: Int,
        val column: Int,
        val lineText: String,
        val matchStart: Int,
        val matchLength: Int,
    ) : FlowableSeItem
}
