package com.flowable.atlas.settings

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level Flowable Atlas settings, stored in `.idea/flowable-atlas.xml` so a team can share
 * them via VCS. Holds the expression allowlist: functions/namespaces the *project* provides
 * (custom JUEL functions, custom `flw.*` members) that the hand-maintained catalog cannot know —
 * validator findings about them are false positives and get suppressed here. Also the
 * "Pull from Flowable Design" connection (server/workspace/app/target folder; credentials live in
 * the PasswordSafe, never in this file), the Inspect connection, and the Atlas generation options
 * (artifact selection, output folder, custom-function discovery).
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableAtlasProjectSettings", storages = [Storage("flowable-atlas.xml")])
class FlowableAtlasProjectSettings : PersistentStateComponent<FlowableAtlasProjectSettings.State> {

    class State {
        /** Backend function namespaces the project registers itself (`myfns` covers every `myfns:*`). */
        var allowedNamespaces: MutableList<String> = mutableListOf()

        /** Individual functions: `myfns:doIt` (backend) or `flw.custom` (frontend). */
        var allowedFunctions: MutableList<String> = mutableListOf()

        /** Root identifiers the grounding inspection must accept (runtime-only variables, custom roots). */
        var allowedGroundingRoots: MutableList<String> = mutableListOf()

        /** Discover project custom functions (externals.additionalData) during Atlas generation. */
        var customFunctionsEnabled: Boolean = true

        /** Optional project-relative customization source for custom-function discovery. */
        var customFunctionsPath: String = ""

        /** Project-relative folder Atlas artifacts are generated into by default. */
        var atlasOutputDir: String = DEFAULT_ATLAS_OUTPUT_DIR

        /** Which artifacts the "Generate Atlas Explorer" action produces. */
        var atlasArtifacts: MutableSet<AtlasArtifact> = mutableSetOf(AtlasArtifact.EXPLORER_HTML)

        /** How generated model-constant identifiers are derived. */
        var constantNaming: ConstantNaming = ConstantNaming.NAME_AND_KEY

        /** Whether generated model constants are a class of Strings or an enum. */
        var constantFormat: ConstantFormat = ConstantFormat.CLASS

        /** Base URL of a running Flowable app for the playground's "Evaluate against app" (Inspect). */
        var inspectBaseUrl: String = ""

        /** Username for Inspect basic-auth (the password is entered per-session, never persisted). */
        var inspectUsername: String = ""

        /** Flowable Design base URL incl. context path, e.g. `http://localhost:8888/flowable-design`. */
        var designBaseUrl: String = ""

        /** Key of the Design workspace the pulled app lives in. */
        var designWorkspaceKey: String = ""

        /** Key of the Design app whose export ZIP is pulled into the project. */
        var designAppKey: String = ""

        /** Project-relative folder the pulled `{appKey}.zip` is written to. */
        var designTargetFolder: String = DEFAULT_DESIGN_TARGET_FOLDER
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

    var customFunctionsEnabled: Boolean
        get() = state.customFunctionsEnabled
        set(value) { state.customFunctionsEnabled = value }

    var customFunctionsPath: String
        get() = state.customFunctionsPath
        set(value) { state.customFunctionsPath = value }

    var atlasOutputDir: String
        get() = state.atlasOutputDir.ifBlank { DEFAULT_ATLAS_OUTPUT_DIR }
        set(value) { state.atlasOutputDir = value.ifBlank { DEFAULT_ATLAS_OUTPUT_DIR } }

    var atlasArtifacts: MutableSet<AtlasArtifact>
        get() = state.atlasArtifacts
        set(value) {
            state.atlasArtifacts = if (value.isEmpty()) mutableSetOf(AtlasArtifact.EXPLORER_HTML) else value
        }

    var constantNaming: ConstantNaming
        get() = state.constantNaming
        set(value) { state.constantNaming = value }

    var constantFormat: ConstantFormat
        get() = state.constantFormat
        set(value) { state.constantFormat = value }

    var inspectBaseUrl: String
        get() = state.inspectBaseUrl
        set(value) { state.inspectBaseUrl = value }

    var inspectUsername: String
        get() = state.inspectUsername
        set(value) { state.inspectUsername = value }

    var designBaseUrl: String
        get() = state.designBaseUrl
        set(value) { state.designBaseUrl = value }

    var designWorkspaceKey: String
        get() = state.designWorkspaceKey
        set(value) { state.designWorkspaceKey = value }

    var designAppKey: String
        get() = state.designAppKey
        set(value) { state.designAppKey = value }

    var designTargetFolder: String
        get() = state.designTargetFolder
        set(value) { state.designTargetFolder = value }

    /** True once server, workspace and app are configured — i.e. a pull can run without the dialog. */
    fun isDesignConfigured(): Boolean =
        designBaseUrl.isNotBlank() && designWorkspaceKey.isNotBlank() && designAppKey.isNotBlank()

    companion object {
        const val DEFAULT_DESIGN_TARGET_FOLDER = "flowable-models"
        const val DEFAULT_ATLAS_OUTPUT_DIR = "atlas-output"

        fun getInstance(project: Project): FlowableAtlasProjectSettings = project.service()
    }
}
