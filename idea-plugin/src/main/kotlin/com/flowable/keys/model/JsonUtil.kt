package com.flowable.keys.model

import com.flowable.keys.index.ColumnMapping
import com.flowable.keys.index.DataObjectInfo
import com.flowable.keys.index.OperationInfo
import com.flowable.keys.index.ParamInfo
import com.flowable.keys.index.RawModel
import com.flowable.keys.index.ServiceTable
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads the specific fields we need from Flowable model JSON files, using the dependency-free
 * [MiniJson] parser. Text-based (no PSI), safe to call from the indexer, cheap for small files.
 */
object JsonUtil {

    private fun parseMap(bytes: ByteArray): Map<*, *>? =
        MiniJson.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *>

    private fun parseMap(file: VirtualFile): Map<*, *>? =
        try { parseMap(file.contentsToByteArray()) } catch (e: Exception) { null }

    private fun str(map: Map<*, *>?, field: String): String? = map?.get(field) as? String

    /**
     * Extract a model's key + name. Ordered probe covering both representations: top-level
     * `key`/`name` (Design workspace JSON, `.action`, `.data`, `.service`, …) then
     * `metadata.key`/`metadata.name` (deployment `.form`, `.page`).
     */
    fun extractKeyName(bytes: ByteArray): RawModel? {
        val root = parseMap(bytes) ?: return null
        str(root, "key")?.let { return RawModel(it, str(root, "name")) }
        val meta = root["metadata"] as? Map<*, *>
        str(meta, "key")?.let { return RawModel(it, str(meta, "name")) }
        return null
    }

    /** Value of a top-level string field (e.g. `referencedServiceDefinitionModelKey`). */
    fun topLevelString(file: VirtualFile, field: String): String? = str(parseMap(file), field)

    /**
     * Parse a `.service` model's operations, each with its input parameters. Shape:
     * `{ "operations": [ { "key", "name", "type", "inputParameters": [ { "name", "type", "required" } ] } ] }`.
     */
    fun readOperations(file: VirtualFile): List<OperationInfo> {
        val root = parseMap(file) ?: return emptyList()
        val ops = root["operations"] as? List<*> ?: return emptyList()
        return ops.mapNotNull { opAny ->
            val op = opAny as? Map<*, *> ?: return@mapNotNull null
            val opKey = op["key"] as? String ?: return@mapNotNull null
            val params = (op["inputParameters"] as? List<*>).orEmpty().mapNotNull { pAny ->
                val p = pAny as? Map<*, *> ?: return@mapNotNull null
                val name = p["name"] as? String ?: return@mapNotNull null
                ParamInfo(name, p["type"] as? String, (p["required"] as? Boolean) ?: false)
            }
            OperationInfo(opKey, op["name"] as? String, op["type"] as? String, params)
        }
    }

    /** Form field ids + outcomes of a `.form` model (used to build member vocabularies). */
    data class FormInfo(val fields: List<String>, val outcomes: List<String>)

    fun readForm(bytes: ByteArray): FormInfo {
        val root = parseMap(bytes) ?: return FormInfo(emptyList(), emptyList())
        val fields = LinkedHashSet<String>()
        val outcomes = LinkedHashSet<String>()
        collectForm(root, fields, outcomes)
        return FormInfo(fields.toList(), outcomes.toList())
    }

    private fun collectForm(node: Any?, fields: MutableSet<String>, outcomes: MutableSet<String>) {
        when (node) {
            is Map<*, *> -> {
                val id = node["id"] as? String
                if (id != null && node["type"] != null) fields.add(id)
                (node["outcomes"] as? List<*>)?.forEach { o ->
                    when (o) {
                        is String -> outcomes.add(o)
                        is Map<*, *> -> ((o["name"] ?: o["value"]) as? String)?.let { outcomes.add(it) }
                    }
                }
                node.values.forEach { collectForm(it, fields, outcomes) }
            }
            is List<*> -> node.forEach { collectForm(it, fields, outcomes) }
        }
    }

    /** Event payload parameter names of an `.event` model. */
    fun readEventPayload(bytes: ByteArray): List<String> {
        val root = parseMap(bytes) ?: return emptyList()
        val payload = root["payload"] as? List<*> ?: return emptyList()
        return payload.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
    }

    /**
     * The physical-table mapping of a `.service` model (Service Registry). Shape:
     * `{ "key", "type", "tableName", "referenceKey", "referencedLiquibaseModelKey",
     *    "columnMappings": [ { "name", "columnName", "type" } ] }`.
     */
    fun readServiceTable(file: VirtualFile): ServiceTable? = readServiceTable(parseMap(file))

    fun readServiceTable(bytes: ByteArray): ServiceTable? = readServiceTable(parseMap(bytes))

    private fun readServiceTable(root: Map<*, *>?): ServiceTable? {
        root ?: return null
        val key = str(root, "key") ?: return null
        val columns = (root["columnMappings"] as? List<*>).orEmpty().mapNotNull { cAny ->
            val c = cAny as? Map<*, *> ?: return@mapNotNull null
            ColumnMapping(c["name"] as? String, c["columnName"] as? String, c["type"] as? String)
        }
        return ServiceTable(
            key = key,
            type = str(root, "type"),
            tableName = str(root, "tableName"),
            referenceKey = str(root, "referenceKey"),
            referencedLiquibaseModelKey = str(root, "referencedLiquibaseModelKey"),
            columns = columns,
        )
    }

    /**
     * The logical field mapping of a `.data` model. Shape:
     * `{ "key", "dataObjectType", "referencedServiceDefinitionModelKey",
     *    "fieldMappings": [ { "name", "type", "label" } ] }`.
     */
    fun readDataObject(file: VirtualFile): DataObjectInfo? = readDataObject(parseMap(file))

    fun readDataObject(bytes: ByteArray): DataObjectInfo? = readDataObject(parseMap(bytes))

    private fun readDataObject(root: Map<*, *>?): DataObjectInfo? {
        root ?: return null
        val key = str(root, "key") ?: return null
        val fields = (root["fieldMappings"] as? List<*>).orEmpty().mapNotNull { fAny ->
            (fAny as? Map<*, *>)?.get("name") as? String
        }
        return DataObjectInfo(
            key = key,
            dataObjectType = str(root, "dataObjectType"),
            referencedServiceDefinitionModelKey = str(root, "referencedServiceDefinitionModelKey"),
            fields = fields,
        )
    }
}
