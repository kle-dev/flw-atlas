package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ServiceTable
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.flowable.atlas.settings.GenerationConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Backs the "Generate → Liquibase" dialog ([GenerateLiquibaseDialog]). Splits into three concerns:
 *
 *  - **plan** ([computePlans]) — a read-only pass over the model index and the app-export zips that
 *    resolves, for every data object and every bundled changelog, what would be written and *how*
 *    (extracted verbatim from the bundling app, or synthesized from the data object's fields). No file
 *    is touched; the dialog renders this as a preview. Must run under a read action (the caller wraps).
 *  - **write** ([writeResolved]) — applies the user's confirmed selection in a single write command:
 *    each changelog under the configured output folder, the master `flowable-project-db-changelog.xml`
 *    created from a skeleton when absent, and each file registered via an idempotent `<include>`.
 *  - **report** ([reportGenerated]) — opens the first file and raises the summary notification.
 *
 * The only project files ever created or modified are the per-changelog `*.changelog.xml` files and
 * the master changelog — never a `liquibase.properties` or any build file.
 */
@Service(Service.Level.PROJECT)
class LiquibaseScaffoldService(private val project: Project) {

    // ---- plan model ---------------------------------------------------------------------------

    /** Where a changelog's content comes from — surfaced verbatim in the preview so the user sees it. */
    sealed interface Origin {
        val label: String

        /** The bundling app export shipped a Liquibase model; its [fileName] XML is emitted unchanged. */
        data class Extracted(val fileName: String) : Origin {
            override val label get() = "Extracted from $fileName"
        }

        /** No bundled changelog matched; a `createTable` is synthesized from [fieldCount] data-object fields. */
        data class Synthesized(val fieldCount: Int) : Origin {
            override val label get() = "Synthesized ($fieldCount field${if (fieldCount == 1) "" else "s"})"
        }
    }

    /**
     * One candidate changelog for the preview: its [key], the [defaultName] the `{name}` token starts
     * from (slug of the model's display name / key), the [serviceKey] and [tableName] the other tokens
     * derive from, its [origin] and the resolved [xml] body.
     */
    data class ChangelogPlanItem(
        val key: String,
        val defaultName: String,
        val serviceKey: String?,
        val tableName: String?,
        val origin: Origin,
        val xml: String,
    )

    /** The full preview for both sources, computed in one pass so switching source is instant. */
    data class Plans(val dataObjects: List<ChangelogPlanItem>, val apps: List<ChangelogPlanItem>)

    /** A confirmed write: the final [fileName] (rendered from the pattern) and the [xml] to store. */
    data class ChangelogWrite(val fileName: String, val xml: String)

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Compute the preview off the EDT and open [GenerateLiquibaseDialog] with [source] preselected. When
     * nothing at all is generatable (no data objects, no bundled changelogs), a notification is shown
     * instead of an empty dialog. Both "Generate → Liquibase" menu actions funnel through here.
     */
    fun openDialog(source: LiquibaseSource) {
        val base = projectBaseDir()
            ?: return notify("Cannot generate changelogs", "No project directory on disk.", NotificationType.ERROR)
        object : Task.Backgroundable(project, "Preparing Liquibase preview", true) {
            override fun run(indicator: ProgressIndicator) {
                val plans = try {
                    ReadAction.computeBlocking<Plans, RuntimeException> { computePlans(base) }
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (t: Throwable) {
                    thisLogger().warn("Liquibase preview failed", t)
                    return notifyLater("Liquibase preview failed", t.message ?: t.javaClass.simpleName, NotificationType.ERROR)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (plans.dataObjects.isEmpty() && plans.apps.isEmpty()) {
                        notify(
                            "Nothing to generate",
                            "No data objects are indexed and no app export ships a Liquibase changelog.",
                            NotificationType.INFORMATION,
                        )
                    } else {
                        GenerateLiquibaseDialog(project, base, plans, source).show()
                    }
                }
            }
        }.queue()
    }

    // ---- plan ---------------------------------------------------------------------------------

    /** The active project directory on disk, or null when it is not materialized (e.g. remote/default). */
    fun projectBaseDir(): VirtualFile? {
        val dir = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
    }

    /**
     * Resolve every data object (extract-if-bundled, else synthesize) and every bundled changelog under
     * [base] into a [Plans] preview. Read access required — call inside a read action.
     */
    fun computePlans(base: VirtualFile): Plans {
        val index = project.service<FlowableModelIndexService>()
        val candidates = bundledChangelogs(base)

        // Resolve data objects first — reading keysOfType builds the index cache that dataObjectTables()
        // relies on, and lets us record the service/table metadata per bundled changelog so the
        // app-export rows can reuse it (their `{service}`/`{table}` tokens otherwise have no source).
        val entries = index.keysOfType(ModelType.DATA_OBJECT).distinctBy { it.key }.sortedBy { it.key }
        val tables = index.dataObjectTables()
        val enrichByChangelog = HashMap<String, Pair<String?, String?>>()

        val dataObjects = entries.mapNotNull { entry ->
            val info = index.dataObjectInfoOf(entry.key) ?: return@mapNotNull null
            val service = info.referencedServiceDefinitionModelKey?.let { index.serviceTableOf(it) }
            val modelTable = service?.tableName ?: tables[entry.key]
            val bundled = matchBundled(entry.key, service, candidates)
            val origin: Origin
            val xml: String
            if (bundled != null) {
                origin = Origin.Extracted(bundled.fileName)
                xml = bundled.xml
            } else {
                origin = Origin.Synthesized(info.fieldMappings.size)
                xml = LiquibaseChangelogGenerator.synthesize(entry.key, modelTable.orEmpty(), info.fieldMappings)
            }
            val tableName = tableNameOf(xml, modelTable)
            val serviceKey = info.referencedServiceDefinitionModelKey
            if (bundled != null) enrichByChangelog[bundled.key] = serviceKey to tableName
            ChangelogPlanItem(entry.key, LiquibaseFileNamePattern.slug(entry.name), serviceKey, tableName, origin, xml)
        }

        val apps = candidates.distinctBy { it.key }.map {
            val enriched = enrichByChangelog[it.key]
            ChangelogPlanItem(
                key = it.key,
                defaultName = LiquibaseFileNamePattern.slug(it.key),
                serviceKey = enriched?.first,
                tableName = enriched?.second ?: tableNameOf(it.xml, null),
                origin = Origin.Extracted(it.fileName),
                xml = it.xml,
            )
        }
        return Plans(dataObjects, apps)
    }

    /** The changelog's own `createTable` name (authoritative), else the model-derived [fallback]; blank → null. */
    private fun tableNameOf(xml: String, fallback: String?): String? =
        LiquibaseChangelog.tableNames(xml).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }

    /**
     * The bundled changelog backing a data object, matched (strongest first): the backing service's
     * `referencedLiquibaseModelKey` equals the changelog's key; else a changelog whose `tableName`
     * equals the service's; else the changelog whose key equals the data-object key. Null when none
     * line up (→ synthesize).
     */
    private fun matchBundled(
        dataObjectKey: String,
        service: ServiceTable?,
        candidates: List<LiquibaseChangelogExtractor.Extracted>,
    ): LiquibaseChangelogExtractor.Extracted? {
        service?.referencedLiquibaseModelKey?.takeIf { it.isNotBlank() }?.let { lk ->
            candidates.firstOrNull { it.key == lk }?.let { return it }
        }
        service?.tableName?.takeIf { it.isNotBlank() }?.let { tn ->
            candidates.firstOrNull { c -> LiquibaseChangelog.tableNames(c.xml).any { it.equals(tn, ignoreCase = true) } }
                ?.let { return it }
        }
        return candidates.firstOrNull { it.key == dataObjectKey }
    }

    /**
     * Every bundled changelog under [base] — extracted from each `.zip`/`.bar` app export and read from
     * any loose `*.data.changelog.xml` of an unpacked export. Build-output directories and the configured
     * Liquibase output folder are skipped, so the master and already-generated changelogs are never
     * re-surfaced as app exports.
     */
    private fun bundledChangelogs(base: VirtualFile): List<LiquibaseChangelogExtractor.Extracted> {
        val outputDir = base.findFileByRelativePath(FlowableAtlasProjectSettings.getInstance(project).liquibaseOutputDir)
        val out = ArrayList<LiquibaseChangelogExtractor.Extracted>()
        VfsUtilCore.iterateChildrenRecursively(
            base,
            { vf -> !(vf.isDirectory && (vf.name in ModelPaths.EXCLUDE_DIRS || vf == outputDir)) },
            { file ->
                when {
                    LiquibaseChangelogExtractor.isArchive(file) -> out.addAll(LiquibaseChangelogExtractor.extract(file))
                    LiquibaseChangelogExtractor.isLooseChangelog(file) ->
                        LiquibaseChangelogExtractor.readLoose(file)?.let { out.add(it) }
                }
                true
            },
        )
        return out
    }

    // ---- write --------------------------------------------------------------------------------

    /**
     * Write each of [writes] to `<outputDir>/<fileName>` under [projectDir], create the master skeleton
     * when absent and register each via an idempotent `<include>`. Files that already exist are
     * overwritten unless [skipExisting] (then their content is kept but the include is still ensured).
     * One write command; returns the files actually written (created or overwritten; master excluded).
     */
    fun writeResolved(
        projectDir: VirtualFile,
        outputDir: String,
        writes: List<ChangelogWrite>,
        skipExisting: Boolean,
    ): List<VirtualFile> {
        val written = ArrayList<VirtualFile>()
        WriteCommandAction.runWriteCommandAction(project, "Generate Liquibase Changelog", null, {
            val dir = VfsUtil.createDirectoryIfMissing(projectDir, outputDir)
            val master = dir.findChild(LiquibaseChangelogGenerator.MASTER_CHANGELOG)
                ?: dir.createChildData(this, LiquibaseChangelogGenerator.MASTER_CHANGELOG)
                    .also { VfsUtil.saveText(it, LiquibaseChangelogGenerator.masterSkeleton()) }

            var masterText = VfsUtilCore.loadText(master)
            for (w in writes) {
                val existing = dir.findChild(w.fileName)
                if (existing != null && skipExisting) {
                    masterText = LiquibaseChangelogGenerator.withInclude(masterText, w.fileName)
                    continue
                }
                val file = existing ?: dir.createChildData(this, w.fileName)
                VfsUtil.saveText(file, w.xml)
                written.add(file)
                masterText = LiquibaseChangelogGenerator.withInclude(masterText, w.fileName)
            }
            if (masterText != VfsUtilCore.loadText(master)) VfsUtil.saveText(master, masterText)
        })
        return written
    }

    // ---- report -------------------------------------------------------------------------------

    /** Open the first written file and raise the summary notification (with a settings deep-link). */
    fun reportGenerated(written: List<VirtualFile>, skipped: Int, outputDir: String) {
        if (project.isDisposed) return
        written.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        val skippedNote = if (skipped > 0) " $skipped file(s) already existed and were kept." else ""
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Generated ${written.size} Liquibase changelog(s)",
                "Written under $outputDir and registered in ${LiquibaseChangelogGenerator.MASTER_CHANGELOG} " +
                    "(Flowable runs it automatically).$skippedNote No liquibase.properties or build changes were made.",
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimple("Open Generation settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GenerationConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    private fun notify(title: String, message: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, type)
            .notify(project)
    }

    private fun notifyLater(title: String, message: String, type: NotificationType) =
        ApplicationManager.getApplication().invokeLater { notify(title, message, type) }

    companion object {
        private const val GROUP_ID = "Flowable Atlas"

        fun getInstance(project: Project): LiquibaseScaffoldService = project.service()
    }
}
