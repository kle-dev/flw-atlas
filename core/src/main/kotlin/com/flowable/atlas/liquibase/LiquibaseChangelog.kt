package com.flowable.atlas.liquibase

/**
 * One changelog's schema changes as the IDE inspection needs them, read by [LiquibaseParser] — the reader
 * the whole Liquibase coverage uses, so the editor and the explorer see the same change sets.
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

    private val SERVICE_REFS = Regex("name=\"serviceDefinitionReferences\"\\s+value=\"([^\"]*)\"")
    private val TABLE_NAME = Regex("tableName=\"([^\"]+)\"")

    /**
     * The schema-change ops of a changelog's change sets, in document order — read by [LiquibaseParser],
     * so a change inside a `<rollback>` or a change set commented out is not one. Empty for text that is
     * not a well-formed changelog (a file mid-edit): no verdict beats one read off half a file.
     */
    fun parseOps(text: String): List<Op> {
        val changelog = try { LiquibaseParser.parse(text) } catch (e: LiquibaseParser.ParseException) { null } ?: return emptyList()
        return changelog.changeSets.flatMap { cs -> cs.changes.mapNotNull(::op) }
    }

    private fun op(c: LbChange): Op? = when (c) {
        is LbChange.CreateTable -> Op.TableColumns("createTable", c.table, c.columns.map { Column(it.name, it.type) }).takeIf { c.columns.isNotEmpty() }
        is LbChange.AddColumn -> Op.TableColumns("addColumn", c.table, c.columns.map { Column(it.name, it.type) }).takeIf { c.columns.isNotEmpty() }
        is LbChange.RenameColumn -> Op.RenameColumn(c.table, c.oldName, c.newName, c.type)
        is LbChange.DropColumn -> Op.DropColumn(c.table, c.columns)
        is LbChange.ModifyDataType -> Op.ModifyType(c.table, c.column, c.type)
        is LbChange.RenameTable -> Op.RenameTable(c.oldName, c.newName)
        is LbChange.DropTable -> Op.DropTable(c.table)
        // a key changes which columns are special, not which exist — nothing for the coverage inspection
        is LbChange.SqlFile, is LbChange.Unread, is LbChange.PrimaryKey, is LbChange.DropPrimaryKey -> null
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

    /**
     * The Liquibase column `type` Flowable Design generates for a service `columnMappings[].type`,
     * the same mapping Design applies when it generates a changelog (the `${'$'}{varchar.type}` /
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
