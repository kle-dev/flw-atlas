package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Test
import java.io.File

/** Byte-exact golden gate for the full Markdown report. */
class OverviewRendererTest {

    @Test
    fun overviewGolden() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        GoldenFiles.assertMatches("miniproject.overview.md", OverviewRenderer.render(result, fixtureDir) + "\n")
    }
}
