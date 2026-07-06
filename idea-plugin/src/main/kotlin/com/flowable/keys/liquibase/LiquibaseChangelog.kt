package com.flowable.keys.liquibase

/**
 * Parsing + replay of Liquibase changelogs, ported from `flowable_atlas.py`
 * (`_liquibase_ops` / `_liquibase_replay` / `_loose` / `_liquibase_key`). Regex-based over the raw
 * text — no XSD, no Liquibase runtime — so it works on any changelog the IDE has open.
 *
 * The point is to line a changelog's columns up against the Flowable `.service` model that generated
 * it: a column present in the changelog but absent from the service's `columnMappings` is a schema
 * drift the coverage inspection flags.
 */
object LiquibaseChangelog {

    sealed class Op {
        /** `<createTable>` / `<addColumn>` — declares [columns] on [table]. */
        data class TableColumns(val kind: String, val table: String?, val columns: List<Column>) : Op()
        data class RenameColumn(val table: String?, val oldName: String, val newName: String, val type: String?) : Op()
        data class DropColumn(val table: String?, val columns: List<String>) : Op()
        data class ModifyType(val table: String?, val column: String, val type: String?) : Op()
        data class RenameTable(val oldTable: String, val newTable: String) : Op()
        data class DropTable(val table: String?) : Op()
    }

    data class Column(val name: String, val type: String?)

    private val BLOCK = Regex("<(createTable|addColumn)\\b([^>]*?)>(.*?)</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val COLUMN = Regex("<column\\b([^>]*?)/?>")
    private val RENAME_COL = Regex("<renameColumn\\b([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val DROP_COL = Regex("<dropColumn\\b([^>]*?)(?:/>|>(.*?)</dropColumn\\s*>)", setOf(RegexOption.DOT_MATCHES_ALL))
    private val MODIFY_TYPE = Regex("<modifyDataType\\b([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val RENAME_TABLE = Regex("<renameTable\\b([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val DROP_TABLE = Regex("<dropTable\\b([^>]*?)/?>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val SERVICE_REFS = Regex("name=\"serviceDefinitionReferences\"\\s+value=\"([^\"]*)\"")
    private val TABLE_NAME = Regex("tableName=\"([^\"]+)\"")

    /** Parse a changelog into an ordered list of schema-change ops (document order preserved). */
    fun parseOps(text: String): List<Op> {
        val found = ArrayList<Pair<Int, Op>>()
        for (m in BLOCK.findAll(text)) {
            val cols = COLUMN.findAll(m.groupValues[3]).mapNotNull { cm ->
                val nm = attr(cm.groupValues[1], "name") ?: return@mapNotNull null
                Column(nm, attr(cm.groupValues[1], "type"))
            }.toList()
            if (cols.isNotEmpty()) {
                found.add(m.range.first to Op.TableColumns(m.groupValues[1], attr(m.groupValues[2], "tableName"), cols))
            }
        }
        for (m in RENAME_COL.findAll(text)) {
            val a = m.groupValues[1]
            val old = attr(a, "oldColumnName")
            val new = attr(a, "newColumnName")
            if (old != null && new != null) {
                found.add(m.range.first to Op.RenameColumn(attr(a, "tableName"), old, new, attr(a, "columnDataType")))
            }
        }
        for (m in DROP_COL.findAll(text)) {
            val a = m.groupValues[1]
            val names = ArrayList<String>()
            attr(a, "columnName")?.let { names.add(it) }
            COLUMN.findAll(m.groupValues.getOrElse(2) { "" }).forEach { cm -> attr(cm.groupValues[1], "name")?.let { names.add(it) } }
            if (names.isNotEmpty()) found.add(m.range.first to Op.DropColumn(attr(a, "tableName"), names))
        }
        for (m in MODIFY_TYPE.findAll(text)) {
            val a = m.groupValues[1]
            attr(a, "columnName")?.let { found.add(m.range.first to Op.ModifyType(attr(a, "tableName"), it, attr(a, "newDataType"))) }
        }
        for (m in RENAME_TABLE.findAll(text)) {
            val a = m.groupValues[1]
            val old = attr(a, "oldTableName")
            val new = attr(a, "newTableName")
            if (old != null && new != null) found.add(m.range.first to Op.RenameTable(old, new))
        }
        for (m in DROP_TABLE.findAll(text)) {
            attr(m.groupValues[1], "tableName")?.let { found.add(m.range.first to Op.DropTable(it)) }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * The loose (renamed-from + dropped) column names — those a later op mutates away, so a
     * `createTable` column with that name must NOT be flagged as unmapped at its declaration.
     */
    fun mutatedLooseNames(ops: List<Op>): Set<String> {
        val out = HashSet<String>()
        for (op in ops) when (op) {
            is Op.RenameColumn -> out.add(loose(op.oldName))
            is Op.DropColumn -> op.columns.forEach { out.add(loose(it)) }
            else -> {}
        }
        return out
    }

    /**
     * The loose names of every column the changelog *declares as surviving* (created/added/renamed-to)
     * that is NOT covered by [serviceColumnsLoose]. These are the drift candidates.
     */
    fun unmappedLooseNames(ops: List<Op>, serviceColumnsLoose: Set<String>): Set<String> {
        val mutated = mutatedLooseNames(ops)
        val out = LinkedHashSet<String>()
        for (op in ops) when (op) {
            is Op.TableColumns -> op.columns.forEach {
                val l = loose(it.name)
                if (l !in serviceColumnsLoose && l !in mutated) out.add(l)
            }
            is Op.RenameColumn -> {
                val l = loose(op.newName)
                if (l !in serviceColumnsLoose && l !in mutated) out.add(l)
            }
            else -> {}
        }
        return out
    }

    /** Service definition keys the changelog names in its `serviceDefinitionReferences` property. */
    fun serviceReferences(text: String): Set<String> =
        SERVICE_REFS.findAll(text)
            .flatMap { it.groupValues[1].split(Regex("[,\\s]+")) }
            .filter { it.isNotBlank() }
            .toSet()

    /** All `tableName="…"` values occurring in the changelog. */
    fun tableNames(text: String): Set<String> =
        TABLE_NAME.findAll(text).map { it.groupValues[1] }.toSet()

    /**
     * Loose column-identity key: lowercase, drop every non-alphanumeric. Bridges the SQL ↔ logical
     * naming gap so the same field lines up across layers (`CREW_ID_` == `crewId` == `crew_id`).
     */
    fun loose(s: String?): String = (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * The liquibase model key a changelog filename encodes (the target of a service's
     * `referencedLiquibaseModelKey`), e.g. `liquibase-APP-L003.data.changelog.xml` → `APP-L003`.
     */
    fun changelogKey(fileName: String): String {
        var base = fileName.substringAfterLast('!').substringAfterLast('/')
        base = base.removePrefix("liquibase-")
        return base
            .replace(Regex("\\.data\\.changelog\\.xml$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.changelog\\.xml$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.xml$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.sql$", RegexOption.IGNORE_CASE), "")
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /**
     * The Liquibase column `type` Flowable Design generates for a service `columnMappings[].type`,
     * ported from `LiquibaseModelResourceServiceImpl.asLiquibaseColumnType` (the `${'$'}{varchar.type}` /
     * `${'$'}{datetime.type}` placeholders are the properties every generated changelog declares).
     */
    fun liquibaseType(logicalType: String?): String? = when (logicalType?.uppercase()) {
        "STRING" -> "\${varchar.type}(255)"
        "INT", "INTEGER" -> "integer"
        "LONG" -> "bigint"
        "DOUBLE" -> "double"
        "BOOLEAN", "BOOL" -> "bool"
        "DATE" -> "\${datetime.type}(6)"
        "LOCAL-DATE", "LOCALDATE" -> "date"
        "JSON" -> "longtext"
        else -> null
    }

    /** The full set of Liquibase types Flowable emits — offered as a fallback palette. */
    val TYPE_PALETTE: List<String> = listOf(
        "\${varchar.type}(255)", "integer", "bigint", "double", "bool", "\${datetime.type}(6)", "date", "longtext",
    )
}
