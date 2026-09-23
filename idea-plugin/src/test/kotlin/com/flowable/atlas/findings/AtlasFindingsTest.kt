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
            val field = AtlasFindingsService::class.java.getDeclaredField("last").apply { isAccessible = true }
            field.set(service, first)
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
        val field = AtlasFindingsService::class.java.getDeclaredField("last").apply { isAccessible = true }
        field.set(service, AtlasFindingsService.Analysis(
            File(project.basePath!!).toPath(), File(project.basePath!!, "atlas-output").toPath(),
            listOf(finding("missingRefs", "process:DEMO-P1")),
        ))
        val panel = AtlasFindingsPanel(project)
        try {
            val root = panel.tree.model.root as DefaultMutableTreeNode
            assertEquals(listOf("Defects 1"), titles(root))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }
}
