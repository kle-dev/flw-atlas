package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.flowable.atlas.generate.ModelConstantsSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → Generation: which Atlas artifacts "Generate Atlas Explorer…"
 * produces and where, plus the shape of the generated model-constants class.
 */
class GenerationConfigurable(private val project: Project) : BoundSearchableConfigurable(
    FlowableAtlasBundle.message("configurable.atlas.generation"),
    helpTopic = "",
    _id = "com.flowable.atlas.settings.generation",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val constants = ModelConstantsSettings.getInstance(project).state
        return panel {
            group("Atlas Artifacts") {
                row("Output folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Atlas Output Folder"),
                        project,
                    )
                        .align(AlignX.FILL)
                        .comment("Default location the generate action proposes and \"Open Atlas Explorer\" searches first (project-relative).")
                        .bindText(settings::atlasOutputDir)
                }
                row {
                    label("Artifacts produced by \"Generate Atlas Explorer…\":")
                }
                for (artifact in AtlasArtifact.entries) {
                    row {
                        checkBox(artifact.label)
                            .bindSelected(
                                { artifact in settings.atlasArtifacts },
                                { selected ->
                                    if (selected) settings.atlasArtifacts.add(artifact)
                                    else settings.atlasArtifacts.remove(artifact)
                                },
                            )
                    }
                }
                row {
                    comment(
                        "Only the explorer HTML selected → the action asks for a target file; any other " +
                            "selection → a target folder. An empty selection falls back to the explorer HTML.",
                    )
                }
            }
            group("Model Constants") {
                row("Class name (FQCN):") {
                    textField()
                        .align(AlignX.FILL)
                        .comment("Fully-qualified name of the generated constants class, e.g. flowable.FlowableModelKeys.")
                        .bindText({ constants.fqcn }, { constants.fqcn = it.trim() })
                }
                row {
                    checkBox("Keep the generated class in sync")
                        .comment("Regenerate automatically when models are added, renamed or removed.")
                        .bindSelected({ constants.autoRefresh }, { constants.autoRefresh = it })
                }
                row("Identifier:") {
                    comboBox(ConstantNaming.entries)
                        .bindItem(
                            { settings.constantNaming },
                            { settings.constantNaming = it ?: ConstantNaming.NAME_AND_KEY },
                        )
                }
                row("Format:") {
                    comboBox(ConstantFormat.entries)
                        .bindItem(
                            { settings.constantFormat },
                            { settings.constantFormat = it ?: ConstantFormat.CLASS },
                        )
                }
            }
        }
    }
}
