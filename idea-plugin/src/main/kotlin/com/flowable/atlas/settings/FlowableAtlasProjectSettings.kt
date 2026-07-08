package com.flowable.atlas.settings

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level Flowable Atlas settings, stored in `.idea/flowableAtlas.xml` so a team can share
 * them via VCS. Currently the expression allowlist: functions/namespaces the *project* provides
 * (custom JUEL functions, custom `flw.*` members) that the hand-maintained catalog cannot know —
 * validator findings about them are false positives and get suppressed here.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableAtlasProjectSettings", storages = [Storage("flowableAtlas.xml")])
class FlowableAtlasProjectSettings : PersistentStateComponent<FlowableAtlasProjectSettings.State> {

    class State {
        /** Backend function namespaces the project registers itself (`myfns` covers every `myfns:*`). */
        var allowedNamespaces: MutableList<String> = mutableListOf()

        /** Individual functions: `myfns:doIt` (backend) or `flw.custom` (frontend). */
        var allowedFunctions: MutableList<String> = mutableListOf()

        /** Root identifiers the grounding inspection must accept (runtime-only variables, custom roots). */
        var allowedGroundingRoots: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    /** True when [problem] refers to an allowlisted namespace/function/root — i.e. must not be reported. */
    fun isAllowlisted(problem: ExprProblem): Boolean {
        val subject = problem.subject ?: return false
        return when (problem.kind) {
            ExprProblemKind.UNKNOWN_NAMESPACE -> subject in state.allowedNamespaces
            ExprProblemKind.UNKNOWN_FUNCTION ->
                subject in state.allowedFunctions ||
                    subject.substringBefore(':', "").takeIf { it.isNotEmpty() } in state.allowedNamespaces
            ExprProblemKind.UNKNOWN_ROOT -> subject in state.allowedGroundingRoots
            else -> false
        }
    }

    fun allow(subject: String, kind: ExprProblemKind) {
        val target = when (kind) {
            ExprProblemKind.UNKNOWN_NAMESPACE -> state.allowedNamespaces
            ExprProblemKind.UNKNOWN_FUNCTION -> state.allowedFunctions
            ExprProblemKind.UNKNOWN_ROOT -> state.allowedGroundingRoots
            else -> return
        }
        if (subject !in target) target.add(subject)
    }

    companion object {
        fun getInstance(project: Project): FlowableAtlasProjectSettings = project.service()
    }
}
