package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Byte-exact mirror of Python `test_overview_golden`. */
class OverviewRendererTest {

    @Test
    fun overviewGolden() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        val out = OverviewRenderer.render(result, fixtureDir) + "\n"
        val golden = javaClass.classLoader.getResource("golden/miniproject.overview.md")!!.readText()
        assertEquals(golden, out)
    }
}
