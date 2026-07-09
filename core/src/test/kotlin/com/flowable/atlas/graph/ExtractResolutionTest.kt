package com.flowable.atlas.graph

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Intent-revealing facts for the reference-resolution tail of [Atlas.extract], ported from the
 * matching cases in `tests/test_golden.py`. Unlike the golden comparison these fail loudly with a
 * reason. Only the facts that do NOT need `_build_graph` / Liquibase coverage are ported here (those
 * live in the golden test / a later increment).
 */
class ExtractResolutionTest {

    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir()) }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun warnings() = result["warnings"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun modelIndex() = result["modelIndex"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun refs(section: String) = result[section] as List<Map<String, Any?>>

    // test_broken_file_produces_warning
    @Test
    fun brokenFileProducesWarning() {
        assertTrue(warnings().toString(), warnings().any { "broken.form" in it })
    }

    // test_all_fixture_models_indexed
    @Test
    fun allFixtureModelsIndexed() {
        val required = setOf(
            "app:demoApp", "process:orderProcess", "case:reviewCase",
            "form:orderForm", "service:customerService", "dataObject:customerDO",
        )
        assertTrue(modelIndex().keys.toString(), modelIndex().keys.containsAll(required))
    }

    // test_bean_method_resolves_to_java
    @Test
    fun beanMethodResolvesToJava() {
        val hits = refs("resolvedRefs").filter {
            it["from"] == "orderProcess" && it["kind"] == "bean" && it["value"] == "demoBean"
        }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0]["target"].toString(), "DemoBean.java" in (hits[0]["target"] as String))
    }

    // test_unresolved_refs_stand_out
    @Test
    fun unresolvedRefsStandOut() {
        val unresolved = refs("unresolvedRefs").map { it["value"] }.toSet()
        assertTrue(unresolved.toString(), unresolved.containsAll(setOf("notifierBean", "fulfilmentProcess")))
    }

    // test_form_rest_call_matches_controller
    @Test
    @Suppress("UNCHECKED_CAST")
    fun formRestCallMatchesController() {
        val calls = refs("restCalls").filter { it["source"] == "orderForm" }
        assertTrue(calls.isNotEmpty())
        val matches = calls[0]["matches"] as List<String>
        assertTrue(matches.toString(), matches.any { "CustomerController#customers" in it })
    }

    // test_dmn_servicetask_ref_resolves
    @Test
    fun dmnServiceTaskRefResolves() {
        val hits = refs("resolvedRefs").filter {
            it["from"] == "orderProcess" && it["kind"] == "decision" && it["value"] == "orderDecision"
        }
        assertTrue("serviceTask flowable:type=dmn must link the process to its decision", hits.isNotEmpty())
    }

    // test_receive_event_ref_resolves
    @Test
    fun receiveEventRefResolves() {
        val hits = refs("resolvedRefs").filter {
            it["from"] == "orderProcess" && it["rel"] == "receives-event" && it["value"] == "orderShipped"
        }
        assertTrue("receiveTask <flowable:eventType> must link the process to its event", hits.isNotEmpty())
    }

    // test_policy_list_shape_indexed (modelIndex half; the permissions half needs only the parser)
    @Test
    @Suppress("UNCHECKED_CAST")
    fun policyListShapeIndexed() {
        assertTrue(modelIndex().keys.toString(), "securityPolicy:orderPolicy" in modelIndex().keys)
        val policies = result["policies"] as List<Map<String, Any?>>
        val pol = policies.first { it["key"] == "orderPolicy" }
        val perms = (pol["permissions"] as List<Map<String, Any?>>)
            .associate { it["key"] to it["roles"] }
        assertTrue(
            perms.toString(),
            perms == mapOf("read" to listOf("sales"), "update" to listOf("backoffice")),
        )
    }
}
