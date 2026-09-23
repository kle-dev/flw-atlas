package com.flowable.atlas.expr.eval

import com.flowable.atlas.model.MiniJson

/**
 * Which sub-expressions of a traced evaluation get a ` = value` hint, and where it goes — the playground's
 * inline badges and their Remote-Dev fallback rows both come from here.
 *
 * A hint after every literal or member read is noise, so only operator results, calls, index reads and
 * ternaries are hinted, and only near the top of the tree (or inside parentheses the author wrote to
 * mark a part). The tree is not what the author wrote, though: `a || b || c || d` parses as
 * `((a || b) || c) || d`. Taken as a tree, the first operands sink deeper with every `||` and fell below
 * the depth limit — only the last few ever got a value — and every inner `(…) || c` ends exactly where
 * `c` ends, so two values landed on the same spot. The operands of a chain of one operator are therefore
 * siblings here, at the chain's depth, and the chain's inner links get no hint of their own (a pipe's
 * stages do: `x |> f |> g` is read stage by stage). An operand of `||` / `&&` is hinted whatever it is —
 * which operand decided is the whole point of the chain — and one the evaluation skipped says so.
 */
object SubExpressionHints {

    /** One hint: where the badge anchors, the node's own source text, and the ` = value` label. */
    data class Hint(val anchor: Int, val exprText: String, val label: String)

    /** Node kinds worth a hint on their own. */
    private val HINTED_KINDS = setOf(TraceNodeKind.BINARY, TraceNodeKind.CALL, TraceNodeKind.PIPE, TraceNodeKind.TERNARY, TraceNodeKind.INDEX)

    /** Kinds an operand of a logical chain is hinted as, too. */
    private val OPERAND_KINDS = HINTED_KINDS + setOf(TraceNodeKind.IDENT, TraceNodeKind.MEMBER, TraceNodeKind.UNARY, TraceNodeKind.NS_CALL)

    /** Operators whose operands are read one by one — the ones that short-circuit. */
    private val LOGICAL = setOf("||", "&&", "??", "or", "and")

    const val MAX_HINTS = 32
    const val MAX_VALUE_LENGTH = 40
    private const val MIN_NODE_SPAN = 3
    private const val MAX_DEPTH = 2

    fun compute(text: String, entries: List<TraceEntry>): List<Hint> {
        val parenMatch = matchParens(text)
        // parents from the pre-order walk: an entry's parent is the nearest earlier entry one level up
        val parent = IntArray(entries.size) { -1 }
        val stack = ArrayDeque<Int>()
        for ((i, e) in entries.withIndex()) {
            while (stack.isNotEmpty() && entries[stack.last()].depth >= e.depth) stack.removeLast()
            parent[i] = stack.lastOrNull() ?: -1
            stack.addLast(i)
        }
        // the depth as written: a chain link of its parent's operator stays at its parent's depth, and so
        // do its operands' siblings — `a || b || c` has three operands at depth 1
        val eff = IntArray(entries.size)
        val link = BooleanArray(entries.size)
        for ((i, e) in entries.withIndex()) {
            val p = parent[i]
            if (p < 0) { eff[i] = 0; continue }
            val pe = entries[p]
            link[i] = e.op != null && e.op == pe.op && e.start == pe.start && (e.kind == TraceNodeKind.BINARY || e.kind == TraceNodeKind.PIPE)
            eff[i] = if (link[i]) eff[p] else eff[p] + 1
        }
        // the operator an operand is an operand of: its parent's, seen through the chain links
        fun chainOp(i: Int): String? { val p = parent[i]; return if (p < 0) null else entries[p].op }

        // "skipped" is only true of a short-circuit: after a failure, what was not evaluated was never reached
        val failed = entries.any { it.outcome is TraceOutcome.Error || it.outcome is TraceOutcome.Unavailable }
        // one value per spot: a binary node ends where its right operand ends, so `a == 1 && b == 2`
        // and `b == 2` would share one — the inner node (later in pre-order) keeps it, since the
        // operands say what decided and the group's value follows from them
        val byAnchor = LinkedHashMap<Int, Hint>()
        for ((i, e) in entries.withIndex()) {
            if (byAnchor.size >= MAX_HINTS) break
            if (e.depth == 0) continue                                  // the root's value is the result
            val logicalOperand = !link[i] && chainOp(i) in LOGICAL
            // a chain's inner link ends where its last operand ends — only a pipe stage says something new
            if (link[i] && e.kind != TraceNodeKind.PIPE) continue
            if (e.kind !in (if (logicalOperand) OPERAND_KINDS else HINTED_KINDS)) continue
            if (e.end - e.start < MIN_NODE_SPAN && !logicalOperand) continue
            val (anchor, parenthesized) = anchorAfterParens(text, e, parenMatch)
            if (eff[i] > MAX_DEPTH && !parenthesized) continue
            val label = when (val o = e.outcome) {
                is TraceOutcome.Value -> " = ${truncateMiddle(display(o.value), MAX_VALUE_LENGTH)}"
                is TraceOutcome.Unavailable -> " = ?"
                TraceOutcome.NotEvaluated -> if (logicalOperand && !failed) " (skipped)" else continue
                is TraceOutcome.Error -> continue                      // squiggled already
            }
            val s = e.start.coerceIn(0, text.length)
            byAnchor.remove(anchor)
            byAnchor[anchor] = Hint(anchor, text.substring(s, e.end.coerceIn(s, text.length)), label)
        }
        return byAnchor.values.sortedBy { it.anchor }
    }

    /** Close-paren index → its open-paren index; a quick scan that skips string literals. */
    fun matchParens(text: String): Map<Int, Int> {
        val match = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quote != null -> if (ch == '\\') i++ else if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                ch == '(' -> stack.addLast(i)
                ch == ')' -> stack.removeLastOrNull()?.let { match[i] = it }
            }
            i++
        }
        return match
    }

    /**
     * `(1+1)` has no paren node in the AST and the entry's offsets exclude the parens — hop the anchor
     * over every closing paren whose matching `(` sits before the node, so the hint reads `(1+1) = 2`,
     * not `(1+1 = 2)`. Doubles as the "is parenthesized" detector for the depth limit.
     */
    private fun anchorAfterParens(text: String, entry: TraceEntry, parenMatch: Map<Int, Int>): Pair<Int, Boolean> {
        var anchor = entry.end
        var parenthesized = false
        var start = entry.start
        var i = entry.end
        while (true) {
            while (i < text.length && text[i] == ' ') i++
            // only parens that wrap exactly this node: in `(a && b == 2)` the `)` is the group's, not `b == 2`'s
            val open = if (i < text.length && text[i] == ')') parenMatch[i] else null
            if (open != null && open < start && text.substring(open + 1, start).isBlank()) {
                i++
                anchor = i
                start = open
                parenthesized = true
            } else break
        }
        return anchor to parenthesized
    }

    fun display(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        else -> MiniJson.stringify(value)
    }

    fun truncateMiddle(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max / 2) + "…" + s.takeLast(max / 2 - 1)
}
