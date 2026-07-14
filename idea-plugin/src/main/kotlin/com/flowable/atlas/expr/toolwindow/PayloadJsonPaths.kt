package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.eval.PayloadScopePath
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * JSON-PSI ↔ [PayloadScopePath] bridge for the payload editor: [pathAt] derives the path of the
 * node under the caret ("From caret"), [rangeOf] finds the text range of the node a path points at
 * (the scope highlight). UI-free on purpose so both directions are testable with a plain fixture.
 */
internal object PayloadJsonPaths {

    /**
     * Path of the JSON value containing [offset]; a caret on a property key (or between key and
     * value) counts as that property's value. Null when there is no JSON value at the offset.
     */
    fun pathAt(file: PsiFile, offset: Int): PayloadScopePath? {
        val leaf = file.findElementAt(offset)
            ?: file.findElementAt((offset - 1).coerceAtLeast(0))
            ?: return null
        var node: PsiElement = PsiTreeUtil.getParentOfType(leaf, JsonValue::class.java, false, JsonProperty::class.java)
            ?: PsiTreeUtil.getParentOfType(leaf, JsonProperty::class.java, false)?.let { it.value ?: return null }
            ?: return null
        // A property KEY is itself a JsonValue: a caret *inside* the key resolves to that property's
        // value, but a caret at the key's leading edge — a structural position just inside the object's
        // `{`, e.g. `{<caret>"orders"…` — resolves to the enclosing object itself (so a top-level caret
        // is root), matching how a caret in an object's whitespace already resolves.
        (node.parent as? JsonProperty)?.let { property ->
            if (property.nameElement == node) {
                node = if (offset > node.textRange.startOffset) {
                    property.value ?: return null
                } else {
                    property.parent as? JsonObject ?: return null
                }
            }
        }
        val segments = ArrayList<PayloadScopePath.Segment>()
        var current: PsiElement = node
        var parent = current.parent
        while (parent != null && parent !is JsonFile) {
            when (parent) {
                is JsonProperty -> if (parent.value == current) segments += PayloadScopePath.Segment.Key(parent.name)
                is JsonArray -> {
                    val idx = parent.valueList.indexOfFirst { it === current }
                    if (idx < 0) return null
                    segments += PayloadScopePath.Segment.Index(idx)
                }
                // JsonObject adds no segment — the enclosing JsonProperty carries the key
            }
            current = parent
            parent = parent.parent
        }
        segments.reverse()
        return PayloadScopePath(segments)
    }

    /** Text range of the node [path] points at — for the editor highlight; null when unresolvable. */
    fun rangeOf(file: PsiFile, path: PayloadScopePath): TextRange? {
        var node: JsonValue = (file as? JsonFile)?.topLevelValue ?: return null
        for (seg in path.segments) {
            node = when (seg) {
                is PayloadScopePath.Segment.Key -> (node as? JsonObject)?.findProperty(seg.name)?.value ?: return null
                is PayloadScopePath.Segment.Index -> (node as? JsonArray)?.valueList?.getOrNull(seg.i) ?: return null
            }
        }
        return node.textRange
    }
}
