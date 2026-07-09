package com.flowable.atlas.model

import com.flowable.atlas.parsing.DataObjectInfo
import com.flowable.atlas.parsing.ModelJsonReader
import com.flowable.atlas.parsing.OperationInfo
import com.flowable.atlas.parsing.ServiceTable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile

/**
 * Thin `VirtualFile → bytes` adapter over the pure `:core` [ModelJsonReader]. The actual JSON model
 * reading logic lives in `:core` (shared with the CLI/graph engine); this only reads the file's bytes
 * (logging + swallowing read failures) so on-demand index callers can pass a [VirtualFile].
 */
object JsonUtil {

    private val LOG = logger<JsonUtil>()

    private fun bytesOf(file: VirtualFile): ByteArray? =
        try {
            file.contentsToByteArray()
        } catch (e: Exception) {
            LOG.debug("unreadable model json ${file.path}", e)
            null
        }

    /** Value of a top-level string field (e.g. `referencedServiceDefinitionModelKey`). */
    fun topLevelString(file: VirtualFile, field: String): String? =
        bytesOf(file)?.let { ModelJsonReader.topLevelString(it, field) }

    /** Operations (with input parameters) of a `.service` model. */
    fun readOperations(file: VirtualFile): List<OperationInfo> =
        bytesOf(file)?.let { ModelJsonReader.readOperations(it) } ?: emptyList()

    /** Physical-table mapping of a `.service` model, or null if unreadable / not a table service. */
    fun readServiceTable(file: VirtualFile): ServiceTable? =
        bytesOf(file)?.let { ModelJsonReader.readServiceTable(it) }

    /** Logical field mapping of a `.data` model, or null if unreadable. */
    fun readDataObject(file: VirtualFile): DataObjectInfo? =
        bytesOf(file)?.let { ModelJsonReader.readDataObject(it) }
}
