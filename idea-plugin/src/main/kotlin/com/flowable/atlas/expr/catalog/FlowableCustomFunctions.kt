package com.flowable.atlas.expr.catalog

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-scoped, lazily-computed cache of the project's `externals.additionalData` custom functions
 * (see [CustomFunctionExtractor]). The extractor walks the project base dir once — skipping
 * node_modules/dist/target/… and bounded by file count/size — so semantic validation can consult it
 * cheaply on every expression. Call [refresh] to re-extract after the customization source changes.
 */
@Service(Service.Level.PROJECT)
class FlowableCustomFunctions(private val project: Project) {

    private val LOG = logger<FlowableCustomFunctions>()

    @Volatile private var computed = false
    @Volatile private var cached: CustomFunctionCatalog? = null

    fun catalog(): CustomFunctionCatalog? {
        if (!computed) {
            synchronized(this) {
                if (!computed) {
                    cached = project.basePath?.let {
                        // Warn, not debug: an empty catalog does not merely hide a feature, it makes the
                        // expression inspection report the project's own custom functions as unknown.
                        // A wrong warning in the editor must be traceable to its cause.
                        runCatching { CustomFunctionExtractor.extract(File(it)) }
                            .onFailure { e -> LOG.warn("Extracting custom functions from $it failed — project functions will be flagged as unknown", e) }
                            .getOrNull()
                    }
                    computed = true
                }
            }
        }
        return cached
    }

    fun refresh() {
        synchronized(this) { computed = false; cached = null }
    }

    /** Seed the catalog directly, bypassing the filesystem walk (the extractor reads real files,
     *  which the in-memory test VFS does not provide). */
    @org.jetbrains.annotations.TestOnly
    fun setForTest(catalog: CustomFunctionCatalog?) {
        synchronized(this) { cached = catalog; computed = true }
    }

    companion object {
        fun getInstance(project: Project): FlowableCustomFunctions =
            project.getService(FlowableCustomFunctions::class.java)
    }
}
