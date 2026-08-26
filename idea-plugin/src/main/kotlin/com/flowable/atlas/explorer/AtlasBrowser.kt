package com.flowable.atlas.explorer

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowserManager
import com.intellij.idea.AppMode
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Remote-Dev-safe "Open in Browser" for generated Atlas HTML. Uses the platform browser launcher — the
 * same mechanism as IntelliJ's built-in "Open in Browser" for HTML files: it honours the configured
 * browsers and is routed to the client under Remote Dev. It also gates its own availability, because
 * the old `BrowserUtil.browse(File)` pointed at the backend/pod filesystem and silently did nothing on
 * a headless Remote-Dev host.
 */
object AtlasBrowser {

    /**
     * True when a **local file** can be opened in a browser: not the headless Remote-Dev host, and a
     * browser is configured.
     *
     * A generated `explorer.html` lives on the backend's disk, and under Remote Dev the client cannot
     * see that path — there is nothing to route, so the control is honestly disabled.
     */
    fun canOpenFiles(): Boolean =
        !AppMode.isRemoteDevHost() && WebBrowserManager.getInstance().activeBrowsers.isNotEmpty()

    /**
     * True when a **URL** can be opened — which is nearly always, and deliberately not the same question
     * as [canOpenFiles].
     *
     * This used to share that check, and it was wrong in the one case it mattered: under Remote Dev the
     * whole plugin runs on the backend, so *Open Environment in Browser* was greyed out for every
     * remote developer. A URL is not a backend path. It means the same thing on the client, and
     * [BrowserLauncher] is precisely the API that routes it there — which is why [open] uses it instead
     * of `BrowserUtil`. Gating it on the backend having a browser configured asked the wrong machine.
     */
    fun canOpenUrls(): Boolean = true

    /** Open [path] in the default configured browser (the same as the built-in "Open in Browser"). */
    fun open(path: Path) {
        service<BrowserLauncher>().browse(path)
    }

    /**
     * Open [url] in the default configured browser — an environment's Work, Design, Control or Hub
     * address from the Atlas Hub.
     *
     * Through the same launcher as [open] rather than `BrowserUtil.browse(String)`, for the same
     * reason: under Remote Dev the browse has to be routed to the client, and the launcher is what
     * knows that. Blank is a no-op because a connection may legitimately exist with no URL typed yet.
     */
    fun open(url: String, project: Project?) {
        if (url.isBlank()) return
        service<BrowserLauncher>().browse(url, null, project)
    }
}
