package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelPaths
import java.io.File

/**
 * Walks a project directory (or classifies a single archive) into the file lists Atlas analyses —
 * a port of `flowable_atlas.py` `discover` (~line 1250). Build-output directories ([ModelPaths.EXCLUDE_DIRS])
 * are pruned; the rest are bucketed into Flowable models, `.zip`/`.bar` archives, `.java` sources, and
 * `.xml`/`.sql` Liquibase-changelog candidates.
 */
object Discovery {

    data class Discovered(
        val models: List<File>,
        val archives: List<File>,
        val javas: List<File>,
        val xmls: List<File>,
    )

    fun discover(root: File): Discovered {
        val models = ArrayList<File>()
        val archives = ArrayList<File>()
        val javas = ArrayList<File>()
        val xmls = ArrayList<File>()

        if (root.isFile) {
            if (ModelPaths.isArchive(root.name)) archives.add(root)
            return Discovered(models, archives, javas, xmls)
        }

        // FileWalk, not walkTopDown(): the discovery order propagates into every insertion-ordered map
        // downstream, so a filesystem-dependent order made the generated artifacts machine-dependent.
        FileWalk.files(root) { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }
            .forEach { f ->
                val low = f.name.lowercase()
                when {
                    // Test code is not the project (see ModelPaths.isTestSource); models under a test
                    // source set still are, which is why this is a per-file rule and not a pruned directory.
                    low.endsWith(".java") ->
                        if (!ModelPaths.isTestSource(f.relativeTo(root).invariantSeparatorsPath)) javas.add(f)
                    ModelPaths.isArchive(low) -> archives.add(f)
                    ModelKinds.modelTypeFor(f.name) != null -> models.add(f)
                    // legacy Design-workspace layout: per-model JSON wrappers inside `<type>-models/`
                    low.endsWith(".json") &&
                        com.flowable.atlas.model.ModelType.byDesignFolder(f.parentFile?.name) != null ->
                        models.add(f)
                    low.endsWith(".xml") || low.endsWith(".sql") -> xmls.add(f)  // liquibase candidates
                }
            }
        return Discovered(models, archives, javas, xmls)
    }
}
