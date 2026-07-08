package com.flowable.atlas.expr.eval

import com.flowable.atlas.expr.ExprWrappers
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.parse.ArrayNode
import com.flowable.atlas.expr.parse.ArrowNode
import com.flowable.atlas.expr.parse.BinaryNode
import com.flowable.atlas.expr.parse.CallNode
import com.flowable.atlas.expr.parse.ExprNode
import com.flowable.atlas.expr.parse.ExprParser
import com.flowable.atlas.expr.parse.IdentNode
import com.flowable.atlas.expr.parse.IndexNode
import com.flowable.atlas.expr.parse.LitNode
import com.flowable.atlas.expr.parse.MemberNode
import com.flowable.atlas.expr.parse.NsCallNode
import com.flowable.atlas.expr.parse.PipeNode
import com.flowable.atlas.expr.parse.TernaryNode
import com.flowable.atlas.expr.parse.UnaryNode

/**
 * Evaluates a **frontend** expression against a pasted form payload — the "copy the payload in and see
 * the result" mode. It parses with [ExprParser], builds the same evaluation context shape the browser
 * uses (`{ …defaultAdditionalData, …payload }`, see `flw/FProps.ts`), and walks the AST with JS-flavoured
 * semantics ([Values]) and the pure [FlwLibrary] functions. A parse or runtime failure is returned as
 * [EvalResult.Err] with a human message (the real form runtime silently yields `undefined`, so surfacing
 * the specific failure is strictly more useful). Expressions that are *valid* but can't be evaluated in a
 * static preview — running-form/locale members, or a custom function injected via
 * `flowable.externals.additionalData` — return [EvalResult.Unavailable] so they read as "not previewable
 * here", never as invalid.
 */
object FrontendExpressionEvaluator {

    /** Evaluate [body] (a frontend expression, wrapper optional) against [payloadJson] (a JSON object, or null/blank for `{}`). */
    fun evaluate(body: String, payloadJson: String?): EvalResult {
        val inner = stripWrapper(body)
        if (inner.isBlank()) return EvalResult.Err("Empty expression")

        val parsed = ExprParser.parse(inner, ExpressionDialect.FRONTEND)
        parsed.error?.let { return EvalResult.Err("Syntax error: ${it.message}") }
        val ast = parsed.ast ?: return EvalResult.Err("Could not parse expression")

        val payload: Any? = try {
            if (payloadJson.isNullOrBlank()) emptyMap<String, Any?>() else MiniJson.parse(payloadJson)
        } catch (e: MiniJson.JsonException) {
            return EvalResult.Err("Invalid payload JSON: ${e.message}")
        }

        return try {
            EvalResult.Ok(Evaluator(buildContext(payload)).eval(ast))
        } catch (e: PreviewUnavailableException) {
            EvalResult.Unavailable(e.message ?: "Not available in the payload preview")
        } catch (e: EvalException) {
            EvalResult.Err(e.message ?: "Evaluation error")
        } catch (e: Exception) {
            EvalResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stripWrapper(body: String): String =
        ExprWrappers.stripOuter(body, ExprWrappers.FRONTEND).first.trim()

    private fun buildContext(payload: Any?): Map<String, Any?> {
        val ctx = LinkedHashMap<String, Any?>()
        ctx["flw"] = FlwLibrary.namespace()
        ctx["\$lang"] = "en"
        ctx["\$payload"] = payload
        val map = Values.asMap(payload)
        ctx["root"] = map?.get("root") ?: payload
        if (map != null) ctx.putAll(map)
        return ctx
    }

    private class Evaluator(private val context: Map<String, Any?>) {

        fun eval(node: ExprNode): Any? = when (node) {
            is LitNode -> node.value
            is IdentNode -> context[node.name]
            is ArrayNode -> node.elements.map { eval(it) }
            is TernaryNode -> if (Values.truthy(eval(node.cond))) eval(node.then) else eval(node.otherwise)
            is UnaryNode -> evalUnary(node)
            is BinaryNode -> evalBinary(node)
            is MemberNode -> evalMember(eval(node.receiver), node.name)
            is IndexNode -> evalIndex(eval(node.receiver), eval(node.index))
            is CallNode -> evalCall(node)
            is PipeNode -> evalPipe(node)
            is ArrowNode -> makeArrow(node)
            is NsCallNode -> throw EvalException("'${node.namespace}:${node.name}(…)' is backend function syntax and cannot be evaluated as a frontend expression")
            else -> throw EvalException("Unsupported expression")
        }

        private fun evalUnary(n: UnaryNode): Any? {
            val v = eval(n.operand)
            return when (n.op) {
                "!" -> !Values.truthy(v)
                "-" -> -Values.toNum(v)
                "+" -> Values.toNum(v)
                else -> throw EvalException("Unsupported unary operator '${n.op}'")
            }
        }

        private fun evalBinary(n: BinaryNode): Any? {
            // Short-circuit logical operators return the operand value (JS semantics).
            when (n.op) {
                "&&" -> { val l = eval(n.left); return if (!Values.truthy(l)) l else eval(n.right) }
                "||" -> { val l = eval(n.left); return if (Values.truthy(l)) l else eval(n.right) }
            }
            val l = eval(n.left); val r = eval(n.right)
            return when (n.op) {
                "+" -> {
                    val a = Values.coalesce(l); val b = Values.coalesce(r)
                    if (a is String || b is String) Values.jsString(a) + Values.jsString(b) else Values.toNum(a) + Values.toNum(b)
                }
                "-" -> Values.toNum(l) - Values.toNum(r)
                "*" -> Values.toNum(l) * Values.toNum(r)
                "/" -> Values.toNum(l) / Values.toNum(r)
                "%" -> Values.toNum(l) % Values.toNum(r)
                "==" -> Values.looseEquals(l, r)
                "!=" -> !Values.looseEquals(l, r)
                "===" -> Values.strictEquals(l, r)
                "!==" -> !Values.strictEquals(l, r)
                "<" -> Values.compare(l, r) < 0
                ">" -> Values.compare(l, r) > 0
                "<=" -> Values.compare(l, r) <= 0
                ">=" -> Values.compare(l, r) >= 0
                else -> throw EvalException("Unsupported operator '${n.op}'")
            }
        }

        private fun evalMember(recv: Any?, name: String): Any? = when (recv) {
            is FlwNamespace -> recv.members[name] ?: throw EvalException("Unknown ${recv.name}.$name")
            is Map<*, *> -> recv[name]
            is List<*> -> if (name == "length") recv.size.toDouble() else null
            is String -> if (name == "length") recv.length.toDouble() else null
            null -> null
            else -> null
        }

        private fun evalIndex(recv: Any?, index: Any?): Any? = when (recv) {
            is List<*> -> Values.toNum(index).toInt().let { if (it in recv.indices) recv[it] else null }
            is Map<*, *> -> recv[Values.jsString(index)]
            is String -> Values.toNum(index).toInt().let { if (it in recv.indices) recv[it].toString() else null }
            else -> null
        }

        private fun evalCall(n: CallNode): Any? {
            val args = n.args.map { eval(it) }
            return when (val callee = n.callee) {
                is MemberNode -> {
                    val receiver = callee.receiver
                    // `custom.doThing(…)` where `custom` is an unresolved top-level identifier is most
                    // likely a custom object provided via `flowable.externals.additionalData` — invisible
                    // to a static preview, so report it as unavailable rather than "undefined".
                    if (receiver is IdentNode && !context.containsKey(receiver.name)) {
                        throw PreviewUnavailableException(
                            "'${receiver.name}.${callee.name}(…)' is not available in the payload preview — " +
                                "'${receiver.name}' may be a custom object provided by externals.additionalData")
                    }
                    val recv = eval(receiver)
                    if (recv is FlwNamespace) {
                        when (val m = recv.members[callee.name]) {
                            is FlwCallable -> m.call(args)
                            is FlwNamespace -> throw EvalException("${recv.name}.${callee.name} is a namespace, not a function")
                            else -> throw EvalException("Unknown ${recv.name}.${callee.name}")
                        }
                    } else callBuiltinMethod(recv, callee.name, args)
                }
                is IdentNode -> when (val target = context[callee.name]) {
                    is FlwCallable -> target.call(args)
                    // An identifier that isn't in scope at all is most likely a custom function injected
                    // via `flowable.externals.additionalData` (spread into the top-level scope by
                    // `hookEvalExpression`) — valid at runtime, just not previewable here. A name that
                    // *is* in scope but resolved to a non-function value is a genuine error.
                    else -> if (!context.containsKey(callee.name))
                        throw PreviewUnavailableException(
                            "'${callee.name}(…)' is not available in the payload preview — " +
                                "it may be a custom function provided by externals.additionalData")
                    else throw EvalException("'${callee.name}' is not a function")
                }
                else -> {
                    val t = eval(callee)
                    if (t is FlwCallable) t.call(args) else throw EvalException("Value is not callable")
                }
            }
        }

        private fun evalPipe(n: PipeNode): Any? {
            val left = eval(n.left)
            val right = eval(n.right)
            if (right is FlwCallable) return right.call(listOf(left))
            throw EvalException("The right side of '|>' must be a function reference, not a call — e.g. `x |> flw.round` or `x |> (v => flw.round(v, 2))`")
        }

        private fun makeArrow(n: ArrowNode): FlwCallable = FlwCallable { args ->
            val child = LinkedHashMap(context)
            n.params.forEachIndexed { i, p -> child[p] = args.getOrNull(i) }
            Evaluator(child).eval(n.body)
        }

        /** A small set of the JS String/Array methods forms commonly use directly (`names.join(', ')`). */
        private fun callBuiltinMethod(recv: Any?, name: String, args: List<Any?>): Any? {
            when (recv) {
                is List<*> -> return when (name) {
                    "join" -> recv.joinToString(args.getOrNull(0) as? String ?: ",") { Values.jsString(it) }
                    "indexOf" -> recv.indexOfFirst { Values.strictEquals(it, args.getOrNull(0)) }.toDouble()
                    "includes" -> recv.any { Values.strictEquals(it, args.getOrNull(0)) }
                    "concat" -> recv + (Values.asList(args.getOrNull(0)) ?: listOf(args.getOrNull(0)))
                    "reverse" -> recv.reversed()
                    "slice" -> {
                        val s = Values.toNum(args.getOrNull(0)).toInt().coerceIn(0, recv.size)
                        val e = args.getOrNull(1)?.let { Values.toNum(it).toInt() } ?: recv.size
                        recv.subList(s, e.coerceIn(s, recv.size))
                    }
                    else -> throw EvalException("'.$name(…)' is not supported on an array in the preview")
                }
                is String -> return when (name) {
                    "toUpperCase" -> recv.uppercase()
                    "toLowerCase" -> recv.lowercase()
                    "trim" -> recv.trim()
                    "includes" -> recv.contains(Values.jsString(args.getOrNull(0)))
                    "indexOf" -> recv.indexOf(Values.jsString(args.getOrNull(0))).toDouble()
                    "startsWith" -> recv.startsWith(Values.jsString(args.getOrNull(0)))
                    "endsWith" -> recv.endsWith(Values.jsString(args.getOrNull(0)))
                    "split" -> recv.split(Values.jsString(args.getOrNull(0)))
                    "substring" -> recv.substring(Values.toNum(args.getOrNull(0)).toInt().coerceIn(0, recv.length), (args.getOrNull(1)?.let { Values.toNum(it).toInt() } ?: recv.length).coerceIn(0, recv.length))
                    "replace" -> recv.replaceFirst(Values.jsString(args.getOrNull(0)), Values.jsString(args.getOrNull(1)))
                    "charAt" -> recv.getOrNull(Values.toNum(args.getOrNull(0)).toInt())?.toString() ?: ""
                    else -> throw EvalException("'.$name(…)' is not supported on a string in the preview")
                }
                null -> throw EvalException("Cannot call '.$name(…)' on an undefined value")
                else -> throw EvalException("'.$name(…)' is not supported on this value in the preview")
            }
        }
    }
}
