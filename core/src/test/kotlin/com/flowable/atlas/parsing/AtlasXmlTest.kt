package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
