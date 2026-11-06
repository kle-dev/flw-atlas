package com.flowable.atlas.parsing

/**
 * The pure value types produced by the per-file model extraction ([ModelMemberExtractor] /
 * [ModelJsonReader]) and consumed by both front-ends: the CLI/graph engine and the IDE's live
 * index. IntelliJ-free — the plugin's navigable `ModelEntry` (which holds a `VirtualFile`) wraps
 * a [RawModel] on the plugin side.
 */

/** Raw (key, name, members) extracted from a file before it is associated with a type/file. */
data class RawModel(
    val key: String,
    val name: String?,
    val members: ModelMembers = ModelMembers.EMPTY,
)

/**
 * The "members" of a single model that are useful as completion vocabularies:
 *  - [variables]: process/case variable names — BPMN `<dataObject>` names and `<flowable:formProperty>` ids,
 *    and for a form or page the variables its components bind ([FormBindings]), not their component ids.
 *  - [userTaskIds]: `<userTask id>` — candidates for `taskDefinitionKey(...)`.
 *  - [activityIds]: ids of every flow node — candidates for `activityId(...)`.
 *  - [messages] / [signals]: BPMN top-level `<message name>` / `<signal name>` (file-level, copied onto
 *    every model in that file so a project-wide union can be built cheaply), plus a bare `messageRef` /
 *    `signalRef` that names no `<message id>` / `<signal id>` — the engine then uses the ref itself —
 *    and the `signalRef` of a case's signal event listener, which the same signals reach.
 *  - [dynamicMessages] / [dynamicSignals]: the file computes a message / signal name at run time
 *    (`flowable:messageExpression`, a name holding `${…}`), so its names are not all known here.
 *  - [decisionVariables]: DMN input/output clause variable names — candidates for `variable(...)`.
 *  - [payload]: event payload + correlation parameter names.
 *  - [formFields] / [formOutcomes]: form field / outcome ids — what navigation lands on.
 *  - [botKey]: the `botKey` of an `.action` model — the Java `BotService` (`getKey()`) it invokes.
 */
data class ModelMembers(
    val variables: List<String> = emptyList(),
    val userTaskIds: List<String> = emptyList(),
    val activityIds: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val signals: List<String> = emptyList(),
    val decisionVariables: List<String> = emptyList(),
    val payload: List<String> = emptyList(),
    val formFields: List<String> = emptyList(),
    val formOutcomes: List<String> = emptyList(),
    val botKey: String? = null,
    val dynamicMessages: Boolean = false,
    val dynamicSignals: Boolean = false,
) {
    companion object {
        val EMPTY = ModelMembers()
    }
}

/** Form field ids, outcomes and bound variables of a `.form` / `.page` model (used to build member vocabularies). */
data class FormInfo(val fields: List<String>, val outcomes: List<String>, val variables: List<String> = emptyList())

/** An operation of a service model (a data object's backing service). */
data class OperationInfo(
    val key: String,
    val name: String?,
    val type: String?,
    val inputParameters: List<ParamInfo>,
)

/** One input value field of a service operation. */
data class ParamInfo(
    val name: String,
    val type: String?,
    val required: Boolean,
)

/**
 * The physical-table mapping of a Service Registry `.service` model (read on demand for the
 * Liquibase-coverage inspection). Only `type == "database"` services carry a real table.
 */
data class ServiceTable(
    val key: String,
    val type: String?,
    val tableName: String?,
    val referenceKey: String?,
    val referencedLiquibaseModelKey: String?,
    val columns: List<ColumnMapping>,
    /** The logical name of the mapping that identifies a row — its column is the table's primary key. */
    val lookupId: String? = null,
) {
    val isDatabase: Boolean get() = type.equals("database", ignoreCase = true)
}

/**
 * One `columnMappings[]` entry of a `.service` model: logical [name] ↔ physical [columnName]. A mapping
 * with a [relation] is not a column of the table: it is held in that relation's join table.
 */
data class ColumnMapping(
    val name: String?,
    val columnName: String?,
    val type: String?,
    val relation: ColumnRelation? = null,
)

/** The join table of a related data object: [owningColumnName] holds this row's id, [referenceColumnName] the other's. */
data class ColumnRelation(
    val joinTableName: String?,
    val owningColumnName: String?,
    val referenceColumnName: String?,
)

/**
 * The logical field mapping of a Data Object `.data` model (read on demand). Only
 * `serviceRegistryDataObject` / `databaseSchemaDataObject` are table-backed; `masterData` and
 * internal `dataObject` are EAV and irrelevant for Liquibase coverage.
 */
data class DataObjectInfo(
    val key: String,
    val dataObjectType: String?,
    val referencedServiceDefinitionModelKey: String?,
    val fieldMappings: List<DataField>,
) {
    /** Field names only (kept for callers that don't need the types). */
    val fields: List<String> get() = fieldMappings.map { it.name }

    val isTableBacked: Boolean
        get() = dataObjectType == "serviceRegistryDataObject" || dataObjectType == "databaseSchemaDataObject"
}

/** One `fieldMappings[]` entry of a `.data` model: the logical field [name] and its logical [type]. */
data class DataField(val name: String, val type: String?)
