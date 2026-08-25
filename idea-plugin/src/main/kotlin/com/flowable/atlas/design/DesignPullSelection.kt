package com.flowable.atlas.design

/**
 * What a Design pull fetches: one workspace and the apps ticked in it.
 *
 * This used to carry a whole second layer — a workspace-local "personal override" sitting on top of a
 * VCS-shared default, with a *(personal selection)* marker and a *Reset to configured* link. It went
 * because it could not be told apart from the thing it overrode: with the picker in one place and the
 * default in another, "is this the setting or my copy of it?" had no answer on screen, and an override
 * that had quietly drifted made every edit to the shared default look as if it had done nothing.
 *
 * There is one value now, stored per environment in the project settings
 * ([com.flowable.atlas.settings.FlowableAtlasProjectSettings.pullTarget]), and the Atlas Hub edits it
 * directly. What you see is what a pull does.
 */
data class DesignPullSelection(val workspaceKey: String, val appKeys: List<String>) {

    companion object {

        val EMPTY = DesignPullSelection("", emptyList())

        /**
         * The selection after switching to [workspaceKey]. Apps are per workspace, so the ticked ones
         * do not carry over — the previous workspace's apps do not exist in the next one.
         */
        fun withWorkspace(current: DesignPullSelection, workspaceKey: String): DesignPullSelection =
            if (workspaceKey == current.workspaceKey) current else DesignPullSelection(workspaceKey, emptyList())
    }
}
