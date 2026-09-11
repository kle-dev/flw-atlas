package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonPathTest {

    private val doc = """{"metadata":{"key":"f"},"rows":[{"cols":[{"id":"a","label":"Name"},{"id":"b","label":"TODO price"}]},{"cols":[]}],"note":"TODO tail"}"""

    @Test
    fun thePathOfTheStringHoldingTheOffset() {
        assertEquals("rows[0].cols[1].label", JsonPath.at(doc, doc.indexOf("TODO price")))
        assertEquals("note", JsonPath.at(doc, doc.indexOf("TODO tail")))
        assertEquals("metadata.key", JsonPath.at(doc, doc.indexOf("\"f\"") + 1))
    }

    @Test
    fun escapesAndBracesInsideStringsDoNotConfuseIt() {
        val tricky = """{"a":"x } ] \" {","b":[1,{"c":"TODO here"}]}"""
        assertEquals("b[1].c", JsonPath.at(tricky, tricky.indexOf("TODO here")))
    }

    @Test
    fun outsideAnyValueThereIsNoPath() {
        assertEquals(null, JsonPath.at("TODO plain text", 2))
    }
}
