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
    fun stringConstants() {
        val src = """package com.x;
            public class Keys {
                public static final String ORDER_DO = "do-order";
                static final String CUSTOMER_DO = "do-customer";
                private int notAString = 3;
                // static final String COMMENTED = "ignore-me";
            }"""
        val consts = JavaParser.stringConstants(src)
        assertEquals("do-order", consts["ORDER_DO"])
        assertEquals("do-customer", consts["CUSTOMER_DO"])
        assertFalse(consts.containsKey("COMMENTED"))
    }

    @Test
    fun dataObjectOpCalls() {
        // A builder chain pairs each `.operation("op")` with the nearest preceding `.definitionKey(x)`;
        // the definitionKey may be a constant reference or a string literal. A `.operation()` with no
        // string arg (empty) is not a usage. Calls split by a `;` must not pair across the statement.
        val src = """package com.x;
            public class OrderService {
                public void a() {
                    dataObjectRuntimeService.createDataObjectInstanceQuery()
                        .definitionKey(Keys.ORDER_DO)
                        .operation("findByStatus")
                        .value("status", status).list();
                }
                public void b() {
                    q.definitionKey("do-item").operation("findById");
                    builder.definitionKey("do-item").operation();
                    String op = "findAll"; q2.operation("findAll");
                }
            }"""
        val calls = JavaParser.dataObjectOpCalls(src).map { it["def"] to it["op"] }
        assertTrue("Keys.ORDER_DO" to "findByStatus" in calls)
        assertTrue("\"do-item\"" to "findById" in calls)
        // the trailing `.operation("findAll")` has no definitionKey before it in its statement
        assertFalse(calls.any { it.second == "findAll" })
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
