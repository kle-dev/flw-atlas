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
            group("Liquibase Changelogs") {
                row("Output folder:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Liquibase Output Folder"),
                        project,
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
                            .comment("Applied to the rendered file-name base. e.g. Find S0*(\\d+) Replace L\$1 turns KYC-S009 into …-L9.")
                            .bindText(settings::liquibaseRenameReplace)
                    }
                }
            }
            group("Data-Object DTOs") {
                row("Package:") {
                    textField()
                        .align(AlignX.FILL)
                        .comment("Package the generated DTOs go into, e.g. com.acme.flowable.dto (empty = the source root itself). The target source root is picked in the \"Generate → Data-Object DTOs\" dialog and remembered per project.")
                        .bindText(settings::dtoPackage)
                }
                row("Class name suffix:") {
                    textField()
                        .align(AlignX.FILL)
                        .comment("What the {suffix} token below renders, e.g. Customer + Dto = CustomerDto. Leave empty for the plain model name; it is never doubled when the model name already ends in it.")
                        .bindText(settings::dtoClassSuffix)
                }
                row("Class name pattern:") {
                    textField()
                        .align(AlignX.FILL)
                        .comment(
                            "Tokens: {name} {shortName} {key} {app} {suffix}. Empty = {name}{suffix}, the " +
                                "model name plus the suffix above. <b>{shortName} drops the model key most " +
                                "model names start with</b>, so {shortName}{suffix} turns " +
                                "<code>DEMO-D009 Pod Member</code> into <code>PodMemberDto</code> instead of " +
                                "<code>DEMOD009PodMemberDto</code>.",
                        )
                        .bindText(settings::dtoClassNamePattern)
                }
                collapsibleGroup("Rename (regex)") {
                    row("Find:") {
                        textField().align(AlignX.FILL).bindText(settings::dtoRenameFind)
                    }
                    row("Replace:") {
                        textField()
                            .align(AlignX.FILL)
                            .comment("Applied to the rendered class name, for what the tokens can't express. e.g. Find ^DEMO(\\w+) Replace Demo\$1 turns DEMOCustomerDto into DemoCustomerDto.")
                            .bindText(settings::dtoRenameReplace)
                    }
                }
                row {
                    checkBox("Sub-package per app")
                        .comment("Nest each DTO under the owning app, e.g. com.acme.flowable.dto.kycapp.CustomerDto — so two apps can never claim the same class name.")
                        .bindSelected(settings::dtoPackagePerApp)
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
