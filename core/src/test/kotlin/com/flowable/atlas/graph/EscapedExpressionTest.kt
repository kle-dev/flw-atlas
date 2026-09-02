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
 * `\${x}` is an author saying "literal, do not evaluate" and is not an expression; an expression the
 * harvester may have cut short gets no verdict, and the report can say how many of those there were.
 */
class EscapedExpressionTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-escaped-expr-test").toFile()
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="p">
                       <scriptTask id="s" scriptFormat="groovy"><script>def t = "literal \${'$'}{notAnExpression} here"</script></scriptTask>
                       <serviceTask id="a" flowable:expression="${'$'}{realBean.run()}"/>
                       <serviceTask id="b" flowable:expression="${'$'}{vars:get('m')['k'] == {'x':1}}"/>
                     </process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Test
    fun anEscapedPlaceholderIsNotAnExpression() {
        val ids = nodes().map { it["id"] }
        assertFalse("\\\${…} was harvested as an expression", ids.any { it.toString().contains("notAnExpression") })
        assertTrue("the real expression is still there", "expression:\${realBean.run()}" in ids)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aTruncatedExpressionIsCountedNotJudged() {
        val stats = result["stats"] as Map<String, Any?>
        assertEquals(1, stats["exprSkippedNested"])
        val nested = nodes().single { it["id"].toString().startsWith("expression:\${vars:get('m')") }
        val data = nested["data"] as Map<String, Any?>
        assertTrue("no verdict on a possibly truncated body", data["problems"] == null)
        assertTrue("but the page says why", data["notValidated"].toString().contains("nested"))
    }
}
