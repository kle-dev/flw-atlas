package com.flowable.keys.settings

import com.flowable.keys.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings → Tools → Flowable Keys. */
class FlowableKeysConfigurable : Configurable {

    private var root: JPanel? = null
    private val extra = JBCheckBox("Enable extra completions (messages, signals, variables, task/activity keys, DMN variables)")
    private val indexDesign = JBCheckBox("Index Flowable Design workspace models (per-model .json under *-models/ folders)")

    override fun getDisplayName(): String = "Flowable Keys"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(extra)
            add(indexDesign)
        }
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = FlowableKeysSettings.getInstance()
        return extra.isSelected != s.extraCompletions || indexDesign.isSelected != s.indexDesignWorkspace
    }

    override fun apply() {
        val s = FlowableKeysSettings.getInstance()
        val reindex = indexDesign.isSelected != s.indexDesignWorkspace
        s.extraCompletions = extra.isSelected
        s.indexDesignWorkspace = indexDesign.isSelected
        if (reindex) {
            // The design-workspace toggle changes what gets indexed; drop cached indexes.
            ProjectManager.getInstance().openProjects.forEach {
                if (!it.isDisposed) it.service<FlowableModelIndexService>().invalidate()
            }
        }
    }

    override fun reset() {
        val s = FlowableKeysSettings.getInstance()
        extra.isSelected = s.extraCompletions
        indexDesign.isSelected = s.indexDesignWorkspace
    }

    override fun disposeUIResources() {
        root = null
    }
}
