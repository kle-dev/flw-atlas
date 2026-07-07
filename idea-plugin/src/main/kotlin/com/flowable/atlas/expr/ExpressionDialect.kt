package com.flowable.atlas.expr

/**
 * The two Flowable expression flavours the plugin understands.
 *
 *  - [BACKEND]  — JUEL / Spring-EL, delimited by `${…}` / `#{…}`, evaluated by the Java engine.
 *                 Functions are written `prefix:name(args)` (e.g. `${date:now()}`), and a rich set
 *                 of implicit root objects is available (`execution`, `task`, beans, …).
 *  - [FRONTEND] — the client-side form expression language, delimited by `{{…}}`, evaluated in the
 *                 browser (jsep). Functions live under the `flw` namespace (`{{flw.sum(items)}}`),
 *                 the custom `|>` pipe operator is allowed, and the root scope is the form payload.
 *
 * The two share one grammar; they differ only in delimiter (chosen by the injector), function-call
 * form, the `|>` operator, and the [com.flowable.atlas.expr.catalog.FlowableExpressionCatalog].
 */
enum class ExpressionDialect(val display: String, val open: String, val close: String) {
    BACKEND("Backend (\${…})", "\${", "}"),
    FRONTEND("Frontend ({{…}})", "{{", "}}"),
}
