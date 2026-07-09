package com.flowable.atlas.expr.parse

/**
 * A tiny expression AST shared by both Flowable dialects. It is produced by [ExprParser] and consumed
 * by the structural validator (syntax errors) and — for the frontend dialect — by the payload
 * evaluator. Nodes carry absolute [start]/[end] offsets into the expression *body* so findings and
 * evaluation errors can be pointed back at the source.
 */
sealed interface ExprNode {
    val start: Int
    val end: Int
}

/** A literal: number, string, boolean or null. [value] is the decoded Kotlin value. */
data class LitNode(val value: Any?, val raw: String, override val start: Int, override val end: Int) : ExprNode

/** A bare identifier / variable / root object reference (`order`, `execution`, `flw`, `$item`). */
data class IdentNode(val name: String, override val start: Int, override val end: Int) : ExprNode

/** Property access `receiver.name` (dotted). */
data class MemberNode(val receiver: ExprNode, val name: String, override val start: Int, override val end: Int) : ExprNode

/** Index access `receiver[index]`. */
data class IndexNode(val receiver: ExprNode, val index: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** A call `callee(args)`. [callee] is typically an [IdentNode] or [MemberNode]. */
data class CallNode(val callee: ExprNode, val args: List<ExprNode>, override val start: Int, override val end: Int) : ExprNode

/** A backend namespaced function call `namespace:name(args)` (e.g. `date:now()`). */
data class NsCallNode(
    val namespace: String, val name: String, val args: List<ExprNode>,
    val nsStart: Int, val nameEnd: Int,
    override val start: Int, override val end: Int,
) : ExprNode

/** Unary prefix operator: `- + ! not empty`. */
data class UnaryNode(val op: String, val operand: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** Binary / logical / comparison operator (word forms are normalised to their symbolic equivalents). */
data class BinaryNode(val op: String, val left: ExprNode, val right: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** Ternary `cond ? then : otherwise`. */
data class TernaryNode(val cond: ExprNode, val then: ExprNode, val otherwise: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** Frontend array literal `[a, b, c]`. */
data class ArrayNode(val elements: List<ExprNode>, override val start: Int, override val end: Int) : ExprNode

/** Frontend pipe `left |> right` — evaluates to `right(left)` (right must be a function reference). */
data class PipeNode(val left: ExprNode, val right: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** Frontend arrow `params => body`. [params] is one name (`x => …`) or several (`[a, b] => …`). */
data class ArrowNode(val params: List<String>, val body: ExprNode, override val start: Int, override val end: Int) : ExprNode

/** Parser-internal: a parenthesised comma list `(a, b)` that is only legal as arrow parameters. Never
 *  survives a successful parse — it is either turned into an [ArrowNode]'s params or reported as an error. */
data class ParenParamsNode(val items: List<ExprNode>, override val start: Int, override val end: Int) : ExprNode
