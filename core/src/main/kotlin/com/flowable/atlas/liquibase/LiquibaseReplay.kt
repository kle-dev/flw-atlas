package com.flowable.atlas.liquibase

import com.flowable.atlas.model.ModelPaths

/**
 * Runs changelogs the way Liquibase runs them, and says what every table holds once they have run: which
 * columns, with which type, added by which change set, and which columns a later change set dropped or
 * renamed.
 *
 * There are three kinds of run, because a project's changelogs reach the database three ways:
 *
 * - **The application's own changelogs** — loose files that are not a `.data.changelog.xml` — are one run
 *   into one database: what the application's Liquibase applies at startup. A master runs its `<include>`s
 *   and `<includeAll>`s where they stand; an `includeAll` takes its directory recursively in plain string
 *   order of the path, which is Liquibase's order (`v10/` before `v2/`), not a natural one. A file no
 *   master includes runs after the masters, in natural path order. Change sets are identified as Liquibase
 *   identifies them — id, author and file path, where the file path is the change set's `logicalFilePath`,
 *   else its changelog's, else the path the file was included by — and one that already ran does not run
 *   again, so the same changelog kept in `v2/`, `v3/` and `v4/` under one `logicalFilePath` builds its
 *   table once. Those under a test source set run on their own: a test's database is not the application's.
 * - **Each Flowable schema definition** — a `.data.changelog.xml`, loose or inside an app archive — is a
 *   run of its own. The data-object engine applies a definition against a changelog table of its own, keyed
 *   by the definition, so its change sets never meet the application's. Every copy of one definition (the
 *   same model exported in several app versions) runs into the same history, in natural path order: the
 *   table is what applying every revision leaves.
 *
 * Preconditions are evaluated against the tables built so far. `tableExists`, `columnExists`,
 * `changeSetExecuted` and their `not`/`and`/`or` are answered; a check that needs a live database
 * (`sqlCheck`, `dbms`, …) neither passes nor fails, so the change set runs. A change set whose
 * precondition fails is skipped unless `onFail` is `WARN`; `MARK_RAN` also records it as run.
 * Contexts and labels do not filter: Liquibase run without a context runs every change set.
 */
object LiquibaseReplay {

    /** A changelog file. [rel] is a project-relative path, or an archive entry's `app.zip!entry` label. */
    class File(val rel: String, val changelog: LbChangelog, internal val raw: Boolean = false) {
        /** A Flowable data-object schema definition: shipped inside an app, or named `*.data.changelog.xml`. */
        val isDefinition: Boolean = !raw && ('!' in rel || rel.lowercase().endsWith(".data.changelog.xml"))
        /** Under a test source set: replayed apart from the application's database. */
        val isTest: Boolean = !isDefinition && ModelPaths.isTestSource(rel)
        /** The path Liquibase knows the file by: below the resources root, or the archive entry. */
        val classpath: String = classpathOf(rel)
        /** What precedes [classpath] in [rel] — the module, or the archive. */
        val module: String = rel.removeSuffix(classpath)
        val baseName: String = rel.substringAfterLast('!').substringAfterLast('/')
    }

    /** A change set of a file — where a column came from, or what removed it. */
    data class Ref(val file: String, val changeSet: String, val line: Int)

    class Column(var name: String, var type: String?, var table: String, val from: Ref) {
        /** Part of the table's primary key: declared so, or named by a later `addPrimaryKey`. A rename keeps it. */
        var pk: Boolean = false
    }

    /** A column no longer in its table: dropped, or renamed to [renamedTo]. */
    class Removed(val name: String, val type: String?, val table: String, val from: Ref?, val by: Ref, val renamedTo: String?)

    class Table(var name: String, val createdBy: Ref) {
        /** Upper-cased name → column. Not loosely: `CONTROL_TYPE` and `CONTROL_TYPE_` are two columns. */
        val columns = LinkedHashMap<String, Column>()
        val removed = ArrayList<Removed>()
    }

    class Schema {
        val tables = LinkedHashMap<String, Table>()
        /** An upper-cased old table name → the name a `renameTable` gave it. */
        val alias = LinkedHashMap<String, String>()
        val droppedTables = LinkedHashMap<String, Ref>()

        fun resolve(name: String): String {
            var t = name.uppercase()
            val seen = HashSet<String>()
            while (t in alias && seen.add(t)) t = alias.getValue(t)
            return t
        }

        fun table(name: String): Table? = tables[resolve(name)]
    }

    /** What happened to one change set: `ran`, `skipped` (a precondition), or `duplicate` (it ran before). */
    class Outcome(val file: File, val changeSet: LbChangeSet, val status: String, val note: String?, val changes: List<String>)

    data class Identity(val id: String, val author: String, val path: String)

    class Run(
        /** `application`, `test` or `definition`. */
        val kind: String,
        /** The definition key, for a definition's run. */
        val key: String?,
    ) {
        val schema = Schema()
        val files = ArrayList<File>()
        val outcomes = LinkedHashMap<File, MutableList<Outcome>>()
        internal val ran = LinkedHashMap<Identity, Ref>()
        internal val props = LinkedHashMap<String, String>()
    }

    class Result(
        val runs: List<Run>,
        /** The run a file ran in; a file nothing ran (a plain `.sql` no changelog includes) has none. */
        val runOf: Map<File, Run>,
        /** `<include>`s and `<includeAll>`s that name no file of the project. */
        val unresolved: List<Pair<File, String>>,
    )

    /**
     * Replays [changelogs]; [plainSql] (path → text) are `.sql` files that are not formatted changelogs,
     * which an `<include>`, an `<includeAll>` or a `<sqlFile>` can still run.
     */
    fun replay(changelogs: List<File>, plainSql: Map<String, String> = emptyMap()): Result {
        val raw = plainSql.map { (rel, text) ->
            val changes = SqlDdl.parse(text)
            // Liquibase runs an included plain `.sql` as one change set with this id and author
            val cs = LbChangeSet("raw", "includeAll", null, 1, null, changes,
                changes.flatMap(LiquibaseParser::tablesOf).distinct(), text.replace(Regex("\\s+"), " ").trim())
            File(rel, LbChangelog(null, emptyMap(), listOf(LbEntry.ChangeSet(cs)), emptyList()), raw = true)
        }
        val index = Index(changelogs + raw, plainSql)
        // The tables some changelog of the project creates. `tableExists` of one of them, before it is
        // created, is false; of any other — an engine table like `ACT_RU_VARIABLE`, another application's —
        // it is unknown: the replay starts from an empty database, the real one never does.
        val known = HashSet<String>()
        for (f in changelogs + raw) for (cs in f.changelog.changeSets) for (c in cs.changes) {
            val t = when (c) { is LbChange.CreateTable -> c.table; is LbChange.RenameTable -> c.newName; else -> null } ?: continue
            known.add(expand(t, f.changelog.properties).uppercase())
        }
        val runOf = java.util.IdentityHashMap<File, Run>()
        val unresolved = ArrayList<Pair<File, String>>()
        val runs = ArrayList<Run>()

        for ((kind, members) in listOf(
            "application" to changelogs.filter { !it.isDefinition && !it.isTest },
            "test" to changelogs.filter { it.isTest },
        )) {
            if (members.isEmpty()) continue
            val reached = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<File, Boolean>())
            for (f in members) for (e in f.changelog.entries) reached.addAll(index.targets(f, e))
            val roots = members.filter { it !in reached }
            val run = Run(kind, null)
            val exec = Exec(run, index, known, unresolved) { it.classpath }
            for (f in roots.filter { it.changelog.includes }.sortedBy { it.classpath }) exec.file(f)
            for (f in roots.filter { !it.changelog.includes }.sortedWith(NATURAL)) exec.file(f)
            // files that include each other in a circle have no root; they still run
            for (f in members.sortedWith(NATURAL)) exec.file(f)
            runs.add(run)
        }

        val definitions = LinkedHashMap<String, MutableList<File>>()
        for (f in changelogs) if (f.isDefinition) definitions.getOrPut(LiquibaseChangelog.changelogKey(f.rel)) { ArrayList() }.add(f)
        for ((key, copies) in definitions) {
            val run = Run("definition", key)
            // the resource name inside a deployment: the same across every copy of the definition
            val exec = Exec(run, index, known, unresolved) { it.baseName }
            for (f in copies.sortedWith(NATURAL)) exec.file(f)
            runs.add(run)
        }

        for (r in runs) for (f in r.files) runOf.putIfAbsent(f, r)
        return Result(runs, runOf, unresolved)
    }

    // ---- one run --------------------------------------------------------------------------------------

    private class Exec(
        val run: Run,
        val index: Index,
        val known: Set<String>,
        val unresolved: MutableList<Pair<File, String>>,
        val pathOf: (File) -> String,
    ) {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<File, Boolean>())

        fun file(f: File) {
            // Liquibase would read a second include of the file again, and find every change set ran
            if (!visited.add(f)) return
            run.files.add(f)
            // global, and the first definition of a name wins — as Liquibase's changelog parameters
            for ((k, v) in f.changelog.properties) run.props.putIfAbsent(k, v)
            val outcomes = run.outcomes.getOrPut(f) { ArrayList() }
            for (e in f.changelog.entries) when (e) {
                is LbEntry.ChangeSet -> outcomes.add(changeSet(f, e.changeSet))
                is LbEntry.Include -> {
                    val child = index.include(f, e.file, e.relative)
                    if (child == null) unresolved.add(f to e.file) else file(child)
                }
                is LbEntry.IncludeAll -> {
                    val kids = index.includeAll(f, e.path, e.relative)
                    if (kids.isEmpty()) unresolved.add(f to e.path)
                    kids.forEach(::file)
                }
            }
        }

        private fun sub(s: String): String = expand(s, run.props)

        private fun changeSet(f: File, cs: LbChangeSet): Outcome {
            val ident = Identity(cs.id, cs.author, cs.logicalFilePath ?: f.changelog.logicalFilePath ?: pathOf(f))
            val ref = Ref(f.rel, cs.id, cs.line)
            val summary = cs.changes.map(::describe)
            run.ran[ident]?.let { first ->
                return Outcome(f, cs, "duplicate", "already ran from ${first.file} (change set ${first.changeSet})", summary)
            }
            val pre = cs.preconditions
            if (pre != null && all(pre.all) == false && pre.onFail != "WARN") {
                if (pre.onFail == "MARK_RAN") run.ran[ident] = ref
                val failed = pre.all.filter { eval(it) == false }.joinToString(", ", transform = ::describe)
                return Outcome(f, cs, "skipped", "precondition $failed fails (onFail ${pre.onFail})", summary)
            }
            val notes = ArrayList<String>()
            for (c in cs.changes) apply(f, c, ref, notes)
            run.ran[ident] = ref
            return Outcome(f, cs, "ran", notes.distinct().joinToString("; ").ifEmpty { null }, summary)
        }

        // ---- preconditions ----

        private fun all(ps: List<LbPrecondition>): Boolean? {
            var unknown = false
            for (p in ps) when (eval(p)) { false -> return false; null -> unknown = true; true -> {} }
            return if (unknown) null else true
        }

        private fun eval(p: LbPrecondition): Boolean? = when (p) {
            is LbPrecondition.TableExists -> exists(sub(p.table))
            is LbPrecondition.ColumnExists -> run.schema.table(sub(p.table))?.columns?.containsKey(sub(p.column).uppercase())
                ?: exists(sub(p.table))
            is LbPrecondition.ChangeSetExecuted -> run.ran.keys.any {
                it.id == p.id && it.author == p.author && (p.file == null || it.path == p.file || it.path.endsWith("/" + p.file))
            }
            is LbPrecondition.Not -> all(p.all)?.not()
            is LbPrecondition.And -> all(p.all)
            is LbPrecondition.Or -> {
                val rs = p.any.map(::eval)
                if (rs.any { it == true } || rs.isEmpty()) true else if (rs.all { it == false }) false else null
            }
            is LbPrecondition.Unknown -> null
        }

        /** True or false for a table the project's changelogs create; unknown for any other. */
        private fun exists(table: String): Boolean? = when {
            run.schema.table(table) != null -> true
            run.schema.resolve(table) in known || table.uppercase() in known -> false
            else -> null
        }

        private fun describe(p: LbPrecondition): String = when (p) {
            is LbPrecondition.TableExists -> "tableExists ${sub(p.table)}"
            is LbPrecondition.ColumnExists -> "columnExists ${sub(p.table)}.${sub(p.column)}"
            is LbPrecondition.ChangeSetExecuted -> "changeSetExecuted ${p.id}"
            is LbPrecondition.Not -> "not " + p.all.joinToString(" and ", transform = ::describe)
            is LbPrecondition.And -> p.all.joinToString(" and ", "(", ")", transform = ::describe)
            is LbPrecondition.Or -> p.any.joinToString(" or ", "(", ")", transform = ::describe)
            is LbPrecondition.Unknown -> p.kind
        }

        // ---- changes ----

        private fun describe(c: LbChange): String = when (c) {
            is LbChange.CreateTable -> "createTable ${sub(c.table)} (${c.columns.size} column${if (c.columns.size == 1) "" else "s"})"
            is LbChange.AddColumn -> "addColumn ${sub(c.table)}: " + c.columns.joinToString(", ") { sub(it.name) }
            is LbChange.DropColumn -> "dropColumn ${sub(c.table)}: " + c.columns.joinToString(", ", transform = ::sub)
            is LbChange.RenameColumn -> "renameColumn ${sub(c.table)}: ${sub(c.oldName)} → ${sub(c.newName)}"
            is LbChange.ModifyDataType -> "modifyDataType ${sub(c.table)}.${sub(c.column)}: ${c.type?.let(::sub) ?: "?"}"
            is LbChange.RenameTable -> "renameTable ${sub(c.oldName)} → ${sub(c.newName)}"
            is LbChange.DropTable -> "dropTable ${sub(c.table)}"
            is LbChange.PrimaryKey -> "addPrimaryKey ${sub(c.table)}: " + c.columns.joinToString(", ", transform = ::sub)
            is LbChange.DropPrimaryKey -> "dropPrimaryKey ${sub(c.table)}"
            is LbChange.SqlFile -> "sqlFile ${c.path}"
            is LbChange.Unread -> c.what
        }

        private fun ensure(name: String, ref: Ref): Table {
            run.schema.table(name)?.let { return it }
            val key = run.schema.resolve(name)
            run.schema.droppedTables.remove(key)
            return Table(name, ref).also { run.schema.tables[key] = it }
        }

        private fun add(t: Table, cols: List<LbColumn>, ref: Ref) {
            for (c in cols) {
                val name = sub(c.name)
                val k = name.uppercase()
                val type = c.type?.let(::sub)
                val existing = t.columns[k]
                if (existing != null) {
                    if (type != null) existing.type = type
                    if (c.pk) existing.pk = true
                    continue
                }
                t.columns[k] = Column(name, type, t.name, ref).also { it.pk = c.pk }
                // back in the table: no longer one it lost
                t.removed.removeAll { it.name.uppercase() == k }
            }
        }

        private fun apply(f: File, c: LbChange, ref: Ref, notes: MutableList<String>) {
            val schema = run.schema
            when (c) {
                is LbChange.CreateTable -> {
                    val name = sub(c.table)
                    if (schema.table(name) != null) notes.add("table $name already existed")
                    add(ensure(name, ref), c.columns, ref)
                }
                is LbChange.AddColumn -> add(ensure(sub(c.table), ref), c.columns, ref)
                is LbChange.DropColumn -> {
                    val t = schema.table(sub(c.table)) ?: return
                    for (n in c.columns) {
                        val col = t.columns.remove(sub(n).uppercase()) ?: continue
                        t.removed.add(Removed(col.name, col.type, t.name, col.from, ref, null))
                    }
                }
                is LbChange.RenameColumn -> {
                    val t = ensure(sub(c.table), ref)
                    val old = sub(c.oldName); val new = sub(c.newName)
                    val col = t.columns.remove(old.uppercase())
                    val type = c.type?.let(::sub)
                    if (col != null) {
                        t.removed.add(Removed(col.name, col.type, t.name, col.from, ref, new))
                        col.name = new
                        if (type != null) col.type = type
                        t.columns[new.uppercase()] = col
                    } else {
                        t.columns[new.uppercase()] = Column(new, type, t.name, ref)
                    }
                    t.removed.removeAll { it.name.uppercase() == new.uppercase() }
                }
                is LbChange.ModifyDataType -> {
                    val col = schema.table(sub(c.table))?.columns?.get(sub(c.column).uppercase()) ?: return
                    c.type?.let { col.type = sub(it) }
                }
                is LbChange.RenameTable -> {
                    val old = schema.resolve(sub(c.oldName)); val newName = sub(c.newName); val new = newName.uppercase()
                    val t = schema.tables.remove(old)
                    if (t != null) {
                        t.name = newName
                        t.columns.values.forEach { it.table = newName }
                        val into = schema.tables[new]
                        if (into == null) schema.tables[new] = t else { t.columns.forEach { (k, v) -> into.columns.putIfAbsent(k, v) } }
                    }
                    if (old != new) schema.alias[old] = new
                }
                is LbChange.DropTable -> {
                    val key = schema.resolve(sub(c.table))
                    if (schema.tables.remove(key) != null) schema.droppedTables[key] = ref
                }
                is LbChange.PrimaryKey -> {
                    // a table has one primary key: the one named here replaces whatever was declared before
                    val t = schema.table(sub(c.table)) ?: return
                    val key = c.columns.map { sub(it).uppercase() }.toSet()
                    t.columns.forEach { (k, col) -> col.pk = k in key }
                }
                is LbChange.DropPrimaryKey -> schema.table(sub(c.table))?.columns?.values?.forEach { it.pk = false }
                is LbChange.SqlFile -> {
                    val text = index.sqlFile(f, c.path, c.relative)
                    if (text == null) notes.add("sqlFile ${c.path} is not in the project") else SqlDdl.parse(text).forEach { apply(f, it, ref, notes) }
                }
                is LbChange.Unread -> notes.add("${c.what} is not read")
            }
        }
    }

    // ---- paths ----------------------------------------------------------------------------------------

    private class Index(files: List<File>, val plainSql: Map<String, String>) {
        val byRel = files.associateBy { it.rel }
        val byClasspath: Map<String, List<File>> = files.groupBy { it.classpath }

        fun targets(from: File, e: LbEntry): List<File> = when (e) {
            is LbEntry.Include -> listOfNotNull(include(from, e.file, e.relative))
            is LbEntry.IncludeAll -> includeAll(from, e.path, e.relative)
            is LbEntry.ChangeSet -> emptyList()
        }

        private fun target(from: File, path: String, relative: Boolean): String =
            normalize(if (relative) from.classpath.substringBeforeLast('/', "") + "/" + clean(path) else clean(path))

        fun include(from: File, path: String, relative: Boolean): File? {
            val t = target(from, path, relative)
            val cands = byClasspath[t]
                ?: byRel.values.filter { ("/" + it.rel).endsWith("/$t") || ("/" + it.classpath).endsWith("/$t") }
            return pick(from, cands.filter { it !== from })
        }

        fun includeAll(from: File, path: String, relative: Boolean): List<File> {
            val dir = target(from, path, relative).trimEnd('/')
            var cands = byRel.values.filter { it !== from && (dir.isEmpty() || it.classpath.startsWith("$dir/")) }
            if (cands.isEmpty() && dir.isNotEmpty()) cands = byRel.values.filter { it !== from && ("/" + it.rel).contains("/$dir/") }
            // Liquibase collects them in a set keyed by the path: two modules' copies of one path are one file
            return cands.groupBy { it.classpath }.toSortedMap().values.mapNotNull { pick(from, it) }
        }

        fun sqlFile(from: File, path: String, relative: Boolean): String? {
            val t = target(from, path, relative)
            val rel = plainSql.keys.filter { classpathOf(it) == t }.ifEmpty { plainSql.keys.filter { ("/" + it).endsWith("/$t") } }
                .let { rs -> rs.firstOrNull { classpathOf(it).let { cp -> it.removeSuffix(cp) } == from.module } ?: rs.minOrNull() }
            return rel?.let { plainSql[it] }
        }

        /** The copy in the including file's own module or archive, else the first by path. */
        private fun pick(from: File, cands: List<File>): File? =
            cands.firstOrNull { it.module == from.module } ?: cands.minByOrNull { it.rel }
    }

    /** The path below a resources root, or an archive entry's own path: what a classpath include names. */
    fun classpathOf(rel: String): String {
        if ('!' in rel) return rel.substringAfterLast('!').trimStart('/')
        val p = "/$rel"
        for (m in listOf("/src/main/resources/", "/src/test/resources/", "/resources/")) {
            val i = p.lastIndexOf(m)
            if (i >= 0) return p.substring(i + m.length)
        }
        return rel
    }

    private fun clean(p: String): String =
        p.replace('\\', '/').trim().replace(Regex("^classpath\\*?:"), "").trimStart('/')

    private fun normalize(p: String): String {
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) when (seg) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(seg)
        }
        return out.joinToString("/")
    }

    private val PROPERTY = Regex("""\$\{([^}]+)}""")

    /** `${name}` → the property's value; one the changelogs never set stays as written. */
    fun expand(s: String, props: Map<String, String>): String =
        if ('$' !in s) s else PROPERTY.replace(s) { m -> props[m.groupValues[1]]?.let(Regex::escapeReplacement) ?: Regex.escapeReplacement(m.value) }

    // ---- natural order (v2 < v10) — for files no Liquibase run orders ------------------------------

    private val DIGITS = Regex("\\d+")

    private fun chunks(s: String): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in DIGITS.findAll(s)) {
            out.add(s.substring(last, m.range.first)); out.add(m.value); last = m.range.last + 1
        }
        out.add(s.substring(last))
        return out
    }

    fun compareNatural(a: String, b: String): Int {
        val pa = chunks(a); val pb = chunks(b)
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val xd = x.isNotEmpty() && x.all { it.isDigit() }
            val yd = y.isNotEmpty() && y.all { it.isDigit() }
            val c = if (xd && yd) x.toBigInteger().compareTo(y.toBigInteger()) else x.lowercase().compareTo(y.lowercase())
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private val NATURAL = Comparator<File> { a, b -> compareNatural(a.rel, b.rel) }
}
