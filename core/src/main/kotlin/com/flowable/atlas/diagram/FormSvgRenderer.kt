package com.flowable.atlas.diagram

import com.flowable.atlas.parsing.ModelJsonReader
import kotlin.math.max

/**
 * Paints a `.form` / `.page` as a wireframe: the [FormLayout] grid at its real proportions, each
 * component as a placeholder of its kind (input, text area, checkbox, select, button, table, subform,
 * panel or tabs), its caption with a star when required, and its id underneath, which is the name code
 * and expressions reach it by. A `visible` / `enabled` that depends on an expression is spelled out
 * beside the id; one that is plainly `false` greys the component out.
 *
 * It is a developer's map of the form, not a preview of the Work UI: no styling, no data, no runtime
 * logic. The IDE's gutter icon and model preview and the explorer's form page all draw it (see
 * [ModelPicture]); every component is a `<g data-el="<id>">` and a [Picture.Hotspot], so a click on it
 * lands on the component — its row on the explorer page, its declaration in the editor.
 *
 * Output is deterministic and draws its own white background, so it is legible in any viewer theme.
 */
object FormSvgRenderer {

    private const val FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"
    private const val MONO = "'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace"
    private const val WIDTH = 960.0
    private const val PAD = 20.0
    private const val GAP = 12.0            // between cells of a row
    private const val ROW_GAP = 14.0
    private const val TITLE_H = 34.0
    private const val LABEL_H = 17.0
    private const val FIELD_H = 28.0
    private const val META_H = 15.0
    private const val INSET = 12.0          // a container's padding
    private const val HEADER_H = 26.0       // a container's title bar / a tab strip
    private const val CHAR_W = 6.6          // ~average advance of the 12px UI font
    private const val MONO_W = 6.0          // the 10px mono font

    private const val TEXT = "#1f2933"
    private const val MUTED = "#607d8b"
    private const val STROKE = "#b0bec5"
    private const val FIELD_FILL = "#ffffff"
    private const val PANEL_FILL = "#f7f9fa"
    private const val HEADER_FILL = "#eceff1"
    private const val ACCENT = "#1565c0"
    private const val REQUIRED = "#c62828"
    private const val ALERT_FILL = "#e3f2fd"
    private const val EXPR = "#6a1b9a"

    private val CHECK_TYPES = setOf("boolean", "checkbox", "switcher")
    private val MULTILINE_TYPES = setOf("textarea", "richText")
    private val PROSE_TYPES = setOf("alert", "htmlComponent", "paragraph")
    private val CHOICE_TYPES = setOf("select", "radio", "people", "group", "multiselect")

    /** The wireframe of a form/page document, or null when [bytes] is not one. */
    fun renderSvg(bytes: ByteArray): String? = picture(bytes)?.svg

    /** The wireframe with every component's box as a hotspot, in drawing order — a panel before the
     *  components inside it, so the innermost one wins a click. */
    fun picture(bytes: ByteArray): Picture? {
        val layout = FormLayout.parse(bytes) ?: return null
        val hits = ArrayList<Picture.Hotspot>()
        val inner = WIDTH - 2 * PAD
        val gridH = if (layout.rows.isEmpty()) LABEL_H else gridHeight(layout, inner)
        val titleH = if (layout.title != null) TITLE_H else 0.0
        val height = PAD + titleH + gridH + PAD

        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(WIDTH)}" height="${fmt(height)}" """ +
                """viewBox="0 0 ${fmt(WIDTH)} ${fmt(height)}">""",
        )
        sb.append("""<rect width="${fmt(WIDTH)}" height="${fmt(height)}" fill="#ffffff"/>""")
        sb.append("""<g font-family="$FONT" font-size="12" fill="$TEXT">""")
        layout.title?.let { sb.append(text(PAD, PAD + 16.0, clip(it, inner, CHAR_W * 1.2), size = 15.0, weight = "600")) }
        val top = PAD + titleH
        if (layout.rows.isEmpty()) {
            sb.append(text(PAD, top + 13.0, "No components", fill = MUTED))
        } else {
            drawGrid(sb, layout, PAD, top, inner, hits)
        }
        sb.append("</g></svg>")
        return Picture(sb.toString(), Picture.Kind.WIREFRAME, Picture.Box(0.0, 0.0, WIDTH, height), hits)
    }

    // ---- layout ------------------------------------------------------------------------------

    /** A row's cells broken into lines of at most twelve columns — Design never overfills one, a hand edit may. */
    private fun lines(row: FormLayout.Row): List<List<FormLayout.Cell>> {
        val out = ArrayList<MutableList<FormLayout.Cell>>()
        var used = 12
        for (c in row.cells) {
            if (used + c.size > 12) { out.add(ArrayList()); used = 0 }
            out.last().add(c)
            used += c.size
        }
        return out
    }

    private fun gridHeight(layout: FormLayout, width: Double): Double {
        val lines = layout.rows.flatMap(::lines)
        return lines.sumOf { line -> line.maxOf { cellHeight(it, span(it, width)) } } + ROW_GAP * max(0, lines.size - 1)
    }

    private fun span(c: FormLayout.Cell, width: Double): Double = (width + GAP) * c.size / 12 - GAP

    private fun cellHeight(c: FormLayout.Cell, w: Double): Double = META_H + when {
        c.sections.isNotEmpty() -> HEADER_H + c.sections.sumOf { s ->
            val grid = if (s.layout.rows.isEmpty()) LABEL_H else gridHeight(s.layout, w - 2 * INSET)
            (if (c.sections.size > 1) HEADER_H else 0.0) + INSET + grid + INSET
        }
        c.subform != null -> 46.0
        c.type == "dataTable" -> LABEL_H + 24.0 + 2 * 20.0
        c.type == "hline" -> 10.0
        c.type == "headline" -> 24.0
        isButton(c) || c.type in CHECK_TYPES -> FIELD_H
        c.type in PROSE_TYPES -> 44.0
        c.type in MULTILINE_TYPES -> LABEL_H + 64.0
        else -> LABEL_H + FIELD_H
    }

    private fun isButton(c: FormLayout.Cell): Boolean =
        c.type in ModelJsonReader.BUTTON_TYPES || c.type == "buttonGroup"

    // ---- painting ----------------------------------------------------------------------------

    private fun drawGrid(
        sb: StringBuilder, layout: FormLayout, x0: Double, y0: Double, width: Double, hits: MutableList<Picture.Hotspot>,
    ) {
        var y = y0
        for (line in layout.rows.flatMap(::lines)) {
            var col = 0
            val h = line.maxOf { cellHeight(it, span(it, width)) }
            for (c in line) {
                val x = x0 + (width + GAP) * col / 12
                drawCell(sb, c, x, y, span(c, width), hits)
                col += c.size
            }
            y += h + ROW_GAP
        }
    }

    private fun drawCell(sb: StringBuilder, c: FormLayout.Cell, x: Double, y: Double, w: Double, hits: MutableList<Picture.Hotspot>) {
        val hidden = c.visible == false
        // the component is one clickable element — the same contract as a diagram shape
        val id = c.id.takeIf { it.isNotBlank() }
        sb.append("<g")
        if (id != null) {
            sb.append(""" data-el="${esc(id)}" tabindex="0" role="button"""")
            hits.add(Picture.Hotspot(id, x, y, w, cellHeight(c, w)))
        }
        sb.append(if (hidden) """ opacity="0.45">""" else ">")
        c.label?.let { sb.append("<title>${esc(it)} (${esc(c.type)})</title>") }
        val bodyH = cellHeight(c, w) - META_H
        when {
            c.sections.isNotEmpty() -> drawContainer(sb, c, x, y, w, hits)
            c.subform != null -> {
                sb.append(box(x, y, w, bodyH, PANEL_FILL, dashed = true))
                sb.append(text(x + 10.0, y + 18.0, clip(c.label ?: "Subform", w - 20.0), weight = "600"))
                sb.append(text(x + 10.0, y + 35.0, clip("↳ subform ${c.subform}", w - 20.0, MONO_W), size = 10.5, fill = ACCENT, mono = true))
            }
            c.type == "dataTable" -> drawTable(sb, c, x, y, w)
            c.type == "hline" -> sb.append(line(x, y + 5.0, x + w, y + 5.0))
            c.type == "headline" -> sb.append(text(x, y + 17.0, clip(c.label ?: c.value ?: "", w, CHAR_W * 1.3), size = 15.0, weight = "600"))
            isButton(c) -> {
                val caption = c.label ?: c.type
                val bw = minOf(w, caption.length * CHAR_W + 32.0)
                sb.append(box(x, y, bw, FIELD_H, ACCENT, radius = 14.0, stroke = ACCENT))
                sb.append(text(x + 16.0, y + 18.5, clip(caption, bw - 32.0), fill = "#ffffff", weight = "600"))
            }
            c.type in CHECK_TYPES -> {
                sb.append(box(x, y + 7.0, 14.0, 14.0, FIELD_FILL, radius = if (c.type == "switcher") 7.0 else 2.0))
                sb.append(label(c, x + 22.0, y + 18.5, w - 22.0))
            }
            c.type in PROSE_TYPES -> {
                sb.append(box(x, y, w, bodyH, if (c.type == "alert") ALERT_FILL else PANEL_FILL, dashed = c.type == "htmlComponent"))
                val prose = c.label ?: c.value?.replace(Regex("<[^>]*>"), " ") ?: c.type
                sb.append(text(x + 10.0, y + 19.0, clip(prose, w - 20.0), fill = if (c.label != null) TEXT else MUTED))
                sb.append(text(x + 10.0, y + 35.0, clip(c.type, w - 20.0, MONO_W), size = 10.0, fill = MUTED))
            }
            else -> {
                sb.append(label(c, x, y + 12.0, w))
                val fh = if (c.type in MULTILINE_TYPES) 64.0 else FIELD_H
                val fy = y + LABEL_H
                sb.append(box(x, fy, w, fh, if (c.enabled == false) PANEL_FILL else FIELD_FILL))
                c.value?.takeIf { it.contains("{{") }?.let { sb.append(text(x + 8.0, fy + 18.0, clip(it, w - 36.0, MONO_W), size = 10.5, fill = MUTED, mono = true)) }
                if (c.type in CHOICE_TYPES) sb.append(chevron(x + w - 16.0, fy + fh / 2))
                if (c.type == "date") sb.append(box(x + w - 22.0, fy + 8.0, 12.0, 12.0, HEADER_FILL, radius = 1.0))
            }
        }
        drawMeta(sb, c, x, y + bodyH, w)
        sb.append("</g>")
    }

    /** A panel: a title bar over its grid; tabs and accordions: one strip + grid per section. */
    private fun drawContainer(sb: StringBuilder, c: FormLayout.Cell, x: Double, y: Double, w: Double, hits: MutableList<Picture.Hotspot>) {
        val bodyH = cellHeight(c, w) - META_H
        sb.append(box(x, y, w, bodyH, PANEL_FILL))
        sb.append(box(x, y, w, HEADER_H, HEADER_FILL))
        val title = listOfNotNull(c.label, c.type.takeIf { c.label == null || c.sections.size > 1 }).joinToString(" · ")
        sb.append(text(x + 10.0, y + 17.0, clip(title, w - 20.0), weight = "600"))
        var sy = y + HEADER_H
        for (s in c.sections) {
            if (c.sections.size > 1) {
                sb.append(line(x, sy, x + w, sy))
                sb.append(text(x + 10.0, sy + 17.0, clip("▸ ${s.label ?: "Section"}", w - 20.0), fill = ACCENT, weight = "600"))
                sy += HEADER_H
            }
            val inner = w - 2 * INSET
            if (s.layout.rows.isEmpty()) {
                sb.append(text(x + INSET, sy + INSET + 12.0, "Empty", fill = MUTED))
                sy += INSET + LABEL_H + INSET
            } else {
                drawGrid(sb, s.layout, x + INSET, sy + INSET, inner, hits)
                sy += INSET + gridHeight(s.layout, inner) + INSET
            }
        }
    }

    private fun drawTable(sb: StringBuilder, c: FormLayout.Cell, x: Double, y: Double, w: Double) {
        sb.append(label(c, x, y + 12.0, w))
        val ty = y + LABEL_H
        sb.append(box(x, ty, w, 24.0 + 2 * 20.0, FIELD_FILL))
        sb.append(box(x, ty, w, 24.0, HEADER_FILL))
        val cols = c.columns.ifEmpty { listOf("—") }
        val cw = w / cols.size
        for ((i, h) in cols.withIndex()) {
            val cx = x + cw * i
            if (i > 0) sb.append(line(cx, ty, cx, ty + 64.0))
            sb.append(text(cx + 6.0, ty + 16.0, clip(h, cw - 12.0), size = 11.0, weight = "600"))
        }
        sb.append(line(x, ty + 44.0, x + w, ty + 44.0))
    }

    /** The id — what a `{{…}}` or a script names the component by — and any condition it renders under. */
    private fun drawMeta(sb: StringBuilder, c: FormLayout.Cell, x: Double, y: Double, w: Double) {
        val parts = ArrayList<Pair<String, String>>()
        parts.add(c.id to MUTED)
        when (val v = c.visible) {
            null -> {}
            false -> parts.add("hidden" to MUTED)
            else -> parts.add("visible if $v" to EXPR)
        }
        when (val e = c.enabled) {
            null -> {}
            false -> parts.add("disabled" to MUTED)
            else -> parts.add("enabled if $e" to EXPR)
        }
        var cx = x
        val baseline = y + 11.0
        for ((s, fill) in parts) {
            val room = x + w - cx
            if (room < 4 * MONO_W) break
            val shown = clip(s, room, MONO_W)
            sb.append(text(cx, baseline, shown, size = 10.0, fill = fill, mono = true))
            cx += shown.length * MONO_W + 10.0
        }
    }

    private fun label(c: FormLayout.Cell, x: Double, baseline: Double, w: Double): String {
        val caption = clip(c.label ?: c.id, w - (if (c.required) 12.0 else 0.0))
        val star = if (c.required) {
            """<tspan fill="$REQUIRED"> *</tspan>"""
        } else {
            ""
        }
        return """<text x="${fmt(x)}" y="${fmt(baseline)}">${esc(caption)}$star</text>"""
    }

    private fun chevron(cx: Double, cy: Double): String =
        """<polyline points="${fmt(cx - 4)},${fmt(cy - 2)} ${fmt(cx)},${fmt(cy + 2)} ${fmt(cx + 4)},${fmt(cy - 2)}" """ +
            """fill="none" stroke="$MUTED" stroke-width="1.5"/>"""

    private fun box(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        fill: String,
        radius: Double = 3.0,
        stroke: String = STROKE,
        dashed: Boolean = false,
    ): String =
        """<rect x="${fmt(x)}" y="${fmt(y)}" width="${fmt(w)}" height="${fmt(h)}" rx="${fmt(radius)}" fill="$fill" """ +
            """stroke="$stroke" ${if (dashed) " stroke-dasharray=\"4 3\"" else ""}/>"""

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double): String =
        """<line x1="${fmt(x1)}" y1="${fmt(y1)}" x2="${fmt(x2)}" y2="${fmt(y2)}" stroke="$STROKE"/>"""

    private fun text(
        x: Double,
        y: Double,
        s: String,
        size: Double = 12.0,
        fill: String = TEXT,
        weight: String = "400",
        mono: Boolean = false,
    ): String = buildString {
        // only what differs from the drawing's group — family, 12px, regular, text colour — is written
        append("""<text x="${fmt(x)}" y="${fmt(y)}"""")
        if (mono) append(""" font-family="$MONO"""")
        if (size != 12.0) append(""" font-size="${fmt(size)}"""")
        if (weight != "400") append(""" font-weight="$weight"""")
        if (fill != TEXT) append(""" fill="$fill"""")
        append(">").append(esc(s)).append("</text>")
    }

    /** [s] on one line, cut with an ellipsis to what fits in [width] at [charW] per character. */
    private fun clip(s: String, width: Double, charW: Double = CHAR_W): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        val max = (width / charW).toInt().coerceAtLeast(1)
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }

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
