package com.flowable.atlas

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.CheckCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `site/flowable-demo` is the project every screenshot and the live demo on the documentation site are
 * generated from. It has one job: to show what Atlas does, honestly, on something a reader could have
 * written themselves.
 *
 * ## Why it is tested
 * A sample project rots silently. Someone tidies a model, the graph loses an edge, and the published
 * screenshots quietly start showing an empty *Checks* page — which reads as "Atlas found nothing" rather
 * than "the sample lost its findings". Worse, a broken model would make the deployed demo look like a
 * broken product.
 *
 * So two invariants:
 *  - **every** health check produces at least one finding, because the site claims all of them are real;
 *  - the parse-issue count is exactly the one deliberately-broken file, so a genuinely broken model in
 *    the sample cannot hide behind it.
 *
 * This is deliberately not a golden test: the demo is meant to be edited freely, and pinning its output
 * byte-for-byte would make improving it annoying enough that nobody would. Only the properties the site
 * depends on are asserted.
 */
class SiteDemoProjectTest {

    private val demo = File(GoldenFiles.repoRoot, "site/flowable-demo")

    private val result: Map<String, Any?> by lazy {
        assertTrue("missing the documentation site's demo project at site/flowable-demo", demo.isDirectory)
        Atlas.extract(demo)
    }

    @Suppress("UNCHECKED_CAST")
    private fun checks(): Map<String, Any?> = result["checks"] as? Map<String, Any?> ?: emptyMap()

    @Test
    fun everyCheckHasSomethingToShow() {
        val counts = checks()
        val silent = CheckCatalog.ORDER.filter { ((counts[it] as? Number)?.toInt() ?: 0) == 0 }
        assertTrue(
            "site/flowable-demo produces no finding for: $silent\n" +
                "The documentation site shows this project's Checks page as evidence that all " +
                "${CheckCatalog.ORDER.size} checks are real, so each one needs at least one honest " +
                "example. Add a model that triggers it, or explain the gap on site/pages/checks.md.",
            silent.isEmpty(),
        )
    }

    /** A finding whose check the catalog does not describe would reach every surface unlabelled. */
    @Test
    fun everyFindingBelongsToACatalogedCheck() {
        val unknown = checks().keys.filterNot { it in CheckCatalog.ORDER || it == "open" || it == "waived" }
        assertTrue("site/flowable-demo emits check id(s) CheckCatalog does not describe: $unknown", unknown.isEmpty())
    }

    /**
     * `broken.form` is deliberately unparseable — it is how the site demonstrates that a file Atlas
     * cannot read becomes a finding rather than a smaller project. Exactly one, so a second broken model
     * cannot slip in unnoticed.
     */
    @Test
    fun onlyTheDeliberatelyBrokenFileFailsToParse() {
        assertEquals(
            "site/flowable-demo should have exactly one parse issue (broken.form, on purpose). " +
                "A different count means a sample model is broken by accident.",
            1,
            (checks()["parseIssues"] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * Every "Does it fit?" table the explorer draws has an honest row to show on the site — a gap and
     * a fit. Properties, not a golden: the models may change, what they demonstrate may not.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun theContractTablesHaveSomethingToShow() {
        val graph = result["graph"] as Map<String, Any?>
        val nodes = (graph["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        val edges = graph["edges"] as List<Map<String, Any?>>
        fun data(id: String) = nodes[id]?.get("data") as? Map<String, Any?> ?: error("no node $id")
        fun bucket(name: String, key: String) = (result[name] as List<Map<String, Any?>>).single { it["key"] == key }

        // an operation called through field injection and by an agent's tool
        val byNumber = data("serviceOperation:orderService#findByNumber")["usedBy"] as List<*>
        assertTrue(byNumber.toString(), "process:onboardingReviewProcess" in byNumber && "agent:supportAgent" in byNumber)
        // …and its REST call reaches only the handler for its verb
        val call = (result["restCalls"] as List<Map<String, Any?>>).single { it["source"] == "orderService" && it["where"] == "findByNumber" }
        assertEquals(listOf("GET /api/orders/{orderNumber}"), (call["matches"] as List<String>).map { it.substringBefore(" ->") })
        // a mapping names the operation it feeds
        assertTrue((result["processes"] as List<Map<String, Any?>>).any { p -> (p["ioParameters"] as? List<Map<String, Any?>>).orEmpty().any { it["refOp"] != null } })
        // a task calls an operation without its required parameter
        val t3 = (bucket("processes", "typeDemo")["serviceTasks"] as List<Map<String, Any?>>).single { it["id"] == "t3" }
        assertEquals("findById", t3["operationKey"])
        assertTrue((data("serviceOperation:customerService#findById")["params"] as List<Map<String, Any?>>).any { it["name"] == "customerId" && it["required"] == true })
        // an event with a publisher on a channel and a consumer
        assertTrue(edges.any { it["t"] == "event:customerCreated" && it["rel"] == "sends-event" })
        assertTrue(edges.any { it["t"] == "event:customerCreated" && it["rel"] == "receives-event" })
        assertTrue((bucket("processes", "onboardingReviewProcess")["serviceTasks"] as List<Map<String, Any?>>).any { it["channelKey"] == "customerEventsOutChannel" })
        // a signal thrown in one model and caught in another, by name
        assertTrue(edges.any { it["t"] == "signal:customerOnboarded" && it["rel"] == "throws-signal" })
        assertTrue(edges.any { it["t"] == "signal:customerOnboarded" && it["rel"] == "catches-signal" })
        // a sub-process reads a value its caller never passes
        val prio = data("variable:shippingPriority")
        assertTrue((prio["reads"] as List<Map<String, Any?>>).any { it["model"] == "process:fulfilmentProcess" })
        assertTrue((bucket("processes", "orderProcess")["ioParameters"] as List<Map<String, Any?>>).none { it["target"] == "shippingPriority" })
        // an outcome no condition tests, next to one that is
        val conditions = (bucket("processes", "onboardingReviewProcess")["flows"] as List<Map<String, Any?>>).mapNotNull { it["condition"] as? String }
        assertTrue(conditions.any { "'reject'" in it })
        assertTrue(conditions.none { "'accept'" in it })
    }
}
