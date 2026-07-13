package com.flowable.atlas.expr.eval

import com.flowable.atlas.model.MiniJson

/** A runtime evaluation failure (unknown function, calling a non-function, unsupported operator, …). */
open class EvalException(message: String) : RuntimeException(message)

/**
 * The expression is *valid* but cannot be evaluated in a static payload preview — it depends on the
 * running form/locale (`flw.getUser`, date/locale members) or on a custom function a project injected
 * via `flowable.externals.additionalData`. Surfaced as [EvalResult.Unavailable] (neutral), not an error.
 */
class PreviewUnavailableException(message: String) : EvalException(message)

/** A callable value — a `flw.*` function or an arrow lambda. */
fun interface FlwCallable {
    fun call(args: List<Any?>): Any?
}

/** The `flw` namespace (or a nested one like `flw.array` / `flw.remove`). Members are [FlwCallable]s or nested [FlwNamespace]s. */
class FlwNamespace(val name: String, val members: Map<String, Any?>)

/** Outcome of evaluating a frontend expression against a payload. */
sealed interface EvalResult {
    data class Ok(val value: Any?) : EvalResult
    data class Err(val message: String) : EvalResult
    /** Valid, but not evaluable in a static preview (running-form / locale / custom-injected function). */
    data class Unavailable(val message: String) : EvalResult
}

/**
 * JS-flavoured value helpers shared by the evaluator and the `flw.*` library — number coercion,
 * truthiness, equality and string rendering, kept close to the browser semantics the frontend relies on.
 */
object Values {

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0 && !v.isNaN()
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    /** Best-effort number coercion (JS `Number(x)`). Returns NaN when it can't. */
    fun toNum(v: Any?): Double = when (v) {
        null -> 0.0
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    /** null → "" (used by `+` coalescing); otherwise JS-like string form. */
    fun coalesce(v: Any?): Any? = v ?: ""

    fun jsString(v: Any?): String = when (v) {
        null -> "null"
        is Double -> if (v == v.toLong().toDouble() && !v.isInfinite()) v.toLong().toString() else v.toString()
        is String -> v
        is Boolean -> v.toString()
        is Map<*, *>, is List<*> -> MiniJson.stringify(v)
        else -> v.toString()
    }

    fun strictEquals(a: Any?, b: Any?): Boolean = when {
        a == null && b == null -> true
        a is Double && b is Double -> a == b
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        else -> a === b || a == b
    }

    fun looseEquals(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a is Double || b is Double || a is Number || b is Number ->
            if ((a is String && a.toDoubleOrNull() == null) || (b is String && b.toDoubleOrNull() == null)) jsString(a) == jsString(b)
            else toNum(a) == toNum(b)
        a is Boolean || b is Boolean -> toNum(a) == toNum(b)
        else -> jsString(a) == jsString(b)
    }

    fun compare(a: Any?, b: Any?): Int = when {
        a is String && b is String -> a.compareTo(b)
        else -> toNum(a).compareTo(toNum(b))
    }

    fun asList(v: Any?): List<Any?>? = when (v) {
        is List<*> -> v
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?>? = when (v) {
        is Map<*, *> -> v as Map<String, Any?>
        else -> null
    }
}
