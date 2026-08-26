package com.flowable.atlas.parsing

import com.flowable.atlas.model.MiniJson

/**
 * Reads the specific fields we need from Flowable model JSON files, using the dependency-free
 * [MiniJson] parser. Pure and byte-based (no I/O, no PSI, no IntelliJ): the plugin's `JsonUtil`
 * is a thin `VirtualFile → bytes` adapter over this, and [ModelMemberExtractor] calls it for the
 * JSON model kinds. Ported from `flowable_atlas.py`'s JSON model readers.
 */
object ModelJsonReader {

    private fun parseMap(bytes: ByteArray): Map<*, *>? =
        MiniJson.parseOrNull(String(bytes, Charsets.UTF_8)) as? Map<*, *>

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
    fun topLevelString(bytes: ByteArray, field: String): String? = str(parseMap(bytes), field)

    /**
     * Parse a `.service` model's operations, each with its input parameters. Shape:
     * `{ "operations": [ { "key", "name", "type", "inputParameters": [ { "name", "type", "required" } ] } ] }`.
     */
    fun readOperations(bytes: ByteArray): List<OperationInfo> {
        val root = parseMap(bytes) ?: return emptyList()
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

    /**
     * A **variable-binding** form field carries `id` + `type` + `label` (the same predicate as the
     * original `flowable_atlas` `parse_form`) — plain containers have no label, and their ids must not
     * leak into the variables vocabulary.
     *
     * Deliberately narrower than [isFormComponent]: this one gates the completion vocabulary, where a
     * button id like `rest-button2` would be noise.
     */
    fun isFormField(node: Map<*, *>): Boolean =
        node["id"] is String && node.containsKey("type") && node.containsKey("label")

    /**
     * Every button flavour of the Design palettes, by the `type` a deployed `.form` / `.page` carries
     * (the authoritative list is the platform's `DefaultFormComponentType`). A button is the one
     * component that acts on its own — it calls an action, a service, a URL — so [isFormComponent]
     * accepts it on its `type` alone, without insisting on a caption.
     */
    val BUTTON_TYPES: Set<String> = setOf(
        "workAction", "restButton", "scriptButton", "outcomeButton", "linkButton",
        "workAgentButton", "workInvokeService", "createInstanceButton", "workUserEventListenerButton",
        // legacy / hand-written models call a plain button just that
        "button",
    )

    /**
     * A form/page **component** worth listing and searching: `id` + `type` and something to call it by
     * — or a button, which counts on its `type` alone.
     *
     * Buttons are the reason this is wider than [isFormField]. Their caption lives in
     * `extraSettings.text`, not `label`, so a `label`-only predicate dropped them silently. And plenty
     * carry neither: measured over one real project, half the REST buttons (50 of 87) and every link
     * button are icon-only or captioned by `value`, so *they* were dropped in turn — id, endpoint and
     * the action they invoke never reached the model data. Containers still need a caption, so they
     * stay out.
     */
    fun isFormComponent(node: Map<*, *>): Boolean =
        node["id"] is String && node.containsKey("type") &&
            (node.containsKey("label") || (node["extraSettings"] as? Map<*, *>)?.get("text") != null ||
                node["type"] in BUTTON_TYPES)

    /** Form field ids + outcomes of a `.form` model (used to build member vocabularies). */
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
                if (isFormField(node)) (node["id"] as? String)?.let { fields.add(it) }
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
    fun readServiceTable(bytes: ByteArray): ServiceTable? {
        val root = parseMap(bytes) ?: return null
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
     * The model keys an `.app` lists as its members. Shape:
     * `{ "extension": { "design": { "childModels": [ { "key", "type" } ] } } }` — the same list
     * [ModelParsers.parseApp] turns into `contains` edges. [type] filters on the child's `type`
     * (`"dataObject"`, `"bpmn"`, …); null returns every child key. Pages listed only under
     * `pageModels` are deliberately not included — this is the child-model list, not the page list.
     */
    fun readAppChildKeys(bytes: ByteArray, type: String? = null): List<String> {
        val root = parseMap(bytes) ?: return emptyList()
        val design = (root["extension"] as? Map<*, *>)?.get("design") as? Map<*, *> ?: return emptyList()
        val children = design["childModels"] as? List<*> ?: return emptyList()
        return children.mapNotNull { cAny ->
            val child = cAny as? Map<*, *> ?: return@mapNotNull null
            if (type != null && str(child, "type") != type) return@mapNotNull null
            str(child, "key")
        }
    }

    /**
     * The logical field mapping of a `.data` model. Shape:
     * `{ "key", "dataObjectType", "referencedServiceDefinitionModelKey",
     *    "fieldMappings": [ { "name", "type", "label" } ] }`.
     */
    fun readDataObject(bytes: ByteArray): DataObjectInfo? {
        val root = parseMap(bytes) ?: return null
        val key = str(root, "key") ?: return null
        val fields = (root["fieldMappings"] as? List<*>).orEmpty().mapNotNull { fAny ->
            val f = fAny as? Map<*, *> ?: return@mapNotNull null
            val name = f["name"] as? String ?: return@mapNotNull null
            DataField(name, f["type"] as? String)
        } + ((root["variables"] as? Map<*, *>).orEmpty().keys.mapNotNull { it as? String }
            // masterData-shaped data objects keep their fields in a `variables` map
            .map { DataField(it, null) })
        return DataObjectInfo(
            key = key,
            dataObjectType = str(root, "dataObjectType"),
            referencedServiceDefinitionModelKey = str(root, "referencedServiceDefinitionModelKey"),
            fieldMappings = fields,
        )
    }
}
