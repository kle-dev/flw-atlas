package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks the `miniproject` fixture does not trigger.
 *
 * The golden covers the six findings the fixture deliberately contains (a broken form, an invalid
 * expression, two script errors, a missing model reference, a schema gap, a script-inferred variable).
 * The remaining checks — unused forms, unused operations and custom functions, orphan/superseded
 * changelogs, merely *suspect* expressions — would otherwise ship untested, which for a check means
 * "silently never fires".
 */
class FindingsTest {

    private fun node(
        id: String, type: String, key: String = id.substringAfter(':'),
        data: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "id" to id, "type" to type, "label" to key, "key" to key, "file" to null, "data" to data,
    )

    private fun run(
        nodes: List<Map<String, Any?>>,
        edges: List<Map<String, Any?>> = emptyList(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(extra)
        result["graph"] = mapOf("nodes" to nodes, "edges" to edges)
        Findings.apply(result)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun checks(r: Map<String, Any?>) = r["checks"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun findings(r: Map<String, Any?>) = r["findings"] as List<Map<String, Any?>>

    @Test
    fun aFormOnlyAnAppContainsCountsAsUnused() {
        val r = run(
            nodes = listOf(node("form:lonely", "form"), node("form:used", "form")),
            // app membership is not use; a humanTask pointing at it is
            edges = listOf(
                mapOf("s" to "app:a", "t" to "form:lonely", "rel" to "contains"),
                mapOf("s" to "app:a", "t" to "form:used", "rel" to "contains"),
                mapOf("s" to "process:p", "t" to "form:used", "rel" to "humanTask-form"),
            ),
        )
        assertEquals(1, checks(r)["unusedForms"])
        assertEquals("form:lonely", findings(r).single { it["check"] == "unusedForms" }["node"])
    }

    @Test
    fun operationsAndCustomFunctionsNobodyCallsAreReported() {
        val r = run(
            listOf(
                node("serviceOperation:svc#unused", "serviceOperation", data = mapOf("usedBy" to emptyList<String>())),
                node("serviceOperation:svc#used", "serviceOperation", data = mapOf("usedBy" to listOf("form:f"))),
                node("customFunction:my:fn", "customFunction", data = mapOf("usedBy" to emptyList<String>())),
            )
        )
        assertEquals(1, checks(r)["unusedOps"])
        assertEquals(1, checks(r)["unusedFns"])
    }

    @Test
    fun anUnreadVariableNamesTheWriteToDelete() {
        // A finding that only says "never read" leaves the reader hunting for the line to remove, so the
        // message carries the construct and the element in Design's own words.
        val r = run(listOf(node("variable:shippingStamp", "variable", data = mapOf(
            "unread" to true, "writeCount" to 2,
            "writes" to listOf(
                mapOf("model" to "process:p", "via" to "scriptApi", "elementName" to "Stamp order"),
                mapOf("model" to "process:p", "via" to "resultVariable", "elementName" to "Calculate"),
            ),
        ))))
        assertEquals(1, checks(r)["unusedVars"])
        assertEquals(
            "written but never read — written by a script on `Stamp order`, the result variable of `Calculate`",
            findings(r).single()["message"],
        )
    }

    @Test
    fun anUnreadCallInputNamesTheCalleeAndStaysApartFromTheGlobalCheck() {
        val r = run(listOf(
            node("variable:subOrderId", "variable", data = mapOf(
                "unreadIn" to listOf("process:fulfilment"), "writeCount" to 1,
                "writes" to listOf(mapOf("model" to "process:p", "via" to "inParameter", "elementName" to "Fulfil")),
            )),
            // the same variable node cannot be both; a name nothing reads anywhere is the stronger finding
            node("variable:orphan", "variable", data = mapOf(
                "unread" to true, "writeCount" to 1,
                "writes" to listOf(mapOf("model" to "process:p", "via" to "dmnOutput")),
            )),
        ))
        assertEquals(1, checks(r)["unreadInputs"])
        assertEquals(1, checks(r)["unusedVars"])
        assertEquals(
            "mapped into `process:fulfilment`, which never reads it — written by an in parameter on `Fulfil`",
            findings(r).single { it["check"] == "unreadInputs" }["message"],
        )
    }

    @Test
    fun aVariableCanBeBothAScriptGuessAndUnread() {
        // The `variable` branch reports three independent things, and an early `continue` between them
        // would silently drop whichever came second.
        val r = run(listOf(node("variable:x", "variable", data = mapOf(
            "heuristic" to true, "unread" to true, "writeCount" to 1,
            "writes" to listOf(mapOf("model" to "process:p", "via" to "scriptApi")),
        ))))
        assertEquals(setOf("guessedVars", "unusedVars"), findings(r).map { it["check"] }.toSet())
    }

    @Test
    fun aSilencedVariableIsNeverAFinding() {
        // `readsUnknown` is the verdict Atlas declines to give; it must produce no finding on any surface.
        val r = run(listOf(node("variable:sent", "variable", data = mapOf(
            "readsUnknown" to true, "writeCount" to 1, "readCount" to 0,
            "writes" to listOf(mapOf("model" to "action:a", "via" to "flwPayload")),
        ))))
        assertTrue(findings(r).isEmpty())
        assertEquals(0, checks(r)["open"])
    }

    @Test
    fun orphanAndSupersededChangelogsAreReportedWithTheirSuccessor() {
        val r = run(
            listOf(
                node("liquibase:001", "liquibase", data = mapOf("authority" to mapOf("status" to "orphan"))),
                node("liquibase:002", "liquibase", data = mapOf(
                    "authority" to mapOf("status" to "superseded", "supersededBy" to listOf("003-later")))),
                node("liquibase:003", "liquibase", data = mapOf("authority" to mapOf("status" to "live"))),
            )
        )
        assertEquals(2, checks(r)["changelogIssues"])
        val superseded = findings(r).single { it["node"] == "liquibase:002" }
        assertTrue(superseded["message"].toString(), superseded["message"].toString().contains("003-later"))
    }

    @Test
    fun anExpressionWithOnlyWarningsIsSuspectNotInvalid() {
        val r = run(
            listOf(
                node("expression:\${a:b()}", "expression", data = mapOf(
                    "problems" to listOf(mapOf("severity" to "warning", "message" to "Unknown function 'a:b'")))),
                node("expression:\${c(}", "expression", data = mapOf(
                    "problems" to listOf(mapOf("severity" to "error", "message" to "Unclosed '('")))),
            )
        )
        assertEquals(1, checks(r)["suspectExpr"])
        assertEquals(1, checks(r)["invalidExpr"])
    }

    @Test
    fun openIsTheTotalAndTheOrderPutsErrorsFirst() {
        val r = run(
            nodes = listOf(
                node("variable:guessed", "variable", data = mapOf("heuristic" to true)),
                node("external:gone", "external", data = mapOf("missingModel" to true)),
            ),
            extra = mapOf("diagnostics" to listOf(mapOf("kind" to "parse", "path" to "x.form", "message" to "bad"))),
        )
        assertEquals(3, checks(r)["open"])
        // parse issues, then the missing model, then the merely-inferred variable
        assertEquals(
            listOf("parseIssues", "missingRefs", "guessedVars"),
            findings(r).map { it["check"] },
        )
    }

    @Test
    fun aCleanProjectReportsNoFindingsAtAll() {
        val r = run(listOf(node("process:p", "process")))
        assertEquals(0, checks(r)["open"])
        assertTrue(findings(r).isEmpty())
    }
}
