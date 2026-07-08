package com.flowable.atlas.expr.completion

import com.flowable.atlas.completion.withLookupStrings
import com.flowable.atlas.completion.FlowableInfixMatcher
import com.flowable.atlas.completion.KeyLookup
import com.flowable.atlas.expr.ExprCompletionContext
import com.flowable.atlas.expr.ExpressionContext
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.catalog.ExprFunction
import com.flowable.atlas.expr.catalog.ExprRoot
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext

/**
 * Completion for Flowable expressions (both dialects — the dialect is read from the fragment's
 * language). Offers, depending on the caret context ([ExpressionContext]):
 *  - at the root: root objects, function namespaces / `flw`, no-prefix functions, and the project's
 *    process variables / form fields (scoped to the enclosing model when reachable);
 *  - after a backend namespace + `:` : that namespace's functions;
 *  - after `flw.` (or `flw.remove.` / `flw.JSON.`): the frontend members.
 *
 * Registered per dialect language in plugin.xml.
 */
class FlowableExpressionCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val file = parameters.position.containingFile ?: return
            val dialect = dialectOf(file.language) ?: return
            val ctx = ExpressionContext.classify(file.text, parameters.offset, dialect)
            val out = result.withPrefixMatcher(FlowableInfixMatcher(ctx.prefix))
            val service = parameters.position.project.service<FlowableModelIndexService>()

            when (ctx) {
                is ExprCompletionContext.Root -> addRoot(out, dialect, service, parameters)
                is ExprCompletionContext.AfterNamespace ->
                    if (dialect == ExpressionDialect.BACKEND) addFunctions(out, FlowableExpressionCatalog.backendFunctionsForPrefix(ctx.namespace), dialect, ctx.namespace)
                is ExprCompletionContext.AfterDot -> when (dialect) {
                    ExpressionDialect.FRONTEND -> addFrontendMembers(out, ctx.receiver, parameters.position.project)
                    ExpressionDialect.BACKEND -> addBackendMembers(out, ctx.receiver, parameters.position.project)
                }
            }
        }

        /** After `bean.` in a backend expression, offer the resolved Java class's methods + properties. */
        private fun addBackendMembers(out: CompletionResultSet, receiver: String, project: com.intellij.openapi.project.Project) {
            for (m in BackendBeanResolver.membersOf(receiver, project)) {
                var b = LookupElementBuilder.create(m.name).withTypeText(m.typeText, true)
                b = when (m.kind) {
                    BackendBeanResolver.Kind.METHOD -> b.withInsertHandler(CallInsertHandler).withTailText(m.paramText, true)
                    BackendBeanResolver.Kind.PROPERTY -> b.withTailText("  property", true)
                }
                out.addElement(b)
            }
        }

        private fun addRoot(
            out: CompletionResultSet,
            dialect: ExpressionDialect,
            service: FlowableModelIndexService,
            parameters: CompletionParameters,
        ) {
            for (root in FlowableExpressionCatalog.roots(dialect)) out.addElement(rootLookup(root, dialect))

            if (dialect == ExpressionDialect.BACKEND) {
                for (prefix in canonicalPrefixes()) out.addElement(namespaceLookup(prefix))
                addFunctions(out, FlowableExpressionCatalog.backendNoPrefixFunctions(), dialect, null)
                addReferencedIdentifiers(out, service, parameters.position.project)
            }

            if (dialect == ExpressionDialect.FRONTEND) addCustomRoot(out, parameters.position.project)

            val varLabel = if (dialect == ExpressionDialect.FRONTEND) "field" else "variable"
            for (v in scopeVariables(parameters, dialect, service)) out.addElement(variableLookup(v, varLabel))
        }

        /** Project custom functions (externals.additionalData) at the frontend root: each namespace
         *  (`flowkyc` → `flowkyc.` + re-popup) and each bare top-level helper. `flw.*` custom members
         *  are offered after `flw.` (see [addFrontendMembers]). */
        private fun addCustomRoot(out: CompletionResultSet, project: com.intellij.openapi.project.Project) {
            val cat = FlowableCustomFunctions.getInstance(project).catalog() ?: return
            for (ns in cat.namespaces.keys.sorted())
                out.addElement(
                    LookupElementBuilder.create(ns).withTypeText("custom 🧩", true)
                        .withTailText("  $ns.…", true).withInsertHandler(DotInsertHandler),
                )
            for (fn in cat.topLevel.sorted()) out.addElement(customFnLookup(fn, cat.signatureOf(fn)))
        }

        /**
         * Identifiers scraped from the project's expressions ([FlowableIndex.referencedIdentifiers]) are a
         * mixed bag — bean names, but also method names (`getVariable`), root objects and function names.
         * Only label as "bean" the ones that actually resolve to a Java class; drop names already offered
         * as roots / namespaces / functions / variables; offer the rest neutrally as "referenced".
         */
        private fun addReferencedIdentifiers(out: CompletionResultSet, service: FlowableModelIndexService, project: com.intellij.openapi.project.Project) {
            val exclude = HashSet<String>()
            exclude += FlowableExpressionCatalog.rootNames(ExpressionDialect.BACKEND)
            exclude += FlowableExpressionCatalog.backendPrefixes()
            exclude += FlowableExpressionCatalog.backendNoPrefixFunctions().flatMap { it.allNames }
            for (prefix in canonicalPrefixes()) {
                exclude += FlowableExpressionCatalog.backendFunctionsForPrefix(prefix).flatMap { it.allNames }
            }
            exclude += service.variables()   // real variables are offered separately, properly labelled

            val projectScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            for (id in service.index().referencedIdentifiers) {
                if (id.length < 2 || id in exclude) continue
                val type = if (BackendBeanResolver.resolveClasses(id, project, projectScope).isNotEmpty()) "bean" else "referenced"
                out.addElement(referenceLookup(id, type))
            }
        }

        private fun addFunctions(out: CompletionResultSet, functions: List<ExprFunction>, dialect: ExpressionDialect, typeText: String?) {
            for (f in functions) {
                var b = LookupElementBuilder.create(f.name)
                    .withInsertHandler(CallInsertHandler)
                    .withLookupStrings(KeyLookup.searchTokens(f.name, null))
                typeText?.let { b = b.withTypeText(it, true) }
                if (dialect == ExpressionDialect.BACKEND && f.prefix != null) b = b.withPresentableText("${f.prefix}:${f.name}")
                f.doc?.let { b = b.withTailText("  $it", true) }
                if (f.aliases.isNotEmpty()) b = b.withLookupStrings(f.aliases.toSet())
                out.addElement(b)
            }
        }

        private fun addFrontendMembers(out: CompletionResultSet, receiver: String, project: com.intellij.openapi.project.Project) {
            val members = when (receiver) {
                FlowableExpressionCatalog.FRONTEND_NS -> FlowableExpressionCatalog.frontendMembers()
                in FlowableExpressionCatalog.frontendNestingMembers -> FlowableExpressionCatalog.frontendSubMembers(receiver)
                else -> emptyList()
            }
            for (f in members) {
                val nestsFurther = receiver == FlowableExpressionCatalog.FRONTEND_NS && f.name in FlowableExpressionCatalog.frontendNestingMembers
                var b = LookupElementBuilder.create(f.name)
                    .withInsertHandler(if (nestsFurther) DotInsertHandler else CallInsertHandler)
                    .withLookupStrings(KeyLookup.searchTokens(f.name, null))
                f.doc?.let { b = b.withTailText("  $it", true) }
                out.addElement(b)
            }
            // Project custom members: `flw.<custom>` and `<namespace>.<member>` from externals.additionalData.
            val cat = FlowableCustomFunctions.getInstance(project).catalog() ?: return
            val qualify = if (receiver == FlowableExpressionCatalog.FRONTEND_NS) "flw." else "$receiver."
            val custom = if (receiver == FlowableExpressionCatalog.FRONTEND_NS) cat.flw else cat.namespaces[receiver]
            custom?.sorted()?.forEach { out.addElement(customFnLookup(it, cat.signatureOf(qualify + it))) }
        }

        /** A custom-function lookup that lists its parameters as tail text and, on selection, inserts
         *  `(params)` with the parameters selected so they're easy to fill in. */
        private fun customFnLookup(name: String, params: String?): LookupElement {
            var b = LookupElementBuilder.create(name).withTypeText("custom 🧩", true)
                .withInsertHandler(if (params.isNullOrEmpty()) CallInsertHandler else CustomParamsInsertHandler(params))
                .withLookupStrings(KeyLookup.searchTokens(name, null))
            if (params != null) b = b.withTailText("($params)", true)
            return b
        }

        // ---- lookup element builders ----

        private fun rootLookup(root: ExprRoot, dialect: ExpressionDialect): LookupElement {
            var b = LookupElementBuilder.create(root.name).withTypeText("root", true)
            root.doc?.let { b = b.withTailText("  $it", true) }
            if (dialect == ExpressionDialect.FRONTEND && root.name == FlowableExpressionCatalog.FRONTEND_NS) {
                b = b.withInsertHandler(DotInsertHandler)
            }
            return b
        }

        private fun namespaceLookup(prefix: String): LookupElement =
            LookupElementBuilder.create(prefix)
                .withTypeText("namespace", true)
                .withTailText("  ${prefix}:…", true)
                .withInsertHandler(ColonInsertHandler)

        private fun referenceLookup(name: String, typeText: String): LookupElement =
            LookupElementBuilder.create(name).withTypeText(typeText, true)

        private fun variableLookup(name: String, label: String): LookupElement =
            LookupElementBuilder.create(name).withTypeText(label, true)

        /** Distinct canonical backend prefixes (so `variables`/`vars`/`var` show once as `variables`). */
        private fun canonicalPrefixes(): List<String> =
            FlowableExpressionCatalog.backendPrefixes().mapNotNull { FlowableExpressionCatalog.resolvePrefix(it) }.distinct()

        /**
         * The variables/fields to offer: the enclosing model's members when we can resolve it (the
         * injection host's model file, or the playground's selected model key), then the project-wide
         * union as a fallback.
         */
        private fun scopeVariables(
            parameters: CompletionParameters,
            dialect: ExpressionDialect,
            service: FlowableModelIndexService,
        ): Collection<String> {
            val scoped = LinkedHashSet<String>()
            scopeModelKey(parameters, service)?.let { key ->
                val members = if (dialect == ExpressionDialect.FRONTEND) {
                    service.scopedMembers(key, listOf(ModelType.FORM))?.formFields
                } else {
                    service.scopedMembers(key, listOf(ModelType.PROCESS, ModelType.CASE))?.variables
                }
                members?.let { scoped.addAll(it) }
            }
            scoped.addAll(service.variables())
            return scoped
        }

        /** Resolve the model key to scope to: playground user-data first, else the injection host's file. */
        private fun scopeModelKey(parameters: CompletionParameters, service: FlowableModelIndexService): String? {
            val original: PsiFile? = parameters.originalFile
            original?.getUserData(ExpressionScope.MODEL_KEY)?.let { return it }
            val host = original?.let { InjectedLanguageManager.getInstance(it.project).getInjectionHost(it) } ?: return null
            val vFile = host.containingFile?.virtualFile ?: return null
            return service.index().allDistinct().firstOrNull { it.file == vFile }?.key
        }
    }

    /** Appends `()` and puts the caret between the parentheses. */
    private object CallInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val tail = context.tailOffset
            if (charAt(context, tail) != '(') context.document.insertString(tail, "()")
            context.editor.caretModel.moveToOffset(tail + 1)
            context.commitDocument()
        }
    }

    /** Inserts `(param0, param1)` and selects the parameter text, so the needed arguments are shown
     *  and easy to replace with actual values. */
    private class CustomParamsInsertHandler(private val params: String) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val tail = context.tailOffset
            if (charAt(context, tail) == '(') { context.editor.caretModel.moveToOffset(tail + 1); context.commitDocument(); return }
            context.document.insertString(tail, "($params)")
            context.editor.caretModel.moveToOffset(tail + 1)
            context.editor.selectionModel.setSelection(tail + 1, tail + 1 + params.length)
            context.commitDocument()
        }
    }

    /** Appends `:` and re-triggers completion (for a backend namespace). */
    private object ColonInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val tail = context.tailOffset
            if (charAt(context, tail) != ':') context.document.insertString(tail, ":")
            context.editor.caretModel.moveToOffset(tail + 1)
            context.commitDocument()
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }

    /** Appends `.` and re-triggers completion (for `flw` / a nesting frontend member). */
    private object DotInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val tail = context.tailOffset
            if (charAt(context, tail) != '.') context.document.insertString(tail, ".")
            context.editor.caretModel.moveToOffset(tail + 1)
            context.commitDocument()
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }
}

private fun charAt(context: InsertionContext, offset: Int): Char? {
    val seq = context.document.charsSequence
    return if (offset in 0 until seq.length) seq[offset] else null
}

