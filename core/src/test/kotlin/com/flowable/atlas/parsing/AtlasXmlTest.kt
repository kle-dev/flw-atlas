package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXParseException

/**
 * [AtlasXml.El.text] is ElementTree's `.text`: everything before the first child element. The DOM
 * hands that run over as several nodes, and reading only the first one lost every condition, script
 * body and DMN entry that Design wrote as an indented CDATA section.
 */
class AtlasXmlTest {

    private fun text(xml: String): String? = AtlasXml.parse(xml.toByteArray()).text

    @Test
    fun cdataAfterIndentationIsKept() {
        val t = text("<conditionExpression>\n    <![CDATA[\${approved}]]>\n  </conditionExpression>")
        assertEquals("\${approved}", t?.trim())
    }

    @Test
    fun aBodySplitIntoTwoCdataSectionsIsJoined() {
        assertEquals("a]]>b", text("<script><![CDATA[a]]]]><![CDATA[>b]]></script>"))
    }

    @Test
    fun commentsBeforeTheTextAreSkipped() {
        assertEquals("x > 1", text("<text><!-- generated --><![CDATA[x > 1]]></text>")?.trim())
    }

    @Test
    fun textStopsAtTheFirstChildElement() {
        assertEquals("head ", text("<a>head <b>inner</b> tail</a>"))
    }

    @Test
    fun anElementWithOnlyChildrenHasNoText() {
        assertNull(text("<a><b/></a>"))
    }

    @Test
    fun aConditionWrittenOnItsOwnLineReachesTheSequenceFlow() {
        val bpmn = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p">
                <sequenceFlow id="f" sourceRef="a" targetRef="b">
                  <conditionExpression>
                    <![CDATA[${'$'}{amount > 100}]]>
                  </conditionExpression>
                </sequenceFlow>
              </process>
            </definitions>
        """.trimIndent()
        val flow = AtlasXml.parse(bpmn.toByteArray()).iter("sequenceFlow").single()
        assertEquals("\${amount > 100}", flow.textOfDescendant("conditionExpression"))
    }

    /**
     * Malformed XML throws, and prints nothing: the JDK's default error handler wrote `[Fatal Error] :2:3: …`
     * to stderr even under `-q`. A fresh thread gets a fresh builder, whose default handler would capture the
     * swapped stderr; the second parse proves that the builder's reset() did not drop the handler again.
     */
    @Test
    fun malformedXmlThrowsWithItsLineAndPrintsNothing() {
        val broken = "<definitions><process id=\"DEMO-P1\"><startEvent id=\"s\">\n</definitions>".toByteArray()
        val lines = ArrayList<Int>()
        val buf = java.io.ByteArrayOutputStream()
        val prev = System.err
        System.setErr(java.io.PrintStream(buf, true, "UTF-8"))
        try {
            val t = Thread {
                repeat(2) {
                    try { AtlasXml.parse(broken) } catch (e: SAXParseException) { lines.add(e.lineNumber) }
                }
            }
            t.start(); t.join()
        } finally {
            System.setErr(prev)
        }
        assertEquals(listOf(2, 2), lines)
        assertTrue("stderr: " + buf.toString("UTF-8"), buf.size() == 0)
        assertEquals(2, AtlasXml.lineOf(RuntimeException("wrapped", SAXParseException("x", null, null, 2, 3))))
        assertNull(AtlasXml.lineOf(IllegalStateException("no position")))
    }
}
