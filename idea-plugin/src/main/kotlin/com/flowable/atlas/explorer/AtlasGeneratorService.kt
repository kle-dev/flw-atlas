package com.flowable.atlas.explorer

import com.flowable.atlas.diagram.DiagramArtifacts
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.Waivers
import com.flowable.atlas.render.ClaudeRenderer
import com.flowable.atlas.render.ExplorerHtmlRenderer
import com.flowable.atlas.render.GraphJsonRenderer
import com.flowable.atlas.render.OverviewRenderer
import com.flowable.atlas.render.SummaryRenderer
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path

/**
 * Runs the Flowable Atlas generator against the open project entirely in-process. The analysis and
 * rendering are the shared pure-JVM `:core` engine ([Atlas] + the render objects) — the same code
 * the standalone `atlas` CLI uses, guaranteeing identical output — so there is no external
 * interpreter or subprocess. Generation honours the project settings: the expression allowlist and
 * custom-function discovery flow into [Atlas.extract] (mirroring the CLI's `--expr-allowlist` /
 * `--custom-functions` / `--no-custom-functions`), and the artifact selection decides what is
 * rendered.
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
        generateExplorers(projectDir, listOf(outputHtml), indicator)

    /**
     * The explorer page at every path in [outputs], from **one** analysis of [projectDir]. All of them
     * must sit in one folder: that folder's waivers.json is what the page's Save writes, so it is the
     * file read — pages in different folders are different reports.
     */
    fun generateExplorers(projectDir: Path, outputs: List<Path>, indicator: ProgressIndicator): Outcome =
        try {
            require(outputs.isNotEmpty() && outputs.map { it.parent }.distinct().size == 1) { "one folder of pages at a time" }
            val root = projectDir.toFile()
            val result = extract(root, indicator, waiverFile(outputs.first().parent))

            indicator.text = "Rendering Atlas explorer…"
            val html = ExplorerHtmlRenderer.render(result, root, waiverAuthor = Waivers.defaultAuthor(root))
            // The last moment a Cancel can still leave the old pages as they were.
            indicator.checkCanceled()
            for (out in outputs) out.toFile().writeText(html, Charsets.UTF_8)

            Outcome.Success(outputs.first(), outputs, summaryLog(result))
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            LOG.warn("Atlas explorer generation failed", e)
            Outcome.Failure("Failed to generate the Atlas explorer: ${e.message}", e.stackTraceToString())
        }

    /** Generate the selected [artifacts] (summary, overview, graph, explorer, CLAUDE.md) into [outputDir]. */
    fun generateAll(
        projectDir: Path,
        outputDir: Path,
        indicator: ProgressIndicator,
        artifacts: Set<AtlasArtifact> = FlowableAtlasProjectSettings.getInstance(project).atlasArtifacts,
    ): Outcome =
        try {
            val root = projectDir.toFile()
            val result = extract(root, indicator, waiverFile(outputDir))

            indicator.text = "Rendering Atlas artifacts…"
            val name = atlasProjectName(projectDir)
            outputDir.toFile().mkdirs()
            // The folder is regenerated and may carry client data; waivers.json is the one file in it
            // meant to be kept. The same .gitignore the CLI writes says so.
            Waivers.ensureOutputGitignore(outputDir.toFile())
            val renderers = mapOf<AtlasArtifact, () -> String>(
                AtlasArtifact.SUMMARY_MD to { SummaryRenderer.render(result, root) },
                AtlasArtifact.OVERVIEW_MD to { OverviewRenderer.render(result, root) },
                // Projected + minified (GraphJsonRenderer): the raw result duplicated every model body
                // and pretty-printed it, which on a large project meant a multi-megabyte file in the
                // user's repo.
                AtlasArtifact.GRAPH_JSON to { GraphJsonRenderer.render(result) },
                AtlasArtifact.EXPLORER_HTML to { ExplorerHtmlRenderer.render(result, root, waiverAuthor = Waivers.defaultAuthor(root)) },
                // The file spells its sibling paths from the project root and only names the ones that
                // are actually being written — the user picks the artifact set in Settings → Generation.
                AtlasArtifact.CLAUDE_MD to {
                    val siblings = AtlasArtifact.SUMMARY_MD in artifacts && AtlasArtifact.GRAPH_JSON in artifacts
                    ClaudeRenderer.render(result, root, ClaudeRenderer.Layout(outputDir.toFile(), siblings))
                },
            )
            val diagrams = if (AtlasArtifact.DIAGRAMS_SVG in artifacts) DiagramArtifacts.render(result, root) else emptyMap()
            val texts = AtlasArtifact.entries
                .filter { it in artifacts && it != AtlasArtifact.DIAGRAMS_SVG }
                .associateWith { renderers.getValue(it)() }
            // Everything is rendered before anything is written, and this is the last moment a Cancel can
            // still leave the previous artifacts as they were.
            indicator.checkCanceled()
            val written = ArrayList<Path>()
            for ((artifact, text) in texts) {
                val p = outputDir.resolve("$name${artifact.suffix}")
                p.toFile().writeText(text, Charsets.UTF_8)
                written.add(p)
            }
            // Each process/case/decision's DI layout → one SVG in `<name>.diagrams/`. Additive post-pass
            // over the finished result; a project with no BPMN/CMMN/DMN layout emits none.
            if (diagrams.isNotEmpty()) {
                val dir = outputDir.resolve("$name.diagrams")
                dir.toFile().mkdirs()
                for ((fn, svg) in diagrams) {
                    val p = dir.resolve(fn)
                    p.toFile().writeText(svg, Charsets.UTF_8)
                    written.add(p)
                }
            }
            val explorer = written.firstOrNull { it.fileName.toString().endsWith(".explorer.html") }
            Outcome.Success(explorer, written, summaryLog(result))
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            LOG.warn("Atlas artifact generation failed", e)
            Outcome.Failure("Failed to generate the Atlas artifacts: ${e.message}", e.stackTraceToString())
        }

    /** `waivers.json` beside the artifacts in [outputDir] — the file the explorer's Save writes. */
    internal fun waiverFile(outputDir: Path?): File? = outputDir?.resolve(Waivers.FILE_NAME)?.toFile()

    /**
     * [Atlas.extract] with the project's allowlist and custom-function settings applied, and the
     * accepted findings in [waiverFile] honoured — the CLI reads that file, and a page generated
     * here that ignored it would show a team the findings it had already decided about.
     */
    internal fun extract(root: File, indicator: ProgressIndicator, waiverFile: File?): LinkedHashMap<String, Any?> {
        indicator.isIndeterminate = true
        indicator.text = "Analyzing Flowable project…"
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val allowlist = (settings.allowedNamespaces + settings.allowedFunctions)
            .toSet().takeIf { it.isNotEmpty() }
        val customPath = settings.customFunctionsPath.takeIf { it.isNotBlank() }
            ?.let { root.resolve(it) }?.takeIf { it.exists() }
        return Atlas.extract(
            root,
            exprAllowlist = allowlist,
            discoverCustom = settings.customFunctionsEnabled,
            customPath = customPath,
            waivers = waiverFile?.let { Waivers.load(it) } ?: Waivers.EMPTY,
            checkCanceled = indicator::checkCanceled,
        )
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
