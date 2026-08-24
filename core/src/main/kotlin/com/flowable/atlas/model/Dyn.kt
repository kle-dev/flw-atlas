package com.flowable.atlas.model

/**
 * The one place that casts the engine's dynamic `Map<String, Any?>` graph to a usable shape.
 *
 * Atlas carries parsed models as nested `Map`/`List` of `Any?` — the shape [MiniJson] produces and the
 * shape the Python original had. That is deliberate (see [com.flowable.atlas.graph.GraphBuilder]): the
 * renderers dump unknown fields generically, so a model attribute nobody wrote code for still reaches
 * the report. The cost is that every read has to cast, and every cast is unchecked.
 *
 * Those casts used to be handled by putting `@Suppress("UNCHECKED_CAST")` on whole functions — some of
 * them hundreds of lines long. That silences the *real* cast at the top and every future one added
 * anywhere in the body, so the compiler stopped being able to warn about a genuinely wrong cast in the
 * exact code most likely to contain one. Four files had also each grown their own private `asMap` /
 * `asList` / `mapList` / `objOf` with subtly different null-vs-empty semantics.
 *
 * So: the cast lives here, once, audited, and callers get a total function instead of a suppression.
 * The `Any?`-typed value parameters are the point — this is the boundary where dynamic becomes typed.
 *
 * Naming convention: `…OrNull` returns null for "absent or wrong type" (the caller distinguishes);
 * the bare name returns an empty collection (the caller treats absent and empty alike).
 */
object Dyn {

    /** [v] as a string-keyed map, or null when it is neither a map nor present. */
    @Suppress("UNCHECKED_CAST")
    fun mapOrNull(v: Any?): Map<String, Any?>? = (v as? Map<String, Any?>)

    /** [v] as a string-keyed map, empty when absent or of another type. */
    fun map(v: Any?): Map<String, Any?> = mapOrNull(v) ?: emptyMap()

    /** [v] as a mutable string-keyed map, or null — for the in-place graph mutation passes. */
    @Suppress("UNCHECKED_CAST")
    fun mutableMapOrNull(v: Any?): MutableMap<String, Any?>? = (v as? MutableMap<String, Any?>)

    /**
     * [v] as a mutable map with `Any?` keys.
     *
     * Only the explorer payload needs this: its slimming step ([com.flowable.atlas.render] `slimData`)
     * iterates a `Map<*, *>` and so produces `Any?`-keyed maps. Same erased type as the String-keyed
     * variants at runtime — the separate signature exists so the payload code does not have to claim a
     * key type it did not build.
     */
    @Suppress("UNCHECKED_CAST")
    fun anyMutableMapOrNull(v: Any?): MutableMap<Any?, Any?>? = (v as? MutableMap<Any?, Any?>)

    /** [v] as a list, or null when it is neither a list nor present. */
    fun listOrNull(v: Any?): List<Any?>? = (v as? List<*>)

    /** [v] as a list, empty when absent or of another type. */
    fun list(v: Any?): List<Any?> = listOrNull(v) ?: emptyList()

    /** [v] as a mutable list, or null — for the buckets the extractor appends to. */
    @Suppress("UNCHECKED_CAST")
    fun mutableListOrNull(v: Any?): MutableList<Any?>? = (v as? MutableList<Any?>)

    /**
     * The maps inside [v], skipping every element that is not one.
     *
     * Skipping rather than failing is the established behaviour: a hand-edited model can hold a scalar
     * where the schema says object, and one bad element must not lose the whole list.
     */
    fun maps(v: Any?): List<Map<String, Any?>> = list(v).mapNotNull { mapOrNull(it) }

    /** [maps] for the passes that mutate the elements they walk. */
    fun mutableMaps(v: Any?): List<MutableMap<String, Any?>> = list(v).mapNotNull { mutableMapOrNull(it) }

    /** The strings inside [v], skipping non-strings. */
    fun strings(v: Any?): List<String> = list(v).filterIsInstance<String>()
}
