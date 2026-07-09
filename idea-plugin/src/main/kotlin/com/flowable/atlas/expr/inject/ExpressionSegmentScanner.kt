package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect

/** An expression body found inside a host string: [innerStart]..[innerEnd] (relative to the scanned
 *  text, delimiters excluded) and the [dialect] its delimiter implies. */
data class ExpressionSegment(val innerStart: Int, val innerEnd: Int, val dialect: ExpressionDialect)

/**
 * Finds the expression bodies embedded in a host string. Dialect is chosen strictly by delimiter:
 * `${…}` / `#{…}` → backend, `{{…}}` → frontend. A single host may carry several segments (e.g. a
 * BPMN documentation line or a form label with two `{{…}}`), so all matches are returned, sorted.
 *
 * The backend regex mirrors [com.flowable.atlas.parsing.ModelRefScanner]. Empty bodies (`${}`)
 * are skipped.
 */
object ExpressionSegmentScanner {

    private val BACKEND = Regex("[#$]\\{([^}]*)}")
    private val FRONTEND = Regex("\\{\\{(.*?)}}")

    fun scan(text: String, dialects: Set<ExpressionDialect>): List<ExpressionSegment> {
        val out = ArrayList<ExpressionSegment>()
        if (ExpressionDialect.BACKEND in dialects) collect(text, BACKEND, ExpressionDialect.BACKEND, out)
        if (ExpressionDialect.FRONTEND in dialects) collect(text, FRONTEND, ExpressionDialect.FRONTEND, out)
        return out.sortedBy { it.innerStart }
    }

    private fun collect(text: String, regex: Regex, dialect: ExpressionDialect, out: MutableList<ExpressionSegment>) {
        for (m in regex.findAll(text)) {
            val group = m.groups[1] ?: continue
            if (group.value.isEmpty()) continue
            out += ExpressionSegment(group.range.first, group.range.last + 1, dialect)
        }
    }
}
