package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Test
import java.io.File

/**
 * Byte-exact golden gate for [SummaryRenderer], mirroring Python's `test_summary_golden`. Runs
 * [Atlas.extract] over the `miniproject` fixture and asserts the rendered summary (plus the trailing
 * newline `print` adds) equals the committed `golden/miniproject.summary.md`.
 */
class SummaryRendererTest {

    @Test
    fun summaryGolden() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        GoldenFiles.assertMatches("miniproject.summary.md", SummaryRenderer.render(result, fixtureDir) + "\n")
    }
}
