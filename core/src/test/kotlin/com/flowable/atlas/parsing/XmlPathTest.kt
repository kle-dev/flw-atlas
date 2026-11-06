package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmlPathTest {

    private fun at(xml: String, needle: String = "TODO") = XmlPath.at(xml, xml.indexOf(needle))

    @Test
    fun theInnermostElementWithAnIdOwnsWhatItsChildrenHold() {
        assertEquals(XmlPath.Site("t", "userTask", "documentation", "TODO"),
            at("""<process id="p"><userTask id="t"><documentation>TODO</documentation></userTask></process>"""))
    }

    @Test
    fun anAttributeIsHeldByItsElement() {
        assertEquals(XmlPath.Site("t", "userTask", "@name", "Check docs TODO"),
            at("""<process id="p"><userTask id="t" name="Check docs TODO"/></process>"""))
    }

    @Test
    fun aScriptBodyACommentAndASiblingThatClosedItself() {
        val script = """<process id="p"><bpmn:scriptTask id="s"><bpmn:script><![CDATA[
            def x = 1 // TODO
        ]]></bpmn:script></bpmn:scriptTask></process>"""
        assertEquals("s" to "script", at(script)!!.let { it.ownerId to it.holder })
        val comment = """<process id="p"><!-- TODO: later --><userTask id="t"/></process>"""
        assertEquals(XmlPath.Site("p", "process", "#comment", "TODO: later"), at(comment))
        // a self-closing sibling before the marker is not left open around it
        val sibling = """<process id="p"><userTask id="t"/><documentation>TODO</documentation></process>"""
        assertEquals("p", at(sibling)!!.ownerId)
        // a `>` inside an attribute value does not end the tag
        val gt = """<process id="p"><sequenceFlow id="f" name="a > b"/><documentation>TODO</documentation></process>"""
        assertEquals("p" to "documentation", at(gt)!!.let { it.ownerId to it.holder })
    }

    @Test
    fun nothingWithAnIdAroundItIsNoSite() {
        assertNull(at("""<definitions><documentation>TODO</documentation></definitions>"""))
    }
}
