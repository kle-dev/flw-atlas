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
 * A `.data` that is a master-data list is its own kind: its `variables` are the columns of a reference
 * table, not variables of the project, and a select over the table links to it.
 */
class MasterDataTypeTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-master-data-test").toFile()
            File(dir, "country.data").writeText(
                """{"key":"md-country","name":"Country","dataObjectType":"masterData","type":"internal",
                    "subType":"country","variables":{"lang":"Language","iso":"ISO code"}}""")
            File(dir, "pick.form").writeText(
                """{"metadata":{"key":"DEMO-F001","name":"Pick","modelType":"form"},
                    "rows":[[{"id":"country","type":"select","label":"Country","value":"{{country}}",
                      "extraSettings":{"dataSource":"Master","tableKey":"md-country"}}]]}""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun graph(part: String) = (result["graph"] as Map<String, Any?>)[part] as List<Map<String, Any?>>

    @Test
    fun aMasterDataListIsItsOwnKindAndItsColumnsAreNotVariables() {
        val ids = graph("nodes").map { it["id"] }.toSet()
        assertTrue(ids.toString(), "masterData:md-country" in ids)
        assertFalse("dataObject:md-country" in ids)
        assertFalse("a reference table's column is not a project variable", "variable:lang" in ids)
        assertFalse("variable:iso" in ids)
    }

    @Test
    fun aSelectOverTheTableLinksToIt() {
        val edge = graph("edges").single { it["s"] == "form:DEMO-F001" && it["rel"] == "field-masterData" }
        assertEquals("masterData:md-country", edge["t"])
        assertTrue("a data-object reference to a master-data list is compatible, not suspect", edge["suspect"] != true)
    }
}
