package com.flowable.atlas.explorer

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The Flowable Atlas generator (`flowable_atlas.py`) is shipped verbatim as a plugin resource
 * (bundled from the repo-root script at build time — see build.gradle.kts). To run it we extract
 * it to a stable temp location so a `python3` process can execute it.
 *
 * Extraction is idempotent and cheap (~200 KB); we overwrite on every run so the extracted copy
 * always matches the bundled one after a plugin upgrade.
 */
object AtlasScript {

    private const val RESOURCE = "/atlas/flowable_atlas.py"

    /** True when the generator script is bundled in this plugin build. */
    fun isBundled(): Boolean = AtlasScript::class.java.getResource(RESOURCE) != null

    /** Extract the bundled generator to a temp file and return its path, or null if not bundled. */
    fun extractToTemp(): Path? {
        val input = AtlasScript::class.java.getResourceAsStream(RESOURCE) ?: return null
        val dir = Path.of(PathManager.getTempPath(), "flowable-atlas")
        Files.createDirectories(dir)
        val target = dir.resolve("flowable_atlas.py")
        input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target
    }
}
