package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.explorer.AtlasBrowser
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * The whole editor for a connection Atlas never calls: one URL.
 *
 * Flowable Control and Flowable Hub are addresses per stage — the pages a developer keeps in bookmarks
 * and hunts for whenever they switch environment. Atlas has the environment list already, so the only
 * thing missing was the address; everything the [WorkConnectionForm] carries beyond it (a username, a
 * password, a browser session, *Test Connection*) exists because *Atlas* signs in, and nothing here
 * does. A form that showed those fields anyway would be asking for a password nothing would ever read.
 *
 * *Open in Browser* is here rather than only in the Atlas Hub because this is where the URL is typed,
 * and "did I get it right?" is the question of that moment — the counterpart of the other forms'
 * *Test Connection*, minus the network.
 */
class LinkConnectionForm(private val project: Project) {

    private val baseUrlField = JBTextField()

    private var current: ConnectionsDraft.Conn? = null

    val component: JComponent = panel {
        row { comment(COMMENT) }
        row("URL:") {
            // resizableColumn(), not just align(FILL): without it the column keeps the field at its
            // minimum width and a URL field renders about as wide as the word "http:".
            cell(baseUrlField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn()
            link("Open in Browser") { AtlasBrowser.open(baseUrlField.text.trim(), project) }
        }
    }

    fun load(conn: ConnectionsDraft.Conn) {
        flush()                       // keep what was typed for the connection being left
        current = conn
        baseUrlField.text = conn.baseUrl
        baseUrlField.emptyText.text = placeholderFor(conn.kind)
        baseUrlField.isEditable = !conn.shared
    }

    /**
     * Copy what is on screen back into the draft. Touches **no UI**: this runs on the `isModified()`
     * path the Settings dialog polls, and a repaint there feeds the poll loop until the page never
     * finishes loading.
     */
    fun flush() {
        val conn = current ?: return
        if (conn.shared) return       // the project's file owns this URL
        conn.baseUrl = BaseUrls.normalize(conn.kind, baseUrlField.text)
    }

    private fun placeholderFor(kind: ConnectionKind): String = when (kind) {
        ConnectionKind.CONTROL -> "http://localhost:8081/flowable-control"
        ConnectionKind.HUB -> "https://hub.example.com"
        // Not reachable — the tree only routes the link-only kinds here — and a wrong-looking hint is
        // cheaper than a crash if that ever changes.
        ConnectionKind.DESIGN, ConnectionKind.WORK -> "https://host"
    }

    private companion object {
        const val COMMENT =
            "An address Atlas opens in your browser from the Atlas Hub — nothing is called and nothing " +
                "is signed in to, so there is no password to store."
    }
}
