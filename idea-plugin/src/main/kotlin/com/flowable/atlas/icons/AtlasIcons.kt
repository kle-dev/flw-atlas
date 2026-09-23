package com.flowable.atlas.icons

import com.flowable.atlas.model.ModelType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The plugin's icons, one name per meaning.
 *
 * Before this object the Hub's tool-window glyph stood in for five things — the Hub, "a model" in Search
 * Everywhere and Go to Symbol, and three gutter markers that meant three different relationships — so a
 * reader could not tell from the gutter whether a method was a bot, an endpoint or merely referenced.
 * Every call site names its meaning here instead, and the file behind the name is the only place the
 * glyph lives.
 *
 * The files are `/icons/atlas/<name>.svg` with a `_dark` sibling the platform picks under a dark theme.
 * `type-<id>` and the chrome icons derived from the explorer are **generated** by
 * `scripts/plugin-icons.mjs` from `explorer.js`'s `TYPE_ICONS` and `explorer.css`'s `--c-*` palette, so a
 * model type wears the same glyph and colour in the Project view as on its explorer page; `hub` and
 * `playground` are the two hand-drawn originals. `core`'s `PluginIconsSyncTest` fails the build when the
 * generated files drift from their sources.
 */
object AtlasIcons {
    /** The Atlas Hub tool window and the action that opens it. */
    @JvmField val Hub: Icon = load("hub")
    /** The Expression Playground tool window (the playground) and the action that opens it. */
    @JvmField val Playground: Icon = load("playground")
    /** A generated Atlas Explorer — the artifact rows in the Hub, the open/generate actions. */
    @JvmField val Explorer: Icon = load("explorer")
    /** A Flowable model whose type is not known or not relevant (the Tools menu group). */
    @JvmField val Model: Icon = load("model")
    /** A `BotService` implementation (Go to Symbol rows). */
    @JvmField val Bot: Icon = load("bot")
    /** A REST handler that Flowable models call. */
    @JvmField val Endpoint: Icon = load("endpoint")
    /** A `.bar` deployment archive in the Project view. */
    @JvmField val Archive: Icon = load("archive")

    // Gutter markers are 12 px like the platform's own AllIcons.Gutter.*; a 16 px icon there reads heavy.
    /** Gutter: a Java symbol that Flowable models reference by name. */
    @JvmField val GutterReference: Icon = load("gutter-reference")
    /** Gutter: a bot class that Flowable actions use. */
    @JvmField val GutterBot: Icon = load("gutter-bot")
    /** Gutter: a REST handler that Flowable models call. */
    @JvmField val GutterEndpoint: Icon = load("gutter-endpoint")
    /** Gutter: a model-key literal whose model has a diagram to open. */
    @JvmField val GutterDiagram: Icon = load("gutter-diagram")

    private val byType: Map<ModelType, Icon> = ModelType.entries.associateWith { load("type-${it.id}") }

    /** The icon of a model type — the explorer's glyph in the explorer's colour, light or dark. */
    fun forType(type: ModelType): Icon = byType.getValue(type)

    /** The classpath path of an icon file; what a `plugin.xml` `icon="…"` attribute must equal. */
    fun path(name: String): String = "/icons/atlas/$name.svg"

    /** Every name this object loads — the test walks them so a renamed file cannot go unnoticed. */
    internal val names: List<String> =
        listOf("hub", "playground", "explorer", "model", "bot", "endpoint", "archive",
            "gutter-reference", "gutter-bot", "gutter-endpoint", "gutter-diagram") +
            ModelType.entries.map { "type-${it.id}" }

    private fun load(name: String): Icon = IconLoader.getIcon(path(name), AtlasIcons::class.java)
}
