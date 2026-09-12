package com.flowable.atlas.navigation

import com.flowable.atlas.index.ModelEntry
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * The named **elements inside** a model that the index already carries per model — user tasks, flow
 * nodes, variables, messages, signals, an event's payload fields, a form's fields and outcomes — as
 * things a developer can search for by name. `approveTask` is what a Java test names in
 * `taskDefinitionKey("approveTask")` and what a log line shows; until now Search Everywhere knew the
 * process and not the task.
 */
object ModelElements {

    enum class Kind(val label: String, val icon: Icon) {
        USER_TASK("User task", AllIcons.Nodes.Tag),
        ACTIVITY("Activity", AllIcons.Nodes.Tag),
        VARIABLE("Variable", AllIcons.Nodes.Variable),
        MESSAGE("Message", AllIcons.Nodes.Constant),
        SIGNAL("Signal", AllIcons.Nodes.Constant),
        PAYLOAD("Payload field", AllIcons.Nodes.Parameter),
        FORM_FIELD("Form field", AllIcons.Nodes.Field),
        FORM_OUTCOME("Outcome", AllIcons.Nodes.Property),
        DECISION_VARIABLE("Decision variable", AllIcons.Nodes.Variable),
    }

    /** One element of one model. */
    data class Element(val owner: ModelEntry, val kind: Kind, val id: String) {
        /** `User task · in DEMO-P001` — the row's grey half and the Go to Symbol location. */
        val location: String get() = "${kind.label} · in ${owner.key}"
    }

    /** Every named element of [entry], activities that are also user tasks listed once as user tasks. */
    fun of(entry: ModelEntry): List<Element> {
        val m = entry.members
        val out = ArrayList<Element>()
        val userTasks = m.userTaskIds.toSet()
        fun add(kind: Kind, ids: Collection<String>) {
            for (id in ids) if (id.isNotBlank()) out.add(Element(entry, kind, id))
        }
        add(Kind.USER_TASK, m.userTaskIds)
        add(Kind.ACTIVITY, m.activityIds.filter { it !in userTasks })
        add(Kind.VARIABLE, m.variables)
        add(Kind.MESSAGE, m.messages)
        add(Kind.SIGNAL, m.signals)
        add(Kind.PAYLOAD, m.payload)
        add(Kind.FORM_FIELD, m.formFields)
        add(Kind.FORM_OUTCOME, m.formOutcomes)
        add(Kind.DECISION_VARIABLE, m.decisionVariables)
        return out
    }

    /**
     * Where [id] is declared in its model's [text]: the first quoted occurrence — `id="approve"` in XML,
     * `"id": "approve"` or `"name": "approve"` in JSON — or null when the text does not spell it so.
     */
    fun declarationOffset(text: String, id: String): Int? {
        for (quote in charArrayOf('"', '\'')) {
            val at = text.indexOf("$quote$id$quote")
            if (at >= 0) return at + 1
        }
        return null
    }
}
