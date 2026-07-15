package com.flowable.atlas.explorer

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
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
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Embedded viewer for a self-contained Atlas explorer HTML, rendered with JCEF and wrapped with a
 * thin editor toolbar (Regenerate / Reload / Open in Browser). Registered for any `*.explorer.html`
 * file (see [AtlasFileEditorProvider]), so a page generated into the project can be viewed without
 * leaving the IDE.
 *
 * The page follows the IDE theme: the initial load seeds `?ideTheme=light|dark` (no flash) and a
 * [LafManagerListener] pushes live theme switches via the page's `window.__atlasSetIdeTheme` hook —
 * the IDE theme drives the page's `auto` preference, while an explicit in-page override still wins
 * (see the theme section of `explorer.js` for the contract).
 */
class AtlasFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val wrapper = JPanel(BorderLayout())

    // JS→Kotlin channel so the page's copy buttons work inside the JCEF file:// viewer, where
    // navigator.clipboard is blocked; the page falls back to this via window.__atlasCopy.
    private val copyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            // Re-push after any (re)load: the query param goes stale when the IDE theme switched
            // between load and reload. The page-side `window.__atlasSetIdeTheme &&` guard makes an
            // early or racing push a harmless no-op. The copy bridge is (re)installed the same way.
            if (frame.isMain) { installCopyBridge(); pushIdeTheme() }
        }
    }

    init {
        Disposer.register(this, browser)
        Disposer.register(this, copyQuery)
        copyQuery.addHandler { text ->
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            null
        }
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { pushIdeTheme() })

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

    private fun load() {
        browser.loadURL(file.url + "?ideTheme=" + ideTheme())
    }

    private fun pushIdeTheme() {
        browser.cefBrowser.executeJavaScript(
            "window.__atlasSetIdeTheme && window.__atlasSetIdeTheme('${ideTheme()}');",
            browser.cefBrowser.url,
            0,
        )
    }

    private fun installCopyBridge() {
        // Define window.__atlasCopy(text) → route the string to copyQuery's handler (system clipboard).
        browser.cefBrowser.executeJavaScript(
            "window.__atlasCopy = function(text){ ${copyQuery.inject("text")} };",
            browser.cefBrowser.url,
            0,
        )
    }

    private fun buildToolbarGroup() = DefaultActionGroup(
        object : AnAction("Regenerate", "Re-run the Atlas generator for this file and reload", AllIcons.Actions.ForceRefresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                AtlasGenerationRunner.generateExplorer(project, file.toNioPath(), quiet = true) { load() }
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
                e.presentation.isEnabledAndVisible = AtlasBrowser.isAvailable()
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
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
    }
}
