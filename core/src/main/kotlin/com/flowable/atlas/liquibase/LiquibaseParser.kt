package com.flowable.atlas.liquibase

import com.flowable.atlas.parsing.AtlasXml

/**
 * Reads a Liquibase changelog — XML, or formatted SQL — into an [LbChangelog].
 *
 * XML is read through the DOM, not matched with regular expressions over the text. The regex reading this
 * replaces applied what Liquibase never runs: a `<dropTable>` inside a change set's `<rollback>` dropped
 * the table the change set had just created, and a change set commented out with `<!-- … -->` still added
 * its columns. It also had no notion of a change set, so a precondition that skips one, or the same change
 * set kept in two files, could not be told apart from a change that runs.
 *
 * Properties are read here and expanded when the change set runs ([LiquibaseReplay]): Liquibase properties
 * are global, so a type in one file may use a property another file declared first.
 */
object LiquibaseParser {

    /** Thrown for a file that is a changelog but cannot be read as one — malformed XML, most often. */
    class ParseException(message: String) : Exception(message)

    private val SQL_HEADER = Regex("""^\s*--\s*liquibase\s+formatted\s+sql\b(.*)$""", RegexOption.IGNORE_CASE)

    /** Whether [text] is a changelog at all: a `databaseChangeLog` document, or formatted SQL. */
    fun isChangelog(text: String): Boolean = isFormattedSql(text) || text.contains("databaseChangeLog")

    private fun isFormattedSql(text: String): Boolean =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.let { SQL_HEADER.matches(it) } == true

    /** The changelog in [text], or null when it is none — a plain `.sql` script is none. Throws
     *  [ParseException] for a broken one. [path] tells an SQL file from an XML one. */
    fun parse(text: String, path: String = ""): LbChangelog? {
        if (isFormattedSql(text)) return parseSql(text)
        if (path.lowercase().endsWith(".sql") || !text.contains("databaseChangeLog")) return null
        val root = try {
            AtlasXml.parse(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ParseException(e.message?.lineSequence()?.firstOrNull() ?: "malformed XML")
        }
        if (root.tag != "databaseChangeLog") return null
        return parseXml(root, text)
    }

    // ---- XML ------------------------------------------------------------------------------------------

    /** `<changeSet` tags outside comments and CDATA, in document order — the DOM keeps no line numbers. */
    private val CHANGESET_TAG = Regex("""<(?:[\w.-]+:)?changeSet\b""")
    private val COMMENT_OR_CDATA = Regex("""<!--.*?-->|<!\[CDATA\[.*?]]>""", RegexOption.DOT_MATCHES_ALL)

    private fun changeSetLines(text: String): List<Int> {
        val masked = COMMENT_OR_CDATA.replace(text) { m -> m.value.replace(Regex("[^\n]"), " ") }
        val lines = ArrayList<Int>()
        var line = 1
        var at = 0
        for (m in CHANGESET_TAG.findAll(masked)) {
            for (k in at until m.range.first) if (masked[k] == '\n') line++
            at = m.range.first
            lines.add(line)
        }
        return lines
    }

    private fun parseXml(root: AtlasXml.El, text: String): LbChangelog {
        val lines = changeSetLines(text)
        var csIndex = 0
        val plain = LinkedHashMap<String, String>()
        val byDbms = LinkedHashMap<String, String>()
        val entries = ArrayList<LbEntry>()
        fun walk(parent: AtlasXml.El) {
            for (c in parent.children) when (c.tag) {
                "property" -> {
                    val name = c.attr("name") ?: continue
                    val value = c.attr("value") ?: continue
                    if (c.attr("dbms").isNullOrBlank()) plain.putIfAbsent(name, value) else byDbms.putIfAbsent(name, value)
                }
                "include" -> c.attr("file")?.let { entries.add(LbEntry.Include(it, relative(c))) }
                "includeAll" -> c.attr("path")?.let { entries.add(LbEntry.IncludeAll(it, relative(c))) }
                "changeSet" -> entries.add(LbEntry.ChangeSet(changeSet(c, lines.getOrElse(csIndex++) { 0 })))
                // Liquibase 4.x wraps includes whose change sets it rewrites; the includes are still included
                "modifyChangeSets" -> walk(c)
            }
        }
        walk(root)
        // A property declared per database and once without one takes the plain value on a database this
        // cannot know; one declared only per database takes the first of them rather than staying `${…}`.
        val props = LinkedHashMap(plain)
        for ((k, v) in byDbms) props.putIfAbsent(k, v)
        val refs = props["serviceDefinitionReferences"]?.split(Regex("[,\\s]+"))?.filter { it.isNotBlank() }.orEmpty()
        return LbChangelog(root.attr("logicalFilePath")?.ifBlank { null }, props, entries, refs)
    }

    private fun relative(e: AtlasXml.El) = e.attr("relativeToChangelogFile")?.trim().equals("true", ignoreCase = true)

    private fun changeSet(e: AtlasXml.El, line: Int): LbChangeSet {
        val changes = ArrayList<LbChange>()
        val tables = LinkedHashSet<String>()
        var pre: LbPreconditions? = null
        for (c in e.children) when (c.tag) {
            "preConditions" -> pre = preconditions(c)
            // what runs on rollback, a description, a checksum: nothing the forward run does
            "rollback", "comment", "validCheckSum", "modifySql" -> {}
            else -> {
                change(c, changes)
                collectTables(c, tables)
            }
        }
        for (ch in changes) tablesOf(ch).forEach { tables.add(it) }
        return LbChangeSet(
            id = e.attr("id") ?: "",
            author = e.attr("author") ?: "",
            logicalFilePath = e.attr("logicalFilePath")?.ifBlank { null },
            line = line,
            preconditions = pre,
            changes = changes,
            tables = tables.toList(),
            signature = e.children.filter { it.tag != "comment" }.joinToString("") { canonical(it) },
        )
    }

    private fun collectTables(e: AtlasXml.El, into: MutableSet<String>) {
        for (a in listOf("tableName", "oldTableName", "newTableName", "baseTableName", "referencedTableName")) {
            e.attr(a)?.takeIf { it.isNotBlank() }?.let { into.add(it) }
        }
        for (c in e.children) if (c.tag != "rollback") collectTables(c, into)
    }

    /** Tag, sorted attributes, text and children, whitespace collapsed; comments are not part of it. */
    private fun canonical(e: AtlasXml.El): String = buildString {
        append('<').append(e.tag)
        for ((k, v) in e.attributes.toSortedMap()) append(' ').append(k).append('=').append(v.trim())
        append('>')
        val t = e.ownText.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty()) append(t)
        for (c in e.children) if (c.tag != "comment") append(canonical(c))
        append("</>")
    }

    private fun preconditions(e: AtlasXml.El) =
        LbPreconditions(e.attr("onFail")?.trim()?.uppercase()?.ifEmpty { null } ?: "HALT", e.children.map(::precondition))

    private fun precondition(e: AtlasXml.El): LbPrecondition = when (e.tag) {
        "tableExists" -> e.attr("tableName")?.let { LbPrecondition.TableExists(it) } ?: LbPrecondition.Unknown(e.tag)
        "columnExists" -> {
            val t = e.attr("tableName"); val c = e.attr("columnName")
            if (t != null && c != null) LbPrecondition.ColumnExists(t, c) else LbPrecondition.Unknown(e.tag)
        }
        "changeSetExecuted" -> {
            val id = e.attr("id"); val author = e.attr("author")
            if (id != null && author != null) LbPrecondition.ChangeSetExecuted(id, author, e.attr("changeLogFile"))
            else LbPrecondition.Unknown(e.tag)
        }
        "not" -> LbPrecondition.Not(e.children.map(::precondition))
        "and" -> LbPrecondition.And(e.children.map(::precondition))
        "or" -> LbPrecondition.Or(e.children.map(::precondition))
        else -> LbPrecondition.Unknown(e.tag)
    }

    private fun columns(e: AtlasXml.El): List<LbColumn> =
        e.findChildren("column").mapNotNull { c -> c.attr("name")?.let { LbColumn(it, c.attr("type"), primaryKey(c)) } }

    /** `<column><constraints primaryKey="true"/></column>` — the way most changelogs declare a key. */
    private fun primaryKey(column: AtlasXml.El) =
        column.findChildren("constraints").any { it.attr("primaryKey")?.trim().equals("true", ignoreCase = true) }

    private fun change(e: AtlasXml.El, out: MutableList<LbChange>) {
        val t = e.attr("tableName")
        when (e.tag) {
            "createTable" -> if (t != null) out.add(LbChange.CreateTable(t, columns(e)))
            "addColumn" -> if (t != null) out.add(LbChange.AddColumn(t, columns(e)))
            "dropColumn" -> if (t != null) {
                val names = listOfNotNull(e.attr("columnName")) + e.findChildren("column").mapNotNull { it.attr("name") }
                if (names.isNotEmpty()) out.add(LbChange.DropColumn(t, names))
            }
            "renameColumn" -> {
                val old = e.attr("oldColumnName"); val new = e.attr("newColumnName")
                if (t != null && old != null && new != null) out.add(LbChange.RenameColumn(t, old, new, e.attr("columnDataType")))
            }
            "modifyDataType" -> {
                val c = e.attr("columnName")
                if (t != null && c != null) out.add(LbChange.ModifyDataType(t, c, e.attr("newDataType")))
            }
            "renameTable" -> {
                val old = e.attr("oldTableName"); val new = e.attr("newTableName")
                if (old != null && new != null) out.add(LbChange.RenameTable(old, new))
            }
            "dropTable" -> if (t != null) out.add(LbChange.DropTable(t))
            "addPrimaryKey" -> {
                val cols = e.attr("columnNames")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (t != null && cols.isNotEmpty()) out.add(LbChange.PrimaryKey(t, cols))
            }
            "dropPrimaryKey" -> if (t != null) out.add(LbChange.DropPrimaryKey(t))
            "mergeColumns" -> {
                val a = e.attr("column1Name"); val b = e.attr("column2Name"); val f = e.attr("finalColumnName")
                if (t != null && a != null && b != null && f != null) {
                    out.add(LbChange.AddColumn(t, listOf(LbColumn(f, e.attr("finalColumnType")))))
                    out.add(LbChange.DropColumn(t, listOf(a, b).filter { !it.equals(f, ignoreCase = true) }))
                }
            }
            "sql" -> out.addAll(SqlDdl.parse(e.ownText))
            "sqlFile" -> e.attr("path")?.let { out.add(LbChange.SqlFile(it, relative(e))) }
            "customChange" -> out.add(LbChange.Unread("customChange ${e.attr("class")?.substringAfterLast('.') ?: ""}".trim()))
        }
    }

    // ---- formatted SQL --------------------------------------------------------------------------------

    private val SQL_CHANGESET = Regex("""^\s*--\s*changeset\s+(?:"([^"]+)"|([^\s:]+)):(?:"([^"]+)"|(\S+))(.*)$""", RegexOption.IGNORE_CASE)
    private val SQL_PRECONDITIONS = Regex("""^\s*--\s*preconditions\b(.*)$""", RegexOption.IGNORE_CASE)
    private val SQL_TABLE_EXISTS = Regex("""^\s*--\s*precondition-table-exists\b(.*)$""", RegexOption.IGNORE_CASE)
    private val SQL_OTHER_PRECONDITION = Regex("""^\s*--\s*precondition-([\w-]+)""", RegexOption.IGNORE_CASE)
    private val SQL_DIRECTIVE = Regex("""^\s*--\s*(rollback|comment|validCheckSum|ignoreLines)\b.*$""", RegexOption.IGNORE_CASE)
    private val SQL_ATTR = Regex("""(\w+):(\S+)""")

    private fun parseSql(text: String): LbChangelog {
        val lines = text.lines()
        val header = SQL_HEADER.find(lines.first { it.isNotBlank() })?.groupValues?.get(1).orEmpty()
        val logical = SQL_ATTR.findAll(header).firstOrNull { it.groupValues[1] == "logicalFilePath" }?.groupValues?.get(2)
        val entries = ArrayList<LbEntry>()

        var id: String? = null
        var author = ""
        var csLogical: String? = null
        var line = 0
        var onFail = "HALT"
        val pre = ArrayList<LbPrecondition>()
        val body = StringBuilder()
        var ignoring = false
        fun flush() {
            val cid = id ?: return
            val changes = SqlDdl.parse(body.toString())
            entries.add(LbEntry.ChangeSet(LbChangeSet(
                id = cid, author = author, logicalFilePath = csLogical, line = line,
                preconditions = if (pre.isEmpty()) null else LbPreconditions(onFail, pre.toList()),
                changes = changes, tables = changes.flatMap(::tablesOf).distinct(),
                signature = body.toString().replace(Regex("\\s+"), " ").trim(),
            )))
            id = null; body.setLength(0); pre.clear(); onFail = "HALT"; csLogical = null
        }
        for ((i, raw) in lines.withIndex()) {
            if (ignoring) {
                if (raw.contains("ignoreLines:end", ignoreCase = true)) ignoring = false
                continue
            }
            val cs = SQL_CHANGESET.find(raw)
            if (cs != null) {
                flush()
                author = cs.groupValues[1].ifEmpty { cs.groupValues[2] }
                id = cs.groupValues[3].ifEmpty { cs.groupValues[4] }
                csLogical = SQL_ATTR.findAll(cs.groupValues[5]).firstOrNull { it.groupValues[1] == "logicalFilePath" }?.groupValues?.get(2)
                line = i + 1
                continue
            }
            val pm = SQL_PRECONDITIONS.find(raw)
            if (pm != null) {
                SQL_ATTR.findAll(pm.groupValues[1]).firstOrNull { it.groupValues[1] == "onFail" }?.let { onFail = it.groupValues[2].uppercase() }
                continue
            }
            val tm = SQL_TABLE_EXISTS.find(raw)
            if (tm != null) {
                val table = SQL_ATTR.findAll(tm.groupValues[1]).firstOrNull { it.groupValues[1] == "table" }?.groupValues?.get(2)
                pre.add(if (table != null) LbPrecondition.TableExists(table) else LbPrecondition.Unknown("table-exists"))
                continue
            }
            val om = SQL_OTHER_PRECONDITION.find(raw)
            if (om != null) { pre.add(LbPrecondition.Unknown(om.groupValues[1])); continue }
            if (SQL_DIRECTIVE.matches(raw)) {
                if (raw.contains("ignoreLines:start", ignoreCase = true)) ignoring = true
                continue
            }
            if (id != null) body.append(raw).append('\n')
        }
        flush()
        return LbChangelog(logical, emptyMap(), entries, emptyList())
    }

    /** The tables a change names — its own, and a renamed table's new name too. */
    fun tablesOf(c: LbChange): List<String> = when (c) {
        is LbChange.CreateTable -> listOf(c.table)
        is LbChange.AddColumn -> listOf(c.table)
        is LbChange.DropColumn -> listOf(c.table)
        is LbChange.RenameColumn -> listOf(c.table)
        is LbChange.ModifyDataType -> listOf(c.table)
        is LbChange.RenameTable -> listOf(c.oldName, c.newName)
        is LbChange.DropTable -> listOf(c.table)
        is LbChange.PrimaryKey -> listOf(c.table)
        is LbChange.DropPrimaryKey -> listOf(c.table)
        is LbChange.SqlFile, is LbChange.Unread -> emptyList()
    }
}
