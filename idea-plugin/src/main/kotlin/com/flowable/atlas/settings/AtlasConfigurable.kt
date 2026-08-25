package com.flowable.atlas.settings

import com.flowable.atlas.events.AtlasEvents
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Base class for every Atlas settings page, existing for exactly one reason: to make "the page was
 * applied and nothing was told about it" impossible.
 *
 * That was a real defect, not a hypothetical one. `GenerationConfigurable` had no `onApply` hook at
 * all, so changing the Atlas output folder left the Hub listing artifacts from the old one until some
 * unrelated event happened to fire — which is most of what "I configure something in Settings and it
 * has no effect in the Atlas Hub" was made of.
 *
 * [apply] is **`final`**: a new page cannot forget, because forgetting is a compile error rather than
 * something a reviewer has to notice. Existing `onApply { … }` blocks are unaffected — `super.apply()`
 * runs them first, so `ExpressionsConfigurable`'s daemon restart and `FlowableAtlasConfigurable`'s
 * index invalidation still happen before anyone is notified.
 *
 * The event is deliberately coarse. Four pages feed the Hub, the playground and generation, and a
 * per-field event surface only moves the question "who forgot to publish?" one level down. A settings
 * Apply is rare and cheap to answer.
 */
abstract class AtlasProjectConfigurable(
    protected val project: Project,
    displayName: String,
    id: String,
) : BoundSearchableConfigurable(displayName, helpTopic = "", _id = id) {

    final override fun apply() {
        super.apply()          // the DSL bindings, and every onApply the page declared
        doApply()
        AtlasEvents.settingsApplied(project)
    }

    /**
     * Page-specific work, run after the DSL bindings and before the notification. Pages override this
     * instead of [apply], which is what keeps "applied but nobody told" unrepresentable.
     */
    protected open fun doApply() {}
}

/**
 * The application-level counterpart of [AtlasProjectConfigurable]. The topic is project-level, so an
 * app-wide change fans out over the open projects — the same shape [FlowableAtlasConfigurable] already
 * used to invalidate every project's model index.
 */
abstract class AtlasApplicationConfigurable(
    displayName: String,
    id: String,
) : BoundSearchableConfigurable(displayName, helpTopic = "", _id = id) {

    final override fun apply() {
        super.apply()
        doApply()
        ProjectManager.getInstance().openProjects.forEach { AtlasEvents.settingsApplied(it) }
    }

    /** Page-specific work, run after the DSL bindings and before the notification. */
    protected open fun doApply() {}
}
