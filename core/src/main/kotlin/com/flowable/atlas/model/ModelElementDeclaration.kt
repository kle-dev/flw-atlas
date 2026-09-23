package com.flowable.atlas.model

/**
 * Where an element of a model is declared in the model's text — the sibling of [ModelKeyDeclaration] for
 * the elements inside a model: a BPMN task, a CMMN plan item, a DMN rule, a form component.
 *
 * The declaration, not the first mention: `sourceRef="approve"` on a flow can come before the task's own
 * `id="approve"`, and a form field called `name` or `type` is spelled like a JSON key long before its
 * `"id": "name"`. So the id attribute is tried first, then a JSON `"id"` member, then the same member in
 * the escaped `editorJson` string a Design workspace export wraps a model in — and only then the first
 * quoted occurrence, which still serves the names that have no declaration of their own (a payload field,
 * a variable).
 */
object ModelElementDeclaration {

    /** The offset of [id]'s first character in its declaration in [text], or null when [text] does not
     *  spell it at all. */
    fun offsetOf(text: String, id: String): Int? {
        if (id.isEmpty()) return null
        val q = Regex.escape(id)
        Regex("""(?<![\w:-])id\s*=\s*(["'])($q)\1""").find(text)?.let { return it.groups[2]!!.range.first }
        Regex(""""id"\s*:\s*"($q)"""").find(text)?.let { return it.groups[1]!!.range.first }
        Regex("""\\"id\\"\s*:\s*\\"($q)\\"""").find(text)?.let { return it.groups[1]!!.range.first }
        for (quote in charArrayOf('"', '\'')) {
            val at = text.indexOf("$quote$id$quote")
            if (at >= 0) return at + 1
        }
        return null
    }
}
