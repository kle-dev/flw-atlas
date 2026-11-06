package com.flowable.atlas.explorer

import com.flowable.atlas.render.PlaywrightRenderer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The generated artifacts of a project as files: what [AtlasGeneratorService] calls them, and which of the
 * selected ones are on disk. The IDE used to know only the explorer pages, so a team that generates
 * CLAUDE.md, the graph and the Playwright guide but no page had no Regenerate and was never told those
 * were stale.
 */
object AtlasArtifactFiles {

    /** The name the generator derives for a project's output files: the analysed folder's name, last extension stripped. */
    fun projectName(projectDir: Path): String {
        val base = projectDir.toAbsolutePath().normalize().fileName?.toString().orEmpty()
        return base.substringBeforeLast('.', base).ifBlank { "project" }
    }

    /**
     * The files of the [selected] artifacts, other than explorer pages (those are found by
     * [AtlasExplorerFiles], wherever they were saved), that exist in [outputDir] under [projectDir] — the
     * diagrams folder counts as one. A few stat calls and no walk, so the Hub can ask on every refresh.
     */
    fun present(projectDir: Path, outputDir: String, selected: Set<AtlasArtifact>): List<Path> {
        val dir = runCatching { projectDir.resolve(outputDir) }.getOrNull() ?: return emptyList()
        val name = projectName(projectDir)
        val files = (selected - AtlasArtifact.EXPLORER_HTML).flatMap { a ->
            val main = dir.resolve(name + a.suffix)
            // The guide is one artifact in two files; the map is written with it.
            if (a == AtlasArtifact.PLAYWRIGHT_MD) listOf(main, dir.resolve("$name.${PlaywrightRenderer.MAP_SUFFIX}")) else listOf(main)
        }
        return files.filter { Files.exists(it) }
    }
}
