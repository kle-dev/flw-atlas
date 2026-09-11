package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.explorer.AtlasArtifact
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.layout.ValidationInfoBuilder
import java.io.File
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → **Generation**: what Atlas writes into the project, and where —
 * the artifacts "Generate Atlas Explorer…" produces, and the folder a Flowable Design pull lands in.
 *
 * The pull folder had a page of its own for a while, on the argument that a pull downloads rather than
 * generates. True, but a page with one row costs a tree node per field, and the reader's question is the
 * same for both: where does Atlas write into my project? The heading of its group says which direction.
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
            group("Atlas Artifacts") {
                row("Output folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Atlas Output Folder"),
                        project,
                        projectRelativeChooser(project),
                    )
                        .align(AlignX.FILL)
                        .comment("Default location the generate action proposes and \"Open Atlas Explorer\" searches first (project-relative).")
                        .bindText(settings::atlasOutputDir)
                        .validationOnInput { projectRelativeFolder(it.text) }
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
                                    // Through the property setter, not the live set: the setter is where
                                    // "an empty selection falls back to the explorer HTML" lives, and
                                    // mutating the set in place used to bypass it — leaving an empty set,
                                    // a generate run that wrote nothing, and a balloon saying it had.
                                    val next = settings.atlasArtifacts.toMutableSet()
                                    if (selected) next.add(artifact) else next.remove(artifact)
                                    settings.atlasArtifacts = next
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
            // Where the pull lands is the one fact about a pull that is a fact about *this project* rather
            // than about a server or a moment — the rest (environment, workspace, apps) is chosen in the Hub.
            // A page of its own held this single row; "where does Atlas write into my project" is the
            // question this page already answers.
            group("Flowable Design Pull") {
                row("Pulled models folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Pulled Models Folder")
                            .withDescription("The pulled app archives are written into this folder inside the project"),
                        project,
                        projectRelativeChooser(project),
                    )
                        .align(AlignX.FILL)
                        .comment(
                            "Where \"Pull from Flowable Design\" writes the app archives, relative to the Flowable " +
                                "project. Which environment it pulls from, and which of its apps, is chosen in the Atlas Hub.",
                        )
                        .bindText(settings::designTargetFolder)
                        .validationOnInput { projectRelativeFolder(it.text) }
                }
            }
        }
    }

    /**
     * Both folders are resolved against the project directory, so an absolute path or a `..` segment would
     * make a pull or a generation write outside the repository — and a blank one nowhere at all.
     */
    private fun ValidationInfoBuilder.projectRelativeFolder(text: String): ValidationInfo? {
        val t = text.trim()
        return when {
            t.isEmpty() -> error("A folder is required")
            File(t).isAbsolute || t.startsWith("~") -> error("Must be relative to the project directory")
            t.split('/', '\\').any { it == ".." } -> error("Must stay inside the project directory (no ..)")
            else -> null
        }
    }
}
