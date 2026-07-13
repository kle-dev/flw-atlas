package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → Expressions: validation toggles, the project's expression
 * allowlist (one table over namespaces / functions / grounding roots — the same store the
 * Alt-Enter quick-fix writes to) and custom-function discovery for Atlas generation.
 */
class ExpressionsConfigurable(private val project: Project) : BoundSearchableConfigurable(
    FlowableAtlasBundle.message("configurable.atlas.expressions"),
    helpTopic = "",
    _id = "com.flowable.atlas.settings.expressions",
) {

    private val allowlist = AllowlistTablePanel()

    override fun createPanel(): DialogPanel {
        val app = FlowableAtlasSettings.getInstance()
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
            group("Validation") {
                row {
                    checkBox("Validate expression syntax")
                        .comment("Structural errors in the playground and in injected \${…} / {{…}} fragments in models.")
                        .bindSelected(app::expressionValidation)
                }
                row {
                    checkBox("Also validate expressions in Java string literals")
                        .comment("Inject the backend expression language into Java strings that contain \${…} / #{…}.")
                        .bindSelected(app::injectJavaExpressions)
                }
                row {
                    comment(
                        "Unknown-function warnings &amp; codebase grounding are inspections with per-profile " +
                            "severity: Settings → Editor → Inspections → Flowable.",
                    )
                }
            }
            group("Project allowlist") {
                row {
                    comment(
                        "Functions, namespaces and expression roots the project provides itself — findings " +
                            "about them are suppressed in the editor and in the generated Atlas explorer. " +
                            "Alt-Enter → \"Add … to Flowable expression allowlist\" adds entries here. " +
                            "Shared with the team via .idea/flowable-atlas.xml.",
                    )
                }
                row {
                    cell(allowlist.component)
                        .align(AlignX.FILL)
                        .onReset { allowlist.reset(settings) }
                        .onIsModified { allowlist.isModified(settings) }
                        .onApply {
                            allowlist.apply(settings)
                            // Mirror the quick-fix: findings depending on the allowlist must re-run.
                            DaemonCodeAnalyzer.getInstance(project).restart("Flowable Atlas expression allowlist changed")
                        }
                }
            }
            group("Custom functions") {
                row {
                    checkBox("Discover project custom functions")
                        .comment("Read externals.additionalData functions from the project's frontend customization during Atlas generation (the CLI's default too).")
                        .bindSelected(settings::customFunctionsEnabled)
                }
                row("Customization source:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                            .withTitle("Select Customization Source"),
                        project,
                    )
                        .align(AlignX.FILL)
                        .comment("Optional: a specific file or folder to read custom functions from (project-relative); leave empty for auto-discovery.")
                        .bindText(settings::customFunctionsPath)
                }
            }
        }
    }
}
