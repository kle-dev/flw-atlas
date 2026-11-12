package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.PlatformDefaultModels
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The one answer to "is this model-key reference inside a model broken?", for the XML and the JSON
 * inspection alike — the same question the CLI's `missingRefs` check answers, so the editor and the report
 * agree on which references are defects.
 */
internal object ModelKeyVerdict {

    /** What to say about an unknown key, and the nearest known key of the expected type, when there is one. */
    data class Verdict(val message: String, val suggestion: String?)

    /**
     * The types the CLI also accepts for a reference of a type (its resolver's fallback): a form key may
     * name a page and back, a data-object key a master-data model and back. Without them a reference the
     * report draws as an edge would be a warning in the editor.
     */
    private val COMPATIBLE: Map<ModelType, List<ModelType>> = mapOf(
        ModelType.FORM to listOf(ModelType.PAGE),
        ModelType.PAGE to listOf(ModelType.FORM),
        ModelType.DATA_OBJECT to listOf(ModelType.MASTER_DATA),
        ModelType.MASTER_DATA to listOf(ModelType.DATA_OBJECT),
    )

    /**
     * Null when [value] is fine or no verdict can be given: an expression or a `{{binding}}` (a key named at
     * run time), a model the platform ships itself under that key and of one of those [types] (the
     * `_flowableMailEvent` event, the `F07-deactivateUser` form — the report shows those as platform
     * externals, not missing models), no index yet, or no indexed model of the expected [types] at all — an
     * unindexed or empty project is never guessed at.
     */
    fun unknownKey(project: Project, value: String, types: Collection<ModelType>): Verdict? {
        if (!FlowableXmlKeyCatalog.isResolvableKey(value) || value.contains("{{")) return null
        if (types.any { PlatformDefaultModels.provides(it.id, value) }) return null
        // The cached index only — a highlighting pass must not build it.
        val index = project.service<FlowableModelIndexService>().cachedOrRequest() ?: return null
        val known = LinkedHashSet<String>()
        for (type in types) known.addAll(index.keySetOf(type))
        if (known.isEmpty() || value in known) return null
        if (types.flatMap { COMPATIBLE[it].orEmpty() }.any { value in index.keySetOf(it) }) return null

        val typeLabel = types.joinToString("/") { it.display }
        val suggestion = Suggestions.closest(value, known)
        val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
        // In a monorepo the index is one sub-project's; a key from another module is unknown *here*,
        // and the message has to say so or it reads as "this key does not exist".
        val scope = ProjectModelScope.label(project)?.let { " in $it" } ?: ""
        return Verdict("'$value' is not a known $typeLabel key$scope$hint", suggestion)
    }
}
