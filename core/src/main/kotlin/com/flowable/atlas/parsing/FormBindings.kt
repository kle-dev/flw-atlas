package com.flowable.atlas.parsing

/**
 * Which variable a form or page component stands for — one rule for the two readers of a form:
 * [ModelParsers.parseForm], which puts the form into the CLI's variable graph, and [ModelJsonReader.readForm],
 * which gives the IDE's index its variable vocabulary.
 *
 * They used to answer differently. The IDE took every labelled component's *id* for a variable, a rule
 * carried over from a time when the two were assumed equal; Design names its components `text1`, `date1`,
 * `string-list1`, and measured over five real projects the id named the bound variable in 12 of 1 430
 * bound fields. So the IDE completed `getVariable("text1")`, which resolves to nothing at runtime, and never
 * offered the `customerName` the field actually binds — while the CLI graph credited the right names.
 */
internal object FormBindings {

    /** Components that bind no value of their own: without a `{{…}}` value their id is no variable.
     *  A data table's selection, an HTML component's markup, a subform's children and a service button's
     *  result go elsewhere, and Design's default ids (`datatable1`) tied unrelated forms together. */
    val NON_BINDING_FIELD_TYPES = setOf(
        "text", "headline", "horizontalLine", "image", "html", "link", "spacer", "expression",
        "container", "panel", "tabs", "tab", "workAction", "outcomeButton",
        "dataTable", "htmlComponent", "cellComponent", "subform", "workInvokeService", "linkButton",
        "workProcessList", "workCaseList", "workTaskList", "iframe", "pdfViewer", "alert",
    )

    /** Field-id roots that are frontend scratch space, never process/case variables. */
    val FIELD_ID_IGNORE = setOf("temp", "response", "payload", "root", "item", "self")

    /** Buttons whose `value` binding is where the call's result is stored, not a caption or a placeholder. */
    val RESULT_BINDING_TYPES = setOf("scriptButton", "restButton")

    private val PATH_BINDING = Regex("[A-Za-z_$][A-Za-z0-9_.\\[\\]$]*")

    /** The root of a `{{path}}` value — `{{customer.birthDate}}` → `customer` — or null for anything else. */
    fun bindingRoot(value: Any?): String? {
        val raw = (value as? String)?.trim() ?: return null
        if (!raw.startsWith("{{") || !raw.endsWith("}}")) return null
        val inner = raw.removeSurrounding("{{", "}}").trim()
        if (inner.isEmpty() || !inner.matches(PATH_BINDING)) return null
        // `{{$temp.x}}`, `{{$currentUser…}}`: the forms runtime's own scope, never a variable
        if (inner.startsWith("$")) return null
        val root = inner.substringBefore('.').substringBefore('[')
        return root.takeIf { it.isNotEmpty() && it !in FIELD_ID_IGNORE }
    }

    private fun isButton(type: String) = type.lowercase().contains("button") || type in ModelJsonReader.BUTTON_TYPES

    /**
     * A data-entry field with no `{{…}}` value of its own: the forms runtime reads and writes the variable
     * its id spells, so the id's root is the variable. Buttons and the [NON_BINDING_FIELD_TYPES] bind none.
     */
    fun idVariable(n: Map<*, *>): String? {
        val type = n["type"] as? String ?: ""
        if ((n["value"] as? String)?.contains("{{") == true || type in NON_BINDING_FIELD_TYPES || isButton(type)) return null
        val root = n["id"].toString().trimStart('$').substringBefore('.').substringBefore('[')
        return root.takeIf { it !in FIELD_ID_IGNORE && Constants.isVariableName(it) }
    }

    /** Where an expression or REST button stores its result: the root of its `value` binding. */
    fun storedVariable(n: Map<*, *>): String? =
        if (n["type"] in RESULT_BINDING_TYPES) bindingRoot(n["value"])?.takeIf(Constants::isVariableName) else null

    /**
     * The variable a component's plain `{{path}}` value binds — what the graph's binding pass reads there,
     * which leaves the form runtime's own scratch roots (`{{data.x}}`, `{{item.x}}`) out. A button's value
     * is its result's target ([storedVariable]) or a caption, never an input.
     */
    fun boundVariable(n: Map<*, *>): String? {
        if (isButton(n["type"] as? String ?: "")) return null
        return bindingRoot(n["value"])
            ?.takeIf { it !in Constants.FRONTEND_SCRATCH_ROOTS && Constants.isVariableName(it) }
    }

    /**
     * Every variable a form or page document's components and its outcome variable name, in document order —
     * the IDE's vocabulary for that model. Walks what [ModelParsers.parseForm] walks: the whole document,
     * past the Oryx bookkeeping maps, gated on [ModelJsonReader.isFormComponent].
     */
    fun variablesOf(doc: Map<*, *>): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    if (ModelJsonReader.isFormComponent(node)) {
                        listOfNotNull(boundVariable(node), idVariable(node), storedVariable(node)).forEach(out::add)
                    }
                    for ((k, v) in node) if (k !in ModelParsers.ORYX_METADATA_KEYS) walk(v)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(doc)
        // the variable the chosen outcome is stored in (the key is all-lowercase in the model JSON)
        (doc["outcomevariablename"] as? String)?.trim()?.takeIf(Constants::isVariableName)?.let(out::add)
        return out.toList()
    }
}
