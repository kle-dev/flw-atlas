package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.inspect.InspectClient
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-user playground state (last expression, dialect, payload, scope) — deliberately stored in
 * `workspace.xml`, NOT the team-shared `.idea/flowable-atlas.xml`: a scratch expression is personal,
 * and a pasted payload may contain sample data that must not end up in VCS. With two live hosts
 * (tool window + explorer editor tab) the last writer wins; they intentionally do not live-sync.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableExprPlayground", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlowableExprPlaygroundState : PersistentStateComponent<FlowableExprPlaygroundState.State> {

    class State {
        var dialect: String = ExpressionDialect.BACKEND.name
        var expression: String = ""
        var payload: String = ""
        var scopeKey: String? = null
        var inspectScopeType: String = InspectClient.ScopeType.BPMN.name
        var inspectScopeId: String = ""
        var showSubEvaluations: Boolean = true
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    var dialect: ExpressionDialect
        get() = runCatching { ExpressionDialect.valueOf(state.dialect) }.getOrDefault(ExpressionDialect.BACKEND)
        set(value) { state.dialect = value.name }

    var expression: String
        get() = state.expression
        set(value) { state.expression = value }

    var payload: String
        get() = state.payload
        set(value) { state.payload = value }

    var scopeKey: String?
        get() = state.scopeKey
        set(value) { state.scopeKey = value }

    var inspectScopeType: InspectClient.ScopeType
        get() = runCatching { InspectClient.ScopeType.valueOf(state.inspectScopeType) }.getOrDefault(InspectClient.ScopeType.BPMN)
        set(value) { state.inspectScopeType = value.name }

    var inspectScopeId: String
        get() = state.inspectScopeId
        set(value) { state.inspectScopeId = value }

    var showSubEvaluations: Boolean
        get() = state.showSubEvaluations
        set(value) { state.showSubEvaluations = value }

    companion object {
        fun getInstance(project: Project): FlowableExprPlaygroundState = project.service()
    }
}
