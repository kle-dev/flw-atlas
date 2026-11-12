package com.flowable.atlas.environment

import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import com.intellij.openapi.util.text.StringUtil

/**
 * Every string that names a connection, in one place — so the Atlas Hub and the settings pages
 * cannot drift apart in wording, which is the small version of the very problem this feature is
 * fixing.
 *
 * Two registers, because the two surfaces have very different room. [narrow] is for the Hub and the
 * playground, which live in a panel a few hundred pixels wide: it names the **environment** and
 * nothing else, and everything a reader might occasionally want is in [tooltip]. [wide] is for a
 * settings page, where the URL fits and is worth showing.
 *
 * Pure — no Swing, no services — so the wording is unit-tested rather than eyeballed in a screenshot.
 */
object ConnectionLabels {

    /** Past this, an environment name is shortened; the whole one stays in the tooltip. */
    private const val MAX_NAME = 22

    /**
     * `Environment: <b>DEV1</b>` — HTML, because a Swing label needs it for the bold.
     *
     * [label] defaults to the kind, which is what a row needs when two of them sit side by side. Inside
     * a section that already says *Flowable Design*, repeating the word is noise, so the caller passes
     * "Environment" instead.
     */
    fun narrow(kind: ConnectionKind, resolution: Resolution, label: String = kind.display): String {
        val value = when (resolution) {
            is Resolution.Selected -> "<b>${escape(shortName(resolution.connection.environmentName))}</b>"
            // Two different situations that need two different fixes: nothing was ever picked, versus a
            // pick that something else took away. Saying "not set" for both hides the second one.
            Resolution.NotSet -> "<i>not set</i>"
            is Resolution.Dangling -> "<i>connection removed</i>"
        }
        return "<html>$label: $value</html>"
    }

    /**
     * The full name, the URL, and what *protected* means — the details that do not fit a narrow row
     * but that someone occasionally needs. Null when there is nothing worth hovering for.
     */
    fun tooltip(kind: ConnectionKind, resolution: Resolution): String? = when (resolution) {
        is Resolution.Selected -> buildString {
            append(resolution.connection.environmentName).append(" · ").append(resolution.connection.baseUrl)
            if (resolution.connection.requiresConfirmation) {
                append("<br>Protected — Atlas asks before using it.")
            }
            if (resolution.connection.shared) {
                append("<br>Defined by this project; your credentials stay in the IDE password safe.")
            }
        }.let { "<html>$it</html>" }
        Resolution.NotSet -> "Choose the ${kind.display.lowercase()} connection this project uses"
        is Resolution.Dangling ->
            "The ${kind.display.lowercase()} connection this project used is no longer configured."
    }

    /**
     * What a picker row shows: the environment, since one connection per kind makes that unambiguous —
     * plus `(project)` when the definition is the repository's rather than this IDE's.
     *
     * Provenance is on the row and not only in the tooltip because it changes what the reader can do
     * with the entry: a shared environment's URL is not theirs to edit, and its credentials are.
     */
    fun pickerItem(connection: AtlasConnection): String =
        shortName(connection.environmentName) + if (connection.shared) " (project)" else ""

    /**
     * How a pull's app selection reads. Spelling out every key made the Hub — and with it the whole
     * panel — as wide as the selection happened to be, and the list above it says which ones anyway.
     */
    fun appsLabel(appKeys: List<String>): String = when {
        appKeys.isEmpty() -> "no apps selected"
        appKeys.size == 1 -> appKeys.first()
        appKeys.size <= 3 -> appKeys.joinToString(", ")
        else -> "${appKeys.size} apps selected"
    }

    private fun shortName(name: String): String =
        if (name.isBlank()) "unnamed" else StringUtil.shortenTextWithEllipsis(name, MAX_NAME, 6)

    /** Swing renders label text as HTML, so a stray `<` in a user-typed name must not become markup. */
    private fun escape(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
