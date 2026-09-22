package com.flowable.atlas.preview

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.usage.DiagramSvgCache
import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The picture half of a model's editor: its diagram, decision table or form wireframe, as
 * [DiagramSvgCache] renders it, painted by [SvgCanvas]. Rendering runs off the EDT; a loose model file
 * that changes on disk (a pull, a checkout) is drawn again. Archive entries do not change in place.
 */
internal class FlowableModelPreview(
    private val project: Project,
    private val file: VirtualFile,
    private val type: ModelType,
) : UserDataHolderBase(), FileEditor {

    private val canvas = SvgCanvas()
    private val message = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private val cards = JPanel(CardLayout()).apply {
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, CANVAS)
        add(message, MESSAGE)
    }
    private val root = JPanel(BorderLayout())
    private val redraw = SingleAlarm(::render, 300, this)
    @Volatile private var disposed = false

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("FlowableModelPreview", zoomActions(), true)
        toolbar.targetComponent = canvas
        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(cards, BorderLayout.CENTER)
        show(FlowableAtlasBundle.message("preview.rendering"))
        render()
        if (file.isInLocalFileSystem) {
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file }) redraw.cancelAndRequest()
                }
            })
        }
    }

    /** The drawing on show, null while rendering or when the model has none. */
    internal val document: SVGDocument? get() = canvas.document

    private fun render() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val doc = runCatching { load() }
                .onFailure { LOG.warn("Could not draw the preview of ${file.path}", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                if (doc == null) {
                    show(FlowableAtlasBundle.message("linemarker.diagram.nolayout"))
                } else {
                    canvas.document = doc
                    (cards.layout as CardLayout).show(cards, CANVAS)
                }
            }, project.disposed)
        }
    }

    private fun load(): SVGDocument? {
        if (!file.isValid) return null
        val svg = DiagramSvgCache.getInstance(project).resolveDiagram(file, type) ?: return null
        val text = SvgFonts.resolvable(String(svg.contentsToByteArray(), Charsets.UTF_8))
        return text.byteInputStream().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }
    }

    private fun show(text: String) {
        message.text = text
        (cards.layout as CardLayout).show(cards, MESSAGE)
    }

    private fun zoomActions() = DefaultActionGroup(
        zoomAction("preview.zoomIn", AllIcons.Graph.ZoomIn) { canvas.zoomBy(1.25) },
        zoomAction("preview.zoomOut", AllIcons.Graph.ZoomOut) { canvas.zoomBy(0.8) },
        zoomAction("preview.fitWidth", AllIcons.General.FitContent) { canvas.fitWidth() },
    )

    private fun zoomAction(key: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : DumbAwareAction(FlowableAtlasBundle.message(key), null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = canvas.document != null }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName(): String = FlowableAtlasBundle.message("preview.name")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() { disposed = true }

    private companion object {
        val LOG = logger<FlowableModelPreview>()
        const val CANVAS = "canvas"
        const val MESSAGE = "message"
    }
}
