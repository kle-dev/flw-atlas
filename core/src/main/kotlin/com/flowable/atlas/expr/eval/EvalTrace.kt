package com.flowable.atlas.expr.eval

import com.flowable.atlas.expr.parse.ArrayNode
import com.flowable.atlas.expr.parse.ArrowNode
import com.flowable.atlas.expr.parse.BinaryNode
import com.flowable.atlas.expr.parse.CallNode
import com.flowable.atlas.expr.parse.ExprNode
import com.flowable.atlas.expr.parse.IdentNode
import com.flowable.atlas.expr.parse.IndexNode
import com.flowable.atlas.expr.parse.LitNode
import com.flowable.atlas.expr.parse.MemberNode
import com.flowable.atlas.expr.parse.NsCallNode
import com.flowable.atlas.expr.parse.PipeNode
import com.flowable.atlas.expr.parse.TernaryNode
import com.flowable.atlas.expr.parse.UnaryNode
import java.util.IdentityHashMap

/**
 * A per-sub-expression record of what [FrontendExpressionEvaluator.evaluateTraced] computed: for
 * `(1+1) + (2+2)` the trace contains `1+1` → 2 and `2+2` → 4 alongside the root's 6, each entry
 * pointing back at its source range. Consumers (the playground's inline result hints) filter by
 * [TraceEntry.kind]/[TraceEntry.depth] — a hint after every literal would be noise.
 */
enum class TraceNodeKind { LITERAL, IDENT, MEMBER, INDEX, CALL, NS_CALL, UNARY, BINARY, TERNARY, ARRAY, PIPE, ARROW }

sealed interface TraceOutcome {
    data class Value(val value: Any?) : TraceOutcome

    /** Only the deepest failing node carries the error; unwinding ancestors record nothing. */
    data class Error(val message: String) : TraceOutcome
    data class Unavailable(val message: String) : TraceOutcome

    /** Short-circuited (`&&`/`||`/ternary) branch, or inside an arrow body (which runs once per
     *  element, so "the" value of a node there is ambiguous). Never force-evaluated. */
    data object NotEvaluated : TraceOutcome
}

/** [start]/[end] are offsets into the ORIGINAL body passed to `evaluateTraced` (wrapper included). */
data class TraceEntry(
    val start: Int,
    val end: Int,
    val kind: TraceNodeKind,
    /** 0 = the whole expression; children of the root operator are 1, and so on. */
    val depth: Int,
    val outcome: TraceOutcome,
)

/** The overall result plus one [TraceEntry] per AST node in source (pre-)order. */
data class TracedEvaluation(val result: EvalResult, val entries: List<TraceEntry>)

/** Collects per-node outcomes during one evaluation pass. Identity-keyed: the same node object is
 *  evaluated at most once outside arrows. */
internal class TraceCollector {
    private val outcomes = IdentityHashMap<ExprNode, TraceOutcome>()
    private var failureRecorded = false

    fun recordValue(node: ExprNode, value: Any?) {
        outcomes[node] = TraceOutcome.Value(value)
    }

    fun recordUnavailable(node: ExprNode, message: String) {
        if (!failureRecorded) {
            outcomes[node] = TraceOutcome.Unavailable(message)
            failureRecorded = true
        }
    }

    fun recordError(node: ExprNode, message: String) {
        if (!failureRecorded) {
            outcomes[node] = TraceOutcome.Error(message)
            failureRecorded = true
        }
    }

    /** Pre-order walk emitting an entry per node; nodes the evaluator never reached (short-circuited
     *  branches, arrow bodies) become [TraceOutcome.NotEvaluated]. [shift] maps AST offsets (which are
     *  relative to the stripped inner text) back into the original body. */
    fun entriesFor(root: ExprNode, shift: Int): List<TraceEntry> {
        val out = ArrayList<TraceEntry>()
        fun walk(node: ExprNode, depth: Int) {
            val kind = kindOf(node) ?: return
            out += TraceEntry(node.start + shift, node.end + shift, kind, depth, outcomes[node] ?: TraceOutcome.NotEvaluated)
            for (child in childrenOf(node)) walk(child, depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun kindOf(node: ExprNode): TraceNodeKind? = when (node) {
        is LitNode -> TraceNodeKind.LITERAL
        is IdentNode -> TraceNodeKind.IDENT
        is MemberNode -> TraceNodeKind.MEMBER
        is IndexNode -> TraceNodeKind.INDEX
        is CallNode -> TraceNodeKind.CALL
        is NsCallNode -> TraceNodeKind.NS_CALL
        is UnaryNode -> TraceNodeKind.UNARY
        is BinaryNode -> TraceNodeKind.BINARY
        is TernaryNode -> TraceNodeKind.TERNARY
        is ArrayNode -> TraceNodeKind.ARRAY
        is PipeNode -> TraceNodeKind.PIPE
        is ArrowNode -> TraceNodeKind.ARROW
        else -> null   // ParenParamsNode never survives a successful parse
    }

    private fun childrenOf(node: ExprNode): List<ExprNode> = when (node) {
        is LitNode, is IdentNode -> emptyList()
        is MemberNode -> listOf(node.receiver)
        is IndexNode -> listOf(node.receiver, node.index)
        is CallNode -> listOf(node.callee) + node.args
        is NsCallNode -> node.args
        is UnaryNode -> listOf(node.operand)
        is BinaryNode -> listOf(node.left, node.right)
        is TernaryNode -> listOf(node.cond, node.then, node.otherwise)
        is ArrayNode -> node.elements
        is PipeNode -> listOf(node.left, node.right)
        is ArrowNode -> listOf(node.body)
        else -> emptyList()
    }
}
