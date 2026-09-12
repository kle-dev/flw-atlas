package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.ModelParsers

/**
 * Attaches a template's parts to its `.tpl` record. A deployment BAR keeps the body outside the model:
 * `template-<key>.tpl` holds only `{name, key, templateType, variationContentType}`, the text lives in
 * `template-<key>.tplvariation` — a list of `{templateDefinitionKey, variationContent |
 * variationContentResource, parameterValues}` — and an attached document's name in
 * `.tplfile-metadata` (`{id: "template-<key>", name, resourceType}`). Until now the parts were dropped
 * without a word and 23 of 23 template nodes on the real projects had no body: nothing to search, and
 * the `${root.travelerFirstName}` a mail template reads never reached the variable graph.
 */
object TemplateParts {

    /** `${root.x}` in a FreeMarker template body reads the variable `x`: `root` is the scope. */
    private val ROOT_READ_RE = Regex("[#$]\\{\\s*root\\.([A-Za-z_]\\w*)")

    @Suppress("UNCHECKED_CAST")
    fun attach(
        result: MutableMap<String, Any?>, ctx: Ctx, parts: List<Pair<String, String>>,
        diag: (String, String, String) -> Unit,
    ) {
        if (parts.isEmpty()) return
        val templates = HashMap<String, MutableMap<String, Any?>>()
        for (o in (result["others"] as? List<*>).orEmpty()) {
            val m = o as? MutableMap<String, Any?> ?: continue
            if (m["modelType"] == "template") (m["key"] as? String)?.let { templates[it] = m }
        }
        for ((label, text) in parts) {
            val low = label.lowercase()
            val parsed = try { MiniJson.parse(text) } catch (e: Exception) { diag("parse", label, e.message ?: e.toString()); continue }
            if (low.endsWith(".tplvariation")) {
                for (v in (parsed as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }) {
                    val key = v["templateDefinitionKey"] as? String ?: continue
                    val tpl = templates[key]
                    if (tpl == null) { diag("skip", label, "a variation of template '$key', which is not in this project"); continue }
                    val rec = linkedMapOf<String, Any?>()
                    (v["parameterValues"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { rec["parameters"] = it }
                    val content = v["variationContent"] as? String
                    content?.takeIf { it.isNotBlank() }?.let { rec["text"] = ModelParsers.capText(it) }
                    v["variationContentResource"]?.let { rec["resource"] = it }
                    if (rec.isEmpty()) continue
                    val list = (tpl["variations"] as? MutableList<Any?>) ?: ArrayList<Any?>().also { tpl["variations"] = it }
                    list.add(rec)
                    if (tpl["content"] == null && content != null) tpl["content"] = ModelParsers.capText(content)
                    if (content != null) harvest(ctx, key, content)
                }
            } else {
                val m = parsed as? Map<String, Any?> ?: continue
                val id = m["id"] as? String ?: continue
                val name = m["name"] as? String ?: continue
                // `template-<key>` names the template; an older export uses the content item's uuid, which
                // nothing here can tie to a model — left alone rather than guessed.
                val tpl = templates[id.removePrefix("template-")] ?: continue
                val list = (tpl["attachments"] as? MutableList<Any?>) ?: ArrayList<Any?>().also { tpl["attachments"] = it }
                list.add(linkedMapOf("name" to name, "type" to m["resourceType"]))
            }
        }
    }

    /** What a body reads: its `${…}` as expressions of the template, and `${root.x}` as a read of `x`. */
    private fun harvest(ctx: Ctx, key: String, content: String) {
        val id = "template:$key"
        for (e in Constants.EXPR_RE.findAll(content).map { Constants.htmlUnescape(it.value) }) {
            ctx.expr.add(e)
            ctx.exprUse.getOrPut(e) { LinkedHashSet() }.add(id)
        }
        val prev = ctx.currentModel
        ctx.currentModel = "template"
        for (m in ROOT_READ_RE.findAll(content)) {
            val name = m.groupValues[1]
            ctx.addVar(key, name)                                  // the name, so the variable has a node
            ctx.addVarSite(key, name, Ctx.READ, "template")        // and the direction, so the read counts
        }
        ctx.currentModel = prev
    }
}
