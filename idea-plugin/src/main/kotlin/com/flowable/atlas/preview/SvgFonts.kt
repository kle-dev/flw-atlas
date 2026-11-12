package com.flowable.atlas.preview

import java.awt.GraphicsEnvironment

/**
 * Adapts an Atlas SVG's fonts to what [SvgCanvas] can draw. JSVG takes the *first* family of a
 * `font-family` list and falls back to a serif face when that one is not installed — so the list the
 * renderers write for browsers (`'Segoe UI', 'Helvetica Neue', Arial, sans-serif`) came out in Times
 * on a Mac, and on a Linux Remote Dev host that has none of them. It also draws only 700 as bold.
 *
 * So every list is narrowed to its first installed family (else its generic family, else `sans-serif`),
 * and `600` becomes `bold`. The renderers stay as they are: a browser handles both.
 */
internal object SvgFonts {

    private val FAMILY = Regex("""font-family="([^"]*)"""")
    private val GENERIC = setOf("serif", "sans-serif", "monospace")

    private val installed: Set<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.map { it.lowercase() }.toSet() }
            .getOrDefault(emptySet())
    }

    fun resolvable(svg: String, installed: Set<String> = this.installed): String =
        FAMILY.replace(svg) { m ->
            val families = m.groupValues[1].split(',').map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
            val pick = families.firstOrNull { it.lowercase() in installed }
                ?: families.lastOrNull { it in GENERIC }
                ?: "sans-serif"
            "font-family=\"$pick\""
        }.replace("font-weight=\"600\"", "font-weight=\"bold\"")
}
