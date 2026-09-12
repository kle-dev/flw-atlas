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

    @Test
    fun anUnknownIdKeepsItsName() {
        assertEquals("somethingNew", CheckCatalog.label("somethingNew"))
    }
}
