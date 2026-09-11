package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The waiver file and what it does to a report.
 *
 * The cases worth pinning are the ones where being wrong is invisible: a waiver that matches more than
 * it should, one that silently stops matching, and counts that disagree with the list they summarise.
 */
class WaiversTest {

    private fun node(id: String, type: String, data: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("id" to id, "type" to type, "label" to id.substringAfter(':'), "key" to id.substringAfter(':'),
            "file" to null, "data" to data)

    private fun run(nodes: List<Map<String, Any?>>, waivers: Waivers.Set): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["graph"] = mapOf("nodes" to nodes, "edges" to emptyList<Map<String, Any?>>())
        Findings.apply(result, waivers)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun checks(r: Map<String, Any?>) = r["checks"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun findings(r: Map<String, Any?>) = r["findings"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun report(r: Map<String, Any?>) = r["waivers"] as? Map<String, Any?>

    private fun forms(vararg ids: String) = ids.map { node(it, "form") }

    private fun waive(vararg w: Waivers.Waiver) = Waivers.Set(w.toList())

    @Test
    fun aWaivedFindingStaysInTheListButLeavesTheCounts() {
        val r = run(forms("form:a", "form:b"), waive(
            Waivers.Waiver(check = "unusedForms", node = "form:a", reason = "kept for the pilot")))

        assertEquals("both findings are still reported", 2, findings(r).size)
        assertEquals("only the open one is counted", 1, checks(r)["unusedForms"])
        assertEquals(1, checks(r)["open"])
        assertEquals(1, checks(r)["waived"])

        val waived = findings(r).single { it["node"] == "form:a" }
        assertEquals(mapOf("reason" to "kept for the pilot"), waived["waived"])
        assertNull("the other one is untouched", findings(r).single { it["node"] == "form:b" }["waived"])
    }

    @Test
    fun aWaiverOnlyCoversItsOwnNode() {
        val r = run(forms("form:a", "form:b"), waive(
            Waivers.Waiver(check = "unusedForms", node = "form:a", reason = "why not")))
        assertEquals(1, checks(r)["waived"])
    }

    @Test
    fun aWaiverForAnotherCheckDoesNotMatch() {
        val r = run(forms("form:a"), waive(
            Waivers.Waiver(check = "unusedOps", node = "form:a", reason = "wrong check")))
        assertEquals(1, checks(r)["open"])
        assertNull(checks(r)["waived"])
    }

    /** Without `subject` a rule covers every finding of that check on the node; with it, exactly one. */
    @Test
    fun subjectNarrowsAWaiverToOneOfSeveralFindings() {
        val variable = node("variable:v", "variable", mapOf("unreadIn" to listOf("process:one", "process:two")))
        val broad = run(listOf(variable), waive(
            Waivers.Waiver(check = "unreadInputs", node = "variable:v", reason = "all of them")))
        assertEquals("no subject waives both", 2, broad.let { checks(it)["waived"] })

        val narrow = run(listOf(variable), waive(
            Waivers.Waiver(check = "unreadInputs", node = "variable:v", subject = "process:one",
                reason = "just that one")))
        assertEquals(1, checks(narrow)["waived"])
        assertEquals(1, checks(narrow)["unreadInputs"])
    }

    @Test
    fun anExpiredWaiverStopsSuppressingAndSaysSo() {
        val expired = Waivers.Waiver(
            check = "unusedForms", node = "form:a", reason = "until the migration lands",
            until = "2020-01-01")
        val r = run(forms("form:a"), waive(expired))

        assertEquals("the finding is open again", 1, checks(r)["open"])
        assertNull(checks(r)["waived"])
        assertTrue(report(r)!!["stale"].toString().contains("expired on 2020-01-01"))
    }

    @Test
    fun aWaiverThatMatchesNothingIsReportedAsStale() {
        val r = run(forms("form:a"), waive(
            Waivers.Waiver(check = "unusedForms", node = "form:gone", reason = "deleted last year")))
        assertTrue(report(r)!!["stale"].toString().contains("matched nothing"))
    }

    /** A reason is the only part of a waiver a reviewer can actually review. */
    @Test
    fun aWaiverWithoutAReasonIsReported() {
        val r = run(forms("form:a"), waive(Waivers.Waiver(check = "unusedForms", node = "form:a")))
        assertEquals("it still suppresses", 1, checks(r)["waived"])
        assertTrue(report(r)!!["unexplained"].toString().contains("no reason given"))
    }

    @Test
    fun noWaiverFileMeansNoWaiverBlock() {
        val r = run(forms("form:a"), Waivers.EMPTY)
        assertNull(report(r))
        assertNull(checks(r)["waived"])
    }

    // ---- the file itself -------------------------------------------------------------------------

    @Test
    fun parsingKeepsWhatItUnderstandsAndReportsTheRest() {
        val set = Waivers.parse("""
            {"version": 1,
             "waivers": [
               {"check": "unusedForms", "node": "form:a", "reason": "pilot"},
               {"check": "unusedForms"},
               "not an object"
             ],
             "notes": [{"node": "form:b", "text": "revisit in Q3", "importance": "high"}]}
        """.trimIndent())

        assertEquals(1, set.waivers.size)
        assertEquals(1, set.notes.size)
        assertEquals("revisit in Q3", set.notes[0].text)
        assertEquals(2, set.problems.size)
    }

    @Test
    fun anUnreadableFileIsAProblemNotACrash() {
        val set = Waivers.parse("this is not json")
        assertTrue(set.problems.isNotEmpty())
        assertTrue(set.waivers.isEmpty())
    }

    @Test
    fun serializingIsStableAndRoundTrips() {
        val set = Waivers.Set(
            waivers = listOf(
                Waivers.Waiver(check = "unusedOps", node = "op:z", reason = "external caller"),
                Waivers.Waiver(check = "unusedForms", node = "form:a", reason = "pilot"),
            ),
            notes = listOf(Waivers.Note(node = "form:b", text = "revisit")),
        )
        val once = Waivers.serialize(set, "0.0.0")
        val twice = Waivers.serialize(Waivers.parse(once), "0.0.0")
        assertEquals("a round trip must not churn the file", once, twice)
        assertTrue("sorted by check, so the diff is reviewable",
            once.indexOf("unusedForms") < once.indexOf("unusedOps"))
        assertTrue(once.endsWith("\n"))
    }

    @Test
    fun aFileFromANewerAtlasIsReadAsFarAsPossible() {
        val set = Waivers.parse("""{"version": 99, "waivers": [
            {"check": "unusedForms", "node": "form:a", "reason": "still readable"}]}""")
        assertEquals(1, set.waivers.size)
        assertTrue(set.problems.single().contains("version 99"))
    }

    @Test
    fun expiryIsADateComparison() {
        val w = Waivers.Waiver(check = "c", node = "n", until = "2026-06-01")
        assertTrue(w.expiredOn(LocalDate.parse("2026-06-02")))
        assertTrue("the day itself is still covered", !w.expiredOn(LocalDate.parse("2026-06-01")))
        assertTrue(!Waivers.Waiver(check = "c", node = "n").expiredOn(LocalDate.parse("2999-01-01")))
    }
}
