package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.dsl.builder.bindText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → Generation → **Liquibase**: where "Generate → Liquibase" writes
 * changelogs and what it calls them.
 */
class GenerationLiquibaseConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.generation.liquibase"),
    "com.flowable.atlas.settings.generation.liquibase",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
group("Liquibase Changelogs") {
                row("Output folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Liquibase Output Folder"),
                        project,
                        projectRelativeChooser(project),
                    )
                        .align(AlignX.FILL)
                        .comment("Where \"Generate → Liquibase\" writes changelogs and the master flowable-project-db-changelog.xml (project-relative).")
                        .bindText(settings::liquibaseOutputDir)
                }
                row("File name pattern:") {
                    textField()
                        .align(AlignX.FILL)
                        .comment("Tokens: {key} {name} {service} {servicePrefix} {serviceNo} {table}. e.g. {servicePrefix}-L{serviceNo}-{name}")
                        .bindText(settings::liquibaseFileNamePattern)
                }
                collapsibleGroup("Rename (regex)") {
                    row("Find:") {
                        textField().align(AlignX.FILL).bindText(settings::liquibaseRenameFind)
                    }
                    row("Replace:") {
                        textField()
                            .align(AlignX.FILL)
                            .comment("Applied to the rendered file-name base. e.g. Find S0*(\\d+) Replace L\$1 turns DEMO-S009 into …-L9.")
                            .bindText(settings::liquibaseRenameReplace)
                    }
                }
            }
        }
    }
}
