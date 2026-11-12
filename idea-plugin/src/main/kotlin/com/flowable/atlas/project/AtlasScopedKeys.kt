package com.flowable.atlas.project

import com.flowable.atlas.environment.AtlasConnectionIds

/**
 * The one rule for naming a workspace-local ([com.intellij.ide.util.PropertiesComponent]) key that
 * depends on the active Flowable sub-project and/or the selected connection:
 *
 * ```
 * base[.<subProject>][@<connectionId>]
 * ```
 *
 * There were two half-rules before, which is why this exists. `DesignPullSelection` appended
 * `.<subProject>` while `DesignPullService.LAST_PULL_PROPERTY` appended nothing, so a monorepo showed
 * one sub-project's ticked apps correctly and every sub-project's "last pull" as the same timestamp.
 * Now that a *connection* is part of the identity too — a workspace key and a ticked app only mean
 * anything relative to one server — getting this right in one place is the difference between "the
 * Hub shows what a pull will do" and "the Hub shows what some other environment did".
 *
 * A connection id can never contain [AtlasConnectionIds.SEPARATOR], so the two suffixes cannot be
 * confused with each other even when a sub-project path contains dots.
 */
internal object AtlasScopedKeys {

    fun scoped(base: String, subProject: String, connectionId: String = ""): String {
        val scope = subProject.trim().trim('/')
        val withScope = if (scope.isEmpty()) base else "$base.$scope"
        return if (connectionId.isBlank()) withScope else withScope + AtlasConnectionIds.SEPARATOR + connectionId
    }
}
