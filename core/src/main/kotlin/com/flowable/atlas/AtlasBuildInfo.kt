package com.flowable.atlas

/**
 * The Atlas build version, baked into `:core` at build time. `core/build.gradle.kts` generates
 * `/atlas-version.txt` (the Gradle `project.version`) onto the main resources; this reads it back so the
 * generated explorer HTML and the CLI can stamp which Atlas version produced their output. Falls back to
 * `"dev"` when the resource is absent (e.g. running :core tests straight from the IDE without the build
 * step). Both the plugin and the CLI share this same version because it flows from one Gradle `version`.
 */
object AtlasBuildInfo {

    val VERSION: String by lazy {
        AtlasBuildInfo::class.java.getResourceAsStream("/atlas-version.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: "dev"
    }
}
