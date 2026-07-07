package com.flowable.atlas.settings

import com.flowable.atlas.explorer.AtlasArtifactScope
import com.flowable.atlas.generate.ConstantFormat
import com.flowable.atlas.generate.ConstantNaming
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Settings → Tools → Flowable Atlas. */
class FlowableAtlasConfigurable : Configurable {

    private var root: JPanel? = null
    private val extra = JBCheckBox("Enable extra completions (messages, signals, variables, task/activity keys, DMN variables)")
    private val exprValidation = JBCheckBox("Validate Flowable expressions (playground + injected \${…} / {{…}} in models)")
    private val injectJava = JBCheckBox("Also validate expressions in Java String literals that contain \${…} / #{…}")
    private val backendGrounding = JBCheckBox("Ground backend expressions against the project (warn on unknown variables / beans)")
    private val indexDesign = JBCheckBox("Index Flowable Design workspace models (per-model .json under *-models/ folders)")
    private val naming = JComboBox(ConstantNaming.entries.toTypedArray())
    private val format = JComboBox(ConstantFormat.entries.toTypedArray())
    private val pythonPath = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select Python 3 Interpreter"),
        )
    }
    private val artifactScope = JComboBox(AtlasArtifactScope.entries.toTypedArray())

    override fun getDisplayName(): String = "Flowable Atlas"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(leftAligned(extra))
            add(leftAligned(exprValidation))
            add(leftAligned(injectJava))
            add(leftAligned(backendGrounding))
            add(leftAligned(indexDesign))
            add(leftAligned(JLabel("Generated model constants (Tools → Generate Model Constants):")))
            add(labeledRow("Identifier:", naming))
            add(labeledRow("Format:", format))
            add(leftAligned(JLabel("Atlas explorer (Tools → Generate Atlas Explorer):")))
            add(labeledRow("Generate:", artifactScope))
            add(labeledRow("Python 3 interpreter:", pythonPath))
            add(leftAligned(JLabel("Leave empty to auto-detect python3 / python on PATH.")))
        }
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = FlowableAtlasSettings.getInstance()
        return extra.isSelected != s.extraCompletions ||
            exprValidation.isSelected != s.expressionValidation ||
            injectJava.isSelected != s.injectJavaExpressions ||
            backendGrounding.isSelected != s.backendCodebaseGrounding ||
            indexDesign.isSelected != s.indexDesignWorkspace ||
            naming.selectedItem != s.constantNaming ||
            format.selectedItem != s.constantFormat ||
            pythonPath.text.trim() != s.pythonInterpreterPath ||
            artifactScope.selectedItem != s.atlasArtifactScope
    }

    override fun apply() {
        val s = FlowableAtlasSettings.getInstance()
        val reindex = indexDesign.isSelected != s.indexDesignWorkspace
        s.extraCompletions = extra.isSelected
        s.expressionValidation = exprValidation.isSelected
        s.injectJavaExpressions = injectJava.isSelected
        s.backendCodebaseGrounding = backendGrounding.isSelected
        s.indexDesignWorkspace = indexDesign.isSelected
        s.constantNaming = naming.selectedItem as ConstantNaming
        s.constantFormat = format.selectedItem as ConstantFormat
        s.pythonInterpreterPath = pythonPath.text.trim()
        s.atlasArtifactScope = artifactScope.selectedItem as AtlasArtifactScope
        if (reindex) {
            // The design-workspace toggle changes what gets indexed; drop cached indexes.
            ProjectManager.getInstance().openProjects.forEach {
                if (!it.isDisposed) it.service<FlowableModelIndexService>().invalidate()
            }
        }
    }

    override fun reset() {
        val s = FlowableAtlasSettings.getInstance()
        extra.isSelected = s.extraCompletions
        exprValidation.isSelected = s.expressionValidation
        injectJava.isSelected = s.injectJavaExpressions
        backendGrounding.isSelected = s.backendCodebaseGrounding
        indexDesign.isSelected = s.indexDesignWorkspace
        naming.selectedItem = s.constantNaming
        format.selectedItem = s.constantFormat
        pythonPath.text = s.pythonInterpreterPath
        artifactScope.selectedItem = s.atlasArtifactScope
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun leftAligned(c: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(c); alignmentX = Component.LEFT_ALIGNMENT }

    private fun labeledRow(label: String, field: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JLabel(label).apply { border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 8) })
            add(field)
            alignmentX = Component.LEFT_ALIGNMENT
        }
}
