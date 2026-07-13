package com.flowable.atlas.design

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.ConnectionsConfigurable
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Executes "Pull from Flowable Design": downloads each configured app's export ZIP into the
 * configured project folder — named like Design's own "Export app" (the server's `Content-Disposition`
 * filename, else the app's display name, else its key) — then force-rebuilds the model index once (the
 * VFS listener alone would only invalidate lazily). Each ZIP lands via temp file + atomic move so the
 * index/jar file system never sees a half-written archive. A single app's failure doesn't abort the
 * rest; the summary notification lists what was pulled and what failed. Missing configuration or
 * credentials open the Connections settings page and re-run the pull once configured.
 */
@Service(Service.Level.PROJECT)
class DesignPullService(private val project: Project) {

    /** One successfully written app, for the summary notification and VFS/index refresh. */
    private data class PulledApp(val app: DesignClient.App?, val appKey: String, val target: Path, val size: Int)

    /** Queues the pull as a background task; safe to call from the EDT. */
    fun pullInBackground() {
        object : Task.Backgroundable(project, "Pulling app from Flowable Design", true) {
            override fun run(indicator: ProgressIndicator) = pull(indicator)
        }.queue()
    }

    private fun pull(indicator: ProgressIndicator) {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val projectDir = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (projectDir == null || !settings.isDesignConfigured()) {
            configureThenRetry()
            return
        }
        val credentials = runCatching { DesignCredentials.load(settings.designBaseUrl) }.getOrNull()
        val username = credentials?.userName
        val password = credentials?.getPasswordAsString()
        if (username.isNullOrBlank() || password == null) {
            configureThenRetry()   // e.g. keychain entry was deleted
            return
        }
        val conn = DesignClient.Connection(settings.designBaseUrl, username, password)
        val workspaceKey = settings.designWorkspaceKey
        val appKeys = settings.designAppKeys.toList()
        val targetDir = projectDir.resolve(settings.designTargetFolder.ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER })
            .normalize()

        // Resolve display names/versions once, up front (best-effort — a list failure only degrades the
        // filename/notification to the raw key and never aborts the pull).
        val apps = (DesignClient.listApps(conn, workspaceKey) as? DesignClient.Result.Success)?.value.orEmpty()

        val written = mutableListOf<PulledApp>()
        val failed = mutableListOf<String>()
        val usedNames = mutableSetOf<String>()
        for (appKey in appKeys) {
            indicator.checkCanceled()
            val app = apps.firstOrNull { it.key == appKey }
            val display = app?.name ?: appKey
            indicator.text = "Exporting '$display' from Flowable Design…"
            when (val export = DesignClient.exportApp(conn, workspaceKey, appKey)) {
                is DesignClient.Result.Failed -> failed += "$display — ${export.message}"
                is DesignClient.Result.Success -> {
                    val fileBase = uniqueFileBase(export.value.fileName, app?.name, appKey, usedNames)
                    indicator.text = "Writing $fileBase.zip…"
                    try {
                        val target = writeZip(targetDir, fileBase, export.value.bytes)
                        written += PulledApp(app, appKey, target, export.value.bytes.size)
                    } catch (e: IOException) {
                        failed += "$display — could not write the ZIP (${e.message})"
                    }
                }
            }
        }

        if (written.isEmpty()) {
            notifyFailure(failed.joinToString("<br>").ifBlank { "Nothing was pulled." })
            return
        }

        // Replace, don't accumulate: drop each pulled app's pre-update {key}.zip before the VFS refresh
        // so an upgrade doesn't leave a double-indexed duplicate next to the new {name}.zip.
        removeSupersededLegacyZips(targetDir, written.map { it.appKey }, written.map { it.target.fileName.toString() }.toSet())

        // Make the platform see the new/changed archives before rebuilding the index — one refresh for
        // the whole folder. Synchronous refresh is fine: we are on a pooled thread, not the EDT.
        val outsideContent = refreshVfsAndDetectOutsideContent(targetDir, written.map { it.target })

        indicator.text = "Rebuilding Flowable index…"
        project.service<FlowableModelIndexService>().refresh()

        notifySuccess(projectDir, written, failed, outsideContent)
    }

    private fun writeZip(dir: Path, fileBase: String, bytes: ByteArray): Path {
        Files.createDirectories(dir)
        val target = dir.resolve("$fileBase.zip")
        val tmp = dir.resolve("$fileBase.zip.part")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }


    /**
     * Refreshes the target [folder] and each written archive in the VFS (dropping stale jar-FS caches)
     * and reports whether the archives land outside the project's content roots (so they won't be
     * indexed). All pulled files share the folder, so content membership is decided folder-wide.
     */
    private fun refreshVfsAndDetectOutsideContent(folder: Path, targets: List<Path>): Boolean {
        val lfs = LocalFileSystem.getInstance()
        lfs.refreshAndFindFileByNioFile(folder)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        var anyInContent = false
        for (target in targets) {
            val vf = lfs.refreshAndFindFileByNioFile(target) ?: continue
            JarFileSystem.getInstance().refreshAndFindFileByPath(vf.path + JarFileSystem.JAR_SEPARATOR)
                ?.refresh(false, true)
            if (ReadAction.computeBlocking<Boolean, RuntimeException> { ProjectFileIndex.getInstance(project).isInContent(vf) }) {
                anyInContent = true
            }
        }
        return !anyInContent
    }

    /** Opens the Connections settings page on the EDT and re-runs the pull once configured. */
    private fun configureThenRetry() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ConnectionsConfigurable::class.java)
            if (FlowableAtlasProjectSettings.getInstance(project).isDesignConfigured()) pullInBackground()
        }
    }

    private fun notifySuccess(projectDir: Path, written: List<PulledApp>, failed: List<String>, outsideContent: Boolean) {
        val title = if (written.size == 1) {
            val only = written.first()
            "Pulled ${only.app?.name ?: only.appKey}${only.app?.version?.let { " v$it" } ?: ""} from Flowable Design"
        } else {
            "Pulled ${written.size} apps from Flowable Design"
        }
        var body = written.joinToString("<br>") { "${projectDir.relativize(it.target)} (${(it.size + 1023) / 1024} KB)" } +
            "<br>Flowable index refreshed"
        if (outsideContent) {
            body += "<br>The target folder is outside the project's content roots — models in it will not be indexed."
        }
        if (failed.isNotEmpty()) {
            body += "<br><br>Failed:<br>" + failed.joinToString("<br>")
        }
        val type = if (outsideContent || failed.isNotEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, body, type)
            .notify(project)
        recordPullFinished(succeeded = true)
    }

    private fun notifyFailure(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Pull from Flowable Design failed", message, NotificationType.ERROR)
            .addAction(NotificationAction.createSimple("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ConnectionsConfigurable::class.java)
                if (FlowableAtlasProjectSettings.getInstance(project).isDesignConfigured()) pullInBackground()
            })
            .notify(project)
        recordPullFinished(succeeded = false)
    }

    private fun recordPullFinished(succeeded: Boolean) {
        if (project.isDisposed) return
        if (succeeded) {
            // Workspace-local on purpose: a timestamp in the VCS-shared project settings would be noise.
            PropertiesComponent.getInstance(project)
                .setValue(LAST_PULL_PROPERTY, System.currentTimeMillis().toString())
        }
        project.messageBus.syncPublisher(AtlasEvents.TOPIC).designPullFinished(succeeded)
    }

    companion object {
        private const val GROUP_ID = "Flowable Atlas"

        /** Epoch millis of the last successful pull, in [PropertiesComponent] (workspace-local). */
        const val LAST_PULL_PROPERTY = "flowable.atlas.lastDesignPull"

        fun lastPullMillis(project: Project): Long? =
            PropertiesComponent.getInstance(project).getValue(LAST_PULL_PROPERTY)?.toLongOrNull()

        fun getInstance(project: Project): DesignPullService = project.service()

        // ---- pure filename logic (project-independent, unit-tested) ------------------------------

        /**
         * The sanitized, collision-free base filename (no extension) for a pulled app: the server's
         * `Content-Disposition` name if present, else the app display [appName], else the [appKey].
         * Two apps that would resolve to the same name are disambiguated with the (unique) app key so
         * neither silently overwrites the other; [used] accumulates the names already handed out.
         */
        internal fun uniqueFileBase(serverFileName: String?, appName: String?, appKey: String, used: MutableSet<String>): String {
            val preferred = serverFileName?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.removeSuffix(".zip")?.takeUnless { it.isBlank() }
                ?: appName?.takeUnless { it.isBlank() }
                ?: appKey
            val base = sanitize(preferred)
            if (used.add(base)) return base
            val withKey = "$base-${sanitize(appKey)}"
            if (used.add(withKey)) return withKey
            var i = 2
            while (!used.add("$withKey-$i")) i++
            return "$withKey-$i"
        }

        /**
         * Makes [name] safe as a filename while staying as close to Design's export name as possible:
         * only path separators, control chars and characters illegal on Windows are replaced (spaces,
         * parentheses etc. are kept), and leading/trailing whitespace or dots are trimmed.
         */
        internal fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "-").trim().trim('.').ifBlank { "app" }

        /** The pre-multi-app filename scheme: the app key with everything outside `[A-Za-z0-9._-]` dashed. */
        internal fun legacySanitize(name: String): String = name.replace(Regex("""[^A-Za-z0-9._-]"""), "-")

        /**
         * Removes each pulled app's pre-update `{key}.zip` (the old naming scheme) from [dir] when it
         * differs from the app's new `{name}.zip` — so an upgrade *replaces* the old archive instead of
         * leaving a double-indexed duplicate. The comparison is case-insensitive so a legacy name that
         * only differs in case from a file we just wrote (same file on a case-insensitive filesystem) is
         * never deleted. Unrelated ZIPs (deselected apps, manual exports) are left untouched.
         */
        internal fun removeSupersededLegacyZips(dir: Path, appKeys: List<String>, currentFileNames: Set<String>) {
            val current = currentFileNames.map { it.lowercase() }.toSet()
            for (appKey in appKeys) {
                val legacyName = legacySanitize(appKey) + ".zip"
                if (legacyName.lowercase() in current) continue   // it *is* a file we just wrote — keep it
                runCatching { Files.deleteIfExists(dir.resolve(legacyName)) }
            }
        }
    }
}
