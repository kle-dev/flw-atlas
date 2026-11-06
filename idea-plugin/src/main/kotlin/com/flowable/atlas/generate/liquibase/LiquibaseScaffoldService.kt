package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.liquibase.LiquibaseProjectReplay
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ServiceTable
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.flowable.atlas.settings.GenerationLiquibaseConfigurable
import com.intellij.notification.NotificationAction
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
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Backs the "Generate → Liquibase" dialog ([GenerateLiquibaseDialog]). Splits into three concerns:
 *
 *  - **plan** ([preparePlans], [computePlans]) — a read-only pass over the model index and the app-export
 *    zips that resolves, for every data object and every bundled changelog, what would be written and
 *    *how*: extracted verbatim from the bundling app; else built from the backing service's column
 *    mappings, as Flowable Design builds it; else guessed from the data object's fields — or nothing,
 *    when the project's own changelogs already create the table. No file is touched; the dialog renders
 *    this as a preview. The index build and the archive reads happen before the read action, never in it.
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

        /** No bundled changelog matched; the table is built from the [columnCount] column mappings of the
         *  backing service [serviceKey] — the names the service reads and writes — as Design builds it. */
        data class FromService(val serviceKey: String, val columnCount: Int) : Origin {
            override val label get() = "Built from $serviceKey ($columnCount column${if (columnCount == 1) "" else "s"})"
        }

        /** No database service behind the data object: the columns are its [fieldCount] field names — a guess. */
        data class Synthesized(val fieldCount: Int) : Origin {
            override val label get() = "No database service — column names guessed ($fieldCount field${if (fieldCount == 1) "" else "s"})"
        }

        /**
         * The project's own changelog [file] already creates the table. The master changelog Flowable runs
         * at startup and the project's own Liquibase share one database, so a second `createTable` fails
         * whichever runs second: nothing is offered to write.
         */
        data class AlreadyCreated(val file: String) : Origin {
            override val label get() = "Already created by $file"
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
    ) {
        /** False when there is nothing to write — the table exists already. */
        val generatable: Boolean get() = origin !is Origin.AlreadyCreated
    }

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
                    preparePlans(base)
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
     * [computePlans] from a background task: the slow parts first, each outside the read lock — the model
     * index (cold after every model save, exactly when this is used), then the walk over the archives —
     * and only the lookups inside it. Under one read action the whole index scan, the project walk and
     * the archive decompression ran with the lock held, and every keystroke waited for them.
     */
    internal fun preparePlans(base: VirtualFile): Plans {
        // Build the index (when cold) *before* taking the read action: the scan manages its own short read
        // actions on purpose, and inside one it would run inline with the lock held throughout.
        project.service<FlowableModelIndexService>().index()
        val candidates = bundledChangelogs(base)
        val created = ReadAction.computeBlocking<Map<String, String>, RuntimeException> { projectTables(base) }
        return ReadAction.computeBlocking<Plans, RuntimeException> { computePlans(base, candidates, created) }
    }

    /**
     * Resolve every data object (extract-if-bundled, else build from its service, else guess from its
     * fields; nothing when [created] already holds its table) and every bundled changelog under [base]
     * into a [Plans] preview. Read access required — call inside a read action; [preparePlans] gathers
     * [candidates] and [created] before taking it.
     */
    fun computePlans(
        base: VirtualFile,
        candidates: List<LiquibaseChangelogExtractor.Extracted> = bundledChangelogs(base),
        created: Map<String, String> = projectTables(base),
    ): Plans {
        val index = project.service<FlowableModelIndexService>()

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
            val creator = modelTable?.takeIf { it.isNotBlank() }?.let { created[it.uppercase()] }
            val fromService = if (bundled == null && creator == null) service?.let(LiquibaseChangelogGenerator::fromService) else null
            val (origin, xml) = when {
                bundled != null -> Origin.Extracted(bundled.fileName) to bundled.xml
                creator != null -> Origin.AlreadyCreated(creator) to ""
                // The service's columns, not the data object's fields: a field is the logical name and its
                // column is named by the service — in practice they almost never agree — and a table built
                // from field names had none of the columns the service reads, and no primary key.
                fromService != null ->
                    Origin.FromService(service!!.key, LiquibaseChangelogGenerator.tableColumns(service).size) to fromService
                else -> Origin.Synthesized(info.fieldMappings.size) to
                    LiquibaseChangelogGenerator.synthesize(entry.key, modelTable.orEmpty(), info.fieldMappings)
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
     * line up (→ built from the service, or guessed from the fields).
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
     * The tables the project's own changelogs under [base] create (upper-cased name → the first creating
     * file, relative to [base]) — the same replay the coverage inspection reads. A file in the configured
     * output folder does not count: what Atlas generated there before is what this run may overwrite. Only
     * that file is discounted, not the table — when the project's own changelog creates it too, that one is
     * what a second `createTable` would collide with. Read access required.
     */
    private fun projectTables(base: VirtualFile): Map<String, String> {
        val root = base.path.trimEnd('/') + "/"
        val output = outputDir(base)?.path?.trimEnd('/')?.plus("/")
        val out = HashMap<String, String>()
        for ((table, files) in LiquibaseProjectReplay.createdTables(project)) {
            files.firstOrNull { it.startsWith(root) && (output == null || !it.startsWith(output)) }
                ?.let { out[table] = it.removePrefix(root) }
        }
        return out
    }

    private fun outputDir(base: VirtualFile): VirtualFile? =
        base.findFileByRelativePath(FlowableAtlasProjectSettings.getInstance(project).liquibaseOutputDir)

    /**
     * Every bundled changelog under [base] — extracted from each `.zip`/`.bar` app export and read from
     * any loose `*.data.changelog.xml` of an unpacked export. Build-output directories and the configured
     * Liquibase output folder are skipped, so the master and already-generated changelogs are never
     * re-surfaced as app exports.
     *
     * The files are listed under a short read action and read outside it: decompressing every archive with
     * the lock held made each write wait, and a jar file system that has not mounted an archive yet may
     * only be refreshed without it.
     */
    private fun bundledChangelogs(base: VirtualFile): List<LiquibaseChangelogExtractor.Extracted> {
        val files = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            val outputDir = outputDir(base)
            val found = ArrayList<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(
                base,
                { vf -> !(vf.isDirectory && (vf.name in ModelPaths.EXCLUDE_DIRS || vf == outputDir)) },
                { file ->
                    if (LiquibaseChangelogExtractor.isArchive(file) || LiquibaseChangelogExtractor.isLooseChangelog(file)) found.add(file)
                    true
                },
            )
            found
        }
        val out = ArrayList<LiquibaseChangelogExtractor.Extracted>()
        for (file in files) {
            ProgressManager.checkCanceled()
            if (!file.isValid) continue
            onBundledFile?.invoke(file)
            if (LiquibaseChangelogExtractor.isArchive(file)) out.addAll(LiquibaseChangelogExtractor.extract(file))
            else LiquibaseChangelogExtractor.readLoose(file)?.let { out.add(it) }
        }
        return out
    }

    /** Called per archive or loose changelog just before it is read — where a test asks which lock that holds. */
    @Volatile
    internal var onBundledFile: ((VirtualFile) -> Unit)? = null

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
        val notification = AtlasNotifications.results()
            .createNotification(
                "Generated ${written.size} Liquibase changelog(s)",
                "Written under $outputDir and registered in ${LiquibaseChangelogGenerator.MASTER_CHANGELOG} " +
                    "(Flowable runs it automatically).$skippedNote No liquibase.properties or build changes were made.",
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimple("Open Liquibase Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GenerationLiquibaseConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    private fun notify(title: String, message: String, type: NotificationType) {
        if (project.isDisposed) return
        AtlasNotifications.groupFor(type)
            .createNotification(title, message, type)
            .notify(project)
    }

    private fun notifyLater(title: String, message: String, type: NotificationType) =
        ApplicationManager.getApplication().invokeLater { notify(title, message, type) }

    companion object {

        fun getInstance(project: Project): LiquibaseScaffoldService = project.service()
    }
}
