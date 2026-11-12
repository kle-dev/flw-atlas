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
 * `${some.property:default}` in a channel or an agent is a Spring placeholder the environment fills in —
 * not JUEL to validate, and not a chain of variables to harvest.
 */
class ConfigPlaceholderTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-config-placeholder-test").toFile()
            File(dir, "mail.channel").writeText(
                """{"key":"mailIn","name":"Mail in","channelType":"inbound","type":"mail",
                    "url":"${'$'}{email.inbound.channel.imap-url:imap://localhost:3143/inbox}",
                    "channelEventKeyDetection":{"fixedValue":"mailEvent"},
                    "authentication":{"username":"${'$'}{email.inbound.user}","password":"${'$'}{email.inbound.password}"}}""")
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="p">
                       <userTask id="a" flowable:assignee="${'$'}{order.owner}"/>
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
    private fun node(id: String) = nodes().single { it["id"] == id }["data"] as Map<String, Any?>

    @Test
    fun aPlaceholderIsMarkedAndNotValidated() {
        val d = node("expression:\${email.inbound.channel.imap-url:imap://localhost:3143/inbox}")
        assertEquals(true, d["placeholder"])
        assertTrue("no JUEL verdict on a property placeholder", d["problems"] == null)
        assertTrue("nothing flagged", (result["findings"] as List<*>).none { (it as Map<*, *>)["check"] == "invalidExpr" })
    }

    @Test
    fun aPlaceholderIsNotAChainOfVariables() {
        val ids = nodes().map { it["id"].toString() }
        for (v in listOf("email", "imap", "localhost", "inbox")) assertFalse("variable:$v", "variable:$v" in ids)
    }

    @Test
    fun aDottedPropertyPathIsStillAnExpression() {
        val d = node("expression:\${order.owner}")
        assertTrue(d["placeholder"] == null)
        assertTrue("`order` is a variable the process reads", nodes().any { it["id"] == "variable:order" })
    }

    @Test
    fun everythingInAChannelIsAPlaceholder() {
        // no default, no dash — but nothing in a channel is JUEL, so this is configuration too
        assertEquals(true, node("expression:\${email.inbound.user}")["placeholder"])
    }
}
