package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.settings.connections.EnvironmentsTreePanel
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → **Environments**: the DEV/QA/UAT/PROD list and the Flowable
 * Design and Flowable Work connections inside each of them.
 *
 * Registered as a *project* configurable although the catalog it edits is application-wide. That is
 * deliberate rather than sloppy: the page needs a project for *Detect from Project*, for the Design
 * token dialog, and to mark which connection the current project is actually using — and the one
 * thing a project-level registration would otherwise cost, a reader assuming the list is per project,
 * is bought back by the sentence at the top of the page saying it is not.
 */
class EnvironmentsConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.environments"),
    "com.flowable.atlas.settings.environments",
) {

    private var panel: EnvironmentsTreePanel? = null

    override fun createPanel(): DialogPanel {
        val tree = EnvironmentsTreePanel(project).also { panel = it }
        Disposer.register(disposable!!, tree)
        return panel {
            row {
                comment(
                    "Shared by every project in this IDE — define a server once and pick it anywhere. " +
                        "Passwords and access tokens go to the IDE password safe, never into a file.<br>" +
                        "Entries marked <i>project</i> come from this repository's committed " +
                        "<code>.idea/flowable-environments.xml</code> and are the same for everyone who " +
                        "clones it; <b>Share with Project</b> puts one of yours there. Only URLs travel — " +
                        "each developer signs in as themselves.",
                )
            }
            row {
                cell(tree.component)
                    .align(Align.FILL)
                    .onReset { tree.reset() }
                    .onIsModified { tree.isModified() }
                    .onApply {
                        tree.validate()?.let { throw ConfigurationException(it) }
                        tree.apply()
                    }
            }.resizableRow()
        }
    }

    /** Open the page with a specific node selected — so a "Configure…" link is never a dead end. */
    fun select(target: EnvironmentsTreePanel.SelectionTarget) {
        panel?.select(target)
    }

    override fun disposeUIResources() {
        panel = null
        super.disposeUIResources()
    }
}
