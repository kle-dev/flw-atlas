package com.flowable.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The declaration of an element, not its first mention. DEMO-* names — repo public. */
class ModelElementDeclarationTest {

    private fun at(text: String, id: String) = ModelElementDeclaration.offsetOf(text, id)?.let { text.substring(it, it + id.length) to text.substring(0, it).takeLast(6) }

    @Test
    fun anXmlElementIsItsIdAttributeNotAFlowThatMentionsItFirst() {
        val xml = """<process id="DEMO-P1"><sequenceFlow id="f1" sourceRef="approve" targetRef="end"/><userTask id="approve" name="Approve"/></process>"""
        val off = ModelElementDeclaration.offsetOf(xml, "approve")!!
        assertEquals("id=\"", xml.substring(off - 4, off))
    }

    @Test
    fun aFormFieldNamedLikeAJsonKeyIsItsIdMember() {
        val json = """{"name":"DEMO-F1","rows":[[{"id":"type","type":"text","label":"Type"},{"id":"name","type":"text"}]]}"""
        val off = ModelElementDeclaration.offsetOf(json, "name")!!
        assertEquals("\"id\":\"", json.substring(off - 6, off))
        assertEquals("\"id\":\"", json.substring(ModelElementDeclaration.offsetOf(json, "type")!! - 6, ModelElementDeclaration.offsetOf(json, "type")!!))
    }

    @Test
    fun aDesignExportWrapsTheModelInAnEscapedString() {
        val wrapped = """{"name":"DEMO-F2","editorJson":"{\"rows\":[[{\"type\":\"text\",\"id\":\"amount\"}]]}"}"""
        val off = ModelElementDeclaration.offsetOf(wrapped, "amount")!!
        assertEquals("amount", wrapped.substring(off, off + 6))
        assertEquals("\\\"id\\\":\\\"", wrapped.substring(off - 9, off))
    }

    @Test
    fun aNameWithoutADeclarationIsItsFirstQuotedMention() {
        val json = """{"payload":[{"name":"customerId"}]}"""
        assertEquals("customerId", at(json, "customerId")!!.first)
        assertNull(ModelElementDeclaration.offsetOf(json, "nowhere"))
        assertNull(ModelElementDeclaration.offsetOf(json, ""))
    }

    @Test
    fun aDecisionRuleIsItsIdAttribute() {
        val dmn = """<decisionTable><rule id="r2"><inputEntry id="r2_in"/></rule></decisionTable>"""
        assertEquals("r2\"", dmn.substring(ModelElementDeclaration.offsetOf(dmn, "r2")!!, ModelElementDeclaration.offsetOf(dmn, "r2")!! + 3))
    }
}
