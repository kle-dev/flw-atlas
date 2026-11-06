package com.flowable.atlas.inspection

import com.flowable.atlas.completion.Vocabulary
import com.flowable.atlas.completion.VocabularyScope
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelPaths
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression

/**
 * Flags a message, signal, task key or activity id in Java that no model declares:
 *
 *     runtimeService.startProcessInstanceByMessage("DEMO-orderPlacd")   // throws: no such message
 *     runtimeService.signalEventReceived("DEMO-orderCancelld")          // silently reaches nobody
 *
 * The first throws at run time and the second does nothing at all, and neither is a compile error — the
 * same class of mistake as a misspelt model key, one position over. Messages and signals are judged
 * against every model in scope; a task key or activity id only when the chain names the one model it
 * belongs to (`…processDefinitionKey("DEMO-P001").taskDefinitionKey("…")`), since without it a task of a
 * process outside the project is a fine thing to query. `taskDefinitionKeyLike` takes a pattern and is
 * not judged; variables and form outcomes are not judged at all — a variable is whatever the running
 * instance was given.
 *
 * No verdict where one would be a guess: before the index exists, when the project declares no name of
 * that kind, when a model computes such a name at run time (`flowable:signalExpression`, a name holding
 * `${…}`), or when a process is known only from a file whose elements are not read. Not in test sources,
 * like the key inspection: a test sending an unknown signal may be asserting that nothing happens.
 */
class FlowableUnknownNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitLiteralExpression(literal: PsiLiteralExpression) {
                val value = literal.value as? String ?: return
                if (value.isBlank() || value.contains("\${")) return
                val at = VocabularyScope.siteForLiteral(literal) ?: return
                if (at.site.vocabulary !in JUDGED || at.site.methodName.endsWith("Like")) return
                if (literal.containingFile?.virtualFile?.path?.let(ModelPaths::isTestSource) == true) return
                val project = literal.project
                val index = project.service<FlowableModelIndexService>().cachedOrRequest() ?: return
                val (known, where) = knownNames(index, at, project) ?: return
                if (known.isEmpty() || value in known) return

                val suggestion = Suggestions.closest(value, known)
                val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
                val fixes = suggestion?.let { arrayOf<LocalQuickFix>(ReplaceStringLiteralFix(it, ReplaceStringLiteralFix.KNOWN_NAME)) }
                    ?: LocalQuickFix.EMPTY_ARRAY
                holder.registerProblem(
                    literal,
                    "'$value' is not a known ${at.site.vocabulary.display.lowercase()}$where$hint",
                    ProblemHighlightType.WARNING,
                    *fixes,
                )
            }
        }
    }

    /**
     * The names [at] may take and the words naming where they were looked for, or null for no verdict.
     * Messages and signals: the project's union, " in apps/x" in a monorepo. Tasks and activities: the one
     * model the chain names, " of DEMO-P001".
     */
    private fun knownNames(index: FlowableIndex, at: VocabularyScope.At, project: Project): Pair<Set<String>, String>? {
        val vocabulary = at.site.vocabulary
        return when (vocabulary) {
            Vocabulary.MESSAGE, Vocabulary.SIGNAL -> {
                if (index.processWithoutMembers) return null
                val dynamic = if (vocabulary == Vocabulary.MESSAGE) index.dynamicMessages else index.dynamicSignals
                if (dynamic) return null
                val names = if (vocabulary == Vocabulary.MESSAGE) index.messages else index.signals
                names to (ProjectModelScope.label(project)?.let { " in $it" } ?: "")
            }
            else -> {
                if (VocabularyScope.hasOr(at.call)) return null
                val key = VocabularyScope.scopeKey(at.call, at.site, project) ?: return null
                // A key no model has is the key inspection's finding, not a verdict on the task; a model
                // whose elements were not read (Design JSON) has none, which is no verdict either.
                val names = VocabularyScope.scopedModels(index, at.site, key)
                    .flatMapTo(LinkedHashSet()) { VocabularyScope.valuesOf(it.members, vocabulary) }
                names to " of $key"
            }
        }
    }

    private companion object {
        val JUDGED = setOf(Vocabulary.MESSAGE, Vocabulary.SIGNAL, Vocabulary.USER_TASK, Vocabulary.ACTIVITY)
    }
}
