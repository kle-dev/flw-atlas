package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.ScriptContext
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-user Script Playground state (last script + language) — `workspace.xml`, NOT the team-shared
 * `.idea/flowable-atlas.xml`, for the same reason as [com.flowable.atlas.expr.toolwindow.FlowableExprPlaygroundState]:
 * a pasted script is personal scratch content that must not end up in VCS.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableScriptPlayground", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlowableScriptPlaygroundState : PersistentStateComponent<FlowableScriptPlaygroundState.State> {

    class State {
        var script: String = ""
        /** The raw `scriptFormat` string ("groovy", "javascript", "js", …) — kept verbatim so a
         *  script loaded from a model validates under exactly the format the model declares. */
        var format: String = "groovy"
        /** [com.flowable.atlas.script.ScriptContext] name — decides which bindings validate. */
        var context: String = ScriptContext.BPMN_SCRIPT_TASK.name
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    var script: String
        get() = state.script
        set(value) { state.script = value }

    var format: String
        get() = state.format.ifBlank { "groovy" }
        set(value) { state.format = value.ifBlank { "groovy" } }

    var context: ScriptContext
        get() = runCatching { ScriptContext.valueOf(state.context) }.getOrDefault(ScriptContext.BPMN_SCRIPT_TASK)
        set(value) { state.context = value.name }

    companion object {
        fun getInstance(project: Project): FlowableScriptPlaygroundState = project.service()
    }
}
