package com.flowable.atlas.render

import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.parsing.ModelJsonReader
import java.io.File
import java.util.IdentityHashMap

/**
 * `<project>.playwright.md` — what an agent needs to drive this project's **Flowable Work** UI in a
 * browser: how Work behaves under Playwright (generic, §0–§8) and this project's map (§9), read from the
 * models — which cases and processes there are and how to start each, which stages a case has and in what
 * order Work's stage bar shows them, what starts each one, which tasks and buttons sit in them, which form
 * fields are required and what rules they check, and which buttons are conditional and on what.
 *
 * Why a file of its own and not a section of `CLAUDE.md`: a coding task never needs it, and the context
 * file is loaded into every session (see [ClaudeRenderer] — instructions are followed, overviews only
 * cost). `CLAUDE.md` carries one line saying when to read this one. It opens with skill front matter, so
 * the same file also works as a Claude Code skill (`.claude/skills/flowable-work-ui/SKILL.md`), which is
 * loaded only when a task matches its description.
 *
 * A skill body is loaded whole when it triggers, and on a real project §9's details are four times the
 * generic text. So when [Layout.map] says so (`--all`, the launcher, the plugin), §9 keeps an index of the
 * cases and processes, and the details move to `<name>.playwright-map.md` ([renderMap]) — the reference
 * file a skill reads on demand. `--playwright` alone writes one file and keeps them inline.
 *
 * The generic half lives in `agents/playwright-guide.md` on the classpath — prose full of `{{…}}` and
 * `${…}` reads better unescaped. The project half follows one rule: state what the model says, never
 * what the runtime will do with it. A sentry's condition is shown, not evaluated; the deployed version
 * may differ, and the guide tells the agent to trust the running app over this file.
 */
object PlaywrightRenderer {

    /**
     * [outDir] spells sibling paths from the project root (as [ClaudeRenderer.Layout] does); [graph] says
     * whether `<name>.graph.json` is written beside this file, which decides how §10's lookups are phrased;
     * [map] that §9's details go to `<name>.playwright-map.md` ([renderMap]) instead of inline.
     */
    data class Layout(val outDir: File? = null, val graph: Boolean = true, val map: Boolean = false)

    /** The skill name the front matter declares, and the folder the header tells a reader to copy it to. */
    const val SKILL_NAME = "flowable-work-ui"

    /** `<name>.playwright.md`, and the map beside it when [Layout.map] is set. */
    const val SUFFIX = "playwright.md"
    const val MAP_SUFFIX = "playwright-map.md"

    /** What the map is called once copied into the skill folder, beside `SKILL.md`. */
    const val SKILL_MAP_FILE = "project-map.md"

    /**
     * The four columns of the guide's §8, by their exact labels: the version note names one of them, and
     * an agent finds the column by that text. 3.11–3.14 are a column of their own because Work's chrome got
     * its ARIA roles only in 3.15.0.
     */
    val VERSION_COLUMNS = listOf("2026.1 and newer", "2025.2", "2025.1 and 3.15–3.17", "3.14 and older")

    /**
     * Groups named per app or page in the sidebar list. Who may *see* a sidebar entry rarely decides a route,
     * and on a real project those lists are dozens long; who may start or work on something is never cut.
     */
    internal const val GROUPS_LISTED = 4

    /** Conditional (non-button) fields listed per form before a count and a query take their place. */
    internal const val CONDITIONAL_FIELDS_LISTED = 6

    /** How much of one condition §9 prints before cutting it (see [condition]). */
    internal const val CONDITION_SHOWN = 160

    /** Claude Code's limit for a skill's `description`; the project's titles fill what the fixed text leaves. */
    internal const val DESCRIPTION_MAX = 1024

    /** Below this `metadata.flowableDesignVersion` the form runtime does not grey out outcomes on an invalid form. */
    private const val DESIGN_VERSION_GATES_OUTCOMES = 3080

    private val GENERIC: String by lazy {
        PlaywrightRenderer::class.java.getResourceAsStream("/agents/playwright-guide.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("agents/playwright-guide.md is missing from the classpath")
    }

    /** The guide: front matter, header, version note, §0–§8, §9 (index, or index and details) and §10. */
    fun render(result: Map<String, Any?>, root: File, layout: Layout = Layout()): String {
        val at = Where(root, layout)
        val map = ProjectMap(result)
        // With nothing deployed the map holds one line saying so: the header must not send the reader
        // there, nor the description promise stage bars it does not have. (details() is cached.)
        val detailed = map.details().isNotEmpty()
        val split = layout.map && detailed
        val L = ArrayList<String>()
        // Claude Code reads `name` + `description` to decide when to load a skill: the description is
        // therefore the situations it is for, in the words a user types, plus the project's own titles.
        L.add("---")
        L.add("name: $SKILL_NAME")
        L.add("description: " + yamlQuoted(description(at.name, map.titles(), knows = detailed)))
        L.add("---")
        L.add("")
        L.add("# Driving Flowable Work in a browser — ${code(at.name)}\n")
        val mapWhere = "${code(SKILL_MAP_FILE)} beside this file when it is installed as a skill, else " +
            code(at.sibling(MAP_SUFFIX))
        L.add("_Generated by Flowable Atlas from this repository's models. Read §0 and §1 now, then the §9 entry " +
            "of your case or process; look up §2–§8 when a step needs them." +
            (if (split) " §9 is an index: each case's and process's details are in the project map — $mapWhere. " +
                "To install this as a Claude Code skill, copy this file to `.claude/skills/$SKILL_NAME/SKILL.md` and " +
                "the map to `.claude/skills/$SKILL_NAME/$SKILL_MAP_FILE`. Regenerate both when the models change._\n"
            else " To install it as a Claude Code skill, copy it to `.claude/skills/$SKILL_NAME/SKILL.md`. " +
                "Regenerate it when the models change._\n"))
        L.add(versionNote(ClaudeRenderer.flowableVersion(root)))
        L.add("")
        L.add(GENERIC.trimEnd())
        L.add("")
        L.addAll(map.index())
        when {
            !detailed -> {}   // no pointer to a map that holds nothing
            split -> L.add("**The details** — each case's stages with what starts them, its tasks and buttons; " +
                "each process's user tasks; the project's actions; and the forms with their required fields, rules, " +
                "outcomes and conditional buttons — are in the project map: $mapWhere. Search it for the key " +
                "(${code("#### Case `KEY`")} or ${code("#### Process `KEY`")}) or for a name.\n")
            else -> L.addAll(map.details())
        }
        L.addAll(lookups(at, layout.graph))
        return L.joinToString("\n").trimEnd() + "\n"
    }

    /** `<name>.playwright-map.md`: §9's details, for a guide rendered with [Layout.map]. */
    fun renderMap(result: Map<String, Any?>, root: File, layout: Layout = Layout(map = true)): String {
        val at = Where(root, layout)
        val map = ProjectMap(result)
        val L = ArrayList<String>()
        L.add("# Flowable Work project map — ${code(at.name)}\n")
        L.add("_The details behind §9 of ${code(at.sibling(SUFFIX))} (installed as a skill: `SKILL.md` beside " +
            "this file), generated by Flowable Atlas from this repository's models. The notation is explained " +
            "at the top of that §9. Search for a key or a name; regenerate when the models change._\n")
        val details = map.details()
        val contents = map.contents()
        if (contents.isNotEmpty()) L.add("Contents: " + contents.joinToString(" · ") + "\n")
        L.addAll(details.ifEmpty {
            listOf("_No case or process of this repository is deployed with the app — nothing to detail (see §9 of the guide)._")
        })
        return L.joinToString("\n").trimEnd() + "\n"
    }

    /**
     * Which column of the guide's version notes applies. Work's UI changed visibly between the lines —
     * roles, dialogs, what Complete does next — and an agent that follows the wrong column clicks on things
     * that do not exist. The build file is the only place the repository states the version; the running
     * system is the authority, and the guide says how to read it there.
     */
    internal fun versionNote(version: String?): String {
        val column = workLine(version)
        return when {
            version == null ->
                "**Flowable version:** not found in the build files. Read it from the running system before " +
                    "you rely on §8 (§2 *Which Work is this?*); many installations still run 2025.1 or 3.x."
            column == null ->
                "**Flowable version:** `$version` (open-source engine numbering). Flowable Work is the commercial " +
                    "platform; check that this project actually deploys to a Work installation before planning clicks."
            else ->
                "**Flowable version:** this project builds against `$version` → use the **$column** column of §8 " +
                    "wherever §2–§8 differ by version. Confirm on the running system (§2 *Which Work is this?*): " +
                    "the deployed Work can be older or newer than the build."
        }
    }

    /** One of [VERSION_COLUMNS] — or null for an engine-only (6.x–9.x) version. */
    internal fun workLine(version: String?): String? {
        val v = version?.trim() ?: return null
        Regex("^(20\\d\\d)\\.(\\d+)").find(v)?.let { m ->
            val year = m.groupValues[1].toInt()
            val release = m.groupValues[2].toInt()
            return when {
                year >= 2026 -> VERSION_COLUMNS[0]
                year == 2025 && release >= 2 -> VERSION_COLUMNS[1]
                else -> VERSION_COLUMNS[2]
            }
        }
        // The platform's 3.x line is what 2025.1 succeeded. 3.15 brought the ARIA roles 2025.1 has; before
        // it, Work's sidebar, toolbar, title and tabs carry none.
        Regex("^3\\.(\\d+)").find(v)?.let { m ->
            return if (m.groupValues[1].toInt() >= 15) VERSION_COLUMNS[2] else VERSION_COLUMNS[3]
        }
        return null
    }

    /**
     * Third person, what it does and when — Claude Code's skill-authoring rules — and the requests users
     * actually type, since a request that does not resemble the description never loads the skill. The
     * project's case and process titles fill whatever the limit leaves. Without [knows] — nothing of the
     * project's own is deployed — it claims no knowledge of the project.
     */
    internal fun description(name: String, titles: Pair<List<String>, List<String>>, knows: Boolean = true): String {
        val lead = "Guides driving the Flowable Work web UI of $name in a browser (Playwright MCP, a Playwright " +
            "script or another browser tool): signing in, starting a case or process, opening a case or task, " +
            "reading which stage a case is in, claiming and completing tasks and filling in their forms, pressing " +
            "case actions, bringing a case to a given stage, finding the open tasks of a user or group, and " +
            "explaining why a button is missing or greyed out" + (if (knows) ". Knows this project's stage bars, " +
            "tasks, required fields, outcomes and button conditions" else "")
        val tail = ". Use when the user wants something done, checked or explained in a running Flowable Work, " +
            "not for changing the models or the code."
        fun fits(s: String) = yamlQuoted(s).length - 2 <= DESCRIPTION_MAX
        val (cases, processes) = titles
        val parts = ArrayList<Pair<String, MutableList<String>>>()
        if (cases.isNotEmpty()) parts.add("cases" to ArrayList())
        if (processes.isNotEmpty()) parts.add("processes" to ArrayList())
        fun assemble(cut: Boolean): String {
            val shown = parts.filter { it.second.isNotEmpty() }
            if (shown.isEmpty()) return lead + tail
            return lead + " (" + shown.joinToString("; ") { (k, v) -> "$k: " + v.joinToString(", ") } +
                (if (cut) ", …" else "") + ")" + tail
        }
        var cut = false
        loop@ for ((i, list) in listOf(cases, processes).withIndex()) {
            val target = parts.firstOrNull { it.first == (if (i == 0) "cases" else "processes") }?.second ?: continue
            for (t in list) {
                target.add(t)
                if (!fits(assemble(true))) { target.removeAt(target.size - 1); cut = true; break@loop }
            }
        }
        return assemble(cut)
    }

    /** A YAML double-quoted scalar: the only form in which any title is safe (`: `, `#`, quotes). */
    internal fun yamlQuoted(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '\\' -> append("\\\\")
            ch == '"' -> append("\\\"")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
        append('"')
    }

    /** Where the files land, as seen from the project root (where both the guide and the agent run from). */
    private class Where(root: File, layout: Layout) {
        val name = Fmt.artifactName(root)
        private val prefix = ClaudeRenderer.siblingPrefix(root, layout.outDir)

        /**
         * The output directory when it is not inside the project — the launcher's default — `~`-masked
         * ([ClaudeRenderer.shownDir]). Bare names would not resolve from the project root.
         */
        private val outside: String? = run {
            val rootAbs = File(root.path.trimEnd('/').ifEmpty { "." }).absoluteFile.normalize()
            val outAbs = layout.outDir?.absoluteFile?.normalize() ?: return@run null
            if (prefix.isNotEmpty() || outAbs == rootAbs) return@run null
            ClaudeRenderer.shownDir(outAbs)
        }

        fun sibling(suffix: String): String = outside?.let { "$it/$name.$suffix" } ?: "$prefix$name.$suffix"
    }

    /** §10 — how to get the details §9 deliberately leaves out, without reading the graph whole. */
    private fun lookups(at: Where, graph: Boolean): List<String> {
        val L = ArrayList<String>()
        L.add("## 10. Looking up details\n")
        L.add("§9 is the route map. The full form definitions, every flow and every variable are in the Atlas " +
            "graph — query it, never read it whole. Run these from the project root:")
        // One path for the hint and the queries: generated anywhere else, every query below misses it.
        val g = shellArg(at.sibling("graph.json"))
        if (!graph) {
            L.add("_(not written beside this file — generate it with " +
                code("java -jar <atlas-cli>.jar <project-dir> --json -o $g") + ")_")
        }
        L.add("```bash")
        for ((what, filter) in LOOKUPS) {
            L.add("# $what")
            L.add("jq '$filter' $g")
        }
        L.add("```")
        L.add("One model with its context in both directions: `java -jar <atlas-cli>.jar <project-dir> --slice case:<key> --stdout`.")
        L.add("")
        return L
    }

    /**
     * §10's jq filters, by what they answer. Placeholders: `FORM`, `CASE`, `PROCESS`, `VAR`. A test runs each
     * against a real graph, so a change to the graph's shape cannot silently break the one lookup an agent
     * is sent to when a condition blocks its route.
     */
    internal val LOOKUPS: List<Pair<String, String>> = listOf(
        "every field of a form: required, visible / enabled / ignore, and validation rules" to
            ".forms[] | select(.key==\"FORM\") | .fields[] | {id, type, label, required, settings, validation}",
        "a case's plan model: stages, plan items, rules, criteria" to
            ".cases[] | select(.key==\"CASE\") | .planModel",
        "the sentries (entry/exit conditions) of a case" to
            ".cases[] | select(.key==\"CASE\") | .sentries",
        "a process's user tasks and its conditional flows" to
            ".processes[] | select(.key==\"PROCESS\") | {userTasks, conditions}",
        "who writes and who reads a variable (e.g. the one a sentry or gateway checks)" to
            ".graph.nodes[] | select(.id==\"variable:VAR\") | .data | {writes, reads}",
    )

    // ---------------------------------------------------------------------------------------------
    // §9 — the project map
    // ---------------------------------------------------------------------------------------------

    /**
     * §9, computed from the extract result: the index (always in the guide) and the details (inline, or in
     * the map file). Models under a test source set are left out of both — Work never offers them — and
     * named once at the end of the index. One instance per file: it collects the forms its details mention
     * so their requirements follow in one place.
     */
    @Suppress("UNCHECKED_CAST")
    private class ProjectMap(private val result: Map<String, Any?>) {

        private fun deployed(m: Map<String, Any?>) = !ModelPaths.isTestSourceModel(str(m["file"]))

        private val allCases = objs(result["cases"]).filter { str(it["key"]) != null }
        private val allProcesses = objs(result["processes"]).filter { str(it["key"]) != null }
        private val allApps = objs(result["apps"]).filter { str(it["key"]) != null }
        private val allActions = objs(result["actions"]).filter { str(it["key"]) != null }

        private val cases = allCases.filter(::deployed)
        private val processes = allProcesses.filter(::deployed)
        private val apps = allApps.filter(::deployed)
        private val actions = allActions.filter(::deployed)

        /** Keys only a test source set holds — named once, so an agent does not go looking for them. */
        private val testOnly: List<String> = (allCases + allProcesses + allApps + allActions)
            .filterNot(::deployed).mapNotNull { str(it["key"]) }.distinct()
            .filter { k -> (cases + processes + apps + actions).none { str(it["key"]) == k } }

        private val forms: Map<String, Map<String, Any?>> = objs(result["forms"])
            .mapNotNull { f -> str(f["key"])?.let { it to f } }.toMap()

        /** Graph edges by source and by target node id — start forms, callers and page contents are edges. */
        private val edges = objs((result["graph"] as? Map<String, Any?>)?.get("edges"))
        private val edgesFrom: Map<String, List<Map<String, Any?>>> = edges.groupBy { it["s"].toString() }
        private val edgesTo: Map<String, List<Map<String, Any?>>> = edges.groupBy { it["t"].toString() }

        /** `variable:<name>` graph node → the models and elements that write it (for the ← hints). */
        private val writes: Map<String, List<Map<String, Any?>>> =
            objs((result["graph"] as? Map<String, Any?>)?.get("nodes"))
                .filter { str(it["id"])?.startsWith("variable:") == true }
                .associate { n -> str(n["id"])!!.removePrefix("variable:") to objs((n["data"] as? Map<String, Any?>)?.get("writes")) }

        /** Interactive processes: a user task or a start form, i.e. something to click. */
        private fun interactive(p: Map<String, Any?>) =
            objs(p["userTasks"]).isNotEmpty() || startForms("process:${str(p["key"])}").isNotEmpty()

        /** Titles shared by two or more definitions: starting one of them by name is a coin toss. */
        private val sharedTitles: Map<String, List<String>> = (cases + processes)
            .mapNotNull { m -> str(m["name"])?.let { it to str(m["key"])!! } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }

        /** Where each form is shown anywhere in the project — for a ← hint whose writer is not in the case at hand. */
        private val formTasks: Map<String, Set<String>> by lazy {
            val m = LinkedHashMap<String, LinkedHashSet<String>>()
            for (p in processes) for (t in objs(p["userTasks"])) str(t["formKey"])?.let {
                m.getOrPut(it) { LinkedHashSet() }.add("on task ${em(str(t["name"]) ?: str(t["id"]) ?: "?")} of ${code(str(p["key"])!!)}")
            }
            fun walk(c: Map<String, Any?>, n: Map<String, Any?>) {
                for (ch in objs(n["children"])) {
                    if (ch["type"] == "humanTask") str(ch["formKey"])?.let {
                        m.getOrPut(it) { LinkedHashSet() }.add("on task ${em(str(ch["name"]) ?: str(ch["id"]) ?: "?")} of case ${code(str(c["key"])!!)}")
                    }
                    if (ch.containsKey("children")) walk(c, ch)
                }
            }
            for (c in cases) (c["planModel"] as? Map<String, Any?>)?.let { walk(c, it) }
            m
        }

        /** Forms named somewhere in the details, in first-mention order, each with where it is used. */
        private val formsUsed = LinkedHashMap<String, LinkedHashSet<String>>()

        /** The literal values a calling case's in-mappings give a process's variables (`${taskName}` task names). */
        private val callerLiterals: Map<String, List<IoLiteral>> by lazy {
            val out = HashMap<String, MutableList<IoLiteral>>()
            for (c in cases) for (io in objs(c["ioParameters"])) {
                if (str(io["refKind"]) != "process" || str(io["dir"]) != "in") continue
                val target = str(io["target"]) ?: continue
                val source = str(io["source"]) ?: continue
                // only a literal sourceExpression is a value; a plain `source` is a variable copy
                if (io["expression"] != true || source.contains("\${") || source.contains("#{")) continue
                out.getOrPut(str(io["refKey"]) ?: continue) { ArrayList() }
                    .add(IoLiteral(str(io["element"]) ?: "", str(io["elementName"]) ?: str(io["element"]) ?: "?", target, source))
            }
            out
        }

        fun titles(): Pair<List<String>, List<String>> =
            cases.mapNotNull { str(it["name"]) ?: str(it["key"]) }.distinct() to
                processes.filter { interactive(it) && callersOf(str(it["key"])!!).isEmpty() }
                    .mapNotNull { str(it["name"]) ?: str(it["key"]) }.distinct()

        /** Whether a task the details list names no assignee, group or user — the legend then explains the mark. */
        fun anyUnassigned(): Boolean {
            fun walk(n: Map<String, Any?>): Boolean = objs(n["children"]).any { ch ->
                (ch["type"] == "humanTask" && assignment(ch) == null) || (ch.containsKey("children") && walk(ch))
            }
            return cases.any { c -> (c["planModel"] as? Map<String, Any?>)?.let(::walk) == true } ||
                processes.filter(::interactive).any { p -> objs(p["userTasks"]).any { assignment(it) == null } }
        }

        // ---- the index (always in the guide) ---------------------------------------------------------

        fun index(): List<String> {
            val L = ArrayList<String>()
            L.add("## 9. This project — what the models say\n")
            L.add("_Read by Atlas from the models in this repository. Work runs the **deployed** version: when the " +
                "UI disagrees with this section, the running app is right (§1.2). Conditions are shown as written; " +
                "whether one holds is decided at runtime._\n")
            if (cases.isEmpty() && processes.isEmpty()) {
                // Nothing to drive: say so first — the notation below would only describe an empty map.
                L.add(if (testOnly.isNotEmpty()) "_No case or process of this repository is deployed with the app: the " +
                    "only ones are in a test source set (deployed by unit tests, never offered by Work): " +
                    testOnly.joinToString(", ") { code(it) } + ". Work can still be driven with §0–§8; its cases come " +
                    "from elsewhere._\n"
                else "_No case or process was found in this repository — this project has nothing of its own for Work " +
                    "to show; §0–§8 still apply to whatever is deployed._\n")
                apps(L)
                return L
            }
            L.add("How to read it:")
            // No `<stage>` placeholder: Markdown takes it for an HTML tag and shows nothing.
            L.add("- **Step N of M — *stage name*** is the stage's place in Work's stage bar, counted as Work counts it; " +
                "**Stage bar:** lists the bar.")
            L.add("- *starts when:* what starts a stage or item; *offered (enabled) when:* what makes a manual " +
                "item's button appear; *reached when:* a milestone. Alternatives are joined by **or**, the parts " +
                "of one sentry by **and**.")
            L.add("- *(onEvent)* after a sentry: nothing is remembered between evaluations, so its condition must " +
                "already hold when its on-part happens — setting the variable afterwards does not help (§4).")
            L.add("- ← names what sets a variable a condition reads: an outcome of a task, or a field of a form. " +
                "Press or fill that.")
            L.add("- *rules:* a form field's validation — pattern (whole value), range, length, date format, custom check.")
            // Said once here, not per task: on a real project most tasks name nobody, and the silence would
            // otherwise read as "anyone may do it" when a listener or code picks the people at runtime.
            if (anyUnassigned()) {
                L.add("- *$UNASSIGNED* after a task: the model names no assignee, group or user, so who may work on " +
                    "it is decided at runtime (a listener, a rule or code). Once the task exists, read who may with " +
                    "`platform-api/tasks/<taskId>/candidates` (§3); ask the user for those accounts up front (§1.7).")
            }
            L.add("- ⚠ marks a trap: a required field that can be hidden, a stage bar whose order changes as the " +
                "case runs, stage names that carry numbers of their own, two definitions with one title.")
            L.add("")

            val shownProcesses = processes.filter(::interactive)
            if (cases.isNotEmpty() || shownProcesses.isNotEmpty()) {
                L.add("### Cases and processes\n")
                // The link selects the definition only among those the user may start: for anyone else Work
                // falls back to its chooser without a word, so the link and *May start* belong together.
                L.add("Start one by opening the link under Work's base URL (§0); it works on every version for a " +
                    "user who may start it (column *May start*). For anyone else Work opens the chooser instead, " +
                    "or the form of the one definition that user may start: confirm what opened before you submit " +
                    "(§2 *Starting a case or process*).\n")
                L.add("| Case or process | Kind | Start | Stage bar, or user tasks | May start |")
                L.add("|---|---|---|---|---|")
                for (c in cases) {
                    val key = str(c["key"])!!
                    val cx = (c["planModel"] as? Map<String, Any?>)?.let { CaseIndex(c, it) }
                    val bar = cx?.shownBar().orEmpty()
                    // Names that carry numbers of their own get their step in front: "stage 3" read off this
                    // cell must not land on the stage *named* 3.
                    val numbered = cx?.numberedName(bar) != null
                    val barText = when {
                        cx == null -> "—"
                        bar.size < 2 -> "no stage bar"
                        else -> bar.withIndex().joinToString(" · ") { (i, e) ->
                            if (numbered) "Step ${i + 1} ${em(e.name)}" else inline(e.name)
                        } + if (cx.tie(bar).isNotEmpty() || numbered) " ⚠" else ""
                    }
                    L.add(row(title(c), "case", code(startLink(key)), barText, starters(c)))
                }
                for (p in shownProcesses) {
                    val key = str(p["key"])!!
                    val callers = callersOf(key)
                    // A process another model runs belongs to that instance: started on its own it has no context.
                    val start = if (callers.isEmpty()) code(startLink(key))
                        else "sub-item of " + callers.map { it.first }.distinct().take(3).joinToString(", ") { s ->
                            (if (s.startsWith("case:")) "case " else "process ") + code(s.substringAfter(':'))
                        } + " — open it there"
                    val n = objs(p["userTasks"]).size
                    val tasks = when (n) { 0 -> "start form only"; 1 -> "1 user task"; else -> "$n user tasks" }
                    L.add(row(title(p), "process", start, tasks, starters(p)))
                }
                L.add("")
            }
            val automatic = processes.filterNot(::interactive)
            if (automatic.isNotEmpty()) {
                L.add("Processes that run by themselves (no user task, no start form — nothing to click): " +
                    automatic.joinToString(", ") { code(str(it["key"])!!) } + ".\n")
            }
            apps(L)
            if (testOnly.isNotEmpty()) {
                L.add("_Only in a test source set (deployed by unit tests, never with the app, so Work does not " +
                    "offer them): " + testOnly.joinToString(", ") { code(it) } + " — ignore them when driving Work._\n")
            }
            return L
        }

        private fun title(m: Map<String, Any?>): String {
            val key = str(m["key"])!!
            val nm = str(m["name"])
            return (nm?.let { strong(it) + " " } ?: "") + code(key) + (if (nm != null && nm in sharedTitles) " ⚠ shared title" else "")
        }

        /**
         * Work lists a definition in its create dialog for a non-administrator only through a candidate
         * starter link: a definition without one is there for administrators alone.
         */
        private fun starters(m: Map<String, Any?>): String {
            val parts = ArrayList<String>()
            str(m["candidateStarterGroups"])?.let { parts.add("groups ${codes(it)}") }
            str(m["candidateStarterUsers"])?.let { parts.add("users ${codes(it)}") }
            return if (parts.isEmpty()) "administrators only (no candidate starters)" else parts.joinToString(" · ")
        }

        private fun row(vararg cells: String) = "| " + cells.joinToString(" | ") { it.replace("|", "\\|") } + " |"

        // ---- apps & pages ------------------------------------------------------------------------

        /**
         * Only an app the model marks `flowApp` is a sidebar entry; its pages are labelled by their label and
         * routed by their URL segment, neither of which need be the key.
         */
        private fun apps(L: MutableList<String>) {
            if (apps.isEmpty()) return
            val (sidebar, hidden) = apps.partition { it["flowApp"] == true }
            if (sidebar.isNotEmpty()) {
                L.add("### Apps in the sidebar\n")
                L.add("Click the app: its pages appear beneath it (§2). The routes are the app's and the page's URL segments.\n")
                for (app in sidebar.sortedBy { (it["order"] as? Number)?.toInt() ?: Int.MAX_VALUE }) {
                    val key = str(app["key"])!!
                    val seg = str(app["url"]) ?: key
                    val head = (str(app["name"])?.let { strong(it) + " " } ?: "") + "(${code(key)}, ${code("#/$seg")})"
                    val parts = ArrayList<String>()
                    str(app["groupsAccess"])?.let { parts.add("open to groups ${codes(it, GROUPS_LISTED)}") }
                    str(app["usersAccess"])?.let { parts.add("open to users ${codes(it, GROUPS_LISTED)}") }
                    val pageRecs = objs(app["pages"]).filter { str(it["key"]) != null }
                    // One page per line, and a group list every page shares said once on the app: on a real
                    // project the same thirty groups otherwise fill one line per app, thousands of characters.
                    val shared = pageRecs.map { str(it["access"]) }.distinct().singleOrNull()
                        ?.takeIf { pageRecs.size > 1 }
                    shared?.let { parts.add("every page for groups ${codes(it, GROUPS_LISTED)}") }
                    L.add("- $head" + if (parts.isEmpty()) "" else " — " + parts.joinToString(" · "))
                    for (p in pageRecs) {
                        val pk = str(p["key"])!!
                        val label = str(p["label"]) ?: pageLabel(pk)
                        val bits = arrayListOf(code("#/$seg/${str(p["url"]) ?: pk}"))
                        if (shared == null) str(p["access"])?.let { bits.add("groups ${codes(it, GROUPS_LISTED)}") }
                        pageContent(pk)?.let { bits.add(it) }
                        L.add("  - " + (label?.let { em(it) + " " } ?: code(pk) + " ") + "(" + bits.joinToString("; ") + ")")
                    }
                }
                L.add("")
            }
            if (hidden.isNotEmpty()) {
                L.add("Deployed without a sidebar entry (`flowApp` off): " + hidden.joinToString(", ") { a ->
                    (str(a["name"])?.let { strong(it) + " " } ?: "") + "(${code(str(a["key"])!!)})"
                } + if (cases.isEmpty() && processes.isEmpty()) ".\n" else " — reach their cases and processes through the start links above.\n")
            }
        }

        private fun pageLabel(key: String): String? {
            val node = objs((result["graph"] as? Map<String, Any?>)?.get("nodes")).firstOrNull { it["id"] == "page:$key" }
            return str(node?.get("label"))?.takeIf { it != key }
        }

        /** What a page lets a user do from it: start work, list instances, run an action. */
        private fun pageContent(pageKey: String): String? {
            val out = edgesFrom["page:$pageKey"].orEmpty()
            val bits = ArrayList<String>()
            fun keys(rel: String) = out.filter { it["rel"] == rel }.map { it["t"].toString().substringAfter(':') }.distinct()
            keys("starts-case").takeIf { it.isNotEmpty() }?.let { bits.add("starts case " + it.joinToString(", ") { k -> code(k) }) }
            keys("starts-process").takeIf { it.isNotEmpty() }?.let { bits.add("starts process " + it.joinToString(", ") { k -> code(k) }) }
            keys("lists-instances").takeIf { it.isNotEmpty() }?.let { bits.add("lists " + it.joinToString(", ") { k -> code(k) }) }
            keys("triggers-action").takeIf { it.isNotEmpty() }?.let { bits.add("runs action " + it.joinToString(", ") { k -> code(k) }) }
            return bits.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        // ---- the details (inline, or the map file) -----------------------------------------------

        private var rendered: List<String>? = null

        fun details(): List<String> = rendered ?: ArrayList<String>().also { L ->
            if (cases.isNotEmpty()) { L.add("### Cases\n"); for (c in cases) case(c, L) }
            val shown = processes.filter(::interactive)
            if (shown.isNotEmpty()) { L.add("### Processes\n"); for (p in shown) process(p, L) }
            actions(L)
            formDetails(L)
            rendered = L
        }

        fun contents(): List<String> {
            val out = ArrayList<String>()
            if (cases.isNotEmpty()) out.add("cases " + cases.joinToString(", ") { code(str(it["key"])!!) })
            val shown = processes.filter(::interactive)
            if (shown.isNotEmpty()) out.add("processes " + shown.joinToString(", ") { code(str(it["key"])!!) })
            if (actions.isNotEmpty()) out.add("actions")
            if (formsUsed.isNotEmpty()) out.add("forms")
            return out
        }

        // ---- cases --------------------------------------------------------------------------------

        private fun startLink(key: String) = "#/work?create=true&definitionKey=$key"

        /** `start: <link> → form `F` → **Submit**` — what Work opens on the way, and which button creates it. */
        private fun startLine(key: String, title: String?, startForm: String?, usedBy: String): String {
            val then = if (startForm == null) " → **Continue**" else {
                val outs = forms[startForm]?.let { f -> objs(f["outcomes"]).filter { o -> str(o["value"])?.startsWith("__") == false } }
                    .orEmpty().mapNotNull { str(it["label"]) ?: str(it["value"]) }
                " → form ${formRef(startForm, usedBy)} → " +
                    (if (outs.isEmpty()) "**Submit**" else outs.joinToString(" / ") { strong(it) })
            }
            return "start: ${code(startLink(key))}$then (or the create dialog, §2: ${strong(title ?: key)})"
        }

        private fun case(c: Map<String, Any?>, L: MutableList<String>) {
            val key = str(c["key"])!!
            val title = str(c["name"])
            L.add("#### Case ${code(key)}" + (title?.let { " — ${inline(it)}" } ?: ""))
            val plan = c["planModel"] as? Map<String, Any?>
            val cx = plan?.let { CaseIndex(c, it) }
            val head = ArrayList<String>()
            str(c["file"])?.let { head.add(code(it)) }
            // The case's own start form — a plan item's start form is that item's dialog, named on its line.
            head.add(startLine(key, title, str(c["startFormKey"]), "start form of case ${code(key)}"))
            head.add("may start: " + starters(c))
            L.add(head.joinToString(" · "))
            str(c["workFormKey"])?.let {
                L.add("*Work form* tab: form ${formRef(it, "*Work form* of case ${code(key)}")} — with edit rights its " +
                    "**Save** writes the case variables at once (§2).")
            }
            str(c["customViewKey"])?.let { v ->
                L.add("Has a custom case view (${code(v)}): the case header links **Navigate to case view**" +
                    (str(c["customViewGroups"])?.let { " (for groups ${codes(it)})" } ?: "") +
                    "; the case pages and the case's own navigation are there, not among the standard tabs.")
            }
            title?.let { t -> sharedTitles[t]?.let { keys ->
                L.add("⚠ ${keys.size} definitions are called ${em(t)} (${keys.joinToString(", ") { code(it) }}): start this one by its link.")
            } }
            if (cx == null) { L.add("_No plan model was parsed._\n"); return }
            cx.reopen()?.let { L.add(it) }
            cx.caseExits().forEach { L.add(it) }

            // The bar the user (and the request "bring it to stage 5") counts by.
            val bar = cx.overview()
            val shown = bar.filter { it.shown != false }
            if (shown.size >= 2) {
                L.add("**Stage bar:** " + shown.mapIndexed { i, e ->
                    "Step ${i + 1} ${em(e.name)}" + (if (e.milestone) " (milestone)" else "") +
                        (if (e.shown == null) " (only if ${code(e.include!!)})" else "")
                }.joinToString(" · "))
                if (shown.any { it.shown == null }) {
                    L.add("An *only if* entry drops out while its condition is false; the later steps then move up.")
                }
                val ties = cx.tie(shown)
                if (ties.isNotEmpty()) {
                    L.add("⚠ ${ties.joinToString(", ") { em(it.name) }} share a `displayOrder` (or have none): at runtime " +
                        "Work orders them by when they ended, ended ones first, so a reached milestone or a finished " +
                        "stage moves ahead. These numbers are the ones before anything ends; count in the live bar " +
                        "(or `stage-overview`, §3) before acting on \"stage N\".")
                }
                // "0. Setup", "1. Pre-check", … — names that carry a number of their own, off by one (or more)
                // from the bar position, make "stage 5" mean two different stages. Say so, with the example.
                val clash = cx.numberedName(shown)
                if (clash != null) {
                    L.add("⚠ Stage names carry numbers of their own that differ from their step in the bar " +
                        "(${em(clash.value.name)} is Step ${clash.index + 1}). When the user says \"stage N\", " +
                        "ask whether they mean Step N of the bar or the stage named N.")
                }
            } else if (bar.isNotEmpty()) {
                L.add("No stage bar: Work draws it only for two or more stages and milestones.")
            }
            val hidden = bar.filter { it.shown == false }
            if (hidden.isNotEmpty()) {
                L.add("Kept out of the bar (`includeInStageOverview=\"false\"`): " +
                    hidden.joinToString(", ") { em(it.name) + if (it.milestone) " (milestone)" else "" } + ".")
            }
            L.add("")

            // Each stage in bar order (then any not in the bar), with what starts it and what is inside.
            val stages = cx.stages()
            val number = IdentityHashMap<Map<String, Any?>, Int>()
            if (shown.size >= 2) shown.forEachIndexed { i, e -> number[e.rec] = i + 1 }
            val ordered = stages.withIndex()
                .sortedWith(compareBy({ number[it.value.first] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
            for ((s, parent) in ordered) {
                val n = number[s]
                val heading = if (n != null) "**Step $n of ${shown.size} — ${inline(cx.display(s))}**"
                    else "**${inline(cx.display(s))}**" + (if (shown.size >= 2) " (not in the stage bar)" else "")
                val inside = parent?.let { " (inside stage ${em(it)})" } ?: ""
                L.add("$heading$inside — " + (listOf(cx.startsWhen(s, parent), cx.completion(s)) + cx.ruleFacts(s))
                    .joinToString(" · "))
                cx.stageExits(s).forEach { L.add("- $it") }
                val items = cx.items(s)
                if (items.isEmpty()) L.add("- _(nothing inside)_")
                for (line in items) L.add("- $line")
                L.add("")
            }
            // Plan items directly on the case plan model, outside every stage — often the user event
            // listeners that jump the case somewhere, which is exactly what a route planner must not miss.
            val top = cx.items(plan)
            if (top.isNotEmpty()) {
                L.add(if (stages.isEmpty()) "**The case plan** (no stages):" else "**Outside the stages** (directly on the case plan):")
                for (line in top) L.add("- $line")
                L.add("")
            }
        }

        /** The plan tree of one case, indexed for "what starts what". */
        private inner class CaseIndex(private val c: Map<String, Any?>, private val plan: Map<String, Any?>) {

            private val caseKey = str(c["key"])!!

            private val sentries: Map<String, Map<String, Any?>> = objs(c["sentries"])
                .mapNotNull { s -> str(s["id"])?.let { it to s } }.toMap()

            /** Plan item id (and definition id) → the record, so an onPart source can be named. */
            private val byRef = HashMap<String, Map<String, Any?>>()

            /**
             * Plan item id → the criteria on that plan item, gathered from its parent's list. Keyed by the
             * plan item, not the definition: two plan items may share a definition, each with its own sentries.
             */
            private val criteria = HashMap<String, MutableList<Map<String, Any?>>>()

            /** Every plan-tree record → the stage (or plan) it sits in. */
            private val parentOf = IdentityHashMap<Map<String, Any?>, Map<String, Any?>>()

            private val milestones: Map<String, Map<String, Any?>> = objs(c["milestones"])
                .mapNotNull { m -> str(m["id"])?.let { it to m } }.toMap()

            private val timers: Map<String, String> = objs(c["eventListeners"])
                .mapNotNull { e -> str(e["id"])?.let { id -> str(e["timer"])?.let { id to it } } }.toMap()

            /** Plan items some sentry waits for — an automatic step that nothing waits for is summarised. */
            private val referenced = HashSet<String>()

            /** How often each displayed name occurs: a repeated one is qualified wherever it is named. */
            private val nameCount = HashMap<String, Int>()

            /** Where this case shows each form — task, start form, Work form, button — for the ← hints. */
            val formUse = LinkedHashMap<String, LinkedHashSet<String>>()

            init {
                fun walk(node: Map<String, Any?>) {
                    for (cr in objs(node["criteria"])) {
                        (str(cr["planItemId"]) ?: str(cr["planItemDef"]))?.let { criteria.getOrPut(it) { ArrayList() }.add(cr) }
                    }
                    for (ch in objs(node["children"])) {
                        parentOf[ch] = node
                        str(ch["id"])?.let { byRef.putIfAbsent(it, ch) }
                        str(ch["planItemId"])?.let { byRef[it] = ch }
                        if (ch["type"] != "planFragment") nameCount.merge(display(ch), 1, Int::plus)
                        val nm = em(display(ch))
                        when (ch["type"]) {
                            "humanTask" -> str(ch["formKey"])?.let { formUse.getOrPut(it) { LinkedHashSet() }.add("on task $nm") }
                            "task" -> str(ch["formKey"])?.let {
                                formUse.getOrPut(it) { LinkedHashSet() }.add(if (str(ch["taskType"]) == "casePage") "on case page $nm" else "on task $nm")
                            }
                        }
                        str(ch["startFormKey"])?.let { formUse.getOrPut(it) { LinkedHashSet() }.add("in the form of button ${strong(buttonName(ch))}") }
                        if (ch.containsKey("children")) walk(ch)
                    }
                }
                walk(plan)
                str(c["startFormKey"])?.let { formUse.getOrPut(it) { LinkedHashSet() }.add("in the start form") }
                str(c["workFormKey"])?.let { formUse.getOrPut(it) { LinkedHashSet() }.add("on the *Work form* tab") }
                for (s in sentries.values) (s["onParts"] as? List<*>).orEmpty().mapNotNull { str(it) }.forEach { referenced.add(it) }
            }

            /**
             * What pressing a listener's button sets off: the stages and items whose sentry waits for it, and
             * whether it alone decides (sole on-part, no condition) — the jumps a route planner must know about.
             */
            fun setsOff(listener: Map<String, Any?>): String? {
                val ids = setOfNotNull(str(listener["planItemId"]), str(listener["id"]))
                val alone = ArrayList<String>(); val partly = ArrayList<String>()
                fun consider(cr: Map<String, Any?>, target: String) {
                    val s = sentries[str(cr["sentryRef"])] ?: return
                    val on = (s["onParts"] as? List<*>).orEmpty().mapNotNull { str(it) }
                    if (on.none { it in ids }) return
                    val verb = if (cr["type"] == "exitCriterion") "ends" else "starts"
                    if (on.size == 1 && str(s["condition"]) == null) alone.add("$verb $target") else partly.add(target)
                }
                fun walk(node: Map<String, Any?>) {
                    for (cr in objs(node["criteria"])) {
                        val t = byRef[str(cr["planItemId"]) ?: str(cr["planItemDef"]) ?: ""] ?: continue
                        consider(cr, ref(t))
                    }
                    for (ch in objs(node["children"])) if (ch.containsKey("children")) walk(ch)
                }
                walk(plan)
                for (cr in objs(plan["exitCriteria"])) consider(cr, "the whole case")
                val out = ArrayList<String>()
                if (alone.isNotEmpty()) out.add("pressing it " + alone.distinct().joinToString(", "))
                val waiting = partly.distinct()
                if (waiting.isNotEmpty()) out.add(waiting.joinToString(", ") + (if (waiting.size == 1) " also waits" else " also wait") + " for it")
                return out.takeIf { it.isNotEmpty() }?.joinToString("; ")
            }

            /** The name Work shows: the plan item's own name when it has one, else its definition's. */
            fun display(rec: Map<String, Any?>): String = str(rec["planItemName"]) ?: str(rec["name"]) ?: str(rec["id"]) ?: "?"

            /** A plan item named in a sentry: qualified by its stage, or its id, when the name repeats. */
            private fun ref(rec: Map<String, Any?>): String {
                val nm = em(display(rec))
                if ((nameCount[display(rec)] ?: 0) < 2) return nm
                val parent = parentOf[rec]
                val sameParent = parent?.let { p -> objs(p["children"]).count { it !== rec && display(it) == display(rec) } } ?: 0
                return when {
                    sameParent > 0 || parent == null -> "$nm ${code(str(rec["planItemId"]) ?: str(rec["id"]) ?: "?")}"
                    parent === plan -> "$nm (on the case plan)"
                    else -> "$nm (in ${em(display(parent))})"
                }
            }

            /** An item's own line: its id only when a sibling has the same name. */
            private fun own(rec: Map<String, Any?>): String {
                val nm = em(display(rec))
                val parent = parentOf[rec] ?: return nm
                val twins = objs(parent["children"]).count { it !== rec && display(it) == display(rec) }
                return if (twins > 0) "$nm ${code(str(rec["planItemId"]) ?: str(rec["id"]) ?: "?")}" else nm
            }

            /** Every stage, nested ones and those in plan fragments included, with the stage it sits in. */
            fun stages(): List<Pair<Map<String, Any?>, String?>> {
                val out = ArrayList<Pair<Map<String, Any?>, String?>>()
                fun walk(node: Map<String, Any?>, parent: String?) {
                    for (ch in objs(node["children"])) when (ch["type"]) {
                        "stage" -> { out.add(ch to parent); walk(ch, display(ch)) }
                        // A plan fragment creates no instance: its children live in the enclosing stage.
                        "planFragment" -> walk(ch, parent)
                    }
                }
                walk(plan, null)
                return out
            }

            /**
             * The stage bar exactly as the engine builds it (`GetStageOverviewCmd`): every stage and every
             * milestone of the case, nested ones included, sorted by `displayOrder` with the unordered ones
             * first, ties in the engine's initial order (stages, then milestones). `includeInStageOverview` is
             * true unless the model says otherwise, and may be an expression decided at runtime. At runtime ties
             * are broken by end time — which [tie] says.
             */
            fun overview(): List<BarEntry> {
                val entries = ArrayList<BarEntry>()
                for ((s, _) in stages()) entries.add(entry(s, s, false))
                fun walk(node: Map<String, Any?>) {
                    for (ch in objs(node["children"])) {
                        if (ch["type"] == "milestone") entries.add(entry(ch, milestones[str(ch["id"])] ?: ch, true))
                        if (ch.containsKey("children")) walk(ch)
                    }
                }
                walk(plan)
                return entries.withIndex()
                    .sortedWith(compareBy({ it.value.order != null }, { it.value.order ?: 0.0 },
                        { it.value.engineIndex ?: (it.index + 100_000) }))
                    .map { it.value }
            }

            fun shownBar(): List<BarEntry> = overview().filter { it.shown != false }

            private fun entry(rec: Map<String, Any?>, attrs: Map<String, Any?>, milestone: Boolean): BarEntry {
                val include = str(attrs["includeInStageOverview"]) ?: str(rec["includeInStageOverview"])
                val shown = when {
                    include == null || include.equals("true", ignoreCase = true) -> true
                    include.equals("false", ignoreCase = true) -> false
                    else -> null
                }
                val order = (str(attrs["displayOrder"]) ?: str(rec["displayOrder"]))?.toDoubleOrNull()
                val idx = (rec["overviewIndex"] as? Number ?: attrs["overviewIndex"] as? Number)?.toInt()
                return BarEntry(rec, display(rec), order, shown, include?.let { condition(it) }, milestone, idx)
            }

            /** The shown entries whose place is decided at runtime: they share a `displayOrder`, or have none. */
            fun tie(shown: List<BarEntry>): List<BarEntry> =
                shown.groupBy { it.order }.values.filter { it.size > 1 }.flatten()

            /** The first shown entry whose name starts with a number other than its step in the bar — or null. */
            fun numberedName(shown: List<BarEntry>): IndexedValue<BarEntry>? = shown.withIndex().firstOrNull { (i, e) ->
                NUMBERED.find(e.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it != i + 1 } == true
            }

            /** "starts as soon as …" or "starts when: X completes — or — Y is triggered". */
            fun startsWhen(node: Map<String, Any?>, parent: String?): String {
                val entry = criteriaOf(node, "entryCriterion")
                val manual = isManual(node)
                val whenParent = parent?.let { "as soon as stage ${em(it)} is active" } ?: "as soon as the case starts"
                val verb = if (manual) "offered (enabled)" else "starts"
                val button = if (manual) " — " + manualFact(node) else ""
                if (entry.isEmpty()) return "$verb $whenParent$button"
                // An empty sentry is satisfied at its first evaluation: it waits for nothing.
                if (entry.any { isEmptySentry(it) }) return "$verb $whenParent (an empty entry criterion fires at once)$button"
                return "$verb when: " + alternatives(entry, " — **or** — ") + button
            }

            /** A stage's exits, split by what they count as for the sentries that wait on the stage. */
            fun stageExits(node: Map<String, Any?>): List<String> {
                val exits = criteriaOf(node, "exitCriterion")
                if (exits.isEmpty()) return emptyList()
                val out = ArrayList<String>()
                val byType = exits.groupBy { str(it["exitEventType"])?.lowercase() }
                byType["forcecomplete"]?.let { g ->
                    out.add("completes when: " + alternatives(g, " — **or** — ") +
                        " (forceComplete: it ends as *completed* even with work still open inside, so what waits for it to complete starts)")
                }
                byType["complete"]?.let { g ->
                    out.add("completes when: " + alternatives(g, " — **or** — ") +
                        " (only if nothing inside still holds it open — otherwise the step that triggers it fails with an error)")
                }
                val plain = exits.filter { str(it["exitEventType"])?.lowercase() !in setOf("complete", "forcecomplete") }
                if (plain.isNotEmpty()) {
                    out.add("exits when: " + alternatives(plain, " — **or** — ") +
                        " (an exit, not a completion: what waits for it to complete does not start)")
                }
                return out
            }

            /** What ends the whole case: exit criteria on the case plan model itself. */
            fun caseExits(): List<String> {
                val ex = objs(plan["exitCriteria"])
                if (ex.isEmpty()) return emptyList()
                val (completing, ending) = ex.partition { str(it["exitEventType"])?.lowercase() in setOf("complete", "forcecomplete") }
                val out = ArrayList<String>()
                if (completing.isNotEmpty()) out.add("**The case completes** when: " + alternatives(completing, " — **or** — ") + ".")
                if (ending.isNotEmpty()) {
                    // A button among the on-parts is the trap: one click ends the case for good.
                    val button = ending.any { cr -> (sentries[str(cr["sentryRef"])]?.get("onParts") as? List<*>).orEmpty()
                        .any { byRef[str(it) ?: ""]?.get("type") == "userEventListener" } }
                    out.add((if (button) "⚠ " else "") + "**The whole case ends** (terminated, with every open stage and task) when: " +
                        alternatives(ending, " — **or** — ") + ".")
                }
                return out
            }

            /** The reactivation listener, which is a button on a *finished* case, not an item of a running one. */
            fun reopen(): String? {
                var found: Map<String, Any?>? = null
                fun walk(node: Map<String, Any?>) {
                    for (ch in objs(node["children"])) {
                        if (ch["type"] == "eventListener" && str(ch["listenerType"]) == "reactivate") found = found ?: ch
                        if (ch.containsKey("children")) walk(ch)
                    }
                }
                walk(plan)
                val r = found ?: return null
                val name = str(r["name"]) ?: "Reactivate ${str(c["name"]) ?: caseKey}"
                val facts = ArrayList<String>()
                str(r["startFormKey"])?.let { facts.add("asks for form " + formRef(it, "reopen button ${strong(name)} of case ${code(caseKey)}")) }
                who(r)?.let { facts.add(it) }
                str(r["offeredFor"])?.let { facts.add("offered for ${code(it)} after the case ends") }
                return "Reopen: once the case has completed or been terminated, Work offers ${strong(name)} " +
                    "(toolbar / *More Actions*)" + (if (facts.isEmpty()) "" else " — " + facts.joinToString(" · ")) +
                    ". Only when the user asks (§1.5)."
            }

            fun completion(stage: Map<String, Any?>): String {
                str(stage["autoCompleteCondition"])?.let { return "completes: autoComplete if ${code(condition(it))}" }
                return if (str(stage["autoComplete"])?.equals("true", ignoreCase = true) == true) "completes: autoComplete"
                    else "completes: once nothing inside is left (no autoComplete, §4)"
            }

            private fun autoCompletes(stage: Map<String, Any?>?): Boolean = stage != null &&
                (str(stage["autoComplete"])?.equals("true", ignoreCase = true) == true || str(stage["autoCompleteCondition"]) != null)

            private fun criteriaOf(node: Map<String, Any?>, type: String): List<Map<String, Any?>> =
                criteria[str(node["planItemId"]) ?: str(node["id"])].orEmpty().filter { it["type"] == type }

            private fun isEmptySentry(criterion: Map<String, Any?>): Boolean {
                val s = sentries[str(criterion["sentryRef"])] ?: return false
                return (s["onParts"] as? List<*>).orEmpty().mapNotNull { str(it) }.isEmpty() && str(s["condition"]) == null
            }

            /** Criteria read out as alternatives, each ← hint said once: the same writer is no news the second time. */
            private fun alternatives(criteria: List<Map<String, Any?>>, sep: String): String {
                val seen = HashSet<String>()
                return criteria.joinToString(sep) { cr ->
                    val d = describe(cr)
                    val i = d.indexOf(" ← ")
                    if (i < 0 || seen.add(d.substring(i))) d else d.substring(0, i)
                }
            }

            /** One sentry, read out: every onPart must have happened, and the condition must hold. */
            fun describe(criterion: Map<String, Any?>): String {
                val sid = str(criterion["sentryRef"])
                val s = sentries[sid] ?: return "sentry ${code(sid ?: "?")}"
                val exit = criterion["type"] == "exitCriterion"
                val events = objs(s["events"]).associate { (str(it["source"]) ?: "") to (str(it["event"]) ?: "") }
                val onParts = (s["onParts"] as? List<*>).orEmpty().mapNotNull { str(it) }
                val parts = ArrayList<String>()
                for (src in onParts) {
                    val rec = byRef[src]
                    parts.add((rec?.let { ref(it) } ?: code(src)) + " " + verb(events[src], rec))
                }
                val cond = str(s["condition"])
                cond?.let { parts.add(code(condition(it))) }
                if (parts.isEmpty()) {
                    return if (exit) "at once (an empty exit criterion fires at once — check the deployed model, §3)"
                        else "nothing (an empty entry criterion fires at once)"
                }
                // onEvent changes something only when two things must meet: an on-part and a condition, or two on-parts.
                val onEvent = str(s["triggerMode"])?.equals("onEvent", ignoreCase = true) == true &&
                    ((onParts.isNotEmpty() && cond != null) || onParts.size >= 2)
                return parts.joinToString(" **and** ") + (if (onEvent) " (onEvent)" else "") + (cond?.let { setBy(it, formUse) } ?: "")
            }

            /** One plan item inside [node], as a line: what it is, what it needs, how it starts. */
            fun items(node: Map<String, Any?>): List<String> {
                val out = ArrayList<String>()
                val quiet = ArrayList<String>()
                fun visit(container: Map<String, Any?>) {
                    for (ch in objs(container["children"])) {
                        val type = ch["type"] as? String ?: continue
                        if (type == "planFragment") { visit(ch); continue }
                        if (type == "stage") {
                            // A stage has its own entry; inside another stage it is worth a pointer, on the
                            // case plan it would only repeat the list.
                            if (node !== plan) out.add("stage ${own(ch)} — see its own entry")
                            continue
                        }
                        if (type == "eventListener" && str(ch["listenerType"]) == "reactivate") continue
                        val line = item(ch, type, node)
                        if (line == null) quiet.add(display(ch)) else out.add(line)
                    }
                }
                visit(node)
                if (quiet.isNotEmpty()) {
                    val counts = LinkedHashMap<String, Int>()
                    quiet.forEach { counts.merge(it, 1, Int::plus) }
                    // Not "+ N …": printed as a list item, `- + …` is a nested list in Markdown.
                    out.add("${quiet.size} automatic step${if (quiet.size == 1) "" else "s"} nothing waits for (" +
                        counts.entries.take(8).joinToString(", ") { (n, k) -> em(n) + if (k > 1) " ×$k" else "" } +
                        (if (counts.size > 8) ", …" else "") + ")")
                }
                return out
            }

            /** null: an automatic step that no sentry waits for and that has no rule of its own — summarised. */
            private fun item(ch: Map<String, Any?>, type: String, stage: Map<String, Any?>): String? {
                val nm = own(ch)
                val facts = ArrayList<String>()
                val manual = isManual(ch)
                var automatic = false
                val head = when (type) {
                    "humanTask" -> {
                        str(ch["formKey"])?.let { facts.add("form " + formRef(it, "task $nm of case ${code(caseKey)}")) }
                            ?: facts.add("no form (a plain *Complete*)")
                        facts.add(assignment(ch) ?: UNASSIGNED)
                        "task $nm"
                    }
                    "userEventListener" -> {
                        str(ch["startFormKey"])?.let { facts.add("asks for form " + formRef(it, "button ${strong(buttonName(ch))} of case ${code(caseKey)}")) }
                        who(ch)?.let { facts.add(it) }
                        str(ch["availableCondition"])?.let {
                            facts.add("button only while ${code(condition(it))}${setBy(it, formUse)} (otherwise *unavailable*, no button)")
                        }
                        setsOff(ch)?.let { facts.add(it) }
                        "user event listener $nm — button ${strong(buttonName(ch))} (toolbar / *More Actions*) while it is available"
                    }
                    "timerEventListener" -> {
                        str(ch["availableCondition"])?.let { facts.add("listens only while ${code(condition(it))}") }
                        val t = str(ch["timer"]) ?: timers[str(ch["id"])]
                        "timer $nm — fires " + (t?.let { "after ${code(it)} " } ?: "") + "by itself; nothing to click"
                    }
                    "eventListener" -> {
                        str(ch["availableCondition"])?.let { facts.add("listens only while ${code(condition(it))}") }
                        listener(ch, nm)
                    }
                    "milestone" -> "milestone $nm — reached by itself, nothing to click"
                    "processTask" -> processTask(ch, nm)
                    "caseTask" -> "case task $nm — runs case ${code(str(ch["caseRef"]) ?: "?")} (a sub-item)"
                    "decisionTask" -> "decision $nm — evaluates ${code(str(ch["decisionRef"]) ?: "?")} by itself"
                    "planItem(?)" -> "plan item $nm — its definition was not found in the model"
                    else -> {
                        val form = str(ch["formKey"])
                        if (str(ch["taskType"]) == "casePage") {
                            // A case page is navigation, not work: no task is created for it.
                            form?.let { facts.add("form " + formRef(it, "case page $nm of case ${code(caseKey)}") + " with only *Save*") }
                            "case page $nm — an entry in the case view's navigation (**Navigate to case view** in the " +
                                "case header), not a task (never in a task list)"
                        } else {
                            form?.let { facts.add("form " + formRef(it, "task $nm of case ${code(caseKey)}")) }
                            assignment(ch)?.let { facts.add(it) }
                            automatic = facts.isEmpty()
                            if (automatic) "automatic step $nm" else "task $nm"
                        }
                    }
                }
                if (manual) facts.add(manualFact(ch) + if (autoCompletes(stage) && rule(rules(ch)["requiredRule"]) == null)
                    " — start it before the stage's required work is done: the stage drops it when it completes" else "")
                val entry = criteriaOf(ch, "entryCriterion")
                if (entry.isNotEmpty()) {
                    val verb = when {
                        type == "milestone" -> "reached when"
                        manual -> "offered (enabled) when"
                        type == "userEventListener" -> "its button appears when"
                        else -> "starts when"
                    }
                    facts.add(if (entry.any { isEmptySentry(it) }) "$verb: at once (an empty entry criterion)"
                        else "$verb: " + alternatives(entry, " **or** "))
                }
                facts.addAll(ruleFacts(ch, hasEntry = entry.isNotEmpty()))
                val exits = criteriaOf(ch, "exitCriterion")
                if (exits.isNotEmpty()) {
                    val scope = when (str(exits.firstNotNullOfOrNull { str(it["exitType"]) })) {
                        "activeInstances" -> " (only running instances are ended)"
                        "activeAndEnabledInstances" -> " (only running or enabled instances are ended)"
                        else -> ""
                    }
                    facts.add("ends when: " + alternatives(exits, " **or** ") + scope)
                }
                if (automatic && !manual && entry.isEmpty() && exits.isEmpty() && rule(rules(ch)["requiredRule"]) == null &&
                    (str(ch["planItemId"]) ?: "") !in referenced && (str(ch["id"]) ?: "") !in referenced) return null
                return head + when { facts.isEmpty() -> ""; " — " in head -> " · "; else -> " — " } + facts.joinToString(" · ")
            }

            /** What a non-user event listener waits for: nothing a click in Work produces. */
            private fun listener(ch: Map<String, Any?>, nm: String): String = when (str(ch["listenerType"])) {
                "variable" -> {
                    val v = str(ch["variableName"])
                    val change = when (str(ch["variableChangeType"])) {
                        "create" -> "created"; "update" -> "updated"; "update-create" -> "created or updated"
                        "delete" -> "deleted"; else -> "set or changed"
                    }
                    "variable listener $nm — fires when case variable ${code(v ?: "?")} is $change after the listener " +
                        "became available; nothing to click: set it through the step that writes it" +
                        (v?.let { setBy(it, formUse, whole = true) } ?: "")
                }
                "signal" -> "signal listener $nm — waits for signal ${code(str(ch["signalRef"]) ?: "?")} thrown elsewhere " +
                    "(another process or case, or code); nothing to click"
                "intent" -> "intent listener $nm — fired by a conversation intent; nothing to click"
                null -> str(ch["eventType"])?.let { "event listener $nm — waits for inbound event ${code(it)} (sent from outside Work); nothing to click" }
                    ?: "event listener $nm — triggered only by code or REST; nothing to click"
                else -> "event listener $nm (${code(str(ch["listenerType"])!!)}) — fired by code; nothing to click"
            }

            /** A process task, with the task names and forms its literal in-mappings give the child process. */
            private fun processTask(ch: Map<String, Any?>, nm: String): String {
                val ref = str(ch["processRef"]) ?: "?"
                val base = "process task $nm — runs process ${code(ref)} (a sub-item; see ${code(ref)})"
                val child = allProcesses.firstOrNull { str(it["key"]) == ref } ?: return base
                val lits = callerLiterals[ref].orEmpty().filter { it.element == str(ch["id"]) }.associate { it.target to it.value }
                if (lits.isEmpty()) return base
                val shows = objs(child["userTasks"]).mapNotNull { t ->
                    val tn = str(t["name"])?.let { varOf(it) }?.let { lits[it] } ?: return@mapNotNull null
                    val fk = str(t["formKey"])?.let { varOf(it) }?.let { lits[it] }
                    em(tn) + (fk?.let { " with form " + formRef(it, "task ${em(tn)} (via process task $nm of case ${code(caseKey)})") } ?: "")
                }
                return if (shows.isEmpty()) base else "$base; its task appears as " + shows.joinToString(", ")
            }

            /** The rules of a stage or item that change how it starts, repeats or holds its stage open. */
            fun ruleFacts(ch: Map<String, Any?>, hasEntry: Boolean = criteriaOf(ch, "entryCriterion").isNotEmpty()): List<String> {
                val r = rules(ch)
                val out = ArrayList<String>()
                rule(r["requiredRule"])?.let { out.add(if (it == true) "required" else "required if ${code(it.toString())}") }
                val pcr = (r["parentCompletionRule"] as? String)?.trim()
                rule(r["repetitionRule"])?.let { rep ->
                    val cond = (rep as? String)?.let { code(it) }
                    val letsGo = pcr == "ignore" || pcr?.startsWith("ignoreAfterFirstCompletion") == true
                    out.add(when {
                        ch["type"] in setOf("userEventListener", "eventListener", "timerEventListener") ->
                            "repeatable: stays available after it fires" + (cond?.let { " (while $it)" } ?: "")
                        hasEntry -> "repeats each time its entry criterion fires" + (cond?.let { " while $it" } ?: "")
                        isManual(ch) -> "repeats: offered again each time it completes" + (cond?.let { " while $it" } ?: "")
                        cond != null -> "repeats: starts again each time it completes while $cond holds" +
                            if (letsGo) "" else " — its stage cannot complete meanwhile"
                        else -> "repeats: starts again every time it completes" + if (letsGo) "" else " — its stage never completes by itself"
                    })
                }
                str(r["maxInstanceCount"])?.takeIf { !it.equals("unlimited", ignoreCase = true) }?.let { out.add("at most $it at once") }
                str(r["collectionVariable"])?.let { out.add("one instance per element of ${code(it)}") }
                when {
                    pcr == null || r["parentCompletionRule"] == true || pcr == "default" -> {}
                    pcr == "ignore" -> out.add("never holds its stage open")
                    pcr == "ignoreIfAvailable" -> out.add("holds its stage open only once started")
                    pcr == "ignoreIfAvailableOrEnabled" -> out.add("holds its stage open only while running")
                    pcr.startsWith("ignoreAfterFirstCompletion") -> out.add("holds its stage open only until its first completion")
                }
                rule(r["completionNeutralRule"])?.let {
                    out.add("holds its stage open only once started" + if (it == true) "" else " (if ${code(it.toString())})")
                }
                return out
            }

            private fun isManual(node: Map<String, Any?>): Boolean = rule(rules(node)["manualActivationRule"]) != null

            /** "**manual activation** — action button **X** (groups …), opens form `F` first". */
            private fun manualFact(ch: Map<String, Any?>): String {
                val r = rule(rules(ch)["manualActivationRule"])
                val ai = str(rules(ch)["aiActivationType"])
                val button = "action button ${strong(buttonName(ch))}" + (who(ch)?.let { " ($it)" } ?: "") +
                    (str(ch["startFormKey"])?.let { f ->
                        ", opens form " + formRef(f, "button ${strong(buttonName(ch))} of case ${code(caseKey)}") + " first"
                    } ?: "")
                val head = if (r == true) "**manual activation**: $button" else "manual activation if ${code(r.toString())}: $button"
                // The platform offers the AI-only kinds to the case's agent and makes no button for users.
                val agentOnly = ai == "ai-suggestion" || ai == "ai-automatic"
                return head + if (agentOnly) " — for the case's AI agent only (no button for users when the case has an agent)" else ""
            }

            /** The name Work gives the action it makes of this item: the model's own, else the plan item's. */
            fun buttonName(node: Map<String, Any?>): String = str(node["actionName"]) ?: display(node)
        }

        private data class BarEntry(
            val rec: Map<String, Any?>, val name: String, val order: Double?, val shown: Boolean?, val include: String?,
            val milestone: Boolean, val engineIndex: Int?,
        )

        private data class IoLiteral(val element: String, val elementName: String, val target: String, val value: String)

        /** One way a process reaches a user task, read backwards through its automatic steps. */
        private sealed class Way {
            data class Cond(val from: String, val flow: Map<String, Any?>, val cond: String) : Way()
            data class Default(val from: String, val flow: Map<String, Any?>) : Way()
            data class After(val task: String) : Way()
            data class Start(val event: String) : Way()
            data class Boundary(val event: String) : Way()
            data class Catch(val event: String) : Way()
        }

        /** `${taskName}` → `taskName`: a name or key that is nothing but one variable. */
        private fun varOf(s: String): String? = Regex("^[$#]\\{\\s*([A-Za-z_][\\w]*)\\s*}$").find(s.trim())?.groupValues?.get(1)

        // ---- processes ----------------------------------------------------------------------------

        private fun startForms(nodeId: String): List<String> =
            edgesFrom[nodeId].orEmpty().filter { it["rel"] == "start-form" }.map { it["t"].toString().substringAfter(':') }.distinct()

        /** Who runs a process as a sub-item: (caller node id, the calling element's name). */
        private fun callersOf(key: String): List<Pair<String, String?>> =
            edgesTo["process:$key"].orEmpty().filter { it["rel"] == "processTask" || it["rel"] == "callActivity" }
                .map { e ->
                    val s = e["s"].toString()
                    val callerKey = s.substringAfter(':')
                    val element = if (s.startsWith("case:")) {
                        var found: String? = null
                        fun walk(n: Map<String, Any?>) {
                            for (ch in objs(n["children"])) {
                                if (ch["type"] == "processTask" && str(ch["processRef"]) == key) found = found ?: (str(ch["planItemName"]) ?: str(ch["name"]))
                                if (ch.containsKey("children")) walk(ch)
                            }
                        }
                        allCases.firstOrNull { str(it["key"]) == callerKey }?.let { (it["planModel"] as? Map<String, Any?>)?.let(::walk) }
                        found
                    } else allProcesses.firstOrNull { str(it["key"]) == callerKey }
                        ?.let { p -> objs(p["callActivities"]).firstOrNull { str(it["calledElement"]) == key }?.let { str(it["name"]) } }
                    s to element
                }.distinctBy { it.first }

        private fun process(p: Map<String, Any?>, L: MutableList<String>) {
            val key = str(p["key"])!!
            val title = str(p["name"])
            L.add("#### Process ${code(key)}" + (title?.let { " — ${inline(it)}" } ?: ""))
            val head = ArrayList<String>()
            str(p["file"])?.let { head.add(code(it)) }
            val callers = callersOf(key)
            head.add(startLine(key, title, startForms("process:$key").firstOrNull(), "start form of process ${code(key)}"))
            head.add("may start: " + starters(p))
            L.add(head.joinToString(" · "))
            if (callers.isNotEmpty()) {
                L.add("Runs as a sub-item of " + callers.joinToString(", ") { (s, el) ->
                    (if (s.startsWith("case:")) "case " else "process ") + code(s.substringAfter(':')) + (el?.let { " (${em(it)})" } ?: "")
                } + " — open it there (*Sub-items*); do not start it on its own.")
            }
            val starts = edgesTo["process:$key"].orEmpty().filter { it["rel"] == "starts-process" }
                .map { it["s"].toString() }.filter { it.startsWith("action:") || it.startsWith("page:") || it.startsWith("form:") }.distinct()
            if (starts.isNotEmpty()) {
                L.add("Also started from: " + starts.joinToString(", ") { "${it.substringBefore(':')} ${code(it.substringAfter(':'))}" } + ".")
            }
            title?.let { t -> sharedTitles[t]?.let { keys ->
                L.add("⚠ ${keys.size} definitions are called ${em(t)} (${keys.joinToString(", ") { code(it) }}): start this one by its link.")
            } }
            L.add("")

            val flows = Flows(p)
            val tasks = objs(p["userTasks"])
            val subs = objs(p["subProcesses"]).mapNotNull { s -> str(s["id"])?.let { it to s } }.toMap()
            fun eventSub(t: Map<String, Any?>): Map<String, Any?>? {
                var cur = str(t["parent"])
                val seen = HashSet<String>()
                while (cur != null && seen.add(cur)) {
                    val s = subs[cur] ?: return null
                    if (s["eventSubProcess"] == true) return s
                    cur = str(s["parent"])
                }
                return null
            }
            val mi = objs(p["multiInstance"]).mapNotNull { m -> str(m["activity"])?.let { it to m } }.toMap()
            val lits = callerLiterals[key].orEmpty()
            val (onEvent, inFlow) = tasks.partition { eventSub(it) != null }
            val order = OverviewRenderer.flowOrder(p, inFlow.mapNotNull { str(it["id"]) })
            fun taskFacts(t: Map<String, Any?>, nm: String): ArrayList<String> {
                val facts = ArrayList<String>()
                val fk = str(t["formKey"])
                val fkVar = fk?.let { varOf(it) }
                val fkLits = fkVar?.let { v -> lits.filter { it.target == v }.distinctBy { it.value } }.orEmpty()
                when {
                    fk == null -> facts.add("no form (a plain *Complete*)")
                    fkLits.isNotEmpty() -> facts.add("form set by the caller: " + fkLits.joinToString(", ") { l ->
                        formRef(l.value, "task $nm of ${code(key)} (via process task ${em(l.elementName)})") + " (${em(l.elementName)})"
                    })
                    else -> facts.add("form " + formRef(fk, "task $nm of ${code(key)}"))
                }
                facts.add(assignment(t) ?: UNASSIGNED)
                mi[str(t["id"])]?.let { m ->
                    val seq = str(m["sequential"])?.equals("true", ignoreCase = true) == true
                    val per = str(m["collection"])?.let { "one task per element of ${code(it)}" }
                        ?: str(m["cardinality"])?.let { "${code(it)} tasks" } ?: "several tasks of this name"
                    facts.add("multi-instance (${if (seq) "one after the other" else "parallel"}): $per; the process goes on " +
                        (str(m["completionCondition"])?.let { "once ${code(condition(it))} holds or all are done" } ?: "only when all are completed"))
                }
                return facts
            }
            fun taskName(t: Map<String, Any?>): String {
                val raw = str(t["name"]) ?: str(t["id"]) ?: "?"
                val v = varOf(raw) ?: return em(raw)
                val names = lits.filter { it.target == v }.map { it.value }.distinct()
                return em(raw) + if (names.isEmpty()) " (its name is set at runtime)" else " (appears as " + names.take(6).joinToString(", ") { em(it) } +
                    (if (names.size > 6) ", …" else "") + ")"
            }
            if (inFlow.isNotEmpty()) {
                L.add("User tasks, in flow order:")
                inFlow.sortedBy { order[str(it["id"])] ?: Int.MAX_VALUE }.forEachIndexed { i, t ->
                    val nm = taskName(t)
                    val facts = taskFacts(t, nm)
                    flows.waysIn(str(t["id"]) ?: "")?.let { facts.add("reached $it") }
                    L.add("${i + 1}. $nm — " + facts.joinToString(" · "))
                }
                L.add("")
            }
            if (onEvent.isNotEmpty()) {
                L.add("Only when an event fires (event sub-process — not in the flow above):")
                for (t in onEvent) {
                    val nm = taskName(t)
                    val sub = eventSub(t)!!
                    val facts = taskFacts(t, nm)
                    val trigger = flows.trigger(str(t["id"]) ?: "")
                    flows.waysIn(str(t["id"]) ?: "", start = false)?.let { facts.add("reached $it") }
                    L.add("- $nm — in ${em(str(sub["name"]) ?: str(sub["id"]) ?: "?")}" + (trigger?.let { ", starts on $it" } ?: "") +
                        " · " + facts.joinToString(" · "))
                }
                L.add("")
            }
            val branches = flows.unusedBranches()
            if (branches.isNotEmpty()) {
                L.add("Other branches: " + branches.joinToString("; "))
                L.add("")
            }
            val calls = objs(p["callActivities"]).mapNotNull { ca ->
                str(ca["calledElement"])?.let { "${code(it)} (${em(str(ca["name"]) ?: str(ca["id"]) ?: "?")})" }
            }
            if (calls.isNotEmpty()) L.add("Calls: " + calls.joinToString(", ") + " — their tasks belong to the child process (*Sub-items*).\n")
        }

        /**
         * A process's sequence flows, read backwards from a user task: through every automatic step to the
         * gateway, boundary event, task or start that decides whether the task appears at all.
         */
        private inner class Flows(p: Map<String, Any?>) {
            private val flows = objs(p["flows"])
            private val incoming = flows.groupBy { str(it["to"]) }
            private val userTasks = objs(p["userTasks"]).mapNotNull { str(it["id"]) }.toSet()
            private val events = objs(p["events"]).mapNotNull { e -> str(e["id"])?.let { it to e } }.toMap()
            private val gateways = objs(p["gateways"]).mapNotNull { str(it["id"]) }.toSet()
            private val names: Map<String, String> = run {
                val m = HashMap<String, String>()
                for (bucket in listOf("userTasks", "serviceTasks", "scriptTasks", "ruleTasks", "otherTasks",
                        "callActivities", "subProcesses", "gateways", "events")) {
                    for (e in objs(p[bucket])) { val id = str(e["id"]) ?: continue; str(e["name"])?.let { m[id] = it } }
                }
                m
            }
            private val used = HashSet<Any>()
            private val formUse: Map<String, Set<String>> = run {
                val m = LinkedHashMap<String, LinkedHashSet<String>>()
                for (t in objs(p["userTasks"])) str(t["formKey"])?.let {
                    m.getOrPut(it) { LinkedHashSet() }.add("on task ${em(str(t["name"]) ?: str(t["id"]) ?: "?")}")
                }
                for (f in startForms("process:${str(p["key"])}")) m.getOrPut(f) { LinkedHashSet() }.add("in the start form")
                m
            }

            private fun label(id: String, flow: Map<String, Any?>?): String = names[id]?.let { em(it) }
                ?: if (id in gateways) "an unnamed gateway" + (flow?.let { f -> str(f["name"])?.let { " (branch ${em(it)})" } } ?: "")
                else "an unnamed step"

            private fun ways(taskId: String): List<Way> {
                val out = ArrayList<Way>()
                val seen = HashSet<String>()
                val queue = ArrayDeque(listOf(taskId))
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    for (f in incoming[node].orEmpty()) {
                        val src = str(f["from"]) ?: continue
                        val cond = str(f["condition"])
                        val ev = events[src]
                        when {
                            cond != null -> out.add(Way.Cond(src, f, cond))
                            f["default"] == true -> out.add(Way.Default(src, f))
                            src in userTasks -> out.add(Way.After(src))
                            ev?.get("type") == "startEvent" -> out.add(Way.Start(src))
                            ev?.get("type") == "boundaryEvent" -> out.add(Way.Boundary(src))
                            // A catch event is a wait, not a step: the task comes only once its signal, timer
                            // or message does. Walking past it would date the task to whatever came before.
                            ev?.get("type") == "intermediateCatchEvent" && str(ev["def"]) != "link" -> out.add(Way.Catch(src))
                            seen.add(src) -> queue.add(src)
                        }
                    }
                }
                return out.distinct()
            }

            /**
             * "after *Approved?* if `…` · or at the start" — null when every way in is unconditional. Without
             * [start], a start event is not a way in worth naming (an event sub-process names its trigger).
             */
            fun waysIn(taskId: String, start: Boolean = true): String? {
                val ways = ways(taskId).filter { start || it !is Way.Start }
                if (ways.all { it is Way.After || it is Way.Start }) return null
                return ways.joinToString(" · or ") { w ->
                    when (w) {
                        is Way.Cond -> { used.add(w.flow); "after ${label(w.from, w.flow)} if ${code(condition(w.cond))}${setBy(w.cond, formUse)}" }
                        is Way.Default -> { used.add(w.flow); "after ${label(w.from, w.flow)} otherwise (default flow)" }
                        is Way.After -> "after task ${label(w.task, null)}"
                        is Way.Start -> "at the start" + (events[w.event]?.let { trig(it) }?.let { " ($it)" } ?: "")
                        is Way.Boundary -> boundary(w.event)
                        is Way.Catch -> catch(w.event)
                    }
                }
            }

            /** What an intermediate catch event waits for — never something a click in Work produces. */
            private fun catch(id: String): String {
                val e = events[id] ?: return "after event ${code(id)} arrives"
                val nm = str(e["name"])?.let { " (${em(it)})" } ?: ""
                val v = str(e["value"])
                return when (str(e["def"])) {
                    "signal" -> "after signal ${code(v ?: "?")} arrives$nm — thrown elsewhere, nothing to click"
                    "timer" -> "after timer ${code(v ?: "?")}$nm — wait for it, never trigger it"
                    "message" -> "after message ${code(v ?: "?")} arrives$nm — sent from outside, nothing to click"
                    "conditional" -> "once ${code(condition(v ?: "?"))} holds$nm${v?.let { setBy(it, formUse) } ?: ""}"
                    else -> "after event ${str(e["name"])?.let { em(it) } ?: code(id)} arrives (sent from outside Work)"
                }
            }

            /**
             * The start event an event sub-process's task is reached from, as "timer `P1D`": every way back,
             * through gateways and their conditions alike, to the first start event.
             */
            fun trigger(taskId: String): String? {
                val seen = HashSet<String>()
                val queue = ArrayDeque(listOf(taskId))
                while (queue.isNotEmpty()) {
                    for (f in incoming[queue.removeFirst()].orEmpty()) {
                        val src = str(f["from"]) ?: continue
                        val ev = events[src]
                        if (ev?.get("type") == "startEvent") return trig(ev)
                        if (seen.add(src)) queue.add(src)
                    }
                }
                return null
            }

            private fun trig(e: Map<String, Any?>): String? {
                val def = str(e["def"]) ?: return null
                return def + (str(e["value"])?.let { " ${code(it)}" } ?: "")
            }

            private fun boundary(id: String): String {
                val e = events[id] ?: return "when boundary ${code(id)} fires"
                val host = str(e["attachedTo"])?.let { label(it, null) } ?: "its activity"
                val nm = str(e["name"])?.let { " (${em(it)})" } ?: ""
                val keeps = if (str(e["cancelActivity"]) == "false") ", which stays open" else ""
                return when (str(e["def"])) {
                    "error" -> "when $host fails with an error$nm"
                    "timer" -> "when $host is still open after ${code(str(e["value"]) ?: "?")}$nm$keeps"
                    null -> "when a boundary event fires on $host$nm"
                    else -> "when ${str(e["def"])} ${str(e["value"])?.let { code(it) } ?: ""} arrives during $host$nm$keeps".replace("  ", " ")
                }
            }

            /** Conditional flows out of a gateway that no user task's way in already shows: where else the process can go. */
            fun unusedBranches(): List<String> = flows.filter { f ->
                f !in used && str(f["from"]) in gateways && (str(f["condition"]) != null || f["default"] == true)
            }.map { f ->
                val from = str(f["from"])!!
                val to = str(f["to"]) ?: "?"
                val target = names[to]?.let { em(it) } ?: when {
                    to in gateways -> "an unnamed gateway"
                    events[to]?.get("type") == "endEvent" -> "the end"
                    else -> code(to)
                }
                "${label(from, f)} → $target " +
                    (str(f["condition"])?.let { "if ${code(condition(it))}${setBy(it, formUse)}" } ?: "otherwise (default flow)")
            }
        }

        // ---- actions ------------------------------------------------------------------------------

        private fun actions(L: MutableList<String>) {
            if (actions.isEmpty()) return
            L.add("### Actions defined in this project\n")
            L.add("Shown in an instance's toolbar (channel `quick-menu`/`quick-actions`) or under *More Actions* " +
                "(`menu`) — only where the action is bound to that case, process or task and the user may use it.\n")
            for (a in actions) {
                val key = str(a["key"])!!
                val parts = ArrayList<String>()
                (a["channels"] as? List<*>)?.mapNotNull { str(it) }?.takeIf { it.isNotEmpty() }
                    ?.let { parts.add("channels " + it.joinToString(", ") { c -> code(c) }) }
                str(a["scopeType"])?.let { parts.add("scope ${code(it)}") }
                (a["permissionGroups"] as? List<*>)?.mapNotNull { g -> (g as? Map<*, *>)?.get("permission-group")?.let { str(it) } ?: str(g) }
                    ?.takeIf { it.isNotEmpty() }?.let { parts.add("groups " + it.joinToString(", ") { g -> code(g) }) }
                str(a["formKey"])?.let { parts.add("asks for form " + formRef(it, "action ${code(key)}")) }
                str(a["botKey"])?.let { parts.add("runs bot ${code(it)}") }
                val nm = str(a["name"])?.let { "${em(it)} (${code(key)})" } ?: code(key)
                L.add("- $nm" + if (parts.isEmpty()) "" else " — " + parts.joinToString(" · "))
            }
            L.add("")
        }

        // ---- who sets what a condition reads -----------------------------------------------------

        /**
         * " ← outcome **Approve** on task *Assess damage*": the outcomes and form fields that write the
         * variables [cond] reads. A condition names a variable; the agent needs the button or the field.
         * Forms this case or process shows ([use]) come first; another form's writer is named by its key.
         */
        fun setBy(cond: String, use: Map<String, Set<String>>, whole: Boolean = false): String {
            // identifiers outside string literals: in `x == 'approve'` the variable is x, not approve
            val bare = cond.replace(Regex("'[^']*'|\"[^\"]*\""), " ")
            val vars = if (whole) listOf(cond) else Regex("(?<![\\w.$])([A-Za-z_][\\w]*)").findAll(bare).map { it.groupValues[1] }.distinct().toList()
            val hints = ArrayList<String>()
            for (v in vars) {
                // a form that names no outcome variable writes `form_<key>_outcome`, which the graph may not list
                val implicit = Regex("^form_(.+)_outcome$").find(v)?.groupValues?.get(1)
                    ?.takeIf { k -> forms[k]?.let { str(it["outcomeVariableName"]) == null } == true }
                    ?.let { listOf(mapOf<String, Any?>("model" to "form:$it", "via" to "formOutcome")) }.orEmpty()
                val ws = (writes[v].orEmpty().filter { str(it["model"])?.startsWith("form:") == true &&
                    str(it["via"]) in setOf("formOutcome", "formField") }).ifEmpty { implicit }
                if (ws.isEmpty()) continue
                val local = ws.filter { str(it["model"])!!.removePrefix("form:") in use }
                // An outcome writer counts only when one of its values is what the condition compares with.
                val literal = Regex("'[^']*'|\"[^\"]*\"").containsMatchIn(cond)
                val candidates = (local.ifEmpty { ws }).filter { w ->
                    str(w["via"]) != "formOutcome" || !literal || objs(forms[str(w["model"])!!.removePrefix("form:")]?.get("outcomes"))
                        .any { o -> str(o["value"])?.let { Regex("['\"]${Regex.escape(it)}['\"]").containsMatchIn(cond) } == true }
                }
                for (w in candidates.take(2)) {
                    val fk = str(w["model"])!!.removePrefix("form:")
                    val where = (use[fk] ?: formTasks[fk])?.take(2)?.joinToString(" or ") ?: "of form ${code(fk)}"
                    hints.add(when (str(w["via"])) {
                        "formOutcome" -> {
                            // The outcome whose value the condition compares with — or, when it compares
                            // with `!=`/`notEquals`, every outcome but that one.
                            val pos = ArrayList<String>(); val neg = ArrayList<String>()
                            for (o in objs(forms[fk]?.get("outcomes"))) {
                                val value = str(o["value"]) ?: continue
                                val lit = "['\"]${Regex.escape(value)}['\"]"
                                if (!Regex(lit).containsMatchIn(cond)) continue
                                val x = Regex.escape(v)
                                val negated = Regex("notEquals\\(\\s*$x\\s*,\\s*$lit|$x\\s*!=\\s*$lit|$lit\\s*!=\\s*$x").containsMatchIn(cond)
                                (if (negated) neg else pos).add(strong(str(o["label"]) ?: value))
                            }
                            "outcome " + when {
                                pos.isNotEmpty() -> pos.joinToString(" / ") + " "
                                neg.isNotEmpty() -> "other than " + neg.joinToString(" / ") + " "
                                else -> ""
                            } + where
                        }
                        else -> "field " + (str(w["elementName"])?.takeIf { !it.contains("{{") }?.let { em(it) + " " } ?: "") +
                            "(${code(str(w["element"]) ?: v)}) $where"
                    })
                }
                if (hints.size >= 3) break
            }
            return if (hints.isEmpty()) "" else " ← " + hints.distinct().joinToString(", ")
        }

        // ---- forms --------------------------------------------------------------------------------

        /** `` `claimForm` `` — and remember it, with where it is used, for the form details below. */
        private fun formRef(key: String, usedBy: String): String {
            formsUsed.getOrPut(key) { LinkedHashSet() }.add(usedBy)
            return code(key)
        }

        /**
         * What it takes to get through each form the map mentions: required fields and their rules,
         * outcomes, and every component whose visibility or enabled state is an expression. Subforms are
         * followed, because a required field in an embedded form blocks the outcome just the same.
         */
        private fun formDetails(L: MutableList<String>) {
            if (formsUsed.isEmpty()) return
            L.add("### Forms — what it takes to get through\n")
            L.add("_Outcomes and *Complete* wait for a valid form (forms saved by Design 3.8 or later, §5). " +
                "`{{…}}` conditions are evaluated in the browser against the form's values._\n")
            val done = HashSet<String>()
            val plain = ArrayList<String>()
            val queue = ArrayDeque(formsUsed.keys)
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (!done.add(key)) continue
                val f = forms[key]
                val usedBy = formsUsed[key].orEmpty()
                val where = if (usedBy.isEmpty()) "" else " — " + usedBy.joinToString("; ")
                if (key.contains("\${") || key.contains("#{")) {
                    L.add("- ${code(key)}$where — _chosen at runtime by this expression: read the variable on the running task (§3) to know which form opens_")
                    continue
                }
                if (f == null) {
                    L.add("- ${code(key)}$where — _not found in this repository (deployed from elsewhere, or a broken reference)_")
                    continue
                }
                val nm = str(f["name"])?.let { " " + em(it) } ?: ""
                val D = form(key, f)
                val subforms = (f["subforms"] as? List<*>).orEmpty().mapNotNull { str(it) }.distinct()
                if (subforms.isNotEmpty()) {
                    D.add("  - embeds: " + subforms.joinToString(", ") { code(it) } + " (its required fields count too)")
                    for (s in subforms) {
                        formsUsed.getOrPut(s) { LinkedHashSet() }.add("embedded in ${code(key)}")
                        queue.addLast(s)
                    }
                }
                if (D.isEmpty()) plain.add(key) else { L.add("- ${code(key)}$nm$where"); L.addAll(D) }
            }
            // Forms with nothing to fill in, choose or press: named once, so the list above stays the ones that matter.
            if (plain.isNotEmpty()) L.add("- Nothing required, conditional or clickable in: " + plain.joinToString(", ") { code(it) })
            L.add("")
        }

        /** The detail lines of one form; empty when there is nothing to fill in, choose or press. */
        private fun form(key: String, f: Map<String, Any?>): ArrayList<String> {
            val D = ArrayList<String>()
            val fields = objs(f["fields"])
            val isButton = { f2: Map<String, Any?> -> (f2["type"] as? String) in ModelJsonReader.BUTTON_TYPES }
            // The value path each component writes: what a `{{…}}` condition elsewhere in the form reads.
            val bound = LinkedHashMap<String, Map<String, Any?>>()
            for (fl in fields) {
                val path = (str(fl["stores"]) ?: str(fl["value"]))?.let { Regex("^\\{\\{\\s*([\\w$.]+)\\s*}}$").find(it)?.groupValues?.get(1) }
                    ?: str(fl["id"])?.takeIf { !isButton(fl) && fl["value"] == null }
                if (path != null) bound.putIfAbsent(path, fl)
            }
            fun linked(cond: String): String {
                val hits = bound.entries.filter { (path, _) ->
                    Regex("(?<![\\w$.])${Regex.escape(path)}(?![\\w$])").containsMatchIn(cond)
                }.take(2)
                if (hits.isEmpty()) return ""
                return " ← " + hits.joinToString(", ") { (_, fl) ->
                    val s = fl["settings"] as? Map<*, *>
                    if (s?.get("visible") == false || s?.get("autoExecute") != null) "filled by ${code(runtimeId(key, fl))} (runs by itself)"
                    else "field " + label(key, fl)
                }
            }

            // A disabled or ignored component is valid whatever its value, and so is everything inside a
            // disabled or ignored container: such a field blocks nothing, however `isRequired` reads.
            fun gates(fl: Map<String, Any?>): List<Map<*, *>> =
                listOfNotNull(fl["settings"] as? Map<*, *>) + objs(fl["containerGates"])
            val required = fields.filter { fl ->
                val r = fl["required"]
                (r == true || (r as? String)?.trim()?.equals("true", ignoreCase = true) == true || conditional(r) != null) &&
                    gates(fl).none { it["enabled"] == false || it["ignore"] == true }
            }
            if (required.isNotEmpty()) {
                D.add("  - required: " + required.joinToString(", ") { r ->
                    label(key, r) + (conditional(r["required"])?.let { " (if ${code(condition(it))})" } ?: "") +
                        (gates(r).firstNotNullOfOrNull { it["enabled"] as? String }?.let { " (only while enabled: ${code(condition(it))})" } ?: "")
                })
            }
            // The form runtime validates a required field whether it is shown or not: hidden and empty, it
            // keeps every outcome disabled and no error appears anywhere on the page. Not a trap when the
            // requirement carries its own condition, when an `ignore` covers it, or when the same condition
            // disables it (hidden ⇒ disabled ⇒ valid) — Design pairs `visible` with `enabled` that way.
            val hiddenRequired = required.mapNotNull { r ->
                if (conditional(r["required"]) != null) return@mapNotNull null
                val gs = gates(r)
                if (gs.any { it["ignore"] != null && it["ignore"] != false }) return@mapNotNull null
                val own = r["settings"] as? Map<*, *>
                val hider: Pair<Map<*, *>, Any?> = (listOfNotNull(own) + objs(r["containerGates"]))
                    .firstOrNull { it["visible"] == false || it["visible"] is String }?.let { it to it["visible"] } ?: return@mapNotNull null
                val enables = gs.mapNotNull { it["enabled"] as? String }
                if (hider.second is String && enables.any { same(it, hider.second as String) }) return@mapNotNull null
                val by = if (hider.first === own) "" else " (with ${code(str(hider.first["type"]) ?: "container")} ${code(str(hider.first["id"]) ?: "?")}" +
                    ((hider.second as? String)?.let { ", visible if ${code(condition(it))}" } ?: ", hidden") + ")"
                label(key, r) + by + (enables.firstOrNull()?.let { " (blocks only while enabled: ${code(condition(it))})" } ?: "")
            }
            if (hiddenRequired.isNotEmpty()) {
                D.add("  - ⚠ required but hidden at times: " + hiddenRequired.joinToString(", ") +
                    " — while hidden and empty the form stays invalid **with no visible error**; make it visible " +
                    "(fill what its condition reads), or fill whatever it depends on")
            }
            validationRules(f, fields).takeIf { it.isNotEmpty() }?.let { D.add("  - rules: " + it.joinToString(" · ")) }

            val outcomes = objs(f["outcomes"]).filter { str(it["value"]) != null }
            val reserved = setOf("__SAVE", "__CANCEL", "__CLAIM")
            val real = outcomes.filter { str(it["value"]) !in reserved }
            val save = outcomes.any { str(it["value"]) == "__SAVE" }
            if (real.isNotEmpty()) {
                val variable = if (f["doNotCreateOutcomeVariable"] == true) null
                    else str(f["outcomeVariableName"]) ?: "form_${key}_outcome"
                D.add("  - outcomes " + (variable?.let { "→ ${code(it)}" } ?: "(no variable written)") + ": " + real.joinToString(" · ") { o ->
                    val gs = ArrayList<String>()
                    (o["visible"] as? String)?.let { gs.add("visible if ${code(condition(it))}${linked(it)}") }
                    if (o["visible"] == false) gs.add("hidden")
                    (o["enabled"] as? String)?.let { gs.add("enabled only when ${code(condition(it))}${linked(it)}, not by form validity") }
                    if (o["enabled"] == true) gs.add("always enabled, even on an invalid form")
                    if (o["ignoreValidation"] == true) gs.add("works on an invalid form")
                    if (str(o["value"]) == "COMPLETE") gs.add("Work sends no outcome for this one, so the variable stays unset")
                    str(o["navigationUrl"])?.let { gs.add("then opens ${code(it)}") }
                    em(str(o["label"]) ?: str(o["value"])!!) + " " + code(str(o["value"])!!) +
                        if (gs.isEmpty()) "" else " (" + gs.joinToString("; ") + ")"
                } + if (save) " (its *Save* stores a draft without completing)" else "")
                // The form runtime greys outcomes out on an invalid form only for forms Design 3.8+ saved;
                // a Design-workspace export (`.json`) is stamped at publish, so its absence there says nothing.
                val dv = (f["designVersion"] as? Number)?.toInt()
                val deployable = str(f["file"])?.lowercase()?.let { it.endsWith(".form") || it.endsWith(".page") } == true
                if ((dv != null && dv < DESIGN_VERSION_GATES_OUTCOMES) || (dv == null && deployable)) {
                    D.add("  - the outcomes stay enabled while this form is invalid (it predates Design 3.8, or was written by " +
                        "hand): check the field errors before pressing one")
                }
            } else if (save) {
                D.add("  - only *Save* — it stores a draft; the task is completed some other way")
            }
            // Every conditional *button* a user can see — it is what decides whether the user can act at all.
            // Conditional fields only while they are few: the agent sees which fields are on screen, and on a
            // real project they are 40 % of this file. Past the limit the count and the query stand in for
            // them, so the list never pretends to be complete when it is not.
            val visible = { fl: Map<String, Any?> -> (fl["settings"] as? Map<*, *>)?.get("visible") != false }
            val gatedButtons = fields.filter { isButton(it) && visible(it) }.mapNotNull { gate(key, it, ::linked) }.distinct()
            if (gatedButtons.isNotEmpty()) D.add("  - conditional buttons: " + gatedButtons.joinToString("; "))
            val gatedFields = fields.filterNot(isButton).mapNotNull { gate(key, it, ::linked) }.distinct()
            when {
                gatedFields.isEmpty() -> {}
                gatedFields.size <= CONDITIONAL_FIELDS_LISTED -> D.add("  - conditional fields: " + gatedFields.joinToString("; "))
                else -> D.add("  - ${gatedFields.size} fields appear or unlock on a condition — list them with " +
                    "the first query in §10 (`FORM` = ${code(key)})")
            }
            val buttons = fields.filter { isButton(it) && it["type"] != "outcomeButton" && visible(it) }
                .mapNotNull { button(key, it) }.distinct()
            if (buttons.isNotEmpty()) D.add("  - buttons: " + buttons.joinToString("; "))
            // Hidden helpers that run by themselves matter only for what they fill — when a gate reads it.
            val gateTexts = (outcomes.flatMap { listOf(it["visible"], it["enabled"]) } +
                fields.flatMap { fl -> listOf((fl["settings"] as? Map<*, *>)?.get("visible"), (fl["settings"] as? Map<*, *>)?.get("enabled")) })
                .filterIsInstance<String>()
            val helpers = fields.filter { isButton(it) && !visible(it) && (it["settings"] as? Map<*, *>)?.get("autoExecute") != null }
                .mapNotNull { fl ->
                    val fills = (str(fl["stores"]) ?: str(fl["value"]))?.let { Regex("^\\{\\{\\s*([\\w$.]+)\\s*}}$").find(it)?.groupValues?.get(1) }
                        ?: return@mapNotNull null
                    if (gateTexts.none { Regex("(?<![\\w$.])${Regex.escape(fills)}(?![\\w$])").containsMatchIn(it) }) return@mapNotNull null
                    "${code(runtimeId(key, fl))} → fills ${code(fills)}"
                }.distinct()
            if (helpers.isNotEmpty()) D.add("  - runs by itself (hidden): " + helpers.joinToString("; "))
            return D
        }

        /** The validation each field checks — what makes a filled-in field still invalid. */
        private fun validationRules(f: Map<String, Any?>, fields: List<Map<String, Any?>>): List<String> {
            val out = ArrayList<String>()
            val dateDefault = str(f["defaultDateFormat"])
            for (fl in fields) {
                val v = fl["validation"] as? Map<*, *>
                val bits = ArrayList<String>()
                fun range(lo: Any?, hi: Any?, what: String) {
                    val a = Fmt.scalar(lo); val b = Fmt.scalar(hi)
                    when {
                        a != null && b != null -> bits.add("$what $a–$b")
                        a != null -> bits.add("$what at least $a")
                        b != null -> bits.add("$what at most $b")
                    }
                }
                if (v != null) {
                    str(v["pattern"])?.let { bits.add("must match ${code(it)} (the whole value)") }
                    str(v["mask"])?.let { bits.add("mask ${code(it)}") }
                    range(v["min"], v["max"], "value")
                    range(v["minLength"], v["maxLength"], "length")
                    if (v["minDate"] != null || v["maxDate"] != null) {
                        bits.add("date" + (str(v["minDate"])?.let { " from ${code(it)}" } ?: "") + (str(v["maxDate"])?.let { " up to ${code(it)}" } ?: ""))
                    }
                    range(v["minItems"], v["maxItems"], "items")
                    range(v["minFiles"], v["maxFiles"], "files")
                    (v["fractionSize"] as? Number)?.toInt()?.let { bits.add(if (it == 0) "a whole number" else "at most $it decimals") }
                    str(v["matchTo"])?.let { bits.add("must equal ${code(it)}") }
                    str(v["format"])?.let { bits.add("typed as ${code(it)}") }
                    for (cv in objs(v["custom"])) {
                        bits.add("custom check " + (str(cv["errorMsg"])?.let { "\"${inline(it)}\"" } ?: code(condition(str(cv["expression"]) ?: "?"))))
                    }
                }
                if (v?.get("format") == null && dateDefault != null && (str(fl["type"])?.lowercase()?.contains("date") == true)) {
                    bits.add("typed as ${code(dateDefault)} (the form's default)")
                }
                if (bits.isNotEmpty()) out.add(label(str(f["key"]) ?: "", fl) + " " + bits.joinToString(", "))
            }
            return out
        }

        /**
         * `*Label* `id`` — and, for the components the form deployer renames, the id Work puts on the page
         * (`data-flw-id="<formKey>_<id>"`), which is the one a locator needs.
         */
        private fun label(formKey: String, field: Map<String, Any?>): String {
            val id = str(field["id"]) ?: "?"
            val page = runtimeId(formKey, field)
            val ids = code(id) + if (page != id) " (on the page ${code(page)})" else ""
            val lbl = str(field["label"])
            return when {
                lbl == null || lbl == id -> ids
                lbl.contains("{{") -> "$ids (label computed)"
                else -> em(if (lbl.length > 80) lbl.take(80).trimEnd() + " …" else lbl) + " " + ids
            }
        }

        private fun runtimeId(formKey: String, field: Map<String, Any?>): String {
            val id = str(field["id"]) ?: "?"
            return if (str(field["type"]) in RENAMED_ON_DEPLOY && formKey.isNotEmpty() && !id.startsWith("${formKey}_")) "${formKey}_$id" else id
        }

        /** A component whose visible/enabled state is an expression — the ones that explain a missing or greyed-out control. */
        private fun gate(formKey: String, field: Map<String, Any?>, linked: (String) -> String): String? {
            val s = field["settings"] as? Map<*, *> ?: return null
            val parts = ArrayList<String>()
            (s["visible"] as? String)?.let { parts.add("visible if ${code(condition(it))}${linked(it)}") }
            (s["enabled"] as? String)?.let { if (!same(it, s["visible"])) parts.add("enabled if ${code(condition(it))}${linked(it)}") }
            if (s["enabled"] == false) parts.add("read-only")
            if (parts.isEmpty()) return null
            return "${label(formKey, field)} " + parts.joinToString(", ")
        }

        /** A visible button and what pressing it does, when the model says. */
        private fun button(formKey: String, field: Map<String, Any?>): String? {
            val callee = field["callee"] as? Map<*, *>
            val does = when (field["type"]) {
                "workAction" -> callee?.let { "runs action ${code(str(it["key"]) ?: "?")}" } ?: "runs an action"
                "restButton" -> "calls REST" + (callee?.let { " " + code(condition(str(it["key"]) ?: "?")) } ?: "")
                "workInvokeService" -> callee?.let { c ->
                    "calls service ${code(str(c["key"]) ?: "?")}" + (str(c["op"])?.let { o -> " · ${code(o)}" } ?: "")
                } ?: "calls a service"
                "workAgentButton" -> callee?.let { "asks agent ${code(str(it["key"]) ?: "?")}" } ?: "asks an agent"
                "createInstanceButton" -> "starts a new instance"
                "workUserEventListenerButton" -> "triggers a user event listener"
                "linkButton" -> "opens a link"
                "scriptButton" -> "runs a script in the browser"
                else -> callee?.let { "calls ${str(it["kind"])} ${code(str(it["key"]) ?: "?")}" }
            } ?: return null
            val auto = (field["settings"] as? Map<*, *>)?.get("autoExecute")?.let { " (runs by itself)" } ?: ""
            return "${label(formKey, field)} $does$auto"
        }

        private fun assignment(t: Map<String, Any?>): String? {
            val parts = ArrayList<String>()
            str(t["assignee"])?.let { parts.add("assignee ${code(it)}") }
            str(t["candidateGroups"])?.let { parts.add("groups ${codes(it)}") }
            str(t["candidateUsers"])?.let { parts.add("users ${codes(it)}") }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /** Who gets a manual-activation or listener button: the model's groups and users, when it names any. */
        private fun who(n: Map<String, Any?>): String? {
            val parts = ArrayList<String>()
            str(n["actionGroups"])?.let { parts.add("groups ${codes(it)}") }
            str(n["actionUsers"])?.let { parts.add("users ${codes(it)}") }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    /** Field types whose `data-flw-id` the form deployer prefixes with the form key. */
    private val RENAMED_ON_DEPLOY = setOf("workAction", "workInvokeService", "workAgentButton", "upload", "attachment")

    /** What happening to an onPart's source means, in the words a user would see it. */
    private fun verb(event: String?, rec: Map<String, Any?>?): String {
        val type = rec?.get("type") as? String
        return when (event?.lowercase()) {
            null, "" -> "fires"
            "complete" -> "completes"
            "occur" -> when (type) {
                "userEventListener" -> "is triggered (someone clicks it)"
                "milestone" -> "is reached"
                "timerEventListener" -> "fires"
                "eventListener" -> when (str(rec["listenerType"])) {
                    "variable" -> "fires (variable ${code(str(rec["variableName"]) ?: "?")} set)"
                    "signal" -> "receives its signal"
                    else -> "receives its event"
                }
                else -> "occurs"
            }
            "exit" -> "is exited"
            "terminate" -> "is terminated"
            "start" -> "starts"
            "create" -> "is created"
            "enable" -> "is enabled"
            "disable" -> "is disabled"
            "manualstart" -> "is started by hand"
            "fault" -> "fails"
            "reactivate" -> "is reactivated"
            else -> event
        }
    }

    /**
     * An expression on one line, and not more than [CONDITION_SHOWN] characters of it. Real sentry
     * conditions span a dozen lines; printed as written they break the list they sit in. Cut ones end in
     * an ellipsis — the full text is one query away (§10).
     */
    internal fun condition(text: String): String {
        val one = text.replace(WS, " ").trim()
        return if (one.length <= CONDITION_SHOWN) one else one.take(CONDITION_SHOWN).trimEnd() + " …"
    }

    /** Two `{{…}}`/`${…}` texts that differ only in whitespace. */
    private fun same(a: Any?, b: Any?): Boolean =
        a is String && b is String && a.replace(WS, "") == b.replace(WS, "")

    /** `isRequired` as an expression: the field blocks only while it holds. */
    private fun conditional(v: Any?): String? = (v as? String)?.takeIf { it.contains("{{") }

    /** A rule value: `true` for an unconditional rule, the condition text for a conditional one, null when absent. */
    private fun rule(v: Any?): Any? = when (v) {
        null, false -> null
        true -> true
        is String -> v.trim().ifEmpty { null }?.let { if (it.equals("true", ignoreCase = true)) true else condition(it) }
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private fun rules(node: Map<String, Any?>): Map<String, Any?> = node["rules"] as? Map<String, Any?> ?: emptyMap()

    private val WS = Regex("\\s+")

    /** A stage name that opens with a number of its own: "0. Setup", "Stage 3 – Review". */
    private val NUMBERED = Regex("^\\s*(?:stage\\s*)?(\\d+)\\b", RegexOption.IGNORE_CASE)

    /** Placed after a task that names nobody; the §9 legend says what it means. */
    private const val UNASSIGNED = "assigned at runtime"

    @Suppress("UNCHECKED_CAST")
    private fun objs(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

    /**
     * A non-blank value on one line, or null — never the text `null`. One line because names come from
     * models, and a name with a line break (Design wraps long names that way) would otherwise start a
     * heading or a list item of its own in the middle of §9.
     */
    private fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v.replace(WS, " ").trim().ifEmpty { null }
        is Map<*, *>, is Collection<*> -> null
        else -> Fmt.scalar(v)
    }

    /** A code span that survives backticks inside it (CommonMark: a longer fence, padded). */
    internal fun code(s: String): String {
        val one = s.replace(WS, " ").trim()
        if ('`' !in one) return "`$one`"
        val run = Regex("`+").findAll(one).maxOf { it.value.length }
        val fence = "`".repeat(run + 1)
        return "$fence $one $fence"
    }

    /** Model text placed in running Markdown: the characters that would open emphasis, code or HTML are escaped. */
    internal fun inline(s: String): String = s.replace(WS, " ").trim()
        .replace("\\", "\\\\").replace("*", "\\*").replace("`", "\\`").replace("<", "\\<")

    private fun em(s: String) = "*${inline(s)}*"
    private fun strong(s: String) = "**${inline(s)}**"

    /**
     * A path as one shell word, for the commands §10 prints. A plain path stays as it is, so the common
     * case reads cleanly; one with a space or a shell character is double-quoted, and a `~/` path becomes
     * `"$HOME/…"` because a quoted `~` does not expand.
     */
    internal fun shellArg(path: String): String {
        val home = path.startsWith("~/")
        val rest = if (home) path.removePrefix("~/") else path
        if (SHELL_SAFE.matches(rest)) return path
        val body = rest.replace(Regex("[\"\\\\$`]")) { "\\" + it.value }
        return if (home) "\"\$HOME/$body\"" else "\"$body\""
    }

    private val SHELL_SAFE = Regex("[A-Za-z0-9_./+@%,:=-]*")

    /** `a,b` → `` `a`, `b` `` — group lists are stored comma-separated. */
    private fun codes(csv: String): String =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { code(it) }

    /** At most [max] of them, then `+N more` — the graph holds the whole list. */
    private fun codes(csv: String, max: Int): String {
        val all = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return all.take(max).joinToString(", ") { code(it) } + if (all.size > max) " +${all.size - max} more" else ""
    }
}
