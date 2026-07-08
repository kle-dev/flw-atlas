package com.flowable.atlas.completion

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.index.OperationInfo
import com.flowable.atlas.index.ParamInfo
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.openapi.util.Key
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Autocompletes Flowable model keys, service operations, and operation value-fields at Flowable
 * public-API call sites in Java (see [FlowableApiCatalog]). Candidates are searchable by key or by
 * model name.
 *
 * Fires in two positions:
 *  - inside a string literal argument: `caseDefinitionKey("<caret>")` — inserts the bare key;
 *  - at a bare/partial argument (no quotes yet): `caseDefinitionKey(<caret>)` — inserts a quoted key.
 *
 * Pressing completion twice (invocation count ≥ 2) inside any string literal / argument lists
 * **all** model keys of every type, not just the ones matching the current API method.
 */
class FlowableJavaCompletionContributor : CompletionContributor() {

    init {
        val provider = Provider()
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(PsiLiteralExpression::class.java), provider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withParent(PsiReferenceExpression::class.java), provider)
    }

    private data class ArgContext(
        val prefix: String,
        val quote: Boolean,
        val call: PsiMethodCallExpression?,
        val argIndex: Int,
    )

    private class Provider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val position = parameters.position
            val ctx = resolveContext(position) ?: return

            val site = matchSite(ctx)
            // Infix matching: typing "0061" matches "KYC-DO-0061" (a mid-key fragment), not just a prefix.
            val base = result.withPrefixMatcher(FlowableInfixMatcher(ctx.prefix))
            val results = base.withRelevanceSorter(flowableFirstSorter(parameters, base.prefixMatcher))
            val service = position.project.service<FlowableModelIndexService>()
            val showAll = parameters.invocationCount >= 2

            when (site) {
                is KeySite ->
                    if (showAll) addAllKeys(results, service, ctx.quote, site.targetTypes.toSet())
                    else addKeys(results, service, site, ctx.quote)

                is OperationSite -> addOperations(results, service, ctx.call!!, site, position.project, ctx.quote)

                is ValueSite -> addValueFields(results, service, ctx.call!!, site, position.project, ctx.quote)

                is VocabularySite ->
                    if (FlowableAtlasSettings.getInstance().extraCompletions) addVocabulary(results, service, ctx.call, site, position.project, ctx.quote)

                is MemberSite ->
                    if (FlowableAtlasSettings.getInstance().extraCompletions) addMembers(results, service, ctx.call!!, site, position.project, ctx.quote)

                null ->
                    // Not a recognized API method. Offer all keys when completion is invoked twice,
                    // or (in a string literal) as soon as a non-empty prefix is typed — e.g. "DEMO-F".
                    if (showAll || (!ctx.quote && ctx.prefix.isNotEmpty())) {
                        addAllKeys(results, service, ctx.quote, emptySet())
                    }
            }
        }

        /** Ranks Flowable key items above everything else (beats type-matched local variables). */
        private fun flowableFirstSorter(parameters: CompletionParameters, matcher: PrefixMatcher): CompletionSorter {
            val weigher = object : LookupElementWeigher("flowableKeysFirst") {
                override fun weigh(element: LookupElement): Comparable<*> =
                    if (element.getUserData(FLOWABLE_KEY_MARKER) == true) 0 else 1
            }
            val default = CompletionSorter.defaultSorter(parameters, matcher)
            return try {
                default.weighBefore("priority", weigher)
            } catch (e: Throwable) {
                default.weighBefore("stats", weigher)
            }
        }

        /** Figure out the enclosing call + argument index, prefix, and whether we must quote the inserted key. */
        private fun resolveContext(position: PsiElement): ArgContext? {
            val dummy = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED

            val literal = PsiTreeUtil.getParentOfType(position, PsiLiteralExpression::class.java)
            if (literal != null && literal.value is String) {
                val call = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java)
                val argIndex = call?.argumentList?.expressions?.indexOfFirst { PsiTreeUtil.isAncestor(it, literal, false) } ?: -1
                val prefix = (literal.value as String).substringBefore(dummy)
                return ArgContext(prefix, quote = false, call = if (argIndex >= 0) call else null, argIndex = argIndex)
            }

            // Bare/partial argument, e.g. `caseDefinitionKey(<caret>)` (the completion dummy makes it
            // a reference expression). Its parent must be the call's argument list.
            val ref = position.parent as? PsiReferenceExpression ?: return null
            val argList = ref.parent as? PsiExpressionList ?: return null
            val call = argList.parent as? PsiMethodCallExpression ?: return null
            val argIndex = argList.expressions.indexOf(ref)
            if (argIndex < 0) return null
            return ArgContext(ref.text.substringBefore(dummy), quote = true, call = call, argIndex = argIndex)
        }

        private fun matchSite(ctx: ArgContext): ApiSite? {
            val call = ctx.call ?: return null
            if (ctx.argIndex < 0) return null
            val method = call.resolveMethod() ?: return null
            val declaring = method.containingClass ?: return null
            return FlowableApiCatalog.sitesForMethod(method.name).firstOrNull { s ->
                s.argIndex == ctx.argIndex && SiteMatching.isReceiver(declaring, s.receiverFqn)
            }
        }

        private fun addKeys(result: CompletionResultSet, service: FlowableModelIndexService, site: KeySite, quote: Boolean) {
            val seen = HashSet<String>()
            for (type in site.targetTypes) {
                for (entry in service.keysOfType(type)) {
                    if (seen.add(entry.key)) result.addElement(prioritized(keyLookup(entry, quote)))
                }
            }
        }

        /** Every key of every type; keys of [boostedTypes] are ranked above the rest. */
        private fun addAllKeys(
            result: CompletionResultSet,
            service: FlowableModelIndexService,
            quote: Boolean,
            boostedTypes: Set<ModelType>,
        ) {
            for (type in ModelType.entries) {
                val priority = if (type in boostedTypes) 100.0 else 0.0
                for (entry in service.keysOfType(type)) {
                    result.addElement(prioritized(keyLookup(entry, quote), priority))
                }
            }
        }

        private fun addOperations(
            result: CompletionResultSet,
            service: FlowableModelIndexService,
            call: PsiMethodCallExpression,
            site: OperationSite,
            project: Project,
            quote: Boolean,
        ) {
            val modelKey = resolveKey(call, site.keyMethod, project) ?: return
            for (op in operationsFor(service, modelKey, site.keyIsService)) {
                result.addElement(prioritized(operationLookup(op, modelKey, quote)))
                if (op.inputParameters.isNotEmpty()) {
                    result.addElement(prioritized(operationWithValuesLookup(op, modelKey, quote)))
                }
            }
        }

        private fun addValueFields(
            result: CompletionResultSet,
            service: FlowableModelIndexService,
            call: PsiMethodCallExpression,
            site: ValueSite,
            project: Project,
            quote: Boolean,
        ) {
            val modelKey = resolveKey(call, site.keyMethod, project) ?: return
            val operationKey = resolveKey(call, site.operationMethod, project)
            val operations = operationsFor(service, modelKey, site.keyIsService)
            val params: List<ParamInfo> = if (operationKey != null) {
                operations.firstOrNull { it.key == operationKey }?.inputParameters.orEmpty()
            } else {
                operations.flatMap { it.inputParameters }.distinctBy { it.name }
            }
            val context = if (operationKey != null) "$modelKey · $operationKey" else modelKey
            for (param in params) result.addElement(prioritized(paramLookup(param, quote, context)))
        }

        private fun operationsFor(service: FlowableModelIndexService, modelKey: String, isService: Boolean) =
            if (isService) service.operationsOfService(modelKey) else service.operationsOf(modelKey)

        private fun resolveKey(call: PsiMethodCallExpression, method: String, project: Project, argIndex: Int = 0): String? {
            val chain = FluentChain.collectCalls(call)
            val keyCall = FluentChain.findCall(chain, method) ?: return null
            return FluentChain.constantStringArg(keyCall, argIndex, project)
        }

        /**
         * A vocabulary (messages, signals, variables, task keys, activity ids). Narrowed to a single
         * model when the site is scoped and the chain carries the sibling key; otherwise the
         * project-wide union.
         */
        private fun addVocabulary(
            result: CompletionResultSet,
            service: FlowableModelIndexService,
            call: PsiMethodCallExpression?,
            site: VocabularySite,
            project: Project,
            quote: Boolean,
        ) {
            val scoped = scopedVocabulary(service, call, site, project)
            val values: Collection<String> = scoped?.values ?: when (site.vocabulary) {
                Vocabulary.MESSAGE -> service.messages()
                Vocabulary.SIGNAL -> service.signals()
                Vocabulary.VARIABLE -> service.variables()
                Vocabulary.USER_TASK -> service.userTaskIds()
                Vocabulary.ACTIVITY -> service.activityIds()
            }
            val typeText = scoped?.let { site.vocabulary.display + " · " + it.modelKey } ?: site.vocabulary.display
            for (v in values) result.addElement(prioritized(memberLookup(v, typeText, quote)))
        }

        /** One model's members carried alongside the key it was resolved from (for the type text). */
        private class ScopedValues(val modelKey: String, val values: List<String>)

        /**
         * If [site] is scoped and the chain carries one of its sibling key calls, resolve that model's
         * members for the vocabulary; null otherwise (→ caller uses the project-wide union).
         */
        private fun scopedVocabulary(
            service: FlowableModelIndexService,
            call: PsiMethodCallExpression?,
            site: VocabularySite,
            project: Project,
        ): ScopedValues? {
            if (site.scopeKeyMethods.isEmpty() || call == null) return null
            val key = site.scopeKeyMethods.firstNotNullOfOrNull { m -> resolveKey(call, m, project) } ?: return null
            val members = service.scopedMembers(key, site.scopeTypes) ?: return null
            val values = when (site.vocabulary) {
                Vocabulary.USER_TASK -> members.userTaskIds
                Vocabulary.ACTIVITY -> members.activityIds
                Vocabulary.VARIABLE -> members.variables
                else -> return null
            }
            return values.ifEmpty { null }?.let { ScopedValues(key, it) }
        }

        /** Members (DMN decision variables / event payload) of the model resolved from the sibling key. */
        private fun addMembers(
            result: CompletionResultSet,
            service: FlowableModelIndexService,
            call: PsiMethodCallExpression,
            site: MemberSite,
            project: Project,
            quote: Boolean,
        ) {
            val key = resolveKey(call, site.keyMethod, project, site.keyArgIndex) ?: return
            val members = when (site.memberKind) {
                MemberKind.DECISION_VARIABLE -> service.decisionVariablesOf(key)
                MemberKind.EVENT_PAYLOAD -> service.payloadOf(key)
            }
            for (m in members) result.addElement(prioritized(memberLookup(m, key, quote)))
        }

        // ---- lookup element rendering ----

        private fun prioritized(builder: LookupElementBuilder, priority: Double = 1000.0): LookupElement {
            builder.putUserData(FLOWABLE_KEY_MARKER, true)
            val element = PrioritizedLookupElement.withPriority(builder, priority)
            element.putUserData(FLOWABLE_KEY_MARKER, true)
            return element
        }

        private fun keyLookup(entry: ModelEntry, quote: Boolean): LookupElementBuilder {
            var b = LookupElementBuilder.create(entry.key).withTypeText(entry.type.display, true)
            if (entry.name != entry.key) b = b.withTailText("  ${entry.name}", true)
            b = b.withLookupStrings(searchTokens(entry.key, entry.name))
            if (quote) b = b.withInsertHandler(QuoteInsertHandler)
            return b
        }

        private fun operationLookup(op: OperationInfo, definitionKey: String, quote: Boolean): LookupElementBuilder {
            val tail = buildString {
                op.type?.let { append("  [").append(it).append(']') }
                if (op.name != null && op.name != op.key) append("  ").append(op.name)
            }
            // Right-aligned type text shows the owning data object so the operation can be verified.
            var b = LookupElementBuilder.create(op.key).withTypeText(definitionKey, true)
            if (tail.isNotEmpty()) b = b.withTailText(tail, true)
            b = b.withLookupStrings(searchTokens(op.key, op.name))
            if (quote) b = b.withInsertHandler(QuoteInsertHandler)
            return b
        }

        /** An operation variant that also inserts a `.value("field", placeholder)` call for every input. */
        private fun operationWithValuesLookup(op: OperationInfo, definitionKey: String, quote: Boolean): LookupElementBuilder {
            val n = op.inputParameters.size
            return LookupElementBuilder.create(op.key)
                .withTypeText(definitionKey, true)
                .withTailText("  + insert $n value${if (n == 1) "" else "s"} (placeholders)", true)
                .withLookupStrings(searchTokens(op.key, op.name))
                .withInsertHandler(OperationValuesInsertHandler(op, quote))
        }

        private fun paramLookup(param: ParamInfo, quote: Boolean, context: String): LookupElementBuilder {
            // Right-aligned type text shows the owning data object (· operation); the value's own
            // type is a dim tail so the field's affiliation is obvious.
            var b = LookupElementBuilder.create(param.name).withTypeText(context, true)
            param.type?.let { b = b.withTailText("  [$it]", true) }
            if (quote) b = b.withInsertHandler(QuoteInsertHandler)
            return b
        }

        /** A plain vocabulary/member value (message, signal, variable, decision variable, …). */
        private fun memberLookup(value: String, typeText: String, quote: Boolean): LookupElementBuilder {
            var b = LookupElementBuilder.create(value).withTypeText(typeText, true)
            if (quote) b = b.withInsertHandler(QuoteInsertHandler)
            return b
        }

        /** Key/name plus name/key word tokens, so the item is findable by key OR by (part of the) name. */
        private fun searchTokens(key: String, name: String?): Set<String> = KeyLookup.searchTokens(key, name)
    }

    /**
     * Inserts the operation key, then appends a `.value("field", placeholder)` call for every input
     * parameter of the operation ("" for String types, null otherwise).
     */
    private class OperationValuesInsertHandler(
        private val op: OperationInfo,
        private val quote: Boolean,
    ) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val keyText = if (quote) "\"${op.key}\"" else op.key
            document.replaceString(context.startOffset, context.tailOffset, keyText)
            context.commitDocument()

            val leaf = context.file.findElementAt(context.startOffset) ?: return
            val call = PsiTreeUtil.getParentOfType(leaf, PsiMethodCallExpression::class.java) ?: return
            val insertAt = call.textRange.endOffset
            val chain = op.inputParameters.joinToString("") { p ->
                ".value(\"${p.name}\", ${placeholder(p.type)})\n"
            }
            if (chain.isEmpty()) return

            document.insertString(insertAt, "\n$chain")
            context.commitDocument()
            CodeStyleManager.getInstance(context.project)
                .reformatText(context.file, insertAt, (insertAt + chain.length + 1).coerceAtMost(document.textLength))
        }

        private fun placeholder(type: String?): String =
            if (type != null && type.equals("string", ignoreCase = true)) "\"\"" else "null"
    }

    /** Wraps the inserted value in double quotes (used when completing a bare, unquoted argument). */
    private object QuoteInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val text = item.lookupString
            context.document.replaceString(context.startOffset, context.tailOffset, "\"$text\"")
            context.commitDocument()
            context.editor.caretModel.moveToOffset(context.startOffset + text.length + 2)
        }
    }
}

/** Marks a lookup element as a Flowable key so the relevance sorter can rank it first. */
private val FLOWABLE_KEY_MARKER: Key<Boolean> = Key.create("com.flowable.atlas.completion.flowableKeyItem")


