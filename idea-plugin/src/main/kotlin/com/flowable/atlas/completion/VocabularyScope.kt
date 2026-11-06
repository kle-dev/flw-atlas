package com.flowable.atlas.completion

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ModelMembers
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression

/**
 * Which models a name at a [VocabularySite] belongs to — a message in `startProcessInstanceByMessage("…")`,
 * a task key in `…processDefinitionKey("X").taskDefinitionKey("…")`. Completion, Ctrl+click, the hover and
 * the unknown-name inspection all ask; one answer keeps the four from narrowing differently, so a name that
 * completes is never one that is flagged, and a flagged one never resolves.
 */
object VocabularyScope {

    /** A String literal argument at a vocabulary site, with the call it is an argument of. */
    data class At(val site: VocabularySite, val call: PsiMethodCallExpression)

    /** The vocabulary site [literal] is an argument at, or null. */
    fun siteForLiteral(literal: PsiLiteralExpression): At? {
        if (literal.value !is String) return null
        val argList = literal.parent as? PsiExpressionList ?: return null
        val call = argList.parent as? PsiMethodCallExpression ?: return null
        val site = SiteMatching.siteAt(call, argList.expressions.indexOf(literal)) as? VocabularySite ?: return null
        return At(site, call)
    }

    /**
     * The model key the chain narrows a scoped [site] to — the `X` of a sibling `processDefinitionKey("X")`
     * — or null when the site is not scoped or the chain names no constant key.
     */
    fun scopeKey(call: PsiMethodCallExpression?, site: VocabularySite, project: Project): String? {
        if (site.scopeKeyMethods.isEmpty() || call == null) return null
        val chain = FluentChain.collectCalls(call)
        return site.scopeKeyMethods.firstNotNullOfOrNull { m ->
            FluentChain.findCall(chain, m)?.let { FluentChain.constantStringArg(it, 0, project) }
        }
    }

    /**
     * Whether the chain combines conditions with `or()` — `processDefinitionKey("A")` then says nothing
     * about which model a task key belongs to, so no single model narrows it.
     */
    fun hasOr(call: PsiMethodCallExpression): Boolean =
        FluentChain.collectCalls(call).any { it.methodExpression.referenceName == "or" }

    /** [vocabulary]'s names in one model's [members]. */
    fun valuesOf(members: ModelMembers, vocabulary: Vocabulary): List<String> = when (vocabulary) {
        Vocabulary.MESSAGE -> members.messages
        Vocabulary.SIGNAL -> members.signals
        Vocabulary.VARIABLE -> members.variables
        Vocabulary.USER_TASK -> members.userTaskIds
        Vocabulary.ACTIVITY -> members.activityIds
        Vocabulary.OUTCOME -> members.formOutcomes
    }

    /** The model types that declare [vocabulary]'s names — a case's signal event listener names a signal too. */
    fun declaringTypes(vocabulary: Vocabulary): Set<ModelType> = when (vocabulary) {
        Vocabulary.MESSAGE -> setOf(ModelType.PROCESS)
        Vocabulary.SIGNAL, Vocabulary.USER_TASK, Vocabulary.ACTIVITY, Vocabulary.VARIABLE -> setOf(ModelType.PROCESS, ModelType.CASE)
        Vocabulary.OUTCOME -> setOf(ModelType.FORM, ModelType.PAGE)
    }

    /**
     * The model [scopeKey] names for [site], in every file the index holds it in — a BPMN file and the
     * Design JSON of the same process are one model, and only the first has its elements read. Empty when
     * no model of the site's scope types has that key.
     */
    fun scopedModels(index: FlowableIndex, site: VocabularySite, scopeKey: String): List<ModelEntry> =
        index.find(scopeKey).filter { it.type in site.scopeTypes }

    /**
     * The models declaring [value] as one of [site]'s names: only the scoped model when the chain names one
     * the index has, else every model of the declaring types. One entry per file — a BPMN file's messages
     * are copied onto each process in it.
     */
    fun declaringModels(index: FlowableIndex, site: VocabularySite, scopeKey: String?, value: String): List<ModelEntry> {
        val scoped = scopeKey?.let { scopedModels(index, site, it) }.orEmpty()
        val types = declaringTypes(site.vocabulary)
        val candidates = scoped.ifEmpty { index.allEntries().filter { it.type in types } }
        return candidates.filter { value in valuesOf(it.members, site.vocabulary) }.distinctBy { it.file }
    }
}
