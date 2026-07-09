package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the `parse_java` / `match_rest` cases in `tests/test_parsers.py`. */
class JavaParserTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun beanEndpointAndVars() {
        val src = """package com.x;
            import org.springframework.stereotype.Component;
            // @Component("commentedOut") must be ignored
            @Component("beanName")
            public class MyBean {
                public void go(Object execution) {
                    execution.setVariable("orderId", 1);
                }
            }"""
        val jc = JavaParser.parseJava(src, "MyBean.java")
        assertEquals("MyBean", jc["primary"])
        assertEquals("com.x.MyBean", jc["fqn"])
        assertTrue("beanName" in (jc["beanNames"] as Set<String>))
        assertFalse("commentedOut" in (jc["beanNames"] as Set<String>))
        assertTrue("orderId" in (jc["vars"] as List<String>))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun controllerMapping() {
        val src = """package com.x;
            @RestController
            public class Ctl {
                @GetMapping("/api/things")
                public String list() { return ""; }
            }"""
        val jc = JavaParser.parseJava(src, "Ctl.java")
        assertEquals(true, jc["isController"])
        val eps = (jc["endpoints"] as List<Map<String, Any?>>).map { it["http"] to it["path"] }
        assertEquals(listOf("GET" to "/api/things"), eps)
    }

    @Test
    fun matchRest() {
        val eps = listOf(
            mapOf<String, Any?>("http" to "GET", "path" to "/api/things", "controller" to "Ctl",
                "handler" to "list", "file" to "Ctl.java", "line" to 3),
        )
        assertTrue(JavaParser.matchRest("/api/things", eps).isNotEmpty())
        assertTrue(JavaParser.matchRest("http://host:8080/api/things?x=1", eps).isNotEmpty())
        assertTrue(JavaParser.matchRest("/api/other", eps).isEmpty())
    }
}
