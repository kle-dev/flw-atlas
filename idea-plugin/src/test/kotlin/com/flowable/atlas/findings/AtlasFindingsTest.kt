package com.flowable.atlas.findings

import com.flowable.atlas.findings.FindingsTree.CheckItem
import com.flowable.atlas.findings.FindingsTree.FindingItem
import com.flowable.atlas.findings.FindingsTree.Group
import com.flowable.atlas.graph.Waivers
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The findings tool window: the analysis the explorer is generated from, grouped as defects / advice /
 * accepted, and Accept writing the explorer's own waivers.json. DEMO-* names: the repo is public.
 */
class AtlasFindingsTest : BasePlatformTestCase() {

    private fun finding(check: String, node: String, waived: Boolean = false) = linkedMapOf<String, Any?>(
        "check" to check, "severity" to "error", "node" to node, "label" to node.substringAfter(':'),
        "file" to "models/x.bpmn", "line" to 3, "message" to "m",
    ).apply { if (waived) put("waived", mapOf("reason" to "ok")) }

    private fun titles(n: DefaultMutableTreeNode): List<String> = (0 until n.childCount).map {
        when (val o = (n.getChildAt(it) as DefaultMutableTreeNode).userObject) {
            is Group -> "${o.title} ${o.count}"
            is CheckItem -> "${o.id} ${o.count}"
            is FindingItem -> o.label
            else -> "?"
        }
    }

    fun testDefectsComeFirstAndAdviceAndAcceptedOnlyWhenAskedFor() {
        val fs = listOf(finding("missingRefs", "process:DEMO-P1"), finding("unusedForms", "form:DEMO-F1"),
            finding("missingRefs", "process:DEMO-P2", waived = true))
        assertEquals(listOf("Defects 1"), titles(FindingsTree.build(fs, showAdvice = false, showAccepted = false)))
        val all = FindingsTree.build(fs, showAdvice = true, showAccepted = true)
        assertEquals(listOf("Defects 1", "Advice 1", "Accepted 1"), titles(all))
        val defects = all.getChildAt(0) as DefaultMutableTreeNode
        assertEquals(listOf("missingRefs 1"), titles(defects))
        assertEquals(listOf("DEMO-P1"), titles(defects.getChildAt(0) as DefaultMutableTreeNode))
    }

    fun testAnAdviceFindingIsMarkedAsAdviceWhateverItsSeverity() {
        assertTrue(FindingItem(finding("unusedForms", "form:DEMO-F1")).isAdvice)
        assertFalse(FindingItem(finding("missingRefs", "process:DEMO-P1")).isAdvice)
    }

    fun testAcceptingWritesTheExplorersWaiverFileAndTheNextAnalysisHonoursIt() {
        val dir = FileUtil.createTempDirectory("atlas-findings", null)
        try {
            File(dir, "models").mkdirs()
            // a call to a process nobody defines: a missingRefs defect
            File(dir, "models/DEMO-P001.bpmn").writeText(
                """<definitions><process id="DEMO-P001"><callActivity id="c" calledElement="DEMO-NOPE"/></process></definitions>""")
            val service = AtlasFindingsService.getInstance(project)
            val first = service.analyze(dir.toPath(), EmptyProgressIndicator())
            val missing = first.findings.filter { it["check"] == "missingRefs" && it["waived"] == null }
            assertTrue("expected a missingRefs finding: ${first.findings}", missing.isNotEmpty())

            // accept() works on the last analysis; seed it the way refresh() would
            service.seedForTest(first)
            service.accept(missing, "the callee lives in another app")
            val written = Waivers.load(File(dir, "atlas-output/${Waivers.FILE_NAME}"))
            assertEquals(listOf("the callee lives in another app"), written.waivers.map { it.reason }.distinct())

            val second = service.analyze(dir.toPath(), EmptyProgressIndicator())
            assertTrue(second.findings.filter { it["check"] == "missingRefs" }.all { it["waived"] != null })
        } finally {
            FileUtil.delete(dir)
        }
    }

    fun testThePanelShowsTheLastAnalysis() {
        val service = AtlasFindingsService.getInstance(project)
        service.seedForTest(AtlasFindingsService.Analysis(
            File(project.basePath!!).toPath(), File(project.basePath!!, "atlas-output").toPath(),
            listOf(finding("missingRefs", "process:DEMO-P1")),
        ))
        val panel = AtlasFindingsPanel(project)
        try {
            val root = panel.tree.model.root as DefaultMutableTreeNode
            assertEquals(listOf("Defects 1"), titles(root))
            // The status line says how current it is, in the counts the Hub's health row uses.
            assertTrue(panel.statusForTest, panel.statusForTest.startsWith("1 defect · 0 advice · analyzed "))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            service.seedForTest(null)
        }
    }

    /**
     * A generation runs the very analysis the window shows, so its findings are taken over — for the
     * project the window is about, and for no other folder.
     */
    fun testAGenerationHandsItsFindingsOver() {
        val service = AtlasFindingsService.getInstance(project)
        val root = File(project.basePath!!).toPath()
        try {
            service.adopt(root.resolve("elsewhere"), root.resolve("atlas-output"), listOf(finding("missingRefs", "process:DEMO-P1")))
            assertNull("another folder's analysis is not this project's", service.last)
            service.adopt(root, root.resolve("atlas-output"), listOf(finding("missingRefs", "process:DEMO-P1")))
            assertEquals(1, service.last?.defects)
        } finally {
            service.seedForTest(null)
        }
    }

    /** A model newer than the analysis makes it stale — said by the service, not re-run behind anyone's back. */
    fun testAModelNewerThanTheAnalysisMakesItStale() {
        myFixture.addFileToProject("models/DEMO-P001.bpmn", """<definitions><process id="DEMO-P001"/></definitions>""")
        project.getService(com.flowable.atlas.index.FlowableModelIndexService::class.java).index()
        val service = AtlasFindingsService.getInstance(project)
        val root = File(project.basePath!!).toPath()
        try {
            service.seedForTest(AtlasFindingsService.Analysis(root, root, emptyList(), atMillis = 1L))
            assertTrue(service.stale)
            service.seedForTest(AtlasFindingsService.Analysis(root, root, emptyList(), atMillis = Long.MAX_VALUE))
            assertFalse(service.stale)
        } finally {
            service.seedForTest(null)
        }
    }

    /** The detail pane says what the explorer's Checks page says: why, what to do, and where to read on. */
    fun testTheDetailOfAFindingExplainsItsCheck() {
        val d = FindingsDetail.of(FindingItem(finding("missingRefs", "process:DEMO-P1")), openUnder = 1)
        assertEquals("Missing model refs", d.title)
        assertTrue(d.kind!!, d.kind!!.startsWith("Defect · "))
        assertEquals("DEMO-P1 — m", d.finding)
        assertEquals("models/x.bpmn" to 3, d.file to d.line)
        assertEquals("the model's page", "process%3ADEMO-P1", d.route)
        assertFalse(d.why.isNullOrBlank())
        assertFalse(d.fix.isNullOrBlank())
        assertTrue(d.docsUrl!!.contains("/checks/#"))
        assertEquals(1, d.acceptable)

        val check = FindingsDetail.of(CheckItem("missingRefs", "Missing model refs", 2), openUnder = 2)
        assertEquals("the check on the Checks page", "/checks&f=Missing%20model%20refs", check.route)
        assertEquals(2, check.acceptable)

        val accepted = FindingsDetail.of(FindingItem(finding("missingRefs", "process:DEMO-P2", waived = true)), openUnder = 0)
        assertEquals(0, accepted.acceptable)
        assertEquals("ok", accepted.accepted)
    }

    /** Accept asks why, and does not close on an empty answer — the old input dialog took that for Cancel. */
    fun testAcceptRefusesAnEmptyReason() {
        val dialog = AcceptFindingsDialog(project, 2)
        try {
            assertNotNull(dialog.validateWith("   "))
            assertNull(dialog.validateWith("the callee lives in another app"))
            assertEquals("the callee lives in another app", dialog.reason)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(dialog.disposable)
        }
    }
}
