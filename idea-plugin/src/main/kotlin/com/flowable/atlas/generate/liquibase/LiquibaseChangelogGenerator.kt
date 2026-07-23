package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.parsing.DataField

/**
 * Pure (no `com.intellij.*`, no I/O) builders for the "Generate → Liquibase" feature:
 *
 *  - [synthesize] — the fallback changelog for a data object that ships no bundled Liquibase model:
 *    a single `createTable` with one `<column>` per field, typed via the shared
 *    [LiquibaseChangelog.liquibaseType]. Like the changelogs Flowable Design bundles, it references
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
     * A minimal changelog for the data object [key] backed by physical table [tableName]: one
     * `createTable` change with a `<column>` per entry of [fields]. Best-effort scaffold — the
     * developer is expected to review it (e.g. add a primary key / constraints).
     */
    fun synthesize(key: String, tableName: String, fields: List<DataField>): String {
        val table = tableName.ifBlank { key }
        val columns = fields.joinToString("\n") { f ->
            val type = LiquibaseChangelog.liquibaseType(f.type) ?: DEFAULT_TYPE
            "                <column name=\"${xml(f.name)}\" type=\"${xml(type)}\"/>"
        }
        val body = if (columns.isEmpty()) "" else "\n$columns\n            "
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.6.xsd">
            <changeSet id="1" author="flowable-atlas">
                <createTable tableName="${xml(table)}">$body</createTable>
            </changeSet>
        </databaseChangeLog>
        """.trimIndent() + "\n"
    }

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
