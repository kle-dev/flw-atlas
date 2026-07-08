package com.flowable.atlas.expr.catalog

import com.intellij.openapi.components.Service
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

    @Volatile private var computed = false
    @Volatile private var cached: CustomFunctionCatalog? = null

    fun catalog(): CustomFunctionCatalog? {
        if (!computed) {
            synchronized(this) {
                if (!computed) {
                    cached = project.basePath?.let {
                        runCatching { CustomFunctionExtractor.extract(File(it)) }.getOrNull()
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

    companion object {
        fun getInstance(project: Project): FlowableCustomFunctions =
            project.getService(FlowableCustomFunctions::class.java)
    }
}
