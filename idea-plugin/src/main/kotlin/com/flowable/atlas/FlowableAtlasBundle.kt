package com.flowable.atlas

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.FlowableAtlasBundle"

/**
 * Message bundle for every user-visible plugin string that lives in `plugin.xml` (action/group
 * texts, configurable display names) plus strings shared across classes. One auditable file keeps
 * the naming conventions (title case for actions/dialogs, sentence case for notifications)
 * enforceable in a single place.
 */
object FlowableAtlasBundle : DynamicBundle(BUNDLE) {

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
