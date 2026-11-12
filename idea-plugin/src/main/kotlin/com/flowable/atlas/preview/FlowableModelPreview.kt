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
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.navigation.ModelElements
import com.flowable.atlas.navigation.ModelKeyTargets
import com.intellij.openapi.components.service
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
 * the text editor's caret on its declaration; a double click (or ⌘-click on macOS, Ctrl-click elsewhere)
 * on a subform opens the form it embeds.
 *
 * While the text is being typed it will not always parse. The picture then stays as it last was, with a
 * thin line above it saying so, instead of flipping to "no layout" at every keystroke.
 */
internal class FlowableModelPreview(
    private val project: Project,
    private val file: VirtualFile,
    private val type: ModelType,
) : UserDataHolderBase(), FileEditor {

    private val canvas = SvgCanvas()
    private val message = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    /** "The text does not parse — the picture is the last one that did." Shown only while that is true. */
    private val staleLine = JBLabel(FlowableAtlasBundle.message("preview.stale"), AllIcons.General.Warning, SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(3, 8)
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }
    private val zoomLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyRight(10)
    }
    private val cards = JPanel(CardLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            add(staleLine, BorderLayout.NORTH)
            add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }, CANVAS)
        add(message, MESSAGE)
    }
    private val root = JPanel(BorderLayout())
    private val redraw = SingleAlarm(::render, 300, this)
    @Volatile private var disposed = false

    /** The drawn elements, in the SVG's viewBox space, for mapping a click back to an element id. */
    @Volatile private var hitMap: HitMap? = null

    /** The files of the subforms drawn inside this form — a change to one redraws it, like its own. */
    @Volatile private var embedded: Set<VirtualFile> = emptySet()

    init {
        val actions = zoomActions()
        val toolbar = ActionManager.getInstance().createActionToolbar("FlowableModelPreview", actions, true)
        toolbar.targetComponent = canvas
        root.add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.CENTER)
            add(zoomLabel, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        root.add(cards, BorderLayout.CENTER)
        canvas.onClick = ::select
        canvas.onOpen = { at -> if (!open(at)) select(at) }
        canvas.onZoom = { zoomLabel.text = "$it %" }
        canvas.hitTest = ::hover
        show(FlowableAtlasBundle.message("preview.rendering"))
        render()
        if (file.isInLocalFileSystem) {
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file || it.file in embedded }) redraw.cancelAndRequest()
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

    /** For tests: whether the line saying the picture is the last one that parsed is up. */
    internal val showsStalePicture: Boolean get() = staleLine.isVisible

    /** For tests: what hovering at [at] would outline and say. */
    internal fun hoverForTest(at: Point2D.Double): SvgCanvas.Hover? = hover(at)

    private fun render() {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Whether this pass drew the editor's unsaved text: a half-typed model is expected not to parse.
            val typing = ReadAction.computeBlocking<Boolean, RuntimeException> { FileDocumentManager.getInstance().isFileModified(file) }
            val drawn = runCatching { load() }
                .onFailure { if (!typing) LOG.warn("Could not draw the preview of ${file.path}", it) }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val doc = drawn.getOrNull()
                when {
                    // Mid-edit: keep the last picture that did parse, and say that it is the last one.
                    doc == null && typing && canvas.document != null -> staleLine.isVisible = true
                    // A renderer that threw is a defect worth naming — not a model without a layout.
                    drawn.isFailure -> show(FlowableAtlasBundle.message("preview.failed",
                        drawn.exceptionOrNull()?.message ?: drawn.exceptionOrNull()?.javaClass?.simpleName ?: ""))
                    doc == null -> show(FlowableAtlasBundle.message("preview.nolayout"))
                    else -> {
                        staleLine.isVisible = false
                        canvas.document = doc
                        canvas.hint = hitMap?.let {
                            FlowableAtlasBundle.message(if (it.opensModels) "preview.hint.subform" else "preview.hint")
                        }
                        (cards.layout as CardLayout).show(cards, CANVAS)
                    }
                }
            }, project.disposed)
        }
    }

    /** What hovering the picture at [at] outlines and says: the element's id, and what a double click opens. */
    private fun hover(at: Point2D.Double): SvgCanvas.Hover? {
        val hit = hitMap?.hitAt(at) ?: return null
        val tip = hit.ref?.let { FlowableAtlasBundle.message("preview.hover.opens", hit.id, it.substringAfter(':')) }
            ?: FlowableAtlasBundle.message("preview.hover", hit.id)
        return SvgCanvas.Hover(hit.bounds, tip)
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
            bytes != null -> cache.Subforms().let { subforms ->
                cache.picture(bytes, file.name, type, subforms).also { embedded = subforms.read.keys.toSet() }
            }
            sibling != null -> null
            else -> cache.resolvePicture(file, type).also { embedded = cache.embeddedFiles(file) }
        }
        val svgText = picture?.svg ?: sibling?.let { String(it.contentsToByteArray(), Charsets.UTF_8) }
        hitMap = HitMap.of(picture)
        if (svgText == null) return null
        val text = SvgFonts.resolvable(svgText)
        return text.byteInputStream().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }
    }

    /**
     * A click on the picture: put the text editor's caret on the element under it — without taking the
     * focus from the picture, so the zoom keys keep working; the Structure view follows the caret anyway.
     */
    private fun select(at: Point2D.Double) {
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
        val offset = offsetAt(at, text) ?: return
        OpenFileDescriptor(project, file, offset).navigate(false)
    }

    /** A double click on the picture: open the model the element under it embeds — a form's subform. */
    private fun open(at: Point2D.Double): Boolean {
        val entry = openTarget(at) ?: return false
        ModelKeyTargets.openAt(project, entry.file) { ModelKeyTargets.lineColumn(entry) }
        return true
    }

    /** The model a double click at [at] opens: the subform's form, found in the model index. */
    internal fun openTarget(at: Point2D.Double): ModelEntry? {
        val ref = hitMap?.refAt(at) ?: return null
        val type = ModelType.entries.firstOrNull { it.id == ref.substringBefore(':') } ?: return null
        return project.service<FlowableModelIndexService>().cachedOrRequest()?.find(ref.substringAfter(':'), type)
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
        // No key of its own: Ctrl/⌘ + 1 is the Project tool window, and a picture must not shadow it.
        zoomAction("preview.actualSize", AllIcons.General.ActualZoom, null) { canvas.actualSize() },
    )

    /** A toolbar zoom action, also on Ctrl/⌘ + [keyCode] while the picture has the focus. */
    private fun zoomAction(key: String, icon: javax.swing.Icon, keyCode: Int?, run: () -> Unit) =
        object : DumbAwareAction(FlowableAtlasBundle.message(key), null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = canvas.document != null }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }.also {
            if (keyCode == null) return@also
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
