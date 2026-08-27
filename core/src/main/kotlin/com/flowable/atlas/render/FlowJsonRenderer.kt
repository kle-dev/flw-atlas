package com.flowable.atlas.render

import com.flowable.atlas.flow.FlowStoryFile
import com.flowable.atlas.flow.FlowTraversal
import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * Renders the standalone `<project>.flow.json` artifact: the structured [FlowStoryFile] payload with
 * one [com.flowable.atlas.flow.FlowStory] per startable BPMN process / CMMN case.
 *
 * Written by the CLI's `--all` mode alongside the other five artifacts (see `cli/.../Main.kt`), and
 * also embedded into the self-contained `<project>.explorer.html` by [ExplorerHtmlRenderer] so the
 * Storyline view can render offline.
 */
object FlowJsonRenderer {

    /** Build the payload — reused by the CLI writer and by the explorer embed to keep them in sync. */
    fun build(result: Map<String, Any?>, root: File): FlowStoryFile =
        FlowStoryFile(
            project = root.absoluteFile.name.ifEmpty { "project" },
            stories = FlowTraversal.traverseAll(result),
        )

    /** Serialise to indented JSON — matches the other CLI artifacts (2-space indent, LF newlines). */
    fun render(result: Map<String, Any?>, root: File): String =
        MiniJson.stringify(build(result, root).toMap(), 2)
}
