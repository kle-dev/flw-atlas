package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Two things a project references without defining: an action's UI placement (`channels`), which is not
 * a model at all, and the event models the platform palette ships. Neither is a missing model.
 */
class PlatformProvidedRefsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-platform-refs-test").toFile()
            File(dir, "complete.action").writeText(
                """{"key":"completeTask","name":"Complete","botKey":"bpmn-complete-usertask-bot",
                    "channels":["menu","quick-menu"],"scopeType":"bpmn"}""")
            File(dir, "mail.channel").writeText(
                """{"key":"mailIn","name":"Mail in","channelType":"inbound","type":"mail",
                    "channelEventKeyDetection":{"fixedValue":"_flowableMailEvent"}}""")
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="p">
                       <serviceTask id="ghost" flowable:type="send-event">
                         <extensionElements><flowable:eventType>orderPlacedEvent</flowable:eventType></extensionElements>
                       </serviceTask>
                     </process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun missing(): List<String> =
        (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "missingRefs" }.map { it["node"].toString() }

    @Test
    fun anActionsChannelsAreNotModels() {
        val ids = nodes().map { it["id"].toString() }
        assertFalse("`menu` is a UI placement, not a channel model", ids.any { it == "external:menu" || it == "external:quick-menu" })
        assertTrue(missing().none { it.contains("menu") })
        @Suppress("UNCHECKED_CAST")
        val action = nodes().single { it["id"] == "action:completeTask" }["data"] as Map<String, Any?>
        assertEquals(listOf("menu", "quick-menu"), action["channels"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aPlatformEventIsProvidedNotMissing() {
        val ext = nodes().single { it["id"] == "external:_flowableMailEvent" }["data"] as Map<String, Any?>
        assertEquals(true, ext["platform"])
        assertTrue(ext["missingModel"] == null)
        // …while an event the project really does not define is still reported
        assertEquals(listOf("external:orderPlacedEvent"), missing())
    }
}
