package com.flowable.atlas.graph

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.liquibase.LiquibaseParser
import com.flowable.atlas.liquibase.LiquibaseReplay
import com.flowable.atlas.model.Dyn
import java.io.File

/**
 * Liquibase schema coverage: what each changelog builds, which changelog describes a service's table,
 * and how far each of that table's columns is mapped through to a data object.
 *
 * [apply] is the single entry point: given the assembled [Atlas.extract] `result` (already carrying its
 * parsed `services` / `dataObjects`) and every `.xml`/`.sql` text the project holds, it (1) replays the
 * changelogs ([LiquibaseReplay]) and builds `result["liquibase"]` — one entry per changelog, with the
 * columns its tables hold once everything has run, where each came from, what was dropped, and what
 * happened to each change set — (2) denormalizes each data object's backing-service table onto it
 * (`serviceTableName` / `serviceType`), (3) attaches `schemaCoverage` to each service, and (4) records
 * each changelog's `authority`.
 */
object LiquibaseCoverage {

    /**
     * Run the whole Liquibase flow, mutating [result] in place. [sources] are `(path, text)` pairs — a
     * loose file's project-relative path, or an archive entry's `archive.zip!entry` label. A Design export
     * ships its changelogs inside the app zip, and reading only files on disk left every one of them
     * invisible: the app's reference to its own changelog came back as a *missing model*.
     */
    fun apply(result: MutableMap<String, Any?>, sources: List<Pair<String, String>>) {
        val book = buildLiquibase(result, sources)
        enrichDataObjects(result)
        val bound = schemaCoverage(result, book)
        markLiquibaseAuthority(result, book, bound)
    }

    /** The files-on-disk form of [apply]: [xmlFiles] relative to [root]. */
    fun apply(result: MutableMap<String, Any?>, xmlFiles: List<File>, root: File) = apply(
        result,
        xmlFiles.mapNotNull { f ->
            if (f.length() > Atlas.MAX_MODEL_BYTES) return@mapNotNull null
            val txt = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return@mapNotNull null }
            (if (root.isDirectory) relpath(root, f) else f.name) to txt
        },
    )

    /** Whether a `.xml`/`.sql` text is a changelog at all — the same test [buildLiquibase] applies. */
    fun isChangelog(txt: String): Boolean = LiquibaseParser.isChangelog(txt)

    /** The model key a changelog file carries: `liquibase-<key>.data.changelog.xml` → `<key>`. Public so the
     *  extractor can index changelogs before references resolve — an app lists its changelogs by this key. */
    fun keyOf(path: String): String = LiquibaseChangelog.changelogKey(path)

    // ---------------------------------------------------------------------------
    // the changelog entries
    // ---------------------------------------------------------------------------

    /** One changelog node: an application changelog, or a schema definition with every copy of it. */
    private class Entry(
        val map: MutableMap<String, Any?>,
        val key: String,
        val file: LiquibaseReplay.File,
        val run: LiquibaseReplay.Run?,
        val definition: Boolean,
        /** The Liquibase identity: its `logicalFilePath`, a definition's key when it declares none. */
        val logical: String?,
        /** Upper-cased tables its change sets shape that exist once everything ran. */
        val effective: Set<String>,
        val hasChangeSets: Boolean,
    ) {
        val serviceRefs: List<String> get() = Dyn.strings(map["serviceRefs"])
    }

    private class Book(val entries: List<Entry>) {
        val byKey: Map<String, Entry> = entries.associateBy { it.key }
    }

    private fun buildLiquibase(result: MutableMap<String, Any?>, sources: List<Pair<String, String>>): Book {
        // error(), not `?: return`: Atlas.extract seeds this bucket, so a missing or wrong-typed one is
        // a programming error. Degrading it to a silent skip would hide a broken result map behind an
        // empty Liquibase section.
        val bucket = Dyn.mutableListOrNull(result["liquibase"])
            ?: error("result[\"liquibase\"] is missing or not a MutableList — Atlas.extract must seed it")
        val files = ArrayList<LiquibaseReplay.File>()
        val plainSql = LinkedHashMap<String, String>()
        for ((rel, txt) in sources) {
            val parsed = try {
                LiquibaseParser.parse(txt, rel)
            } catch (e: LiquibaseParser.ParseException) {
                // Liquibase stops at a changelog it cannot read, so this is the application failing to start
                Dyn.mutableListOrNull(result["diagnostics"])?.add(linkedMapOf("kind" to "parse", "path" to rel, "message" to "(liquibase) ${e.message}"))
                null
            }
            if (parsed != null) files.add(LiquibaseReplay.File(rel, parsed))
            else if (rel.lowercase().endsWith(".sql")) plainSql[rel] = txt
        }
        if (files.isEmpty()) return Book(emptyList())
        val replay = LiquibaseReplay.replay(files, plainSql)

        // A definition is one node however many copies of it the project holds — the app exported into
        // `v2/` and `v3/`, or extracted beside the export. The project's own copy names the node; else the
        // newest copy that adds a change set to the history, else the first.
        val definitions = replay.runs.filter { it.kind == "definition" }
        val defKeys = definitions.mapNotNull { it.key }.toHashSet()
        val singles = files.filter { !it.isDefinition } + replay.runOf.keys.filter { it !in files && !it.isDefinition }
        val keys = applicationKeys(singles, defKeys)

        val entries = ArrayList<Entry>()
        for (f in singles) {
            val run = replay.runOf[f]
            val (key, label) = keys.getValue(f)
            entries.add(entry(key, label, f, listOf(f), run, false, replay))
        }
        for (run in definitions) {
            val copies = run.files.filter { it.isDefinition }
            val rep = copies.firstOrNull { '!' !in it.rel }
                ?: copies.lastOrNull { f -> run.outcomes[f].orEmpty().any { it.status != "duplicate" } } ?: copies.first()
            entries.add(entry(run.key!!, null, rep, copies, run, true, replay))
        }
        for (e in entries) bucket.add(e.map)
        return Book(entries)
    }

    /**
     * An application changelog's key is the one its file name carries, as before; two files of one name —
     * `v2/customer.xml` and `v3/customer.xml` — are told apart by the folders they sit in, and so is a
     * file whose name a schema definition's key already uses: an app lists definitions by key.
     */
    private fun applicationKeys(files: List<LiquibaseReplay.File>, taken: Set<String>): Map<LiquibaseReplay.File, Pair<String, String?>> {
        val out = java.util.IdentityHashMap<LiquibaseReplay.File, Pair<String, String?>>()
        for ((base, fs) in files.groupBy { keyOf(it.rel) }) {
            if (fs.size == 1 && base !in taken) { out[fs[0]] = base to null; continue }
            val dirs = fs.associateWith { it.rel.replace('!', '/').split('/').dropLast(1) }
            val depth = dirs.values.maxOf { it.size }
            var assigned = false
            for (k in 1..depth) {
                val cands = fs.map { f -> (dirs.getValue(f).takeLast(k) + base).joinToString("/") }
                if (cands.toSet().size == fs.size && cands.none { it in taken }) {
                    for ((f, c) in fs.zip(cands)) out[f] = c to (dirs.getValue(f).takeLast(k) + f.baseName).joinToString("/")
                    assigned = true
                    break
                }
            }
            if (!assigned) for (f in fs) out[f] = f.rel to f.rel
        }
        return out
    }

    private fun entry(
        key: String,
        label: String?,
        file: LiquibaseReplay.File,
        copies: List<LiquibaseReplay.File>,
        run: LiquibaseReplay.Run?,
        definition: Boolean,
        replay: LiquibaseReplay.Result,
    ): Entry {
        // the run's properties: global and first-come, as the replay expanded them
        val props = run?.props ?: file.changelog.properties
        fun x(s: String) = LiquibaseReplay.expand(s, props)
        val changeSets = copies.flatMap { it.changelog.changeSets }
        val named = LinkedHashSet<String>()
        val shaped = LinkedHashSet<String>()
        for (cs in changeSets) {
            cs.tables.forEach { named.add(x(it)) }
            for (c in cs.changes) LiquibaseParser.tablesOf(c).forEach { shaped.add(x(it)) }
        }
        val schema = run?.schema
        val effective = LinkedHashMap<String, String>()
        if (schema != null) for (t in shaped) {
            val k = schema.resolve(t)
            schema.tables[k]?.let { effective.putIfAbsent(k, it.name) }
        }
        val columns = ArrayList<Map<String, Any?>>()
        val dropped = ArrayList<Map<String, Any?>>()
        if (schema != null) for (k in effective.keys) {
            val t = schema.tables.getValue(k)
            for (c in t.columns.values) columns.add(linkedMapOf("name" to c.name, "type" to c.type, "table" to c.table, "from" to ref(c.from)))
            for (r in t.removed) dropped.add(linkedMapOf(
                "name" to r.name, "type" to r.type, "table" to r.table, "by" to ref(r.by), "renamedTo" to r.renamedTo,
            ))
        }
        // the tables it shaped that are gone at the end, and the change set that dropped each
        val droppedTables = if (schema == null) emptyList() else shaped.map { schema.resolve(it) }.distinct().mapNotNull { k ->
            schema.droppedTables[k]?.let { linkedMapOf("table" to (shaped.firstOrNull { t -> schema.resolve(t) == k } ?: k), "by" to ref(it)) }
        }
        val outcomes = if (run == null) emptyList() else copies.flatMap { run.outcomes[it] ?: emptyList() }
        val logical = file.changelog.logicalFilePath?.removeSuffix(".data.changelog.xml") ?: if (definition) key else null
        val map = linkedMapOf<String, Any?>(
            "key" to key, "file" to file.rel,
            "tables" to named.toSortedSet().toList(),
            "effectiveTables" to effective.values.toSortedSet().toList(),
            "serviceRefs" to copies.flatMap { it.changelog.serviceRefs }.toSortedSet().toList(),
            "columns" to columns,
        )
        if (label != null) map["label"] = label
        map["origin"] = when {
            definition -> "definition"
            run?.kind == "test" -> "test"
            else -> "application"
        }
        file.changelog.logicalFilePath?.let { map["logicalFilePath"] = it }
        if (dropped.isNotEmpty()) map["dropped"] = dropped
        if (droppedTables.isNotEmpty()) map["droppedTables"] = droppedTables
        if (outcomes.isNotEmpty()) map["changeSets"] = outcomes.map { o ->
            linkedMapOf(
                "id" to o.changeSet.id, "author" to o.changeSet.author, "file" to o.file.rel, "line" to o.changeSet.line,
                "status" to o.status, "note" to o.note, "changes" to o.changes,
            )
        }
        if (copies.size > 1) map["revisions"] = copies.map { it.rel }
        replay.unresolved.filter { (f, _) -> copies.any { it === f } }.map { it.second }.distinct()
            .takeIf { it.isNotEmpty() }?.let { map["includesMissing"] = it }
        return Entry(map, key, file, run, definition, logical, effective.keys, changeSets.isNotEmpty())
    }

    private fun ref(r: LiquibaseReplay.Ref): Map<String, Any?> = linkedMapOf("file" to r.file, "changeSet" to r.changeSet, "line" to r.line)

    // ---------------------------------------------------------------------------
    // _enrich_data_objects
    // ---------------------------------------------------------------------------
    private fun enrichDataObjects(result: MutableMap<String, Any?>) {
        val services = mapList(result["services"])
        val dataObjects = mapList(result["dataObjects"])
        val svc = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (s in services) (s["key"] as? String)?.let { svc[it] = s }
        for (d in dataObjects) {
            val s = svc[d["service"] as? String] ?: continue
            if (truthy(s["tableName"])) d["serviceTableName"] = s["tableName"]
            if (truthy(s["type"])) d["serviceType"] = s["type"]
        }
        // A data object typed by a dictionary type has that type's properties as its fields; its own
        // `fieldMappings` say only what needs saying about one of them — the lookup id, a label. Read
        // from the mappings alone, a service-registry data object with a twelve-property type had one
        // field, and eleven mapped columns were "used by no data object" — 15 of the 16 such findings on
        // two real projects. The type's properties come first, in the type's order; a mapping the type
        // does not know (a relation to another object) keeps its row.
        val typeProps = HashMap<Pair<String, String>, List<Map<String, Any?>>>()
        for (dd in mapList(result["dictionaries"])) {
            val dk = dd["key"] as? String ?: continue
            for (t in mapList(dd["typeDefs"])) {
                val tn = t["name"]?.toString() ?: continue
                typeProps[dk to tn] = mapList(t["properties"])
            }
        }
        for (d in dataObjects) {
            val dk = d["dictionary"] as? String ?: continue
            val tn = d["dictionaryType"] as? String ?: continue
            val props = typeProps[dk to tn]?.takeIf { it.isNotEmpty() } ?: continue
            val own = mapList(d["columns"]).associateBy { it["name"]?.toString() }
            val merged = ArrayList<MutableMap<String, Any?>>()
            for (p in props) {
                val name = p["name"]?.toString() ?: continue
                val col = LinkedHashMap<String, Any?>(own[name] ?: linkedMapOf("name" to name, "label" to null, "type" to null))
                if (col["type"] == null) col["type"] = p["type"]
                merged.add(col)
            }
            for ((n, c) in own) if (n != null && merged.none { it["name"] == n }) merged.add(c)
            d["columns"] = merged
            d["fields"] = merged.map { it["name"] }
            d["fieldsFromDictionary"] = true
        }
    }

    // ---------------------------------------------------------------------------
    // schema coverage
    // ---------------------------------------------------------------------------

    /** A service bound to the changelog its coverage reads. [strong] when that choice says something about
     *  the table: the changelog the service names, or the application's own changelog of its table. */
    private class Binding(val service: String, val entry: Entry, val strong: Boolean)

    /**
     * The changelog a service's table is read from. The application's own changelog of that table comes
     * first — the one the service names, the one naming the service back, any that shapes the table: it is
     * what the application's Liquibase builds at startup. A schema definition in the app is applied on
     * request only, so the table in the database is the application's even when the service model names
     * the definition. Reading the definition first — which a service's `referencedLiquibaseModelKey`
     * always names — reported the columns the project added in its own changelog as "not in Liquibase"
     * and the project's changelog as superseded, on a project that keeps both.
     */
    private fun resolve(s: Map<String, Any?>, book: Book): Binding? {
        val sk = s["key"] as? String ?: return null
        val rk = s["referencedLiquibaseModelKey"]?.toString()?.takeIf { it.isNotEmpty() }
        val table = (s["tableName"] as? String)?.uppercase()?.ifEmpty { null }
        val apps = book.entries.filter { !it.definition && it.run?.kind == "application" }
        val named = rk?.let { book.byKey[it] }
        val byLogical = { k: String? -> k?.let { l -> apps.firstOrNull { it.logical == l && it.hasChangeSets } } }
        val backRef = book.entries.firstOrNull { sk in it.serviceRefs }
        fun builds(e: Entry) = table == null || table in e.effective
        val own = listOfNotNull(byLogical(rk), byLogical(named?.logical), named, backRef).firstOrNull { !it.definition && it.run?.kind == "application" && builds(it) }
            ?: table?.let { t -> apps.filter { t in it.effective }.let { es -> es.firstOrNull { e -> e.run?.schema?.tables?.get(t)?.createdBy?.file == e.file.rel } ?: es.firstOrNull() } }
        if (own != null) return Binding(sk, own, true)
        val lb = named ?: backRef ?: table?.let { t -> book.entries.firstOrNull { t in it.effective } } ?: return null
        return Binding(sk, lb, lb === named)
    }

    private fun schemaCoverage(result: MutableMap<String, Any?>, book: Book): List<Binding> {
        val services = mapList(result["services"])
        val dataObjects = mapList(result["dataObjects"])
        val dosByService = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (d in dataObjects) (d["service"] as? String)?.let { dosByService.getOrPut(it) { ArrayList() }.add(d) }

        // consumed[entry key] = ("service" loose names, "dataObject" loose names)
        val consumed = LinkedHashMap<String, Pair<LinkedHashSet<String>, LinkedHashSet<String>>>()
        val bindings = ArrayList<Binding>()

        for (s in services) {
            val binding = resolve(s, book)
            binding?.let { bindings.add(it) }
            val lb = binding?.entry
            val svcTable = ((s["tableName"] as? String) ?: "").uppercase().ifEmpty { null }
            val schema = lb?.run?.schema
            // Only the service's own table, as the run left it. The changelog may shape several; falling
            // back to all of their columns made every column of every *other* table a "not mapped by the
            // service" gap on this service (CrossedColumns already guards the same way).
            val table = if (schema == null) null else svcTable?.let { schema.table(it) }
            val tables = when {
                schema == null -> emptyList()
                svcTable != null -> listOfNotNull(table)
                else -> lb.effective.mapNotNull { schema.tables[it] }
            }
            val lbCols = tables.flatMap { it.columns.values }
            val removed = tables.flatMap { it.removed }

            val svcByLoose = LinkedHashMap<String, Map<String, Any?>>()
            for (c in mapListRO(s["columns"])) {
                val sql = (c["columnName"] as? String) ?: (c["name"] as? String)
                if (sql != null) svcByLoose.putIfAbsent(loose(sql), c)
            }

            val dos = dosByService[s["key"] as? String] ?: emptyList()
            val doByLoose = LinkedHashMap<String, MutableList<Pair<Any?, Map<String, Any?>>>>()
            for (d in dos) for (f in mapListRO(d["columns"])) {
                val nm = f["name"] as? String
                if (nm != null) doByLoose.getOrPut(loose(nm)) { ArrayList() }.add((d["key"]) to f)
            }

            fun doHitsFor(vararg names: String?): List<Map<String, Any?>> {
                val seen = HashSet<Pair<Any?, Any?>>(); val hits = ArrayList<Map<String, Any?>>()
                for (nm in names) {
                    if (nm.isNullOrEmpty()) continue
                    for ((dk, f) in doByLoose[loose(nm)] ?: emptyList()) {
                        val k = dk to f["name"]
                        if (seen.add(k)) hits.add(linkedMapOf("do" to dk, "field" to f["name"], "type" to f["type"]))
                    }
                }
                return hits
            }

            val rows = ArrayList<Map<String, Any?>>()
            val seenSvc = HashSet<String>()
            for (c in lbCols) {
                val sql = c.name
                val key = loose(sql)
                val svcCol = svcByLoose[key]
                if (svcCol != null) seenSvc.add(key)
                // The service mapping is the authority for which data-object field a column carries: a
                // `.data` field binds to the mapping's `name`, never to the physical column. Matching the
                // column name *as well* put a second, wrong field on the row of a crossed mapping —
                // `FIRST_NAME_` claimed both `userName` (which maps it) and `firstName` (which does not) —
                // which is the one row where the reader most needs the table to be exact. Audited against a
                // real project's 120 mapped columns: the column name never contributed a field the mapping
                // name did not already give. Without a mapping there is no field name, so the column is all
                // there is to go on.
                val hits = if (svcCol != null) doHitsFor(svcCol["name"] as? String) else doHitsFor(sql)
                val status = if (svcCol != null && hits.isNotEmpty()) "ok"
                    else if (svcCol != null) "no-dataobject" else "no-service"
                rows.add(linkedMapOf(
                    "sql" to sql, "table" to c.table, "sqlType" to c.type,
                    "inLiquibase" to true, "inService" to (svcCol != null),
                    "service" to (svcCol?.get("name")), "serviceCol" to (svcCol?.get("columnName")),
                    "serviceType" to (svcCol?.get("type")), "dataObjects" to hits, "status" to status,
                    "from" to ref(c.from),
                ))
            }

            if (lb != null) {
                for (c in mapListRO(s["columns"])) {
                    val sql = (c["columnName"] as? String) ?: (c["name"] as? String) ?: ""
                    if (sql.isEmpty() || loose(sql) in seenSvc) continue
                    val row = linkedMapOf<String, Any?>(
                        "sql" to sql, "table" to null, "sqlType" to null,
                        "inLiquibase" to false, "inService" to true,
                        "service" to c["name"], "serviceCol" to c["columnName"], "serviceType" to c["type"],
                        "dataObjects" to doHitsFor(c["name"] as? String),
                        "status" to "extra-service",
                    )
                    // not in the table *now*: a change set dropped it or renamed it away, and says which
                    removed.lastOrNull { loose(it.name) == loose(sql) }?.let { r ->
                        row["removed"] = linkedMapOf("by" to ref(r.by), "renamedTo" to r.renamedTo)
                    }
                    rows.add(row)
                }
            }

            if (lb != null) {
                // every changelog of the run that shapes the table sees the same columns, so each one's
                // column list can say how far a column is mapped
                val shaping = if (lb.definition || lb.run == null) listOf(lb)
                    else book.entries.filter { it.run === lb.run && tables.any { t -> lb.run.schema.resolve(t.name) in it.effective } }.ifEmpty { listOf(lb) }
                for (e in shaping) {
                    val cc = consumed.getOrPut(e.key) { LinkedHashSet<String>() to LinkedHashSet<String>() }
                    for (r in rows) {
                        if (r["inLiquibase"] == true && r["inService"] == true) cc.first.add(loose(r["sql"] as? String))
                        if (r["inLiquibase"] == true && (r["dataObjects"] as List<*>).isNotEmpty()) cc.second.add(loose(r["sql"] as? String))
                    }
                }
            }

            if (rows.isEmpty()) continue
            s["schemaCoverage"] = linkedMapOf(
                "liquibase" to lb?.key,
                "table" to s["tableName"],
                "dataObjects" to dos.map { it["key"] },
                "rows" to rows,
                "counts" to linkedMapOf(
                    "total" to rows.size,
                    "ok" to rows.count { it["status"] == "ok" },
                    "noService" to rows.count { it["status"] == "no-service" },
                    "noDataObject" to rows.count { it["status"] == "no-dataobject" },
                    "extra" to rows.count { it["status"] == "extra-service" },
                ),
            )
        }

        for (e in book.entries) {
            val cc = consumed[e.key] ?: continue
            e.map["coverage"] = linkedMapOf("service" to cc.first.sorted(), "dataObject" to cc.second.sorted())
        }
        return bindings
    }

    // ---------------------------------------------------------------------------
    // authority
    // ---------------------------------------------------------------------------

    /**
     * Whether each changelog is the live description of its tables:
     *
     * - **live** — a service's coverage reads it, or it names a service, or a service names it, or it
     *   shapes a table a service maps.
     * - **copy** — a schema definition that is the same Liquibase changelog as one of the application's own
     *   (the same `logicalFilePath`): the app carries it, the application runs its own copy.
     * - **superseded** — another changelog, in another run, is the one the services of its tables are
     *   bound to. Changelogs of one run are one history and never supersede one another: the file that
     *   adds a column to a table another file created is part of the same table, not a rival of it.
     * - **orphan** — nothing references it. An application changelog that names no service and whose
     *   tables no service maps is the application's own table (a JPA entity, a lock table) and gets no
     *   status: nothing in the models is meant to explain it.
     */
    private fun markLiquibaseAuthority(result: MutableMap<String, Any?>, book: Book, bindings: List<Binding>) {
        val services = mapList(result["services"])
        val svcKeys = services.mapNotNull { it["key"] as? String }.toHashSet()
        val svcByTable = LinkedHashMap<String, MutableList<String>>()
        val namedBy = LinkedHashMap<String, MutableList<String>>()
        for (s in services) {
            val k = s["key"] as? String ?: continue
            (s["tableName"] as? String)?.takeIf { it.isNotEmpty() }?.let { svcByTable.getOrPut(it.uppercase()) { ArrayList() }.add(k) }
            (s["referencedLiquibaseModelKey"] as? String)?.let { namedBy.getOrPut(it) { ArrayList() }.add(k) }
        }
        val strong = LinkedHashMap<String, MutableList<String>>()
        for (b in bindings) if (b.strong) strong.getOrPut(b.entry.key) { ArrayList() }.add(b.service)
        val owners = LinkedHashMap<String, MutableList<Entry>>()      // TABLE -> entries strongly bound to it
        for (e in book.entries) if (!strong[e.key].isNullOrEmpty()) for (t in e.effective) owners.getOrPut(t) { ArrayList() }.add(e)
        val appByLogical = book.entries.filter { !it.definition && it.run?.kind == "application" && it.hasChangeSets && it.logical != null }
            .associateBy { it.logical!! }

        for (e in book.entries) {
            val fwd = strong[e.key].orEmpty().distinct().sorted()
            val back = e.serviceRefs.filter { it in svcKeys }.distinct().sorted()
            val named = namedBy[e.key].orEmpty().distinct().sorted()
            val tblRefs = e.effective.flatMap { svcByTable[it].orEmpty() }.distinct().sorted()
            val status: String
            var by: List<String> = emptyList()
            var superseded: List<String> = emptyList()
            var copyOf: String? = null
            if (!e.definition) {
                when {
                    fwd.isNotEmpty() || tblRefs.isNotEmpty() -> { status = "live"; by = (fwd + tblRefs).distinct().sorted() }
                    back.isNotEmpty() -> { status = "live"; by = back }
                    named.isNotEmpty() -> { status = "live"; by = named }
                    e.serviceRefs.isNotEmpty() -> status = "orphan"   // it names services the project does not have
                    else -> continue
                }
            } else {
                val copy = e.logical?.let { appByLogical[it] }
                val rivals = e.effective.flatMap { owners[it].orEmpty() }.filter { it !== e && it.run !== e.run }.map { it.key }.distinct().sorted()
                when {
                    fwd.isNotEmpty() -> { status = "live"; by = fwd }
                    copy != null -> { status = "copy"; copyOf = copy.key; by = (named + back).distinct().sorted() }
                    rivals.isNotEmpty() -> { status = "superseded"; superseded = rivals }
                    back.isNotEmpty() -> { status = "live"; by = back }
                    named.isNotEmpty() -> { status = "live"; by = named }
                    tblRefs.isNotEmpty() -> { status = "live"; by = tblRefs }
                    e.effective.isEmpty() -> continue
                    else -> status = "orphan"
                }
            }
            val a = linkedMapOf<String, Any?>("status" to status, "referencedBy" to by, "supersededBy" to superseded)
            if (copyOf != null) a["copyOf"] = copyOf
            if (status == "orphan") e.serviceRefs.filter { it !in svcKeys }.takeIf { it.isNotEmpty() }?.let { a["namesMissing"] = it }
            e.map["authority"] = a
        }
    }

    // ---------------------------------------------------------------------------
    // small helpers
    // ---------------------------------------------------------------------------
    private val NON_ALNUM_RE = Regex("[^a-z0-9]")

    private fun loose(s: String?): String = NON_ALNUM_RE.replace((s ?: "").lowercase(), "")

    /** os.path.relpath(path, root) with forward slashes. */
    private fun relpath(root: File, path: File): String =
        root.toPath().relativize(path.toPath()).toString().replace(File.separatorChar, '/')

    // Thin aliases over Dyn so the many call sites below stay short; the unchecked cast itself lives
    // in Dyn, not here.
    private fun mapList(v: Any?): List<MutableMap<String, Any?>> = Dyn.mutableMaps(v)

    private fun mapListRO(v: Any?): List<Map<String, Any?>> = Dyn.maps(v)

    /** Python truthiness for the `or []` / `if x` guards used above. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }
}
