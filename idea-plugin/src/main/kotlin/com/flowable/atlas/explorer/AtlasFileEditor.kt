package com.flowable.atlas.explorer

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.model.MiniJson
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.SwingConstants
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.flowable.atlas.graph.Waivers
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.update.UiNotifyConnector
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
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
 *
 * Under Remote Development the page is not loaded from its file: the thin client's browser would pull
 * it from the host 16 KB at a time. A stub at the page's URL fetches it through the JS bridge in one
 * round trip and caches it client-side instead — see [RemoteExplorerPage].
 *
 * Three states besides the page itself: *loading* (a spinner at the right of the toolbar — the browser
 * cannot be covered, it would paint over an overlay, and cannot be hidden before its first load), a
 * failed load (a panel saying so, with Reload and Regenerate, in place of a blank tab), and a tab
 * reopened after a restart, which comes back on the page it was left on ([AtlasExplorerState]).
 */
class AtlasFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val wrapper = JPanel(BorderLayout())
    /** The browser, or the panel that says the page could not be loaded — never a blank tab. */
    private val body = JPanel(BorderLayout())
    private val loading = JBLabel(message("explorer.loading"), AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyRight(10)
        isVisible = false
    }

    // JS→Kotlin channel so the page's copy buttons work inside the JCEF file:// viewer, where
    // navigator.clipboard is blocked; the page falls back to this via window.__atlasCopy.
    private val copyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // JS→Kotlin channel for "open this file (at this line) in the IDE" — window.__atlasOpen. The page
    // shows the source path of every model and Java class and the line of every method and endpoint;
    // this is the jump from reading a model to editing the code around it, the seam the plugin exists for.
    private val openQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // JS→Kotlin channel that carries the page itself under Remote Dev — window.__atlasFetch(i, ok, fail)
    // answers with part i of the prepared report. One query, one response, any size: the channel the
    // 16 KB resource packets never get (see RemoteExplorerPage).
    private val fetchQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // JS→Kotlin channel for "write these accepted findings into the project" — window.__atlasSaveWaivers.
    // The page can only ever hand a browser a download; here the decision can land where the analysis
    // already is, in the file the next run reads and the next pull request reviews.
    private val waiverQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val remote = RemoteExplorerPage.isRemoteDevHost()
    @Volatile private var remotePage: RemoteExplorerPage.Transfer? = null

    // The LaF colours, cached as the two strings the page takes. Computed on the EDT (JBColor resolves
    // there) whenever the LaF or the editor scheme changes; read from the CEF thread in onLoadEnd.
    @Volatile private var paletteJs = "null"
    @Volatile private var paletteParam = ""
    /** A route asked for before the page was shown — consumed by the first [load]. */
    @Volatile private var pendingHash: String? = null
    @Volatile private var pageLoaded = false

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            // Re-push after any (re)load: the query param goes stale when the IDE theme switched
            // between load and reload. The page-side `window.__atlasSetIdeTheme &&` guard makes an
            // early or racing push a harmless no-op. The bridges are (re)installed the same way.
            if (frame.isMain) {
                installBridges(); pushIdeTheme(); pageLoaded = true
                ApplicationManager.getApplication().invokeLater({ loading.isVisible = false }, project.disposed)
            }
        }

        override fun onLoadError(cefBrowser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String?, failedUrl: String?) {
            // An aborted load is a navigation that replaced it (a hash change, a reload), not a failure.
            if (!frame.isMain || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            ApplicationManager.getApplication().invokeLater({ showLoadError(errorText ?: errorCode.name) }, project.disposed)
        }
    }

    init {
        Disposer.register(this, browser)
        Disposer.register(this, copyQuery)
        Disposer.register(this, openQuery)
        Disposer.register(this, fetchQuery)
        Disposer.register(this, waiverQuery)
        waiverQuery.addHandler { text ->
            // Beside the report it came from: that folder is the analysis output, and waivers.json is
            // the one file in it that is meant to be kept (Atlas writes a .gitignore there saying so).
            ApplicationManager.getApplication().invokeLater({ saveWaivers(text) }, project.disposed)
            null
        }
        fetchQuery.addHandler { index ->
            val page = remotePage
            val i = index.toIntOrNull()
            if (page == null || i == null || i !in page.parts.indices) JBCefJSQuery.Response(null, 1, "no page part $index")
            else JBCefJSQuery.Response(page.parts[i])
        }
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
        // Disposer runs children last-registered-first, so this remover — registered after the browser —
        // runs while the client is still alive. dispose() used to do it after the browser was gone.
        Disposer.register(this) { runCatching { browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser) } }
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
        wrapper.add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.CENTER)
            add(loading, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        body.add(browser.component, BorderLayout.CENTER)
        wrapper.add(body, BorderLayout.CENTER)
        // Load only once the browser is actually on screen. Loading eagerly here — before the editor
        // tab is ever shown (opened in the background, or restored on project reopen) — intermittently
        // left the page blank: the initial navigation is issued into a browser whose native surface
        // isn't created/sized yet, so it's lost, which is why closing and reopening the tab "fixed" it.
        // Deferring to first-shown makes the page load reliably whether the tab opens focused or not.
        UiNotifyConnector.doWhenFirstShown(browser.component, { load(pendingHash.also { pendingHash = null }) }, this)
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
    /**
     * Regenerate the report and show the new page **where the reader was**: the fragment (a Checks block,
     * a node) travels along, and the reload bypasses the CEF cache — `loadURL` on an unchanged URL could
     * hand back the page as it was before the file was rewritten. Accepting three findings from the middle
     * of `#/checks` and pressing Save used to land on the dashboard. (JCEF has no headless test seam; the
     * Remote-Dev half is covered by [RemoteExplorerPageTest], this half by the sandbox.)
     */
    internal fun regenerate() {
        val hash = browser.cefBrowser.url?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }
        AtlasGenerationRunner.generateExplorer(project, file.toNioPath(), quiet = true) {
            if (remote) load(hash) else browser.cefBrowser.reloadIgnoreCache()
        }
    }

    /**
     * Show a route of the page — `process%3ADEMO-P001` for a node, `/checks` for a report. The page routes
     * on its hash, so a loaded page just gets the hash set; a page not yet shown gets it with its first load.
     * This is what "Open in Atlas Explorer" on a Java key literal rides on.
     */
    fun navigate(hash: String) {
        // Only what [ExplorerRoutes] produces: percent-encoded plus `/`, `&`, `=` — nothing that could
        // close the JS string. Anything else is refused rather than escaped.
        if (!Regex("[A-Za-z0-9._~%/&=:-]*").matches(hash)) return
        if (!pageLoaded) { pendingHash = hash; return }
        browser.cefBrowser.executeJavaScript("location.hash = '#$hash';", browser.cefBrowser.url, 0)
    }

    private fun load(hash: String? = null) {
        showPage()
        loading.isVisible = true
        val frag = hash?.let { "#$it" } ?: ""
        val url = file.url + "?ideTheme=" + ideTheme() + "&idePal=" + paletteParam
        if (!remote) { browser.loadURL(url + frag); return }
        // Off the EDT: a report can be several MB. The stub is loaded *at the page's URL* (plus its hash,
        // so a regeneration is a fresh navigation), which is what keeps ?ideTheme/?idePal reaching the
        // page and its localStorage preferences on the same file:// origin. If the file cannot be read
        // the viewer falls back to loading it directly — slow, but not blank.
        ApplicationManager.getApplication().executeOnPooledThread {
            val page = runCatching { RemoteExplorerPage.prepare(file.toNioPath()) }
                .onFailure { LOG.warn("Atlas explorer: could not prepare ${file.path} for the Remote Dev viewer; loading it directly", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (Disposer.isDisposed(this)) return@invokeLater
                if (page == null) { browser.loadURL(url); return@invokeLater }
                remotePage = page
                browser.loadHTML(page.stub, "$url&page=${page.hash.take(12)}$frag")
            }, project.disposed)
        }
    }

    /** The page could not be loaded: say so where it would have been, with the two ways to get it back. */
    private fun showLoadError(reason: String) {
        loading.isVisible = false
        val failure = panel {
            row { label(message("explorer.loadFailed.title")).bold() }
            row { text(StringUtil.escapeXmlEntities(message("explorer.loadFailed", file.name, reason)), MAX_LINE_LENGTH_WORD_WRAP) }
            row {
                link(message("explorer.reload")) { load(pendingHash) }
                link(FlowableActionIds.text(FlowableActionIds.REGENERATE_ATLAS_EXPLORER)) { regenerate() }
            }
        }.withBorder(JBUI.Borders.empty(24))
        body.removeAll()
        body.add(failure, BorderLayout.NORTH)
        body.revalidate()
        body.repaint()
    }

    private fun showPage() {
        if (body.componentCount == 1 && body.getComponent(0) === browser.component) return
        body.removeAll()
        body.add(browser.component, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
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
                "window.__atlasFetch = function(i, ok, fail){ ${fetchQuery.inject("String(i)", "ok", "fail")} };" +
                "window.__atlasSaveWaivers = function(text){ ${waiverQuery.inject("text")} };" +
                "window.dispatchEvent(new Event('atlas-ide-bridge'));",
            browser.cefBrowser.url,
            0,
        )
    }

    /**
     * Write `waivers.json` next to the open report, tell the page, and regenerate. Explicit, never
     * automatic: a silent write into someone's repository is a write nobody can review. Regenerating
     * *is* automatic, because the only bad state after a save is a page whose accepted rows sit beside
     * counts and a gate that still contradict them — and the page batches every decision into one save.
     */
    private fun saveWaivers(payload: String) {
        // `{"text": …, "base": [ids]}` — the file as the page sees it, and the rule ids it started from.
        val envelope = MiniJson.parseOrNull(payload) as? Map<*, *>
        val text = envelope?.get("text") as? String ?: payload
        @Suppress("UNCHECKED_CAST")
        val base = (envelope?.get("base") as? List<Any?>).orEmpty().map { it.toString() }
        fun say(title: String, body: String, type: NotificationType) =
            AtlasNotifications.groupFor(type).createNotification(title, body, type)
        fun tellPage(ok: Boolean) = browser.cefBrowser.executeJavaScript(
            "window.__atlasWaiversSaved && window.__atlasWaiversSaved($ok);", browser.cefBrowser.url, 0)
        // Beside the report it came from: that folder is the analysis output, and waivers.json is the
        // one file in it meant to be kept — Atlas writes a .gitignore there saying exactly that.
        val dir = file.parent
        if (dir == null) {
            tellPage(false)
            say("Could not save waivers", "The report has no folder to write to.", NotificationType.ERROR).notify(project)
            return
        }
        try {
            val written = WaiverFileWriter.write(dir, Waivers.parse(text), base)
            val kept = written.kept
            val target = written.file
            tellPage(true)
            val rules = Regex("\"check\"\\s*:").findAll(text).count() + kept
            say("Waivers saved",
                "$rules rule(s) written to ${dir.name}/${Waivers.FILE_NAME}" +
                    (if (kept > 0) " ($kept the file gained since this page was generated, kept)" else "") +
                    " — regenerating the explorer so the counts and the CI gate follow.", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Open ${Waivers.FILE_NAME}") {
                    if (!project.isDisposed) FileEditorManager.getInstance(project).openFile(target, true)
                })
                .notify(project)
            regenerate()
        } catch (e: Exception) {
            tellPage(false)
            say("Could not save waivers", e.message ?: e.toString(), NotificationType.ERROR).notify(project)
        }
    }

    /** Resolve a report file label to a VirtualFile — off the EDT, it may refresh — and open it at [line]. */
    private fun openInIde(label: String, line: Int?) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = resolveLabel(label)
            ApplicationManager.getApplication().invokeLater({ openResolved(label, vf, line) }, project.disposed)
        }
    }

    private fun openResolved(label: String, vf: VirtualFile?, line: Int?) {
        if (vf == null) {
            // A dead end said so before; now the fix is one click, on the balloon that says what broke.
            AtlasNotifications.group()
                .createNotification(
                    "File not found",
                    "$label is not under the analysed project folder any more — the page is older than the files.",
                    NotificationType.WARNING,
                )
                .addAction(NotificationAction.createSimpleExpiring(FlowableActionIds.text(FlowableActionIds.REGENERATE_ATLAS_EXPLORER)) {
                    if (!project.isDisposed) regenerate()
                })
                .notify(project)
            return
        }
        val descriptor = if (line != null && line > 0) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)
        descriptor.navigate(true)
    }

    /** A label of this page, relative to the folder the report was generated from (see [AtlasFileLabels]). */
    private fun resolveLabel(label: String): VirtualFile? {
        val root = AtlasExplorerFiles.rootOf(project, file.toNioPath())
            ?: AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        return AtlasFileLabels.resolve(root, label)
    }

    private fun buildToolbarGroup() = DefaultActionGroup(
        // The browser's back/forward, which a JCEF tab has no chrome for: from a finding on the Checks
        // page to the model and back again was a dead end without them.
        object : AnAction(message("explorer.back"), message("explorer.back.description"), AllIcons.Actions.Back), DumbAware {
            override fun actionPerformed(e: AnActionEvent) { browser.cefBrowser.goBack() }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = browser.cefBrowser.canGoBack() }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        },
        object : AnAction(message("explorer.forward"), message("explorer.forward.description"), AllIcons.Actions.Forward), DumbAware {
            override fun actionPerformed(e: AnActionEvent) { browser.cefBrowser.goForward() }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = browser.cefBrowser.canGoForward() }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        },
        Separator.getInstance(),
        object : AnAction(
            FlowableActionIds.text(FlowableActionIds.REGENERATE_ATLAS_EXPLORER),
            message("explorer.regenerate.description"), AllIcons.Actions.Compile,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                regenerate()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = project.basePath != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        },
        object : AnAction(message("explorer.reload"), message("explorer.reload.description"), AllIcons.Actions.Refresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                // Under Remote Dev a reload re-reads the file: the stub's cache is keyed by content, so a
                // page rewritten by the CLI would otherwise come back from the cache as it was. After a
                // failed load there is no page to reload: load it again.
                if (remote || !pageLoaded || body.getComponent(0) !== browser.component) load(currentRoute())
                else { loading.isVisible = true; browser.cefBrowser.reloadIgnoreCache() }
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        },
        Separator.getInstance(),
        // The playground used to be a second editor tab on every explorer file — a whole second panel,
        // with its own alarms and listeners, per open page. One tool window, one click away.
        ActionManager.getInstance().getAction(FlowableActionIds.OPEN_ATLAS_PLAYGROUND),
        object : AnAction(message("hub.explorer.openInBrowser"), message("explorer.openInBrowser.description"), AllIcons.General.Web), DumbAware {
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
    override fun getName(): String = message("explorer.editor.name")
    override fun getFile(): VirtualFile = file

    /** The page's route now — its hash, or the one waiting for the first load; null on the dashboard. */
    private fun currentRoute(): String? =
        if (pageLoaded) browser.cefBrowser.url?.substringAfter('#', "")?.takeIf { it.isNotEmpty() } else pendingHash

    // The route survives closing the IDE: the tab comes back on the page it was left on, not the dashboard.
    override fun getState(level: FileEditorStateLevel): FileEditorState = AtlasExplorerState(currentRoute().orEmpty())

    override fun setState(state: FileEditorState) {
        val route = (state as? AtlasExplorerState)?.route?.takeIf { it.isNotEmpty() } ?: return
        navigate(route)
    }
    override fun isModified(): Boolean = false
    // The isDisposed guard keeps late queries (editor-history bookkeeping during IDE shutdown) from
    // touching an already-disconnected VFS, which logs a scary AlreadyDisposedException warning.
    override fun isValid(): Boolean = !project.isDisposed && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}

    private companion object {
        val LOG = logger<AtlasFileEditor>()
    }
}
