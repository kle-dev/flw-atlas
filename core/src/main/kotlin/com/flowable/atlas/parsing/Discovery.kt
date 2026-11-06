package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelPaths
import java.io.File

/**
 * Walks a project directory (or classifies a single archive) into the file lists Atlas analyses —
 * a port of `flowable_atlas.py` `discover` (~line 1250). Build-output directories ([ModelPaths.EXCLUDE_DIRS])
 * are pruned; the rest are bucketed into Flowable models, `.zip`/`.bar` archives, `.java` and `.kt`
 * sources (one parser reads both — see [JavaParser]), and `.xml`/`.sql` Liquibase-changelog candidates.
 */
object Discovery {

    data class Discovered(
        val models: List<File>,
        val archives: List<File>,
        val javas: List<File>,
        val xmls: List<File>,
        /** A template's body and attachment metadata: `.tplvariation`, `.tplfile-metadata` — parts of a
         *  `.tpl` model, not models of their own (see [isTemplatePart]). */
        val templateParts: List<File> = emptyList(),
        /** Spring Boot configuration — `application*.properties|yml` — outside the test source sets. */
        val configs: List<File> = emptyList(),
        /** User definitions and tenant setups (see [com.flowable.atlas.graph.IdentitySetup]). */
        val identity: List<File> = emptyList(),
        /** Java and Kotlin under a test source set — read only for the models they deploy. */
        val testSources: List<File> = emptyList(),
        /** Symbolic links whose target lies outside the project — not followed (see [FileWalk.files]). */
        val outsideLinks: List<File> = emptyList(),
    )

    /** A file that belongs to a template model without being one: the variation that holds the body a
     *  BAR keeps outside the `.tpl`, and the metadata naming an attached file. */
    fun isTemplatePart(lowerName: String): Boolean =
        lowerName.endsWith(".tplvariation") || lowerName.endsWith(".tplfile-metadata") || lowerName.endsWith(".templatefilemetadata")

    fun discover(root: File): Discovered {
        val models = ArrayList<File>()
        val archives = ArrayList<File>()
        val javas = ArrayList<File>()
        val xmls = ArrayList<File>()
        val templateParts = ArrayList<File>()
        val configs = ArrayList<File>()
        val identity = ArrayList<File>()
        val testSources = ArrayList<File>()
        val outsideLinks = ArrayList<File>()

        if (root.isFile) {
            if (ModelPaths.isArchive(root.name)) archives.add(root)
            return Discovered(models, archives, javas, xmls)
        }

        // The list a file goes into, or null for a file Atlas does not read.
        fun bucketOf(f: File): MutableList<File>? {
            val low = f.name.lowercase()
            val rel by lazy { f.relativeTo(root).invariantSeparatorsPath }
            return when {
                // Test code is not the project (see ModelPaths.isTestSource); models under a test
                // source set still are, which is why this is a per-file rule and not a pruned directory.
                low.endsWith(".java") || low.endsWith(".kt") -> if (!ModelPaths.isTestSource(rel)) javas else testSources
                ModelPaths.isArchive(low) -> archives
                isTemplatePart(low) -> templateParts
                ModelKinds.modelTypeFor(f.name) != null -> models
                // legacy Design-workspace layout: per-model JSON wrappers inside `<type>-models/`
                low.endsWith(".json") &&
                    com.flowable.atlas.model.ModelType.byDesignFolder(f.parentFile?.name) != null -> models
                com.flowable.atlas.graph.IdentitySetup.isIdentityFile(rel) -> if (!ModelPaths.isTestSource(rel)) identity else null
                low.endsWith(".xml") || low.endsWith(".sql") -> xmls  // liquibase candidates
                SpringProperties.isConfigFile(f.name) -> if (!ModelPaths.isTestSource(rel)) configs else null
                else -> null
            }
        }

        // FileWalk, not walkTopDown(): the discovery order propagates into every insertion-ordered map
        // downstream, so a filesystem-dependent order made the generated artifacts machine-dependent.
        FileWalk.files(root, onLeave = { link ->
            // Reported only where the report loses something: a folder, which may hold anything, or a file
            // Atlas would have read. A sub-project's `.editorconfig -> ../../.editorconfig` was never part
            // of it, and a warning per such link would teach readers to skim the list.
            if (link.isDirectory || bucketOf(link) != null) outsideLinks.add(link)
        }) { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }
            .forEach { f -> bucketOf(f)?.add(f) }
        return Discovered(models, archives, javas, xmls, templateParts, configs, identity, testSources, outsideLinks)
    }
}
