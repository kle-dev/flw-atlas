package com.flowable.keys.generate

import com.flowable.keys.model.ModelFiles
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm

/**
 * Keeps the generated model-constants class in sync: when a model file is added / removed /
 * renamed / edited, it debounces and regenerates the class (only if one was already generated).
 * Registered as a project-level BulkFileListener in plugin.xml.
 */
class ModelConstantsAutoRefresher(private val project: Project) : BulkFileListener {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    override fun after(events: MutableList<out VFileEvent>) {
        if (events.none { ModelFiles.isModelPath(it.path) }) return
        alarm.cancelAllRequests()
        alarm.addRequest(
            {
                if (!project.isDisposed) ModelConstantsService.getInstance(project).refresh()
            },
            DEBOUNCE_MS,
        )
    }

    companion object {
        private const val DEBOUNCE_MS = 1500
    }
}
