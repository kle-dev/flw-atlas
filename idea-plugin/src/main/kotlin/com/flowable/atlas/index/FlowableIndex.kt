package com.flowable.atlas.index

import com.flowable.atlas.model.ModelType

/**
 * An immutable snapshot of all Flowable models found in the project, keyed by model key.
 * A single key may map to several files (Design workspace + deployment artifact); for
 * completion we expose one distinct entry per (type, key).
 *
 * Besides keys it also carries project-wide "vocabularies" harvested from the models — variable
 * names, message/signal names, task-definition keys and activity ids — used by the non-key
 * completion domains.
 */
class FlowableIndex(
    private val byKey: Map<String, List<ModelEntry>>,
    /** Identifiers (bean names, method names) referenced from model expressions like `${bean.method()}`. */
    val referencedIdentifiers: Set<String> = emptySet(),
    /** Fully-qualified class names referenced from models (delegate/class attributes). */
    val referencedClassFqns: Set<String> = emptySet(),
    /** Project-wide process/case variable names (BPMN dataObjects, formProperties, form fields). */
    val variables: Set<String> = emptySet(),
    /** BPMN top-level `<message name>` values. */
    val messages: Set<String> = emptySet(),
    /** BPMN top-level `<signal name>` values. */
    val signals: Set<String> = emptySet(),
    /** `<userTask id>` values — candidates for `taskDefinitionKey(...)`. */
    val userTaskIds: Set<String> = emptySet(),
    /** Ids of every flow node — candidates for `activityId(...)`. */
    val activityIds: Set<String> = emptySet(),
) {

    private val distinctByType: Map<ModelType, List<ModelEntry>> by lazy {
        byKey.values.asSequence().flatten()
            .groupBy { it.type }
            .mapValues { (_, list) -> list.distinctBy { it.key }.sortedBy { it.key } }
    }

    /** Distinct models of a type (one per key), sorted by key — the completion candidate set. */
    fun keysOfType(type: ModelType): List<ModelEntry> = distinctByType[type] ?: emptyList()

    /** All files declaring a given key (across representations). */
    fun find(key: String): List<ModelEntry> = byKey[key].orEmpty()

    /** First entry of a specific type for a key, if any. */
    fun find(key: String, type: ModelType): ModelEntry? =
        byKey[key]?.firstOrNull { it.type == type }

    /** Members (variables, decision variables, payload, …) of a specific model, if indexed. */
    fun membersOf(key: String, type: ModelType): ModelMembers? = find(key, type)?.members

    fun allDistinct(): List<ModelEntry> = distinctByType.values.flatten()

    fun distinctCount(): Int = distinctByType.values.sumOf { it.size }
}
