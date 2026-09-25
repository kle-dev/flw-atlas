package com.flowable.atlas.liquibase

/**
 * The table-shaping statements of raw SQL — a `<sql>` change, a `<sqlFile>`, a formatted-SQL changelog —
 * read into the same [LbChange]s the XML changes become. Only what decides a table's columns is read:
 * `CREATE TABLE`, `ALTER TABLE … ADD / DROP / RENAME / ALTER / MODIFY / CHANGE`, `DROP TABLE`,
 * `RENAME TABLE` and `sp_rename`, in the spellings of the databases Flowable runs on. Everything else —
 * `INSERT`, `CREATE INDEX`, a view, a sequence — changes no column list and is passed over.
 *
 * A tokenizer rather than regular expressions over the statement: a column type carries parentheses and
 * commas of its own (`NUMERIC(10, 2)`), and a default or a check constraint can carry anything, so only a
 * scan that tracks nesting and quotes knows where one column definition ends.
 */
object SqlDdl {

    fun parse(sql: String): List<LbChange> {
        val out = ArrayList<LbChange>()
        for (stmt in statements(sql)) {
            try {
                statement(stmt, out)
            } catch (e: IndexOutOfBoundsException) {
                // a statement cut short (a template, a half-written file) says nothing reliable about a table
            }
        }
        return out
    }

    // ---- tokens ---------------------------------------------------------------------------------------

    private class Tok(val text: String, val quoted: Boolean) {
        val up: String = if (quoted) "" else text.uppercase()
        override fun toString() = text
    }

    /** The statements of [sql], comments dropped, split at `;` and at a line holding only `GO` or `/`. */
    private fun statements(sql: String): List<List<Tok>> {
        val out = ArrayList<List<Tok>>()
        var cur = ArrayList<Tok>()
        fun flush() { if (cur.isNotEmpty()) out.add(cur); cur = ArrayList() }
        var i = 0
        val n = sql.length
        var lineStart = true
        while (i < n) {
            val c = sql[i]
            when {
                c == '\n' -> { lineStart = true; i++; continue }
                c.isWhitespace() -> { i++; continue }
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> { while (i < n && sql[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                    continue
                }
                c == ';' -> { flush(); i++ }
                lineStart && (c == '/' || c == 'G' || c == 'g') && batchSeparator(sql, i) -> {
                    flush()
                    while (i < n && sql[i] != '\n') i++
                    continue
                }
                c == '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n) {
                        if (sql[i] == '\'') {
                            if (i + 1 < n && sql[i + 1] == '\'') { sb.append('\''); i += 2; continue }
                            i++; break
                        }
                        sb.append(sql[i]); i++
                    }
                    cur.add(Tok("'$sb'", true))
                }
                c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    val end = sql.indexOf(close, i + 1).let { if (it < 0) n else it }
                    cur.add(Tok(sql.substring(i + 1, end), true))
                    i = end + 1
                }
                c.isLetterOrDigit() || c == '_' || c == '$' || c == '#' || c == '@' -> {
                    val s = i
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$' || sql[i] == '#' || sql[i] == '@')) i++
                    cur.add(Tok(sql.substring(s, i), false))
                }
                else -> { cur.add(Tok(c.toString(), false)); i++ }
            }
            lineStart = false
        }
        flush()
        return out
    }

    private fun batchSeparator(sql: String, i: Int): Boolean {
        val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
        val line = sql.substring(i, end).trim()
        return line == "/" || line.equals("GO", ignoreCase = true)
    }

    // ---- statements -----------------------------------------------------------------------------------

    private fun statement(t: List<Tok>, out: MutableList<LbChange>) {
        when (t.firstOrNull()?.up) {
            "CREATE" -> createTable(t, out)
            "ALTER" -> if (t.getOrNull(1)?.up == "TABLE") alterTable(t, out)
            "DROP" -> if (t.getOrNull(1)?.up == "TABLE") dropTable(t, out)
            "RENAME" -> if (t.getOrNull(1)?.up == "TABLE") renameTables(t, 2, out)
            "EXEC", "EXECUTE" -> spRename(t, 1, out)
            "SP_RENAME" -> spRename(t, 0, out)
        }
    }

    private val CREATE_MODIFIERS = setOf("OR", "REPLACE", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP", "UNLOGGED", "VOLATILE", "MULTISET", "SET")

    private fun createTable(t: List<Tok>, out: MutableList<LbChange>) {
        var i = 1
        while (t[i].up in CREATE_MODIFIERS) i++
        if (t[i].up != "TABLE") return
        i++
        i = skipIf(t, i, "IF", "NOT", "EXISTS")
        val (name, next) = qualifiedName(t, i)
        i = next
        if (i >= t.size || t[i].text != "(") {
            // CREATE TABLE x AS SELECT … / LIKE other: a table whose columns another statement decides
            out.add(LbChange.Unread("CREATE TABLE $name AS …"))
            out.add(LbChange.CreateTable(name, emptyList()))
            return
        }
        val close = matching(t, i)
        out.add(LbChange.CreateTable(name, split(t, i + 1, close).mapNotNull(::columnDef)))
    }

    private fun alterTable(t: List<Tok>, out: MutableList<LbChange>) {
        var i = 2
        i = skipIf(t, i, "IF", "EXISTS")
        if (t[i].up == "ONLY") i++
        val (table, next) = qualifiedName(t, i)
        var verb = ""
        for (action in split(t, next, t.size)) {
            if (action.isEmpty()) continue
            var a = action
            // MSSQL `ALTER TABLE t ADD a INT, b INT`: the second definition carries no verb of its own
            if (a[0].up in VERBS) { verb = a[0].up; a = a.subList(1, a.size) } else if (verb != "ADD") continue
            if (a.isEmpty()) continue
            when (verb) {
                "ADD" -> {
                    var j = if (a[0].up == "COLUMN") 1 else 0
                    j = skipIf(a, j, "IF", "NOT", "EXISTS")
                    if (j >= a.size || a[j].up in TABLE_CONSTRAINTS) continue
                    val cols = if (a[j].text == "(") split(a, j + 1, matching(a, j)).mapNotNull(::columnDef)
                    else listOfNotNull(columnDef(a.subList(j, a.size)))
                    if (cols.isNotEmpty()) out.add(LbChange.AddColumn(table, cols))
                }
                "DROP" -> {
                    var j = if (a[0].up == "COLUMN") 1 else 0
                    j = skipIf(a, j, "IF", "EXISTS")
                    if (j >= a.size || (a[j].up in TABLE_CONSTRAINTS && j == 0) || a[j].up == "DEFAULT") continue
                    val names = if (a[j].text == "(") split(a, j + 1, matching(a, j)).mapNotNull { it.firstOrNull()?.text }
                    else listOf(a[j].text)
                    out.add(LbChange.DropColumn(table, names))
                }
                "RENAME" -> when {
                    a[0].up == "TO" || a[0].up == "AS" -> out.add(LbChange.RenameTable(table, qualifiedName(a, 1).first))
                    a[0].up == "COLUMN" && a.size >= 4 && a[2].up == "TO" -> out.add(LbChange.RenameColumn(table, a[1].text, a[3].text, null))
                    a.size >= 3 && a[1].up == "TO" -> out.add(LbChange.RenameColumn(table, a[0].text, a[2].text, null))
                }
                "ALTER" -> {
                    val j = if (a[0].up == "COLUMN") 1 else 0
                    val col = a.getOrNull(j)?.text ?: continue
                    var k = j + 1
                    if (k < a.size && (a[k].up == "SET" || a[k].up == "DROP")) {
                        if (a.getOrNull(k + 1)?.up == "DATA" && a.getOrNull(k + 2)?.up == "TYPE") k += 3 else continue
                    } else if (k < a.size && a[k].up == "TYPE") k++
                    typeOf(a, k)?.let { out.add(LbChange.ModifyDataType(table, col, it)) }
                }
                "MODIFY" -> {
                    val j = if (a[0].up == "COLUMN") 1 else 0
                    val defs = if (a.getOrNull(j)?.text == "(") split(a, j + 1, matching(a, j)).mapNotNull(::columnDef)
                    else listOfNotNull(columnDef(a.subList(j, a.size)))
                    for (d in defs) out.add(LbChange.ModifyDataType(table, d.name, d.type))
                }
                "CHANGE" -> {
                    val j = if (a[0].up == "COLUMN") 1 else 0
                    if (a.size > j + 1) out.add(LbChange.RenameColumn(table, a[j].text, a[j + 1].text, typeOf(a, j + 2)))
                }
            }
        }
    }

    private fun dropTable(t: List<Tok>, out: MutableList<LbChange>) {
        val i = skipIf(t, 2, "IF", "EXISTS")
        for (part in split(t, i, t.size)) if (part.isNotEmpty()) out.add(LbChange.DropTable(qualifiedName(part, 0).first))
    }

    /** MySQL `RENAME TABLE a TO b, c TO d`. */
    private fun renameTables(t: List<Tok>, from: Int, out: MutableList<LbChange>) {
        for (part in split(t, from, t.size)) {
            val to = part.indexOfFirst { it.up == "TO" }
            if (to <= 0) continue
            out.add(LbChange.RenameTable(qualifiedName(part, 0).first, qualifiedName(part, to + 1).first))
        }
    }

    /** MSSQL `EXEC sp_rename 'T.OLD', 'NEW', 'COLUMN'` / `EXEC sp_rename 'OLD', 'NEW'`. */
    private fun spRename(t: List<Tok>, at: Int, out: MutableList<LbChange>) {
        if (t.getOrNull(at)?.text?.substringAfterLast('.')?.equals("sp_rename", ignoreCase = true) != true) return
        val args = t.drop(at + 1).filter { it.quoted }.map { it.text.removeSurrounding("'") }
        if (args.size < 2) return
        val kind = args.getOrNull(2)?.uppercase()
        val old = args[0].split('.').map { it.removeSurrounding("[", "]") }
        if (kind == "COLUMN" && old.size >= 2) out.add(LbChange.RenameColumn(old[old.size - 2], old.last(), args[1], null))
        else if (kind == null || kind == "OBJECT") out.add(LbChange.RenameTable(old.last(), args[1]))
    }

    // ---- pieces ---------------------------------------------------------------------------------------

    private val VERBS = setOf("ADD", "DROP", "RENAME", "ALTER", "MODIFY", "CHANGE")
    private val TABLE_CONSTRAINTS = setOf("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "INDEX", "KEY", "FULLTEXT", "SPATIAL", "EXCLUDE", "LIKE", "PERIOD")
    /** Where a column's type ends and its constraints begin. `WITH` is not one: `TIMESTAMP WITH TIME ZONE`. */
    private val TYPE_STOP = setOf(
        "NOT", "NULL", "DEFAULT", "PRIMARY", "REFERENCES", "UNIQUE", "CHECK", "CONSTRAINT", "AUTO_INCREMENT",
        "AUTOINCREMENT", "IDENTITY", "GENERATED", "COLLATE", "COMMENT", "FIRST", "AFTER", "ENCODE", "USING",
    )

    /** One column definition — `NAME type [constraints]` — or null for a table constraint. */
    private fun columnDef(d: List<Tok>): LbColumn? {
        if (d.isEmpty() || d[0].up in TABLE_CONSTRAINTS) return null
        return LbColumn(d[0].text, typeOf(d, 1))
    }

    /** The type starting at [from]: tokens up to the first constraint word outside parentheses. */
    private fun typeOf(t: List<Tok>, from: Int): String? {
        val sb = StringBuilder()
        var depth = 0
        var i = from
        while (i < t.size) {
            val tok = t[i]
            if (depth == 0 && tok.up in TYPE_STOP) break
            when (tok.text) {
                "(" -> { depth++; sb.append('(') }
                ")" -> { depth--; sb.append(')') }
                "," -> sb.append(',')
                else -> {
                    if (sb.isNotEmpty() && sb.last() != '(' && sb.last() != ',') sb.append(' ')
                    sb.append(tok.text)
                }
            }
            i++
        }
        return sb.toString().ifEmpty { null }
    }

    /** `schema.table` → `table`, returning the index after the name. */
    private fun qualifiedName(t: List<Tok>, from: Int): Pair<String, Int> {
        var i = from
        var name = t[i].text
        i++
        while (i + 1 < t.size && t[i].text == ".") { name = t[i + 1].text; i += 2 }
        return name to i
    }

    private fun skipIf(t: List<Tok>, at: Int, vararg words: String): Int {
        if (at + words.size > t.size) return at
        for ((k, w) in words.withIndex()) if (t[at + k].up != w) return at
        return at + words.size
    }

    /** The index of the `)` closing the `(` at [open]. */
    private fun matching(t: List<Tok>, open: Int): Int {
        var depth = 0
        for (i in open until t.size) {
            if (t[i].text == "(") depth++
            if (t[i].text == ")") { depth--; if (depth == 0) return i }
        }
        return t.size
    }

    /** The tokens between [from] and [to], split at the commas outside parentheses. */
    private fun split(t: List<Tok>, from: Int, to: Int): List<List<Tok>> {
        val out = ArrayList<List<Tok>>()
        var cur = ArrayList<Tok>()
        var depth = 0
        for (i in from until minOf(to, t.size)) {
            val tok = t[i]
            if (tok.text == "(") depth++
            if (tok.text == ")") depth--
            if (depth == 0 && tok.text == ",") { out.add(cur); cur = ArrayList(); continue }
            cur.add(tok)
        }
        out.add(cur)
        return out
    }
}
