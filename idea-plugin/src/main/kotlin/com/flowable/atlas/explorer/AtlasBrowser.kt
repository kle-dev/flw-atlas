package com.flowable.atlas.explorer

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowserManager
import com.intellij.idea.AppMode
import com.intellij.openapi.components.service
import java.nio.file.Path

/**
 * Remote-Dev-safe "Open in Browser" for generated Atlas HTML. Uses the platform browser launcher — the
 * same mechanism as IntelliJ's built-in "Open in Browser" for HTML files: it honours the configured
 * browsers and is routed to the client under Remote Dev. It also gates its own availability, because
 * the old `BrowserUtil.browse(File)` pointed at the backend/pod filesystem and silently did nothing on
 * a headless Remote-Dev host.
 */
object AtlasBrowser {

    /** True when opening a browser can actually work: not the headless Remote-Dev host, and a browser is configured. */
    fun isAvailable(): Boolean =
        !AppMode.isRemoteDevHost() && WebBrowserManager.getInstance().activeBrowsers.isNotEmpty()

    /** Open [path] in the default configured browser (the same as the built-in "Open in Browser"). */
    fun open(path: Path) {
        service<BrowserLauncher>().browse(path)
    }
}
