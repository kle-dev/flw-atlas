package com.flowable.atlas

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `site/flowable-demo` is the project every screenshot and the live demo on the documentation site are
 * generated from. It has one job: to show what Atlas does, honestly, on something a reader could have
 * written themselves.
 *
 * ## Why it is tested
 * A sample project rots silently. Someone tidies a model, the graph loses an edge, and the published
 * screenshots quietly start showing an empty *Checks* page — which reads as "Atlas found nothing" rather
 * than "the sample lost its findings". Worse, a broken model would make the deployed demo look like a
 * broken product.
 *
 * So two invariants:
 *  - **every** health check produces at least one finding, because the site claims all thirteen are real;
 *  - the parse-issue count is exactly the one deliberately-broken file, so a genuinely broken model in
 *    the sample cannot hide behind it.
 *
 * This is deliberately not a golden test: the demo is meant to be edited freely, and pinning its output
 * byte-for-byte would make improving it annoying enough that nobody would. Only the properties the site
 * depends on are asserted.
 */
class SiteDemoProjectTest {

    private val demo = File(GoldenFiles.repoRoot, "site/flowable-demo")

    @Suppress("UNCHECKED_CAST")
    private fun checks(): Map<String, Any?> {
        assertTrue("missing the documentation site's demo project at site/flowable-demo", demo.isDirectory)
        val result = Atlas.extract(demo)
        return result["checks"] as? Map<String, Any?> ?: emptyMap()
    }

    @Test
    fun everyCheckHasSomethingToShow() {
        val counts = checks()
        val silent = Findings.CHECK_ORDER.filter { ((counts[it] as? Number)?.toInt() ?: 0) == 0 }
        assertTrue(
            "site/flowable-demo produces no finding for: $silent\n" +
                "The documentation site shows this project's Checks page as evidence that all " +
                "${Findings.CHECK_ORDER.size} checks are real, so each one needs at least one honest " +
                "example. Add a model that triggers it, or explain the gap on site/pages/checks.md.",
            silent.isEmpty(),
        )
    }

    /**
     * `broken.form` is deliberately unparseable — it is how the site demonstrates that a file Atlas
     * cannot read becomes a finding rather than a smaller project. Exactly one, so a second broken model
     * cannot slip in unnoticed.
     */
    @Test
    fun onlyTheDeliberatelyBrokenFileFailsToParse() {
        assertEquals(
            "site/flowable-demo should have exactly one parse issue (broken.form, on purpose). " +
                "A different count means a sample model is broken by accident.",
            1,
            (checks()["parseIssues"] as? Number)?.toInt() ?: 0,
        )
    }
}
