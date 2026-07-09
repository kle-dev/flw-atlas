package com.flowable.atlas.explorer

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.render.ClaudeRenderer
import com.flowable.atlas.render.ExplorerHtmlRenderer
import com.flowable.atlas.render.OverviewRenderer
import com.flowable.atlas.render.SummaryRenderer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Runs the Flowable Atlas generator against the open project entirely in-process. The analysis and
 * rendering are the shared pure-JVM `:core` engine ([Atlas] + the render objects) — the same code
 * the standalone `atlas` CLI uses, guaranteeing identical output — so there is no external
 * interpreter or subprocess. Can emit just the explorer HTML, or the full set of artifacts (`--all`).
 */
@Service(Service.Level.PROJECT)
class AtlasGeneratorService(private val project: Project) {

    private val LOG = logger<AtlasGeneratorService>()

    sealed interface Outcome {
        /** [explorerHtml] is the `.explorer.html` produced (if any); [written] lists all files written. */
        data class Success(val explorerHtml: Path?, val written: List<Path>, val log: String) : Outcome
        data class Failure(val message: String, val log: String) : Outcome
    }

    /** Generate only the self-contained explorer HTML at [outputHtml]. */
    fun generateExplorer(projectDir: Path, outputHtml: Path, indicator: ProgressIndicator): Outcome =
        try {
            val root = projectDir.toFile()
            indicator.isIndeterminate = true
            indicator.text = "Analyzing Flowable project…"
            val result = Atlas.extract(root)

            indicator.text = "Rendering Atlas explorer…"
            val html = ExplorerHtmlRenderer.render(result, root)
            outputHtml.toFile().writeText(html, Charsets.UTF_8)

            Outcome.Success(outputHtml, listOf(outputHtml), summaryLog(result))
        } catch (e: Exception) {
            LOG.warn("Atlas explorer generation failed", e)
            Outcome.Failure("Failed to generate the Atlas explorer: ${e.message}", "")
        }

    /** Generate all Atlas artifacts (summary, overview, graph, explorer, CLAUDE.md) into [outputDir]. */
    fun generateAll(projectDir: Path, outputDir: Path, indicator: ProgressIndicator): Outcome =
        try {
            val root = projectDir.toFile()
            indicator.isIndeterminate = true
            indicator.text = "Analyzing Flowable project…"
            val result = Atlas.extract(root)

            indicator.text = "Rendering Atlas artifacts…"
            val name = atlasProjectName(projectDir)
            outputDir.toFile().mkdirs()
            val artifacts = listOf(
                "$name.summary.md" to SummaryRenderer.render(result, root),
                "$name.overview.md" to OverviewRenderer.render(result, root),
                "$name.graph.json" to MiniJson.stringify(result, 2),
                "$name.explorer.html" to ExplorerHtmlRenderer.render(result, root),
                "$name.CLAUDE.md" to ClaudeRenderer.render(result, root),
            )
            val written = ArrayList<Path>()
            for ((fn, content) in artifacts) {
                val p = outputDir.resolve(fn)
                p.toFile().writeText(content, Charsets.UTF_8)
                written.add(p)
            }
            val explorer = written.firstOrNull { it.fileName.toString().endsWith(".explorer.html") }
            Outcome.Success(explorer, written, summaryLog(result))
        } catch (e: Exception) {
            LOG.warn("Atlas artifact generation failed", e)
            Outcome.Failure("Failed to generate the Atlas artifacts: ${e.message}", "")
        }

    /** A short one-line health check mirroring the CLI status line, for the "Show details" affordance. */
    private fun summaryLog(result: Map<String, Any?>): String {
        val stats = result["stats"] as? Map<*, *> ?: emptyMap<String, Any?>()
        fun stat(key: String): Int = (stats[key] as? Number)?.toInt() ?: 0
        val resolved = (result["resolvedRefs"] as? List<*>)?.size ?: 0
        val unresolved = (result["unresolvedRefs"] as? List<*>)?.size ?: 0
        val diagnostics = (result["diagnostics"] as? List<*>)?.size ?: 0
        return buildString {
            append("${stat("models")} models · ${stat("java")} java · ${stat("nodes")} nodes · ")
            append("${stat("edges")} links · $resolved resolved / $unresolved unresolved refs")
            if (diagnostics > 0) append(" · ⚠ $diagnostics parse issue(s)")
        }
    }

    /** The project name Atlas derives for output filenames: basename of the dir, last extension stripped. */
    private fun atlasProjectName(projectDir: Path): String {
        val base = projectDir.toAbsolutePath().normalize().fileName?.toString().orEmpty()
        return base.substringBeforeLast('.', base).ifBlank { "project" }
    }

    companion object {
        fun getInstance(project: Project): AtlasGeneratorService = project.service()
    }
}
