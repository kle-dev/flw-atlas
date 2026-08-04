package com.flowable.atlas.diagram

import com.flowable.atlas.parsing.AtlasXml
import kotlin.math.max
import kotlin.math.min

/**
 * Paints a DMN **decision table** to a standalone SVG — the fallback for the one diagram-bearing model
 * type that usually carries no diagram at all.
 *
 * A Flowable Design decision table has no canvas and therefore no `dmndi` layout, so [DiagramRenderer]
 * (which draws DI) has nothing to draw for it: only a *decision requirement diagram* (DRD, with
 * `inputData`/`informationRequirement` shapes) does. Yet the table itself is exactly what a reader wants
 * to see when they click a decision key. So this renders the table the way Design shows it — hit policy,
 * an input band and an output band, one row per rule, annotations last — from the `<decisionTable>`
 * markup that every decision model does carry.
 *
 * Deliberately kept out of [DiagramRenderer.renderSvg]: that feeds the Atlas explorer payload and the
 * `Diagrams (SVG)` artifacts, and the explorer already renders a decision's rules as a real (searchable,
 * themed) HTML table. This is used where there is no such alternative — the IDE's diagram gutter icon.
 *
 * Output is deterministic (source order, locale-free numbers) and draws its own white background, so it
 * is safe for byte-comparison tests and legible in any viewer theme.
 */
object DmnTableSvgRenderer {

    private const val FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"
    private const val MONO = "'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace"
    private const val FONT_SIZE = 12.0
    private const val PAD = 20.0
    private const val CELL_PAD = 8.0
    private const val ROW_H = 26.0
    private const val HEAD_H = 30.0
    private const val BAND_H = 20.0
    private const val TITLE_H = 34.0
    private const val CHAR_W = 6.6                 // ~average advance of the 12px UI font
    private const val MIN_COL = 70.0
    private const val MAX_COL = 260.0
    private const val TABLE_GAP = 28.0

    private const val STROKE = "#cfd8dc"
    private const val STROKE_STRONG = "#90a4ae"
    private const val TEXT = "#1f2933"
    private const val MUTED = "#607d8b"
    private const val INPUT_FILL = "#eceff1"
    private const val OUTPUT_FILL = "#e8f0e9"
    private const val ANNO_FILL = "#f7f9fa"
    private const val ROW_ALT = "#fafbfc"

    /** Longest cell text kept; a whole FEEL expression in one cell would otherwise set the page width. */
    private const val MAX_CELL_CHARS = 44

    /** Rules drawn per table — a 2000-rule table is unreadable as an image; the rest is reported. */
    private const val MAX_ROWS = 200

    /**
     * Render every decision table in a `.dmn` document to one SVG, or null when the document carries no
     * decision table (a pure DRD — [DiagramRenderer] draws that from its `dmndi` layout instead).
     */
    fun renderSvg(bytes: ByteArray): String? {
        val tables = runCatching { parse(bytes) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val laidOut = tables.map { layout(it) }
        val width = laidOut.maxOf { it.width } + 2 * PAD
        val height = laidOut.sumOf { it.height } + TABLE_GAP * (laidOut.size - 1) + 2 * PAD

        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(width)}" height="${fmt(height)}" """ +
                """viewBox="0 0 ${fmt(width)} ${fmt(height)}">""",
        )
        sb.append("""<rect width="${fmt(width)}" height="${fmt(height)}" fill="#ffffff"/>""")
        var y = PAD
        for (t in laidOut) {
            draw(sb, t, PAD, y)
            y += t.height + TABLE_GAP
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- model -------------------------------------------------------------------------------

    /** A column header: its label plus the expression (input) or output name it stands for. */
    private class Column(val label: String, val detail: String?)

    private class Rule(val cells: List<String>, val annotation: String?)

    private class Table(
        val title: String,
        val hitPolicy: String,
        val inputs: List<Column>,
        val outputs: List<Column>,
        val rules: List<Rule>,
        val totalRules: Int,
    )

    private fun parse(bytes: ByteArray): List<Table> {
        val root = AtlasXml.parse(bytes)
        val out = ArrayList<Table>()
        for (dec in root.iter("decision")) {
            val dt = dec.findDescendant("decisionTable") ?: continue
            val inputs = dt.findChildren("input").map { inp ->
                val expr = inp.textOfDescendant("text")
                val label = inp.attr("label") ?: expr ?: "Input"
                // The expression is the interesting part; repeat it only when the label hides it.
                Column(label, expr?.takeIf { it != label })
            }
            val outputs = dt.findChildren("output").map { outp ->
                val name = outp.attr("name")
                val label = outp.attr("label") ?: name ?: "Output"
                Column(label, name?.takeIf { it != label })
            }
            val ruleEls = dt.findChildren("rule")
            val rules = ruleEls.take(MAX_ROWS).map { r ->
                val ins = r.findChildren("inputEntry").map { it.textOfDescendant("text").orEmpty() }
                val outsCells = r.findChildren("outputEntry").map { it.textOfDescendant("text").orEmpty() }
                // Pad to the header width so a rule with missing entries stays column-aligned.
                val cells = pad(ins, inputs.size) + pad(outsCells, outputs.size)
                Rule(cells, r.textOfDescendant("description"))
            }
            out.add(
                Table(
                    title = dec.attr("name") ?: dec.attr("id") ?: "Decision",
                    hitPolicy = dt.attr("hitPolicy") ?: "UNIQUE",
                    inputs = inputs,
                    outputs = outputs,
                    rules = rules,
                    totalRules = ruleEls.size,
                ),
            )
        }
        return out
    }

    private fun pad(cells: List<String>, size: Int): List<String> =
        if (cells.size >= size) cells.take(size) else cells + List(size - cells.size) { "" }

    // ---- layout ------------------------------------------------------------------------------

    private class Laid(
        val table: Table,
        val colWidths: List<Double>,
        val hasAnnotations: Boolean,
        val annoWidth: Double,
        val width: Double,
        val height: Double,
    ) {
        val footer: String? = if (table.totalRules > table.rules.size) {
            "… ${table.totalRules - table.rules.size} more rules (showing the first ${table.rules.size} of ${table.totalRules})"
        } else {
            null
        }
    }

    private fun layout(t: Table): Laid {
        val cols = t.inputs.size + t.outputs.size
        val headers = t.inputs + t.outputs
        val widths = (0 until cols).map { i ->
            val header = maxOf(headers[i].label.length, headers[i].detail?.length ?: 0)
            val widest = t.rules.maxOfOrNull { clip(it.cells.getOrElse(i) { "" }).length } ?: 0
            colWidth(max(header, widest))
        }
        val hasAnno = t.rules.any { !it.annotation.isNullOrBlank() }
        val annoW = if (!hasAnno) 0.0 else colWidth(
            max(10, t.rules.maxOf { clip(it.annotation.orEmpty()).length }),
        )
        // The row-number gutter keeps rules identifiable when talking about "rule 3".
        val width = ROW_NUM_W + widths.sum() + annoW
        val rows = ROW_H * t.rules.size
        val footerH = if (t.totalRules > t.rules.size) 18.0 else 0.0
        return Laid(t, widths, hasAnno, annoW, width, TITLE_H + BAND_H + HEAD_H + rows + footerH)
    }

    private const val ROW_NUM_W = 34.0

    private fun colWidth(chars: Int): Double =
        min(MAX_COL, max(MIN_COL, chars * CHAR_W + 2 * CELL_PAD))

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= MAX_CELL_CHARS) one else one.take(MAX_CELL_CHARS - 1) + "…"
    }

    // ---- painting ----------------------------------------------------------------------------

    private fun draw(sb: StringBuilder, l: Laid, x0: Double, y0: Double) {
        val t = l.table
        sb.append(text(x0, y0 + 16.0, t.title, size = 14.0, weight = "600"))
        sb.append(text(x0 + t.title.length * 7.6 + 12.0, y0 + 16.0, "Hit policy: ${t.hitPolicy}", fill = MUTED))

        val top = y0 + TITLE_H
        val bandY = top
        val headY = top + BAND_H
        val bodyY = headY + HEAD_H
        val bodyH = ROW_H * t.rules.size

        // Column x positions: row-number gutter, inputs, outputs, annotations.
        val xs = ArrayList<Double>(l.colWidths.size + 1)
        var x = x0 + ROW_NUM_W
        for (w in l.colWidths) { xs.add(x); x += w }
        val annoX = x
        val right = annoX + l.annoWidth

        // Input / Output / Annotation bands over the column headers.
        val inputW = l.colWidths.take(t.inputs.size).sum()
        val outputW = l.colWidths.drop(t.inputs.size).sum()
        sb.append(rect(x0, bandY, ROW_NUM_W, BAND_H, INPUT_FILL))
        if (inputW > 0) sb.append(band(x0 + ROW_NUM_W, bandY, inputW, "Input", INPUT_FILL))
        if (outputW > 0) sb.append(band(x0 + ROW_NUM_W + inputW, bandY, outputW, "Output", OUTPUT_FILL))
        if (l.hasAnnotations) sb.append(band(annoX, bandY, l.annoWidth, "Annotation", ANNO_FILL))

        // Column headers: label on top, the expression / output name underneath.
        val headers = t.inputs + t.outputs
        sb.append(rect(x0, headY, ROW_NUM_W, HEAD_H, ANNO_FILL))
        for ((i, h) in headers.withIndex()) {
            val fill = if (i < t.inputs.size) INPUT_FILL else OUTPUT_FILL
            sb.append(rect(xs[i], headY, l.colWidths[i], HEAD_H, fill))
            val detail = h.detail
            sb.append(text(xs[i] + CELL_PAD, headY + (if (detail != null) 13.0 else 19.0), clip(h.label), weight = "600"))
            if (detail != null) {
                sb.append(text(xs[i] + CELL_PAD, headY + 25.0, clip(detail), size = 10.5, fill = MUTED, mono = true))
            }
        }
        if (l.hasAnnotations) sb.append(rect(annoX, headY, l.annoWidth, HEAD_H, ANNO_FILL))

        // Rule rows.
        for ((r, rule) in t.rules.withIndex()) {
            val y = bodyY + ROW_H * r
            if (r % 2 == 1) sb.append(rect(x0, y, right - x0, ROW_H, ROW_ALT))
            sb.append(text(x0 + CELL_PAD, y + 17.5, "${r + 1}", size = 10.5, fill = MUTED))
            for (i in headers.indices) {
                val cell = clip(rule.cells.getOrElse(i) { "" })
                // A dash reads as "any value" — an empty DMN input entry matches everything.
                val shown = if (cell.isEmpty() && i < t.inputs.size) "-" else cell
                if (shown.isNotEmpty()) {
                    sb.append(text(xs[i] + CELL_PAD, y + 17.5, shown, mono = true, fill = if (shown == "-") MUTED else TEXT))
                }
            }
            if (l.hasAnnotations && !rule.annotation.isNullOrBlank()) {
                sb.append(text(annoX + CELL_PAD, y + 17.5, clip(rule.annotation), fill = MUTED))
            }
        }

        // Grid: the outer frame plus every column / row separator.
        val bottom = bodyY + bodyH
        sb.append(frame(x0, bandY, right, bottom))
        for (xi in xs) sb.append(vline(xi, bandY, bottom))
        if (l.hasAnnotations) sb.append(vline(annoX, bandY, bottom))
        sb.append(hline(x0, right, headY))
        sb.append(hline(x0, right, bodyY, strong = true))
        for (r in 1..t.rules.size) sb.append(hline(x0, right, bodyY + ROW_H * r))
        l.footer?.let { sb.append(text(x0, bottom + 14.0, it, size = 10.5, fill = MUTED)) }
    }

    private fun band(x: Double, y: Double, w: Double, label: String, fill: String): String =
        rect(x, y, w, BAND_H, fill) + text(x + CELL_PAD, y + 14.0, label, size = 10.5, fill = MUTED, weight = "600")

    private fun rect(x: Double, y: Double, w: Double, h: Double, fill: String): String =
        """<rect x="${fmt(x)}" y="${fmt(y)}" width="${fmt(w)}" height="${fmt(h)}" fill="$fill"/>"""

    private fun frame(x: Double, y: Double, right: Double, bottom: Double): String =
        """<rect x="${fmt(x)}" y="${fmt(y)}" width="${fmt(right - x)}" height="${fmt(bottom - y)}" """ +
            """fill="none" stroke="$STROKE_STRONG" stroke-width="1"/>"""

    private fun vline(x: Double, y1: Double, y2: Double): String =
        """<line x1="${fmt(x)}" y1="${fmt(y1)}" x2="${fmt(x)}" y2="${fmt(y2)}" stroke="$STROKE" stroke-width="1"/>"""

    private fun hline(x1: Double, x2: Double, y: Double, strong: Boolean = false): String =
        """<line x1="${fmt(x1)}" y1="${fmt(y)}" x2="${fmt(x2)}" y2="${fmt(y)}" """ +
            """stroke="${if (strong) STROKE_STRONG else STROKE}" stroke-width="1"/>"""

    private fun text(
        x: Double,
        y: Double,
        s: String,
        size: Double = FONT_SIZE,
        fill: String = TEXT,
        weight: String = "400",
        mono: Boolean = false,
    ): String =
        """<text x="${fmt(x)}" y="${fmt(y)}" font-family="${if (mono) MONO else FONT}" """ +
            """font-size="${fmt(size)}" font-weight="$weight" fill="$fill">${esc(s)}</text>"""

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
