package com.flowable.atlas.settings

import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

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
    private var inspectPanel: InspectConnectionPanel? = null

    override fun createPanel(): DialogPanel {
        val design = DesignConnectionPanel(project).also { designPanel = it }
        val inspect = InspectConnectionPanel(project).also { inspectPanel = it }
        Disposer.register(disposable!!, design)
        Disposer.register(disposable!!, inspect)

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
                            "against (\"Evaluate Against App\"). Base URL and username are stored in the shared " +
                            "project settings; the password goes to the IDE PasswordSafe, never in a file. " +
                            "The playground shares the same credentials.",
                    )
                }
                row {
                    cell(inspect)
                        .align(AlignX.FILL)
                        .onReset { inspect.reset() }
                        .onIsModified { inspect.isModified() }
                        .onApply { inspect.apply() }
                }
            }
        }
    }

    override fun disposeUIResources() {
        designPanel = null
        inspectPanel = null
        super.disposeUIResources()
    }
}
