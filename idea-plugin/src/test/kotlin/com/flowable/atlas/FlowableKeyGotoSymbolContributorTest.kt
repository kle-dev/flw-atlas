package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.navigation.FlowableKeyGotoSymbolContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.components.service
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters

/**
 * Search Everywhere / Go to Symbol surfaces model keys and bot keys. A bot key resolves to both the
 * BotService class and the action(s) that invoke it.
 */
class FlowableKeyGotoSymbolContributorTest : BasePlatformTestCase() {

    fun testModelKeyAndBotKeyAreSearchable() {
        myFixture.addFileToProject("BotService.java", "public interface BotService { String getKey(); }")
        myFixture.addFileToProject(
            "models/greet.action",
            """{ "key": "greetAction", "name": "Greet", "botKey": "greeter-bot" }""",
        )
        myFixture.configureByText(
            "GreeterBot.java",
            "public class GreeterBot implements BotService { public String getKey() { return \"greeter-bot\"; } }",
        )
        project.service<FlowableModelIndexService>().index()

        val contributor = FlowableKeyGotoSymbolContributor()
        val scope = GlobalSearchScope.allScope(project)

        val names = mutableListOf<String>()
        contributor.processNames(Processor { names.add(it); true }, scope, null)
        assertTrue("model key searchable: $names", names.contains("greetAction"))
        assertTrue("bot key searchable: $names", names.contains("greeter-bot"))

        val items = mutableListOf<NavigationItem>()
        contributor.processElementsWithName(
            "greeter-bot",
            Processor { items.add(it); true },
            FindSymbolParameters.simple(project, true),
        )
        assertTrue("all results are named by the bot key", items.all { it.name == "greeter-bot" })
        val locations = items.mapNotNull { it.presentation?.locationString }
        assertTrue("resolves to the bot class: $locations", locations.any { it.startsWith("Bot") })
        assertTrue("resolves to the using action: $locations", locations.any { it.startsWith("Action") })
    }
}
