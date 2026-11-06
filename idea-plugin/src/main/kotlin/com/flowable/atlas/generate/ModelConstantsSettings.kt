package com.flowable.atlas.generate

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Remembers where the generated model-constants class lives, so auto-refresh can update it. */
@Service(Service.Level.PROJECT)
@State(name = "FlowableModelConstants", storages = [Storage("flowable-atlas.xml")])
class ModelConstantsSettings : PersistentStateComponent<ModelConstantsSettings.State> {

    class State {
        /** Fully-qualified class name of the generated constants class (e.g. app.FlowableModelKeys). */
        var fqcn: String = ""

        /** URL of the source root the class was generated into. */
        var sourceRootUrl: String = ""

        /** Keep the class in sync when models are added/removed. */
        var autoRefresh: Boolean = true

        /**
         * The Flowable sub-project the class was generated from — its root-relative key, `""` for the whole
         * repository. The class holds that scope's keys in that scope's naming, while the file and this
         * state are one per repository; auto-refresh only rewrites it while the same scope is active.
         */
        var subProject: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): ModelConstantsSettings = project.service()
    }
}
