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

    /**
     * A finding with no file of its own opens in the models it lives in: the users of an expression, the
     * service that declares an operation, the model whose edge writes a variable — never the app that merely
     * contains it. A finding with a file opens there and needs no index.
     */
    fun testAFindingWithoutAFileOpensInTheModelsItLivesIn() {
        val expr = "expression:\${vars:get('route', 'x')}"
        fun node(id: String, file: String? = null, data: Map<String, Any?> = emptyMap()) =
            mapOf("id" to id, "type" to id.substringBefore(':'), "file" to file, "data" to data)
        val result = mapOf(
            "findings" to listOf(
                mapOf("check" to "invalidExpr", "node" to expr, "label" to "\${vars:get('route', 'x')}"),
                mapOf("check" to "unusedOps", "node" to "serviceOperation:DEMO-svc#archive", "label" to "archive"),
                mapOf("check" to "unusedVars", "node" to "variable:DEMO-amount", "label" to "DEMO-amount"),
                mapOf("check" to "missingRefs", "node" to "external:DEMO-gone", "file" to "processes/DEMO-p1.bpmn"),
            ),
            "graph" to mapOf(
                "nodes" to listOf(
                    node("process:DEMO-P1", "processes/DEMO-p1.bpmn"),
                    node("process:DEMO-P2", "processes/DEMO-p2.bpmn"),
                    node("service:DEMO-svc", "services/DEMO-svc.service"),
                    node("app:DEMO-app", "apps/DEMO-app.app"),
                    node(expr, data = mapOf("usedBy" to listOf("process:DEMO-P1", "process:DEMO-P2"))),
                    node("serviceOperation:DEMO-svc#archive", data = mapOf("service" to "DEMO-svc", "usedBy" to emptyList<String>())),
                    node("variable:DEMO-amount"),
                    node("external:DEMO-gone"),
                ),
                "edges" to listOf(
                    mapOf("s" to "process:DEMO-P2", "t" to "variable:DEMO-amount", "rel" to "writes"),
                    mapOf("s" to "app:DEMO-app", "t" to "variable:DEMO-amount", "rel" to "contains"),
                    mapOf("s" to "process:DEMO-P1", "t" to "external:DEMO-gone", "rel" to "calls"),
                ),
            ),
        )
        val targets = FindingTargets.index(result)
        assertEquals(mapOf(
            expr to listOf("processes/DEMO-p1.bpmn", "processes/DEMO-p2.bpmn"),
            "serviceOperation:DEMO-svc#archive" to listOf("services/DEMO-svc.service"),
            "variable:DEMO-amount" to listOf("processes/DEMO-p2.bpmn"),
        ), targets)
        @Suppress("UNCHECKED_CAST")
        val missing = (result["findings"] as List<Map<String, Any?>>).last()
        assertEquals(listOf("processes/DEMO-p1.bpmn"), FindingTargets.filesOf(missing, targets))
    }

    /**
     * Where in a file a finding without a line is: a minified form's element by its declaration, a config
     * path by its last key, an expression as XML and JSON spell it, a variable where a model names it.
     */
    fun testAFindingWithoutALineIsFoundInItsFile() {
        val form = """{"rows":[{"cols":[{"type":"button","label":"DEMO-btn"},{"id":"DEMO-btn","type":"button"}]}]}"""
        assertEquals("the declaration, not the label that says the same",
            form.indexOf("\"id\":\"DEMO-btn\"") + 6, FindingTargets.offsetIn(form, mapOf("element" to "DEMO-btn")))

        val service = """{"key":"DEMO-svc","url":"/api/password","config":{"authentication":{"password":"s3cret"}}}"""
        assertEquals(service.indexOf("\"password\"") + 1, FindingTargets.offsetIn(service,
            mapOf("check" to "hardcodedSecrets", "node" to "service:DEMO-svc", "subject" to "config.authentication.password")))

        val bpmn = """<process id="DEMO-P1"><conditionExpression>${'$'}{amount &lt; 3}</conditionExpression></process>"""
        assertEquals(bpmn.indexOf("\${amount &lt;"), FindingTargets.offsetIn(bpmn,
            mapOf("check" to "invalidExpr", "label" to "\${amount < 3}", "subject" to "@2")))

        // a JSON model escapes the quotes inside an expression
        val json = """{"rows":[{"value":"${'$'}{a == \"x\"}"}]}"""
        assertEquals(json.indexOf("\${a =="), FindingTargets.offsetIn(json,
            mapOf("check" to "suspectExpr", "label" to "\${a == \"x\"}")))

        val writer = """<serviceTask id="t"><extensionElements><flowable:in source="x" target="DEMO-amount"/></extensionElements></serviceTask>"""
        assertEquals(writer.indexOf("DEMO-amount"), FindingTargets.offsetIn(writer,
            mapOf("check" to "unusedVars", "node" to "variable:DEMO-amount", "label" to "DEMO-amount")))

        assertNull("nothing it names is there: the file opens at the top",
            FindingTargets.offsetIn("{}", mapOf("check" to "unusedForms", "label" to "DEMO-form")))
    }

    /** The detail pane names where a finding without a file of its own opens, and how many more there are. */
    fun testTheDetailOfAFindingWithoutAFileNamesTheModelsItLivesIn() {
        val item = FindingItem(mapOf("check" to "invalidExpr", "severity" to "error", "node" to "expression:\${x}",
            "label" to "\${x}", "message" to "m", "line" to 4))
        val d = FindingsDetail.of(item, openUnder = 1, targets = listOf("processes/DEMO-p1.bpmn", "processes/DEMO-p2.bpmn"))
        assertEquals("processes/DEMO-p1.bpmn", d.file)
        assertNull("a line belongs to the finding's own file", d.line)
        assertEquals(1, d.others)
        assertEquals(0, FindingsDetail.of(FindingItem(finding("missingRefs", "process:DEMO-P1")), 1, listOf("models/x.bpmn")).others)
    }

    /** The analysis keeps, for an expression two models hold, both their files — where opening it goes. */
    fun testTheAnalysisKnowsWhereAFindingWithoutAFileLives() {
        val dir = FileUtil.createTempDirectory("atlas-findings-targets", null)
        try {
            File(dir, "processes").mkdirs()
            for (p in listOf("DEMO-P1", "DEMO-P2")) File(dir, "processes/$p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="$p">
                  <userTask id="decide"/><userTask id="a"/>
                  <sequenceFlow id="f1" sourceRef="decide" targetRef="a">
                    <conditionExpression>${'$'}{vars:get('route', 'DEMO-x')}</conditionExpression>
                  </sequenceFlow>
                </process></definitions>""")
            val analysis = AtlasFindingsService.getInstance(project).analyze(dir.toPath(), EmptyProgressIndicator())
            val f = analysis.findings.single { it["check"] == "invalidExpr" }
            assertNull("two models hold it: no file of its own", f["file"])
            assertEquals(listOf("processes/DEMO-P1.bpmn", "processes/DEMO-P2.bpmn"),
                FindingTargets.filesOf(f, analysis.targets).sorted())
            val text = File(dir, "processes/DEMO-P2.bpmn").readText()
            assertEquals(text.indexOf("\${vars:get"), FindingTargets.offsetIn(text, f))
        } finally {
            FileUtil.delete(dir)
        }
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
