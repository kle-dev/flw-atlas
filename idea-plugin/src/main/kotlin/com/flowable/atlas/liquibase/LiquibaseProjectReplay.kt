package com.flowable.atlas.liquibase

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * The project's own Liquibase changelogs replayed together ([LiquibaseReplay], the reader the explorer
 * uses), for the one question an open changelog cannot answer alone: does a column it declares survive?
 * A project that keeps its history in `v2/`, `v3/`, `v4/` creates a column in one file and drops or
 * renames it in a later one; read file by file, the coverage inspection flagged the column where it was
 * declared, although the table no longer has it.
 *
 * The same replay answers Generate → Liquibase's question: which tables do the project's own changelogs
 * already create, so a changelog written for one would fail on a table that exists?
 *
 * Cached until PSI changes, and empty while indexing — a highlighting pass must not wait for it.
 * Call inside a read action.
 */
object LiquibaseProjectReplay {

    /** What one replay of the project's application changelogs tells. */
    private class Replayed(val removed: Set<String>, val created: Map<String, List<String>>)

    /** Upper-cased `TABLE.COLUMN` of every column no longer in its table once all changelogs ran, and
     *  `TABLE.` for a table a changelog drops. */
    fun removed(project: Project): Set<String> = replayed(project)?.removed ?: emptySet()

    /**
     * Upper-cased name → the paths of the changelogs whose `createTable` made it, in the order they ran,
     * for every table the project's application changelogs leave standing once all ran. A table they only
     * alter is not among them. Several paths when more than one creates it — a caller that discounts one
     * file (Atlas's own earlier output) still sees the others. Empty while indexing.
     */
    fun createdTables(project: Project): Map<String, List<String>> = replayed(project)?.created ?: emptyMap()

    private fun replayed(project: Project): Replayed? {
        if (DumbService.isDumb(project)) return null
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(compute(project), PsiModificationTracker.getInstance(project))
        }
    }

    private fun compute(project: Project): Replayed {
        val scope = GlobalSearchScope.projectScope(project)
        val files = ArrayList<LiquibaseReplay.File>()
        val docs = FileDocumentManager.getInstance()
        for (vf in FilenameIndex.getAllFilesByExt(project, "xml", scope) + FilenameIndex.getAllFilesByExt(project, "sql", scope)) {
            // a changelog lives under a resources root; a pom, a Spring context or an IDE file does not
            if ("/resources/" !in vf.path) continue
            val text = try {
                docs.getCachedDocument(vf)?.text ?: String(vf.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (!LiquibaseParser.isChangelog(text)) continue
            val changelog = try { LiquibaseParser.parse(text, vf.path) } catch (e: LiquibaseParser.ParseException) { null } ?: continue
            files.add(LiquibaseReplay.File(vf.path, changelog))
        }
        if (files.isEmpty()) return Replayed(emptySet(), emptyMap())
        val out = HashSet<String>()
        val created = LinkedHashMap<String, MutableList<String>>()
        for (run in LiquibaseReplay.replay(files).runs.filter { it.kind == "application" }) {
            for ((k, t) in run.schema.tables) for (c in t.creators) {
                val files = created.getOrPut(k) { ArrayList() }
                if (c.file !in files) files.add(c.file)
            }
            for ((k, t) in run.schema.tables) for (r in t.removed) out.add("$k.${r.name.uppercase()}")
            for (k in run.schema.droppedTables.keys) out.add("$k.")
            // a renamed table: the columns declared under its old name are the new table's
            for ((old, new) in run.schema.alias) run.schema.tables[run.schema.resolve(new)]?.let { t ->
                for (r in t.removed) out.add("$old.${r.name.uppercase()}")
            }
        }
        return Replayed(out, created)
    }

    /** Whether the project's changelogs, all run, leave [column] out of [table]. */
    fun isRemoved(removed: Set<String>, table: String?, column: String): Boolean {
        val t = table?.uppercase() ?: return false
        return "$t." in removed || "$t.${column.uppercase()}" in removed
    }
}
