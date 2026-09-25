package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.render.ExplorerExtension
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
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

    /** Set when an Apply changed which extensions the explorer carries — the page then regenerates it. */
    private var extensionsChanged = false

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
            scopeLine(project)
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
            // Parts of the explorer page rather than artifacts of their own: the page is still one file, it just
            // carries more. One checkbox per ExplorerExtension, so the next extension appears here by itself.
            group("Explorer Extensions") {
                row {
                    comment(
                        "Optional parts of the explorer page. A page generated without one carries none of its code; " +
                            "changing this regenerates the explorer.",
                    )
                }
                for (ext in ExplorerExtension.entries) {
                    row {
                        checkBox(ext.label)
                            .comment(ext.description + ".")
                            .bindSelected(
                                { ext in settings.explorerExtensions },
                                { selected ->
                                    val next = if (selected) settings.explorerExtensions + ext else settings.explorerExtensions - ext
                                    if (next != settings.explorerExtensions) extensionsChanged = true
                                    settings.explorerExtensions = next
                                },
                            )
                    }
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
     * An extension is part of the page, so the page that exists should have it — or lose it — now, not on
     * some later generate the user has to remember. With no page on disk, regenerate says so and offers to
     * generate one.
     */
    override fun doApply() {
        if (!extensionsChanged) return
        extensionsChanged = false
        AtlasGenerationRunner.regenerate(project)
    }
}
