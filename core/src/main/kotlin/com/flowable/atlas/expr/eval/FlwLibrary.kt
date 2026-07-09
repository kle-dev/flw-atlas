package com.flowable.atlas.expr.eval

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.expr.eval.Values.asList
import com.flowable.atlas.expr.eval.Values.asMap
import com.flowable.atlas.expr.eval.Values.truthy
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A JVM reimplementation of the pure, deterministic parts of the frontend `flw.*` namespace
 * (`@flowable/forms` `Expression/functions`). Collection/aggregation/math/JSON helpers evaluate
 * exactly; environment- or locale-dependent members (date formatting, `timeZone`, `numberFormat`,
 * `sanitizeHtml`, `$currentUser`-derived) are reported as "not available in the payload preview"
 * rather than faked, so a green result never misleads.
 */
object FlwLibrary {

    fun namespace(): FlwNamespace = FlwNamespace("flw", ROOT)

    private fun call(f: (List<Any?>) -> Any?): FlwCallable = FlwCallable { f(it) }
    private fun arg(args: List<Any?>, i: Int): Any? = args.getOrNull(i)
    private fun unsupported(name: String): FlwCallable = call { throw PreviewUnavailableException("flw.$name is not available in the payload preview (it depends on the running form/locale)") }

    private fun asCallable(v: Any?, ctx: String): FlwCallable =
        v as? FlwCallable ?: throw EvalException("$ctx expects a function argument")

    private fun getPath(obj: Any?, path: String): Any? {
        var cur = obj
        for (seg in path.split('.')) {
            cur = asMap(cur)?.get(seg) ?: return null
        }
        return cur
    }

    // ---- aggregation ----
    private val aggregation: Map<String, Any?> = mapOf(
        "sum" to call { a -> asList(arg(a, 0))?.sumOf { Values.toNum(it).let { n -> if (n.isNaN()) 0.0 else n } } },
        "avg" to call { a -> asList(arg(a, 0))?.let { if (it.isEmpty()) 0.0 else it.sumOf { e -> Values.toNum(e) } / it.size } },
        "count" to call { a -> asList(arg(a, 0))?.size?.toDouble() },
        "min" to call { a -> asList(arg(a, 0))?.mapNotNull { Values.toNum(it).takeIf { n -> !n.isNaN() } }?.minOrNull() },
        "max" to call { a -> asList(arg(a, 0))?.mapNotNull { Values.toNum(it).takeIf { n -> !n.isNaN() } }?.maxOrNull() },
        "dotProd" to call { a ->
            val x = asList(arg(a, 0)); val y = asList(arg(a, 1))
            if (x == null || y == null || x.size != y.size) null
            else x.indices.sumOf { Values.toNum(x[it]) * Values.toNum(y[it]) }
        },
        "join" to call { a -> asList(arg(a, 0))?.joinToString(arg(a, 1) as? String ?: ", ") { Values.jsString(it) } ?: "" },
    )

    // ---- collection ----
    private val collection: Map<String, Any?> = mapOf(
        "mapAttr" to call { a -> asList(arg(a, 0))?.map { getPath(it, arg(a, 1) as? String ?: "") } },
        "find" to call { a -> val at = arg(a, 1) as? String; asList(arg(a, 0))?.firstOrNull { Values.strictEquals(getPath(it, at ?: ""), arg(a, 2)) } },
        "findAll" to call { a -> val at = arg(a, 1) as? String; asList(arg(a, 0))?.filter { Values.strictEquals(getPath(it, at ?: ""), arg(a, 2)) } },
        "merge" to call { a -> (asList(arg(a, 0)) ?: emptyList()) + (asList(arg(a, 1)) ?: emptyList()) },
        "add" to call { a -> (asList(arg(a, 0)) ?: emptyList()) + listOf(arg(a, 1)) },
        "in" to call { a -> asList(arg(a, 0))?.any { Values.strictEquals(it, arg(a, 1)) } ?: false },
        "keys" to call { a -> asMap(arg(a, 0))?.keys?.toList() },
        "values" to call { a -> asMap(arg(a, 0))?.values?.toList() },
        "forceCollectionSize" to call { a ->
            val list = asList(arg(a, 0)) ?: emptyList(); val size = Values.toNum(arg(a, 1)).toInt(); val fill = arg(a, 2)
            if (list.size >= size) list.take(size) else list + List(size - list.size) { fill }
        },
        "remove" to FlwNamespace("remove", mapOf(
            "byAttr" to call { a -> val at = arg(a, 1) as? String; asList(arg(a, 0))?.filterNot { Values.strictEquals(getPath(it, at ?: ""), arg(a, 2)) } },
            "byPos" to call { a -> val pos = Values.toNum(arg(a, 1)).toInt(); asList(arg(a, 0))?.filterIndexed { i, _ -> i != pos } },
            "byObj" to call { a -> asList(arg(a, 0))?.filterNot { it == arg(a, 1) } },
            "nulls" to call { a -> asList(arg(a, 0))?.filter { it != null && it != "" && !(it is Double && it.isNaN()) } },
        )),
    )

    // ---- math ----
    private val math: Map<String, Any?> = mapOf(
        "round" to call { a ->
            val v = Values.toNum(arg(a, 0)); val d = arg(a, 1)?.let { Values.toNum(it).toInt() } ?: 0
            if (v.isNaN()) null else BigDecimal(v).setScale(d, RoundingMode.HALF_UP).toDouble()
        },
        "floor" to call { a -> Math.floor(Values.toNum(arg(a, 0))) },
        "ceil" to call { a -> Math.ceil(Values.toNum(arg(a, 0))) },
        "abs" to call { a -> Math.abs(Values.toNum(arg(a, 0))) },
        "parseInt" to call { a -> (arg(a, 0) as? String)?.trim()?.toIntOrNull()?.toDouble() ?: Values.toNum(arg(a, 0)).let { if (it.isNaN()) null else Math.floor(it) } },
        "parseFloat" to call { a -> Values.toNum(arg(a, 0)).let { if (it.isNaN()) null else it } },
    )

    // ---- string / encoding ----
    private val strings: Map<String, Any?> = mapOf(
        "encodeURI" to call { a -> java.net.URLEncoder.encode(Values.jsString(arg(a, 0)), Charsets.UTF_8).replace("+", "%20") },
        "encodeURIComponent" to call { a -> java.net.URLEncoder.encode(Values.jsString(arg(a, 0)), Charsets.UTF_8).replace("+", "%20") },
        "escapeHtml" to call { a -> Values.jsString(arg(a, 0) ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;") },
        "exists" to call { a -> exists(arg(a, 0)) },
        "notExists" to call { a -> !exists(arg(a, 0)) },
        "JSON" to FlwNamespace("JSON", mapOf(
            "parse" to call { a -> MiniJson.parse(Values.jsString(arg(a, 0))) },
            "stringify" to call { a -> MiniJson.stringify(arg(a, 0)) },
        )),
    )

    private fun exists(v: Any?): Boolean = when (v) {
        null -> false
        is String -> v.isNotEmpty()
        is Double -> !v.isNaN()
        else -> true
    }

    // ---- flw.array.* (higher-order) ----
    private val array: Map<String, Any?> = mapOf(
        "filter" to call { a -> list(a).filter { truthy(asCallable(arg(a, 1), "flw.array.filter").call(listOf(it))) } },
        "map" to call { a -> list(a).map { asCallable(arg(a, 1), "flw.array.map").call(listOf(it)) } },
        "flatMap" to call { a -> list(a).flatMap { asList(asCallable(arg(a, 1), "flw.array.flatMap").call(listOf(it))) ?: emptyList() } },
        "find" to call { a -> list(a).firstOrNull { truthy(asCallable(arg(a, 1), "flw.array.find").call(listOf(it))) } },
        "any" to call { a -> list(a).any { truthy(asCallable(arg(a, 1), "flw.array.any").call(listOf(it))) } },
        "all" to call { a -> list(a).all { truthy(asCallable(arg(a, 1), "flw.array.all").call(listOf(it))) } },
        "none" to call { a -> list(a).none { truthy(asCallable(arg(a, 1), "flw.array.none").call(listOf(it))) } },
        "count" to call { a -> list(a).count { truthy(asCallable(arg(a, 1), "flw.array.count").call(listOf(it))) }.toDouble() },
        "reduce" to call { a ->
            val fn = asCallable(arg(a, 2), "flw.array.reduce")
            list(a).fold(arg(a, 1)) { acc, item -> fn.call(listOf(acc, item)) }
        },
        "indexOf" to call { a -> list(a).indexOfFirst { Values.strictEquals(it, arg(a, 1)) }.toDouble() },
        "includes" to call { a -> list(a).any { Values.strictEquals(it, arg(a, 1)) } },
        "first" to call { a -> list(a).firstOrNull() },
        "last" to call { a -> list(a).lastOrNull() },
        "isEmpty" to call { a -> list(a).isEmpty() },
        "isNotEmpty" to call { a -> list(a).isNotEmpty() },
        "reverse" to call { a -> list(a).reversed() },
        "concat" to call { a -> list(a) + (asList(arg(a, 1)) ?: emptyList()) },
        "compact" to call { a -> list(a).filter { it != null && it != "" && it != false } },
        "slice" to call { a -> val s = Values.toNum(arg(a, 1)).toInt(); val e = arg(a, 2)?.let { Values.toNum(it).toInt() } ?: list(a).size; list(a).subList(s.coerceIn(0, list(a).size), e.coerceIn(s, list(a).size)) },
        "append" to call { a -> list(a) + a.drop(1) },
        "sort" to call { a ->
            val prop = arg(a, 1) as? String
            val asc = (arg(a, 2) as? String)?.lowercase() != "desc"
            val sorted = list(a).sortedWith(compareBy { if (prop.isNullOrEmpty()) Values.jsString(it) else Values.jsString(getPath(it, prop)) })
            if (asc) sorted else sorted.reversed()
        },
        "pick" to call { a -> list(a).map { row -> asMap(row)?.filterKeys { k -> a.drop(1).any { it == k } } } },
    )

    private fun list(a: List<Any?>): List<Any?> = asList(arg(a, 0)) ?: emptyList()

    // ---- flw.data.* ----
    private val data: Map<String, Any?> = mapOf(
        "hasProperty" to call { a -> getPath(arg(a, 0), arg(a, 1) as? String ?: "") != null },
        "keys" to call { a -> asMap(arg(a, 0))?.keys?.toList() },
        "compact" to call { a -> asMap(arg(a, 0))?.filterValues { it != null && it != "" } },
        "pick" to call { a -> asMap(arg(a, 0))?.filterKeys { k -> a.drop(1).any { it == k } } },
        "merge" to call { a -> (asMap(arg(a, 0)) ?: emptyMap()) + (asMap(arg(a, 1)) ?: emptyMap()) },
        "addProperty" to call { a -> (asMap(arg(a, 0)) ?: emptyMap()) + ((arg(a, 1) as? String ?: "") to arg(a, 2)) },
        "removeProperty" to call { a -> asMap(arg(a, 0))?.filterKeys { it != arg(a, 1) } },
        "removePropertyWithValue" to call { a -> asMap(arg(a, 0))?.filterNot { it.key == arg(a, 1) && Values.strictEquals(it.value, arg(a, 2)) } },
    )

    // ---- date / locale (reported as unsupported in preview) ----
    private val dateFns = listOf(
        "now", "currentDate", "secondsOfDay", "timeZone", "parseDate", "formatDate", "formatTime",
        "dateAdd", "dateSubtract", "startOf", "isBefore", "isAfter", "sameDate",
        "formattedDurationFromNow", "formattedTimeLapseBetween", "durationBetween",
    )
    private val localeFns = listOf("numberFormat", "sanitizeHtml")

    // Work/platform-injected members (see FlowableExpressionCatalog): known functions that hit the
    // backend or drive the running form, so they are real — not "unknown" — but cannot be previewed.
    private val workFns = listOf(
        "getUser", "getMasterDataInstance", "getMasterDataInstanceByKey", "getDataObjectInstance",
        "translateWorkObject", "stringify", "validate", "setActiveTab", "getActiveTab",
    )

    private val ROOT: Map<String, Any?> = buildMap {
        putAll(aggregation)
        putAll(collection)
        putAll(math)
        putAll(strings)
        put("array", FlwNamespace("array", array))
        put("data", FlwNamespace("data", data))
        dateFns.forEach { put(it, unsupported(it)) }
        localeFns.forEach { put(it, unsupported(it)) }
        workFns.forEach { put(it, unsupported(it)) }
    }
}
