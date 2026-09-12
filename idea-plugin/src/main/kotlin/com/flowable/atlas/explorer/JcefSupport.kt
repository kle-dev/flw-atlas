package com.flowable.atlas.explorer

/**
 * Whether the running IDE can host an embedded browser.
 *
 * Since 2026.2 JCEF lives in the bundled *Web Browser (JCEF)* plugin, which `plugin.xml` depends on
 * optionally: with that plugin disabled, `com.intellij.ui.jcef.*` is not on this plugin's classloader at
 * all, and the first class that so much as *names* `JBCefApp` fails to link — a `NoClassDefFoundError`,
 * not `isSupported() == false`. `AtlasFileEditorProvider.accept()` used to name it on every file open.
 *
 * The probe lives in its own object so the link happens only when [isAvailable] runs, where it is caught.
 */
object JcefSupport {

    /** True when the JCEF plugin is loaded and the platform reports a working browser. */
    fun isAvailable(): Boolean = try {
        Probe.supported()
    } catch (e: LinkageError) {
        false
    }

    private object Probe {
        fun supported(): Boolean = com.intellij.ui.jcef.JBCefApp.isSupported()
    }
}
