package com.flowable.atlas.liquibase

/*
 * A Liquibase changelog as [LiquibaseParser] reads it: only what decides which tables and columns exist
 * once it has run, and what decides whether a change set runs at all. Data changes (`insert`, `loadData`),
 * indexes, constraints and views change no column list, so they are not modelled — the tables they name
 * are kept in [LbChangeSet.tables] only, which is what links a changelog to the table it fills.
 */

/** A column as a change declares it. [type] is the raw type, `${'$'}{varchar.type}(255)` included; [pk] when
 *  the declaration makes it (part of) the primary key — an inline constraint, not a later `addPrimaryKey`. */
data class LbColumn(val name: String, val type: String?, val pk: Boolean = false)

/** One schema change of a change set. Table and column names are as written, properties unexpanded. */
sealed class LbChange {
    data class CreateTable(val table: String, val columns: List<LbColumn>) : LbChange()
    data class AddColumn(val table: String, val columns: List<LbColumn>) : LbChange()
    data class DropColumn(val table: String, val columns: List<String>) : LbChange()
    data class RenameColumn(val table: String, val oldName: String, val newName: String, val type: String?) : LbChange()
    data class ModifyDataType(val table: String, val column: String, val type: String?) : LbChange()
    data class RenameTable(val oldName: String, val newName: String) : LbChange()
    data class DropTable(val table: String) : LbChange()
    /** `<addPrimaryKey>` / `PRIMARY KEY (…)`: [columns] are the table's primary key from here on. */
    data class PrimaryKey(val table: String, val columns: List<String>) : LbChange()
    /** `<dropPrimaryKey>` / `DROP PRIMARY KEY`: the table has none any more. */
    data class DropPrimaryKey(val table: String) : LbChange()
    /** `<sqlFile path="…">`: resolved to the SQL file when the change set runs, its DDL read like `<sql>`. */
    data class SqlFile(val path: String, val relative: Boolean) : LbChange()
    /** A change that may alter a table in a way Atlas does not read (`customChange`, `CREATE TABLE … AS SELECT`). */
    data class Unread(val what: String) : LbChange()
}

/**
 * A precondition, evaluated against the tables the replay has built so far. [Unknown] stands for a check
 * that needs a live database (`sqlCheck`, `dbms`, `runningAs`, …): it neither passes nor fails, so a change
 * set guarded only by one runs, as it does on the database its author wrote it for.
 */
sealed class LbPrecondition {
    data class TableExists(val table: String) : LbPrecondition()
    data class ColumnExists(val table: String, val column: String) : LbPrecondition()
    data class ChangeSetExecuted(val id: String, val author: String, val file: String?) : LbPrecondition()
    /** Liquibase's `<not>` negates the *and* of its children. */
    data class Not(val all: List<LbPrecondition>) : LbPrecondition()
    data class And(val all: List<LbPrecondition>) : LbPrecondition()
    data class Or(val any: List<LbPrecondition>) : LbPrecondition()
    data class Unknown(val kind: String) : LbPrecondition()
}

/** A `<preConditions>` block: the *and* of [all]; [onFail] is Liquibase's, upper-cased, `HALT` by default. */
data class LbPreconditions(val onFail: String, val all: List<LbPrecondition>)

data class LbChangeSet(
    val id: String,
    val author: String,
    /** The change set's own `logicalFilePath`, which wins over the changelog's. */
    val logicalFilePath: String?,
    /** 1-based line of the `<changeSet` tag (or `--changeset` directive), 0 when unknown. */
    val line: Int,
    val preconditions: LbPreconditions?,
    val changes: List<LbChange>,
    /** Every table the change set names — its schema changes and its data changes alike. */
    val tables: List<String>,
    /** The change set's content with whitespace and comments normalised away: two copies of one change
     *  set compare equal exactly when they would do the same thing. */
    val signature: String,
)

sealed class LbEntry {
    data class ChangeSet(val changeSet: LbChangeSet) : LbEntry()
    data class Include(val file: String, val relative: Boolean) : LbEntry()
    data class IncludeAll(val path: String, val relative: Boolean) : LbEntry()
}

data class LbChangelog(
    val logicalFilePath: String?,
    /** `<property>` values, the dbms-independent one of each name first — see [LiquibaseParser]. */
    val properties: Map<String, String>,
    /** Change sets and includes in document order: the order Liquibase runs them in. */
    val entries: List<LbEntry>,
    /** The Flowable services the changelog says it backs (its `serviceDefinitionReferences` property). */
    val serviceRefs: List<String>,
) {
    val changeSets: List<LbChangeSet> get() = entries.mapNotNull { (it as? LbEntry.ChangeSet)?.changeSet }
    val includes: Boolean get() = entries.any { it !is LbEntry.ChangeSet }
}
