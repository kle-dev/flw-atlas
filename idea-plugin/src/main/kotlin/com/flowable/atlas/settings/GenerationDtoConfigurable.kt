package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → Generation → **Data-Object DTOs**: the package, class names and
 * nesting of the Java DTOs generated from data objects.
 */
class GenerationDtoConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.generation.dto"),
    "com.flowable.atlas.settings.generation.dto",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return panel {
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
                        .comment("Nest each DTO under the owning app, e.g. com.acme.flowable.dto.demoapp.CustomerDto — so two apps can never claim the same class name.")
                        .bindSelected(settings::dtoPackagePerApp)
                }
            }
        }
    }
}
