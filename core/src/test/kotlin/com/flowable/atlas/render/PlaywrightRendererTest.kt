package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * `<project>.playwright.md` (and its project map) — what an agent driving Flowable Work reads before the
 * first click.
 *
 * No golden: the generic half is prose that will be edited, and pinning it byte-for-byte would make every
 * correction a re-baseline. What is pinned is what an agent acts on: how a definition is started, how the
 * stage bar is numbered (the engine's order, not the model's) and when that numbering moves, what is said
 * to start a stage and who sets the variable its condition reads, which button a manual item becomes,
 * which form fields block an outcome and what rules they check, that §10's queries run against the real
 * graph — and that the file declares itself a skill, with its details split off when asked to.
 *
 * The demo case is `site/flowable-demo/cases/claim.cmmn` with `forms/claim-*.form`; change them together
 * with this test.
 */
class PlaywrightRendererTest {

    private val demoDir by lazy { File(GoldenFiles.repoRoot, "site/flowable-demo") }
    private val demoResult by lazy { Atlas.extract(demoDir) }
    private val demo by lazy { PlaywrightRenderer.render(demoResult, demoDir) }

    // ---- the documentation site's demo case: five stages, an outcome-gated route, a jump -----------

    @Test
    fun theFileOpensAsAClaudeCodeSkill() {
        assertTrue(demo, demo.startsWith("---\nname: ${PlaywrightRenderer.SKILL_NAME}\ndescription: \"Guides driving"))
        val description = demo.lines()[2].removePrefix("description: ")
        assertTrue("a double-quoted YAML scalar", description.startsWith("\"") && description.endsWith("\""))
        assertTrue("within Claude Code's limit", description.length - 2 <= PlaywrightRenderer.DESCRIPTION_MAX)
        assertTrue("it names the project's cases", description.contains("Claim handling"))
        assertTrue(demo.contains(".claude/skills/${PlaywrightRenderer.SKILL_NAME}/SKILL.md"))
        assertTrue("the header says what to read first", demo.contains("Read §0 and §1 now, then the §9 entry"))
    }

    @Test
    fun theIndexNamesEveryDeployedCaseWithItsStartLinkAndBar() {
        assertTrue(demo, demo.contains("| Case or process | Kind | Start | Stage bar, or user tasks | May start |"))
        assertTrue(demo, demo.contains("| **Claim handling** `claimCase` | case | `#/work?create=true&definitionKey=claimCase` | " +
            "Intake · Assessment · Decision · Settlement · Closure | groups `claimHandlers` |"))
        assertTrue("two definitions share a title", demo.contains("**Review Case** `reviewCase` ⚠ shared title"))
        assertTrue("a process another model runs is opened there", demo.contains("sub-item of case `reviewCase` — open it there"))
    }

    @Test
    fun theStageBarIsNumberedAsWorkShowsIt() {
        assertTrue(demo, demo.contains("**Stage bar:** Step 1 *Intake* · Step 2 *Assessment* · Step 3 *Decision* · " +
            "Step 4 *Settlement* · Step 5 *Closure*"))
        assertTrue("a milestone kept out of the bar is named as such",
            demo.contains("Kept out of the bar (`includeInStageOverview=\"false\"`): *Claim paid* (milestone)."))
        assertFalse("every stage has its own displayOrder: no tie", demo.substringAfter("## 9.").contains("share a `displayOrder`"))
    }

    @Test
    fun aStageSaysWhatStartsItAndWhatSetsItsCondition() {
        assertTrue(demo, demo.contains("**Step 3 of 5 — Decision** — starts when: *Assessment* completes **and** " +
            "`\${assessmentOutcome == 'approve'}` ← outcome **Approve** on task *Assess damage*"))
        assertTrue(demo, demo.contains("**Step 5 of 5 — Closure** — starts when: *Settlement* completes — **or** — " +
            "*Close without payout* is triggered (someone clicks it)"))
        assertTrue(demo, demo.contains("**Step 1 of 5 — Intake** — starts as soon as the case starts · completes: autoComplete"))
    }

    @Test
    fun aManualItemIsTheButtonThatStartsIt() {
        assertTrue(demo, demo.contains("task *Request expert opinion* — no form (a plain *Complete*) · groups `claimHandlers` · " +
            "**manual activation**: action button **Request expert opinion** — start it before the stage's required work is done"))
        assertTrue(demo, demo.contains("user event listener *Close without payout* — button **Close without payout**"))
        assertTrue("the jump it makes", demo.contains("pressing it starts *Closure*"))
    }

    @Test
    fun theFormsBehindTheRouteSayWhatBlocksTheirOutcomes() {
        assertTrue(demo, demo.contains("- `claimAssessmentForm` *Claim assessment* — task *Assess damage* of case `claimCase`"))
        assertTrue(demo, demo.contains("required: *Damage amount* `damageAmount`, *Expert report* `expertReport` (if `{{expertRequested}}`)"))
        assertTrue(demo, demo.contains("outcomes → `assessmentOutcome`: *Approve* `approve` · *Reject* `reject`"))
        assertTrue(demo, demo.contains("conditional buttons: *Estimate payout* `estimatePayout` enabled if `{{damageAmount > 0}}` " +
            "← field *Damage amount* `damageAmount`"))
        assertTrue("the start form's pattern", demo.contains("rules: *Policy number* `policyNumber` must match `[A-Z]{2}-[0-9]{6}` (the whole value)"))
        assertTrue("a hand-written form does not grey its outcomes out", demo.contains("the outcomes stay enabled while this form is invalid"))
        assertTrue("an action button carries the form key on the page",
            demo.contains("*Notify customer* `notifyButton` (on the page `orderForm_notifyButton`) runs action `notifyCustomerAction`"))
    }

    @Test
    fun theStartLinkOpensTheDefinitionAndNamesItsForm() {
        assertTrue(demo, demo.contains("start: `#/work?create=true&definitionKey=claimCase` → form `claimIntakeForm` → **Submit**"))
        assertTrue("a start form with outcomes has no Submit",
            demo.contains("definitionKey=onboardingReviewProcess` → form `onboardingForm` → **Accept** / **Reject**"))
    }

    @Test
    fun noReprLeaksIntoEitherFile() {
        val split = PlaywrightRenderer.Layout(map = true)
        for (text in listOf(demo, PlaywrightRenderer.renderMap(demoResult, demoDir, split))) {
            for (needle in listOf("null", "None", "{'", "['")) {
                val hit = text.lines().firstOrNull { line -> Regex("(?<![\\w`])${Regex.escape(needle)}(?![\\w`])").containsMatchIn(line) }
                assertNull("«$needle» leaked: $hit", hit)
            }
        }
    }

    // ---- the map file --------------------------------------------------------------------------------

    @Test
    fun withAMapTheGuideKeepsTheIndexAndTheMapHoldsTheDetails() {
        val layout = PlaywrightRenderer.Layout(map = true)
        val main = PlaywrightRenderer.render(demoResult, demoDir, layout)
        val map = PlaywrightRenderer.renderMap(demoResult, demoDir, layout)
        assertTrue("the index stays", main.contains("| **Claim handling** `claimCase` | case |"))
        assertFalse("the details move", main.contains("#### Case `claimCase`"))
        assertTrue("both places it can be", main.contains("`project-map.md` beside this file when it is installed as a skill, else `flowable-demo.playwright-map.md`"))
        assertTrue(main, main.contains(".claude/skills/${PlaywrightRenderer.SKILL_NAME}/${PlaywrightRenderer.SKILL_MAP_FILE}"))
        assertTrue(map, map.startsWith("# Flowable Work project map — `flowable-demo`"))
        assertTrue(map.contains("#### Case `claimCase` — Claim handling"))
        assertTrue(map.contains("**Step 3 of 5 — Decision**"))
        assertTrue(map.contains("rules: *Policy number* `policyNumber`"))
        assertFalse("the generic guide is not repeated", map.contains("## 1. Rules for driving Work"))
        // alone, one file holds everything
        assertTrue(demo.contains("#### Case `claimCase`") && !demo.contains("project-map.md"))
    }

    // ---- §10: the lookups run against the graph the guide points at ------------------------------------

    @Test
    fun everyLookupRunsAgainstTheGraph() {
        val graphText = GraphJsonRenderer.render(demoResult)
        val graph = MiniJson.parse(graphText)
        for ((_, filter) in PlaywrightRenderer.LOOKUPS) {
            assertTrue("§10 prints it as is: $filter", demo.contains("jq '$filter'"))
        }
        val values = mapOf(
            "FORM" to objs(field(graph, "forms")).map { it["key"].toString() },
            "CASE" to objs(field(graph, "cases")).map { it["key"].toString() },
            "PROCESS" to objs(field(graph, "processes")).map { it["key"].toString() },
            "VAR" to objs(field(field(graph, "graph"), "nodes")).map { it["id"].toString() }
                .filter { it.startsWith("variable:") }.map { it.removePrefix("variable:") },
        )
        for ((what, filter) in PlaywrightRenderer.LOOKUPS) {
            val holder = values.keys.single { filter.contains("\"$it\"") || filter.contains(":$it\"") }
            val out = values.getValue(holder).flatMap { v -> jq(filter.replace(holder, v), graph) }
            assertTrue("«$what» finds nothing in the demo graph", out.any { it != null })
            // an object construction names keys the graph actually has — somewhere
            Regex("\\{([\\w, ]+)}$").find(filter)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.forEach { k ->
                assertTrue("«$what»: no record has `$k`", out.any { (it as? Map<*, *>)?.get(k) != null })
            }
        }
        val writers = jq(PlaywrightRenderer.LOOKUPS.last().second.replace("VAR", "assessmentOutcome"), graph)
        assertTrue(writers.toString(), objs(field(writers.single(), "writes")).any { it["model"] == "form:claimAssessmentForm" })
        // and with the real jq, where there is one
        val jqBin = listOf("/usr/bin/jq", "/opt/homebrew/bin/jq", "/usr/local/bin/jq").firstOrNull { File(it).canExecute() }
        if (jqBin != null) {
            val file = Files.createTempFile("demo", ".graph.json").toFile().apply { writeText(graphText); deleteOnExit() }
            val p = ProcessBuilder(jqBin, PlaywrightRenderer.LOOKUPS.last().second.replace("VAR", "assessmentOutcome"), file.path)
                .redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertEquals(text, 0, p.exitValue())
            assertTrue(text, text.contains("form:claimAssessmentForm"))
        }
    }

    /** The jq subset §10 uses: `.a.b[]`, `select(.k=="v")`, `{a, b}`, joined by `|`. */
    private fun jq(filter: String, input: Any?): List<Any?> {
        var cur = listOf(input)
        for (stage in filter.split("|").map { it.trim() }) {
            cur = cur.flatMap { v ->
                val select = Regex("^select\\(\\.(\\w+)==\"([^\"]*)\"\\)$").find(stage)
                val build = Regex("^\\{([\\w, ]+)}$").find(stage)
                when {
                    select != null -> if ((v as? Map<*, *>)?.get(select.groupValues[1]) == select.groupValues[2]) listOf(v) else emptyList()
                    build != null -> listOf(build.groupValues[1].split(",").map { it.trim() }.associateWith { (v as Map<*, *>)[it] })
                    stage.startsWith(".") -> {
                        var xs = listOf(v)
                        for (seg in stage.removePrefix(".").split(".")) {
                            val key = seg.removeSuffix("[]")
                            xs = xs.map { x -> (x as? Map<*, *> ?: error("«$filter»: .$key of a non-object")).get(key) }
                            if (seg.endsWith("[]")) xs = xs.flatMap { x -> x as? List<*> ?: error("«$filter»: $key[] of a non-array") }
                        }
                        xs
                    }
                    else -> error("not in the tested jq subset: $stage")
                }
            }
        }
        return cur
    }

    private fun field(v: Any?, key: String): Any? = (v as? Map<*, *>)?.get(key)

    @Suppress("UNCHECKED_CAST")
    private fun objs(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

    @Test
    fun anOutputDirectoryOutsideTheProjectIsSpelledOutInFull() {
        val out = Files.createTempDirectory("atlas-out").toFile()
        val text = try {
            PlaywrightRenderer.render(demoResult, demoDir, PlaywrightRenderer.Layout(out, graph = true, map = true))
        } finally {
            out.deleteRecursively()
        }
        val jq = text.lines().first { it.startsWith("jq '.cases[]") }
        val shown = PlaywrightRenderer.shellArg(ClaudeRenderer.shownDir(out) + "/flowable-demo.graph.json")
        assertTrue(jq, jq.endsWith(" $shown"))
        val inside = PlaywrightRenderer.render(demoResult, demoDir, PlaywrightRenderer.Layout(File(demoDir, "atlas-out"), graph = true))
        assertTrue(inside.lines().any { it.endsWith(" atlas-out/flowable-demo.graph.json") })
        assertTrue("from the project root", inside.contains("Run these from the project root"))
    }

    @Test
    fun withoutTheGraphTheHintWritesItWhereTheQueriesReadIt() {
        // the plugin with *Playwright guide* ticked and *Graph JSON* not: both name atlas-out/…
        val text = PlaywrightRenderer.render(demoResult, demoDir,
            PlaywrightRenderer.Layout(File(demoDir, "atlas-out"), graph = false, map = true))
        val hint = text.lines().single { it.contains("not written beside this file") }
        assertTrue(hint, hint.contains("--json -o atlas-out/flowable-demo.graph.json`"))
        assertTrue(text.lines().filter { it.startsWith("jq '") }.all { it.endsWith(" atlas-out/flowable-demo.graph.json") })
        // alone (`--playwright`), the bare name for both
        val alone = PlaywrightRenderer.render(demoResult, demoDir, PlaywrightRenderer.Layout(graph = false))
        assertTrue(alone, alone.contains("--json -o flowable-demo.graph.json`"))
    }

    @Test
    fun aPathWithASpaceIsOneShellWord() {
        assertEquals("atlas-out/p.graph.json", PlaywrightRenderer.shellArg("atlas-out/p.graph.json"))
        assertEquals("~/atlas/p.graph.json", PlaywrightRenderer.shellArg("~/atlas/p.graph.json"))
        assertEquals("\"out dir/p.graph.json\"", PlaywrightRenderer.shellArg("out dir/p.graph.json"))
        assertEquals("\"\$HOME/my out/p.graph.json\"", PlaywrightRenderer.shellArg("~/my out/p.graph.json"))
        assertEquals("\"a\\\$b/p.graph.json\"", PlaywrightRenderer.shellArg("a\$b/p.graph.json"))
        val text = PlaywrightRenderer.render(demoResult, demoDir, PlaywrightRenderer.Layout(File(demoDir, "out dir"), graph = true))
        assertTrue(text.lines().filter { it.startsWith("jq '") }.all { it.endsWith(" \"out dir/flowable-demo.graph.json\"") })
    }

    @Test
    fun theHomeDirectoryIsMaskedAsAPathNotAsAString() {
        val home = File(System.getProperty("user.home"))
        assertEquals("~/atlas-out", ClaudeRenderer.shownDir(File(home, "atlas-out")))
        assertEquals("~", ClaudeRenderer.shownDir(home))
        // a sibling that merely shares the prefix is not under home
        val sibling = File(home.absoluteFile.parentFile, home.name + "-other")
        assertEquals(sibling.absolutePath.replace(File.separatorChar, '/'), ClaudeRenderer.shownDir(sibling))
    }

    @Test
    fun theProjectNamedDotIsTheDirectorysName() {
        val text = PlaywrightRenderer.render(result(), File("."))
        assertFalse(text, text.contains("..graph.json") || text.contains("— `.`"))
    }

    // ---- shapes the demo does not have ---------------------------------------------------------------
    // Hand-built maps, so each edge case stays small. The keys they use are the parser's: WorkUiFactsTest
    // and CmmnRuntimeFactsTest render the guide from parsed models with the same shapes, so renaming a key
    // on one side fails there instead of silently dropping a ⚠ line here.

    private fun result(
        cases: List<Map<String, Any?>> = emptyList(), forms: List<Map<String, Any?>> = emptyList(),
        processes: List<Map<String, Any?>> = emptyList(), apps: List<Map<String, Any?>> = emptyList(),
        edges: List<Map<String, Any?>> = emptyList(), nodes: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> = mapOf("stats" to emptyMap<String, Any?>(), "cases" to cases, "forms" to forms,
        "processes" to processes, "apps" to apps, "graph" to mapOf("nodes" to nodes, "edges" to edges))

    private fun render(case: Map<String, Any?>, forms: List<Map<String, Any?>> = emptyList()): String =
        PlaywrightRenderer.render(result(listOf(case), forms), File("proj"))

    private fun stage(id: String, order: String? = null, include: String? = null, vararg children: Map<String, Any?>) =
        linkedMapOf<String, Any?>("id" to id, "name" to id, "type" to "stage", "children" to children.toList(),
            "criteria" to emptyList<Any?>(), "rules" to emptyMap<String, Any?>(), "planItemId" to "pi$id").apply {
            order?.let { put("displayOrder", it) }
            include?.let { put("includeInStageOverview", it) }
        }

    private fun task(id: String, name: String = id, vararg extra: Pair<String, Any?>) = linkedMapOf<String, Any?>(
        "id" to id, "name" to name, "type" to "humanTask", "rules" to emptyMap<String, Any?>(), "planItemId" to "pi$id",
    ).apply { putAll(extra) }

    private fun case(
        vararg children: Map<String, Any?>,
        milestones: List<Map<String, Any?>> = emptyList(), sentries: List<Map<String, Any?>> = emptyList(),
        criteria: List<Map<String, Any?>> = emptyList(), key: String = "c", file: String? = null,
        extra: Map<String, Any?> = emptyMap(), plan: Map<String, Any?> = emptyMap(),
    ) = linkedMapOf<String, Any?>(
        "key" to key, "name" to "C",
        "planModel" to mapOf("id" to "plan", "type" to "casePlanModel", "children" to children.toList(), "criteria" to criteria) + plan,
        "sentries" to sentries, "milestones" to milestones,
    ).apply { file?.let { put("file", it) }; putAll(extra) }

    private fun entry(target: String, sentry: String, type: String = "entryCriterion", vararg extra: Pair<String, Any?>) =
        linkedMapOf<String, Any?>("id" to "cr$sentry", "planItemDef" to target, "planItemId" to "pi$target", "type" to type,
            "sentryRef" to sentry).apply { putAll(extra) }

    private fun sentry(id: String, condition: String? = null, vararg onParts: Pair<String, String>, mode: String? = null) =
        linkedMapOf<String, Any?>("id" to id, "condition" to condition, "onParts" to onParts.map { it.first },
            "events" to onParts.map { mapOf("source" to it.first, "event" to it.second) }).apply { mode?.let { put("triggerMode", it) } }

    @Test
    fun stagesWithoutADisplayOrderComeFirstAndOrderedOnesSortNumerically() {
        val out = render(case(stage("Late", "20"), stage("Early", "3"), stage("Unordered")))
        assertTrue(out, out.contains("**Stage bar:** Step 1 *Unordered* · Step 2 *Early* · Step 3 *Late*"))
        assertTrue(out, out.contains("**Step 2 of 3 — Early**"))
    }

    @Test
    fun entriesThatShareADisplayOrderAreReorderedAtRuntimeAndTheFileSaysSo() {
        val tied = render(case(stage("A"), stage("B"), stage("C", "1")))
        assertTrue(tied, tied.contains("⚠ *A*, *B* share a `displayOrder` (or have none): at runtime Work orders them by when they ended"))
        assertTrue("the index marks the bar", tied.contains("| A · B · C ⚠ |"))
        val distinct = render(case(stage("A", "1"), stage("B", "2"))).substringAfter("## 9.")
        assertFalse(distinct, distinct.contains("share a `displayOrder`"))
    }

    @Test
    fun milestonesCountInTheBarUnlessTheModelKeepsThemOut() {
        val ms = mapOf("id" to "ms", "name" to "Reached", "type" to "milestone", "rules" to emptyMap<String, Any?>())
        val out = render(case(stage("A", "1"), ms, stage("B", "3"),
            milestones = listOf(mapOf("id" to "ms", "name" to "Reached", "displayOrder" to "2"))))
        assertTrue(out, out.contains("Step 1 *A* · Step 2 *Reached* (milestone) · Step 3 *B*"))
    }

    @Test
    fun anIncludeExpressionIsShownAsAConditionNotDecided() {
        val out = render(case(stage("A", "1"), stage("B", "2", "\${showB}")))
        assertTrue(out, out.contains("Step 2 *B* (only if `\${showB}`)"))
        assertTrue(out, out.contains("the later steps then move up"))
    }

    @Test
    fun aSingleStageMeansNoStageBar() {
        val out = render(case(stage("Only", "1")))
        assertTrue(out, out.contains("No stage bar: Work draws it only for two or more stages and milestones."))
        assertFalse(out, out.contains("**Stage bar:** Step"))
    }

    @Test
    fun stageNamesThatCarryTheirOwnNumbersAreFlagged() {
        val out = render(case(stage("0. Setup", "0"), stage("1. Review", "1")))
        assertTrue(out, out.contains("Stage names carry numbers of their own"))
        assertTrue("the index gives each its step and marks the trap", out.contains("| Step 1 *0. Setup* · Step 2 *1. Review* ⚠ |"))
        val fine = render(case(stage("1. Setup", "0"), stage("2. Review", "1")))
        assertFalse(fine, fine.contains("Stage names carry numbers of their own"))
        assertTrue(fine, fine.contains("| 1. Setup · 2. Review |"))
    }

    @Test
    fun sentriesReadOutEmptyOnEventAndExitKinds() {
        val c = case(
            stage("A", "1", null, task("t", "Assess")), stage("B", "2"), stage("C", "3"),
            criteria = listOf(entry("B", "sB"), entry("C", "sEmpty"), entry("A", "sForce", "exitCriterion", "exitEventType" to "forceComplete")),
            sentries = listOf(
                sentry("sB", "\${ok}", "pit" to "complete", mode = "onEvent"),
                sentry("sEmpty"),
                sentry("sForce", "\${skip}"),
            ),
            plan = mapOf("exitCriteria" to listOf(mapOf("id" to "x", "type" to "exitCriterion", "sentryRef" to "sB"))),
        )
        val out = render(c)
        assertTrue(out, out.contains("**Step 2 of 3 — B** — starts when: *Assess* completes **and** `\${ok}` (onEvent)"))
        assertTrue(out, out.contains("**Step 3 of 3 — C** — starts as soon as the case starts (an empty entry criterion fires at once)"))
        assertTrue(out, out.contains("- completes when: `\${skip}` (forceComplete: it ends as *completed*"))
        assertTrue(out, out.contains("**The whole case ends** (terminated, with every open stage and task) when: *Assess* completes"))
    }

    @Test
    fun aRequiredFieldThatCanBeHiddenIsATrapOnlyWhenItStaysEnabled() {
        val form = mapOf("key" to "f", "name" to "F", "file" to "forms/f.form",
            "containers" to mapOf(
                "details" to mapOf("id" to "details", "type" to "panel", "visible" to "{{show}}"),
                "p2" to mapOf("id" to "p2", "type" to "panel", "visible" to "{{y}}", "ignore" to "{{!y}}"),
            ),
            "fields" to listOf(
            mapOf("id" to "trap", "type" to "text", "required" to true, "settings" to mapOf("visible" to "{{x}}")),
            mapOf("id" to "fine", "type" to "text", "required" to "{{x}}", "settings" to mapOf("visible" to "{{x}}")),
            mapOf("id" to "paired", "type" to "text", "required" to true, "settings" to mapOf("visible" to "{{x}}", "enabled" to "{{ x }}")),
            mapOf("id" to "locked", "type" to "text", "required" to true, "settings" to mapOf("enabled" to false)),
            mapOf("id" to "inPanel", "type" to "text", "required" to true, "containerGates" to listOf("details")),
            mapOf("id" to "ignored", "type" to "text", "required" to true, "containerGates" to listOf("p2")),
        ))
        val out = render(case(task("t", "T", "formKey" to "f")), listOf(form))
        val line = out.lines().single { it.contains("required but hidden at times") }
        assertTrue(line, line.contains("`trap`") && line.contains("`inPanel` (with `panel` `details`, visible if `{{show}}`)"))
        assertFalse(line, line.contains("`fine`") || line.contains("`paired`") || line.contains("`ignored`"))
        val required = out.lines().single { it.contains("  - required: ") }
        assertFalse("a disabled field blocks nothing", required.contains("`locked`"))
    }

    @Test
    fun outcomesSayWhatGatesThemAndWhatIsNotAnOutcome() {
        val form = mapOf("key" to "f", "file" to "forms/f.json", "fields" to listOf(
            mapOf("id" to "agree", "type" to "boolean", "label" to "I agree", "value" to "{{agreed}}"),
            // a helper nobody sees: it matters only because an outcome reads what it fills
            mapOf("id" to "loadCount", "type" to "restButton", "value" to "{{\$temp.count}}", "stores" to "{{\$temp.count}}",
                "settings" to mapOf("visible" to false, "autoExecute" to true, "enabled" to "{{agreed}}")),
        ), "outcomes" to listOf(
            mapOf("value" to "counted", "label" to "Counted", "visible" to "{{\$temp.count > 0}}"),
            mapOf("value" to "go", "label" to "Go", "enabled" to "{{agreed}}", "navigationUrl" to "#/demo/inbox"),
            mapOf("value" to "anyway", "label" to "Anyway", "enabled" to true),
            mapOf("value" to "skip", "label" to "Skip", "ignoreValidation" to true),
            mapOf("value" to "__SAVE", "label" to "Save"),
        ))
        val out = render(case(task("t", "T", "formKey" to "f")), listOf(form))
        assertTrue(out, out.contains("*Go* `go` (enabled only when `{{agreed}}` ← field *I agree* `agree`, not by form validity; then opens `#/demo/inbox`)"))
        assertTrue(out, out.contains("*Anyway* `anyway` (always enabled, even on an invalid form)"))
        assertTrue(out, out.contains("*Skip* `skip` (works on an invalid form)"))
        assertTrue(out, out.contains("(its *Save* stores a draft without completing)"))
        assertFalse("a reserved value is not an outcome", out.contains("`__SAVE`"))
        assertTrue("the form names no variable: the default one", out.contains("outcomes → `form_f_outcome`"))
        assertTrue(out, out.contains("runs by itself (hidden): `loadCount` → fills `\$temp.count`"))
        assertFalse("a hidden button is no conditional button", out.contains("conditional buttons"))
        assertFalse("a Design export carries no version: nothing to say", out.contains("outcomes stay enabled"))
    }

    @Test
    fun typedListenersAreWaitsNotAutomaticStepsAndReactivationIsTheReopenButton() {
        val c = case(
            stage("A", "1", null,
                mapOf("id" to "v", "name" to "Docs arrived", "type" to "eventListener", "listenerType" to "variable",
                    "variableName" to "docsReceived", "variableChangeType" to "create", "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "s", "name" to "Fraud", "type" to "eventListener", "listenerType" to "signal", "signalRef" to "fraud",
                    "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "e", "name" to "Mail in", "type" to "eventListener", "eventType" to "DEMO-mail", "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "p", "name" to "Documents", "type" to "task", "taskType" to "casePage", "formKey" to "docs",
                    "rules" to emptyMap<String, Any?>()),
            ),
            mapOf("id" to "r", "name" to "Reopen claim", "type" to "eventListener", "listenerType" to "reactivate",
                "startFormKey" to "reopenForm", "actionGroups" to "claimManagers", "rules" to emptyMap<String, Any?>()),
            extra = mapOf("startFormKey" to "intakeForm"),
        )
        val out = render(c).substringAfter("## 9.")
        assertFalse(out, out.contains("automatic step"))
        assertTrue(out, out.contains("variable listener *Docs arrived* — fires when case variable `docsReceived` is created"))
        assertTrue(out, out.contains("signal listener *Fraud* — waits for signal `fraud`"))
        assertTrue(out, out.contains("event listener *Mail in* — waits for inbound event `DEMO-mail`"))
        assertTrue(out, out.contains("case page *Documents* — an entry in the case view's navigation (**Navigate to case view** in the case header), not a task"))
        assertTrue(out, out.contains("Reopen: once the case has completed or been terminated, Work offers **Reopen claim**"))
        assertTrue(out, out.contains("→ form `intakeForm` →") && !out.contains("form `reopenForm` →"))
    }

    @Test
    fun aManualItemWithAStartFormOpensItAndCriteriaBelongToTheirPlanItem() {
        // two plan items of one definition, each with its own entry criterion; a fragment is transparent
        val a = task("d", "Check", "planItemId" to "piA", "startFormKey" to "checkForm", "actionName" to "Start check",
            "rules" to mapOf("manualActivationRule" to true))
        val b = task("d", "Check", "planItemId" to "piB")
        val fragment = mapOf("id" to "frag", "type" to "planFragment", "children" to listOf(task("inner", "Inner", "formKey" to "innerForm")),
            "rules" to emptyMap<String, Any?>())
        val c = case(stage("S", "1", null, a, b, fragment), stage("T", "2"),
            criteria = listOf(
                mapOf("id" to "c1", "planItemDef" to "d", "planItemId" to "piA", "type" to "entryCriterion", "sentryRef" to "s1"),
                mapOf("id" to "c2", "planItemDef" to "d", "planItemId" to "piB", "type" to "entryCriterion", "sentryRef" to "s2"),
            ),
            sentries = listOf(sentry("s1", "\${one}"), sentry("s2", "\${two}")),
            extra = mapOf("startFormKey" to "caseForm"),
        )
        val out = render(c).substringAfter("## 9.")
        val lines = out.lines().filter { it.startsWith("- task *Check*") }
        assertEquals(out, 2, lines.size)
        assertTrue(lines[0], lines[0].contains("action button **Start check**, opens form `checkForm` first") &&
            lines[0].contains("offered (enabled) when: `\${one}`") && !lines[0].contains("two"))
        assertTrue(lines[1], lines[1].contains("starts when: `\${two}`") && !lines[1].contains("one}"))
        assertTrue("the fragment's task is in its stage", out.contains("- task *Inner* — form `innerForm`"))
        assertTrue("the item's form is not the case's", out.contains("→ form `caseForm` →") && !out.contains("→ form `checkForm` →"))
    }

    @Test
    fun automaticStepsNothingWaitsForAreCountedNotListed() {
        val auto = { id: String -> mapOf("id" to id, "name" to "Audit", "type" to "serviceTask", "rules" to emptyMap<String, Any?>(), "planItemId" to "pi$id") }
        val out = render(case(stage("A", "1", null, auto("a1"), auto("a2"), task("t", "Work")), stage("B", "2")))
        assertTrue(out, out.contains("- 2 automatic steps nothing waits for (*Audit* ×2)"))
    }

    @Test
    fun repeatedNamesAreQualifiedWhereTheyAreReferenced() {
        val c = case(
            stage("A", "1", null, task("i1", "Init")), stage("B", "2", null, task("i2", "Init"), task("x", "Next")),
            criteria = listOf(entry("x", "sx")),
            sentries = listOf(sentry("sx", null, "pii1" to "complete")),
        )
        val out = render(c)
        assertTrue(out, out.contains("task *Next* — no form (a plain *Complete*) · assigned at runtime · starts when: *Init* (in *A*) completes"))
    }

    @Test
    fun modelsInATestSourceSetAreNotMapped() {
        val r = result(cases = listOf(
            case(stage("A"), key = "testCase", file = "src/test/resources/cases/test.cmmn"),
            case(stage("A"), key = "realCase", file = "src/main/resources/app.bar!real.cmmn"),
        ))
        val out = PlaywrightRenderer.render(r, File("proj"))
        assertFalse(out, out.contains("#### Case `testCase`") || out.contains("definitionKey=testCase"))
        assertTrue(out, out.contains("#### Case `realCase`"))
        assertTrue(out, out.contains("_Only in a test source set (deployed by unit tests, never with the app, so Work does not offer them): `testCase`"))
    }

    @Test
    fun namesFromModelsCannotStartMarkdownOfTheirOwn() {
        val c = case(stage("A", "1", null, task("t", "Check\n## injected\n- item")), stage("B", "2"))
        val form = mapOf("key" to "f", "fields" to listOf(mapOf("id" to "a`b", "type" to "text", "label" to "Pick *one*", "required" to true)))
        val out = render(c, listOf(form)) +
            PlaywrightRenderer.render(result(listOf(case(stage("A", "1", null, task("t", "T", "formKey" to "f")), stage("B", "2"))), listOf(form)), File("proj"))
        assertFalse(out, out.lines().any { it.startsWith("## injected") || it.startsWith("- item") })
        assertTrue(out, out.contains("task *Check ## injected - item*"))
        assertTrue(out, out.contains("*Pick \\*one\\** `` a`b ``"))
        assertEquals("`` a`b ``", PlaywrightRenderer.code("a`b"))
    }

    @Test
    fun aProcessListsItsTasksInFlowOrderWithTheirWaysIn() {
        val p = mapOf(
            "key" to "claimProcess", "name" to "Claim process", "candidateStarterUsers" to "kermit",
            "userTasks" to listOf(
                mapOf("id" to "review", "name" to "Review"),
                mapOf("id" to "draft", "name" to "Draft", "candidateUsers" to "gonzo"),
                mapOf("id" to "each", "name" to "Each"),
                mapOf("id" to "late", "name" to "Escalate", "parent" to "esc"),
            ),
            "subProcesses" to listOf(mapOf("id" to "esc", "name" to "Escalation", "eventSubProcess" to true)),
            "multiInstance" to listOf(mapOf("activity" to "each", "collection" to "\${items}", "sequential" to "false")),
            "gateways" to listOf(mapOf("id" to "gw", "name" to "Again?", "type" to "exclusiveGateway")),
            "events" to listOf(mapOf("id" to "start", "type" to "startEvent"), mapOf("id" to "escStart", "type" to "startEvent", "def" to "timer", "value" to "P1D")),
            "flows" to listOf(
                mapOf("from" to "start", "to" to "draft"), mapOf("from" to "draft", "to" to "review"),
                mapOf("from" to "review", "to" to "gw"), mapOf("from" to "gw", "to" to "draft", "condition" to "\${again}"),
                mapOf("from" to "gw", "to" to "each", "default" to true), mapOf("from" to "escStart", "to" to "late"),
            ),
        )
        val out = PlaywrightRenderer.render(result(processes = listOf(p)), File("proj"))
        assertTrue(out, out.contains("1. *Draft* — no form (a plain *Complete*) · users `gonzo` · reached at the start · or after *Again?* if `\${again}`"))
        assertTrue(out, out.contains("2. *Review* — no form (a plain *Complete*) · assigned at runtime\n"))
        assertTrue(out, out.contains("3. *Each* — no form (a plain *Complete*) · assigned at runtime · multi-instance (parallel): one task per element of `\${items}`"))
        assertTrue(out, out.contains("- *Escalate* — in *Escalation*, starts on timer `P1D`"))
        assertFalse("an event sub-process task is not a step of the flow", out.contains(". *Escalate*"))
        assertTrue(out, out.contains("| **Claim process** `claimProcess` | process | `#/work?create=true&definitionKey=claimProcess` | 4 user tasks | users `kermit` |"))
    }

    /**
     * The walk back from a task stopped at the first start event — inside an embedded sub-process, its own.
     * The task inside was listed as unconditional, and the guard in front of the sub-process went to the
     * task after it, which the walk reached through the sub-process as if it were an automatic step.
     */
    @Test
    fun aTaskInAConditionalSubProcessCarriesItsGuard() {
        val p = mapOf(
            "key" to "DEMO-review",
            "userTasks" to listOf(
                mapOf("id" to "inner", "name" to "Review the request", "candidateGroups" to "DEMO-reviewers", "parent" to "sub"),
                mapOf("id" to "archive", "name" to "Archive", "candidateGroups" to "DEMO-team"),
            ),
            "subProcesses" to listOf(mapOf("id" to "sub", "name" to "Review", "type" to "subProcess", "eventSubProcess" to false)),
            "gateways" to listOf(mapOf("id" to "gw", "name" to "Needs review?", "type" to "exclusiveGateway")),
            "events" to listOf(
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "subStart", "type" to "startEvent", "parent" to "sub"),
                mapOf("id" to "subEnd", "type" to "endEvent", "parent" to "sub"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(
                mapOf("from" to "start", "to" to "gw"),
                mapOf("from" to "gw", "to" to "sub", "condition" to "\${needsReview == true}"),
                mapOf("from" to "gw", "to" to "archive", "default" to true),
                mapOf("from" to "subStart", "to" to "inner"), mapOf("from" to "inner", "to" to "subEnd"),
                mapOf("from" to "sub", "to" to "archive"), mapOf("from" to "archive", "to" to "end"),
            ),
        )
        val out = PlaywrightRenderer.render(result(processes = listOf(p)), File("proj"))
        val inner = out.lines().single { it.startsWith("1. *Review the request*") }
        assertTrue(inner, inner.contains("reached after *Needs review?* if `\${needsReview == true}`"))
        val archive = out.lines().single { it.startsWith("2. *Archive*") }
        assertTrue(archive, archive.contains("after *Needs review?* otherwise (default flow) · or after sub-process *Review*"))
        assertFalse(archive, archive.contains("needsReview"))
        assertFalse("the guard is used, not an other branch", out.contains("Other branches"))
    }

    @Test
    fun aTaskBehindACatchEventWaitsForItsEventNotTheStart() {
        // start → event-based gateway → signal catch → service task → task; or → timer catch → task
        val p = mapOf(
            "key" to "DEMO-waiting", "candidateStarterGroups" to "DEMO-starters",
            "userTasks" to listOf(mapOf("id" to "handle", "name" to "Handle rejection", "candidateGroups" to "DEMO-team")),
            "serviceTasks" to listOf(mapOf("id" to "log", "name" to "Log it")),
            "gateways" to listOf(mapOf("id" to "ebg", "type" to "eventBasedGateway")),
            "events" to listOf(
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "sig", "type" to "intermediateCatchEvent", "name" to "Rejected", "def" to "signal", "value" to "DEMO-signal"),
                mapOf("id" to "tmr", "type" to "intermediateCatchEvent", "name" to "No answer", "def" to "timer", "value" to "P3D"),
            ),
            "flows" to listOf(
                mapOf("from" to "start", "to" to "ebg"), mapOf("from" to "ebg", "to" to "sig"), mapOf("from" to "ebg", "to" to "tmr"),
                mapOf("from" to "sig", "to" to "log"), mapOf("from" to "log", "to" to "handle"), mapOf("from" to "tmr", "to" to "handle"),
            ),
        )
        val out = PlaywrightRenderer.render(result(processes = listOf(p)), File("proj"))
        val line = out.lines().single { it.startsWith("1. *Handle rejection*") }
        assertTrue(line, line.contains("after signal `DEMO-signal` arrives (*Rejected*) — thrown elsewhere, nothing to click"))
        assertTrue(line, line.contains("after timer `P3D` (*No answer*) — wait for it, never trigger it"))
        assertFalse(line, line.contains("at the start"))
    }

    @Test
    fun aCustomCaseViewIsWhereItsCasePagesAre() {
        val c = case(stage("A", "1", null,
            mapOf("id" to "p", "name" to "Documents", "type" to "task", "taskType" to "casePage", "rules" to emptyMap<String, Any?>())),
            stage("B", "2"), extra = mapOf("customViewKey" to "DEMO-view", "customViewGroups" to "DEMO-team"))
        val out = render(c)
        assertTrue(out, out.contains("Has a custom case view (`DEMO-view`): the case header links **Navigate to case view** " +
            "(for groups `DEMO-team`); the case pages and the case's own navigation are there"))
        assertTrue(out, out.contains("case page *Documents* — an entry in the case view's navigation"))
    }

    @Test
    fun aTaskThatNamesNobodyIsMarkedAndTheLegendSaysWhatToDo() {
        val out = render(case(stage("A", "1", null, task("t", "Review"), task("g", "Approve", "candidateGroups" to "DEMO-team")), stage("B", "2")))
        assertTrue(out, out.contains("- task *Review* — no form (a plain *Complete*) · assigned at runtime"))
        assertTrue(out, out.contains("- task *Approve* — no form (a plain *Complete*) · groups `DEMO-team`"))
        assertTrue(out, out.contains("- *assigned at runtime* after a task: the model names no assignee, group or user"))
        assertTrue(out, out.contains("`platform-api/tasks/<taskId>/candidates` (§3)"))
        val named = render(case(stage("A", "1", null, task("g", "Approve", "assignee" to "\${initiator}")), stage("B", "2")))
        assertFalse("no legend line without such a task", named.contains("*assigned at runtime* after a task"))
    }

    @Test
    fun withNothingDeployedTheHeaderSendsNobodyToTheMap() {
        val r = result(cases = listOf(case(stage("A"), key = "testCase", file = "src/test/resources/cases/test.cmmn")))
        val layout = PlaywrightRenderer.Layout(map = true)
        val main = PlaywrightRenderer.render(r, File("proj"), layout)
        assertFalse(main, main.contains("project map"))
        assertTrue(main, main.contains("To install it as a Claude Code skill, copy it to `.claude/skills/"))
        assertFalse("it knows no stage bars", main.lines()[2].contains("Knows this project's"))
        assertTrue("the stub map stays", PlaywrightRenderer.renderMap(r, File("proj"), layout).contains("nothing to detail"))
        assertTrue("with something deployed it does", demo.lines()[2].contains("Knows this project's stage bars"))
    }

    @Test
    fun theLegendHasNoPlaceholderMarkdownWouldSwallow() {
        val legend = demo.substringAfter("How to read it:").substringBefore("### ")
        // outside code spans, where `<taskId>` is literal text
        assertFalse(legend, Regex("<[A-Za-z]+>").containsMatchIn(legend.replace(Regex("`[^`]*`"), "")))
        assertTrue(legend, legend.contains("**Step N of M — *stage name***"))
    }

    /**
     * A placeholder written as `<name>` outside a code span is an HTML tag to CommonMark, and GitHub's and
     * the IDE's previews drop it: the generic half read "*Create New ?*". The rule held for the legend
     * only; this holds it for the hand-written half and both generated files.
     */
    @Test
    fun noPlaceholderInEitherFileIsMarkdownHtml() {
        val guide = javaClass.classLoader.getResource("agents/playwright-guide.md")!!.readText()
        val layout = PlaywrightRenderer.Layout(map = true)
        val files = mapOf(
            "playwright-guide.md" to guide,
            "playwright.md" to PlaywrightRenderer.render(demoResult, demoDir, layout),
            "playwright-map.md" to PlaywrightRenderer.renderMap(demoResult, demoDir, layout),
        )
        val tag = Regex("<[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>") // `<case name>` too; an autolink has a `:`
        val hits = ArrayList<String>()
        for ((name, text) in files) {
            var fence = false
            for ((i, line) in text.lines().withIndex()) {
                val t = line.trim()
                if (t.startsWith("```") || t.startsWith("~~~")) { fence = !fence; continue }
                if (fence) continue
                tag.find(line.replace(Regex("(`+)[\\s\\S]*?\\1"), ""))?.let { hits.add("$name:${i + 1} «${it.value}»: $line") }
            }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun theStartLinkSaysWhoItWorksFor() {
        assertTrue(demo, demo.contains("it works on every version for a user who may start it (column *May start*)"))
    }

    @Test
    fun flowOrderFollowsTheWalkThroughALoop() {
        // start → gw → draft → review → gw2 —(rework)→ gw. The walk waits on gw, which it reached; review,
        // which it did not, sits first in the file — and must not jump ahead of draft for that.
        val p = mapOf(
            "events" to listOf(mapOf("id" to "start", "type" to "startEvent")),
            "flows" to listOf(
                mapOf("from" to "start", "to" to "gw"), mapOf("from" to "gw", "to" to "draft"),
                mapOf("from" to "draft", "to" to "review"), mapOf("from" to "review", "to" to "gw2"),
                mapOf("from" to "gw2", "to" to "gw"), mapOf("from" to "gw2", "to" to "end"),
            ),
        )
        val rank = OverviewRenderer.flowOrder(p, listOf("review", "draft"))
        assertTrue(rank.toString(), rank.getValue("draft") < rank.getValue("review"))
    }

    @Test
    fun onlyFlowAppsAreSidebarEntriesWithTheirLabelsAndRoutes() {
        val apps = listOf(
            mapOf("key" to "DEMO-claims", "name" to "Claims", "flowApp" to true, "url" to "claims",
                "pages" to listOf(mapOf("key" to "DEMO-inbox", "label" to "Inbox", "url" to "inbox"),
                    mapOf("key" to "DEMO-upload", "label" to "Upload", "access" to "DEMO-admins"))),
            mapOf("key" to "DEMO-hidden", "name" to "Hidden"),
        )
        val out = PlaywrightRenderer.render(result(listOf(case(stage("A"))), apps = apps), File("proj"))
        assertTrue(out, out.contains("- **Claims** (`DEMO-claims`, `#/claims`)\n  - *Inbox* (`#/claims/inbox`)\n" +
            "  - *Upload* (`#/claims/DEMO-upload`; groups `DEMO-admins`)\n"))
        assertTrue(out, out.contains("Deployed without a sidebar entry (`flowApp` off): **Hidden** (`DEMO-hidden`)"))
    }

    @Test
    fun longGroupListsInTheSidebarAreCutAndASharedOneIsSaidOnce() {
        val many = (1..30).joinToString(",") { "DEMO-g$it" }
        val apps = listOf(mapOf("key" to "DEMO-pods", "name" to "Pods", "flowApp" to true, "groupsAccess" to many,
            "pages" to listOf(mapOf("key" to "DEMO-cases", "label" to "Cases", "access" to many),
                mapOf("key" to "DEMO-tasks", "label" to "Tasks", "access" to many))))
        val out = PlaywrightRenderer.render(result(listOf(case(stage("A"))), apps = apps), File("proj"))
        val cut = "`DEMO-g1`, `DEMO-g2`, `DEMO-g3`, `DEMO-g4` +26 more"
        assertTrue(out, out.contains("- **Pods** (`DEMO-pods`, `#/DEMO-pods`) — open to groups $cut · every page for groups $cut\n" +
            "  - *Cases* (`#/DEMO-pods/DEMO-cases`)\n  - *Tasks* (`#/DEMO-pods/DEMO-tasks`)\n"))
        assertFalse(out, out.contains("DEMO-g5`"))
    }

    @Test
    fun aRuntimeFormKeyIsNotCalledMissing() {
        val out = render(case(task("t", "T", "formKey" to "\${formKey}")))
        assertTrue(out, out.contains("chosen at runtime by this expression"))
        assertFalse(out, out.contains("broken reference"))
    }

    // ---- small pieces ----------------------------------------------------------------------------

    @Test
    fun theBuildVersionPicksAColumnOfTheVersionNotesByItsLabel() {
        assertEquals("3.14 and older", PlaywrightRenderer.workLine("3.11.3"))
        assertEquals("2025.1 and 3.15–3.17", PlaywrightRenderer.workLine("3.15.2"))
        assertEquals("2025.1 and 3.15–3.17", PlaywrightRenderer.workLine("2025.1.01.RC1"))
        assertEquals("2025.2", PlaywrightRenderer.workLine("2025.2.08"))
        assertEquals("2026.1 and newer", PlaywrightRenderer.workLine("2026.1.4"))
        assertNull("an engine version names no Work release", PlaywrightRenderer.workLine("7.1.0"))
        assertTrue(PlaywrightRenderer.VERSION_COLUMNS.containsAll(listOf("3.11.3", "3.17.1", "2025.2.1", "2027.1.0")
            .map { PlaywrightRenderer.workLine(it) }))
        assertTrue(PlaywrightRenderer.versionNote("3.11.3").contains("use the **3.14 and older** column of §8"))
        assertTrue(PlaywrightRenderer.versionNote(null).contains("not found in the build files"))
    }

    /** The version note names a §8 column by its label: renaming one in the guide must fail here, not mislead. */
    @Test
    fun theVersionNoteNamesColumnsTheGuideHas() {
        val s8 = demo.substringAfter("## 8. Version notes").substringBefore("## 9.")
        val header = "| | " + PlaywrightRenderer.VERSION_COLUMNS.joinToString(" | ") + " |"
        assertTrue("§8's header row is not «$header»:\n$s8", s8.lines().any { it.trim() == header })
    }

    @Test
    fun theDescriptionIsQuotedAndFitsWhateverTheTitles() {
        val titles = (1..200).map { "Case \"$it\": with a colon" }
        val d = PlaywrightRenderer.description("p", titles to emptyList())
        val quoted = PlaywrightRenderer.yamlQuoted(d)
        assertTrue(quoted.length - 2 <= PlaywrightRenderer.DESCRIPTION_MAX)
        assertTrue(quoted, quoted.contains("\\\"1\\\": with a colon") && quoted.contains(", …)"))
    }

    @Test
    fun aLongConditionIsOneLineAndVisiblyCut() {
        val c = PlaywrightRenderer.condition("\${a &&\n   b}")
        assertEquals("\${a && b}", c)
        val long = PlaywrightRenderer.condition("x".repeat(PlaywrightRenderer.CONDITION_SHOWN + 50))
        assertTrue(long, long.endsWith(" …") && long.length <= PlaywrightRenderer.CONDITION_SHOWN + 2)
    }
}
