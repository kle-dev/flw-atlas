package com.flowable.atlas.explorer

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * The nine colours the explorer needs to wear the IDE's look-and-feel instead of its own Hub palette:
 * the page maps them onto its tokens (`--bg`, `--panel`, …) and derives hover, selection and focus
 * tints from them, so a Darcula, a New UI Light or a high-contrast theme all look native. Anything the
 * LaF cannot answer falls back to the Hub value for that mode — an exotic theme never breaks the push.
 *
 * Two encodings, because the page needs the colours twice: `toUrlParam` rides on the initial URL so
 * the pre-paint script can colour the surfaces before the first frame, `toJs` goes into the live
 * `__atlasSetIdeTheme(mode, palette)` push on load and on every LaF or editor-scheme change.
 */
internal object IdePalette {
    /** In the order the page expects them; `toUrlParam` relies on it. */
    val KEYS = listOf("bg", "panel", "panel2", "line", "ink", "inkDim", "accent", "selBg", "selText")
    private val HEX = Regex("^#[0-9a-f]{6}$")

    /** Must run on the EDT: a JBColor resolves against the current LaF when asked for its RGB. */
    fun current(): Map<String, String> {
        val dark = !JBColor.isBright()
        fun pick(key: String, fallback: String, get: () -> Color) =
            key to (runCatching { hex(get()) }.getOrNull()?.takeIf { HEX.matches(it) } ?: fallback)
        return linkedMapOf(
            pick("bg", if (dark) "#0c141c" else "#fafafa") { UIUtil.getPanelBackground() },
            pick("panel", if (dark) "#131e29" else "#ffffff") { EditorColorsManager.getInstance().globalScheme.defaultBackground },
            pick("panel2", if (dark) "#111c26" else "#f8fafc") { JBUI.CurrentTheme.ToolWindow.background() },
            pick("line", if (dark) "#1f3245" else "#e2e8f0") { JBColor.border() },
            pick("ink", if (dark) "#e9eef0" else "#131e29") { UIUtil.getLabelForeground() },
            pick("inkDim", if (dark) "#a5adb5" else "#4c5b6a") { UIUtil.getContextHelpForeground() },
            pick("accent", if (dark) "#6f99e6" else "#0f55d6") { JBUI.CurrentTheme.Link.Foreground.ENABLED },
            pick("selBg", if (dark) "#1c3551" else "#e5f0ff") { UIUtil.getListSelectionBackground(true) },
            pick("selText", "#ffffff") { UIUtil.getListSelectionForeground(true) },
        )
    }

    /** `#rrggbb`, lowercase, alpha dropped — the one shape the page accepts. */
    fun hex(c: Color): String = ColorUtil.toHtmlColor(c).lowercase()

    /** `{"bg":"#f7f8fa",…}` — hand-built; every value is validated hex, so the string is safe to hand to executeJavaScript. */
    fun toJs(p: Map<String, String>): String = KEYS.joinToString(",", "{", "}") { k -> "\"$k\":\"${valid(p[k])}\"" }

    /** `f7f8fa.ffffff.…` — nine 6-hex groups, dot-joined, for the `idePal` URL parameter the pre-paint script reads. */
    fun toUrlParam(p: Map<String, String>): String = KEYS.joinToString(".") { k -> valid(p[k]).substring(1) }

    private fun valid(v: String?): String = v?.takeIf { HEX.matches(it) } ?: error("IdePalette: not a #rrggbb colour: $v")
}
