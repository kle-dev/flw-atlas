package com.flowable.atlas.usage

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [EndpointPsi.endpointsOf] resolution: a class-level `@RequestMapping` base is prepended to the method
 * mapping (the reported bug); the base and path resolve through constants and concatenations, not only
 * literals; array values yield one endpoint each; `@RequestMapping(method = …)` sets the verb; and a
 * base bundled by a meta-annotation is found. Annotations are unqualified (Spring is not on the test
 * classpath) — detection works off the written short name, as in production.
 */
class EndpointPsiTest : BasePlatformTestCase() {

    private fun endpointsOf(src: String, methodName: String): List<EndpointPsi.Endpoint> {
        val file = myFixture.configureByText("Ctl.java", src) as PsiJavaFile
        val method = file.classes.flatMap { it.methods.asList() }.first { it.name == methodName }
        return EndpointPsi.endpointsOf(method)
    }

    fun testClassBaseLiteralPrependedToMethodMapping() {
        val eps = endpointsOf(
            """
            @RestController @RequestMapping("/api/customers")
            class CustomerController {
                @GetMapping("/{id}") public String get() { return ""; }
            }
            """.trimIndent(),
            "get",
        )
        assertEquals(listOf(EndpointPsi.Endpoint("/api/customers/{id}", "GET")), eps)
    }

    fun testConcatenatedPathResolvedViaConstantEvaluator() {
        // A non-literal constant expression (concatenation) resolves through the same PSI constant
        // evaluator that resolves constant references (`@RequestMapping(ApiPaths.CUSTOMERS)`) in a real,
        // indexed project — proving path values are no longer limited to plain string literals.
        val eps = endpointsOf(
            """
            @RestController @RequestMapping("/api" + "/customers")
            class C { @GetMapping("/{id}") public String get() { return ""; } }
            """.trimIndent(),
            "get",
        )
        assertEquals(listOf(EndpointPsi.Endpoint("/api/customers/{id}", "GET")), eps)
    }

    fun testArrayValuesYieldOneEndpointEach() {
        val eps = endpointsOf(
            """
            @RestController @RequestMapping("/api")
            class C { @GetMapping({"/a", "/b"}) public String get() { return ""; } }
            """.trimIndent(),
            "get",
        )
        assertEquals(
            setOf(EndpointPsi.Endpoint("/api/a", "GET"), EndpointPsi.Endpoint("/api/b", "GET")),
            eps.toSet(),
        )
    }

    fun testRequestMappingMethodAttributeSetsVerb() {
        val eps = endpointsOf(
            """
            @RestController @RequestMapping("/api/customers")
            class C { @RequestMapping(method = RequestMethod.GET, path = "/{id}") public String get() { return ""; } }
            """.trimIndent(),
            "get",
        )
        assertEquals(listOf(EndpointPsi.Endpoint("/api/customers/{id}", "GET")), eps)
    }

    fun testMetaAnnotationBaseResolved() {
        val eps = endpointsOf(
            """
            @RequestMapping("/api/customers") @interface CustomerApi {}
            @RestController @CustomerApi
            class C { @GetMapping("/{id}") public String get() { return ""; } }
            """.trimIndent(),
            "get",
        )
        assertEquals(listOf(EndpointPsi.Endpoint("/api/customers/{id}", "GET")), eps)
    }
}
