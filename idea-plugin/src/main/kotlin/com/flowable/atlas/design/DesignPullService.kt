package com.flowable.atlas.design

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
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
 * Executes "Pull from Flowable Design": downloads the configured app's export ZIP and writes it as
 * `{appKey}.zip` into the configured project folder, then force-rebuilds the model index (the VFS
 * listener alone would only invalidate lazily). The ZIP lands via temp file + atomic move so the
 * index/jar file system never sees a half-written archive. Missing configuration or credentials
 * open the Connections settings page and re-run the pull once configured.
 */
@Service(Service.Level.PROJECT)
class DesignPullService(private val project: Project) {

    /** Queues the pull as a background task; safe to call from the EDT. */
    fun pullInBackground() {
        object : Task.Backgroundable(project, "Pulling app from Flowable Design", true) {
            override fun run(indicator: ProgressIndicator) = pull(indicator)
        }.queue()
    }

    private fun pull(indicator: ProgressIndicator) {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val basePath = project.basePath
        if (basePath == null || !settings.isDesignConfigured()) {
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
        val appKey = settings.designAppKey

        indicator.text = "Exporting '$appKey' from Flowable Design…"
        val bytes = when (val export = DesignClient.exportApp(conn, workspaceKey, appKey)) {
            is DesignClient.Result.Failed -> {
                notifyFailure(export.message)
                return
            }
            is DesignClient.Result.Success -> export.value
        }
        indicator.checkCanceled()

        // Best-effort: resolve the app's display name/version for the notification.
        val app = (DesignClient.listApps(conn, workspaceKey) as? DesignClient.Result.Success)
            ?.value?.firstOrNull { it.key == appKey }
        indicator.checkCanceled()

        indicator.text = "Writing $appKey.zip…"
        val target = try {
            writeZip(Path.of(basePath), settings.designTargetFolder, appKey, bytes)
        } catch (e: IOException) {
            notifyFailure("Could not write the ZIP: ${e.message}")
            return
        }

        // Make the platform see the new/changed archive before rebuilding the index. Synchronous
        // refresh is fine here — we are on a pooled thread, not the EDT.
        val lfs = LocalFileSystem.getInstance()
        lfs.refreshAndFindFileByNioFile(target.parent)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        val vf = lfs.refreshAndFindFileByNioFile(target)
        vf?.let {
            // Drop stale jar-FS caches of the previous archive content.
            JarFileSystem.getInstance().refreshAndFindFileByPath(it.path + JarFileSystem.JAR_SEPARATOR)
                ?.refresh(false, true)
        }

        indicator.text = "Rebuilding Flowable index…"
        project.service<FlowableModelIndexService>().refresh()

        val outsideContent = vf == null || !ReadAction.compute<Boolean, RuntimeException> {
            ProjectFileIndex.getInstance(project).isInContent(vf)
        }
        notifySuccess(app, appKey, Path.of(basePath).relativize(target), bytes.size, outsideContent)
    }

    private fun writeZip(projectDir: Path, targetFolder: String, appKey: String, bytes: ByteArray): Path {
        val folder = targetFolder.ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER }
        val dir = projectDir.resolve(folder).normalize()
        Files.createDirectories(dir)
        val fileName = appKey.replace(Regex("""[^A-Za-z0-9._-]"""), "-")
        val target = dir.resolve("$fileName.zip")
        val tmp = dir.resolve("$fileName.zip.part")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /** Opens the Connections settings page on the EDT and re-runs the pull once configured. */
    private fun configureThenRetry() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ConnectionsConfigurable::class.java)
            if (FlowableAtlasProjectSettings.getInstance(project).isDesignConfigured()) pullInBackground()
        }
    }

    private fun notifySuccess(app: DesignClient.App?, appKey: String, relativeTarget: Path, size: Int, outsideContent: Boolean) {
        val title = "Pulled ${app?.name ?: appKey}${app?.version?.let { " v$it" } ?: ""} from Flowable Design"
        var body = "$relativeTarget (${(size + 1023) / 1024} KB) — Flowable index refreshed"
        if (outsideContent) {
            body += "<br>The target folder is outside the project's content roots — models in it will not be indexed."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, body, if (outsideContent) NotificationType.WARNING else NotificationType.INFORMATION)
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
    }
}
