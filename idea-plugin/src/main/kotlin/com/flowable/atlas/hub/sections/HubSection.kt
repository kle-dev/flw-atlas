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

/** One task block of the Hub: built once into the panel, re-applied from every snapshot. */
internal interface HubSection {
    fun build(panel: Panel)
    fun apply(s: HubSnapshot)
}
