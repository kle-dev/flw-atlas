package com.flowable.atlas.graph
import com.flowable.atlas.model.Dyn
/**
 * Which variables are written but never read — the one place that verdict is defined.
 *
 * Runs after the edges are resolved, because the cross-model half needs them, and stamps its conclusion
 * onto the `variable` nodes so [Findings], the Markdown renderers and the explorer all read one answer
 * instead of each deriving its own.
 *
 * The rule is deliberately lopsided. A read Atlas can **prove** counts. A read it merely **suspects**
 * counts. A construct whose direction it **cannot determine** counts as a read too. Only when Atlas has
 * seen a write and has seen nothing that could possibly be a read does it speak. [SILENCE_RULES] is the
 * enumeration of the second case, and it is not optional polish: without it the first report on a real
 * project lists `total`, `data`, and every variable of any process whose bean reads the whole scope.
 *
 * Two verdicts are stamped, disjoint by construction so their counts add up:
 *  - **`unread`** — nothing anywhere reads it. The remedy is to delete the variable.
 *  - **`unreadIn`** — it is mapped into a called model that never reads it. The remedy is to delete the
 *    mapping. This is the case a process passing an input parameter nobody consumes produces.
 *
 * A variable Atlas declined to judge is marked **`readsUnknown`**, which is what lets the page state how
 * many names it stayed quiet about — the honest denominator that makes the numerator trustworthy.
 */
object UnusedVariables {

    /**
     * How far past a called model to look for a reader. Depth 0 is the callee itself, 1 its forms,
     * decisions, beans and onward calls, 2 a sub-form of one of its user tasks. Unbounded would reach
     * half the project and collapse this check into the global one; depth 1 would miss a variable whose
     * only reader is a form the callee's task opens.
     */
    private const val CALLEE_DEPTH = 2

    /** Node types whose variables are worth following when looking for a reader of a callee's scope. */
    private val MODEL_TYPES = setOf(
        "process", "case", "decision", "form", "page", "template", "document",
        "dataObject", "service", "agent", "action", "java", "bot", "event",
    )

    /** Human-readable list of what this check cannot see, for the report that shows its findings. */
    val SILENCE_RULES = listOf(
        "a construct whose direction Flowable does not fix (a variable listener, `hasVariable`)",
        "a value whose consumer is outside the models — an action's response payload, an extracted " +
            "variable a query indexes, a form's outcome variable the task list shows, a loop counter",
        "a name written into a container object, where only field-level reads would show it is used",
        "a bare-EL Init-Variables value, which Atlas has no parser for in that position",
        "a name the `{{…}}` harvester deliberately ignores, so its frontend reads were never collected",
        "a name passed as a string literal somewhere, which may be a variable lookup Atlas cannot parse",
        "any scope whose script or Java code reads the whole variable map at once",
        "a mapping into a called model that is not part of this project",
    )

    fun decide(
        nodes: Map<String, MutableMap<String, Any?>>,
        edges: List<Map<String, Any?>>,
        readsUnknown: Set<String>,
        scopesReadingEverything: Set<String>,
        mustacheIgnored: Set<String>,
    ) {
        // Every literal string any expression or binding carries. A variable addressed by name through a
        // custom function (`${myLib.get('orderId')}`) is indistinguishable from a constant, so its
        // presence here is enough to stop Atlas claiming nothing reads the variable.
        val stringLiterals = nodes.values.filter { it["type"] == "string" }.mapTo(HashSet()) { it["key"].toString() }

        val out = HashMap<String, MutableList<String>>()
        for (e in edges) {
            if (e["rel"] == "contains") continue
            val s = e["s"] as? String ?: continue
            val t = e["t"] as? String ?: continue
            out.getOrPut(s) { ArrayList() }.add(t)
        }

        /** [start] plus everything reachable from it within [CALLEE_DEPTH] model hops. */
        fun closure(start: String): Set<String> {
            val seen = linkedSetOf(start)
            var frontier = listOf(start)
            repeat(CALLEE_DEPTH) {
                val next = ArrayList<String>()
                for (n in frontier) for (t in (out[n] ?: emptyList())) {
                    if (nodes[t]?.get("type") !in MODEL_TYPES) continue
                    if (seen.add(t)) next.add(t)
                }
                frontier = next
            }
            return seen
        }

        for (node in nodes.values) {
            if (node["type"] != "variable") continue
            val data = Dyn.mutableMapOrNull(node["data"]) ?: continue
            val name = node["key"]?.toString() ?: continue
            val writes = Dyn.maps(data["writes"])
            val reads = Dyn.maps(data["reads"])

            if (writes.isEmpty()) continue

            // What the check *would* say, before any silence rule is consulted. Deciding this first is
            // what lets `readsUnknown` mean exactly "this one would have been reported, but Atlas cannot
            // see far enough to stand behind it" — the number the report quotes as its own caveat. Marking
            // every silenced name instead would count variables that have plenty of readers.
            val wouldBeUnread = reads.isEmpty()
            val unreadIn = if (wouldBeUnread) emptyList() else {
                // Something reads the name — but not necessarily in the scope the value was written into.
                // A `<flowable:in>` declares a variable of the *callee*, and the caller reading its own
                // namesake says nothing about whether the called model ever looks at it.
                val readModels = reads.mapNotNullTo(HashSet()) { it["model"] as? String }
                writes.mapNotNull { it["scope"] as? String }.distinct()
                    .filter { scope -> closure(scope).none { it in readModels } }
                    .sorted()
            }
            if (!wouldBeUnread && unreadIn.isEmpty()) continue

            val silenced = name in readsUnknown ||
                name in mustacheIgnored || name.trimStart('$') in mustacheIgnored ||
                name in stringLiterals ||
                writes.any { it["scopeUnresolved"] == true } ||
                // A bean or script that asks for every variable of a scope reads this one too. The scope
                // is the model that declares the write, or anything it hands execution to.
                writes.any { w ->
                    val m = w["model"] as? String ?: return@any false
                    m in scopesReadingEverything ||
                        (out[m] ?: emptyList()).any { it in scopesReadingEverything }
                }
            when {
                silenced -> data["readsUnknown"] = true
                wouldBeUnread -> data["unread"] = true
                else -> data["unreadIn"] = unreadIn
            }
        }
    }
}
