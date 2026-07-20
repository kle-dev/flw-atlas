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
 *  - [variables]: process/case variable names (BPMN `<dataObject>` names + `<flowable:formProperty>` ids
 *    + referenced/embedded form field ids).
 *  - [userTaskIds]: `<userTask id>` — candidates for `taskDefinitionKey(...)`.
 *  - [activityIds]: ids of every flow node — candidates for `activityId(...)`.
 *  - [messages] / [signals]: BPMN top-level `<message name>` / `<signal name>` (file-level, copied onto
 *    every model in that file so a project-wide union can be built cheaply).
 *  - [decisionVariables]: DMN input/output clause variable names — candidates for `variable(...)`.
 *  - [payload]: event payload + correlation parameter names.
 *  - [formFields] / [formOutcomes]: form field / outcome ids.
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
) {
    companion object {
        val EMPTY = ModelMembers()
    }
}

/** Form field ids + outcomes of a `.form` model (used to build member vocabularies). */
data class FormInfo(val fields: List<String>, val outcomes: List<String>)

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
) {
    val isDatabase: Boolean get() = type.equals("database", ignoreCase = true)
}

/** One `columnMappings[]` entry of a `.service` model: logical [name] ↔ physical [columnName]. */
data class ColumnMapping(
    val name: String?,
    val columnName: String?,
    val type: String?,
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
