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

    @Test
    fun anUnknownIdKeepsItsName() {
        assertEquals("somethingNew", CheckCatalog.label("somethingNew"))
    }
}
