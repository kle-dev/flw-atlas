package com.flowable.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelKeyDeclarationTest {

    @Test
    fun theIdOfTheModelsOwnElement() {
        val xml = """<definitions xmlns="x"><message id="orderProcess"/><process isExecutable="true" id="orderProcess" name="Order"/></definitions>"""
        val at = ModelKeyDeclaration.offsetOf(xml, ModelType.PROCESS, "orderProcess")!!
        assertEquals("orderProcess", xml.substring(at, at + "orderProcess".length))
        val elementStart = xml.lastIndexOf('<', at)
        assertEquals("the message's id is not the process's", "<process", xml.substring(elementStart, elementStart + 8))
        assertNull(ModelKeyDeclaration.offsetOf(xml, ModelType.CASE, "orderProcess"))
    }

    @Test
    fun theKeyPropertyOfAJsonModel() {
        val json = """{"metadata":{"key":"orderForm","name":"Order"},"rows":[{"cols":[{"id":"x","formKey":"orderForm"}]}]}"""
        val at = ModelKeyDeclaration.offsetOf(json, ModelType.FORM, "orderForm")!!
        assertEquals(json.indexOf("orderForm"), at)
        assertEquals(0 to at, ModelKeyDeclaration.lineColumn(json, at))
        assertEquals(1 to 3, ModelKeyDeclaration.lineColumn("ab\ncdef", 6))
    }
}
