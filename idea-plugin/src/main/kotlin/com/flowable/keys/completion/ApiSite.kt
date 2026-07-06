package com.flowable.keys.completion

import com.flowable.keys.model.ModelType

/**
 * A Flowable public-API call position whose String argument is a model key, an operation name,
 * or a value-field name. Matched by (declaring interface FQN + method name + argument index).
 *
 *  - [receiverFqn] is the FQN of the interface that *declares* the method (e.g. `TaskInfoQuery`);
 *    subinterfaces are matched via inheritance.
 */
sealed class ApiSite {
    abstract val receiverFqn: String
    abstract val methodName: String
    abstract val argIndex: Int
}

/** The argument is a model key of one of [targetTypes]. */
data class KeySite(
    override val receiverFqn: String,
    override val methodName: String,
    override val argIndex: Int,
    val targetTypes: List<ModelType>,
) : ApiSite()

/**
 * The argument is an operation name. The operation set comes from the model referenced by the
 * sibling [keyMethod] call in the same fluent chain: a data object (resolved to its backing
 * service) unless [keyIsService].
 */
data class OperationSite(
    override val receiverFqn: String,
    override val methodName: String,
    override val argIndex: Int,
    val keyMethod: String,
    val keyIsService: Boolean = false,
) : ApiSite()

/**
 * The argument is an input value-field name of the operation selected by [operationMethod],
 * on the model referenced by [keyMethod] (a data object unless [keyIsService]).
 */
data class ValueSite(
    override val receiverFqn: String,
    override val methodName: String,
    override val argIndex: Int,
    val keyMethod: String,
    val operationMethod: String,
    val keyIsService: Boolean = false,
) : ApiSite()

/** A project-wide vocabulary harvested from the models (not tied to a specific model key). */
enum class Vocabulary(val display: String) {
    MESSAGE("Message"),
    SIGNAL("Signal"),
    VARIABLE("Variable"),
    USER_TASK("Task key"),
    ACTIVITY("Activity id"),
}

/**
 * The argument is a value from a [vocabulary] — e.g. a message/signal name, a process variable
 * name, a task-definition key or an activity id.
 *
 * By default it is offered as the union across all models. When [scopeKeyMethods] is non-empty and
 * the same fluent chain carries one of those key calls (e.g. `processDefinitionKey("X")`), the
 * vocabulary is narrowed to the members of that single model (of one of [scopeTypes]) — so
 * `…processDefinitionKey("X").taskDefinitionKey("<caret>")` offers only process X's task keys. Falls
 * back to the project-wide union when no such sibling key is present.
 */
data class VocabularySite(
    override val receiverFqn: String,
    override val methodName: String,
    override val argIndex: Int,
    val vocabulary: Vocabulary,
    val scopeKeyMethods: List<String> = emptyList(),
    val scopeTypes: List<ModelType> = emptyList(),
) : ApiSite()

/**
 * The argument is a member (a "decision variable" or an "event payload field") of the model
 * referenced by the sibling [keyMethod] call in the same fluent chain / call. [memberKind] selects
 * which member list of that model to offer.
 */
enum class MemberKind { DECISION_VARIABLE, EVENT_PAYLOAD }

data class MemberSite(
    override val receiverFqn: String,
    override val methodName: String,
    override val argIndex: Int,
    val keyMethod: String,
    val keyArgIndex: Int,
    val memberKind: MemberKind,
) : ApiSite()
