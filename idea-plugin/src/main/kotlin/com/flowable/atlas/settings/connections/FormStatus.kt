package com.flowable.atlas.settings.connections

import com.intellij.util.ui.JBUI

/**
 * Renders a status line so that it **wraps instead of widening the form**.
 *
 * A plain Swing label reports the whole message as its preferred width, so one sentence from a failed
 * connection test stretched its column past the edge of the Settings dialog and pushed every field out
 * of view — the layout visibly jumped the moment *Test Connection* answered. Wrapping the text in HTML
 * with an explicit width is what makes the label report a bounded width and lay itself out over as many
 * lines as it needs.
 *
 * The width is scaled, because the HTML `px` here is a real device pixel at the current IDE scale.
 */
internal object FormStatus {

    /** Roughly the width of the detail pane at the default splitter position. */
    private const val WRAP_WIDTH = 430

    fun html(message: String): String =
        if (message.isBlank()) "" else "<html><body style='width:${JBUI.scale(WRAP_WIDTH)}px'>${escape(message)}</body></html>"

    /** A server message can contain anything; a stray `<` must not become markup in the label. */
    private fun escape(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
