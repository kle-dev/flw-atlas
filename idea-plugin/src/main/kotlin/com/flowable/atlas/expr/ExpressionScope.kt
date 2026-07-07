package com.flowable.atlas.expr

import com.intellij.openapi.util.Key

/**
 * User-data keys that let the playground tool window tell completion/validation which model's
 * variables to scope to (there is no injection host to derive it from in a scratch field).
 */
object ExpressionScope {
    /** The model key selected in the playground's "Scope to model" picker, if any. */
    val MODEL_KEY: Key<String> = Key.create("com.flowable.atlas.expr.scopeModelKey")
}
