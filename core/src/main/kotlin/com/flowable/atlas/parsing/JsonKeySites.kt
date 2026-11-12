package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.model.ModelType.ACTION
import com.flowable.atlas.model.ModelType.AGENT
import com.flowable.atlas.model.ModelType.APP
import com.flowable.atlas.model.ModelType.CASE
import com.flowable.atlas.model.ModelType.CHANNEL
import com.flowable.atlas.model.ModelType.DASHBOARD_COMPONENT
import com.flowable.atlas.model.ModelType.DATA_DICTIONARY
import com.flowable.atlas.model.ModelType.DATA_OBJECT
import com.flowable.atlas.model.ModelType.DOCUMENT
import com.flowable.atlas.model.ModelType.EVENT
import com.flowable.atlas.model.ModelType.FORM
import com.flowable.atlas.model.ModelType.KNOWLEDGE_BASE
import com.flowable.atlas.model.ModelType.MASTER_DATA
import com.flowable.atlas.model.ModelType.PAGE
import com.flowable.atlas.model.ModelType.PROCESS
import com.flowable.atlas.model.ModelType.QUERY
import com.flowable.atlas.model.ModelType.SERVICE
import com.flowable.atlas.model.ModelType.SLA
import com.flowable.atlas.model.ModelType.TEMPLATE
import com.flowable.atlas.model.ModelType.VARIABLE_EXTRACTOR

/**
 * Where, inside a Flowable JSON model, a string is another model's key — the JSON counterpart of the
 * BPMN/CMMN attribute catalog the IDE plugin keeps for `calledElement`, `formKey` and the rest.
 *
 * One list, written from what [ModelParsers] records as a reference (`ctx.addRef`), so the graph the
 * CLI draws and the Ctrl+click the IDE offers can never disagree on what is a reference; the test
 * `JsonKeySitesTest` walks the fixture models and checks that every site here is a reference there.
 *
 * A site is a **property path**, matched against the path from the document root to the string with
 * array elements written `[]`. A path that is not [JsonKeySite.rootOnly] matches as a suffix — a form
 * component's `extraSettings.formRef` sits at any depth of nesting. The last segment may be `*` for
 * any property name (a `.document`'s `forms.view`, `forms.edit`). A site marked [JsonKeySite.refObject]
 * accepts Design's newer `{ "id": …, "key": … }` object as well as the bare key: the object's `key`
 * property is then the reference (the path gains a trailing `key`). [JsonKeySite.hosts] names the model
 * types whose files carry the site, so a `formKey` at the root of an action is a form reference and the
 * same property name elsewhere is not; null means any JSON model. [JsonKeySite.sibling] is a property
 * the holding object must carry with that exact value — an action's `signalName` is a process key only
 * for the start-process bot.
 */
object JsonKeySites {

    data class JsonKeySite(
        val path: String,
        val types: List<ModelType>,
        val hosts: Set<ModelType>? = null,
        val rootOnly: Boolean = false,
        val refObject: Boolean = false,
        val sibling: Pair<String, String>? = null,
    ) {
        val segments: List<String> = path.split('.')
    }

    /** The file types a form-style body (components with `extraSettings`) lives in. */
    private val UI: Set<ModelType> = setOf(FORM, PAGE)
    private val ANY: List<ModelType> = ModelType.entries.toList()

    val sites: List<JsonKeySite> = listOf(
        // ---- forms and pages: the top-level outcomes, then a component's settings -----------------
        JsonKeySite("outcomeFormKey", listOf(FORM), UI),
        JsonKeySite("extraSettings.formRef", listOf(FORM), UI, refObject = true),
        JsonKeySite("extraSettings.dataObjectDefinitionKey", listOf(DATA_OBJECT), UI),
        JsonKeySite("extraSettings.tableKey", listOf(MASTER_DATA, DATA_OBJECT), UI),
        JsonKeySite("extraSettings.scopeDefinitionKey", listOf(PROCESS, CASE), UI),
        JsonKeySite("extraSettings.serviceModel.serviceModelKey", listOf(SERVICE), UI),
        JsonKeySite("extraSettings.dataObjectDataTableCreateFormKey", listOf(FORM), UI),
        JsonKeySite("extraSettings.dataObjectDataTableEditFormKey", listOf(FORM), UI),
        JsonKeySite("extraSettings.dataObjectDataTableViewFormKey", listOf(FORM), UI),
        JsonKeySite("extraSettings.dataObjectDataTableDeleteFormKey", listOf(FORM), UI),
        JsonKeySite("extraSettings.expandablePanel", listOf(FORM), UI),
        JsonKeySite("extraSettings.actionDefinitionKey", listOf(ACTION), UI, refObject = true),
        JsonKeySite("extraSettings.agentModel.agentModelKey", listOf(AGENT), UI),
        JsonKeySite("extraSettings.processReference", listOf(PROCESS), UI, refObject = true),
        JsonKeySite("extraSettings.caseReference", listOf(CASE), UI, refObject = true),
        JsonKeySite("extraSettings.query", listOf(QUERY), UI, refObject = true),
        // ---- data object -------------------------------------------------------------------------
        JsonKeySite("referencedServiceDefinitionModelKey", listOf(SERVICE), setOf(DATA_OBJECT), rootOnly = true),
        JsonKeySite("referencedDataDictionaryModelKey", listOf(DATA_DICTIONARY), setOf(DATA_OBJECT), rootOnly = true),
        JsonKeySite("fieldMappings.[].dataObjectModelKey", listOf(DATA_OBJECT), setOf(DATA_OBJECT), rootOnly = true),
        // ---- service -----------------------------------------------------------------------------
        JsonKeySite("referenceKey", listOf(DATA_OBJECT), setOf(SERVICE), rootOnly = true),
        JsonKeySite("columnMappings.[].relation.referenceServiceDefinitionKey", listOf(SERVICE), setOf(SERVICE), rootOnly = true),
        JsonKeySite("typeReference.modelKey", listOf(DATA_DICTIONARY), setOf(SERVICE)),
        JsonKeySite("bodyTemplateModel.bodyTemplateTemplateModelKey", listOf(TEMPLATE), setOf(SERVICE)),
        // ---- event and channel -------------------------------------------------------------------
        JsonKeySite("inboundChannelKeys.[]", listOf(CHANNEL), setOf(EVENT), rootOnly = true),
        JsonKeySite("outboundChannelKeys.[]", listOf(CHANNEL), setOf(EVENT), rootOnly = true),
        JsonKeySite("extensionProperties.dataDictionaryModelKey", listOf(DATA_DICTIONARY), setOf(EVENT)),
        JsonKeySite("channelEventKeyDetection.fixedValue", listOf(EVENT), setOf(CHANNEL), rootOnly = true),
        // ---- action, template, document ----------------------------------------------------------
        JsonKeySite("formKey", listOf(FORM), setOf(ACTION, TEMPLATE), rootOnly = true),
        JsonKeySite("signalName", listOf(PROCESS), setOf(ACTION), rootOnly = true, sibling = "botKey" to "bpmn-start-process-instance-bot"),
        JsonKeySite("signalName", listOf(CASE), setOf(ACTION), rootOnly = true, sibling = "botKey" to "cmmn-start-case-instance-bot"),
        JsonKeySite("forms.*", listOf(FORM), setOf(DOCUMENT), rootOnly = true, refObject = true),
        // ---- query, SLA, variable extractor ------------------------------------------------------
        JsonKeySite("processDefinitionKey", listOf(PROCESS), setOf(QUERY, SLA), rootOnly = true),
        JsonKeySite("actionConfiguration.processDefinitionKey", listOf(PROCESS), setOf(SLA)),
        JsonKeySite("actionConfiguration.caseDefinitionKey", listOf(CASE), setOf(SLA)),
        JsonKeySite("actionConfig.processDefinitionKey", listOf(PROCESS), setOf(SLA)),
        JsonKeySite("actionConfig.caseDefinitionKey", listOf(CASE), setOf(SLA)),
        JsonKeySite("filter.scopeDefinitionKey", listOf(PROCESS, CASE), setOf(VARIABLE_EXTRACTOR)),
        // ---- app ---------------------------------------------------------------------------------
        JsonKeySite("childModels.[].key", ANY, setOf(APP)),
        JsonKeySite("pageModels.[].key", listOf(PAGE), setOf(APP), rootOnly = true),
        // ---- agent -------------------------------------------------------------------------------
        JsonKeySite("tools.[].key", ANY, setOf(AGENT)),
        JsonKeySite("systemMessageTemplate.templateKey", listOf(TEMPLATE), setOf(AGENT)),
        JsonKeySite("userMessageTemplate.templateKey", listOf(TEMPLATE), setOf(AGENT)),
        JsonKeySite("guardrails.[].agentModel.key", listOf(AGENT), setOf(AGENT)),
        JsonKeySite("guardrails.[].serviceModel.key", listOf(SERVICE), setOf(AGENT)),
        JsonKeySite("guardrails.[].configuration.agentModel.key", listOf(AGENT), setOf(AGENT)),
        JsonKeySite("guardrails.[].configuration.serviceModel.key", listOf(SERVICE), setOf(AGENT)),
        JsonKeySite("evaluators.[].reference.key", listOf(AGENT, SERVICE), setOf(AGENT)),
        JsonKeySite("documentClassifications.[].contentModel.key", listOf(DOCUMENT), setOf(AGENT)),
        JsonKeySite("knowledgeBase.knowledgeBaseModelReference.key", listOf(KNOWLEDGE_BASE), setOf(AGENT), rootOnly = true),
        JsonKeySite("documentAgent.documentAgentModel.key", listOf(AGENT), setOf(AGENT), rootOnly = true),
        // ---- dashboard component -----------------------------------------------------------------
        JsonKeySite("queryModel.key", listOf(QUERY), setOf(DASHBOARD_COMPONENT), rootOnly = true),
    )

    /**
     * The sites a string at [path] (root → string, arrays as `[]`) could be, inside a model of type
     * [host]. Several only when a sibling condition tells them apart — the caller checks
     * [JsonKeySite.sibling] against the holding object and takes the first that holds. Empty when the
     * string is nothing a parser records as a reference.
     */
    fun matches(path: List<String>, host: ModelType?): List<JsonKeySite> {
        if (path.isEmpty()) return emptyList()
        return sites.filter { site ->
            (site.hosts == null || host in site.hosts) &&
                (matchesPath(site, path) || (site.refObject && path.last() == "key" && matchesPath(site, path.dropLast(1))))
        }
    }

    /** The one site for [path] when no sibling condition is involved, else the first — a convenience for callers without the holding object. */
    fun match(path: List<String>, host: ModelType?): JsonKeySite? = matches(path, host).firstOrNull { it.sibling == null }

    private fun matchesPath(site: JsonKeySite, path: List<String>): Boolean {
        val s = site.segments
        if (path.size < s.size) return false
        if (site.rootOnly && path.size != s.size) return false
        val offset = path.size - s.size
        for (i in s.indices) {
            val want = s[i]
            if (want != "*" && want != path[offset + i]) return false
        }
        return true
    }
}
