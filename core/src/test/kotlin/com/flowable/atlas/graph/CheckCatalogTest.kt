package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is the one description of every check; these pin the shape every surface relies on. The
 * docs anchors themselves are checked against the page by `SiteDocsCoverageTest`.
 */
class CheckCatalogTest {

    @Test
    fun idsAreUnique() {
        assertEquals(CheckCatalog.ORDER.size, CheckCatalog.ORDER.toSet().size)
    }

    @Test
    fun everyCheckIsFullyDescribed() {
        for (c in CheckCatalog.CHECKS) {
            for ((name, v) in listOf("label" to c.label, "title" to c.title, "what" to c.what,
                "clean" to c.clean, "why" to c.why, "fix" to c.fix, "docs" to c.docs)) {
                assertTrue("${c.id}.$name is blank", v.isNotBlank())
            }
            assertTrue("${c.id}.severity: ${c.severity}", c.severity in setOf("error", "warning", "error · warning"))
            assertTrue("${c.id}.tier: ${c.tier}", c.tier in setOf("broken", "runtime", "unfinished", "noise"))
            assertTrue("${c.id}.kind: ${c.kind}", c.kind in setOf(CheckCatalog.DEFECT, CheckCatalog.ADVICE))
            assertTrue("${c.id}.docs is not a heading slug: ${c.docs}", Regex("[a-z0-9-]+").matches(c.docs))
        }
    }

    @Test
    fun payloadKeepsTheOrderAndCarriesFullDocsUrls() {
        val payload = CheckCatalog.payload()
        assertEquals(CheckCatalog.ORDER, payload.map { it["id"] })
        for (p in payload) {
            assertTrue("${p["id"]} docs is not a URL: ${p["docs"]}",
                (p["docs"] as String).startsWith(CheckCatalog.DOCS_BASE + "checks/#"))
        }
    }

    /** The reading order leads with what is wrong: every defect before the first advice. */
    @Test
    fun defectsComeBeforeAdvice() {
        val kinds = CheckCatalog.CHECKS.map { it.kind }
        val firstAdvice = kinds.indexOf(CheckCatalog.ADVICE)
        assertTrue(firstAdvice > 0)
        assertTrue("a defect after the first advice", kinds.drop(firstAdvice).all { it == CheckCatalog.ADVICE })
        assertEquals(CheckCatalog.DEFECT, CheckCatalog.kind("missingRefs"))
        assertEquals(CheckCatalog.ADVICE, CheckCatalog.kind("unguardedTasks"))
        assertEquals(null, CheckCatalog.kind("somethingNew"))
        assertTrue(CheckCatalog.payload().all { it["kind"] == CheckCatalog.DEFECT || it["kind"] == CheckCatalog.ADVICE })
    }

    /**
     * The explorer and the findings tool window label an advice finding *advice* rather than printing its
     * severity — which is only right while no advice check can emit an error. An advice that could fail
     * `--fail-on error` would be a defect.
     */
    @Test
    fun adviceIsNeverAnError() {
        for (c in CheckCatalog.CHECKS.filter { it.kind == CheckCatalog.ADVICE }) {
            assertEquals("${c.id} is advice, so its severity must be warning", "warning", c.severity)
        }
    }

    @Test
    fun countOpenIgnoresWaivedAndUnknown() {
        val findings = listOf<Map<String, Any?>>(
            mapOf("check" to "missingRefs"),
            mapOf("check" to "missingRefs", "waived" to mapOf("reason" to "x")),
            mapOf("check" to "unguardedTasks"),
            mapOf("check" to "somethingNew"),
        )
        assertEquals(1, CheckCatalog.countOpen(findings, CheckCatalog.DEFECT))
        assertEquals(1, CheckCatalog.countOpen(findings, CheckCatalog.ADVICE))
    }

    /** The `--fail-on` vocabulary, as every surface that reports a red build reads it. */
    @Test
    fun gatesReadsTheFailOnVocabulary() {
        val defectWarning = mapOf<String, Any?>("check" to "missingRefs", "severity" to "warning")
        val adviceError = mapOf<String, Any?>("check" to "unguardedTasks", "severity" to "error")
        val waived = mapOf<String, Any?>("check" to "missingRefs", "severity" to "error", "waived" to mapOf("reason" to "x"))
        assertTrue(CheckCatalog.gates(listOf("defects"), defectWarning))
        assertTrue(!CheckCatalog.gates(listOf("defects"), adviceError))
        assertTrue(CheckCatalog.gates(listOf("advice"), adviceError))
        assertTrue(CheckCatalog.gates(listOf("error"), adviceError))
        assertTrue(!CheckCatalog.gates(listOf("error"), defectWarning))
        assertTrue("`warning` has always meant any finding", CheckCatalog.gates(listOf("warning"), defectWarning))
        assertTrue(CheckCatalog.gates(listOf("any"), adviceError))
        assertTrue(CheckCatalog.gates(listOf("unusedForms", "missingRefs"), defectWarning))
        assertTrue("a waived finding never gates", listOf("any", "error", "defects", "missingRefs").none { CheckCatalog.gates(listOf(it), waived) })
    }

    @Test
    fun anUnknownIdKeepsItsName() {
        assertEquals("somethingNew", CheckCatalog.label("somethingNew"))
    }
}
