package com.flowable.atlas.graph

import java.io.File

/**
 * Liquibase schema-coverage — a faithful port of the `_liquibase_*`, `_enrich_data_objects`,
 * `_schema_coverage` and `_mark_liquibase_authority` helpers in `flowable_atlas.py` (~lines 1615-2079)
 * together with the changelog-building block inside `extract` (~lines 1413-1466).
 *
 * [apply] is the single entry point: given the assembled [Atlas.extract] `result` (already carrying its
 * parsed `services` / `dataObjects`), the discovered `.xml`/`.sql` candidate files and the project root,
 * it (1) builds `result["liquibase"]` — a list of changelog objects with keys
 * `key, file, tables, effectiveTables, serviceRefs, columns` (plus `coverage` and `authority` added by
 * the coverage/authority passes), (2) denormalizes each data object's backing-service table onto it
 * (`serviceTableName` / `serviceType`), and (3) attaches `schemaCoverage` to each service.
 *
 * Regex-based over the raw changelog text, exactly like the Python (no XSD, no Liquibase runtime).
 */
object LiquibaseCoverage {

    /** Run the whole Liquibase flow, mutating [result] in place. Mirrors the four `extract` calls. */
    fun apply(result: MutableMap<String, Any?>, xmlFiles: List<File>, root: File) {
        buildLiquibase(result, xmlFiles, root)
        enrichDataObjects(result)
        schemaCoverage(result)
        markLiquibaseAuthority(result)
    }

    // ---------------------------------------------------------------------------
    // Regexes (mirror _LB_* in flowable_atlas.py)
    // ---------------------------------------------------------------------------
    private val S = setOf(RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_RE = Regex("<(createTable|addColumn)\\b([^>]*?)>(.*?)</\\1\\s*>", S)
    private val COLUMN_RE = Regex("<column\\b([^>]*?)/?>")
    private val RENAMECOL_RE = Regex("<renameColumn\\b([^>]*?)/?>", S)
    private val DROPCOL_RE = Regex("<dropColumn\\b([^>]*?)(?:/>|>(.*?)</dropColumn\\s*>)", S)
    private val MODIFYTYPE_RE = Regex("<modifyDataType\\b([^>]*?)/?>", S)
    private val RENAMETABLE_RE = Regex("<renameTable\\b([^>]*?)/?>", S)
    private val DROPTABLE_RE = Regex("<dropTable\\b([^>]*?)/?>", S)
    private val INCLUDE_RE = Regex("<include(All)?\\b([^>]*?)/?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TABLE_NAME_RE = Regex("tableName=\"([^\"]+)\"")
    private val SVC_REFS_RE = Regex("name=\"serviceDefinitionReferences\"\\s+value=\"([^\"]*)\"")
    private val NON_ALNUM_RE = Regex("[^a-z0-9]")
    private val DIGITS_RE = Regex("\\d+")

    // ---------------------------------------------------------------------------
    // A discovered changelog file (uses reference identity, like Python's id(f)).
    // ---------------------------------------------------------------------------
    private class LbFile(val file: File, val rel: String, val txt: String) {
        val ops: List<Map<String, Any?>> = liquibaseOps(txt)
        val tables: List<String> = TABLE_NAME_RE.findAll(txt).map { it.groupValues[1] }.toSortedSet().toList()
        val pathStr: String = file.path
        val baseName: String = file.name
    }

    // ---------------------------------------------------------------------------
    // _liquibase_ops
    // ---------------------------------------------------------------------------
    private fun liquibaseOps(txt: String): List<Map<String, Any?>> {
        val found = ArrayList<Pair<Int, Map<String, Any?>>>()
        for (m in BLOCK_RE.findAll(txt)) {
            val cols = ArrayList<Map<String, Any?>>()
            for (cm in COLUMN_RE.findAll(m.groupValues[3])) {
                val nm = lbAttr(cm.groupValues[1], "name")
                if (nm != null) cols.add(linkedMapOf("name" to nm, "type" to lbAttr(cm.groupValues[1], "type")))
            }
            if (cols.isNotEmpty()) {
                found.add(m.range.first to linkedMapOf(
                    "op" to m.groupValues[1], "table" to lbAttr(m.groupValues[2], "tableName"), "columns" to cols,
                ))
            }
        }
        for (m in RENAMECOL_RE.findAll(txt)) {
            val a = m.groupValues[1]
            val old = lbAttr(a, "oldColumnName"); val new = lbAttr(a, "newColumnName")
            if (old != null && new != null) {
                found.add(m.range.first to linkedMapOf(
                    "op" to "renameColumn", "table" to lbAttr(a, "tableName"),
                    "oldName" to old, "newName" to new, "type" to lbAttr(a, "columnDataType"),
                ))
            }
        }
        for (m in DROPCOL_RE.findAll(txt)) {
            val a = m.groupValues[1]; val body = m.groupValues.getOrElse(2) { "" }
            val names = ArrayList<String>()
            lbAttr(a, "columnName")?.let { names.add(it) }
            for (cm in COLUMN_RE.findAll(body)) lbAttr(cm.groupValues[1], "name")?.let { names.add(it) }
            if (names.isNotEmpty()) {
                found.add(m.range.first to linkedMapOf("op" to "dropColumn", "table" to lbAttr(a, "tableName"), "columns" to names))
            }
        }
        for (m in MODIFYTYPE_RE.findAll(txt)) {
            val a = m.groupValues[1]
            val col = lbAttr(a, "columnName")
            if (col != null) {
                found.add(m.range.first to linkedMapOf(
                    "op" to "modifyDataType", "table" to lbAttr(a, "tableName"), "column" to col, "type" to lbAttr(a, "newDataType"),
                ))
            }
        }
        for (m in RENAMETABLE_RE.findAll(txt)) {
            val a = m.groupValues[1]
            val old = lbAttr(a, "oldTableName"); val new = lbAttr(a, "newTableName")
            if (old != null && new != null) {
                found.add(m.range.first to linkedMapOf("op" to "renameTable", "oldTable" to old, "newTable" to new))
            }
        }
        for (m in DROPTABLE_RE.findAll(txt)) {
            val t = lbAttr(m.groupValues[1], "tableName")
            if (t != null) found.add(m.range.first to linkedMapOf("op" to "dropTable", "table" to t))
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** _lb_attr — read one XML attribute value out of a tag's attribute string. */
    private fun lbAttr(s: String?, name: String): String? =
        Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(s ?: "")?.groupValues?.get(1)

    // ---------------------------------------------------------------------------
    // _natural_key — v2 < v10 ordering
    // ---------------------------------------------------------------------------
    /** re.split(r'(\d+)', s): alternating non-digit / digit segments, keeping empties. */
    private fun reSplitDigits(s: String): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in DIGITS_RE.findAll(s)) {
            out.add(s.substring(last, m.range.first))
            out.add(m.value)
            last = m.range.last + 1
        }
        out.add(s.substring(last))
        return out
    }

    /** Compare two strings the way `_natural_key` orders them (numbers numerically, else lower-cased). */
    private fun compareNatural(aRaw: String?, bRaw: String?): Int {
        val pa = reSplitDigits(aRaw ?: "")
        val pb = reSplitDigits(bRaw ?: "")
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa[i]; val y = pb[i]
            val xd = x.isNotEmpty() && x.all { it.isDigit() }
            val yd = y.isNotEmpty() && y.all { it.isDigit() }
            val c = if (xd && yd) x.toBigInteger().compareTo(y.toBigInteger()) else x.lowercase().compareTo(y.lowercase())
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private val NATURAL_BY_REL = Comparator<LbFile> { a, b -> compareNatural(a.rel, b.rel) }

    // ---------------------------------------------------------------------------
    // _loose
    // ---------------------------------------------------------------------------
    private fun loose(s: String?): String = NON_ALNUM_RE.replace((s ?: "").lowercase(), "")

    // ---------------------------------------------------------------------------
    // _liquibase_key
    // ---------------------------------------------------------------------------
    private val KEY_SUFFIX_RE = Regex("\\.data\\.changelog\\.xml$|\\.changelog\\.xml$|\\.xml$|\\.sql$", RegexOption.IGNORE_CASE)
    private fun liquibaseKey(path: String): String {
        var base = path.substringAfterLast('!').substringAfterLast('/')
        base = base.replaceFirst(Regex("^liquibase-"), "")
        return KEY_SUFFIX_RE.replaceFirst(base, "")
    }

    // ---------------------------------------------------------------------------
    // _liquibase_groups
    // ---------------------------------------------------------------------------
    private fun clean(p: String?): String =
        Regex("^classpath\\*?:").replace(p ?: "", "").replace('\\', '/').trim('/').lowercase()

    private fun liquibaseGroups(files: List<LbFile>): List<List<LbFile>> {
        val byBase = LinkedHashMap<String, MutableList<LbFile>>()
        for (f in files) byBase.getOrPut(f.baseName) { ArrayList() }.add(f)

        fun nat(fs: List<LbFile>): List<LbFile> = fs.sortedWith(NATURAL_BY_REL)

        fun closure(master: LbFile): List<LbFile> {
            val ordered = ArrayList<LbFile>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LbFile, Boolean>())

            fun walk(f: LbFile) {
                if (!seen.add(f)) return
                for (m in INCLUDE_RE.findAll(f.txt)) {
                    val a = m.groupValues[2]
                    if (m.groupValues[1].isNotEmpty()) {                     // <includeAll path="dir">
                        val pdir = clean(lbAttr(a, "path") ?: lbAttr(a, "dir") ?: "")
                        if (pdir.isEmpty()) continue
                        val kids = files.filter { g ->
                            g !== f && (g.file.parent ?: "").replace('\\', '/').lowercase().endsWith(pdir)
                        }
                        for (g in nat(kids)) walk(g)
                    } else {                                                 // <include file="x.xml">
                        val ref = clean(lbAttr(a, "file"))
                        if (ref.isEmpty()) continue
                        var g: LbFile? = files.firstOrNull { it.pathStr.replace('\\', '/').lowercase().endsWith(ref) }
                        if (g == null) g = byBase[ref.substringAfterLast('/')]?.firstOrNull()
                        if (g != null) walk(g)
                    }
                }
                ordered.add(f)                                               // master itself after its includes
            }

            walk(master)
            return ordered
        }

        val masters = files.filter { INCLUDE_RE.containsMatchIn(it.txt) }
        val assigned = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LbFile, Boolean>())
        val groups = ArrayList<List<LbFile>>()
        for (master in nat(masters)) {
            if (master in assigned) continue
            val grp = closure(master).filter { it !in assigned }
            if (grp.isNotEmpty()) {
                assigned.addAll(grp)
                groups.add(grp)
            }
        }
        for (f in nat(files)) {
            if (f !in assigned) {
                assigned.add(f)
                groups.add(listOf(f))
            }
        }
        return groups
    }

    // ---------------------------------------------------------------------------
    // _liquibase_replay
    // ---------------------------------------------------------------------------
    /** Returns (schema, alias): schema maps UPPER table -> surviving columns; alias maps any historical
     *  UPPER name to its final UPPER name. Column maps carry name/type/table (the _k loose key is dropped). */
    private fun liquibaseReplay(files: List<LbFile>): Pair<Map<String, List<Map<String, Any?>>>, Map<String, String>> {
        val schema = LinkedHashMap<String, ArrayList<MutableMap<String, Any?>>>()
        val alias = LinkedHashMap<String, String>()

        fun cur(start: String): String {
            var tu = start
            val seen = HashSet<String>()
            while (alias.containsKey(tu) && tu !in seen) {
                seen.add(tu)
                tu = alias.getValue(tu)
            }
            return tu
        }

        for (lf in files) for (op in lf.ops) {
            when (op["op"] as String) {
                "createTable", "addColumn" -> {
                    val tu = cur((op["table"] as? String ?: "").uppercase())
                    val lst = schema.getOrPut(tu) { ArrayList() }
                    val idx = HashMap<String, MutableMap<String, Any?>>()
                    for (c in lst) idx[c["_k"] as String] = c
                    @Suppress("UNCHECKED_CAST") val columns = op["columns"] as List<Map<String, Any?>>
                    for (col in columns) {
                        val k = loose(col["name"] as? String)
                        val existing = idx[k]
                        if (existing != null) {
                            if (truthy(col["type"])) existing["type"] = col["type"]
                            continue
                        }
                        val c = linkedMapOf<String, Any?>(
                            "name" to col["name"], "type" to col["type"], "table" to op["table"], "_k" to k,
                        )
                        idx[k] = c
                        lst.add(c)
                    }
                }
                "renameColumn" -> {
                    val tu = cur((op["table"] as? String ?: "").uppercase())
                    val lst = schema[tu]
                    val ok = loose(op["oldName"] as? String); val nk = loose(op["newName"] as? String)
                    val hit = lst?.firstOrNull { it["_k"] == ok }
                    if (hit != null) {
                        hit["name"] = op["newName"]; hit["_k"] = nk
                        if (truthy(op["type"])) hit["type"] = op["type"]
                    } else {
                        schema.getOrPut(tu) { ArrayList() }.add(linkedMapOf(
                            "name" to op["newName"], "type" to op["type"], "table" to op["table"], "_k" to nk,
                        ))
                    }
                }
                "dropColumn" -> {
                    val tu = cur((op["table"] as? String ?: "").uppercase())
                    val lst = schema[tu]
                    if (lst != null) {
                        @Suppress("UNCHECKED_CAST") val cols = op["columns"] as List<String>
                        val drop = cols.map { loose(it) }.toHashSet()
                        schema[tu] = ArrayList(lst.filter { it["_k"] !in drop })
                    }
                }
                "modifyDataType" -> {
                    val tu = cur((op["table"] as? String ?: "").uppercase())
                    val lst = schema[tu]
                    if (lst != null && truthy(op["type"])) {
                        val k = loose(op["column"] as? String)
                        for (c in lst) if (c["_k"] == k) c["type"] = op["type"]
                    }
                }
                "renameTable" -> {
                    val old = cur((op["oldTable"] as String).uppercase()); val new = (op["newTable"] as String).uppercase()
                    val lst = schema.remove(old)
                    if (lst != null) {
                        for (c in lst) c["table"] = op["newTable"]
                        val merged = ArrayList<MutableMap<String, Any?>>()
                        schema[new]?.let { merged.addAll(it) }
                        merged.addAll(lst)
                        schema[new] = merged
                    }
                    alias[old] = new
                }
                "dropTable" -> schema.remove(cur((op["table"] as? String ?: "").uppercase()))
            }
        }

        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for ((t, lst) in schema) {
            out[t] = lst.map { linkedMapOf("name" to it["name"], "type" to it["type"], "table" to it["table"]) }
        }
        val aliasOut = LinkedHashMap<String, String>()
        for (k in alias.keys) aliasOut[k] = cur(k)
        return out to aliasOut
    }

    // ---------------------------------------------------------------------------
    // extract() changelog-building block -> result["liquibase"]
    // ---------------------------------------------------------------------------
    private fun buildLiquibase(result: MutableMap<String, Any?>, xmlFiles: List<File>, root: File) {
        val lbFiles = ArrayList<LbFile>()
        for (path in xmlFiles) {
            val rel = if (root.isDirectory) relpath(root, path) else path.name
            val txt = try { path.readText(Charsets.UTF_8) } catch (e: Exception) { continue }
            if (!txt.contains("databaseChangeLog") && !txt.contains("<changeSet") && !txt.lowercase().contains("createtable")) continue
            lbFiles.add(LbFile(path, rel, txt))
        }
        if (lbFiles.isEmpty()) return

        // Per replay-group schema/alias, keyed by file identity.
        val schemaOf = java.util.IdentityHashMap<LbFile, Map<String, List<Map<String, Any?>>>>()
        val aliasOf = java.util.IdentityHashMap<LbFile, Map<String, String>>()
        for (grp in liquibaseGroups(lbFiles)) {
            val (schema, alias) = liquibaseReplay(grp)
            for (lf in grp) { schemaOf[lf] = schema; aliasOf[lf] = alias }
        }

        @Suppress("UNCHECKED_CAST")
        val bucket = result["liquibase"] as MutableList<Any?>
        for (lf in lbFiles) {
            val schema = schemaOf[lf] ?: emptyMap()
            val alias = aliasOf[lf] ?: emptyMap()
            val touched = ArrayList<String>(); val seenT = HashSet<String>()
            for (op in lf.ops) {
                val t = strOr(op["table"], op["newTable"])
                if (t != null && t.uppercase() !in seenT) { seenT.add(t.uppercase()); touched.add(t) }
            }
            val cols = ArrayList<Map<String, Any?>>()
            for (t in touched) cols.addAll(schema[alias[t.uppercase()] ?: t.uppercase()] ?: emptyList())
            val effTables = cols.mapNotNull { it["table"] as? String }.filter { it.isNotEmpty() }.toSortedSet().toList()
            val svcRefs = SVC_REFS_RE.findAll(lf.txt)
                .flatMap { it.groupValues[1].split(Regex("[,\\s]+")).asSequence() }
                .filter { it.isNotEmpty() }.toSortedSet().toList()
            bucket.add(linkedMapOf(
                "key" to liquibaseKey(lf.rel), "file" to lf.rel,
                "tables" to lf.tables, "effectiveTables" to effTables,
                "serviceRefs" to svcRefs, "columns" to cols,
            ))
        }
    }

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
    }

    // ---------------------------------------------------------------------------
    // _schema_coverage
    // ---------------------------------------------------------------------------
    private fun schemaCoverage(result: MutableMap<String, Any?>) {
        val liquibase = mapList(result["liquibase"])
        val services = mapList(result["services"])
        val dataObjects = mapList(result["dataObjects"])

        val lbByKey = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (lb in liquibase) (lb["key"] as? String)?.let { lbByKey[it] = lb }

        val lbByTable = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (lb in liquibase) {
            val tabs = LinkedHashSet<String>()
            asStrings(lb["effectiveTables"]).forEach { tabs.add(it) }
            asStrings(lb["tables"]).forEach { tabs.add(it) }
            for (t in tabs) lbByTable.putIfAbsent(t.uppercase(), lb)
        }

        val lbBySvcRef = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (lb in liquibase) for (sk in asStrings(lb["serviceRefs"])) lbBySvcRef.putIfAbsent(sk, lb)

        val dosByService = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (d in dataObjects) (d["service"] as? String)?.let { dosByService.getOrPut(it) { ArrayList() }.add(d) }

        // consumed[lbKey] = ("service" loose names, "dataObject" loose names)
        val consumed = LinkedHashMap<String, Pair<LinkedHashSet<String>, LinkedHashSet<String>>>()

        for (s in services) {
            val rk = s["referencedLiquibaseModelKey"]
            var lb: MutableMap<String, Any?>? = if (truthy(rk)) lbByKey[rk.toString()] else null
            if (lb == null) lb = lbBySvcRef[s["key"] as? String]
            if (lb == null && truthy(s["tableName"])) lb = lbByTable[(s["tableName"] as String).uppercase()]

            val svcTable = ((s["tableName"] as? String) ?: "").uppercase().ifEmpty { null }
            val lbCols = ArrayList<Map<String, Any?>>()
            if (lb != null) {
                for (c in mapListRO(lb["columns"])) {
                    val ct = c["table"] as? String
                    if (svcTable != null && ct != null && ct.uppercase() != svcTable) continue
                    lbCols.add(c)
                }
                if (svcTable != null && lbCols.isEmpty()) lbCols.addAll(mapListRO(lb["columns"]))
            }

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
                val sql = (c["name"] as? String) ?: ""
                val key = loose(sql)
                val svcCol = svcByLoose[key]
                if (svcCol != null) seenSvc.add(key)
                val hits = doHitsFor(sql, if (svcCol != null) svcCol["name"] as? String else null)
                val status = if (svcCol != null && hits.isNotEmpty()) "ok"
                    else if (svcCol != null) "no-dataobject" else "no-service"
                rows.add(linkedMapOf(
                    "sql" to sql, "table" to c["table"], "sqlType" to c["type"],
                    "inLiquibase" to true, "inService" to (svcCol != null),
                    "service" to (svcCol?.get("name")), "serviceCol" to (svcCol?.get("columnName")),
                    "serviceType" to (svcCol?.get("type")), "dataObjects" to hits, "status" to status,
                ))
            }

            if (lb != null) {
                for (c in mapListRO(s["columns"])) {
                    val sql = (c["columnName"] as? String) ?: (c["name"] as? String) ?: ""
                    if (sql.isEmpty() || loose(sql) in seenSvc) continue
                    rows.add(linkedMapOf(
                        "sql" to sql, "table" to null, "sqlType" to null,
                        "inLiquibase" to false, "inService" to true,
                        "service" to c["name"], "serviceCol" to c["columnName"], "serviceType" to c["type"],
                        "dataObjects" to doHitsFor(c["name"] as? String, c["columnName"] as? String),
                        "status" to "extra-service",
                    ))
                }
            }

            if (lb != null) {
                val cc = consumed.getOrPut(lb["key"] as String) { LinkedHashSet<String>() to LinkedHashSet<String>() }
                for (r in rows) {
                    if (r["inLiquibase"] == true && r["inService"] == true) cc.first.add(loose(r["sql"] as? String))
                    if (r["inLiquibase"] == true && (r["dataObjects"] as List<*>).isNotEmpty()) cc.second.add(loose(r["sql"] as? String))
                }
            }

            if (rows.isEmpty()) continue
            s["schemaCoverage"] = linkedMapOf(
                "liquibase" to (lb?.get("key")),
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

        for (lb in liquibase) {
            val cc = consumed[lb["key"] as? String] ?: continue
            lb["coverage"] = linkedMapOf(
                "service" to cc.first.sorted(),
                "dataObject" to cc.second.sorted(),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // _mark_liquibase_authority
    // ---------------------------------------------------------------------------
    private fun markLiquibaseAuthority(result: MutableMap<String, Any?>) {
        val services = mapList(result["services"])
        val liquibase = mapList(result["liquibase"])

        val forward = LinkedHashMap<String, MutableList<String?>>()          // lb key -> [services binding it forward]
        val svcByTable = LinkedHashMap<String, MutableList<String?>>()       // TABLE -> [service keys] (tableName match)
        for (s in services) {
            (s["referencedLiquibaseModelKey"] as? String)?.let { forward.getOrPut(it) { ArrayList() }.add(s["key"] as? String) }
            (s["tableName"] as? String)?.let { svcByTable.getOrPut(it.uppercase()) { ArrayList() }.add(s["key"] as? String) }
        }
        val svcKeys = services.mapNotNull { it["key"] as? String }.toHashSet()

        val backref = LinkedHashMap<String, MutableList<String>>()           // lb key -> [services it names back]
        for (lb in liquibase) for (sk in asStrings(lb["serviceRefs"])) {
            if (sk in svcKeys) backref.getOrPut(lb["key"] as String) { ArrayList() }.add(sk)
        }

        fun eff(lb: Map<String, Any?>): Set<String> = asStrings(lb["effectiveTables"]).map { it.uppercase() }.toHashSet()

        val forwardOwner = LinkedHashMap<String, MutableList<String>>()      // TABLE -> [lb keys bound forward]
        for (lb in liquibase) {
            val k = lb["key"] as String
            if (!forward[k].isNullOrEmpty()) for (t in eff(lb)) forwardOwner.getOrPut(t) { ArrayList() }.add(k)
        }

        for (lb in liquibase) {
            val k = lb["key"] as String
            val tbls = eff(lb)
            val fwd = (forward[k] ?: emptyList()).filterNotNull().distinct().sorted()
            val back = (backref[k] ?: emptyList()).distinct().sorted()
            if (tbls.isEmpty() && fwd.isEmpty() && back.isEmpty()) continue
            val owners = tbls.flatMap { forwardOwner[it] ?: emptyList() }.filter { it != k }.distinct().sorted()
            val status: String; val by: List<String>
            if (fwd.isNotEmpty()) { status = "live"; by = fwd }
            else if (owners.isNotEmpty()) { status = "superseded"; by = emptyList() }
            else if (back.isNotEmpty()) { status = "live"; by = back }
            else {
                val tblRefs = tbls.flatMap { (svcByTable[it] ?: emptyList()) }.filterNotNull().distinct().sorted()
                if (tblRefs.isNotEmpty()) { status = "live"; by = tblRefs } else { status = "orphan"; by = emptyList() }
            }
            lb["authority"] = linkedMapOf("status" to status, "referencedBy" to by, "supersededBy" to owners)
        }
    }

    // ---------------------------------------------------------------------------
    // small helpers
    // ---------------------------------------------------------------------------
    /** Python `a or b` for string-ish values (empty/null a falls through to b). */
    private fun strOr(a: Any?, b: Any?): String? {
        val sa = a as? String
        return if (!sa.isNullOrEmpty()) sa else b as? String
    }

    /** os.path.relpath(path, root) with forward slashes. */
    private fun relpath(root: File, path: File): String =
        root.toPath().relativize(path.toPath()).toString().replace(File.separatorChar, '/')

    @Suppress("UNCHECKED_CAST")
    private fun mapList(v: Any?): List<MutableMap<String, Any?>> =
        (v as? List<*>).orEmpty().mapNotNull { it as? MutableMap<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun mapListRO(v: Any?): List<Map<String, Any?>> =
        (v as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

    private fun asStrings(v: Any?): List<String> =
        (v as? List<*>).orEmpty().mapNotNull { it as? String }

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
