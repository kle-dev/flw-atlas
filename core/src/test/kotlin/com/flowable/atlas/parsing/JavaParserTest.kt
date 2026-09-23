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
    fun factoryMethodsDeclareBeansNamedAfterTheMethodOrTheAnnotation() {
        val src = """package com.x;
            @Configuration
            public class Delegates {
                @Bean
                public JavaDelegate approveOrder() { return e -> {}; }

                @Bean("legacyNotifier")
                @Primary
                public JavaDelegate notifier() { return e -> {}; }

                @Bean(name = "auditHook")
                JavaDelegate audit() { return e -> {}; }
            }"""
        val jc = JavaParser.parseJava(src, "Delegates.java")
        val beans = jc["beanNames"] as Set<String>
        assertEquals(setOf("approveOrder", "legacyNotifier", "auditHook"), beans)
        val lines = jc["beanMethods"] as Map<String, Int>
        assertEquals(4, lines["approveOrder"])
        assertEquals(7, lines["legacyNotifier"])
        assertTrue("configuration" in (jc["roles"] as Set<String>))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun kotlinSourcesAreReadWithTheSamePass() {
        val src = """package com.x.kt

            import org.springframework.stereotype.Component

            const val SCORE_PROCESS = "scoreProcess"

            @Component
            class ScoreService(private val repo: ScoreRepo) : JavaDelegate, ExecutionListener {
                override fun execute(execution: DelegateExecution) {
                    execution.setVariable("score", repo.compute())
                    runtimeService.startProcessInstanceByKey("scoreProcess")
                    cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey(ModelConstants.SCORE_CASE).start()
                    dmnDecisionService.createExecuteDecisionBuilder().decisionKey(SCORE_RULES).execute()
                }
                fun helper(a: Int, b: String) = a
            }"""
        val jc = JavaParser.parseJava(src, "ScoreService.kt")
        assertEquals("ScoreService", jc["primary"])
        assertEquals("com.x.kt.ScoreService", jc["fqn"])
        assertTrue("scoreService" in (jc["beanNames"] as Set<String>))
        assertEquals(setOf("JavaDelegate", "ExecutionListener"), jc["interfaces"] as Set<String>)
        assertTrue("delegate" in (jc["roles"] as Set<String>))
        assertTrue((jc["isGlue"] as Boolean))
        assertEquals(listOf("execute", "helper"), (jc["methods"] as List<Map<String, Any?>>).map { it["name"] })
        assertTrue("ScoreRepo" in (jc["deps"] as Set<String>))
        assertTrue("score" in (jc["varWrites"] as List<String>))
        assertTrue("scoreProcess" in (jc["keyedStrings"] as Set<String>))
        // constants at the same positions are kept by simple name for the resolver
        assertEquals(setOf("SCORE_CASE", "SCORE_RULES"), jc["keyedIdents"] as Set<String>)
        assertEquals(mapOf("SCORE_PROCESS" to "scoreProcess"), JavaParser.stringConstants(src))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun kotlinObjectsAndEnumClassesAreTypes() {
        val src = """package com.x
            enum class Status { OPEN, CLOSED }
            object Keys { const val A: String = "a" }"""
        val jc = JavaParser.parseJava(src, "Status.kt")
        assertEquals(listOf("Status", "Keys"), jc["types"] as List<String>)
        assertEquals("Status", jc["primary"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun variableAccessesAreSplitByVerb() {
        val src = """package com.x;
            public class MyBean {
                @Value("${'$'}{mail.from:noreply@example.com}") String from;
                @Value("${'$'}{flamingo.mail.enabled}") boolean enabled;
                public void go(DelegateExecution execution) {
                    execution.setVariable("written", 1);
                    Object r = execution.getVariableLocal("read");
                    if (execution.hasVariable("maybe")) execution.removeVariable("gone");
                    String el = "${'$'}{vars:get(flagReturn)}";
                }
            }"""
        val jc = JavaParser.parseJava(src, "MyBean.java")
        assertEquals(listOf("written"), jc["varWrites"])
        // a Spring placeholder is configuration — `mail` and `flamingo` are no variables; an EL string is
        assertEquals(listOf("flagReturn", "read"), jc["varReads"])
        assertEquals(listOf("gone", "maybe"), jc["varsUndecided"])
        // `vars` stays the union of all three, so nothing that already read it changes behaviour.
        assertEquals(listOf("flagReturn", "gone", "maybe", "read", "written"), jc["vars"])
        assertEquals(false, jc["readsAllVariables"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aWholeMapReadIsRecognised() {
        // A class that reads every variable of the scope makes it impossible to prove any single one
        // unread — the unused-variable check has to stay silent for the models that call it.
        val src = """package com.x;
            public class Nosy {
                public void go(DelegateExecution execution) { execution.getVariables(); }
            }"""
        assertEquals(true, JavaParser.parseJava(src, "Nosy.java")["readsAllVariables"])
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
    @Suppress("UNCHECKED_CAST")
    fun aCommentOpenerInsideAStringDoesNotSwallowTheCodeAfterIt() {
        val src = """package com.x;
            @RestController
            public class Files {
                private static final String BASE = "http://svc/x"; String k = "orderProcess";
                @GetMapping("/files/**")
                public String all() { return ""; }
                /** Lists one file. */
                @GetMapping("/file/{id}")
                public String one() { return ""; }
            }"""
        val jc = JavaParser.parseJava(src, "Files.java")
        val eps = (jc["endpoints"] as List<Map<String, Any?>>).map { it["path"] }
        assertEquals(listOf("/files/**", "/file/{id}"), eps)
        assertTrue((jc["strings"] as Collection<*>).contains("orderProcess"))
    }

    @Test
    fun blankCommentsKeepsLiteralsAndLineBreaks() {
        val src = "a = \"/*\"; // x\nb = '\\''; /* y\n z */ c = \"\"\"//\"\"\""
        val out = JavaParser.blankComments(src)
        assertEquals(src.length, out.length)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
        assertTrue(out.contains("\"/*\""))
        assertFalse(out.contains("x") || out.contains("y") || out.contains("z"))
        assertTrue(out.contains("c = \"\"\"//\"\"\""))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aMapHandedToTheEngineWritesTheNamesPutIntoIt() {
        val src = """package com.x;
            public class Starter {
                void start(String key) {
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("assigneeName", name);
                    vars.put("assigneeId", id);
                    runtimeService.startProcessInstanceByKey("DEMO-P001", vars);
                    Map<String, Object> other = new HashMap<>();
                    other.put("notAVariable", 1);
                    log.info(other);
                    Map<String, Object> done = Map.of();
                    done.put("outcome", "ok");
                    taskService.complete(taskId, done);
                }
            }"""
        val writes = JavaParser.parseJava(src, "Starter.java")["varWrites"] as List<String>
        assertEquals(listOf("assigneeId", "assigneeName", "outcome"), writes)
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
                    q3.definitionKey("do-item").operation(Ops.FIND_OPEN);
                }
            }"""
        // both kept as written — a quoted literal or a constant — for the resolver
        val calls = JavaParser.dataObjectOpCalls(src).map { it["def"] to it["op"] }
        assertTrue("Keys.ORDER_DO" to "\"findByStatus\"" in calls)
        assertTrue("\"do-item\"" to "\"findById\"" in calls)
        assertTrue("\"do-item\"" to "Ops.FIND_OPEN" in calls)
        // the trailing `.operation("findAll")` has no definitionKey before it in its statement
        assertFalse(calls.any { it.second == "\"findAll\"" })
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

    @Test
    fun matchRestVariableMultiSegmentBase() {
        // The endpoint carries a multi-segment base; the model expresses that base as a single variable
        // segment, so the model path is shorter — the leading wildcard must absorb the extra base segs.
        val eps = listOf(mapOf<String, Any?>("path" to "/app-api/v1/customers/{id}"))
        assertTrue(JavaParser.matchRest("{{endpoints.baseUrl}}/customers/{id}", eps).isNotEmpty())
        assertTrue(JavaParser.matchRest("{{base}}/customers/99", eps).isNotEmpty())
        // a different trailing resource must still not match
        assertTrue(JavaParser.matchRest("{{base}}/orders/{id}", eps).isEmpty())
    }
}
