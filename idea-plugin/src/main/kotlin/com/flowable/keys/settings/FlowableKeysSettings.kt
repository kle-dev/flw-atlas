package com.flowable.keys.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-wide Flowable Keys settings. Exposed in Settings → Tools → Flowable Keys
 * ([FlowableKeysConfigurable]).
 */
@Service(Service.Level.APP)
@State(name = "FlowableKeysSettings", storages = [Storage("flowable-keys.xml")])
class FlowableKeysSettings : PersistentStateComponent<FlowableKeysSettings.State> {

    data class State(
        /** Also index per-model `.json` files under the Flowable Design `*-models/` workspace folders. */
        var indexDesignWorkspace: Boolean = false,
        /** Offer the extra completion domains (messages, signals, variables, task/activity, DMN variables). */
        var extraCompletions: Boolean = true,
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

    companion object {
        fun getInstance(): FlowableKeysSettings = service()
    }
}
