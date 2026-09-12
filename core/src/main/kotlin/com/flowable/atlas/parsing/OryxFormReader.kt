package com.flowable.atlas.parsing

/**
 * Reads the legacy Design editor's form/page body — the Oryx shape: a tree of `childShapes`, each with a
 * `stencil.id`, a `resourceId` and a flat `properties` map of lower-cased, hyphenated keys — and rewrites
 * it into the component shape the current Design writes and [ModelParsers.parseForm] reads.
 *
 * Every Design-workspace export in the real projects (the JSON files in the `form-models` and
 * `page-models` folders) is this shape, and until now such a form was registered by key with no fields: 47 forms on the measured
 * projects, blank pages in the explorer, at least 25 false "unused form" findings (the subform that used
 * them was never read), and ~1 000 references — `form-ref`, `operationKey`, `table-key`,
 * `actiondefinitionkey` — that never reached the graph.
 *
 * Rewriting rather than parsing twice: `parseForm` knows what a component means — which key names a
 * form, which a data object, where a REST button keeps its endpoint — and that knowledge must not be
 * duplicated for a second spelling. The map below is only spelling: Oryx key → the key Design writes
 * today. The stencil → type table is read off real exports, where every component carries both its
 * `designInfo.stencilId` and its `type`.
 */
object OryxFormReader {

    /** `designInfo.stencilId` → `type`, as the current Design writes both on one component. */
    private val STENCIL_TYPE: Map<String, String> = mapOf(
        "cloud-text" to "text", "cloud-text-area" to "textarea", "cloud-checkbox" to "boolean",
        "cloud-single-select" to "select", "cloud-multi-select" to "select", "user-auto-complete" to "select",
        "group-auto-complete" to "select", "cloud-date" to "date", "cloud-integer-number" to "number",
        "cloud-float-number" to "number", "cloud-radiobuttons" to "radio", "cloud-output" to "htmlComponent",
        "cloud-html-component" to "htmlComponent", "cloud-rich-text" to "richText", "cloud-attachment" to "upload",
        "cloud-password" to "password", "cloud-string-list" to "stringList", "cloud-image" to "image",
        "cloud-galleries" to "galleries", "cloud-hline" to "hline", "cloud-link" to "link", "cloud-alert" to "alert",
        "cloud-validation-panel" to "validationPanel", "cloud-sub-form" to "subform", "cloud-datatable" to "dataTable",
        "data-object-datatable" to "dataTable", "data-object-select" to "dataObjectSelect", "SlotPanel" to "panel",
        "cloud-sub-panel" to "panel", "cloud-tab-panel" to "panel", "cloud-tabs" to "tabs", "cloud-modal-dialog" to "modal",
        "cloud-button-group" to "buttonGroup", "cloud-script-button" to "scriptButton", "cloud-rest-button" to "restButton",
        "work-action" to "workAction", "cloud-link-button" to "linkButton", "cloud-outcome-button" to "outcomeButton",
        "create-instance-button" to "createInstanceButton", "work-invoke-service" to "workInvokeService",
        "cloud-accordion" to "accordion", "cloud-wizard" to "wizard", "cloud-slider" to "slider", "cloud-switcher" to "switcher",
        "barcode-qr" to "barcodeQr", "cloud-iframe" to "iFrame", "cloud-pdf" to "pdfViewer", "cloud-checkbox-group" to "checkboxGroup",
    )

    /** Oryx property → the `extraSettings` key Design writes today. A `{totalCount, items}` list unwraps to its items. */
    private val SETTING_KEYS: Map<String, String> = mapOf(
        "form-ref" to "formRef",
        "rest-button-url" to "url", "rest-button-method" to "method", "rest-button-path" to "path",
        "rest-button-value-expression" to "valueExpression",
        "rest-button-response-payload-mapping" to "responsePayloadMapping",
        "rest-button-request-payload-mapping" to "sendPayloadMapping",
        "rest-button-header-property-mapping" to "headerPropertyMapping",
        "rest-button-auto-execute" to "autoExecute", "rest-button-execute-always" to "executeAlways",
        "rest-button-refresh-time" to "refreshTime",
        "button-script" to "script", "button-script-timer" to "timer", "button-text" to "text",
        "actiondefinitionkey" to "actionDefinitionKey", "sendpayloadmapping" to "sendPayloadMapping",
        "responsepayloadmapping" to "responsePayloadMapping", "send-full-payload" to "sendFullPayload",
        "map-full-response" to "mapFullResponse", "map-response-inside-scope" to "mapResponseInsideScope",
        "navigationurl" to "navigationUrl", "scopedefinitionid" to "scopeDefinitionId",
        "query-url" to "queryUrl", "lookup-url" to "lookupUrl", "query-params" to "queryParams", "path" to "path",
        "datasource" to "dataSource", "table-key" to "tableKey", "link-target" to "target",
        "options" to "options", "options-run" to "optionsExpression", "modelevents" to "events",
        "custom-validations" to "customValidations", "default-value" to "defaultValue",
        "data-object-data-table-view-form" to "dataObjectDataTableViewFormKey",
        "data-object-data-table-edit-form" to "dataObjectDataTableEditFormKey",
        "data-object-data-table-create-form" to "dataObjectDataTableCreateFormKey",
        "data-object-data-table-edit-operation" to "dataObjectDataTableEditOperationKey",
        "data-object-data-table-delete-operation" to "dataObjectDataTableDeleteOperationKey",
        "data-object-data-table-create-operation" to "dataObjectDataTableCreateOperationKey",
        "data-object-data-table-create-payload-mapping" to "dataObjectDataTableCreatePayloadMapping",
        "data-object-data-table-columns" to "columns",
        "method" to "method", "upload-url" to "uploadUrl", "download-url" to "downloadUrl",
    )

    /** Component-level keys; the Oryx spelling on the left. `visible`/`enabled`/`ignore` prefer their `-run` expression. */
    private val NODE_KEYS: Map<String, String> = mapOf(
        "value" to "value", "visible" to "visible", "enabled" to "enabled", "ignored" to "ignore",
        "required" to "isRequired", "description" to "description", "tooltip" to "tooltip", "placeholder" to "placeholder",
    )

    private val STENCILS_WITH_ROW_LINK = setOf("cloud-datatable", "base-datatable", "data-object-datatable")

    /** Whether a body is the legacy editor's shape. */
    fun isOryx(body: Map<String, Any?>): Boolean = body.containsKey("childShapes") || body.containsKey("stencil")

    /**
     * The modern-shaped document for an Oryx [body]: `outcomes` and `outcomevariablename` at the root,
     * the component tree under `components`. Identity (`metadata`) is the caller's to add — the wrapper
     * states it.
     */
    fun toModern(body: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val root = mapOf(body["properties"])
        items(root["outcomes"])?.let { oc ->
            out["outcomes"] = oc.map { o -> LinkedHashMap<String, Any?>(o).also { it.remove("\$\$hashKey") } }
        }
        str(root["outcomevariablename"])?.let { out["outcomevariablename"] = it }
        str(root["description"])?.let { out["description"] = it }
        out["components"] = children(body)
        return out
    }

    private fun children(shape: Map<String, Any?>): List<Map<String, Any?>> =
        (shape["childShapes"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { component(mapOf(it)) } }

    private fun component(shape: Map<String, Any?>): Map<String, Any?> {
        val props = mapOf(shape["properties"])
        val stencil = (shape["stencil"] as? Map<*, *>)?.get("id")?.toString()
        // `stencilid` is the palette component (`cloud-rest-button`); `stencil.id` only its family (`Button`)
        val stencilId = str(props["stencilid"])?.takeIf { it != "null" } ?: stencil
        val n = LinkedHashMap<String, Any?>()
        n["id"] = str(props["id"]) ?: shape["resourceId"]
        n["type"] = typeOf(stencilId, stencil)
        caption(props, "label")?.let { n["label"] = it }
        for ((from, to) in NODE_KEYS) {
            // `x-run` is the expression variant of `x` and wins when the model states one
            val v = str(props["$from-run"])?.takeIf { it.isNotBlank() } ?: props[from]
            if (v != null && v != "") n[to] = v
        }
        stencilId?.let { n["designInfo"] = linkedMapOf("stencilId" to it) }
        i18n(props)?.let { n["i18n"] = it }

        val es = LinkedHashMap<String, Any?>()
        for ((from, to) in SETTING_KEYS) {
            val v = props["$from-run"]?.let { r -> str(r)?.takeIf { it.isNotBlank() } } ?: props[from] ?: continue
            if (v == "" || v == "null") continue
            // a `{id, key}` model reference is the key; the id is the editor's, not the model's
            es[to] = items(v) ?: refKey(v) ?: v
        }
        caption(props, "button-text")?.let { es["text"] = it }
        // a data table's `url` is where a row click goes, not an endpoint; everything else's is the endpoint
        str(props["url"])?.takeIf { it.isNotBlank() }?.let { es[if (stencilId in STENCILS_WITH_ROW_LINK) "navigationUrl" else "url"] = it }
        // a link button's target is its value, where the current palette keeps it
        if (n["type"] == "linkButton" && n["value"] == null) str(props["link-button-url"])?.let { n["value"] = it }
        // the data-object select / table configuration object: the object it reads and the operation
        for (k in listOf("dataobjectselectsearch", "searchdataobjectconfiguration")) {
            val cfg = props[k] as? Map<*, *> ?: continue
            str(cfg["key"])?.let { es["dataObjectDefinitionKey"] = it; es["dataSource"] = "DataObject" }
            str(cfg["operationKey"])?.let { es["dataObjectOperationKey"] = it }
        }
        if (es.isNotEmpty()) n["extraSettings"] = es
        val kids = children(shape)
        if (kids.isNotEmpty()) n["components"] = kids
        return n
    }

    private fun typeOf(stencilId: String?, stencil: String?): String {
        STENCIL_TYPE[stencilId]?.let { return it }
        STENCIL_TYPE[stencil]?.let { return it }
        // a custom component keeps its palette name, camel-cased the way Design does (`kyc-debounce` → `kycDebounce`)
        val raw = stencilId ?: stencil ?: "component"
        return raw.removePrefix("cloud-").split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")
    }

    /** The caption under [key], or its first localised override (`label_en_us`, …). */
    private fun caption(props: Map<String, Any?>, key: String): String? {
        str(props[key])?.takeIf { it.isNotBlank() }?.let { return it }
        for ((k, v) in props) if (k.startsWith("${key}_") && !k.endsWith("_i18n-key")) str(v)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** `label_de_de` / `button-text_de_de` → the `i18n.<locale>` shape the current palette writes. */
    private fun i18n(props: Map<String, Any?>): Map<String, Any?>? {
        val out = LinkedHashMap<String, MutableMap<String, Any?>>()
        for ((k, v) in props) {
            val s = str(v)?.takeIf { it.isNotBlank() } ?: continue
            if (k.endsWith("_i18n-key")) continue
            val m = LOCALE_RE.matchEntire(k) ?: continue
            val (base, locale) = m.destructured
            when (base) {
                "label" -> out.getOrPut(locale) { LinkedHashMap() }["label"] = s
                "button-text" -> out.getOrPut(locale) { LinkedHashMap() }["extraSettings"] = linkedMapOf("text" to s)
            }
        }
        return out.ifEmpty { null }
    }

    private val LOCALE_RE = Regex("^(label|button-text)_([a-z]{2}_[a-z]{2})$")

    /** The `key` of a `{id, key}` model reference, or null when [v] is not one. */
    private fun refKey(v: Any?): String? {
        val m = v as? Map<*, *> ?: return null
        return if (m.keys.all { it == "id" || it == "key" }) str(m["key"]) else null
    }

    /** The `items` of an Oryx `{totalCount, items}` list, or null when [v] is not one. */
    private fun items(v: Any?): List<Map<String, Any?>>? {
        val m = v as? Map<*, *> ?: return null
        val items = m["items"] as? List<*> ?: return null
        return items.mapNotNull { it as? Map<*, *> }.map { mapOf(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapOf(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    private fun str(v: Any?): String? = v as? String
}
