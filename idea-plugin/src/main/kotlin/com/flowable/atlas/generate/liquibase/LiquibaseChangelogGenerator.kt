package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.parsing.DataField
import com.flowable.atlas.parsing.ServiceTable

/**
 * Pure (no `com.intellij.*`, no I/O) builders for the "Generate → Liquibase" feature:
 *
 *  - [fromService] — the changelog for a data object that ships no bundled Liquibase model, built the way
 *    Flowable Design builds it: from the backing service's column mappings, and [synthesize] — the guess
 *    from the data object's own field names, for one with no database service behind it. Both are typed
 *    via the shared [LiquibaseChangelog.liquibaseType]. Like the changelogs Flowable Design bundles, they reference
 *    the `${'$'}{varchar.type}` / `${'$'}{datetime.type}` properties without declaring them — the
 *    Flowable engine supplies that property context when it auto-runs the master changelog.
 *  - [masterSkeleton] + [withInclude] — the master `flowable-project-db-changelog.xml` skeleton and a
 *    pure, idempotent insertion of a single `<include>` line. Kept pure so the master-mutation logic
 *    (the part that must never rewrite the file destructively) is unit-testable on its own.
 */
object LiquibaseChangelogGenerator {

    /** The master changelog Flowable auto-runs by convention (no property/config needed). */
    const val MASTER_CHANGELOG = "flowable-project-db-changelog.xml"

    /** The type emitted for a field whose logical type has no [LiquibaseChangelog.liquibaseType] mapping. */
    private const val DEFAULT_TYPE = "\${varchar.type}(255)"

    /** The minimal, empty-bodied master changelog (Liquibase 3.6 schema). */
    fun masterSkeleton(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.6.xsd">
        </databaseChangeLog>
        """.trimIndent() + "\n"

    /** True when [masterXml] already includes [includeFileName] (so re-running is a no-op). */
    fun includeExists(masterXml: String, includeFileName: String): Boolean =
        Regex("<include\\b[^>]*\\bfile\\s*=\\s*\"${Regex.escape(includeFileName)}\"").containsMatchIn(masterXml)

    /**
     * [masterXml] with an `<include file="[includeFileName]" relativeToChangelogFile="true"/>` inserted
     * just before the closing `</databaseChangeLog>`. Returns [masterXml] unchanged when the include is
     * already present (idempotent) or when there is no closing tag to anchor to (never rewrites
     * destructively — only ever adds the one missing line).
     */
    fun withInclude(masterXml: String, includeFileName: String): String {
        if (includeExists(masterXml, includeFileName)) return masterXml
        val close = Regex("</databaseChangeLog\\s*>").findAll(masterXml).lastOrNull() ?: return masterXml
        val line = "    <include file=\"$includeFileName\" relativeToChangelogFile=\"true\"/>\n"
        return masterXml.substring(0, close.range.first) + line + masterXml.substring(close.range.first)
    }

    /**
     * The changelog of [service]'s table, as Flowable Design writes it (`LiquibaseModelResourceServiceImpl`):
     * a column per column mapping, named by its physical `columnName` — the names the service reads and
     * writes — the `lookupId` mapping's column as the string primary key, and each relation's join table
     * in a change set of its own, guarded by `tableExists` (either side of a relation may create it) and
     * indexed on both columns. A mapping with a relation is not a column of this table. Null when the
     * service maps no table or no column to build from.
     */
    fun fromService(service: ServiceTable): String? {
        val table = service.tableName?.takeIf { it.isNotBlank() } ?: return null
        val own = tableColumns(service)
        if (own.isEmpty()) return null
        val columns = own.joinToString("\n") { c ->
            if (c.name != null && c.name == service.lookupId) {
                "$COLUMN<column name=\"${xml(c.columnName!!)}\" type=\"${xml(DEFAULT_TYPE)}\">\n" +
                    "$COLUMN    <constraints nullable=\"false\" primaryKey=\"true\"/>\n" +
                    "$COLUMN</column>"
            } else {
                column(c.columnName!!, c.type)
            }
        }
        val changeSets = StringBuilder(changeSet("1", "", table, columns, ""))
        service.columns.mapNotNull { it.relation }
            .filter { !it.joinTableName.isNullOrBlank() && !it.owningColumnName.isNullOrBlank() && !it.referenceColumnName.isNullOrBlank() }
            .distinctBy { it.joinTableName!!.lowercase() }
            .forEachIndexed { i, r ->
                val join = r.joinTableName!!
                val joinColumns = listOf(r.owningColumnName!!, r.referenceColumnName!!)
                val guard = "        <preConditions onFail=\"MARK_RAN\">\n" +
                    "            <not>\n" +
                    "                <tableExists tableName=\"${xml(join)}\"/>\n" +
                    "            </not>\n" +
                    "        </preConditions>\n"
                val indexes = joinColumns.mapIndexed { j, name ->
                    "\n        <createIndex tableName=\"${xml(join)}\" indexName=\"${xml("FLW_IDX_$join${j + 1}")}\">\n" +
                        "            <column name=\"${xml(name)}\"/>\n" +
                        "        </createIndex>"
                }.joinToString("")
                changeSets.append('\n').append(
                    changeSet((i + 2).toString(), guard, join, joinColumns.joinToString("\n") { column(it, "STRING") }, indexes),
                )
            }
        return changelog(changeSets.toString())
    }

    /** The mappings of [service] that are columns of its own table: named, and not held in a join table. */
    fun tableColumns(service: ServiceTable) = service.columns.filter { it.relation == null && !it.columnName.isNullOrBlank() }

    /**
     * A changelog for the data object [key] guessed from its own [fields]: one `createTable` of
     * [tableName] with a `<column>` per field, named as the field. For a data object with no database
     * service behind it only — a service names its columns itself, and they rarely equal the field names
     * ([fromService]). Best-effort scaffold: the developer is expected to review it (a primary key,
     * constraints).
     */
    fun synthesize(key: String, tableName: String, fields: List<DataField>): String {
        val columns = fields.joinToString("\n") { f -> column(f.name, f.type) }
        return changelog(changeSet("1", "", tableName.ifBlank { key }, columns, ""))
    }

    private const val COLUMN = "            "

    private fun column(name: String, logicalType: String?): String {
        val type = LiquibaseChangelog.liquibaseType(logicalType) ?: DEFAULT_TYPE
        return "$COLUMN<column name=\"${xml(name)}\" type=\"${xml(type)}\"/>"
    }

    private fun changeSet(id: String, guard: String, table: String, columns: String, after: String): String {
        val body = if (columns.isEmpty()) "" else "\n$columns\n        "
        return "    <changeSet id=\"$id\" author=\"flowable-atlas\">\n" +
            guard +
            "        <createTable tableName=\"${xml(table)}\">$body</createTable>$after\n" +
            "    </changeSet>"
    }

    private fun changelog(changeSets: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.6.xsd">
        """.trimIndent() + "\n" + changeSets + "\n</databaseChangeLog>\n"

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
