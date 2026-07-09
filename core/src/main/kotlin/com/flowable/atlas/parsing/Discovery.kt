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

        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in ModelPaths.EXCLUDE_DIRS }
            .filter { it.isFile }
            .forEach { f ->
                val low = f.name.lowercase()
                when {
                    low.endsWith(".java") -> javas.add(f)
                    ModelPaths.isArchive(low) -> archives.add(f)
                    ModelKinds.modelTypeFor(f.name) != null -> models.add(f)
                    low.endsWith(".xml") || low.endsWith(".sql") -> xmls.add(f)  // liquibase candidates
                }
            }
        return Discovered(models, archives, javas, xmls)
    }
}
