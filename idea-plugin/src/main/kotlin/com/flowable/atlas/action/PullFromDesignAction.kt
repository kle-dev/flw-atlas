package com.flowable.atlas.action

import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.settings.ConnectionsConfigurable
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Tools → Flowable Atlas → "Pull from Flowable Design": downloads the configured app's export ZIP
 * into the project and rebuilds the model index. On first use (nothing configured yet) it opens
 * the Connections settings page instead and pulls right after it was configured there.
 */
class PullFromDesignAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        if (!settings.isDesignConfigured()) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ConnectionsConfigurable::class.java)
            if (!settings.isDesignConfigured()) return   // still unconfigured — user cancelled
        }
        DesignPullService.getInstance(project).pullInBackground()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
