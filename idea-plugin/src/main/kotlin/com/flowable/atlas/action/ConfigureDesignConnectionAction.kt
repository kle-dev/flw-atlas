package com.flowable.atlas.action

import com.flowable.atlas.settings.ConnectionsConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Tools → Flowable Atlas → "Configure Design Connection…": deep-links to Settings → Tools →
 * Flowable Atlas → Connections, where the Flowable Design server, credentials, workspace/app and
 * target folder used by "Pull from Flowable Design" are edited.
 */
class ConfigureDesignConnectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ConnectionsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
