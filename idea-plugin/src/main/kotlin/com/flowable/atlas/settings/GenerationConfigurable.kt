package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.explorer.AtlasArtifact
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → **Generation**: which Atlas artifacts "Generate Atlas Explorer…"
 * produces and where, and the folder a Design pull writes its archives into.
 *
 * The three generators that have real shapes of their own — Liquibase changelogs, data-object DTOs and
 * the model-constants class — are child pages. On one page they were four screens of fields with no
 * hierarchy, so finding the DTO class-name pattern meant scrolling past the Liquibase rename regex; a
 * reader looking for one generator's options should not have to read the other two's.
 */
class GenerationConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.generation"),
    "com.flowable.atlas.settings.generation",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
            group("Flowable Design") {
                row("Pulled models folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Target Folder")
                            .withDescription("The pulled app archives are written into this folder inside the project"),
                        project,
                    )
                        .bindText(settings::designTargetFolder)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            "Where \"Pull from Flowable Design\" writes the app archives. Which environment it " +
                                "pulls from, and which of its apps, is chosen in the Atlas Hub — beside the models " +
                                "themselves rather than three pages away.",
                        )
                }
            }

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
                                            }
    }
}
