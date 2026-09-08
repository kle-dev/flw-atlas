package com.flowable.atlas

import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin's generated icons (`idea-plugin/src/main/resources/icons/atlas/<name>.svg`) must match the
 * explorer sources they are generated from: `TYPE_ICONS` / `UI_ICONS` in explorer.js and the `--c-*`
 * palette in explorer.css. `scripts/plugin-icons.mjs` writes them; this test is the gate that does not
 * need Node — a changed glyph or colour with a stale icon set is a red build, not a Project view that
 * quietly disagrees with the explorer page.
 *
 * Same shape as [SiteDocsCoverageTest]: reads the other module as text via [GoldenFiles.repoRoot].
 */
class PluginIconsSyncTest {

    private val iconsDir = File(GoldenFiles.repoRoot, "idea-plugin/src/main/resources/icons/atlas")
    private val js = resource("/frontend/explorer.js")
    private val css = resource("/frontend/explorer.css")

    private fun resource(path: String): String =
        PluginIconsSyncTest::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$path is not on the classpath")

    private fun table(name: String): Map<String, String> {
        val block = Regex("\\nconst $name=\\{\\n([\\s\\S]*?)\\n\\};\\n").find(js)?.groupValues?.get(1)
            ?: error("the $name table is gone from explorer.js")
        return block.lines().mapNotNull { line ->
            Regex("^\\s*(\\w+):'(.*)',$").find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
    }

    private fun palette(dark: Boolean): Map<String, String> {
        val cut = css.indexOf(":root[data-theme=dark]").also { assertTrue("no dark block in explorer.css", it >= 0) }
        val half = if (dark) css.substring(cut) else css.substring(0, cut)
        return Regex("--c-(\\w+):(#[0-9a-fA-F]{6})").findAll(half)
            .associate { it.groupValues[1] to it.groupValues[2].lowercase() }
    }

    private fun icon(name: String): String {
        val f = File(iconsDir, "$name.svg")
        assertTrue("missing generated icon $name.svg — run node scripts/plugin-icons.mjs", f.isFile)
        return f.readText()
    }

    private fun strokeOf(svg: String): String =
        Regex("<svg [^>]*\\bstroke=\"(#[0-9a-fA-F]{6})\"").find(svg)?.groupValues?.get(1)?.lowercase()
            ?: error("no stroke colour on the <svg> element")

    /** The generator rewrites the one `fill="currentColor"` (palette's dots) to the concrete colour. */
    private fun assertBody(name: String, body: String, svg: String) {
        val expected = body.replace("fill=\"currentColor\"", "fill=\"${strokeOf(svg)}\"")
        assertTrue("$name.svg does not carry its source body verbatim — run node scripts/plugin-icons.mjs",
            svg.contains(">$expected</svg>"))
    }

    @Test
    fun everyModelTypeIconMatchesTheExplorersGlyphAndColour() {
        val types = table("TYPE_ICONS")
        val light = palette(false)
        val dark = palette(true)
        for (t in ModelType.entries) {
            val body = types[t.id] ?: error("explorer.js has no TYPE_ICONS.${t.id}")
            val l = icon("type-${t.id}")
            val d = icon("type-${t.id}_dark")
            assertBody("type-${t.id}", body, l)
            assertBody("type-${t.id}_dark", body, d)
            // A type without a palette entry borrows --c-external (the generator's documented fallback).
            assertEquals("type-${t.id}.svg colour", light[t.id] ?: light.getValue("external"), strokeOf(l))
            assertEquals("type-${t.id}_dark.svg colour", dark[t.id] ?: dark.getValue("external"), strokeOf(d))
        }
    }

    @Test
    fun derivedChromeIconsCarryTheirSourceBodies() {
        val types = table("TYPE_ICONS")
        val ui = table("UI_ICONS")
        for (name in listOf("bot", "gutter-bot")) assertBody(name, types.getValue("bot"), icon(name))
        for (name in listOf("endpoint", "gutter-endpoint")) assertBody(name, types.getValue("endpoint"), icon(name))
        assertBody("gutter-reference", ui.getValue("link"), icon("gutter-reference"))
        assertBody("archive", types.getValue("external"), icon("archive"))
        assertEquals("archive.svg wears the app colour", palette(false).getValue("app"), strokeOf(icon("archive")))
        // The chrome pair is the New UI grey the hand-drawn hub icon already uses.
        assertEquals("#6c707e", strokeOf(icon("gutter-reference")))
        assertEquals("#ced0d6", strokeOf(icon("gutter-reference_dark")))
    }

    @Test
    fun everyGeneratedIconHasADarkSibling() {
        val names = iconsDir.listFiles { f -> f.name.endsWith(".svg") && !f.name.endsWith("_dark.svg") }!!.map { it.nameWithoutExtension }
        assertTrue("no icons found in $iconsDir", names.isNotEmpty())
        val orphans = names.filterNot { File(iconsDir, "${it}_dark.svg").isFile }
        assertTrue("icons without a _dark variant: $orphans", orphans.isEmpty())
    }
}
