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
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.SystemInfo
import com.flowable.atlas.navigation.ModelElements
import com.flowable.atlas.usage.FlowableDiagram
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.Point2D
import javax.swing.KeyStroke
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
 * that changes on disk (a pull, a checkout) or in its editor is drawn again. Archive entries do not
 * change in place. A click on an element — a task, a plan item, a decision rule, a form component — puts
 * the text editor's caret on its declaration.
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

    /** The drawn elements, in the SVG's viewBox space, for mapping a click back to an element id. */
    @Volatile private var hitMap: HitMap? = null

    init {
        val actions = zoomActions()
        val toolbar = ActionManager.getInstance().createActionToolbar("FlowableModelPreview", actions, true)
        toolbar.targetComponent = canvas
        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(cards, BorderLayout.CENTER)
        canvas.onClick = ::select
        show(FlowableAtlasBundle.message("preview.rendering"))
        render()
        if (file.isInLocalFileSystem) {
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file }) redraw.cancelAndRequest()
                }
            })
            // Typing in the text half redraws the picture too, from the unsaved text.
            FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = redraw.cancelAndRequest()
            }, this)
        }
    }

    /** The drawing on show, null while rendering or when the model has none. */
    internal val document: SVGDocument? get() = canvas.document

    private fun render() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val drawn = runCatching { load() }
                .onFailure { LOG.warn("Could not draw the preview of ${file.path}", it) }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val doc = drawn.getOrNull()
                when {
                    // A renderer that threw is a defect worth naming — not a model without a layout.
                    drawn.isFailure -> show(FlowableAtlasBundle.message("preview.failed",
                        drawn.exceptionOrNull()?.message ?: drawn.exceptionOrNull()?.javaClass?.simpleName ?: ""))
                    doc == null -> show(FlowableAtlasBundle.message("linemarker.diagram.nolayout"))
                    else -> {
                        canvas.document = doc
                        canvas.toolTipText = if (hitMap != null) FlowableAtlasBundle.message("preview.hint") else null
                        (cards.layout as CardLayout).show(cards, CANVAS)
                    }
                }
            }, project.disposed)
        }
    }

    private fun load(): SVGDocument? {
        if (!file.isValid) return null
        val cache = DiagramSvgCache.getInstance(project)
        // The editor's text when it holds edits not yet saved; the file otherwise.
        val unsaved = ReadAction.computeBlocking<String?, RuntimeException> {
            val fdm = FileDocumentManager.getInstance()
            if (fdm.isFileModified(file)) fdm.getCachedDocument(file)?.text else null
        }
        val bytes = unsaved?.toByteArray(Charsets.UTF_8)
        // A bundled export SVG is drawn in its own coordinates and knows no elements: shown, not clickable.
        val sibling = if (bytes == null) FlowableDiagram.siblingSvg(file) else null
        val picture = when {
            bytes != null -> cache.picture(bytes, file.name, type)
            sibling != null -> null
            else -> cache.resolvePicture(file, type)
        }
        val svgText = picture?.svg ?: sibling?.let { String(it.contentsToByteArray(), Charsets.UTF_8) }
        hitMap = HitMap.of(picture)
        if (svgText == null) return null
        val text = SvgFonts.resolvable(svgText)
        return text.byteInputStream().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }
    }

    /** A click on the picture: put the text editor's caret on the element under it. */
    private fun select(at: Point2D.Double) {
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
        val offset = offsetAt(at, text) ?: return
        OpenFileDescriptor(project, file, offset).navigate(true)
    }

    /** Where in [text] the element under [at] — a point in the SVG document's coordinates — is declared. */
    internal fun offsetAt(at: Point2D.Double, text: String): Int? =
        hitMap?.elementAt(at)?.let { ModelElements.declarationOffset(text, it) }

    private fun show(text: String) {
        message.text = text
        (cards.layout as CardLayout).show(cards, MESSAGE)
    }

    private fun zoomActions() = DefaultActionGroup(
        zoomAction("preview.zoomIn", AllIcons.Graph.ZoomIn, KeyEvent.VK_EQUALS) { canvas.zoomBy(1.25) },
        zoomAction("preview.zoomOut", AllIcons.Graph.ZoomOut, KeyEvent.VK_MINUS) { canvas.zoomBy(0.8) },
        zoomAction("preview.fitWidth", AllIcons.General.FitContent, KeyEvent.VK_0) { canvas.fitWidth() },
    )

    /** A toolbar zoom action, also on Ctrl/⌘ + [key] while the picture has the focus. */
    private fun zoomAction(key: String, icon: javax.swing.Icon, keyCode: Int, run: () -> Unit) =
        object : DumbAwareAction(FlowableAtlasBundle.message(key), null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = canvas.document != null }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }.also {
            val mask = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
            it.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(keyCode, mask)), canvas)
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
