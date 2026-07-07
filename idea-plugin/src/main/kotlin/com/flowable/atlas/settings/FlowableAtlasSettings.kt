package com.flowable.atlas.settings

import com.flowable.atlas.explorer.AtlasArtifactScope
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-wide Flowable Atlas settings. Exposed in Settings → Tools → Flowable Atlas
 * ([FlowableAtlasConfigurable]).
 */
@Service(Service.Level.APP)
@State(name = "FlowableAtlasSettings", storages = [Storage("flowable-atlas.xml")])
class FlowableAtlasSettings : PersistentStateComponent<FlowableAtlasSettings.State> {

    data class State(
        /** Also index per-model `.json` files under the Flowable Design `*-models/` workspace folders. */
        var indexDesignWorkspace: Boolean = false,
        /** Offer the extra completion domains (messages, signals, variables, task/activity, DMN variables). */
        var extraCompletions: Boolean = true,
        /** How generated model-constant identifiers are derived. */
        var constantNaming: ConstantNaming = ConstantNaming.NAME_AND_KEY,
        /** Whether generated model constants are a class of Strings or an enum. */
        var constantFormat: ConstantFormat = ConstantFormat.CLASS,
        /**
         * Explicit path to a Python 3 interpreter for the Atlas explorer generator.
         * Empty = auto-detect `python3` / `python` on PATH.
         */
        var pythonInterpreterPath: String = "",
        /** Which Atlas artifacts the "Generate Atlas Explorer" action produces. */
        var atlasArtifactScope: AtlasArtifactScope = AtlasArtifactScope.EXPLORER_ONLY,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    var indexDesignWorkspace: Boolean
        get() = state.indexDesignWorkspace
        set(value) { state.indexDesignWorkspace = value }

    var extraCompletions: Boolean
        get() = state.extraCompletions
        set(value) { state.extraCompletions = value }

    var constantNaming: ConstantNaming
        get() = state.constantNaming
        set(value) { state.constantNaming = value }

    var constantFormat: ConstantFormat
        get() = state.constantFormat
        set(value) { state.constantFormat = value }

    var pythonInterpreterPath: String
        get() = state.pythonInterpreterPath
        set(value) { state.pythonInterpreterPath = value }

    var atlasArtifactScope: AtlasArtifactScope
        get() = state.atlasArtifactScope
        set(value) { state.atlasArtifactScope = value }

    companion object {
        fun getInstance(): FlowableAtlasSettings = service()
    }
}
