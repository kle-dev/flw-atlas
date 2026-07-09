package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx

/**
 * Regex harvesting of backend variable names from a model's raw text — a port of `collect_script_vars`
 * and `_collect_declared_vars` (+ their module regexes) in `flowable_atlas.py` (~lines 81-84, 293-333).
 * Names are attributed to the owning model key(s) in the shared [Ctx].
 */
object VarHarvest {

    private val SCRIPT_VAR_RE = Regex(
        "\\b(?:(?:get|set)(?:Transient)?(?:Input|Output)" +
            "|(?:set|get|has|remove)(?:Transient)?Variable(?:Local)?)" +
            "\\s*\\(\\s*['\"]([A-Za-z_]\\w*)['\"]")
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

    /** Variables referenced inside a script body (Flowable API + legacy `*Variable` idioms). */
    fun collectScriptVars(ctx: Ctx, script: String?, mkeys: List<Any?>) {
        if (script.isNullOrEmpty()) return
        val names = SCRIPT_VAR_RE.findAll(script).map { it.groupValues[1] }.toCollection(LinkedHashSet())
        for (k in mkeys) for (n in names) ctx.addVar(k, n, "script_var_use")
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
}
