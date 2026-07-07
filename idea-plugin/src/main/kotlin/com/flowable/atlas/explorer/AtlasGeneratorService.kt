package com.flowable.atlas.explorer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Runs the bundled Flowable Atlas generator ([AtlasScript]) against the open project. The generator
 * is pure Python 3 (stdlib only), so all we need is a Python interpreter ([PythonLocator]); the
 * analysis itself is done by the same script the standalone `atlas` CLI uses, guaranteeing identical
 * output. Can emit just the explorer HTML, or the full set of artifacts (`--all`).
 */
@Service(Service.Level.PROJECT)
class AtlasGeneratorService(private val project: Project) {

    /** Suffixes of the artifacts Atlas writes in `--all` mode (used to locate what was produced). */
    private val allArtifactSuffixes =
        listOf(".summary.md", ".overview.md", ".graph.json", ".explorer.html", ".CLAUDE.md")

    sealed interface Outcome {
        /** [explorerHtml] is the `.explorer.html` produced (if any); [written] lists all files written. */
        data class Success(val explorerHtml: Path?, val written: List<Path>, val log: String) : Outcome
        data class Failure(val message: String, val log: String) : Outcome
    }

    /** Generate only the self-contained explorer HTML at [outputHtml]. */
    fun generateExplorer(projectDir: Path, outputHtml: Path, indicator: ProgressIndicator): Outcome =
        run(projectDir, indicator, listOf("--html", "-o", outputHtml.toString())) {
            if (!outputHtml.toFile().isFile) {
                Outcome.Failure("Atlas finished but produced no HTML file.", it)
            } else {
                Outcome.Success(outputHtml, listOf(outputHtml), it)
            }
        }

    /** Generate all Atlas artifacts (summary, overview, graph, explorer, CLAUDE.md) into [outputDir]. */
    fun generateAll(projectDir: Path, outputDir: Path, indicator: ProgressIndicator): Outcome =
        run(projectDir, indicator, listOf("--all", "-o", outputDir.toString())) {
            val written = findWrittenArtifacts(projectDir, outputDir)
            if (written.isEmpty()) {
                Outcome.Failure("Atlas finished but no artifacts were found in the output folder.", it)
            } else {
                val explorer = written.firstOrNull { p -> p.fileName.toString().endsWith(".explorer.html") }
                Outcome.Success(explorer, written, it)
            }
        }

    /** Runs the generator with [modeArgs]; [onSuccess] maps the captured log to a success/failure outcome. */
    private fun run(
        projectDir: Path,
        indicator: ProgressIndicator,
        modeArgs: List<String>,
        onSuccess: (log: String) -> Outcome,
    ): Outcome {
        val python = PythonLocator.locate()
        val exe = python.exe ?: return Outcome.Failure(python.error ?: "Python 3 not found.", "")

        val script = AtlasScript.extractToTemp()
            ?: return Outcome.Failure("The bundled Atlas generator is missing from this plugin build.", "")

        indicator.isIndeterminate = true
        indicator.text = "Running Flowable Atlas (${python.version ?: "python"})…"

        val cmd = GeneralCommandLine(listOf(exe, script.toString(), projectDir.toString()) + modeArgs)
            .withWorkDirectory(projectDir.toFile())
            .withCharset(StandardCharsets.UTF_8)

        return try {
            val output = CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator)
            val log = (output.stdout + "\n" + output.stderr).trim()
            when {
                output.isCancelled -> Outcome.Failure("Atlas generation was cancelled.", log)
                output.exitCode != 0 -> Outcome.Failure("Atlas exited with code ${output.exitCode}.", log)
                else -> onSuccess(log)
            }
        } catch (e: Exception) {
            Outcome.Failure("Failed to run the Atlas generator: ${e.message}", "")
        }
    }

    /**
     * Locates the artifacts `--all` wrote. Prefers the exact names Atlas derives from the project dir
     * (`<name>.<artifact>`); falls back to any matching-suffix file in the folder if the name differs.
     */
    private fun findWrittenArtifacts(projectDir: Path, outputDir: Path): List<Path> {
        val name = atlasProjectName(projectDir)
        val expected = allArtifactSuffixes.map { outputDir.resolve("$name$it") }.filter { it.toFile().isFile }
        if (expected.isNotEmpty()) return expected
        val listed = outputDir.toFile().listFiles()?.asList().orEmpty()
        return listed.filter { f -> f.isFile && allArtifactSuffixes.any { f.name.endsWith(it) } }.map { it.toPath() }
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
