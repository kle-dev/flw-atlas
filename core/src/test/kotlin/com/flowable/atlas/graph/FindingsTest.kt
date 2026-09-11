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
                flow("a1", "g1", "a", "\${x}"), flow("a2", "g1", "b", "\${!x}"),   // the finding
                flow("b1", "g2", "a", "\${x}"), flow("b2", "g2", "b"),            // has a default
                flow("c1", "g3", "a", "\${x}"), flow("c2", "g3", "b"),            // an unconditional flow is the way out
                flow("d1", "g4", "a", "\${x}"), flow("d2", "g4", "b", "\${y}"),    // a parallel gateway takes every flow
                flow("e1", "g5", "a", "\${x}"),                                 // one flow is no choice
            ),
        ))))
        assertEquals(listOf("g1"), elements(r, "gatewayNoDefault"))
        assertTrue(findings(r).single { it["check"] == "gatewayNoDefault" }["message"].toString().contains("no outgoing sequence flow"))
    }

    @Test
    fun anActivityWithTwoUnconditionalFlowsIsAnImplicitSplit() {
        val r = run(listOf(process("p", mapOf(
            "userTasks" to listOf(task("fork"), task("choice"), task("mixed"), task("straight")),
            "gateways" to listOf(gw("g", type = "parallelGateway")),
            "flows" to listOf(
                flow("f1", "fork", "x"), flow("f2", "fork", "y"),                  // both run in parallel
                flow("c1", "choice", "x", "\${a}"), flow("c2", "choice", "y", "\${!a}"), // a choice drawn as conditions: quiet
                flow("m1", "mixed", "x"), flow("m2", "mixed", "y", "\${a}"),        // the unconditional one always runs
                flow("s1", "straight", "x"),
                flow("g1", "g", "x"), flow("g2", "g", "y"),                        // a gateway is what a fork should be
            ),
        ))))
        assertEquals(listOf("fork", "mixed"), elements(r, "implicitSplit").sortedBy { it.toString() })
        assertTrue(findings(r).first { it["element"] == "fork" }["message"].toString().contains("all of them run in parallel"))
    }

    @Test
    fun aDecisionNothingCallsIsReported() {
        val r = run(
            nodes = listOf(node("decision:orphan", "decision"), node("decision:used", "decision")),
            edges = listOf(
                mapOf("s" to "app:a", "t" to "decision:orphan", "rel" to "contains"),
                mapOf("s" to "process:p", "t" to "decision:used", "rel" to "ruleTask-decision"),
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
                task("mail", "type" to "mail", "delegateExpression" to "\${mailServiceTask}"))))))
        assertEquals(listOf("ownBean", "ownExpr", "restSvc", "agent", "mail"), elements(r2b, "unguardedTasks"))
        val r3 = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(leaving),
            "events" to listOf(mapOf("id" to "catchAll", "type" to "startEvent", "def" to "error"))))))
        assertTrue("an error event subprocess catches for the whole process", elements(r3, "unguardedTasks").isEmpty())
        val r4 = run(listOf(process("p", mapOf(
            "serviceTasks" to listOf(leaving), "subProcesses" to listOf(task("sub")),
            "events" to listOf(mapOf("id" to "e", "type" to "boundaryEvent", "def" to "error", "attachedTo" to "sub"))))))
        assertTrue("a subprocess with an error boundary may contain the task", elements(r4, "unguardedTasks").isEmpty())
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
                mapOf("file" to "forms/loose.form", "line" to 3, "marker" to "FIXME", "text" to "", "models" to emptyList<String>()),
            )),
        )
        val f = findings(r).filter { it["check"] == "leftoverMarkers" }
        assertEquals(2, f.size)
        val todo = f.single { it["line"] == 12 }
        val fixme = f.single { it["line"] == 3 }
        assertEquals("process:p", todo["node"]); assertEquals("TODO: confirm the SLA", todo["message"]); assertEquals("confirm the SLA", todo["subject"])
        assertEquals(null, fixme["node"]); assertEquals("forms/loose.form", fixme["label"]); assertEquals("FIXME left in the model", fixme["message"])
    }

    @Test
    fun aCleanProjectReportsNoFindingsAtAll() {
        val r = run(listOf(node("process:p", "process")))
        assertEquals(0, checks(r)["open"])
        assertTrue(findings(r).isEmpty())
    }
}
