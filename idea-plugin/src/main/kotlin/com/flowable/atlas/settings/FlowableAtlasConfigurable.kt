package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas: the root page with the application-wide toggles, most consequential
 * first — what the index reads, what a string literal is taken for, what completion lists. Everything
 * per project lives on the child pages (Environments / Expressions / Generation). The two inline hints
 * are switched on the IDE's own Inlay Hints page and nowhere else: a second box here meant two switches
 * the daemon had to honour separately.
 */
class FlowableAtlasConfigurable : AtlasApplicationConfigurable(
    FlowableAtlasBundle.message("configurable.atlas"),
    "com.flowable.atlas.settings",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasSettings.getInstance()
        return panel {
            group("Model Index") {
                row {
                    checkBox("Also index raw Flowable Design workspace sources")
                        .comment(
                            "By default only exported deployment artifacts are indexed " +
                                "(.bpmn/.cmmn/.dmn/.form/.data/.service, incl. inside .bar/.zip). Enable this to " +
                                "also index the raw Design-workspace source JSON (bpmn-models/, form-models/, …) — " +
                                "needed when the repository holds the Design export rather than deployment artifacts.",
                        )
                        .bindSelected(settings::indexDesignWorkspace)
                        .onApply {
                            // The design-workspace toggle changes what gets indexed; drop cached indexes.
                            ProjectManager.getInstance().openProjects.forEach {
                                if (!it.isDisposed) it.service<FlowableModelIndexService>().invalidate()
                            }
                        }
                }
            }
            group("Navigation") {
                row {
                    checkBox("Recognize model keys anywhere in code")
                        .comment(
                            "By default a string is treated as a model key only at a known Flowable API call " +
                                "(startProcessInstanceByKey(\"…\"), .processDefinitionKey(\"…\"), …). Enable this to " +
                                "recognize <i>any</i> string literal whose value equals a known model key — the diagram " +
                                "icon, Ctrl-click, Find Usages and hover then work on it too. Matches on value alone, so " +
                                "a literal that happens to equal a short/common real key can also light up.",
                        )
                        .bindSelected(settings::recognizeModelKeysAnywhere)
                        // The gutter icons and references this decides are drawn by the highlighting pass,
                        // and an open file does not get another one until something makes it — so make it.
                        .onApply { restartHighlightingEverywhere("Flowable Atlas key recognition toggled") }
                }
            }
            group("Completion") {
                row {
                    checkBox("List extra completion domains at an empty prefix")
                        .comment(
                            "Messages, signals, variables, task/activity keys, DMN &amp; master-data fields and " +
                                "form outcomes are always offered once you type a prefix; enable this to also list " +
                                "them with no prefix typed (aggressive).",
                        )
                        .bindSelected(settings::extraCompletions)
                }
            }
            row {
                comment(
                    "Per project, on the pages below: <b>Environments</b> (the servers), <b>Expressions</b> " +
                        "(validation, allowlist, custom functions), <b>Generation</b> (what Atlas writes, and where).<br>" +
                        "Inspections: Settings → Editor → Inspections → Flowable. Inline hints: Settings → Editor → " +
                        "Inlay Hints → Values.",
                )
            }
        }
    }
}
