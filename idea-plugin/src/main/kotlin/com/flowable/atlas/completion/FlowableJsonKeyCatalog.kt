package com.flowable.atlas.completion

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.parsing.JsonKeySites
import com.flowable.atlas.parsing.JsonKeySites.JsonKeySite
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * The model → model key references inside a Flowable **JSON** model — a data object's backing service,
 * a form component's subform, data object, service or action, a document's forms, an app's models —
 * found on the PSI the way [JsonKeySites] describes them: by the property path from the file's root.
 *
 * The catalog itself lives in `:core` next to the parsers that record these references, so the CLI's
 * graph and the IDE's Ctrl+click name the same sites. This object only turns a [JsonStringLiteral] into
 * that path and applies the file-type and sibling conditions the catalog states.
 */
object FlowableJsonKeyCatalog {

    /** The site [literal] is a reference at, or null when it is a name, a label, a value — anything but a key. */
    fun siteForLiteral(literal: JsonStringLiteral): JsonKeySite? {
        val vFile = literal.containingFile?.viewProvider?.virtualFile ?: return null
        val host = ModelFiles.typeOf(vFile) ?: return null
        val path = pathOf(literal) ?: return null
        val candidates = JsonKeySites.matches(path, host)
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.sibling == null || siblingHolds(literal, it) }
    }

    /**
     * Root → [literal] as property names, `[]` for an array element — the path the catalog matches.
     * Null when the literal is a property *name*, or hangs off something that is not JSON structure.
     */
    fun pathOf(literal: JsonStringLiteral): List<String>? {
        val out = ArrayList<String>()
        var child: PsiElement = literal
        var parent: PsiElement? = literal.parent
        while (parent != null && parent !is JsonFile) {
            when (parent) {
                is JsonProperty -> {
                    if (parent.value !== child) return null
                    out.add(parent.name)
                }
                is JsonArray -> out.add("[]")
                is JsonObject -> Unit
                else -> return null
            }
            child = parent
            parent = parent.parent
        }
        if (parent == null) return null
        return out.asReversed()
    }

    /** A sibling-conditioned site holds when the object holding the property carries the sibling with that value. */
    private fun siblingHolds(literal: JsonStringLiteral, site: JsonKeySite): Boolean {
        val (name, value) = site.sibling ?: return true
        val holder = PsiTreeUtil.getParentOfType(literal, JsonObject::class.java) ?: return false
        return (holder.findProperty(name)?.value as? JsonStringLiteral)?.value == value
    }
}
