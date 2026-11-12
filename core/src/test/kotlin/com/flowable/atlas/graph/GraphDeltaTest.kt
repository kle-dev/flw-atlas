package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.render.GraphJsonRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [GraphDelta] on hand-built results, one kind of change at a time; the whole of it, on two versions of the
 * miniproject, is pinned by the `miniproject.changes.md` golden (ChangesRendererTest).
 */
@Suppress("UNCHECKED_CAST")
class GraphDeltaTest {

    private fun process(key: String, vararg members: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("key" to key, "name" to "DEMO $key", "file" to "processes/$key.bpmn").apply { putAll(members) }

    private fun result(
        processes: List<Map<String, Any?>> = emptyList(),
        forms: List<Map<String, Any?>> = emptyList(),
        cases: List<Map<String, Any?>> = emptyList(),
        edges: List<Map<String, Any?>> = emptyList(),
        findings: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> = linkedMapOf(
        "processes" to processes, "forms" to forms, "cases" to cases,
        "graph" to mapOf("nodes" to emptyList<Any?>(), "edges" to edges),
        "findings" to findings,
    )

    private fun edge(s: String, rel: String, t: String) = mapOf("s" to s, "rel" to rel, "t" to t)

    private val base = result(
        processes = listOf(process("DEMO-order",
            "userTasks" to listOf(mapOf("id" to "review", "name" to "Review", "formKey" to "DEMO-form")),
            "flows" to listOf(mapOf("id" to "f1", "from" to "start", "to" to "review")),
            "callActivities" to emptyList<Any?>(),
            "documentation" to "Takes an order.",
        )),
        forms = listOf(linkedMapOf("key" to "DEMO-form", "modelType" to "form", "file" to "forms/f.form",
            "fields" to listOf(mapOf("id" to "amount", "type" to "number"), mapOf("id" to "note", "type" to "text")))),
        edges = listOf(edge("process:DEMO-order", "userTask-form", "form:DEMO-form"), edge("app:DEMO-app", "contains", "process:DEMO-order")),
    )

    @Test
    fun theSameResultTwiceIsNoChange() {
        val d = GraphDelta.of(base, base)
        assertTrue(d.isEmpty)
    }

    /**
     * graph.json does not write a member that is null, so the side read back from a file lacks what the
     * analysed side holds as null — on the model, on an element, and inside an element's own values.
     */
    @Test
    fun aNullMemberAndAMissingOneAreTheSame() {
        fun order(vararg assignee: Pair<String, Any?>) = result(processes = listOf(process("DEMO-order",
            "userTasks" to listOf(linkedMapOf<String, Any?>("id" to "review", "name" to "Review",
                "settings" to linkedMapOf<String, Any?>("enabled" to false, *assignee), *assignee)),
            *assignee)))
        val analysed = order("assignee" to null)
        val readBack = order()
        assertTrue(GraphDelta.of(readBack, analysed).isEmpty)
        assertTrue(GraphDelta.of(analysed, readBack).isEmpty)
        val set = GraphDelta.of(readBack, order("assignee" to "DEMO-clerk")).modelsChanged.single()
        assertEquals("a value where there was none is still a change", listOf("assignee"), set.properties)
        assertEquals(listOf("assignee", "settings"), set.changed.single().fields)
    }

    @Test
    fun aTaskAddedIsAnElementAddedAndAFlowReroutedIsAChangedOne() {
        val head = result(
            processes = listOf(process("DEMO-order",
                "userTasks" to listOf(
                    mapOf("id" to "review", "name" to "Review", "formKey" to "DEMO-form"),
                    mapOf("id" to "approve", "name" to "Approve"),
                ),
                "flows" to listOf(mapOf("id" to "f1", "from" to "start", "to" to "approve")),
                "callActivities" to emptyList<Any?>(),
                "documentation" to "Takes an order.",
            )),
            forms = base["forms"] as List<Map<String, Any?>>,
            edges = (base["graph"] as Map<*, *>)["edges"] as List<Map<String, Any?>>,
        )
        val d = GraphDelta.of(base, head)
        val c = d.modelsChanged.single()
        assertEquals("process:DEMO-order", c.model.id)
        assertEquals(listOf("approve"), c.added)
        assertEquals(listOf("f1"), c.changed.map { it.id })
        assertEquals("the property that changed, not the whole flow", listOf("to"), c.changed.single().fields)
        assertTrue(c.removed.isEmpty() && c.properties.isEmpty())
        assertTrue(d.modelsAdded.isEmpty() && d.edgesAdded.isEmpty() && d.findingsNew.isEmpty())
    }

    @Test
    fun aRenamedFieldIsOneRemovedAndOneAdded() {
        val head = result(
            processes = base["processes"] as List<Map<String, Any?>>,
            forms = listOf(linkedMapOf("key" to "DEMO-form", "modelType" to "form", "file" to "forms/f.form",
                "fields" to listOf(mapOf("id" to "amount", "type" to "number"), mapOf("id" to "remark", "type" to "text")))),
            edges = (base["graph"] as Map<*, *>)["edges"] as List<Map<String, Any?>>,
        )
        val c = GraphDelta.of(base, head).modelsChanged.single()
        assertEquals("form:DEMO-form", c.model.id)
        assertEquals(listOf("remark"), c.added)
        assertEquals(listOf("note"), c.removed)
    }

    @Test
    fun aDanglingCallIsAnElementAReferenceAndANewFinding() {
        val missing = mapOf("check" to "missingRefs", "severity" to "error", "node" to "external:DEMO-nowhere",
            "label" to "DEMO-nowhere", "message" to "referenced model does not exist in this project")
        val head = result(
            processes = listOf(process("DEMO-order",
                "userTasks" to listOf(mapOf("id" to "review", "name" to "Review", "formKey" to "DEMO-form")),
                "flows" to listOf(mapOf("id" to "f1", "from" to "start", "to" to "review")),
                "callActivities" to listOf(mapOf("id" to "call", "calledElement" to "DEMO-nowhere")),
                "documentation" to "Takes an order and books a courier.",
            )),
            forms = base["forms"] as List<Map<String, Any?>>,
            edges = (base["graph"] as Map<*, *>)["edges"] as List<Map<String, Any?>> +
                edge("process:DEMO-order", "callActivity", "external:DEMO-nowhere"),
            findings = listOf(missing),
        )
        val d = GraphDelta.of(base, head)
        val c = d.modelsChanged.single()
        assertEquals("a list that was empty is where the element appears", listOf("call"), c.added)
        assertEquals(listOf("documentation"), c.properties)
        assertEquals(listOf(GraphDelta.Edge("process:DEMO-order", "callActivity", "external:DEMO-nowhere")), d.edgesAdded)
        assertEquals(listOf(missing), d.findingsNew)
        assertTrue(d.findingsResolved.isEmpty())

        val back = GraphDelta.of(head, base)
        assertEquals("the way back resolves it", listOf(missing), back.findingsResolved)
        assertEquals(listOf("call"), back.modelsChanged.single().removed)
    }

    @Test
    fun modelsComeAndGoByTypeAndKey() {
        val head = result(
            processes = base["processes"] as List<Map<String, Any?>>,
            // the same key as a page: another model
            forms = listOf(linkedMapOf("key" to "DEMO-form", "modelType" to "page", "name" to "DEMO page", "file" to "pages/f.page")),
        )
        val d = GraphDelta.of(base, head)
        assertEquals(listOf("page:DEMO-form"), d.modelsAdded.map { it.id })
        assertEquals("DEMO page", d.modelsAdded.single().name)
        assertEquals(listOf("form:DEMO-form"), d.modelsRemoved.map { it.id })
        assertEquals("app membership is not a reference to review",
            listOf(GraphDelta.Edge("process:DEMO-order", "userTask-form", "form:DEMO-form")), d.edgesRemoved)
    }

    /**
     * Where a model is written and what the analysis derived from it are not the model: a script whose
     * problems the validator now sees differently has not changed — the finding says what did.
     */
    @Test
    fun movingAFileShiftingLinesAndDerivedValuesChangeNothing() {
        fun order(file: String, line: Int, uses: List<String>, problems: List<String>) = linkedMapOf<String, Any?>(
            "key" to "DEMO-order", "file" to file, "_uses" to mapOf("variable" to uses),
            "scriptTasks" to listOf(mapOf("id" to "s", "script" to "x()", "line" to line,
                "problems" to problems.map { mapOf("kind" to it) })),
        )
        val d = GraphDelta.of(
            result(processes = listOf(order("processes/order.bpmn", 1, listOf("variable:a"), emptyList()))),
            result(processes = listOf(order("app.zip!processes/order.bpmn", 7, listOf("variable:a", "variable:b"), listOf("unknown-member")))),
        )
        assertTrue(d.modelsChanged.map { it.model.id }.toString(), d.isEmpty)
    }

    /** A case's plan items sit inside its plan model and its stages: a task added to a stage is that task, not the stage. */
    @Test
    fun nestedElementsAreElementsOfTheirOwn() {
        fun case(vararg tasks: String) = linkedMapOf<String, Any?>(
            "key" to "DEMO-case", "file" to "cases/c.cmmn",
            "planModel" to mapOf("id" to "plan", "name" to "Plan", "children" to listOf(
                mapOf("id" to "stage", "type" to "stage", "children" to tasks.map { mapOf("id" to it, "type" to "humanTask") }),
            )),
        )
        val c = GraphDelta.of(result(cases = listOf(case("a"))), result(cases = listOf(case("a", "b")))).modelsChanged.single()
        assertEquals("case:DEMO-case", c.model.id)
        assertEquals(listOf("b"), c.added)
        assertTrue("the stage and the plan are unchanged: ${c.changed.map { it.id }}", c.changed.isEmpty() && c.properties.isEmpty())
    }

    /** [base] with the form held in a second file as well, saying [export]. */
    private fun withExport(export: String, file: String = "design/app.zip!form-models/f.json", from: Map<String, Any?> = base) =
        LinkedHashMap(from).apply { put("modelCopies", mapOf("form:DEMO-form" to mapOf("forms/f.form" to "0000", file to export))) }

    /**
     * A model in two files — a Design export beside its deployment — is compared in the copy the graph shows,
     * and its other copy by what it says: an export changed beside an unchanged deployment is the model
     * changed there, named by that file; the same export under another name is no change.
     */
    @Test
    fun aChangeToAnotherCopyAloneIsNamedByItsFile() {
        val c = GraphDelta.of(withExport("aaaa"), withExport("bbbb")).modelsChanged.single()
        assertEquals("form:DEMO-form", c.model.id)
        assertEquals(listOf("design/app.zip!form-models/f.json"), c.elsewhere)
        assertTrue(c.added.isEmpty() && c.removed.isEmpty() && c.changed.isEmpty() && c.properties.isEmpty())

        assertTrue("moved, not changed", GraphDelta.of(withExport("aaaa"), withExport("aaaa", file = "design/other.zip!f.json")).isEmpty)
        assertEquals("a copy that is gone is named by the base's file",
            listOf("design/app.zip!form-models/f.json"), GraphDelta.of(withExport("aaaa"), base).modelsChanged.single().elsewhere)

        val renamed = result(
            processes = base["processes"] as List<Map<String, Any?>>,
            forms = listOf(linkedMapOf("key" to "DEMO-form", "modelType" to "form", "file" to "forms/f.form",
                "fields" to listOf(mapOf("id" to "amount", "type" to "number"), mapOf("id" to "remark", "type" to "text")))),
            edges = (base["graph"] as Map<*, *>)["edges"] as List<Map<String, Any?>>,
        )
        val both = GraphDelta.of(withExport("aaaa"), withExport("bbbb", from = renamed)).modelsChanged.single()
        assertEquals("the shown copy changed: its elements say what, the export adds nothing", listOf("remark"), both.added)
        assertTrue(both.elsewhere.isEmpty())
    }

    /**
     * The same through extraction: a form loose and inside an app archive. The graph shows the loose one, and
     * a field renamed in the archive's copy alone is that copy changed — and either side may be a graph.json.
     */
    @Test
    fun anExtractionKeepsWhatEveryCopySays() {
        fun form(field: String) = """{"metadata": {"key": "DEMO-copied", "name": "DEMO copied", "modelType": "form"},
            "rows": [[{"id": "$field", "type": "text", "label": "Note"}]]}"""
        fun project(archived: String): File = Files.createTempDirectory("atlas-copies").toFile().also { dir ->
            File(dir, "forms").mkdirs()
            File(dir, "forms/copied.form").writeText(form("note"))
            File(dir, "apps").mkdirs()
            ZipOutputStream(File(dir, "apps/app.zip").outputStream()).use { z ->
                z.putNextEntry(ZipEntry("copied.form"))
                z.write(form(archived).toByteArray())
                z.closeEntry()
            }
        }
        val base = Atlas.extract(project("note"))
        val head = Atlas.extract(project("remark"))
        val copies = base["modelCopies"] as Map<String, Map<String, String>>
        assertEquals(listOf("apps/app.zip!copied.form", "forms/copied.form"), copies["form:DEMO-copied"]!!.keys.toList())

        val c = GraphDelta.of(base, head).modelsChanged.single()
        assertEquals("form:DEMO-copied", c.model.id)
        assertEquals(listOf("apps/app.zip!copied.form"), c.elsewhere)
        assertTrue("the loose copy is the same", c.added.isEmpty() && c.removed.isEmpty())

        val back = MiniJson.parse(GraphJsonRenderer.render(base)) as Map<String, Any?>
        assertTrue(GraphDelta.of(back, base).isEmpty)
        assertEquals(listOf("apps/app.zip!copied.form"), GraphDelta.of(back, head).modelsChanged.single().elsewhere)
    }

    /**
     * Either side may be a graph.json read back, where every number is a Double and a model node's data is a
     * pointer into its bucket: compared with the run that wrote it, nothing changed.
     */
    @Test
    fun aGraphJsonReadBackIsTheResultThatWroteIt() {
        val fixture = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixture)
        @Suppress("UNCHECKED_CAST")
        val back = MiniJson.parse(GraphJsonRenderer.render(result)) as Map<String, Any?>
        val d = GraphDelta.of(back, result)
        assertTrue("models ${d.modelsChanged.map { "${it.model.id} ${it.changed.map { e -> e.id + e.fields }} ${it.properties}" }}, " +
            "edges ${d.edgesAdded} ${d.edgesRemoved}, findings ${d.findingsNew} ${d.findingsResolved}", d.isEmpty)
        assertTrue("…and the fixture has models to compare", (result["processes"] as List<*>).isNotEmpty())
    }
}
