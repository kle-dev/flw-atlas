package com.flowable.keys

import com.flowable.keys.model.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the model JSON readers (pure, byte-array based). */
class JsonUtilTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test fun readServiceTable_reads_columns_and_table() {
        val json = """
            {"key":"DEMO-S010","type":"database","tableName":"ORDER_","referencedLiquibaseModelKey":"DEMO-L010",
             "columnMappings":[{"name":"id","columnName":"ID_","type":"STRING"},
                               {"name":"label","columnName":"LABEL_","type":"STRING"}]}
        """.trimIndent()
        val st = JsonUtil.readServiceTable(bytes(json))
        assertNotNull(st)
        assertEquals("ORDER_", st!!.tableName)
        assertTrue(st.isDatabase)
        assertEquals(listOf("ID_", "LABEL_"), st.columns.map { it.columnName })
    }

    @Test fun readDataObject_reads_type_and_fields() {
        val json = """
            {"key":"DEMO-D010","dataObjectType":"serviceRegistryDataObject",
             "referencedServiceDefinitionModelKey":"DEMO-S010",
             "fieldMappings":[{"name":"label","type":"STRING"},{"name":"region","type":"STRING"}]}
        """.trimIndent()
        val d = JsonUtil.readDataObject(bytes(json))!!
        assertTrue(d.isTableBacked)
        assertEquals("DEMO-S010", d.referencedServiceDefinitionModelKey)
        assertEquals(listOf("label", "region"), d.fields)
    }

    @Test fun masterData_is_not_table_backed() {
        val d = JsonUtil.readDataObject(bytes("""{"key":"md","dataObjectType":"masterData"}"""))!!
        assertTrue(!d.isTableBacked)
    }

    @Test fun readForm_collects_field_ids() {
        val json = """{"metadata":{"key":"F1"},"fields":[{"id":"first","type":"text"},{"id":"last","type":"text"}]}"""
        val form = JsonUtil.readForm(bytes(json))
        assertTrue("fields: ${form.fields}", form.fields.containsAll(listOf("first", "last")))
    }

    @Test fun readEventPayload_reads_names() {
        val json = """{"key":"E1","payload":[{"name":"from","type":"string"},{"name":"subject"}]}"""
        assertEquals(listOf("from", "subject"), JsonUtil.readEventPayload(bytes(json)))
    }

    @Test fun extractKeyName_top_level_and_metadata() {
        assertEquals("K", JsonUtil.extractKeyName(bytes("""{"key":"K","name":"N"}"""))!!.key)
        assertEquals("MK", JsonUtil.extractKeyName(bytes("""{"metadata":{"key":"MK","name":"MN"}}"""))!!.key)
    }
}
