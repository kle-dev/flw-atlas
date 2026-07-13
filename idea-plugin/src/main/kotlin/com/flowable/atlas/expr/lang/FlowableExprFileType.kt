package com.flowable.atlas.expr.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * File types for the two expression dialects. These are registration/scratch/test artifacts — no
 * user-facing Flowable model file uses these extensions; expressions in real projects are reached via
 * language injection. Registering a [LanguageFileType] forces the language singleton to load and lets
 * tests do `configureByText("t.flowable-be", …)`. Each dialect carries its own icon (server vs web —
 * the same pair the playground's dialect toggles use) so the two are distinguishable at a glance.
 */
abstract class FlowableExprFileType(language: FlowableExprLanguage) : LanguageFileType(language)

object FlowableBackendExprFileType : FlowableExprFileType(FlowableBackendExprLanguage) {
    override fun getName(): String = "Flowable Backend Expression"
    override fun getDescription(): String = "Flowable backend (JUEL) expression"
    override fun getDefaultExtension(): String = "flowable-be"
    override fun getIcon(): Icon = AllIcons.Webreferences.Server
}

object FlowableFrontendExprFileType : FlowableExprFileType(FlowableFrontendExprLanguage) {
    override fun getName(): String = "Flowable Frontend Expression"
    override fun getDescription(): String = "Flowable frontend (form) expression"
    override fun getDefaultExtension(): String = "flowable-fe"
    override fun getIcon(): Icon = AllIcons.General.Web
}
