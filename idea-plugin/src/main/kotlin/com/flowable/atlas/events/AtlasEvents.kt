package com.flowable.atlas.events

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

    /** The Design connection settings were applied — status surfaces re-read them. */
    fun designSettingsChanged() {}
}

object AtlasEvents {

    @JvmField
    val TOPIC: Topic<AtlasEventsListener> =
        Topic.create("Flowable Atlas events", AtlasEventsListener::class.java)
}
