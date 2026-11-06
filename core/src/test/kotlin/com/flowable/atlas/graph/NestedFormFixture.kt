package com.flowable.atlas.graph

/**
 * A form whose panels nest [json]'s `depth` levels deep: every level holds [FIELDS_PER_LEVEL] required text
 * fields and one panel, gated by [CONDITION], around the next level. It is the shape that made 0.30.0's
 * graph.json balloon — each field carried a copy of every enclosing panel's conditions — and the tests
 * that keep that from coming back build it at two depths and compare.
 */
internal object NestedFormFixture {

    /** The gate on every panel; long on purpose, so a per-field copy of it is measurable. */
    const val CONDITION = "{{caseStatus == 'DEMO-OPEN' && userRole != 'DEMO-viewer' && !readonlyMode}}"

    const val FIELDS_PER_LEVEL = 40

    /** The `.form` JSON: `depth` panels (`panel0` outermost) and `(depth + 1) × 40` fields. */
    fun json(depth: Int, key: String = "DEMO-deep"): String {
        fun level(d: Int): String {
            val fields = (0 until FIELDS_PER_LEVEL).joinToString(",") {
                """{"cols": [{"id": "f${d}_$it", "type": "text", "label": "F$it", "isRequired": true}]}"""
            }
            val inner = if (d == depth) "" else
                """,{"cols": [{"id": "panel$d", "type": "panel", "visible": "$CONDITION", "extraSettings": {"layoutDefinition": {"rows": [${level(d + 1)}]}}}]}"""
            return fields + inner
        }
        return """{"metadata": {"key": "$key", "name": "Deep"}, "rows": [${level(0)}]}"""
    }
}
