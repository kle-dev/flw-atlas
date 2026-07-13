package com.flowable.atlas.settings

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level Flowable Atlas settings, stored in `.idea/flowable-atlas.xml` so a team can share
 * them via VCS. Holds the expression allowlist, the "Pull from Flowable Design" connection, the
 * Inspect connection and the Atlas generation options.
 *
 * These are **scoped per Flowable sub-project** ([AtlasScope]) so that a monorepo whose IntelliJ root
 * holds several apps can point each one at a different Design app, output folder or allowlist. The
 * historical single-project layout is preserved verbatim as the *default* scope: the flat fields on
 * [State] are the `""` (whole-project) scope, and additional sub-projects live in [State.subProjects].
 * An older flat file therefore deserializes with **no migration** and a single-project user sees
 * byte-identical XML and behavior. The *active* scope is chosen by
 * [com.flowable.atlas.project.AtlasProjectRootService] (workspace-local), and every public accessor
 * below transparently reads/writes that active scope.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableAtlasProjectSettings", storages = [Storage("flowable-atlas.xml")])
class FlowableAtlasProjectSettings(private val project: Project?) :
    PersistentStateComponent<FlowableAtlasProjectSettings.State> {

    /** The tunable surface that differs per sub-project. */
    interface AtlasScope {
        var allowedNamespaces: MutableList<String>
        var allowedFunctions: MutableList<String>
        var allowedGroundingRoots: MutableList<String>
        var customFunctionsEnabled: Boolean
        var customFunctionsPath: String
        var atlasOutputDir: String
        var atlasArtifacts: MutableSet<AtlasArtifact>
        var constantNaming: ConstantNaming
        var constantFormat: ConstantFormat
        var inspectBaseUrl: String
        var inspectUsername: String
        var designBaseUrl: String
        var designWorkspaceKey: String
        var designAppKey: String
        var designTargetFolder: String
    }

    /** One non-default sub-project's scope; [path] (root-relative) is its identity in [State.subProjects]. */
    class SubProjectState() : AtlasScope {
        var path: String = ""
        override var allowedNamespaces: MutableList<String> = mutableListOf()
        override var allowedFunctions: MutableList<String> = mutableListOf()
        override var allowedGroundingRoots: MutableList<String> = mutableListOf()
        override var customFunctionsEnabled: Boolean = true
        override var customFunctionsPath: String = ""
        override var atlasOutputDir: String = DEFAULT_ATLAS_OUTPUT_DIR
        override var atlasArtifacts: MutableSet<AtlasArtifact> = mutableSetOf(AtlasArtifact.EXPLORER_HTML)
        override var constantNaming: ConstantNaming = ConstantNaming.NAME_AND_KEY
        override var constantFormat: ConstantFormat = ConstantFormat.CLASS
        override var inspectBaseUrl: String = ""
        override var inspectUsername: String = ""
        override var designBaseUrl: String = ""
        override var designWorkspaceKey: String = ""
        override var designAppKey: String = ""
        override var designTargetFolder: String = DEFAULT_DESIGN_TARGET_FOLDER

        constructor(path: String) : this() { this.path = path }

        /** True when nothing is configured — such entries are pruned so they never touch the XML. */
        fun isUnconfigured(): Boolean =
            allowedNamespaces.isEmpty() && allowedFunctions.isEmpty() && allowedGroundingRoots.isEmpty() &&
                customFunctionsEnabled && customFunctionsPath.isEmpty() &&
                atlasOutputDir == DEFAULT_ATLAS_OUTPUT_DIR &&
                atlasArtifacts == mutableSetOf(AtlasArtifact.EXPLORER_HTML) &&
                constantNaming == ConstantNaming.NAME_AND_KEY && constantFormat == ConstantFormat.CLASS &&
                inspectBaseUrl.isEmpty() && inspectUsername.isEmpty() &&
                designBaseUrl.isEmpty() && designWorkspaceKey.isEmpty() && designAppKey.isEmpty() &&
                designTargetFolder == DEFAULT_DESIGN_TARGET_FOLDER
    }

    class State {
        // ---- the "" (whole-project) scope: the historical flat fields, verbatim ----
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

        /** Username for Inspect basic-auth (the password lives in the PasswordSafe, never in this XML). */
        var inspectUsername: String = ""

        /** Flowable Design base URL incl. context path, e.g. `http://localhost:8888/flowable-design`. */
        var designBaseUrl: String = ""

        /** Key of the Design workspace the pulled app lives in. */
        var designWorkspaceKey: String = ""

        /** Key of the Design app whose export ZIP is pulled into the project. */
        var designAppKey: String = ""

        /** Project-relative folder the pulled `{appKey}.zip` is written to. */
        var designTargetFolder: String = DEFAULT_DESIGN_TARGET_FOLDER

        // ---- additional sub-projects (path != "") ----
        var subProjects: MutableList<SubProjectState> = mutableListOf()
    }

    private var state = State()

    /** Adapts the flat [State] fields as the `""` scope, always reading the current [state]. */
    private val defaultScope = object : AtlasScope {
        override var allowedNamespaces: MutableList<String>
            get() = state.allowedNamespaces; set(v) { state.allowedNamespaces = v }
        override var allowedFunctions: MutableList<String>
            get() = state.allowedFunctions; set(v) { state.allowedFunctions = v }
        override var allowedGroundingRoots: MutableList<String>
            get() = state.allowedGroundingRoots; set(v) { state.allowedGroundingRoots = v }
        override var customFunctionsEnabled: Boolean
            get() = state.customFunctionsEnabled; set(v) { state.customFunctionsEnabled = v }
        override var customFunctionsPath: String
            get() = state.customFunctionsPath; set(v) { state.customFunctionsPath = v }
        override var atlasOutputDir: String
            get() = state.atlasOutputDir; set(v) { state.atlasOutputDir = v }
        override var atlasArtifacts: MutableSet<AtlasArtifact>
            get() = state.atlasArtifacts; set(v) { state.atlasArtifacts = v }
        override var constantNaming: ConstantNaming
            get() = state.constantNaming; set(v) { state.constantNaming = v }
        override var constantFormat: ConstantFormat
            get() = state.constantFormat; set(v) { state.constantFormat = v }
        override var inspectBaseUrl: String
            get() = state.inspectBaseUrl; set(v) { state.inspectBaseUrl = v }
        override var inspectUsername: String
            get() = state.inspectUsername; set(v) { state.inspectUsername = v }
        override var designBaseUrl: String
            get() = state.designBaseUrl; set(v) { state.designBaseUrl = v }
        override var designWorkspaceKey: String
            get() = state.designWorkspaceKey; set(v) { state.designWorkspaceKey = v }
        override var designAppKey: String
            get() = state.designAppKey; set(v) { state.designAppKey = v }
        override var designTargetFolder: String
            get() = state.designTargetFolder; set(v) { state.designTargetFolder = v }
    }

    private val scopeLock = Any()

    /** Resolve (creating on first access) the scope for [key]; `""` is the flat default scope. */
    fun scope(key: String): AtlasScope {
        if (key.isBlank()) return defaultScope
        synchronized(scopeLock) {
            state.subProjects.firstOrNull { it.path == key }?.let { return it }
            return SubProjectState(key).also { state.subProjects.add(it) }
        }
    }

    private fun activeKey(): String =
        project?.let { PropertiesComponent.getInstance(it).getValue(ACTIVE_SUBPROJECT_PROPERTY, "") } ?: ""

    private fun active(): AtlasScope = scope(activeKey())

    override fun getState(): State {
        synchronized(scopeLock) {
            state.subProjects.removeAll { it.path.isBlank() || it.isUnconfigured() }
            state.subProjects.sortBy { it.path }
        }
        return state
    }

    override fun loadState(newState: State) {
        // The flat fields are the authoritative "" scope; a stray "" list entry (bad edit/merge) is
        // dropped, and duplicate paths are collapsed (last wins) so a bad VCS merge can't corrupt us.
        newState.subProjects.removeAll { it.path.isBlank() }
        newState.subProjects = newState.subProjects.associateBy { it.path }.values.toMutableList()
        state = newState
    }

    /** True when [problem] refers to an allowlisted namespace/function/root — i.e. must not be reported. */
    fun isAllowlisted(problem: ExprProblem): Boolean {
        val subject = problem.subject ?: return false
        val scope = active()
        return when (problem.kind) {
            ExprProblemKind.UNKNOWN_NAMESPACE -> subject in scope.allowedNamespaces
            ExprProblemKind.UNKNOWN_FUNCTION ->
                subject in scope.allowedFunctions ||
                    subject.substringBefore(':', "").takeIf { it.isNotEmpty() } in scope.allowedNamespaces
            ExprProblemKind.UNKNOWN_ROOT -> subject in scope.allowedGroundingRoots
            else -> false
        }
    }

    fun allow(subject: String, kind: ExprProblemKind) {
        val scope = active()
        val target = when (kind) {
            ExprProblemKind.UNKNOWN_NAMESPACE -> scope.allowedNamespaces
            ExprProblemKind.UNKNOWN_FUNCTION -> scope.allowedFunctions
            ExprProblemKind.UNKNOWN_ROOT -> scope.allowedGroundingRoots
            else -> return
        }
        if (subject !in target) target.add(subject)
    }

    var allowedNamespaces: MutableList<String>
        get() = active().allowedNamespaces
        set(value) { active().allowedNamespaces = value }

    var allowedFunctions: MutableList<String>
        get() = active().allowedFunctions
        set(value) { active().allowedFunctions = value }

    var allowedGroundingRoots: MutableList<String>
        get() = active().allowedGroundingRoots
        set(value) { active().allowedGroundingRoots = value }

    var customFunctionsEnabled: Boolean
        get() = active().customFunctionsEnabled
        set(value) { active().customFunctionsEnabled = value }

    var customFunctionsPath: String
        get() = active().customFunctionsPath
        set(value) { active().customFunctionsPath = value }

    var atlasOutputDir: String
        get() = active().atlasOutputDir.ifBlank { DEFAULT_ATLAS_OUTPUT_DIR }
        set(value) { active().atlasOutputDir = value.ifBlank { DEFAULT_ATLAS_OUTPUT_DIR } }

    var atlasArtifacts: MutableSet<AtlasArtifact>
        get() = active().atlasArtifacts
        set(value) {
            active().atlasArtifacts = if (value.isEmpty()) mutableSetOf(AtlasArtifact.EXPLORER_HTML) else value
        }

    var constantNaming: ConstantNaming
        get() = active().constantNaming
        set(value) { active().constantNaming = value }

    var constantFormat: ConstantFormat
        get() = active().constantFormat
        set(value) { active().constantFormat = value }

    var inspectBaseUrl: String
        get() = active().inspectBaseUrl
        set(value) { active().inspectBaseUrl = value }

    var inspectUsername: String
        get() = active().inspectUsername
        set(value) { active().inspectUsername = value }

    var designBaseUrl: String
        get() = active().designBaseUrl
        set(value) { active().designBaseUrl = value }

    var designWorkspaceKey: String
        get() = active().designWorkspaceKey
        set(value) { active().designWorkspaceKey = value }

    var designAppKey: String
        get() = active().designAppKey
        set(value) { active().designAppKey = value }

    var designTargetFolder: String
        get() = active().designTargetFolder
        set(value) { active().designTargetFolder = value }

    /** True once server, workspace and app are configured — i.e. a pull can run without the dialog. */
    fun isDesignConfigured(): Boolean =
        designBaseUrl.isNotBlank() && designWorkspaceKey.isNotBlank() && designAppKey.isNotBlank()

    companion object {
        const val DEFAULT_DESIGN_TARGET_FOLDER = "flowable-models"
        const val DEFAULT_ATLAS_OUTPUT_DIR = "atlas-output"

        /** Active sub-project path, kept in [PropertiesComponent] (workspace-local, not VCS-shared). */
        const val ACTIVE_SUBPROJECT_PROPERTY = "flowable.atlas.activeSubProject"

        fun getInstance(project: Project): FlowableAtlasProjectSettings = project.service()
    }
}
