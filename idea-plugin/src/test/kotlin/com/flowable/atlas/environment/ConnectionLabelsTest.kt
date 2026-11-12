package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLabelsTest {

    private fun connection(env: String, url: String = "http://work-qa.example.com", protected: Boolean = false) =
        AtlasConnection("c", ConnectionKind.WORK, url, "demo", AuthMode.BASIC, "e", env, protected)

    @Test
    fun `the narrow line names the environment and nothing else`() {
        // No base URL: this row lives in a side panel a few hundred pixels wide, and a URL there is
        // what forces horizontal scrolling.
        val line = ConnectionLabels.narrow(ConnectionKind.WORK, Resolution.Selected(connection("QA"), true))
        assertEquals("<html>Work: <b>QA</b></html>", line)
        assertFalse(line.contains("example.com"))
    }

    @Test
    fun `not set and connection removed read differently`() {
        // Two situations that need two different fixes: nothing was ever picked, versus a pick that
        // something else took away.
        assertTrue(ConnectionLabels.narrow(ConnectionKind.DESIGN, Resolution.NotSet).contains("not set"))
        assertTrue(
            ConnectionLabels.narrow(ConnectionKind.DESIGN, Resolution.Dangling("gone")).contains("connection removed"),
        )
    }

    @Test
    fun `a long environment name is shortened and kept whole in the tooltip`() {
        val long = "Customer acceptance environment two"
        val line = ConnectionLabels.narrow(ConnectionKind.WORK, Resolution.Selected(connection(long), true))
        assertFalse("a 35-character name would push Change… off the edge of a side panel", line.contains(long))
        assertTrue("whole in the tooltip, which is where a detail nobody scans for belongs", ConnectionLabels.tooltip(
            ConnectionKind.WORK, Resolution.Selected(connection(long), true),
        )!!.contains(long))
    }

    @Test
    fun `a name that looks like markup cannot become markup`() {
        val line = ConnectionLabels.narrow(ConnectionKind.WORK, Resolution.Selected(connection("<b>QA"), true))
        assertTrue(line.contains("&lt;b&gt;QA"))
    }

    @Test
    fun `the tooltip carries the url and says what protected means`() {
        val tooltip = ConnectionLabels.tooltip(
            ConnectionKind.WORK,
            Resolution.Selected(connection("PROD", "http://work.example.com", protected = true), true),
        )
        assertNotNull(tooltip)
        assertTrue(tooltip!!.contains("http://work.example.com"))
        assertTrue(tooltip.contains("Protected"))
    }

    @Test
    fun `the apps label stops listing keys past three`() {
        assertEquals("no apps selected", ConnectionLabels.appsLabel(emptyList()))
        assertEquals("DEMO-a", ConnectionLabels.appsLabel(listOf("DEMO-a")))
        assertEquals("DEMO-a, DEMO-b, DEMO-c", ConnectionLabels.appsLabel(listOf("DEMO-a", "DEMO-b", "DEMO-c")))
        assertEquals("4 apps selected", ConnectionLabels.appsLabel(listOf("a", "b", "c", "d")))
    }
}
