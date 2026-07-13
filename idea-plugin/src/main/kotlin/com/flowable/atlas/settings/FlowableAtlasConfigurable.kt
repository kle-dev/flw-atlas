package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas: the root page with the application-wide core toggles.
 * Expression validation, generation output and the Design/Inspect connections live on the
 * project-level sub-pages (Expressions / Generation / Connections).
 */
class FlowableAtlasConfigurable : BoundSearchableConfigurable(
    FlowableAtlasBundle.message("configurable.atlas"),
    helpTopic = "",
    _id = "com.flowable.atlas.settings",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasSettings.getInstance()
        return panel {
            group("Completion") {
                row {
                    checkBox("Extra completion domains")
                        .comment("Messages, signals, variables, task/activity keys, DMN variables and form outcomes — in addition to model keys.")
                        .bindSelected(settings::extraCompletions)
                }
            }
            group("Model Index") {
                row {
                    checkBox("Index Flowable Design workspace models")
                        .comment("Also index per-model .json files under *-models/ workspace folders.")
                        .bindSelected(settings::indexDesignWorkspace)
                        .onApply {
                            // The design-workspace toggle changes what gets indexed; drop cached indexes.
                            ProjectManager.getInstance().openProjects.forEach {
                                if (!it.isDisposed) it.service<FlowableModelIndexService>().invalidate()
                            }
                        }
                }
            }
            row {
                comment(
                    "Expression validation and the project allowlist: <b>Expressions</b> · " +
                        "artifact selection, output folder and model constants: <b>Generation</b> · " +
                        "Flowable Design and Inspect: <b>Connections</b> (sub-pages of this one).<br>" +
                        "Unknown-function warnings &amp; codebase grounding are inspections: " +
                        "Settings → Editor → Inspections → Flowable.",
                )
            }
        }
    }
}
