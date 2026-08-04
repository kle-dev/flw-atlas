package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx

/**
 * Regex harvesting of backend variable names from a model's raw text — a port of `collect_script_vars`
 * and `_collect_declared_vars` (+ their module regexes) in `flowable_atlas.py` (~lines 81-84, 293-333).
 * Names are attributed to the owning model key(s) in the shared [Ctx].
 */
object VarHarvest {

    private val DECL_VAR_RE = Regex(
        "\\b(?:resultVariableName|elementVariable|counterVariable|collectionVariable|" +
            "initiatorVariableName|variableName)=\"([A-Za-z_]\\w*)\"")
    private val COLL_RE = Regex("(?:flowable:|activiti:)?collection=\"([A-Za-z_]\\w*)\"")
    private val INOUT_RE = Regex("<(?:flowable:|activiti:)?(?:in|out)\\b([^>]*?)/?>")
    private val VARMAP_RE = Regex("<(?:flowable:|activiti:)?variableMapping\\b([^>]*?)/?>")
    private val PARAM_RE = Regex("<(?:flowable:|activiti:)?(?:input|output)Parameter\\b([^>]*?)/?>")
    private val OUTVAR_RE = Regex("<(?:flowable:|activiti:)?outputVariableName>\\s*(?:<!\\[CDATA\\[)?([A-Za-z_]\\w*)")
    private val NAME_TARGET_RE = Regex("\\b(?:name|target)=\"([A-Za-z_]\\w*)\"")
    private val SRC_TARGET_RE = Regex("\\b(?:source|target)=\"([A-Za-z_]\\w*)\"")
    private val NAME_ATTR_RE = Regex("\\bname=\"([A-Za-z_]\\w*)\"")

    // The directional split of DECL_VAR_RE / COLL_RE, used by `collectDirectedVars`.
    /** The current item of a multi-instance loop — normally consumed inside the loop. */
    private val MI_ELEMENT_RE = Regex("\\belementVariable=\"([A-Za-z_]\\w*)\"")
    /** The loop/repetition index. `elementIndexVariable` is CMMN's name for it and shares nothing but a
     *  suffix with `elementVariable`, so it needs its own alternative rather than a looser pattern. */
    private val MI_COUNTER_RE =
        Regex("\\b(?:counterVariable|elementIndexVariable)=\"([A-Za-z_]\\w*)\"")
    private val MI_COLLECTION_RE = Regex("\\bcollectionVariable=\"([A-Za-z_]\\w*)\"")
    private val INITIATOR_RE = Regex("\\binitiatorVariableName=\"([A-Za-z_]\\w*)\"")
    private val VARIABLE_NAME_RE = Regex("\\bvariableName=\"([A-Za-z_]\\w*)\"")

    /**
     * Variables a script body touches — the Flowable APIs *and* the bare identifiers the script reads
     * out of its scope (see [ScriptVars]). [element]/[elementName]/[elementType] name the script's own
     * element (a script task, a listener) so the variable node can say where it is used; a caller with
     * no element context (a bot script *is* the model) may leave them out.
     */
    fun collectScriptVars(
        ctx: Ctx, script: String?, mkeys: List<Any?>, format: String? = null,
        element: Any? = null, elementName: Any? = null, elementType: String? = null,
    ) {
        if (script.isNullOrEmpty()) return
        val use = ScriptVars.analyze(script, format)
        if (use.isEmpty && !use.readsWholeScope) return
        for (k in mkeys) {
            for (n in use.api) ctx.addScriptVar(k, n, true, element, elementName, elementType)
            for (n in use.reads) ctx.addScriptVar(k, n, false, element, elementName, elementType)
            // The same names again, this time with the verb the API call used — an explicit `set` is a
            // write, an explicit `get` a read, a bare identifier a suspected read, and `hasVariable` a
            // construct that proves neither.
            for (n in use.writes) ctx.addVarSite(k, n, Ctx.WRITE, "scriptApi", element, elementName, elementType)
            for (n in use.apiReads) ctx.addVarSite(k, n, Ctx.READ, "scriptApi", element, elementName, elementType)
            for (n in use.reads) {
                ctx.addVarSite(k, n, Ctx.READ, "scriptRead", element, elementName, elementType, proven = false)
            }
            for (n in use.undecided) ctx.markReadsUnknown(n)
            if (use.readsWholeScope) ctx.varScopeReadsAll.add(k.toString())
        }
    }

    /** Declared/mapped backend variable names from raw XML (init vars, in/out, MI, params, …). */
    fun collectDeclaredVars(ctx: Ctx, raw: String, mkeys: List<Any?>) {
        val names = LinkedHashSet<String>()
        DECL_VAR_RE.findAll(raw).forEach { names.add(it.groupValues[1]) }
        COLL_RE.findAll(raw).forEach { names.add(it.groupValues[1]) }
        OUTVAR_RE.findAll(raw).forEach { names.add(it.groupValues[1]) }
        INOUT_RE.findAll(raw).forEach { m -> SRC_TARGET_RE.findAll(m.groupValues[1]).forEach { names.add(it.groupValues[1]) } }
        VARMAP_RE.findAll(raw).forEach { m -> NAME_TARGET_RE.findAll(m.groupValues[1]).forEach { names.add(it.groupValues[1]) } }
        PARAM_RE.findAll(raw).forEach { m -> NAME_ATTR_RE.findAll(m.groupValues[1]).forEach { names.add(it.groupValues[1]) } }
        for (k in mkeys) for (n in names) ctx.addVar(k, n)
    }

    /**
     * The directional half of [collectDeclaredVars] — the same raw text, but only the attributes whose
     * direction Flowable fixes.
     *
     * [collectDeclaredVars] deliberately lumps everything into one undirected bucket, which is right for
     * the "declared / mapped" record it feeds but useless for deciding whether anyone consumes a
     * variable: it puts `resultVariableName` (a write) next to `collection` (a read). Recovering the
     * halves here is what lets the undirected bucket be ignored by the unused-variable check without
     * losing a read — otherwise every multi-instance collection written by a script looks unread.
     *
     * Only what the structured parsers do **not** already see. `resultVariableName`,
     * `outputVariableName` and every in/out mapping go through [Ctx.addParams], which knows the element
     * and the callee this regex cannot; repeating them here would report one write twice.
     */
    fun collectDirectedVars(ctx: Ctx, raw: String, mkeys: List<Any?>) {
        fun emit(re: Regex, dir: String, via: String) {
            for (m in re.findAll(raw)) for (k in mkeys) ctx.addVarSite(k, m.groupValues[1], dir, via)
        }
        // The engine writes these: the current item of a multi-instance loop, and the user who started
        // the instance.
        emit(MI_ELEMENT_RE, Ctx.WRITE, "multiInstanceElement")
        emit(INITIATOR_RE, Ctx.WRITE, "initiator")
        // A loop counter exists so that the engine and the UI can expose which repetition this is. It is
        // recorded, but its readers are not the models' business — a counter no condition mentions is the
        // normal case, not a finding.
        for (m in MI_COUNTER_RE.findAll(raw)) {
            for (k in mkeys) ctx.addVarSite(k, m.groupValues[1], Ctx.WRITE, "multiInstanceCounter")
            ctx.markReadsUnknown(m.groupValues[1])
        }
        // A multi-instance collection is read to be iterated over.
        emit(MI_COLLECTION_RE, Ctx.READ, "multiInstanceCollection")
        emit(COLL_RE, Ctx.READ, "multiInstanceCollection")
        // `flowable:variableName` belongs to a variable listener or a multi-instance aggregation. Both
        // watch or build a variable rather than reading or writing it outright, so the direction is not
        // ours to declare — recording the name as undecidable keeps the check quiet about it.
        for (m in VARIABLE_NAME_RE.findAll(raw)) ctx.markReadsUnknown(m.groupValues[1])
    }
}
