package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An XML model or changelog that is not well-formed is a parse issue that names the file *and* the line
 * the XML broke on — the finding opens the file there. Nothing else fed malformed XML through extraction:
 * the fixtures' only parse issue is a broken `.form`, which is JSON, and the changelog path had no test at
 * all.
 */
class MalformedXmlTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun eachBrokenXmlFileIsAParseIssueAtItsLine() {
        val dir = Files.createTempDirectory("atlas-malformed-xml-test").toFile()
        try {
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text) }
            // an element left open
            put("processes/DEMO-P1.bpmn20.xml",
                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"DEMO-P1\"><startEvent id=\"s\">\n</definitions>\n")
            put("cases/DEMO-C1.cmmn", """
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL">
                  <case id="DEMO-C1">
                    <casePlanModel id="plan">
                  </case>
                </definitions>
            """.trimIndent())
            // a bare `&` in an attribute
            put("decisions/DEMO-D1.dmn", """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/">
                  <decision id="DEMO-D1" name="DEMO a & b"/>
                </definitions>
            """.trimIndent())
            // a change set never closed: Liquibase stops at it, so the application does not start
            put("db/changelog.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="demo">
                    <createTable tableName="DEMO_T">
                      <column name="ID" type="varchar(36)"/>
                    </createTable>
                </databaseChangeLog>
            """.trimIndent())
            val result = Atlas.extract(dir)
            val issues = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "parseIssues" }
            val byFile = issues.associate { it["file"] to it["line"] }
            assertEquals(issues.toString(), mapOf(
                "processes/DEMO-P1.bpmn20.xml" to 2,
                "cases/DEMO-C1.cmmn" to 4,
                "decisions/DEMO-D1.dmn" to 2,
                "db/changelog.xml" to 7,
            ), byFile)
            val changelog = issues.single { it["file"] == "db/changelog.xml" }
            assertTrue(changelog.toString(), (changelog["message"] as String).contains("(liquibase)"))
            assertEquals("error", changelog["severity"])
            // the status lines `-v` prints carry the position the way an editor opens it
            val warnings = result["warnings"] as List<*>
            assertTrue(warnings.toString(), warnings.any { it.toString().startsWith("parse processes/DEMO-P1.bpmn20.xml:2: (bpmn)") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
