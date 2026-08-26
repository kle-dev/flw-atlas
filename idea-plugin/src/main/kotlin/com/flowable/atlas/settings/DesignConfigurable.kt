package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → **Flowable Design**: where a pull writes the app archives it
 * fetched.
 *
 * A page of its own because this setting was on *Generation*, and a pull generates nothing — it
 * downloads what someone else authored. Sharing a page with the artifact generators meant the one
 * heading had to cover both "what Atlas produces from your models" and "where your models come from",
 * which are opposite directions of travel. A reader looking for the pull folder had no reason to look
 * under Generation, and one reading Generation had to skip a group that was not about generating.
 *
 * It is deliberately a small page. Everything else about a pull — which environment, which workspace,
 * which apps — is chosen in the Atlas Hub, beside the models it fetches, and the [EnvironmentsConfigurable]
 * holds the servers themselves. What is left here is the one thing that is a fact about *this project*
 * rather than about a server or a moment: the folder the archives land in.
 */
class DesignConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.design"),
    "com.flowable.atlas.settings.design",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
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
                        "Where \"Pull from Flowable Design\" writes the app archives, relative to the " +
                            "Flowable project. Which environment it pulls from, and which of its apps, is " +
                            "chosen in the Atlas Hub — beside the models themselves.",
                    )
            }
        }
    }
}
