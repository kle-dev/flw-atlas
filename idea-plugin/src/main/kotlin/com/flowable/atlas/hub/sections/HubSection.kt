package com.flowable.atlas.hub.sections

import com.flowable.atlas.hub.HubSnapshot
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import javax.swing.JComponent

/** What a section needs from the panel that holds it. */
internal interface HubHost {
    val project: Project
    /** The component a popup or a data context anchors to. */
    val anchor: JComponent
    fun invokeAction(id: String)
    /** Schedule a re-gather; the panel debounces. */
    fun requestRefresh()
}

/**
 * One task block of the Hub: built once into the panel, re-applied from every snapshot. The panel puts
 * each under a foldable [title]; [id] is the key its folded state is remembered by.
 */
internal interface HubSection {
    val id: String
    val title: String
    /** The block's rows, without its title — the panel owns the fold. */
    fun build(panel: Panel)
    fun apply(s: HubSnapshot)
}
