package com.flowable.atlas.expr

import com.flowable.atlas.expr.parse.ArrayNode
import com.flowable.atlas.expr.parse.ArrowNode
import com.flowable.atlas.expr.parse.BinaryNode
import com.flowable.atlas.expr.parse.CallNode
import com.flowable.atlas.expr.parse.ExprNode
import com.flowable.atlas.expr.parse.ExprParser
import com.flowable.atlas.expr.parse.IdentNode
import com.flowable.atlas.expr.parse.IndexNode
import com.flowable.atlas.expr.parse.MemberNode
import com.flowable.atlas.expr.parse.NsCallNode
import com.flowable.atlas.expr.parse.PipeNode
import com.flowable.atlas.expr.parse.TernaryNode
import com.flowable.atlas.expr.parse.UnaryNode

/**
 * Optional backend "codebase grounding": resolves the *root identifiers* of a backend expression
 * (the variables / beans it reads, e.g. `order` in `${order.customer.name}`, `myBean` in
 * `${myBean.doThing()}`) against what the project actually declares — indexed process/case variables,
 * referenced Spring beans, and the implicit engine roots (`execution`, `task`, …).
 *
 * This is deliberately a **warning-only, opt-in** layer: process variables can be set at runtime
 * without ever appearing in a model, so an unknown root is a hint ("did you mean…?"), not an error.
 * It is a pure function of (expression, known-name predicate) so it is fully unit-testable; the
 * annotator supplies the predicate from the live index + selected scope.
 */
object BackendGrounding {

    /** One root identifier reference found in an expression, with its offsets in the body. */
    data class RootRef(val name: String, val start: Int, val end: Int)

    /**
     * Collect the root identifier references of a backend expression body. Arrow parameters are treated
     * as locals and excluded (both the parameter binding and its uses within the lambda body). Function
     * names, property names after `.`, and namespaces are not roots.
     */
    fun rootReferences(body: String): List<RootRef> {
        val (inner, shift) = stripOuterWrapper(body)
        val ast = ExprParser.parse(inner, ExpressionDialect.BACKEND).ast ?: return emptyList()
        val refs = ArrayList<RootRef>()
        collect(ast, emptySet(), refs)
        return if (shift == 0) refs else refs.map { it.copy(start = it.start + shift, end = it.end + shift) }
    }

    /** Strip a single `${ … }` / `#{ … }` wrapper if the whole body is wrapped; return (inner, startShift). */
    private fun stripOuterWrapper(body: String): Pair<String, Int> =
        ExprWrappers.stripOuter(body, ExprWrappers.BACKEND)

    /**
     * Grounding findings for [body]: a warning per root reference that [isKnown] rejects. [suggest]
     * offers a "did you mean" fix (or null). Never returns errors.
     */
    fun check(body: String, isKnown: (String) -> Boolean, suggest: (String) -> String? = { null }): List<ExprProblem> =
        rootReferences(body)
            .filterNot { isKnown(it.name) }
            .map {
                val fix = suggest(it.name)
                val hint = fix?.let { s -> " — did you mean '$s'?" } ?: ""
                ExprProblem(it.start, it.end, "'${it.name}' is not a known variable, bean, or root object$hint",
                    ExprSeverity.WARNING, fix, ExprProblemKind.UNKNOWN_ROOT, subject = it.name)
            }

    private fun collect(node: ExprNode, locals: Set<String>, out: MutableList<RootRef>) {
        when (node) {
            is IdentNode -> if (node.name !in locals) out += RootRef(node.name, node.start, node.end)
            is MemberNode -> collect(node.receiver, locals, out)   // `.name` is a property, not a root
            is IndexNode -> { collect(node.receiver, locals, out); collect(node.index, locals, out) }
            is CallNode -> {
                // A bare `foo(...)` callee is a function, not a variable → don't treat as a root ref.
                // A `receiver.method(...)` callee still contributes its receiver's root.
                (node.callee as? MemberNode)?.let { collect(it.receiver, locals, out) }
                node.args.forEach { collect(it, locals, out) }
            }
            is NsCallNode -> node.args.forEach { collect(it, locals, out) }
            is UnaryNode -> collect(node.operand, locals, out)
            is BinaryNode -> { collect(node.left, locals, out); collect(node.right, locals, out) }
            is TernaryNode -> { collect(node.cond, locals, out); collect(node.then, locals, out); collect(node.otherwise, locals, out) }
            is PipeNode -> { collect(node.left, locals, out); collect(node.right, locals, out) }
            is ArrayNode -> node.elements.forEach { collect(it, locals, out) }
            is ArrowNode -> collect(node.body, locals + node.params, out)
            else -> {}
        }
    }
}
