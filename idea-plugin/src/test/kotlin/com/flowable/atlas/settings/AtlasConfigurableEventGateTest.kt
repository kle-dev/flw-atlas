package com.flowable.atlas.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Atlas settings page must publish when it is applied.
 *
 * The base classes make forgetting a compile error — [AtlasProjectConfigurable.apply] and
 * [AtlasApplicationConfigurable.apply] are `final`. What they cannot enforce is that a *new* page
 * extends one of them at all, and that is the gap this closes: registering a page that derives
 * straight from `BoundSearchableConfigurable` compiles happily and then silently reproduces the
 * original defect, where changing the Atlas output folder left the Hub showing the old one.
 *
 * Same shape as `SiteDocsCoverageTest`: scrape the real registration as text, check every entry, and
 * assert the scrape found something — a regex that quietly stops matching would otherwise turn this
 * into a test that passes by looking at nothing.
 */
class AtlasConfigurableEventGateTest {

    /** How many Atlas pages are registered today; the scrape must never fall below it. */
    private val minimumExpectedPages = 4

    private val registration =
        Regex("""<(project|application)Configurable\b[^>]*instance="(com\.flowable\.atlas\.[^"]+)"""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun everyRegisteredAtlasSettingsPagePublishesOnApply() {
        val pages = scrape()
        val offenders = pages.filter { (kind, fqcn) ->
            val expected =
                if (kind == "application") AtlasApplicationConfigurable::class.java else AtlasProjectConfigurable::class.java
            !expected.isAssignableFrom(Class.forName(fqcn, false, javaClass.classLoader))
        }
        assertTrue(
            "these settings pages do not publish AtlasEvents.settingsApplied() when applied — extend " +
                "AtlasProjectConfigurable or AtlasApplicationConfigurable:\n" +
                offenders.joinToString("\n") { (kind, fqcn) -> "  $fqcn (registered as ${kind}Configurable)" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun theScrapeStillFindsEveryRegisteredPage() {
        val found = scrape()
        assertTrue(
            "only ${found.size} Atlas settings page(s) were found in plugin.xml — the registration " +
                "format changed and this gate is no longer checking anything",
            found.size >= minimumExpectedPages,
        )
    }

    private fun scrape(): List<Pair<String, String>> {
        val descriptor = File("src/main/resources/META-INF/plugin.xml")
            .takeIf { it.isFile }
            ?: File("idea-plugin/src/main/resources/META-INF/plugin.xml")
        assertTrue("expected to find the plugin descriptor — did it move?", descriptor.isFile)
        return registration.findAll(descriptor.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }
}
