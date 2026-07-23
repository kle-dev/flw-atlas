package com.flowable.atlas.settings

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
        /**
         * Aggressively list the extra completion domains (messages, signals, variables, task/activity,
         * DMN & master-data fields) even at an *empty* prefix. These domains are always offered once a
         * non-empty prefix is typed regardless of this flag; this only adds the empty-prefix listing.
         */
        var extraCompletions: Boolean = true,
        /**
         * Report structural syntax errors in Flowable expressions (playground + injected). Semantic
         * findings (unknown functions, grounding) are inspections — Settings → Editor → Inspections →
         * Flowable — with per-profile severity and a project allowlist.
         */
        var expressionValidation: Boolean = true,
        /** Inject the backend expression language into Java String literals that carry `${…}` / `#{…}`. */
        var injectJavaExpressions: Boolean = false,
        /**
         * Show the data-object table name as an inline hint next to its key literal. Independent of
         * the hover/Ctrl-Q documentation ([com.flowable.atlas.navigation.FlowableKeyDocumentationProvider]),
         * which always shows the table regardless of this setting.
         */
        var showDataObjectTableInlay: Boolean = true,
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

    var expressionValidation: Boolean
        get() = state.expressionValidation
        set(value) { state.expressionValidation = value }

    var injectJavaExpressions: Boolean
        get() = state.injectJavaExpressions
        set(value) { state.injectJavaExpressions = value }

    var showDataObjectTableInlay: Boolean
        get() = state.showDataObjectTableInlay
        set(value) { state.showDataObjectTableInlay = value }

    companion object {
        fun getInstance(): FlowableAtlasSettings = service()
    }
}
