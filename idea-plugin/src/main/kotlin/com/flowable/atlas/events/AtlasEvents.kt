package com.flowable.atlas.events

import com.flowable.atlas.environment.ConnectionKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * Project-level message-bus events the Atlas Hub (and any other status surface) listens to.
 * Publishers may fire from any thread — including under a read action during a completion-triggered
 * index build — so subscribers must only *schedule* work (e.g. poke a `SingleAlarm`), never touch
 * UI synchronously.
 */
interface AtlasEventsListener {

    /** The model index was invalidated or rebuilt — consumers re-read `cachedOrNull()`. */
    fun modelIndexUpdated() {}

    /** Atlas generation finished successfully; [written] lists every file produced. */
    fun artifactsGenerated(explorerHtml: Path?, written: List<Path>) {}

    /** A "Pull from Flowable Design" run finished. */
    fun designPullFinished(succeeded: Boolean) {}

    /** The active Flowable sub-project changed — consumers re-resolve their root / re-read settings. */
    fun activeSubProjectChanged() {}

    /**
     * *Some* Atlas settings page was applied. Coarse on purpose: four pages feed the Hub, the
     * playground and generation, and a per-field event surface only moves "who forgot to publish" one
     * level down — which is exactly how `GenerationConfigurable` came to change the Hub's output
     * folder without telling it. `AtlasProjectConfigurable` fires this for
     * every page from a `final override`, so a page cannot forget.
     */
    fun settingsApplied() {}

    /**
     * The IDE-wide environment/connection catalog changed — added, edited, removed or imported.
     * Fired for every open project, since every subscriber is project-scoped.
     */
    fun environmentsChanged() {}

    /**
     * This project now uses a different connection for [kind] — or its personal Design pull override
     * moved. Consumers re-resolve; the Design surfaces also drop their cached workspace/app lists,
     * which belong to the previous server.
     */
    fun connectionSelectionChanged(kind: ConnectionKind) {}
}

object AtlasEvents {

    @JvmField
    val TOPIC: Topic<AtlasEventsListener> =
        Topic.create("Flowable Atlas events", AtlasEventsListener::class.java)

    /**
     * Named publishers, so no caller hand-rolls `syncPublisher` and picks the wrong scope. The
     * catalog is application-level while the topic is project-level, so [environmentsChanged] fans
     * out over the open projects — the same shape `FlowableAtlasConfigurable` already uses to
     * invalidate every project's index.
     */
    fun settingsApplied(project: Project) = publish(project) { it.settingsApplied() }

    fun connectionSelectionChanged(project: Project, kind: ConnectionKind) =
        publish(project) { it.connectionSelectionChanged(kind) }

    fun environmentsChanged() {
        ProjectManager.getInstance().openProjects.forEach { publish(it) { l -> l.environmentsChanged() } }
    }

    private inline fun publish(project: Project, crossinline body: (AtlasEventsListener) -> Unit) {
        if (project.isDisposed) return
        body(project.messageBus.syncPublisher(TOPIC))
    }
}
