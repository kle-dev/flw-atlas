package com.flowable.atlas.script.completion

import com.flowable.atlas.completion.FlowableInfixMatcher
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.script.ScriptBindingsCatalog
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptRoot
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext

/**
 * Catalog-driven completion for Flowable script bodies: `execution`, `task`, `planItemInstance`,
 * `flw` & co. are dynamic bindings the Groovy/JS PSI knows nothing about, so the real language
 * completion has nothing to offer after `execution.` — this contributor fills that gap from
 * [ScriptBindingsCatalog], per script context.
 *
 * Registered for `language="any"` on purpose: the playground field may host Groovy, JavaScript or
 * the plain-text fallback (this plugin has no dependency on the language plugins), and the injected
 * fragments inherit whatever language the injector resolved. The context gate is cheap: a user-data
 * key stamped by the Script Playground, or the injection host of a Flowable model file — anything
 * else returns before doing work.
 */
class ScriptBindingsCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet,
        ) {
            val scriptContext = contextOf(parameters) ?: return
            val roots = ScriptBindingsCatalog.rootsFor(scriptContext)
            if (roots.isEmpty()) return
            val pos = classify(parameters.originalFile.text, parameters.offset)
            val out = result.withPrefixMatcher(FlowableInfixMatcher(pos.prefix))
            when {
                pos.receiver == null ->
                    for (root in roots.values) if (!root.hidden) out.addElement(rootLookup(root))
                pos.outerReceiver == null -> {
                    val root = roots[pos.receiver] ?: return
                    addMembers(root, out)
                }
                else -> {
                    val sub = roots[pos.outerReceiver]?.subObjects?.get(pos.receiver) ?: return
                    addMembers(sub, out)
                }
            }
        }

        /** The playground stamps its context on the PsiFile; model files derive it from the host. */
        private fun contextOf(parameters: CompletionParameters): ScriptContext? =
            ScriptScope.contextOf(parameters.originalFile)

        private fun addMembers(obj: ScriptRoot, out: CompletionResultSet) {
            for ((name, sig) in obj.members.orEmpty()) {
                out.addElement(prioritized(LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Method)
                    .withTailText("($sig)", true)
                    .withTypeText(obj.name, true)
                    .withInsertHandler(CallInsertHandler)))
            }
            for (sub in obj.subObjects.values) {
                out.addElement(prioritized(LookupElementBuilder.create(sub.name)
                    .withIcon(AllIcons.Nodes.Package)
                    .withTailText("  ${sub.doc}", true)
                    .withTypeText(obj.name, true)
                    .withInsertHandler(DotInsertHandler)))
            }
        }

        private fun rootLookup(root: ScriptRoot): LookupElement {
            val navigable = root.members != null || root.subObjects.isNotEmpty()
            var b = LookupElementBuilder.create(root.name)
                .withIcon(if (root.bean) AllIcons.Nodes.Plugin else AllIcons.Nodes.Tag)
                .withTypeText(if (root.bean) "Spring bean" else "binding", true)
                .withTailText("  ${root.doc}", true)
            if (navigable) b = b.withInsertHandler(DotInsertHandler)
            return prioritized(b)
        }

        /** The engine bindings belong above the language's generic suggestions. */
        private fun prioritized(element: LookupElement): LookupElement =
            PrioritizedLookupElement.withPriority(element, 100.0)

        /** Caret position, classified from the raw text left of the caret (the dummy identifier the
         *  platform inserts sits right of it): the typed prefix and up to two dot-receivers. */
        private fun classify(text: String, offset: Int): Position {
            var i = minOf(offset, text.length)
            val prefixStart = scanIdentLeft(text, i)
            val prefix = text.substring(prefixStart, i)
            i = skipWs(text, prefixStart)
            if (i > 0 && text[i - 1] == '.') {
                i-- // the dot
                if (i > 0 && text[i - 1] == '?') i--   // Groovy safe navigation
                i = skipWs(text, i)
                val recStart = scanIdentLeft(text, i)
                if (recStart == i) return Position(prefix, null, null)
                val receiver = text.substring(recStart, i)
                var j = skipWs(text, recStart)
                if (j > 0 && text[j - 1] == '.') {
                    j--
                    if (j > 0 && text[j - 1] == '?') j--
                    j = skipWs(text, j)
                    val outerStart = scanIdentLeft(text, j)
                    if (outerStart < j) return Position(prefix, receiver, text.substring(outerStart, j))
                }
                return Position(prefix, receiver, null)
            }
            return Position(prefix, null, null)
        }

        private fun scanIdentLeft(text: String, end: Int): Int {
            var s = end
            while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '_')) s--
            return s
        }

        private fun skipWs(text: String, end: Int): Int {
            var s = end
            while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
            return s
        }

        private data class Position(val prefix: String, val receiver: String?, val outerReceiver: String?)
    }
}

/** Insert `()` and put the caret between the parens (idempotent when they already exist). */
private object CallInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val tail = context.tailOffset
        if (charAt(context, tail) != '(') context.document.insertString(tail, "()")
        context.editor.caretModel.moveToOffset(tail + 1)
        context.commitDocument()
    }
}

/** Append `.` and chain straight into the next completion level. */
private object DotInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val tail = context.tailOffset
        if (charAt(context, tail) != '.') context.document.insertString(tail, ".")
        context.editor.caretModel.moveToOffset(tail + 1)
        context.commitDocument()
        AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }
}

private fun charAt(context: InsertionContext, offset: Int): Char? =
    if (offset < context.document.textLength) context.document.charsSequence[offset] else null
