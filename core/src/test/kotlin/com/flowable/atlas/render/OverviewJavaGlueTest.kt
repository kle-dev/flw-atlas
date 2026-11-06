package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The overview names the interface a Java delegate implements. The parsed record kept its interfaces as a
 * Set, which a list reader sees as nothing: every glue class read `implements []` and no delegate row
 * carried its `[JavaDelegate]` annotation, while graph.json had the interface all along.
 */
class OverviewJavaGlueTest {

    @Test
    fun aDelegateNamesItsInterface() {
        val dir = Files.createTempDirectory("atlas-overview-glue").toFile()
        try {
            File(dir, "src/main/java/com/example").mkdirs()
            File(dir, "src/main/java/com/example/DemoDelegate.java").writeText(
                """
                package com.example;

                @Component("demoDelegate")
                public class DemoDelegate implements JavaDelegate {
                    public void execute(DelegateExecution execution) {}
                }
                """.trimIndent(),
            )
            File(dir, "processes").mkdirs()
            File(dir, "processes/DEMO-p.bpmn20.xml").writeText(
                """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">""" +
                    """<process id="DEMO-p"><serviceTask id="t" flowable:delegateExpression="${'$'}{demoDelegate}"/></process></definitions>""",
            )
            val overview = OverviewRenderer.render(Atlas.extract(dir), dir)
            assertTrue(overview, overview.contains("- `com.example.DemoDelegate` implements JavaDelegate — "))
            assertTrue(overview, overview.contains("- `com.example.DemoDelegate` [JavaDelegate] — "))
            assertFalse(overview, overview.contains("implements []"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
