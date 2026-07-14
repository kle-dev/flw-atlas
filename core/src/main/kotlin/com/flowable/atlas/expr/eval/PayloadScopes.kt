package com.flowable.atlas.expr.eval

/**
 * Resolves a [PayloadScopePath] against a parsed payload into the evaluation frame the real forms
 * engine would give a component living at that node: the node becomes the local scope (its keys are
 * spread into the context), every traversed level contributes one `$itemParent` link
 * (`{ …enclosingScope, $itemParent: outer }` — mirrors `FormUtils.ts`), and the innermost array
 * element binds `$item`/`$index`. `root` and `$payload` stay absolute regardless of depth.
 *
 * Approximation, by design: every path step is treated as a *bound container* boundary (a subform
 * bound to a sub-object, a list bound to an array). The form structure is unknowable from the
 * payload alone, and the node a user scopes to is by construction one a container is bound to.
 * A key step followed by an index into the array it resolved to counts as ONE boundary — the list
 * container — matching the engine, where the row adds `$item`/`$index` while `$itemParent` points
 * at the scope *enclosing* the array. A key-only step (subform on a sub-object) leaves any outer
 * `$item`/`$index` visible, exactly like a plain subform inheriting its row's context.
 *
 * Resolution is strict: a missing key or an out-of-bounds index is [Resolution.NotFound], never a
 * silent fall-back to the root — a plausibly wrong value is worse than a loud error.
 */
object PayloadScopes {

    sealed interface Resolution {
        /** [item] is only meaningful when [hasItem] — the item value itself may legitimately be JSON null. */
        data class Resolved(
            val scope: Any?,
            val itemParent: Any?,
            val item: Any?,
            val hasItem: Boolean,
            val index: Int?,
        ) : Resolution

        data class NotFound(val message: String) : Resolution
    }

    fun resolve(payload: Any?, path: PayloadScopePath): Resolution {
        var scope: Any? = payload
        var itemParent: Any? = null
        var item: Any? = null
        var hasItem = false
        var index: Int? = null
        val segs = path.segments
        var i = 0
        while (i < segs.size) {
            val enclosing = scope
            when (val seg = segs[i]) {
                is PayloadScopePath.Segment.Key -> {
                    val map = Values.asMap(enclosing)
                        ?: return notFound(path, "key '${seg.name}' cannot be read — ${at(segs, i)} is not an object")
                    if (!map.containsKey(seg.name)) return notFound(path, "key '${seg.name}' does not exist in ${at(segs, i)}")
                    val v = map[seg.name]
                    val next = segs.getOrNull(i + 1)
                    if (next is PayloadScopePath.Segment.Index && v is List<*>) {
                        // list container: key + index = ONE boundary; the row binds $item/$index
                        if (next.i !in v.indices) return notFound(path, "index ${next.i} is out of bounds (${size(v)})")
                        item = v[next.i]
                        hasItem = true
                        index = next.i
                        scope = v[next.i]
                        i += 2
                    } else {
                        // subform bound to a sub-object: outer $item/$index stay visible
                        scope = v
                        i += 1
                    }
                }
                is PayloadScopePath.Segment.Index -> {
                    val list = Values.asList(enclosing)
                        ?: return notFound(path, "index [${seg.i}] cannot be applied — ${at(segs, i)} is not an array")
                    if (seg.i !in list.indices) return notFound(path, "index ${seg.i} is out of bounds (${size(list)})")
                    item = list[seg.i]
                    hasItem = true
                    index = seg.i
                    scope = list[seg.i]
                    i += 1
                }
            }
            itemParent = (Values.asMap(enclosing) ?: emptyMap()) + ("\$itemParent" to itemParent)
        }
        return Resolution.Resolved(scope, itemParent, item, hasItem, index)
    }

    private fun notFound(path: PayloadScopePath, detail: String) =
        Resolution.NotFound("Scope path '${path.format()}' does not resolve: $detail")

    private fun at(segs: List<PayloadScopePath.Segment>, count: Int): String =
        PayloadScopePath(segs.take(count)).format().ifEmpty { "the payload root" }.let {
            if (it == "the payload root") it else "'$it'"
        }

    private fun size(list: List<*>): String = "${list.size} item${if (list.size == 1) "" else "s"}"
}
