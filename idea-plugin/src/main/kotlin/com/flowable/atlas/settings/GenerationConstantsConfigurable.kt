package com.flowable.atlas.settings

import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.flowable.atlas.generate.ModelConstantsService
import com.flowable.atlas.generate.ModelConstantsSettings
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNameHelper
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Flowable Atlas → Generation → **Model Constants**: the shape of the generated
 * constants class and whether it is kept in sync.
 */
class GenerationConstantsConfigurable(project: Project) : AtlasProjectConfigurable(
    project,
    FlowableAtlasBundle.message("configurable.atlas.generation.constants"),
    "com.flowable.atlas.settings.generation.constants",
) {

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val constants = ModelConstantsSettings.getInstance(project).state
        return panel {
            // The class name and auto-refresh are stored once per repository; only the naming is per project.
            scopeLine(project, shared = message("settings.scope.constantsShared"))
            row("Class name (FQCN):") {
                textField()
                    .align(AlignX.FILL)
                    .comment("Fully-qualified name of the generated constants class, e.g. flowable.FlowableModelKeys.")
                    .validationOnInput { field ->
                        val t = field.text.trim()
                        if (t.isNotEmpty() && !PsiNameHelper.getInstance(project).isQualifiedName(t)) {
                            error("Not a valid fully-qualified class name — e.g. flowable.FlowableModelKeys")
                        } else null
                    }
                    .bindText(
                        { constants.fqcn },
                        { value ->
                            val old = constants.fqcn
                            constants.fqcn = value.trim()
                            // Auto-refresh resolves the class by its *new* name and finds nothing, so
                            // the file generated under the old one silently stopped being kept in sync.
                            if (old.isNotBlank() && old != constants.fqcn) project.service<ModelConstantsService>().noteRenamed(old)
                        },
                    )
            }
            row {
                checkBox("Keep the generated class in sync")
                    .comment("Regenerate automatically when models are added, renamed or removed.")
                    .bindSelected({ constants.autoRefresh }, { constants.autoRefresh = it })
            }
            // Readable choices with an example each — the combos used to show the enum names themselves.
            row("Identifier:") {
                comboBox(ConstantNaming.entries, textListCellRenderer { it?.let(::namingLabel) })
                    .comment(message("settings.constants.naming.comment"))
                    .bindItem(
                        { settings.constantNaming },
                        { settings.constantNaming = it ?: ConstantNaming.NAME_AND_KEY },
                    )
            }
            row("Format:") {
                comboBox(ConstantFormat.entries, textListCellRenderer { it?.let(::formatLabel) })
                    .bindItem(
                        { settings.constantFormat },
                        { settings.constantFormat = it ?: ConstantFormat.CLASS },
                    )
            }
        }
    }

    private fun namingLabel(n: ConstantNaming): String = message(
        when (n) {
            ConstantNaming.KEY -> "settings.constants.naming.key"
            ConstantNaming.NAME -> "settings.constants.naming.name"
            ConstantNaming.NAME_AND_KEY -> "settings.constants.naming.nameAndKey"
        },
    )

    private fun formatLabel(f: ConstantFormat): String = message(
        when (f) {
            ConstantFormat.CLASS -> "settings.constants.format.class"
            ConstantFormat.ENUM -> "settings.constants.format.enum"
        },
    )
}
