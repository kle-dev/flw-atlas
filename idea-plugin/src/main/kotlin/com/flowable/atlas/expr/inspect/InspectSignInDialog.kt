package com.flowable.atlas.expr.inspect

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCookieVisitor
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.net.URI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * An embedded-browser SSO login for the "Evaluate Against App" flow: opens the app's base URL in a
 * JCEF browser, lets the user complete the real IdP login (Microsoft/Keycloak/…), then harvests the
 * resulting session cookie for the app host from JCEF's shared cookie store. The harvested `Cookie`
 * header ([harvestedCookie]) is replayed by [InspectClient] so the request rides the user's own
 * authenticated session — no OAuth2 flow is implemented here; the app and IdP do the whole dance.
 *
 * Provider-agnostic by construction: nothing but the base URL (from the connection panel) drives it,
 * and cookies are collected for whatever host that URL resolves to. Cookie collection runs on the CEF
 * thread; the "session detected" hint is pushed back to the EDT.
 *
 * Caller must check [com.intellij.ui.jcef.JBCefApp.isSupported] before constructing this.
 */
class InspectSignInDialog(project: Project, private val baseUrl: String) : DialogWrapper(project) {

    private val browser = JBCefBrowser()
    private val host: String = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
    private val status = JBLabel()

    /** The `name=value; …` header captured from the app host, or null if none was seen. */
    @Volatile
    var harvestedCookie: String? = null
        private set

    init {
        title = "Sign in to Flowable app"
        setOKButtonText("Use this session")
        isModal = true
        init()
        Disposer.register(disposable, browser)
        status.text = "Log in below. Once you're back on the app, click “Use this session”."
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) harvest()
            }
        }, browser.cefBrowser)
        browser.loadURL(baseUrl)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(status, BorderLayout.NORTH)
        add(browser.component.apply { preferredSize = Dimension(960, 720) }, BorderLayout.CENTER)
    }

    override fun doOKAction() {
        // harvest() has been running on every page load; take the latest snapshot and close.
        harvest()
        super.doOKAction()
    }

    /**
     * Read the cookies JCEF would send to [baseUrl] (incl. httpOnly) and assemble them into a `Cookie`
     * header. The visitor fires on the CEF thread; the last call (`count == total - 1`) assembles the
     * result and refreshes the status hint on the EDT.
     */
    private fun harvest() {
        val manager = CefCookieManager.getGlobalManager() ?: return
        val collected = ArrayList<String>()
        manager.visitUrlCookies(baseUrl, true, object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                if (cookie.name.isNotBlank()) collected.add("${cookie.name}=${cookie.value}")
                if (count == total - 1) onCollected(collected.joinToString("; "))
                return true
            }
        })
    }

    private fun onCollected(header: String) {
        harvestedCookie = header.ifBlank { null }
        ApplicationManager.getApplication().invokeLater({
            status.text = if (header.isBlank()) {
                "No session cookie yet for $host — finish the login."
            } else {
                "Session detected for $host. Click “Use this session” when your login is complete."
            }
        }, ModalityState.any())
    }
}
