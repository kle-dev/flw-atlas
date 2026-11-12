package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An operation that only Java invokes through the service registry is used, not unused. The invocation
 * is rarely one statement: a helper sets the service key and a lambda elsewhere in the class names the
 * operation, or the key is a constant the class hands to a shared client. DEMO-* names: the repo is public.
 */
class JavaServiceInvocationTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-svc-invoke-test").toFile()
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("models/crm.service", """{"key":"DEMO-S001","name":"CRM","type":"rest",
                "operations":[{"key":"getContact","name":"Get contact"},{"key":"getLead","name":"Get lead"},
                              {"key":"patchContact","name":"Patch"},{"key":"neverCalled","name":"Never"}]}""")
            put("models/erp.service", """{"key":"DEMO-S002","name":"ERP","type":"rest",
                "operations":[{"key":"getOrder","name":"Get order"},{"key":"getContact","name":"Same name"}]}""")
            // the service key in a helper, the operation in a lambda
            put("src/main/java/com/acme/CrmResource.java", """
                package com.acme;
                public class CrmResource {
                    Object contact(String id) { return fetch(b -> b.operationKey("getContact").serviceData("id", id)); }
                    private ServiceInvocationBuilder builder() {
                        return serviceRegistryRuntimeService.createServiceInvocationBuilder().serviceKey("DEMO-S001");
                    }
                }
            """)
            // the service key as the class's own constant, handed to a shared client
            put("src/main/java/com/acme/CrmClient.java", """
                package com.acme;
                public class CrmClient {
                    public static final String SERVICE_KEY = "DEMO-S001";
                    static final String PATCH = "patchContact";
                    Object lead(String mail) { return client.request(SERVICE_KEY, b -> b.operationKey("getLead")); }
                    Object patch() { return client.request(SERVICE_KEY, b -> b.operationKey(PATCH)); }
                }
            """)
            // two services named: each operation goes to the one service that defines it, or to none
            put("src/main/java/com/acme/Both.java", """
                package com.acme;
                public class Both {
                    void a() { svc.createServiceInvocationBuilder().serviceKey("DEMO-S001").operationKey("getContact").invoke(); }
                    void b() { svc.createServiceInvocationBuilder().serviceKey("DEMO-S002").operationKey("getOrder").invoke(); }
                }
            """)
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun usedBy(svc: String, op: String): List<*> =
        ((((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
            .single { it["id"] == "serviceOperation:$svc#$op" }["data"] as Map<String, Any?>)["usedBy"] as List<*>)

    @Test
    fun anOperationInALambdaBelongsToTheHelpersService() {
        assertEquals(listOf("java:com.acme.CrmResource"), usedBy("DEMO-S001", "getContact"))
    }

    @Test
    fun aServiceKeyConstantOfTheClassNamesTheService() {
        assertEquals(listOf("java:com.acme.CrmClient"), usedBy("DEMO-S001", "getLead"))
        assertEquals("an operation given as a constant", listOf("java:com.acme.CrmClient"), usedBy("DEMO-S001", "patchContact"))
    }

    @Test
    fun aClassNamingTwoServicesCreditsOnlyWhatIsDecidable() {
        assertEquals(listOf("java:com.acme.Both"), usedBy("DEMO-S002", "getOrder"))
        // getContact exists in both services Both names: not decidable, so neither is credited by Both
        assertEquals(emptyList<String>(), usedBy("DEMO-S002", "getContact"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun onlyTheOperationNobodyCallsIsUnused() {
        val unused = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedOps" }.map { it["node"] }
        assertEquals(
            listOf("serviceOperation:DEMO-S001#neverCalled", "serviceOperation:DEMO-S002#getContact"),
            unused.map { it.toString() }.sorted(),
        )
    }
}
