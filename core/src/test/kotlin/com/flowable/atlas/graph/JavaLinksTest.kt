package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A link between the Java code and the models is one the code states: the bean a `@Bean` method returns,
 * a class a stereotype declares, a type the imports name, a literal something checks or hands on. Each
 * case below drew a link from a name that merely looked right.
 */
class JavaLinksTest {

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-java-links").toFile()
        try {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun process(body: String) = """
        <definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P">$body</process></definitions>"""

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.edges() = ((this["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nodes() = ((this["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.roles(id: String) =
        ((nodes().single { it["id"] == id }["data"] as Map<String, Any?>)["roles"] as List<String>)

    @Test
    fun aFactoryBeanIsTheTypeItsMethodReturns() {
        val r = extract(mapOf(
            "src/main/java/com/example/AppConfig.java" to """
                package com.example;
                @Configuration
                public class AppConfig {
                    @Bean
                    public OrderService orderService() { return new OrderService(); }
                }""",
            "src/main/java/com/example/OrderService.java" to """
                package com.example;
                public class OrderService {
                    public void place(Object execution) { }
                }""",
            "p.bpmn" to process("""<serviceTask id="t" flowable:expression="${'$'}{orderService.place(execution)}"/>"""),
        ))
        val calls = r.edges().filter { it["rel"] == "calls place()" }.map { it["t"] }
        assertEquals(listOf("java:com.example.OrderService"), calls)
        assertTrue(r.edges().any { it["s"] == "method:com.example.OrderService#place" && it["rel"] == "declared-in" })
        assertFalse(r.nodes().any { it["id"] == "method:com.example.AppConfig#place" })
        assertFalse("delegate" in r.roles("java:com.example.AppConfig"))
    }

    @Test
    fun aNameThatIsNoDeclaredBeanIsAVariable() {
        val r = extract(mapOf(
            // MapStruct's @Named on a method names a mapping, not a bean
            "src/main/java/com/example/InvoiceMapper.java" to """
                package com.example;
                public class InvoiceMapper {
                    @Named("invoice")
                    public String map(Object o) { return ""; }
                }""",
            // a plain class named like the variable is no bean either
            "src/main/java/com/example/Customer.java" to """
                package com.example;
                public class Customer { public String getName() { return ""; } }""",
            "p.bpmn" to process("""<serviceTask id="t" flowable:expression="${'$'}{execution.setVariable('t', invoice.getTotal())}"/>
                <serviceTask id="u" flowable:expression="${'$'}{execution.setVariable('n', customer.getName())}"/>"""),
        ))
        assertFalse(r.edges().any { (it["rel"] as String).startsWith("calls ") })
        assertTrue(r.nodes().any { it["id"] == "variable:customer" })
    }

    @Test
    fun aCallInsideAStringIsText() {
        val r = extract(mapOf(
            "src/main/java/com/example/OrderService.java" to """
                package com.example;
                @Service
                public class OrderService { public void cancel() { } }""",
            "p.bpmn" to process("""<serviceTask id="t" flowable:expression="${'$'}{auditLogService.log('orderService.cancel()')}"/>"""),
        ))
        assertFalse(r.edges().any { it["t"] == "java:com.example.OrderService" })
    }

    @Test
    fun onlyAnExternalWorkerSubscriptionIsATopicWorker() {
        val r = extract(mapOf(
            "src/main/java/com/example/Kafka.java" to """
                package com.example;
                public class Kafka { void wire(Object b) { b.channel().topic("DEMO-orders").build(); } }""",
            "src/main/java/com/example/Worker.java" to """
                package com.example;
                public class Worker { void poll(Object c) { c.createJobAcquireBuilder().topic("DEMO-orders", java.time.Duration.ofMinutes(1)); } }""",
            "p.bpmn" to process("""<serviceTask id="w" flowable:type="external-worker" flowable:topic="DEMO-orders"/>"""),
        ))
        val workers = r.edges().filter { it["t"] == "topic:DEMO-orders" && (it["s"] as String).startsWith("java:") }.map { it["s"] }
        assertEquals(listOf("java:com.example.Worker"), workers)
    }

    @Test
    fun aDependencyIsTheTypeTheImportsName() {
        val r = extract(mapOf(
            "src/main/java/com/example/model/Task.java" to """
                package com.example.model;
                public class Task { }""",
            "src/main/java/com/example/Handler.java" to """
                package com.example;
                import org.flowable.task.api.Task;
                @Component
                public class Handler { private final Task task; public Handler(Task task) { this.task = task; } }""",
        ))
        assertFalse(r.edges().any { it["rel"] == "uses" && it["t"] == "java:com.example.model.Task" })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aLiteralNamesAModelOnlyWhereTheCodeSaysSo() {
        val r = extract(mapOf(
            "data/c.data" to """{"key":"DEMO-customer","name":"Customer","dataObjectType":"lookup","fieldMappings":[]}""",
            "src/main/java/com/example/Payload.java" to """
                package com.example;
                public class Payload {
                    @JsonProperty("DEMO-customer") private String c;
                    void fill(java.util.Map<String, Object> m) { m.put("DEMO-customer", 1); log.info("DEMO-customer"); }
                }""",
            "src/main/java/com/example/Router.java" to """
                package com.example;
                public class Router { boolean isCustomer(String k) { return "DEMO-customer".equals(k); } }""",
        ))
        val refs = r.edges().filter { it["rel"] == "references" }.map { it["s"] }
        assertEquals(listOf("java:com.example.Router"), refs)
    }

    @Test
    fun aQualifiedConstantIsItsOwnersConstant() {
        val r = extract(mapOf(
            "p.bpmn" to process("<startEvent id=\"s\"/>"),
            "src/main/java/com/example/AKeys.java" to """
                package com.example;
                public class AKeys { public static final String MAIN = "DEMO-P"; }""",
            "src/main/java/com/example/Starter.java" to """
                package com.example;
                import org.acme.lib.LibKeys;
                public class Starter {
                    void a(Object rt) { rt.startProcessInstanceByKey(LibKeys.MAIN); }
                }""",
        ))
        assertFalse("LibKeys.MAIN is a library's, not AKeys.MAIN", r.edges().any { it["s"] == "java:com.example.Starter" })
    }
}
