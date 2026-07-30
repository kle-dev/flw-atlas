package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.BackendModelParsers
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.script.ScriptContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer

/**
 * "Load Script from Model…": scan the indexed BPMN/CMMN/action models for every script (script
 * tasks, listener scripts, action bots — the same four sources as the explorer's Script tasks tab),
 * offer them in a speed-searchable popup, and drop the chosen body + its scriptFormat into the
 * playground. The scan parses on a cancellable background task with lock-free file reads — the
 * exact discipline of the model-index build; the index itself never stores script bodies.
 */
internal object ScriptPicker {

    data class ScriptRow(
        val fileName: String,
        val modelKey: String,
        val elementId: String?,
        val elementName: String?,
        val kind: String,          // "script task" | "listener" | "action bot"
        val format: String?,
        val body: String,
        val context: ScriptContext = ScriptContext.UNKNOWN,
    ) {
        val label: String
            get() = buildString {
                append(modelKey)
                (elementName ?: elementId)?.let { append(" · ").append(it) }
                append("  —  ").append(kind)
                format?.let { append(" (").append(it).append(")") }
                append("  ·  ").append(fileName)
            }
    }

    fun show(panel: FlowableScriptPanel) {
        val project = panel.project
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Scanning Flowable models for scripts…", true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val rows = runCatching { collectRows(project) { indicator.checkCanceled() } }
                    .getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) showPopup(panel, rows)
                }, ModalityState.any())
            }
        })
    }

    /** Pooled thread. Parses each candidate file once; a broken model is skipped, never fatal. */
    fun collectRows(project: Project, checkCanceled: () -> Unit): List<ScriptRow> {
        val index = project.service<FlowableModelIndexService>()
        val files = LinkedHashSet<VirtualFile>()
        for (type in listOf(ModelType.PROCESS, ModelType.CASE, ModelType.ACTION)) {
            index.keysOfType(type).mapTo(files) { it.file }
        }
        val out = ArrayList<ScriptRow>()
        for (file in files) {
            checkCanceled()
            if (!file.isValid) continue
            val bytes = runCatching { file.contentsToByteArray() }.getOrNull() ?: continue
            runCatching {
                when (ModelFiles.typeOf(file)) {
                    ModelType.PROCESS -> BackendModelParsers.parseBpmn(bytes, Ctx(), file.name)
                        .forEach { out += rowsFromModel(it, it["key"], file.name, ModelType.PROCESS) }
                    ModelType.CASE -> BackendModelParsers.parseCmmn(bytes, Ctx(), file.name)
                        .forEach { out += rowsFromModel(it, it["key"], file.name, ModelType.CASE) }
                    ModelType.ACTION -> out += rowsFromModel(
                        ModelParsers.parseAction(bytes, Ctx(), file.name), null, file.name, ModelType.ACTION)
                    else -> {}
                }
            }
        }
        return out.distinctBy { Triple(it.elementId, it.kind, it.body) }
    }

    private val LISTENER_KINDS = setOf("executionListener", "taskListener", "planItemLifecycleListener")
    private const val MAX_DEPTH = 32

    /**
     * Generic deep-walk over one parsed model map: any nested map holding a non-blank `"script"`
     * string is a script site. Shape-agnostic on purpose — it covers `scriptTasks`, the CMMN
     * `planModel`/`children` tree, element- and model-level `listeners` and the action record
     * without hard-coding each container, so a new script-carrying record keeps working.
     */
    internal fun rowsFromModel(
        root: Map<*, *>, modelKey: Any?, fileName: String, modelType: ModelType? = null,
    ): List<ScriptRow> {
        val out = ArrayList<ScriptRow>()
        val key = (modelKey as? String) ?: (root["key"] as? String) ?: fileName
        fun walk(node: Any?, elementId: String?, elementName: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (node) {
                is Map<*, *> -> {
                    // a map with its own id is an element and names itself; anything else (a
                    // listener record) inherits the element it hangs off
                    val ownId = (node["id"] as? String)?.ifBlank { null }
                    val id = ownId ?: elementId
                    val name = if (ownId != null) (node["name"] as? String)?.ifBlank { null } else elementName
                    val body = (node["script"] as? String)?.takeIf { it.isNotBlank() }
                    if (body != null) {
                        val listenerKind = (node["kind"] as? String)?.takeIf { it in LISTENER_KINDS }
                        val kind = when {
                            listenerKind != null -> "listener"
                            node.containsKey("botKey") -> "action bot"
                            else -> "script task"
                        }
                        val format = listOf(node["format"], node["scriptFormat"], node["scriptLanguage"])
                            .firstNotNullOfOrNull { (it as? String)?.trim()?.ifEmpty { null } }
                        out += ScriptRow(fileName, key, id, name, kind, format, body,
                            contextOf(kind, listenerKind, modelType))
                    }
                    for (v in node.values) walk(v, id, name, depth + 1)
                }
                is List<*> -> for (v in node) walk(v, elementId, elementName, depth + 1)
                else -> {}
            }
        }
        walk(root, null, null, 0)
        return out
    }

    /** Which bindings a picked script validates against — mirrors the :core parser wiring. */
    private fun contextOf(kind: String, listenerKind: String?, modelType: ModelType?): ScriptContext = when {
        kind == "action bot" -> ScriptContext.ACTION_BOT
        kind == "script task" && modelType == ModelType.PROCESS -> ScriptContext.BPMN_SCRIPT_TASK
        kind == "script task" && modelType == ModelType.CASE -> ScriptContext.CMMN_SCRIPT_TASK
        listenerKind == "executionListener" -> ScriptContext.BPMN_EXECUTION_LISTENER
        listenerKind == "taskListener" && modelType == ModelType.CASE -> ScriptContext.CMMN_TASK_LISTENER
        listenerKind == "taskListener" -> ScriptContext.BPMN_TASK_LISTENER
        else -> ScriptContext.UNKNOWN   // planItemLifecycleListener has no script support anyway
    }

    private fun showPopup(panel: FlowableScriptPanel, rows: List<ScriptRow>) {
        val factory = JBPopupFactory.getInstance()
        if (rows.isEmpty()) {
            factory.createMessage("No scripts found in the project's models.")
                .showCenteredInCurrentWindow(panel.project)
            return
        }
        factory.createPopupChooserBuilder(rows)
            .setTitle("Load Script from Model")
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { row -> panel.loadScript(row.body, row.format, row.context) }
            .createPopup()
            .showCenteredInCurrentWindow(panel.project)
    }
}
