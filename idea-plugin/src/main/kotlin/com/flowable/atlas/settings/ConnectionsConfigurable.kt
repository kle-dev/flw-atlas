package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.expr.inspect.InspectConnectionDetector
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JTextField

/**
 * Settings → Tools → Flowable Atlas → Connections: the Flowable Design connection used by
 * "Pull from Flowable Design" (full editor, replaces the old dialog) and the Inspect connection
 * the Expression Playground evaluates backend expressions against.
 */
class ConnectionsConfigurable(private val project: Project) : BoundSearchableConfigurable(
    FlowableAtlasBundle.message("configurable.atlas.connections"),
    helpTopic = "",
    _id = "com.flowable.atlas.settings.connections",
) {

    private var designPanel: DesignConnectionPanel? = null

    override fun createPanel(): DialogPanel {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val design = DesignConnectionPanel(project).also { designPanel = it }
        Disposer.register(disposable!!, design)

        lateinit var inspectUrlField: JTextField
        lateinit var inspectUserField: JTextField

        return panel {
            group("Flowable Design") {
                row {
                    comment(
                        "Used by \"Pull from Flowable Design\". \"Refresh Workspaces\" loads the live " +
                            "workspace/app lists and doubles as the connection test. Credentials are " +
                            "stored in the IDE PasswordSafe, never in a file.",
                    )
                }
                row {
                    cell(design)
                        .align(AlignX.FILL)
                        .onReset { design.reset() }
                        .onIsModified { design.isModified() }
                        .onApply { design.apply() }
                }
            }
            group("Flowable Inspect") {
                row {
                    comment(
                        "A running Flowable app the Expression Playground evaluates backend expressions " +
                            "against (\"Evaluate Against App\"). The password is entered in the playground and " +
                            "remembered in the IDE PasswordSafe after a successful evaluation — never in a file.",
                    )
                }
                row("App base URL:") {
                    textField()
                        .align(AlignX.FILL)
                        .applyToComponent { inspectUrlField = this }
                        .bindText(settings::inspectBaseUrl)
                }
                row("Username:") {
                    textField()
                        .applyToComponent { inspectUserField = this }
                        .bindText(settings::inspectUsername)
                }
                row {
                    button("Detect from project") {
                        val detected = InspectConnectionDetector.detect(project)
                        detected.baseUrl?.takeIf { it.isNotBlank() }?.let { inspectUrlField.text = it }
                        detected.username?.takeIf { it.isNotBlank() }?.let { inspectUserField.text = it }
                    }.comment("Reads the app URL and admin user from the project's Spring configuration.")
                }
            }
        }
    }

    override fun disposeUIResources() {
        designPanel = null
        super.disposeUIResources()
    }
}
