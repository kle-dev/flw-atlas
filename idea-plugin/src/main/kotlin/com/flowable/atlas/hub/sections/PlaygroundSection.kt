package com.flowable.atlas.hub.sections

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.hub.EnvironmentPicker
import com.flowable.atlas.hub.HubSnapshot
import com.intellij.ui.dsl.builder.Panel

/**
 * The runtime the Atlas Playground evaluates against, and the button that opens it. Independent of the
 * Design pull's environment on purpose: a runtime on QA while models come from DEV1 is a normal way to
 * work, not a mistake to warn about. Two rows, not one: combo and button side by side wanted 450 px.
 */
internal class PlaygroundSection(private val host: HubHost) : HubSection {

    override val id = "playground"
    override val title: String get() = message("hub.section.playground")

    val environment = EnvironmentPicker(host.project, ConnectionKind.WORK)

    override fun build(panel: Panel) {
        panel.row(message("hub.playground.environment")) {
            environment.placeIn(this)
        }
        panel.row {
            button(FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_PLAYGROUND, FlowableActionIds.HUB_SECTION)) {
                host.invokeAction(FlowableActionIds.OPEN_ATLAS_PLAYGROUND)
            }.applyToComponent { toolTipText = FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_PLAYGROUND) }
        }
    }

    override fun apply(s: HubSnapshot) {
        environment.fill(s.workConnections, s.workResolution, s.hasAnyEnvironment)
    }
}
