package com.flowable.atlas.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the model-index build when a project opens, in the background.
 *
 * Without it the index existed only once something happened to ask for it — a completion, a
 * Rebuild click, opening a Java file that trips a line-marker provider — so the Atlas Hub opened on
 * "Not scanned yet" and every marker, hint and inspection stayed dark until then. Nothing waits on
 * this: [FlowableModelIndexService.ensureBuilding] is one background build for any number of callers.
 */
class FlowableIndexWarmUp : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Light test fixtures reuse one project across tests; an unasked-for scan there is noise.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        project.service<FlowableModelIndexService>().ensureBuilding()
    }
}
