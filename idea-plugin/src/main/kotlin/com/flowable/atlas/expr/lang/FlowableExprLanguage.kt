package com.flowable.atlas.expr.lang

import com.flowable.atlas.expr.ExpressionDialect
import com.intellij.lang.Language

/**
 * The Flowable expression language. One abstract language with two singleton dialect instances so a
 * single grammar/parser serves both — the injector, the tool window and the annotator/completion all
 * dispatch on [dialect] (derived from the PSI file's [Language]).
 */
abstract class FlowableExprLanguage(id: String, val dialect: ExpressionDialect) : Language(id)

object FlowableBackendExprLanguage :
    FlowableExprLanguage("FlowableBackendExpr", ExpressionDialect.BACKEND)

object FlowableFrontendExprLanguage :
    FlowableExprLanguage("FlowableFrontendExpr", ExpressionDialect.FRONTEND)

/** The dialect of a language, or null if it is not a Flowable expression language. */
fun dialectOf(language: Language): ExpressionDialect? = (language as? FlowableExprLanguage)?.dialect

/** The language instance for a dialect. */
fun languageOf(dialect: ExpressionDialect): FlowableExprLanguage = when (dialect) {
    ExpressionDialect.BACKEND -> FlowableBackendExprLanguage
    ExpressionDialect.FRONTEND -> FlowableFrontendExprLanguage
}
