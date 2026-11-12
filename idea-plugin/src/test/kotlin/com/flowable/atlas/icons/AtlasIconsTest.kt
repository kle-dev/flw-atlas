package com.flowable.atlas.icons

import com.flowable.atlas.model.ModelType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Every icon [AtlasIcons] names exists in both theme variants, the gutter ones are gutter-sized, and
 * every `icon="/icons/atlas/…"` in plugin.xml points at a file — the descriptor is the one consumer the
 * compiler cannot check.
 */
class AtlasIconsTest : BasePlatformTestCase() {

    private fun resource(path: String): String? =
        AtlasIcons::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    fun testEveryNamedIconExistsInBothThemes() {
        val missing = AtlasIcons.names.flatMap { name ->
            listOf(AtlasIcons.path(name), AtlasIcons.path("${name}_dark")).filter { resource(it) == null }
        }
        assertTrue("icon files missing on the classpath: $missing", missing.isEmpty())
    }

    fun testIconsAreStaticSvgs() {
        // IntelliJ draws the file as-is: a currentColor left in a body renders black in both themes.
        val leaking = AtlasIcons.names.filter { resource(AtlasIcons.path(it))!!.contains("currentColor") }
        assertTrue("currentColor survives in $leaking", leaking.isEmpty())
    }

    fun testGutterIconsAreTwelvePixels() {
        for (name in AtlasIcons.names.filter { it.startsWith("gutter-") }) {
            assertTrue("$name is not 12 px", resource(AtlasIcons.path(name))!!.contains("width=\"12\" height=\"12\""))
        }
    }

    fun testEveryModelTypeHasItsOwnIcon() {
        val icons = ModelType.entries.map { AtlasIcons.forType(it) }
        assertEquals("two model types share one icon instance", ModelType.entries.size, icons.toSet().size)
    }

    fun testEveryDescriptorIconResolves() {
        val descriptor = resource("/META-INF/plugin.xml") ?: error("plugin.xml not on the test classpath")
        val referenced = Regex("icon=\"(/icons/atlas/[^\"]+)\"").findAll(descriptor).map { it.groupValues[1] }.toSet()
        val missing = referenced.filter { resource(it) == null }
        assertTrue("plugin.xml references icons that do not exist: $missing", missing.isEmpty())
    }
}
