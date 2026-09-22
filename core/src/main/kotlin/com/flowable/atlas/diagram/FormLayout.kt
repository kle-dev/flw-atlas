package com.flowable.atlas.diagram

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.parsing.OryxFormReader

/**
 * The grid a `.form` / `.page` lays its components out on: rows of cells, each cell spanning [Cell.size]
 * of twelve columns, the way Design's editor and the Work runtime place them. The graph's form parser
 * walks the same document flat (every component, no positions); this keeps the positions, for
 * [FormSvgRenderer].
 *
 * Containers carry their own grids: a panel or modal one under `extraSettings.layoutDefinition`, tabs and
 * accordions one per `extraSettings.sections[]`. A data table's `columns` are its header, not
 * components; a subform is a reference, drawn as one.
 */
data class FormLayout(val title: String?, val rows: List<Row>) {

    data class Row(val cells: List<Cell>)

    data class Cell(
        val id: String,
        val type: String,
        val label: String?,
        /** Columns out of twelve. */
        val size: Int,
        val required: Boolean,
        /** `false` or the expression it depends on; null when the component always renders. */
        val visible: Any?,
        /** `false` or the expression it depends on; null when the component is always enabled. */
        val enabled: Any?,
        val value: String?,
        val subform: String?,
        /** A data table's column headers. */
        val columns: List<String>,
        /** A container's grids: one untitled section for a panel, one per tab or accordion section. */
        val sections: List<Section>,
    )

    data class Section(val label: String?, val layout: FormLayout)

    companion object {
        private const val GRID = 12

        /**
         * The layout of a form/page document, or null when [bytes] is not one. Takes a modern body
         * (`rows[].cols[]`), a Design workspace wrapper (`editorJson`, as a string or an object), and
         * the legacy Oryx body, whose components carry no positions and so get a full-width row each.
         */
        fun parse(bytes: ByteArray): FormLayout? {
            var doc = runCatching { MiniJson.parse(String(bytes, Charsets.UTF_8)) }.getOrNull() as? Map<*, *> ?: return null
            val title = (map(doc["metadata"])?.get("name") ?: doc["name"]) as? String
            when (val editor = doc["editorJson"]) {
                is String -> doc = runCatching { MiniJson.parse(editor) }.getOrNull() as? Map<*, *> ?: return null
                is Map<*, *> -> doc = editor
            }
            val body = typed(doc)
            if (OryxFormReader.isOryx(body)) {
                val components = OryxFormReader.toModern(body)["components"] as? List<*> ?: return null
                return FormLayout(title, oryxRows(components))
            }
            val rows = body["rows"] as? List<*> ?: return null
            return FormLayout(title, rows(rows))
        }

        private fun rows(rows: List<*>): List<Row> = rows.mapNotNull { row ->
            // Design writes `{cols: [...]}`; a hand-written model may give the cells as a bare list.
            val cols = when (row) {
                is Map<*, *> -> row["cols"] as? List<*>
                is List<*> -> row
                else -> null
            }.orEmpty().mapNotNull { map(it) }
            if (cols.isEmpty()) null else Row(cols.map { cell(it, GRID / cols.size) })
        }

        private fun oryxRows(components: List<*>): List<Row> = components.mapNotNull { map(it) }.map { n ->
            val kids = n["components"] as? List<*>
            val c = cell(n, GRID)
            Row(listOf(if (kids.isNullOrEmpty()) c else c.copy(sections = listOf(Section(null, FormLayout(null, oryxRows(kids)))))))
        }

        private fun cell(n: Map<String, Any?>, fallbackSize: Int): Cell {
            val es = map(n["extraSettings"]).orEmpty()
            val gating = ModelParsers.gatingOf(n).orEmpty()
            return Cell(
                id = n["id"]?.toString().orEmpty(),
                type = n["type"]?.toString().orEmpty(),
                label = ModelParsers.componentCaption(n)?.toString(),
                size = ((n["size"] as? Number)?.toInt() ?: fallbackSize).coerceIn(1, GRID),
                required = n["isRequired"] == true,
                visible = gating["visible"],
                enabled = gating["enabled"],
                value = n["value"] as? String,
                subform = es["formRef"]?.let(ModelParsers::modelRefKey)?.toString(),
                columns = (es["columns"] as? List<*>).orEmpty().mapNotNull { col ->
                    map(col)?.let { (it["label"] ?: it["id"])?.toString() }
                },
                sections = sections(es),
            )
        }

        private fun sections(es: Map<String, Any?>): List<Section> {
            map(es["layoutDefinition"])?.let { def ->
                return listOf(Section(null, FormLayout(null, rows(def["rows"] as? List<*> ?: emptyList<Any>()))))
            }
            return (es["sections"] as? List<*>).orEmpty().mapNotNull { map(it) }.map { s ->
                val grid = map(map(s["extraSettings"])?.get("layoutDefinition"))?.get("rows") as? List<*>
                Section(ModelParsers.componentCaption(s)?.toString(), FormLayout(null, rows(grid.orEmpty())))
            }
        }

        private fun map(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let(::typed)

        @Suppress("UNCHECKED_CAST")
        private fun typed(m: Map<*, *>): Map<String, Any?> = m as Map<String, Any?>
    }
}
