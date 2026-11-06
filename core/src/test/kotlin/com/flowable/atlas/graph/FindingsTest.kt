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
            nodes = listOf(node("form:lonely", "form"), node("form:used", "form"), node("form:gated", "form")),
            // app membership is not use, and neither is a group allowed to use a button on it; a
            // humanTask pointing at it is
            edges = listOf(
                mapOf("s" to "app:a", "t" to "form:lonely", "rel" to "contains"),
                mapOf("s" to "app:a", "t" to "form:used", "rel" to "contains"),
                mapOf("s" to "process:p", "t" to "form:used", "rel" to "humanTask-form"),
                mapOf("s" to "group:clerks", "t" to "form:gated", "rel" to "use"),
            ),
        )
        assertEquals(2, checks(r)["unusedForms"])
        assertEquals(setOf("form:lonely", "form:gated"), findings(r).filter { it["check"] == "unusedForms" }.map { it["node"] }.toSet())
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
    fun aCrossedColumnMappingIsReportedNextToItsCoverageRows() {
        // Two independent things can be wrong with one service, and the crossing half must not depend on
        // there being a changelog: an early `continue` on `schemaCoverage` would silence it entirely.
        val r = run(listOf(node("service:personService", "service", data = mapOf(
            "crossedColumns" to listOf(
                mapOf("kind" to "swapped", "mappings" to listOf(
                    mapOf("field" to "userName", "column" to "first_name_"),
                    mapOf("field" to "firstName", "column" to "user_name_"),
                )),
                mapOf("kind" to "crossed", "expected" to "customer_name_", "otherField" to "legacyName",
                    "mappings" to listOf(mapOf("field" to "customerName", "column" to "name_"))),
            ),
        ))))
        assertEquals(2, checks(r)["crossedColumns"])
        val f = findings(r).filter { it["check"] == "crossedColumns" }
        // A closed swap is provably wrong; one direction alone can still be a deliberate legacy mapping.
        assertEquals(listOf("error", "warning"), f.map { it["severity"] })
        assertEquals(
            "`userName` maps to column `first_name_` and `firstName` maps to `user_name_` — " +
                "the two column mappings look swapped",
            f[0]["message"],
        )
        assertEquals(
            "`customerName` maps to column `name_`, but the table's own `customer_name_` is the " +
                "column its name points at — `legacyName` maps that one",
            f[1]["message"],
        )
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
        // the same kind of site three times — three "Initialize variables" tasks — is said once, with a count
        val r2 = run(listOf(node("variable:flag", "variable", data = mapOf(
            "unread" to true, "writeCount" to 4,
            "writes" to listOf(
                mapOf("model" to "case:c", "via" to "variableMapping", "element" to "va1", "elementName" to "Initialize variables"),
                mapOf("model" to "case:c", "via" to "variableMapping", "element" to "va2", "elementName" to "Initialize variables"),
                mapOf("model" to "case:c", "via" to "variableMapping", "element" to "va3", "elementName" to "Initialize variables"),
                mapOf("model" to "case:c", "via" to "inParameter", "elementName" to "Fulfil"),
            ),
        ))))
        assertEquals(
            "written but never read — written by 3 init-variables mappings on `Initialize variables`, an in parameter on `Fulfil`",
            findings(r2).single()["message"],
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

    /**
     * A consumer of graph.json asks "what is broken" by kind: severity alone leaves out every
     * warning-level defect. The kind sits next to the check it comes from.
     */
    @Test
    fun everyFindingCarriesTheKindOfItsCheck() {
        val r = run(
            nodes = listOf(
                node("variable:guessed", "variable", data = mapOf("heuristic" to true)),
                node("external:gone", "external", data = mapOf("missingModel" to true)),
                process("p", mapOf(
                    "gateways" to listOf(gw("g1")), "userTasks" to listOf(task("a"), task("b")),
                    "flows" to listOf(flow("a1", "g1", "a", "\${x}"), flow("a2", "g1", "b", "\${!x}")),
                )),
            ),
        )
        assertTrue(findings(r).size >= 3)
        for (f in findings(r)) {
            assertEquals("kind of ${f["check"]}", CheckCatalog.kind(f["check"].toString()), f["kind"])
            assertEquals("kind follows the check", listOf("check", "kind"), f.keys.take(2))
        }
        val gateway = findings(r).single { it["check"] == "gatewayNoDefault" }
        assertEquals("a warning can be a defect", "warning" to CheckCatalog.DEFECT, gateway["severity"] to gateway["kind"])
    }

    // ---- how a process is wired and configured ----------------------------------------------------

    private fun process(id: String, data: Map<String, Any?>) = node("process:$id", "process", data = data)
    private fun flow(id: String, from: String, to: String, cond: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to id, "from" to from, "to" to to).also { if (cond != null) it["condition"] = cond }
    private fun gw(id: String, type: String = "exclusiveGateway", default: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to id, "name" to id, "type" to type).also { if (default != null) it["default"] = default }
    private fun task(id: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to id, "name" to id, *extra)
    private fun elements(r: Map<String, Any?>, check: String) =
        findings(r).filter { it["check"] == check }.map { it["element"] }

    @Test
    fun aGatewayWhoseEveryFlowIsConditionalAndHasNoDefaultIsReported() {
        val r = run(listOf(process("p", mapOf(
            "gateways" to listOf(gw("g1"), gw("g2", default = "b2"), gw("g3"), gw("g4", type = "parallelGateway"), gw("g5")),
            "userTasks" to listOf(task("a"), task("b")),
            "flows" to listOf(
                flow("a1", "g1", "a", "\${x == 1}"), flow("a2", "g1", "b", "\${x == 2}"),   // the finding
                flow("b1", "g2", "a", "\${x}"), flow("b2", "g2", "b"),            // has a default
                flow("c1", "g3", "a", "\${x}"), flow("c2", "g3", "b"),            // an unconditional flow is the way out
                flow("d1", "g4", "a", "\${x}"), flow("d2", "g4", "b", "\${y}"),    // a parallel gateway takes every flow
                flow("e1", "g5", "a", "\${x}"),                                 // one conditional flow throws just the same
            ),
        ))))
        assertEquals(listOf("g1", "g5"), elements(r, "gatewayNoDefault"))
        assertTrue(findings(r).all { it["check"] != "gatewayNoDefault" || it["message"].toString().contains("no outgoing sequence flow") })
        assertTrue(findings(r).single { it["element"] == "g5" }["message"].toString().contains("1 conditional outgoing flow and"))
    }

    @Test
    fun exactOppositesAlwaysLeaveAWayOut() {
        // Between two exact opposites an unset value makes the condition itself throw — no default could
        // catch that — so a gateway whose flows include such a pair always has a way out.
        val silent = listOf(
            listOf("\${x}", "\${ !x }"),
            listOf("#{a == b}", "#{a != b}"),
            listOf("\${b eq a}", "\${a ne b}"),
            listOf("\${vars:isEmpty(c)}", "\${variables:notEmpty(c)}"),
            listOf("\${not(x)}", "\${x}"),
            listOf("\${(a.b == 'x')}", "\${a.b != 'x'}"),
            listOf("\${y > 3}", "\${!x}", "\${x}"),   // two of three flows are opposites
        )
        // …and these are not opposites: null makes both false, or the halves differ
        val reported = listOf(
            listOf("\${approved == true}", "\${approved == false}"),
            listOf("\${vars:equals(x,'a')}", "\${vars:notEquals(x,'a')}"),
            listOf("\${amount > 10}", "\${amount <= 10}"),
            listOf("\${x && y}", "\${!x && y}"),
            listOf("\${a}\${b}", "\${!a}\${b}"),
            listOf("\${vars:isEmpty(a) || vars:isEmpty(b)}", "\${vars:isNotEmpty(a) || vars:isEmpty(b)}"),
            listOf("\${a == b}", "\${a == c}"),
        )
        fun gateway(i: Int, conds: List<String>) = conds.mapIndexed { k, c -> flow("f$i-$k", "g$i", "t", c) }
        val all = silent + reported
        val r = run(listOf(process("p", mapOf(
            "gateways" to all.indices.map { gw("g$it") },
            "flows" to all.flatMapIndexed { i, c -> gateway(i, c) },
        ))))
        assertEquals(reported.indices.map { "g${it + silent.size}" }, elements(r, "gatewayNoDefault"))
        assertTrue(Findings.hasComplementaryPair(listOf("\${x}", "\${!x}")))
    }

    @Test
    fun aFlowAfterAnUnconditionalOneIsNeverTaken() {
        fun dflt(id: String, from: String) = linkedMapOf<String, Any?>("id" to id, "from" to from, "to" to "t", "default" to true)
        val r = run(listOf(process("p", mapOf(
            "gateways" to listOf(
                gw("g1", default = "slow"), gw("g2"), gw("g3"),
                gw("g4", type = "inclusiveGateway", default = "d4"), gw("g5", type = "parallelGateway"),
                gw("g6", default = "d6"),
            ),
            "flows" to listOf(
                // the default can never be reached past a flow with no condition
                linkedMapOf<String, Any?>("id" to "fast", "name" to "≥ 15 km/h", "from" to "g1", "to" to "t"), dflt("slow", "g1"),
                // an unconditional flow written last is an implicit default: quiet
                flow("c2", "g2", "t", "\${x}"), flow("u2", "g2", "t"),
                // written first, it shadows everything after it
                flow("u3", "g3", "t"), flow("c3", "g3", "t", "\${x}"), flow("c3b", "g3", "t", "\${y}"),
                // an inclusive gateway takes every true flow; only its default dies
                flow("u4", "g4", "t"), flow("c4", "g4", "t", "\${x}"), dflt("d4", "g4"),
                // a parallel gateway takes every flow anyway
                flow("u5", "g5", "t"), flow("v5", "g5", "t"),
                // every other flow conditional: the default is the way out, nothing is shadowed
                flow("c6", "g6", "t", "\${x}"), dflt("d6", "g6"),
            ),
        ))))
        assertEquals(listOf("g1", "g3", "g4"), elements(r, "shadowedFlow"))
        val msg = findings(r).filter { it["check"] == "shadowedFlow" }.associate { it["element"] to it["message"].toString() }
        assertEquals("`g1` always leaves through `≥ 15 km/h`, which has no condition — the default `slow` is never taken", msg["g1"])
        assertEquals("`g3` always leaves through `u3`, which has no condition — `c3`, `c3b` are never taken", msg["g3"])
        assertEquals("`g4` always leaves through `u4`, which has no condition — the default `d4` is never taken", msg["g4"])
        // a gateway with an unconditional flow has a way out: gatewayNoDefault is not the question here
        assertTrue(elements(r, "gatewayNoDefault").isEmpty())
    }

    @Test
    fun anActivityWhoseEveryFlowIsConditionalNeedsADefault() {
        val r = run(listOf(process("p", mapOf(
            "userTasks" to listOf(task("t1"), task("t2"), task("t3"), task("end"), task("t5")),
            "serviceTasks" to listOf(task("dmn", "type" to "dmn")), "ruleTasks" to listOf(task("dmn")),
            "flows" to listOf(
                flow("a", "t1", "x", "\${x}"),                                   // one condition, no way out
                flow("b1", "t2", "x", "\${x}"), flow("b2", "t2", "y", "\${y}"),  // two, neither the default
                flow("c1", "t3", "x", "\${x}"),                                  // with its default flow: quiet
                linkedMapOf<String, Any?>("id" to "c2", "from" to "t3", "to" to "y", "default" to true),
                flow("e1", "t5", "x", "\${ok}"), flow("e2", "t5", "y", "\${!ok}"), // exact opposites: quiet
                flow("d1", "dmn", "x", "\${hit}"),                                // listed twice, judged once
            ),
        ))))
        assertEquals(listOf("dmn", "t1", "t2"), elements(r, "gatewayNoDefault").sortedBy { it.toString() })
        val msg = findings(r).filter { it["check"] == "gatewayNoDefault" }.associate { it["element"] to it["message"].toString() }
        assertEquals("`t1` leaves only through 1 conditional flow and has no default flow — when it is false the " +
            "engine throws \"no outgoing sequence flow\"", msg["t1"])
        assertTrue(msg["t2"]!!, msg["t2"]!!.contains("none of them is true"))
        assertTrue(elements(r, "implicitSplit").isEmpty())
    }

    @Test
    fun anActivityWithTwoUnconditionalFlowsIsAnImplicitSplit() {
        val r = run(listOf(process("p", mapOf(
            "userTasks" to listOf(task("fork"), task("choice"), task("mixed"), task("straight"), task("guided")),
            // a DMN service task is listed twice by the parser — one element, one finding
            "serviceTasks" to listOf(task("dmn", "type" to "dmn")), "ruleTasks" to listOf(task("dmn")),
            "gateways" to listOf(gw("g", type = "parallelGateway")),
            "flows" to listOf(
                flow("f1", "fork", "x"), flow("f2", "fork", "y"),                  // both run in parallel
                flow("c1", "choice", "x", "\${a}"), flow("c2", "choice", "y", "\${!a}"), // a choice drawn as conditions: quiet
                flow("m1", "mixed", "x"), flow("m2", "mixed", "y", "\${a}"),        // the unconditional one always runs
                flow("s1", "straight", "x"),
                flow("g1", "g", "x"), flow("g2", "g", "y"),                        // a gateway is what a fork should be
                // BPMN lets an activity carry a default flow: taken only when no condition holds — quiet
                flow("u1", "guided", "x", "\${a}"), linkedMapOf<String, Any?>("id" to "u2", "from" to "guided", "to" to "y", "default" to true),
                flow("r1", "dmn", "x"), flow("r2", "dmn", "y"),
            ),
        ))))
        assertEquals(listOf("dmn", "fork", "mixed"), elements(r, "implicitSplit").sortedBy { it.toString() })
        assertTrue(findings(r).first { it["element"] == "fork" }["message"].toString().contains("all of them run in parallel"))
    }

    @Test
    fun aButtonThatReplacesThePayloadIsReportedOnTheButton() {
        fun btn(id: String, type: String, value: String?, vararg settings: Pair<String, Any?>) =
            linkedMapOf<String, Any?>("id" to id, "type" to type, "value" to value, "replacesPayload" to true)
                .apply { if (settings.isNotEmpty()) put("settings", mapOf(*settings)) }
        val r = run(listOf(
            node("form:f", "form", data = mapOf("fields" to listOf(
                btn("clear", "scriptButton", "{{}}"),
                btn("score", "restButton", null, "autoExecute" to true),
                btn("sum", "scriptButton", "{{a + b}}", "autoExecute" to "{{a}}"),
                mapOf("id" to "fine", "type" to "scriptButton", "value" to "{{total}}"),
            ))),
            // a page is the same component model, and it is never an "unused form"
            node("page:p", "page", data = mapOf("fields" to listOf(btn("reset", "scriptButton", "{{ }}")))),
        ), edges = listOf(mapOf("s" to "process:x", "t" to "form:f", "rel" to "humanTask-form")))
        val found = findings(r).filter { it["check"] == "payloadOverwrite" }
        assertEquals(listOf("clear", "reset", "score", "sum"), found.map { it["element"] }.sortedBy { it.toString() })
        val msg = found.associate { it["element"] to it["message"].toString() }
        assertEquals("Expression button `clear` stores its result in `{{}}`, which names no variable — " +
            "pressing it replaces the whole payload", msg["clear"])
        assertEquals("REST button `score` has no value to store its response in — it runs as the form opens " +
            "and replaces the whole payload", msg["score"])
        assertTrue(msg.getValue("sum").contains("which is not a variable path — pressing it, or its automatic run,"))
        // the binding itself is the snippet, so the row shows the expression that is wrong
        assertEquals("{{}}", found.single { it["element"] == "clear" }["snippet"])
        assertEquals(4, checks(r)["payloadOverwrite"])
        assertTrue(findings(r).none { it["check"] == "unusedForms" })
    }

    @Test
    fun aDecisionNothingCallsIsReported() {
        val r = run(
            nodes = listOf(node("decision:orphan", "decision"), node("decision:used", "decision"),
                // a decision service is the caller of its tables, never an unused table
                node("decision:drd", "decision", data = mapOf("decisionService" to true))),
            edges = listOf(
                mapOf("s" to "app:a", "t" to "decision:orphan", "rel" to "contains"),
                mapOf("s" to "process:p", "t" to "decision:used", "rel" to "ruleTask-decision"),
                mapOf("s" to "app:a", "t" to "decision:drd", "rel" to "contains"),
            ),
        )
        assertEquals(1, checks(r)["unusedDecisions"])
        assertEquals("decision:orphan", findings(r).single { it["check"] == "unusedDecisions" }["node"])
    }

    @Test
    fun theRuntimeRiskChecksStayQuietWhereTheModelGivesNoReasonToSpeak() {
        // Only the opt-out is reported; a plain async element and one with its own retry policy are not.
        val r1 = run(listOf(process("p", mapOf("serviceTasks" to listOf(
            task("optOut", "async" to "true", "exclusive" to "false"),
            task("plain", "async" to "true", "retryTimeCycle" to "R3/PT10M"),
            task("sync"))))))
        assertEquals(listOf("optOut"), elements(r1, "nonExclusiveAsync"))
        assertEquals(listOf("optOut"), elements(r1, "asyncWithoutRetry"))

        // Unguarded: a leaving task without a boundary is reported; a guarded one, a task that stays in
        // the engine, and every task in a process that catches centrally are not.
        val leaving = task("http", "type" to "http")
        val r2 = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(leaving, task("guarded", "class" to "com.example.X"), task("inEngine", "type" to "dmn")),
            "events" to listOf(mapOf("id" to "err", "type" to "boundaryEvent", "def" to "error", "attachedTo" to "guarded"))))))
        assertEquals(listOf("http"), elements(r2, "unguardedTasks"))
        // Design writes a platform bean into every task type's delegate, so the bean alone is not a call
        // out: only the task types that leave, the project's own code, and a REST service are.
        val r2b = run(listOf(
            node("service:crm", "service", data = mapOf("type" to "REST")),
            node("service:orders", "service", data = mapOf("type" to "database")),
            process("p", mapOf("serviceTasks" to listOf(
                task("initVars", "type" to "init-variables", "delegateExpression" to "\${initVariablesService}"),
                task("audit", "type" to "audit", "delegateExpression" to "\${auditLogService}"),
                task("ownBean", "delegateExpression" to "\${orderService}"),
                task("ownExpr", "expression" to "\${orderService.place(order)}"),
                task("ctxExpr", "expression" to "\${execution.setVariable('x', 1)}"),
                task("restSvc", "type" to "service-registry", "delegateExpression" to "\${serviceRegistryService}", "serviceModelKey" to "crm"),
                task("dbSvc", "type" to "service-registry", "delegateExpression" to "\${serviceRegistryService}", "serviceModelKey" to "orders"),
                task("tolerant", "type" to "http", "fields" to mapOf("requestUrl" to "https://x", "ignoreException" to "true")),
                task("agent", "type" to "agent", "delegateExpression" to "\${agentService}"),
                task("mail", "type" to "mail", "delegateExpression" to "\${mailServiceTask}"),
                // an async task's failure is a failed job, not an exception to the caller
                task("asyncMail", "type" to "mail", "async" to "true"),
                // a platform function namespace and an engine service are not code of the project's
                task("fn", "expression" to "\${bpmn:removeAssignee()}"),
                task("engineApi", "expression" to "\${dataObjectRuntimeService.addUserIdentityLink(x, y)}"),
                // the platform's scripting API root, an Engage task delegate and the CMMN query root
                task("flwApi", "expression" to "\${flw.setOutput('x', 1)}"),
                task("engage", "delegateExpression" to "\${processSendMessageTask}"),
                task("cmmnCtx", "expression" to "\${planItemInstances.definitionId('a').count()}"),
                // an expression whose root is a variable reads it; one whose root is named like a bean calls out
                task("varExpr", "expression" to "\${requesterData.getName()}"),
                task("projBean", "expression" to "\${userProfileDataService.load(x)}"))))))
        assertEquals(listOf("ownBean", "ownExpr", "restSvc", "agent", "mail", "projBean"), elements(r2b, "unguardedTasks"))
        // the message names the fix for the kind of call
        val msgs = findings(r2b).filter { it["check"] == "unguardedTasks" }.associate { it["element"] to it["message"].toString() }
        assertTrue(msgs["mail"]!!, msgs["mail"]!!.contains("mark the task async"))
        assertTrue(msgs["ownBean"]!!, msgs["ownBean"]!!.contains("code of the project's own"))
        assertTrue(msgs["restSvc"]!!, msgs["restSvc"]!!.contains("calls out of the engine"))
        assertTrue(msgs["agent"]!!, msgs["agent"]!!.contains("calls out of the engine"))
        // an error event subprocess catches for the whole process — what can raise a BPMN error
        val own = task("own", "class" to "com.example.X")
        val r3 = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(own, leaving),
            "events" to listOf(mapOf("id" to "catchAll", "type" to "startEvent", "def" to "error"))))))
        assertEquals("a failed HTTP call is no BPMN error: nothing catches it", listOf("http"), elements(r3, "unguardedTasks"))
        val r4 = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(own, leaving), "subProcesses" to listOf(task("sub")),
            "events" to listOf(mapOf("id" to "e", "type" to "boundaryEvent", "def" to "error", "attachedTo" to "sub"))))))
        assertEquals("a subprocess with an error boundary may contain the task", listOf("http"), elements(r4, "unguardedTasks"))
    }

    @Test
    fun onlyABpmnErrorReachesAnErrorBoundary() {
        fun boundary(on: String) = mapOf("id" to "b-$on", "type" to "boundaryEvent", "def" to "error", "attachedTo" to on)
        val r = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(
                // a failed mail is a FlowableException: the boundary never fires…
                task("mail", "type" to "mail"),
                // …unless the task maps it to an error code
                task("mappedMail", "type" to "mail", "mapsExceptions" to true),
                task("http", "type" to "http", "fields" to mapOf("requestUrl" to "https://x")),
                task("mappedHttp", "type" to "http", "mapsExceptions" to true),
                task("ownCode", "class" to "com.example.X"),
                // status codes become BPMN error HTTP<code>: a promise that something catches them
                task("statusMapped", "type" to "http", "fields" to mapOf("requestUrl" to "https://x", "handleStatusCodes" to "4XX,5XX")),
                task("statusCaught", "type" to "http", "fields" to mapOf("handleStatusCodes" to "5XX")),
                // ignoreException swallows a failed call, but not the BPMN error a mapped status raises
                task("both", "type" to "http", "fields" to mapOf("requestUrl" to "https://x", "ignoreException" to "true", "handleStatusCodes" to "5XX")),
                task("tolerant", "type" to "http", "fields" to mapOf("ignoreException" to "true")),
            ),
            "events" to listOf(boundary("mail"), boundary("mappedMail"), boundary("http"), boundary("mappedHttp"),
                boundary("ownCode"), boundary("statusCaught")),
        ))))
        assertEquals(listOf("mail", "http", "statusMapped", "both"), elements(r, "unguardedTasks"))
        val msgs = findings(r).filter { it["check"] == "unguardedTasks" }.associate { it["element"] to it["message"].toString() }
        assertTrue(msgs["mail"]!!, msgs["mail"]!!.contains("mapException") && msgs["mail"]!!.contains("no error boundary"))
        assertTrue(msgs["statusMapped"]!!, msgs["statusMapped"]!!.contains("HTTP<code>") && msgs["statusMapped"]!!.contains("4XX,5XX"))
        assertTrue(msgs["both"]!!, msgs["both"]!!.contains("HTTP<code>"))
    }

    @Test
    fun aTaskOnlyAJobReachesHasNoCaller() {
        fun ev(id: String, type: String, def: String? = null, vararg extra: Pair<String, Any?>) =
            linkedMapOf<String, Any?>("id" to id, "type" to type, "def" to def, *extra)
        fun bean(id: String, vararg extra: Pair<String, Any?>) = task(id, "delegateExpression" to "\${orderService}", *extra)
        val r = run(listOf(process("p", mapOf(
            "events" to listOf(
                ev("timerStart", "startEvent", "timer"), ev("noneStart", "startEvent"),
                ev("tb", "boundaryEvent", "timer", "attachedTo" to "wait"),
                ev("timerCatch", "intermediateCatchEvent", "timer"),
            ),
            "userTasks" to listOf(task("wait"), task("asyncWait", "async" to "true")),
            "gateways" to listOf(gw("join"), gw("loop")),
            "serviceTasks" to listOf(
                bean("afterTimer"), task("afterBoundary", "type" to "mail"), bean("afterUser"), bean("afterAsyncUser"),
                bean("asyncStep", "async" to "true"), bean("afterAsyncStep"), bean("afterJoin"), bean("inLoop"),
                bean("orphan"), bean("afterTimerCatch"),
            ),
            "flows" to listOf(
                flow("1", "timerStart", "afterTimer"),                  // a timer start: the job is the caller
                flow("2", "tb", "afterBoundary"),                       // a timer boundary: the same
                flow("3", "noneStart", "wait"), flow("4", "wait", "afterUser"),           // a user completes it
                flow("5", "asyncWait", "afterAsyncUser"),               // async or not, a user task waits
                flow("6", "afterTimer", "asyncStep"), flow("7", "asyncStep", "afterAsyncStep"), // an async step's job
                flow("8", "timerCatch", "join"), flow("9", "wait", "join"), flow("10", "join", "afterJoin"), // one way has a caller
                flow("11", "afterTimer", "loop"), flow("12", "loop", "inLoop"), flow("13", "inLoop", "loop"), // a loop entered from a job
                flow("14", "timerCatch", "afterTimerCatch"),
            ),
        ))))
        assertEquals(listOf("afterUser", "afterAsyncUser", "afterJoin", "orphan"), elements(r, "unguardedTasks"))
    }

    @Test
    fun aPlanItemListenerScriptProblemIsAFinding() {
        // the parser flags a lifecycle listener that carries a script (the engine's factory has no script
        // branch — a silent no-op); the finding has to reach the case's page
        val case = mapOf("key" to "c", "name" to "Case", "file" to "c.cmmn", "planModel" to mapOf(
            "id" to "plan", "children" to listOf(mapOf(
                "id" to "t1", "name" to "Review",
                "listeners" to listOf(mapOf("kind" to "planItemLifecycleListener", "problems" to listOf(
                    mapOf("severity" to "warning", "message" to "a script on a lifecycle listener is never run")))),
            )),
        ))
        // script findings hang off the parsed model buckets, not off the graph nodes
        val r = run(listOf(node("case:c", "case")), extra = mapOf("cases" to listOf(case)))
        val f = findings(r).single { it["check"] == "scriptIssues" }
        assertEquals("case:c", f["node"])
        assertEquals("t1", f["element"])
        assertTrue(f["message"].toString().contains("never run"))
    }

    // ---- what a model writes down that it should not ------------------------------------------------

    @Test
    fun aLiteralSecretIsAnErrorWhereverTheParserRecordedOne() {
        val r = run(listOf(
            node("service:s", "service", data = mapOf("literalSecrets" to listOf("config.authentication.password"))),
            node("knowledgeBase:kb", "knowledgeBase", data = mapOf("literalSecrets" to listOf("vectorStore.credentials.apiKey"))),
            process("p", mapOf("serviceTasks" to listOf(task("http", "type" to "http", "secretFields" to listOf("password"))))),
            node("case:c", "case", data = mapOf("planModel" to mapOf("id" to "plan", "children" to listOf(
                mapOf("id" to "st", "name" to "Call it", "secretFields" to listOf("apiKey")))))),
            node("service:clean", "service"),
        ))
        val f = findings(r).filter { it["check"] == "hardcodedSecrets" }
        assertEquals(4, f.size)
        assertTrue(f.all { it["severity"] == "error" })
        assertEquals(setOf("config.authentication.password", "vectorStore.credentials.apiKey", "password", "apiKey"),
            f.map { it["subject"] }.toSet())
        assertEquals("st", f.single { it["node"] == "case:c" }["element"])
        assertTrue("never the value", f.none { it["message"].toString().contains("hunter") })
    }

    @Test
    fun aQueryTemplateThatInterpolatesWithoutEscapingIsReportedPerParameter() {
        val r = run(listOf(node("query:q", "query", data = mapOf(
            "templateContent" to """{"term": {"name": "${'$'}{customerName}"}, "range": {"gte": ${'$'}{minTotal?c}}, "x": "${'$'}{customerName}"}""",
            "templateFilter" to """{"term": {"owner": "${'$'}{owner.id}"}}""",
        ))))
        val f = findings(r).filter { it["check"] == "unsafeQueries" }
        assertEquals(listOf("customerName", "customerName", "owner"), f.map { it["subject"] })
        assertTrue(f.first()["message"].toString().contains("?json_string"))
    }

    @Test
    fun aLeftoverMarkerIsAWarningOnTheModelItsFileDefines() {
        val r = run(
            nodes = listOf(process("p", emptyMap())),
            extra = mapOf("markers" to listOf(
                mapOf("file" to "processes/p.bpmn", "line" to 12, "marker" to "TODO", "text" to "confirm the SLA", "models" to listOf("process:p")),
                // the same model inside an archive: the same marker, not a second finding
                mapOf("file" to "app.bar!processes/p.bpmn", "line" to 12, "marker" to "TODO", "text" to "confirm the SLA", "models" to listOf("process:p")),
                mapOf("file" to "forms/loose.form", "line" to 3, "marker" to "FIXME", "text" to "", "models" to emptyList<String>()),
                // a text-less marker in a minified model is named by the path of the element holding it
                mapOf("file" to "processes/p.bpmn", "line" to 1, "marker" to "HACK", "text" to "", "path" to "rows[2].cols[0].label", "models" to listOf("process:p")),
            )),
        )
        val f = findings(r).filter { it["check"] == "leftoverMarkers" }
        assertEquals(3, f.size)
        val todo = f.single { it["line"] == 12 }
        val fixme = f.single { it["line"] == 3 }
        val hack = f.single { it["line"] == 1 }
        assertEquals("process:p", todo["node"]); assertEquals("TODO: confirm the SLA", todo["message"]); assertEquals("confirm the SLA", todo["subject"])
        assertEquals("processes/p.bpmn", todo["file"])
        assertEquals(null, fixme["node"]); assertEquals("forms/loose.form", fixme["label"]); assertEquals("FIXME left in the model", fixme["message"])
        assertEquals("@3", fixme["subject"])
        assertEquals("rows[2].cols[0].label", hack["subject"])
    }

    /** A baseline compares findings by the key a waiver uses; a message that rewords is the same finding. */
    @Test
    fun aFindingKeepsItsIdentityWhenItsMessageRewords() {
        val a = mapOf<String, Any?>("check" to "unusedVars", "node" to "variable:DEMO-x", "message" to "written by `a` (+2 more)")
        assertEquals(Findings.identity(a), Findings.identity(a + ("message" to "written by `a` (+3 more)")))
        assertTrue(Findings.identity(a) != Findings.identity(a + ("subject" to "process:DEMO-p")))
        assertTrue(Findings.identity(a) != Findings.identity(a + ("element" to "DEMO-task")))
        // A parse finding has no node; its file is what it is about.
        val p = mapOf<String, Any?>("check" to "parseIssues", "node" to null, "file" to "forms/a.form")
        assertTrue(Findings.identity(p) != Findings.identity(p + ("file" to "forms/b.form")))
        // …which is the key a waiver narrows on: a rule built from the parts covers the finding.
        for (f in listOf(a + ("element" to "DEMO-task"), p)) {
            val w = Waivers.Waiver(check = f["check"].toString(), node = (f["node"] ?: f["file"]).toString(),
                element = f["element"] as? String, subject = f["subject"] as? String)
            assertTrue("$f", w.covers(f))
        }
    }

    /** Counted, not set-wise: the same finding twice against once in the base is one new finding. */
    @Test
    fun newSinceCountsOccurrences() {
        val x = mapOf<String, Any?>("check" to "unsafeQueries", "node" to "query:DEMO-q", "subject" to "templateContent")
        val y = mapOf<String, Any?>("check" to "missingRefs", "node" to "external:DEMO-y")
        val head = listOf(LinkedHashMap(x), LinkedHashMap(x), LinkedHashMap(y))
        val new = Findings.newSince(listOf(x), head)
        assertEquals(2, new.size)
        assertTrue("the instances of head, in its order", new[0] === head[1] && new[1] === head[2])
        assertTrue(Findings.newSince(head, head).isEmpty())
    }

    @Test
    fun aCleanProjectReportsNoFindingsAtAll() {
        val r = run(listOf(node("process:p", "process")))
        assertEquals(0, checks(r)["open"])
        assertTrue(findings(r).isEmpty())
    }
}
