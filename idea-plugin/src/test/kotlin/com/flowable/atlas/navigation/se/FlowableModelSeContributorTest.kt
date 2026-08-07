package com.flowable.atlas.navigation.se

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor

/**
 * The "Flowable Model" Search Everywhere tab finds a model by its key and by its file name, finds a
 * word inside a model's content as a full-text hit carrying the right line, and always ranks models
 * above text hits.
 */
class FlowableModelSeContributorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "models/demo-invoice.bpmn",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="DEMO-P001" name="Demo Invoice">
                <userTask id="approve" name="Approve invoiceAmount"/>
              </process>
            </definitions>
            """.trimIndent(),
        )
        project.service<FlowableModelIndexService>().index()
    }

    fun testModelIsFoundByKey() {
        val keys = search("DEMO-P001").models().map { it.entry.key }
        assertTrue("model found by key: $keys", keys.contains("DEMO-P001"))
    }

    fun testModelIsFoundByFileName() {
        // The key is DEMO-P001 — nothing about it matches "demo-invoice", so only the path can.
        val paths = search("demo-invoice").models().map { it.displayPath }
        assertTrue("model found by file name: $paths", paths.contains("demo-invoice.bpmn"))
    }

    fun testContentWordIsFoundAsTextHitOnItsLine() {
        val hits = search("invoiceAmount").textHits()
        assertEquals("one occurrence in the fixture: ${hits.map { it.lineText }}", 1, hits.size)
        assertEquals("the userTask is the 4th line", 3, hits[0].line)
        assertTrue("row shows the matched line: ${hits[0].lineText}", hits[0].lineText.contains("invoiceAmount"))
        assertEquals("demo-invoice.bpmn", hits[0].displayPath)
    }

    fun testModelsOutrankTextHits() {
        // "invoice" matches the file name (a model hit) and the content (text hits).
        val found = search("invoice")
        assertTrue("both kinds present: $found", found.models().isNotEmpty() && found.textHits().isNotEmpty())
        val lowestModel = found.filter { it.item is FlowableSeItem.Model }.minOf { it.weight }
        val highestText = found.filter { it.item is FlowableSeItem.TextHit }.maxOf { it.weight }
        assertTrue("every model outranks every text hit ($lowestModel > $highestText)", lowestModel > highestText)
    }

    fun testLongLineIsWindowedAroundTheMatch() {
        // Model files routinely hold single lines of hundreds of characters; the raw line would push
        // the match out of view and collide with the file name on the right.
        val padding = "x".repeat(400)
        myFixture.addFileToProject("models/demo-wide.bpmn", "<definitions>$padding needleWord$padding</definitions>")
        project.service<FlowableModelIndexService>().refresh()

        val hit = search("needleWord").textHits().single { it.displayPath == "demo-wide.bpmn" }
        assertTrue("windowed to a row's worth of text: ${hit.lineText.length}", hit.lineText.length <= 120)
        assertTrue("marked as cut on both sides: ${hit.lineText}", hit.lineText.startsWith("…") && hit.lineText.endsWith("…"))
        assertEquals(
            "match offset points at the needle inside the window",
            "needleWord",
            hit.lineText.substring(hit.matchStart, hit.matchStart + hit.matchLength),
        )
    }

    fun testShortPatternDoesNotTriggerTheGrep() {
        // A single character matches almost every model's text, so the grep starts at two.
        assertTrue("no text hits below the threshold", search("i").textHits().isEmpty())
        assertTrue("two characters do grep", search("in").textHits().isNotEmpty())
    }

    private fun search(pattern: String): List<FoundItemDescriptor<FlowableSeItem>> {
        val found = mutableListOf<FoundItemDescriptor<FlowableSeItem>>()
        FlowableModelSeContributor(project).fetchWeightedElements(
            pattern,
            EmptyProgressIndicator(),
            Processor { found.add(it); true },
        )
        return found
    }

    private fun List<FoundItemDescriptor<FlowableSeItem>>.models(): List<FlowableSeItem.Model> =
        mapNotNull { it.item as? FlowableSeItem.Model }

    private fun List<FoundItemDescriptor<FlowableSeItem>>.textHits(): List<FlowableSeItem.TextHit> =
        mapNotNull { it.item as? FlowableSeItem.TextHit }
}
