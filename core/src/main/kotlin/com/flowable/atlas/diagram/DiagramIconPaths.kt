package com.flowable.atlas.diagram

/**
 * The vector outline of each [DiaIcon], authored in a 24×24 box with the origin top-left.
 *
 * Kept apart from the painter so the drawing code stays about layout and this file stays about shapes.
 * Every glyph is stroke-only line art (no fills except where a filled mark *is* the notation, e.g. the
 * terminate disc), which keeps them legible at the ~14 px the painter scales them to and lets the painter
 * pick the colour. [DiagramSvgRenderer] wraps these in a `<g transform="translate(...) scale(...)">`.
 */
internal object DiagramIconPaths {

    /** A glyph as a list of SVG elements, already relative to the 24×24 box. */
    fun of(icon: DiaIcon): String = PATHS[icon] ?: ""

    private const val FILLED = """ fill="currentColor" stroke="none""""

    private fun p(d: String, extra: String = "") = """<path d="$d"$extra/>"""
    private fun circle(cx: Double, cy: Double, r: Double, extra: String = "") =
        """<circle cx="$cx" cy="$cy" r="$r"$extra/>"""

    private val PATHS: Map<DiaIcon, String> = mapOf(
        // ---- BPMN standard task types --------------------------------------------------------
        // person: head + shoulders
        DiaIcon.USER to circle(12.0, 8.0, 4.0) + p("M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"),
        // gear: ring + six teeth
        DiaIcon.SERVICE to circle(12.0, 12.0, 4.0) + circle(12.0, 12.0, 8.5) +
            p("M12 3.5v3M12 17.5v3M3.5 12h3M17.5 12h3M6 6l2.1 2.1M15.9 15.9L18 18M18 6l-2.1 2.1M8.1 15.9L6 18"),
        // script: page with lines
        DiaIcon.SCRIPT to p("M7 3h10v18H7z") + p("M10 8h4M10 12h4M10 16h4"),
        // hand
        DiaIcon.MANUAL to p("M6 13V8a2 2 0 0 1 4 0v4V5a2 2 0 0 1 4 0v7V7a2 2 0 0 1 4 0v8a6 6 0 0 1-6 6h-2a6 6 0 0 1-6-6z"),
        // rule table: grid
        DiaIcon.BUSINESS_RULE to p("M3 5h18v14H3z") + p("M3 10h18M3 15h18M9 5v14"),
        // decision table: grid with a marked first column
        DiaIcon.DECISION to p("M3 5h18v14H3z") + p("M3 10h18M3 15h18M8 5v14") +
            p("M3 5h5v5H3z", FILLED),
        // envelope, sent (filled flap)
        DiaIcon.SEND to p("M3 6h18v12H3z") + p("M3 6l9 7 9-7", FILLED),
        // envelope, open
        DiaIcon.RECEIVE to p("M3 6h18v12H3z") + p("M3 6l9 7 9-7"),
        DiaIcon.MAIL to p("M3 6h18v12H3z") + p("M3 6l9 7 9-7") + p("M3 18l6-6M21 18l-6-6"),
        // globe
        DiaIcon.HTTP to circle(12.0, 12.0, 9.0) + p("M3 12h18") +
            p("M12 3c3 3.2 3 14 0 18M12 3c-3 3.2-3 14 0 18"),
        DiaIcon.SHELL to p("M3 4h18v16H3z") + p("M6 9l3 3-3 3M12 15h6"),
        DiaIcon.CAMEL to p("M3 17c2-6 5-8 8-8s3 2 5 2 3-1 5-3") + p("M8 17v3M16 15v5"),

        // ---- Flowable Work task types --------------------------------------------------------
        // service registry: a request out and a response back (⇄) — deliberately unlike the gear of a
        // plain service task and the globe of an HTTP task
        DiaIcon.SERVICE_REGISTRY to p("M3 9h15l-4-4M21 15H6l4 4"),
        // AI agent: four-point spark plus a small one
        DiaIcon.AGENT to p("M12 2l2.2 5.8L20 10l-5.8 2.2L12 18l-2.2-5.8L4 10l5.8-2.2z") +
            p("M18.5 15.5l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9z"),
        // data object: cylinder (database)
        DiaIcon.DATA_OBJECT to p("M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z") +
            p("M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6") + p("M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"),
        // document: page with a folded corner
        DiaIcon.DOCUMENT to p("M6 3h8l5 5v13H6z") + p("M14 3v5h5") + p("M9 13h6M9 17h6"),
        // sequence: ascending steps with a hash
        DiaIcon.SEQUENCE to p("M4 19h4v-4H4zM10 19h4V11h-4zM16 19h4V7h-4z"),
        // initialize variables: braces around an equals sign
        DiaIcon.INIT_VARIABLES to p("M8 4C5 4 5 12 3 12c2 0 2 8 5 8M16 4c3 0 3 8 5 8-2 0-2 8-5 8") +
            p("M9 10h6M9 14h6"),
        // audit: clipboard with a check
        DiaIcon.AUDIT to p("M6 4h12v17H6z") + p("M9 4V2h6v2") + p("M9 12l2.5 2.5L16 10"),
        // external worker: outbound arrow leaving a box
        DiaIcon.EXTERNAL_WORKER to p("M13 4H5v15h15v-8") + p("M14 10l7-7M15 3h6v6"),
        DiaIcon.CASE to p("M3 7h18v13H3z") + p("M9 7V4h6v3") + p("M3 12h18"),
        DiaIcon.PROCESS to p("M2 9h6v6H2zM16 9h6v6h-6z") + p("M8 12h8") + p("M13 9l3 3-3 3"),
        DiaIcon.SEND_EVENT to circle(12.0, 12.0, 9.0) + p("M7 10h10v6H7z") + p("M7 10l5 4 5-4", FILLED),
        DiaIcon.RECEIVE_EVENT to circle(12.0, 12.0, 9.0) + p("M7 10h10v6H7z") + p("M7 10l5 4 5-4"),

        // ---- event definitions ---------------------------------------------------------------
        // no tick marks around the dial: inside an event circle they collide with the ring
        DiaIcon.TIMER to circle(12.0, 12.0, 9.0) + p("M12 6V12l4.5 2.5"),
        DiaIcon.MESSAGE to p("M3 7h18v10H3z") + p("M3 7l9 6.5L21 7"),
        DiaIcon.SIGNAL to p("M12 4l9 15.5H3z"),
        DiaIcon.ERROR to p("M4 19l5.5-9.5L13 14l7-9-3 14.5-4.5-5L8 19z", FILLED),
        DiaIcon.ESCALATION to p("M12 4l7 16-7-7-7 7z", FILLED),
        DiaIcon.TERMINATE to circle(12.0, 12.0, 8.0, FILLED),
        DiaIcon.CONDITIONAL to p("M4 4h16v16H4z") + p("M7 8h10M7 12h10M7 16h6"),
        DiaIcon.COMPENSATION to p("M11 5v14L3 12zM21 5v14l-8-7z"),
        DiaIcon.LINK to p("M4 12h12l-4-4M16 12l-4 4") + p("M20 5v14"),
    )
}
