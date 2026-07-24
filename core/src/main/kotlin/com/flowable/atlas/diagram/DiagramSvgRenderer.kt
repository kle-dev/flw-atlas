package com.flowable.atlas.diagram

import kotlin.math.max
import kotlin.math.min

/**
 * Paints a [DiagramGeometry] to a standalone SVG string — a schematic but layout-faithful rendering:
 * every shape keeps its real DI position and size, connectors follow their real path, and each element
 * category (task, event, gateway, pool, data object, DMN decision, …) gets a recognisable silhouette.
 * It is intentionally *not* a full bpmn.js-fidelity renderer (no type mini-icons, no colour theming).
 *
 * The output is deterministic (stable element order, locale-free number formatting) so it is safe for
 * golden/byte-comparison tests and for embedding in the Atlas explorer HTML. A white background is
 * always drawn so the diagram is legible whatever the host viewer's theme is.
 */
object DiagramSvgRenderer {

    private const val PAD = 24.0
    private const val FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"
    private const val FONT_SIZE = 12.0
    private const val LINE_HEIGHT = 14.0
    private const val STROKE = "#37474f"
    private const val TEXT = "#1f2933"
    private const val FILL = "#ffffff"
    private const val LANE_FILL = "#f7f9fa"

    /** Render [geometry] to an SVG document, or null when there is nothing to draw. */
    fun render(geometry: DiagramGeometry): String? {
        if (geometry.isEmpty()) return null
        val box = boundingBox(geometry) ?: return null
        val (minX, minY, maxX, maxY) = box
        val viewX = minX - PAD
        val viewY = minY - PAD
        val viewW = max(1.0, (maxX - minX) + 2 * PAD)
        val viewH = max(1.0, (maxY - minY) + 2 * PAD)

        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(viewW)}" height="${fmt(viewH)}" """ +
                """viewBox="${fmt(viewX)} ${fmt(viewY)} ${fmt(viewW)} ${fmt(viewH)}">""",
        )
        sb.append(defs())
        sb.append("""<rect x="${fmt(viewX)}" y="${fmt(viewY)}" width="${fmt(viewW)}" height="${fmt(viewH)}" fill="#ffffff"/>""")

        // Containers (pool/lane/stage/subprocess) first, then edges, then the remaining shapes on top,
        // so connectors do not hide task/event silhouettes and labels stay readable.
        for (s in geometry.shapes) if (isContainer(s.kind)) drawShape(sb, s)
        for (e in geometry.edges) drawEdge(sb, e)
        for (s in geometry.shapes) if (!isContainer(s.kind)) drawShape(sb, s)

        sb.append("</svg>")
        return sb.toString()
    }

    private fun isContainer(k: ShapeKind): Boolean =
        k == ShapeKind.POOL || k == ShapeKind.LANE || k == ShapeKind.SUBPROCESS || k == ShapeKind.CMMN_STAGE

    private fun defs(): String =
        """<defs>""" +
            """<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">""" +
            """<path d="M0,0 L10,5 L0,10 z" fill="$STROKE"/></marker>""" +
            """<marker id="openArrow" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="10" markerHeight="10" orient="auto-start-reverse">""" +
            """<path d="M1,1 L11,6 L1,11" fill="none" stroke="$STROKE" stroke-width="1.4"/></marker>""" +
            """</defs>"""

    // ---- edges -------------------------------------------------------------------------------

    private fun drawEdge(sb: StringBuilder, e: DiaEdge) {
        if (e.waypoints.size < 2) return
        val pts = e.waypoints.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
        val (dash, marker) = when (e.kind) {
            EdgeKind.SEQUENCE_FLOW -> "" to "arrow"
            EdgeKind.DMN_REQUIREMENT -> "" to "arrow"
            EdgeKind.MESSAGE_FLOW -> """ stroke-dasharray="6 4"""" to "openArrow"
            EdgeKind.ASSOCIATION, EdgeKind.CMMN_ASSOCIATION -> """ stroke-dasharray="2 4"""" to "openArrow"
        }
        sb.append(
            """<polyline points="$pts" fill="none" stroke="$STROKE" stroke-width="1.4"$dash """ +
                """marker-end="url(#$marker)"/>""",
        )
    }

    // ---- shapes ------------------------------------------------------------------------------

    private fun drawShape(sb: StringBuilder, s: DiaShape) {
        when {
            s.kind.isEvent -> drawEvent(sb, s)
            s.kind.isGateway -> drawGateway(sb, s)
            s.kind == ShapeKind.DATA_OBJECT -> drawDataObject(sb, s)
            s.kind == ShapeKind.TEXT_ANNOTATION -> drawTextAnnotation(sb, s)
            s.kind == ShapeKind.POOL || s.kind == ShapeKind.LANE -> drawLane(sb, s)
            s.kind == ShapeKind.CMMN_MILESTONE || s.kind == ShapeKind.DMN_INPUT_DATA -> drawStadium(sb, s)
            s.kind == ShapeKind.DMN_DECISION -> drawRect(sb, s, rx = 0.0)
            else -> drawRect(sb, s, rx = 8.0)   // task / subprocess / stage / bkm / generic
        }
    }

    private fun drawRect(sb: StringBuilder, s: DiaShape, rx: Double) {
        val fill = if (s.kind == ShapeKind.CMMN_STAGE) LANE_FILL else FILL
        sb.append(
            """<rect x="${fmt(s.x)}" y="${fmt(s.y)}" width="${fmt(s.width)}" height="${fmt(s.height)}" """ +
                """rx="${fmt(rx)}" ry="${fmt(rx)}" fill="$fill" stroke="$STROKE" stroke-width="1.5"/>""",
        )
        if (s.kind == ShapeKind.SUBPROCESS) {
            // collapsed-subprocess [+] marker, bottom-centre
            val bx = s.centerX - 7
            val by = s.y + s.height - 16
            sb.append("""<rect x="${fmt(bx)}" y="${fmt(by)}" width="14" height="14" fill="none" stroke="$STROKE" stroke-width="1.2"/>""")
            sb.append(plus(s.centerX, by + 7, 4.0, 1.2))
        }
        centeredLabel(sb, s)
    }

    private fun drawStadium(sb: StringBuilder, s: DiaShape) {
        val r = min(s.width, s.height) / 2
        sb.append(
            """<rect x="${fmt(s.x)}" y="${fmt(s.y)}" width="${fmt(s.width)}" height="${fmt(s.height)}" """ +
                """rx="${fmt(r)}" ry="${fmt(r)}" fill="$FILL" stroke="$STROKE" stroke-width="1.5"/>""",
        )
        centeredLabel(sb, s)
    }

    private fun drawLane(sb: StringBuilder, s: DiaShape) {
        sb.append(
            """<rect x="${fmt(s.x)}" y="${fmt(s.y)}" width="${fmt(s.width)}" height="${fmt(s.height)}" """ +
                """fill="$LANE_FILL" stroke="$STROKE" stroke-width="1.3"/>""",
        )
        // label in a left title band, rotated vertical (typical pool/lane layout)
        s.label?.takeIf { it.isNotBlank() }?.let { label ->
            val tx = s.x + 14
            val ty = s.centerY
            sb.append(
                """<text x="${fmt(tx)}" y="${fmt(ty)}" text-anchor="middle" transform="rotate(-90 ${fmt(tx)} ${fmt(ty)})" """ +
                    """font-family="$FONT" font-size="$FONT_SIZE" fill="$TEXT">${esc(truncate(label, 40))}</text>""",
            )
        }
    }

    private fun drawEvent(sb: StringBuilder, s: DiaShape) {
        val r = min(s.width, s.height) / 2
        val cx = s.centerX
        val cy = s.centerY
        val strokeW = if (s.kind == ShapeKind.EVENT_END) 3.0 else 1.6
        sb.append("""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r)}" fill="$FILL" stroke="$STROKE" stroke-width="${fmt(strokeW)}"/>""")
        if (s.kind == ShapeKind.EVENT_INTERMEDIATE && r > 4) {
            sb.append("""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r - 3)}" fill="none" stroke="$STROKE" stroke-width="1.3"/>""")
        }
        belowLabel(sb, s)
    }

    private fun drawGateway(sb: StringBuilder, s: DiaShape) {
        val cx = s.centerX
        val cy = s.centerY
        val pts = "${fmt(cx)},${fmt(s.y)} ${fmt(s.x + s.width)},${fmt(cy)} ${fmt(cx)},${fmt(s.y + s.height)} ${fmt(s.x)},${fmt(cy)}"
        sb.append("""<polygon points="$pts" fill="$FILL" stroke="$STROKE" stroke-width="1.5"/>""")
        val d = min(s.width, s.height) * 0.18
        when (s.kind) {
            ShapeKind.GATEWAY_PARALLEL -> sb.append(plus(cx, cy, d, 2.4))
            ShapeKind.GATEWAY_INCLUSIVE -> sb.append("""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(d)}" fill="none" stroke="$STROKE" stroke-width="2.2"/>""")
            ShapeKind.GATEWAY_EVENT -> sb.append("""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(d)}" fill="none" stroke="$STROKE" stroke-width="1.4"/>""")
            else -> {   // exclusive → X
                sb.append(line(cx - d, cy - d, cx + d, cy + d, 2.4))
                sb.append(line(cx - d, cy + d, cx + d, cy - d, 2.4))
            }
        }
        belowLabel(sb, s)
    }

    private fun drawDataObject(sb: StringBuilder, s: DiaShape) {
        val fold = min(12.0, min(s.width, s.height) / 3)
        val x = s.x
        val y = s.y
        val r = s.x + s.width
        val b = s.y + s.height
        val d = "M${fmt(x)},${fmt(y)} L${fmt(r - fold)},${fmt(y)} L${fmt(r)},${fmt(y + fold)} L${fmt(r)},${fmt(b)} L${fmt(x)},${fmt(b)} Z"
        sb.append("""<path d="$d" fill="$FILL" stroke="$STROKE" stroke-width="1.4"/>""")
        sb.append("""<path d="M${fmt(r - fold)},${fmt(y)} L${fmt(r - fold)},${fmt(y + fold)} L${fmt(r)},${fmt(y + fold)}" fill="none" stroke="$STROKE" stroke-width="1.2"/>""")
        belowLabel(sb, s)
    }

    private fun drawTextAnnotation(sb: StringBuilder, s: DiaShape) {
        val x = s.x
        val y = s.y
        val b = s.y + s.height
        sb.append("""<path d="M${fmt(x + 8)},${fmt(y)} L${fmt(x)},${fmt(y)} L${fmt(x)},${fmt(b)} L${fmt(x + 8)},${fmt(b)}" fill="none" stroke="$STROKE" stroke-width="1.3"/>""")
        s.label?.takeIf { it.isNotBlank() }?.let {
            val lines = wrap(it, maxChars(s.width - 12), 3)
            textBlock(sb, x + 12, s.y + 2, lines, "start")
        }
    }

    // ---- labels ------------------------------------------------------------------------------

    private fun centeredLabel(sb: StringBuilder, s: DiaShape) {
        val label = s.label?.takeIf { it.isNotBlank() } ?: return
        val lines = wrap(label, maxChars(s.width - 8), 4)
        if (lines.isEmpty()) return
        val blockH = lines.size * LINE_HEIGHT
        val topY = s.centerY - blockH / 2
        textBlock(sb, s.centerX, topY, lines, "middle")
    }

    private fun belowLabel(sb: StringBuilder, s: DiaShape) {
        val label = s.label?.takeIf { it.isNotBlank() } ?: return
        val lines = wrap(label, maxChars(max(s.width, 90.0)), 2)
        textBlock(sb, s.centerX, s.y + s.height + 2, lines, "middle")
    }

    private fun textBlock(sb: StringBuilder, x: Double, topY: Double, lines: List<String>, anchor: String) {
        for ((i, line) in lines.withIndex()) {
            val baseline = topY + FONT_SIZE + i * LINE_HEIGHT
            sb.append(
                """<text x="${fmt(x)}" y="${fmt(baseline)}" text-anchor="$anchor" """ +
                    """font-family="$FONT" font-size="$FONT_SIZE" fill="$TEXT">${esc(line)}</text>""",
            )
        }
    }

    // ---- small svg helpers -------------------------------------------------------------------

    private fun plus(cx: Double, cy: Double, half: Double, w: Double): String =
        line(cx - half, cy, cx + half, cy, w) + line(cx, cy - half, cx, cy + half, w)

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, w: Double): String =
        """<line x1="${fmt(x1)}" y1="${fmt(y1)}" x2="${fmt(x2)}" y2="${fmt(y2)}" stroke="$STROKE" stroke-width="${fmt(w)}"/>"""

    // ---- geometry / text utilities -----------------------------------------------------------

    private data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    private fun boundingBox(g: DiagramGeometry): Box? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var any = false
        fun include(x: Double, y: Double) {
            any = true
            minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
        }
        for (s in g.shapes) { include(s.x, s.y); include(s.x + s.width, s.y + s.height) }
        for (e in g.edges) for (p in e.waypoints) include(p.x, p.y)
        return if (any) Box(minX, minY, maxX, maxY) else null
    }

    private fun maxChars(widthPx: Double): Int = max(4, (widthPx / 6.6).toInt())

    private fun wrap(text: String, maxChars: Int, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var cur = ""
        for (w in words) {
            cur = when {
                cur.isEmpty() -> w
                cur.length + 1 + w.length <= maxChars -> "$cur $w"
                else -> { lines.add(cur); w }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        kept[maxLines - 1] = truncate(kept[maxLines - 1], max(1, maxChars - 1))
        return kept
    }

    private fun truncate(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(max(1, maxChars - 1)).trimEnd() + "…"

    /** Locale-free number formatting: round to 2 decimals, drop a trailing `.0`. */
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
