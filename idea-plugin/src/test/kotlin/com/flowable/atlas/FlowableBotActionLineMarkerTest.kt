package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A BotService class used by an `.action` (via `botKey`) gets a gutter marker linking to the actions;
 * a bot no action uses gets none. Also exercises the reverse lookup [FlowableModelIndexService.actionsUsingBot].
 */
class FlowableBotActionLineMarkerTest : BasePlatformTestCase() {

    fun testGutterOnBotUsedByAction() {
        myFixture.addFileToProject(
            "models/greet.action",
            """{ "key": "greetAction", "name": "Greet", "botKey": "greeter-bot" }""",
        )
        val service = project.service<FlowableModelIndexService>()
        service.index()
        assertEquals("action should be found by its botKey", 1, service.actionsUsingBot("greeter-bot").size)

        myFixture.configureByText(
            "GreeterBot.java",
            "public class GreeterBot implements BotService { public String getKey() { return \"greeter-bot\"; } }",
        )
        myFixture.doHighlighting()

        val gutters = myFixture.findAllGutters().filter { it.tooltipText == "Used by Flowable actions" }
        assertEquals("the bot class should carry one marker", 1, gutters.size)
    }

    fun testNoGutterWhenNoActionUsesBot() {
        project.service<FlowableModelIndexService>().index()
        myFixture.configureByText(
            "LonelyBot.java",
            "public class LonelyBot implements BotService { public String getKey() { return \"lonely-bot\"; } }",
        )
        myFixture.doHighlighting()

        val gutters = myFixture.findAllGutters().filter { it.tooltipText == "Used by Flowable actions" }
        assertEquals("no action uses this bot → no marker", 0, gutters.size)
    }
}
