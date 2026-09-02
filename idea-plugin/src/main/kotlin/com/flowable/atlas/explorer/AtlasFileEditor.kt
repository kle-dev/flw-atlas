package com.flowable.atlas.explorer

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.update.UiNotifyConnector
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Embedded viewer for a self-contained Atlas explorer HTML, rendered with JCEF and wrapped with a
 * thin editor toolbar (Regenerate / Reload / Open in Browser). Registered for any `*.explorer.html`
 * file (see [AtlasFileEditorProvider]), so a page generated into the project can be viewed without
 * leaving the IDE.
 *
 * The page follows the IDE theme — and wears its colours: the initial load seeds `?ideTheme=light|dark`
 * plus `&idePal=<nine colours>` (no flash), and a [LafManagerListener] / editor-scheme listener pushes
 * live switches via the page's `window.__atlasSetIdeTheme(mode, palette)` hook. The IDE theme drives
 * the page's `auto` preference, while an explicit in-page override still wins — and drops the IDE
 * colours for the Hub palette of the mode the reader asked for (see the theme section of `explorer.js`
 * for the contract; [IdePalette] for where the colours come from).
 */
class AtlasFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val wrapper = JPanel(BorderLayout())

    // JS→Kotlin channel so the page's copy buttons work inside the JCEF file:// viewer, where
    // navigator.clipboard is blocked; the page falls back to this via window.__atlasCopy.
    private val copyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // JS→Kotlin channel for "open this file (at this line) in the IDE" — window.__atlasOpen. The page
    // shows the source path of every model and Java class and the line of every method and endpoint;
    // this is the jump from reading a model to editing the code around it, the seam the plugin exists for.
    private val openQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // The LaF colours, cached as the two strings the page takes. Computed on the EDT (JBColor resolves
    // there) whenever the LaF or the editor scheme changes; read from the CEF thread in onLoadEnd.
    @Volatile private var paletteJs = "null"
    @Volatile private var paletteParam = ""

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            // Re-push after any (re)load: the query param goes stale when the IDE theme switched
            // between load and reload. The page-side `window.__atlasSetIdeTheme &&` guard makes an
            // early or racing push a harmless no-op. The bridges are (re)installed the same way.
            if (frame.isMain) { installBridges(); pushIdeTheme() }
        }
    }

    init {
        Disposer.register(this, browser)
        Disposer.register(this, copyQuery)
        Disposer.register(this, openQuery)
        copyQuery.addHandler { text ->
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            null
        }
        openQuery.addHandler { payload ->
            // "<file label>|<line or empty>" — the label is what the report carries: relative to the
            // analysed root, `archive!entry` for a model inside a .bar/.zip.
            val bar = payload.lastIndexOf('|')
            val label = if (bar >= 0) payload.substring(0, bar) else payload
            val line = if (bar >= 0) payload.substring(bar + 1).toIntOrNull() else null
            ApplicationManager.getApplication().invokeLater({ openInIde(label, line) }, project.disposed)
            null
        }
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        refreshPalette()
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(LafManagerListener.TOPIC, LafManagerListener { refreshPalette(); pushIdeTheme() })
        // the editor scheme can change without a LaF change, and it is where the page's raised surface comes from
        appBus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refreshPalette(); pushIdeTheme() })
        // The "models changed since this was generated" banner (AtlasExplorerStaleNotificationProvider)
        // is re-evaluated whenever what it compares moves: the index (newest model mtime), a pull, or a
        // regeneration — which clears it, since the runner publishes artifactsGenerated after writing.
        project.messageBus.connect(this).subscribe(
            AtlasEvents.TOPIC,
            object : AtlasEventsListener {
                override fun modelIndexUpdated() = refreshBanner()
                override fun artifactsGenerated(explorerHtml: Path?, written: List<Path>) = refreshBanner()
                override fun designPullFinished(succeeded: Boolean) = refreshBanner()
            },
        )

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("AtlasExplorerEditor", buildToolbarGroup(), true)
        toolbar.targetComponent = wrapper
        wrapper.add(toolbar.component, BorderLayout.NORTH)
        wrapper.add(browser.component, BorderLayout.CENTER)
        // Load only once the browser is actually on screen. Loading eagerly here — before the editor
        // tab is ever shown (opened in the background, or restored on project reopen) — intermittently
        // left the page blank: the initial navigation is issued into a browser whose native surface
        // isn't created/sized yet, so it's lost, which is why closing and reopening the tab "fixed" it.
        // Deferring to first-shown makes the page load reliably whether the tab opens focused or not.
        UiNotifyConnector.doWhenFirstShown(browser.component, { load() }, this)
    }

    private fun ideTheme(): String = if (JBColor.isBright()) "light" else "dark"

    private fun refreshPalette() {
        val p = IdePalette.current()
        paletteJs = IdePalette.toJs(p)
        paletteParam = IdePalette.toUrlParam(p)
    }

    /** Schedule-only, as the listener contract asks: EditorNotifications batches and runs it itself. */
    private fun refreshBanner() {
        if (!project.isDisposed) EditorNotifications.getInstance(project).updateNotifications(file)
    }

    /** Re-run the generator for this page and reload it — the toolbar's Regenerate, and the banner's. */
    internal fun regenerate() {
        AtlasGenerationRunner.generateExplorer(project, file.toNioPath(), quiet = true) { load() }
    }

    private fun load() {
        browser.loadURL(file.url + "?ideTheme=" + ideTheme() + "&idePal=" + paletteParam)
    }

    private fun pushIdeTheme() {
        browser.cefBrowser.executeJavaScript(
            "window.__atlasSetIdeTheme && window.__atlasSetIdeTheme('${ideTheme()}', $paletteJs);",
            browser.cefBrowser.url,
            0,
        )
    }

    private fun installBridges() {
        // window.__atlasCopy(text) → copyQuery (system clipboard); window.__atlasOpen(file, line) →
        // openQuery (an editor tab). The event tells the page the bridges exist, so it shows the open
        // buttons only here and never in a plain browser.
        browser.cefBrowser.executeJavaScript(
            "window.__atlasCopy = function(text){ ${copyQuery.inject("text")} };" +
                "window.__atlasOpen = function(file, line){ ${openQuery.inject("file + '|' + (line || '')")} };" +
                "window.dispatchEvent(new Event('atlas-ide-bridge'));",
            browser.cefBrowser.url,
            0,
        )
    }

    /** Resolve a report file label to a VirtualFile and open it — at [line] (1-based) when given. */
    private fun openInIde(label: String, line: Int?) {
        if (project.isDisposed) return
        val vf = resolveLabel(label)
        if (vf == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("Flowable Atlas")
                .createNotification(
                    "File not found",
                    "$label is not under the analysed project folder any more — regenerate the explorer.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        val descriptor = if (line != null && line > 0) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)
        descriptor.navigate(true)
    }

    /**
     * A label is relative to the folder the report was generated from (the active Flowable project);
     * `archive!entry` names a model inside a .bar/.zip, which the jar file system mounts read-only. A
     * doubly nested `archive!inner.bar!entry` cannot be mounted, so the outer archive opens instead.
     */
    private fun resolveLabel(label: String): VirtualFile? {
        val root = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        val lfs = LocalFileSystem.getInstance()
        val bang = label.indexOf('!')
        if (bang < 0) return lfs.refreshAndFindFileByNioFile(root.resolve(label))
        val archive = lfs.refreshAndFindFileByNioFile(root.resolve(label.substring(0, bang))) ?: return null
        val entry = label.substring(bang + 1)
        if (entry.contains('!')) return archive
        return JarFileSystem.getInstance().findFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR + entry) ?: archive
    }

    private fun buildToolbarGroup() = DefaultActionGroup(
        object : AnAction("Regenerate", "Re-run the Atlas generator for this file and reload", AllIcons.Actions.ForceRefresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                regenerate()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = project.basePath != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        },
        object : AnAction("Reload", "Reload the page without regenerating", AllIcons.Actions.Refresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                browser.cefBrowser.reloadIgnoreCache()
            }
        },
        Separator.getInstance(),
        object : AnAction("Open in Browser", "Open this explorer in the external browser", AllIcons.General.Web), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                AtlasBrowser.open(file.toNioPath())
            }

            // Hidden where a browser launch can't work (headless Remote-Dev host / no configured browser).
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = AtlasBrowser.canOpenFiles()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        },
    )

    override fun getComponent(): JComponent = wrapper
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "Atlas Explorer"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    // The isDisposed guard keeps late queries (editor-history bookkeeping during IDE shutdown) from
    // touching an already-disconnected VFS, which logs a scary AlreadyDisposedException warning.
    override fun isValid(): Boolean = !project.isDisposed && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
    }
}
