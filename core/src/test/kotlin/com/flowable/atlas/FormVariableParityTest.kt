package com.flowable.atlas

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ModelJsonReader
import com.flowable.atlas.parsing.ModelMemberExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The IDE's index and the CLI's graph name the same variables for a form. The IDE offers a form's
 * `variables` for completion, Go to Symbol and grounding; the graph is what every report and check is built
 * from. They used to follow two rules — the IDE took component ids (`text1`) for variables — so the plugin
 * completed names that resolve to nothing and never offered the ones the form binds.
 *
 * Walks every form and page of the two fixture projects and checks both directions: every name the IDE
 * lists is a variable the graph credits to that form, and every variable the graph credits to one of the
 * form's fields — an unbound input's id, a result button's target, the outcome variable, a field's whole
 * `{{…}}` value — is one the IDE lists.
 */
class FormVariableParityTest {

    private val fieldVias = setOf("formField", "formOutcome", "scriptButton", "restButton")
    private val plainBinding = Regex("""^\{\{\s*([A-Za-z_]\w*)[\w.\[\]]*\s*}}$""")

    @Test
    fun theIndexAndTheGraphNameTheSameVariablesForEveryForm() {
        var checked = 0
        val problems = ArrayList<String>()
        for (project in listOf(File(GoldenFiles.repoRoot, "core/src/test/resources/miniproject"), File(GoldenFiles.repoRoot, "site/flowable-demo"))) {
            @Suppress("UNCHECKED_CAST")
            val nodes = ((Atlas.extract(project)["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
            val byId = nodes.associateBy { it["id"].toString() }
            for (file in project.walkTopDown().filter { it.isFile && (it.name.endsWith(".form") || it.name.endsWith(".page")) }) {
                val type = if (file.name.endsWith(".page")) ModelType.PAGE else ModelType.FORM
                val raw = ModelMemberExtractor.extract(file.name, file.readBytes(), type).singleOrNull() ?: continue
                val nodeId = "${type.id}:${raw.key}".let { if (it in byId) it else "form:${raw.key}" }
                val node = byId[nodeId] ?: continue
                checked++
                val ide = raw.members.variables.toSet()

                // every variable the graph has a site for on this form, and which of them a field accounts for
                val credited = HashSet<String>()
                val byField = HashSet<String>()
                for (v in nodes) {
                    if (v["type"] != "variable") continue
                    @Suppress("UNCHECKED_CAST")
                    val data = v["data"] as? Map<String, Any?> ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val sites = ((data["writes"] as? List<Map<String, Any?>>).orEmpty() + (data["reads"] as? List<Map<String, Any?>>).orEmpty())
                        .filter { it["model"] == nodeId }
                    if (sites.isEmpty()) continue
                    credited.add(v["key"].toString())
                    if (sites.any { it["via"] in fieldVias }) byField.add(v["key"].toString())
                }
                @Suppress("UNCHECKED_CAST")
                for (f in ((node["data"] as Map<String, Any?>)["fields"] as? List<Map<String, Any?>>).orEmpty()) {
                    val ftype = f["type"] as? String ?: ""
                    if (ftype.lowercase().contains("button") || ftype in ModelJsonReader.BUTTON_TYPES) continue
                    val head = plainBinding.find(f["value"] as? String ?: "")?.groupValues?.get(1) ?: continue
                    if (head in credited) byField.add(head)
                }

                (ide - credited).takeIf { it.isNotEmpty() }?.let { problems.add("${file.name}: the IDE offers $it, which the graph does not credit to $nodeId") }
                (byField - ide).takeIf { it.isNotEmpty() }?.let { problems.add("${file.name}: the graph credits $it to a field of $nodeId, which the IDE does not offer") }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("the fixtures should hold forms and pages to compare, checked $checked", checked >= 6)
    }
}
