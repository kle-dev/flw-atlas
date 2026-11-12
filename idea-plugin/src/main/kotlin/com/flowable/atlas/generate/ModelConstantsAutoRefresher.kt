package com.flowable.atlas.generate

import com.flowable.atlas.events.AtlasEventsListener
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Keeps the generated model-constants class in sync with the model index: whenever the index lands or
 * is dropped, it debounces and regenerates the class (only if one was already generated).
 *
 * A listener on the *index*, not on the file system. The VFS listener it replaced fired on the same
 * change that had just dropped the index, so `refresh()` found no index, gave up, and nothing brought it
 * round again unless the rebuild happened to finish inside its 1.5 s window — "kept in sync" was a coin
 * flip on any repository the scan takes longer than that. The index's own `modelIndexUpdated` fires
 * once when it is dropped (nothing to do yet) and once when the rebuild lands (regenerate).
 * Registered as a project-level listener in plugin.xml.
 */
class ModelConstantsAutoRefresher(private val project: Project) : AtlasEventsListener {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    override fun modelIndexUpdated() {
        alarm.cancelAllRequests()
        alarm.addRequest(
            {
                if (!project.isDisposed) ModelConstantsService.getInstance(project).refresh()
            },
            DEBOUNCE_MS,
        )
    }

    companion object {
        /** Several rapid index updates (a pull rewrites many archives) collapse into one regeneration. */
        private const val DEBOUNCE_MS = 700
    }
}
