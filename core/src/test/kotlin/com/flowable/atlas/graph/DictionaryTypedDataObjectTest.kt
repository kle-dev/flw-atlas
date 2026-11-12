package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A data object typed by a dictionary type has that type's properties as its fields; its own field
 * mappings only add the lookup id and a label. Read from the mappings alone it had one field, and every
 * other column the service maps was "used by no data object".
 */
class DictionaryTypedDataObjectTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-dict-typed-do-test").toFile()
            File(dir, "bots.dictionary").writeText(
                """{"key":"botDict","name":"Bots","types":{"botUser":{"label":"Bot user","type":"object",
                    "properties":{"username":{"type":"string"},"email":{"type":"string"},"created":{"type":"date"}}}}}""")
            File(dir, "bot.data").writeText(
                """{"key":"botDO","name":"Bot user","dataObjectType":"serviceRegistryDataObject",
                    "referencedServiceDefinitionModelKey":"bots",
                    "referencedDataDictionaryModelKey":"botDict","dataDictionaryTypeName":"botUser",
                    "fieldMappings":[{"name":"username","label":"User","lookupId":true}]}""")
            File(dir, "bots.service").writeText(
                """{"key":"bots","name":"Bots","type":"database","tableName":"BOT_USERS",
                    "referencedLiquibaseModelKey":"botsSchema",
                    "columnMappings":[{"name":"username","columnName":"USERNAME","type":"STRING"},
                      {"name":"email","columnName":"EMAIL","type":"STRING"},
                      {"name":"created","columnName":"CREATED","type":"DATE"}],
                    "operations":[{"key":"findById","type":"lookup","name":"Lookup"}]}""")
            File(dir, "liquibase-botsSchema.data.changelog.xml").writeText(
                """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                     <changeSet id="1" author="fixture">
                       <createTable tableName="BOT_USERS">
                         <column name="USERNAME" type="varchar(64)"/>
                         <column name="EMAIL" type="varchar(255)"/>
                         <column name="CREATED" type="timestamp"/>
                       </createTable>
                     </changeSet>
                   </databaseChangeLog>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dataObject() = (result["dataObjects"] as List<Map<String, Any?>>).single { it["key"] == "botDO" }

    @Test
    fun theTypesPropertiesAreTheObjectsFields() {
        val d = dataObject()
        assertEquals("botUser", d["dictionaryType"])
        assertEquals(listOf("username", "email", "created"), d["fields"])
        @Suppress("UNCHECKED_CAST")
        val cols = d["columns"] as List<Map<String, Any?>>
        // the mapping's label survives on the property it describes; the type supplies the rest
        assertEquals("User", cols[0]["label"])
        assertEquals("string", cols[1]["type"])
        assertEquals(true, d["fieldsFromDictionary"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theGraphNodeAndTheSchemaCheckSeeTheSameFields() {
        val node = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
            .single { it["id"] == "dataObject:botDO" }
        assertEquals(3, ((node["data"] as Map<String, Any?>)["columns"] as List<*>).size)
        val gaps = (result["findings"] as List<Map<String, Any?>>)
            .filter { it["check"] == "schemaGaps" }.map { it["message"] }
        assertTrue("every mapped column is a field of the typed data object: $gaps", gaps.isEmpty())
    }
}
