package com.flowable.atlas.hub.sections

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.hub.EnvironmentPicker
import com.flowable.atlas.hub.HubSnapshot
import com.intellij.ui.dsl.builder.Panel

/**
 * The runtime the Expression Playground evaluates against, beside the button that opens it. Independent
 * of the Design pull's environment on purpose: a runtime on QA while models come from DEV1 is a normal
 * way to work, not a mistake to warn about.
 */
internal class PlaygroundSection(private val host: HubHost) : HubSection {

    val environment = EnvironmentPicker(host.project, ConnectionKind.WORK)

    override fun build(panel: Panel) {
        panel.group(message("hub.section.playground")) {
            row(message("hub.playground.environment")) {
                environment.placeIn(this)
                button(FlowableActionIds.text(FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND)) {
                    host.invokeAction(FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND)
                }
            }
        }
    }

    override fun apply(s: HubSnapshot) {
        environment.fill(
            AtlasCatalog.connections(host.project, ConnectionKind.WORK), s.workResolution, s.hasAnyEnvironment,
        )
    }
}
