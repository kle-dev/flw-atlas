package com.flowable.atlas.render

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.script.ScriptBindingsCatalog
import com.flowable.atlas.script.ScriptContext
import java.io.File

/**
 * CLAUDE.md generator: a generic Flowable primer + this project's discovered facts.
 *
 * What goes in follows one test — is it an instruction, or a fact the agent cannot get from the repo?
 * Inventory, counts and directory layouts fail it (they are in the summary, and a repository overview is
 * the one kind of context file content measured not to help: Gloaguen et al., *Evaluating AGENTS.md*,
 * 2026); the wiring examples, the known issues, the Design-vs-repo convention and the expression
 * catalogs pass it. The generic §1–§3 / §5 blocks are the `CLAUDE_PLATFORM` / `CLAUDE_RULES` constants,
 * shared with [renderGeneric] so `CLAUDE.template.md` cannot drift. No golden exists;
 * `RenderersSmokeTest` and `ClaudeFactsTest` pin the invariants.
 */
object ClaudeRenderer {

    /**
     * Where the generated file sits and what exists next to it.
     *
     * The file is meant to become the project's `CLAUDE.md`, i.e. to be read from the repository root,
     * while its siblings (`<name>.summary.md`, `.graph.json`, …) stay in the Atlas output directory. The
     * two only coincide when Atlas writes into the project itself, so the file needs to know [outDir] to
     * spell the sibling paths from the root — and whether the siblings were written at all: `--claude`
     * alone writes nothing but this file, and the plugin lets the user untick them.
     *
     * `outDir == null` means "unknown": sibling names stay bare, as they were before this existed.
     */
    data class Layout(val outDir: File? = null, val siblings: Boolean = true)

    @Suppress("UNCHECKED_CAST")
    fun render(result: Map<String, Any?>, root: File, layout: Layout = Layout()): String {
        val name = splitextName(File(root.path.trimEnd('/')).absoluteFile.name).ifEmpty { "project" }
        val s = result["stats"] as Map<String, Any?>

        // Sibling paths as seen from the project root, which is where a CLAUDE.md is read from.
        val rootAbs = File(root.path.trimEnd('/').ifEmpty { "." }).absoluteFile.normalize()
        val outAbs = layout.outDir?.absoluteFile?.normalize()
        val inside = outAbs != null && rootAbs.isDirectory && outAbs.startsWith(rootAbs)
        val prefix = if (inside) {
            val rel = rootAbs.toPath().relativize(outAbs!!.toPath()).toString().replace(File.separatorChar, '/')
            if (rel.isEmpty() || rel == ".") "" else "$rel/"
        } else ""
        fun art(suffix: String) = "$prefix$name.$suffix"
        val nodes = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        val byType = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (n in nodes) byType.getOrPut(n["type"] as String) { ArrayList() }.add(n)
        fun bt(t: String): List<Map<String, Any?>> = byType[t] ?: emptyList()
        val MODEL = setOf("app", "process", "case", "decision", "form", "page", "dataObject", "dataDictionary",
            "service", "agent", "channel", "event", "action", "query", "template", "sequence",
            "securityPolicy", "variableExtractor", "masterData", "document", "dashboardComponent")

        val digits = Regex("\\d+")

        fun folder(f: Any?): String {
            val fs = (if (truthy(f)) f.toString() else "").substringBefore("!")
            return if (fs.contains("/")) fs.substringBeforeLast("/") else "."
        }

        /**
         * The naming pattern a model type follows, e.g. ``process `DEMO-P#` (e.g. `DEMO-P041`)``.
         *
         * Only stated when there is actually a pattern: with one model per type it used to emit
         * ``process `orderProcess` (e.g. `orderProcess`)`` — the same string twice, presented as a
         * convention. A convention needs either digits that vary or two keys that agree.
         */
        fun keypat(t: String): String? {
            val keys = bt(t).mapNotNull { val k = it["key"]; if (truthy(k)) k as String else null }
            if (keys.isEmpty()) return null
            val counts = LinkedHashMap<String, Int>()
            for (k in keys) { val p = k.replace(digits, "#"); counts[p] = (counts[p] ?: 0) + 1 }
            val top = counts.entries.maxByOrNull { it.value }!!
            val pat = top.key
            val example = keys.firstOrNull { it.replace(digits, "#") == pat } ?: keys[0]
            if (top.value < 2 && pat == example) return null      // nothing generalizes from one name
            return if (pat == example) "`$pat`" else "`$pat` (e.g. `$example`)"
        }

        val L = ArrayList<String>()
        L.add("# CLAUDE.md — `$name` (Flowable solution project)\n")
        // Claude Code loads `CLAUDE.md`, never `<name>.CLAUDE.md`, so the file has to say how it gets there.
        if (layout.siblings) {
            val import = if (prefix.isNotEmpty()) ", or keep your own `CLAUDE.md` and import this file " +
                    "with a line `@$prefix$name.CLAUDE.md`" else ""
            L.add("_Auto-generated by Flowable Atlas. Use it as the project's `CLAUDE.md`: copy it to the " +
                    "repository root$import (Claude Code loads `CLAUDE.md`, never `$name.CLAUDE.md`). Hand " +
                    "edits belong in your copy; regenerate with `./atlas <project-dir> <out-dir>` and diff._\n")
        } else {
            L.add("_Auto-generated by Flowable Atlas (`--claude`). Hand edits are yours to keep; " +
                    "regenerate with `--claude --stdout` and diff._\n")
        }

        // §0 — start here, pointing at the actual artifact filenames
        L.add("## 0. Understand this project — start here\n")
        L.add("Don't guess — build the picture in order:")
        val steps = ArrayList<String>()
        if (!layout.siblings) {
            // `--claude` alone writes only this file: sending the agent to a summary that does not exist
            // costs it a failed read and the trust in the rest of the list.
            steps.add("Generate the Atlas artifacts first: `java -jar <atlas-cli>.jar <project-dir> --all -o <out-dir>` " +
                    "(or `./atlas <project-dir> <out-dir>`). It writes `$name.summary.md`, `$name.overview.md`, " +
                    "`$name.graph.json` and `$name.explorer.html` into `<out-dir>`; the steps below read them from there.")
        }
        steps.add("Read **`${art("summary.md")}`** — apps, inventory, entry points, integrations, hotspots, " +
                "open findings. A few KB; read it whole.")
        // The launcher always adds `--all`, which `--slice` refuses; only the jar form works as typed.
        steps.add("For one model, ask for its slice: `java -jar <atlas-cli>.jar <project-dir> --slice process:<key> --stdout` — " +
                "what it uses, **who uses it**, and the findings that touch it, on one page.")
        steps.add("For the wider picture, **`${art("overview.md")}`** — every model in execution order, the " +
                "access map, the data layer, the findings.")
        // Telling an agent to "query the graph" without saying how, or how big it is, invites it to read
        // the file: on a real project that is megabytes and blows the context it was meant to save.
        val g = art("graph.json")
        steps.add("**Query — never read — `$g`** (${s["nodes"] ?: 0} nodes, " +
                "${s["edges"] ?: 0} edges; megabytes on a large project). Its `_schema` key documents the " +
                "shape; the recipes that matter:\n" +
                "   ```bash\n" +
                "   jq '.graph.nodes[] | select(.id==\"process:KEY\") | .usedBy' $g  # who references it\n" +
                "   jq '.graph.edges[] | select(.s==\"process:KEY\")'          $g  # what it references\n" +
                "   jq '.processes[] | select(.key==\"KEY\")'                  $g  # the model body\n" +
                "   jq '.findings[] | select(.severity==\"error\")'            $g  # what is broken\n" +
                "   ```\n" +
                "   A model node's body is stored once in its bucket; the node carries " +
                "`data.dataIn` naming that bucket. Every node carries `usedBy`, so relationships are " +
                "traversable in both directions.")
        steps.add("Open **`${art("explorer.html")}`** for the clickable view (for humans).")
        steps.add("Read the actual source to verify, then implement.")
        steps.forEachIndexed { i, step -> L.add("${i + 1}. $step") }
        if (layout.siblings && outAbs != null && !inside) {
            // The launcher's default output directory is next to the Atlas checkout, not in the project:
            // from the repository root none of the names above resolve unless the file says where they are.
            // `~` rather than the literal home: this file gets committed, and a user name is not its business.
            val home = System.getProperty("user.home")?.trimEnd('/')
            val shown = if (!home.isNullOrEmpty() && outAbs.path.startsWith("$home/")) "~" + outAbs.path.removePrefix(home)
                        else outAbs.path
            L.add("")
            L.add("The Atlas files live in `$shown` (as generated); if they are missing, regenerate them: " +
                    "`./atlas <project-dir> <out-dir>`, or in the IDE *Tools → Flowable Atlas → Generate*.")
        }
        L.add("")

        L.add(CLAUDE_PLATFORM)

        // §4 — discovered facts. Only what an agent acts on: the inventory, the counts and the entry
        // points are in the summary, which §0 has it read whole, and an overview an agent can derive is
        // the one kind of context that measurably does not help (Gloaguen et al. 2026).
        L.add("## 4. This project (auto-discovered by Atlas)\n")
        val mf = LinkedHashMap<String, Int>()
        val archives = LinkedHashSet<String>()
        for (n in nodes) if (n["type"] in MODEL && truthy(n["file"])) {
            val f = n["file"].toString()
            if (f.contains("!")) archives.add(f.substringBefore("!"))
            else { val d = folder(f); mf[d] = (mf[d] ?: 0) + 1 }
        }
        val jf = LinkedHashMap<String, Int>()
        for (n in bt("java")) if (truthy(n["file"])) { val d = folder(n["file"]); jf[d] = (jf[d] ?: 0) + 1 }
        val javaDirs = jf.entries.sortedByDescending { it.value }.take(3).map { "`${it.key}/`" }
        val modelDirs = mf.entries.sortedByDescending { it.value }.take(5).map { "`${it.key}/`" }
        val where = ArrayList<String>()
        if (javaDirs.isNotEmpty()) where.add("custom Java goes in ${javaDirs.joinToString(", ")}")
        if (archives.isNotEmpty()) {
            // §3 hedges ("unless this project's convention is to edit the model files directly"); here the
            // hedge is resolved from what was actually found, so the agent is told rather than sent to check.
            val loose = if (modelDirs.isNotEmpty()) ", plus unpacked model files under ${modelDirs.joinToString(", ")}" else ""
            where.add("the models are Design exports packed in ${archives.take(3).joinToString(", ") { "`$it`" }}$loose — " +
                    "never edit the exports; describe the model change instead (§3)")
        } else if (modelDirs.isNotEmpty()) {
            where.add("the models are unpacked files under ${modelDirs.joinToString(", ")} — check `git log` on one " +
                    "of them before deciding whether this project edits models by hand or re-exports from Design")
        }
        if (where.isNotEmpty()) {
            L.add("- **Where to write, what not to touch:** " +
                    where.joinToString(". ") { it.replaceFirstChar(Char::uppercase) } + ".")
        }
        val convs = listOf("process", "case", "form", "decision", "service", "dataObject", "query")
            .mapNotNull { t -> keypat(t)?.let { "$t $it" } }
        if (convs.isNotEmpty()) L.add("- **Key conventions:** " + convs.joinToString("; "))

        // build / version detection
        if (root.isDirectory) {
            fun fileExists(vararg p: String): Boolean = File(root, p.joinToString("/")).exists()
            val b = ArrayList<String>()
            if (fileExists("mvnw") || fileExists("pom.xml")) {
                b.add("Maven — `./mvnw clean install -DskipTests -T 1C`; tests `./mvnw test -pl <module> -am -Dtest=Class` " +
                        "(use `-am` on scoped builds)")
            }
            if (fileExists("gradlew") || fileExists("build.gradle")) b.add("Gradle — `./gradlew build`")
            for (fe in listOf("frontend", "ui", "web", "src/main/frontend")) {
                if (fileExists(fe, "package.json")) { b.add("Frontend — `cd $fe && yarn install && yarn build`"); break }
            }
            if (b.isNotEmpty()) L.add("- **Build/test:** " + b.joinToString("; "))
            // run & verify loop (detect frontend start script + a backend app module)
            val run = ArrayList<String>()
            val feDir = listOf("frontend", "ui", "web", "src/main/frontend").firstOrNull { fileExists(it, "package.json") }
            if (feDir != null) {
                try {
                    val pkg = MiniJson.parse(File(root, "$feDir/package.json").readText()) as Map<String, Any?>
                    val scripts = (pkg["scripts"] as? Map<String, Any?>) ?: emptyMap()
                    val cand = scripts.keys.filter { it.startsWith("start") }
                        .ifEmpty { scripts.keys.filter { it == "dev" || it == "serve" } }
                    if (cand.isNotEmpty()) run.add("frontend `cd $feDir && yarn ${cand.sorted().first()}`")
                } catch (_: Exception) { }
            }
            val appmod = try {
                (root.listFiles()?.map { it.name }?.sorted() ?: emptyList()).firstOrNull { d ->
                    File(root, d).isDirectory &&
                            (d.endsWith("-app") || d.endsWith("-work") || d.endsWith("-workflow") || d.endsWith("-runtime"))
                }
            } catch (_: Exception) { null }
            if (appmod != null) run.add("backend = Spring Boot app in `$appmod/`")
            // Without a detected start script or app module the line used to fall back to a sentence
            // that fits every Flowable project and so tells this one nothing.
            if (run.isNotEmpty()) {
                L.add("- **Run & verify:** " + run.joinToString("; ") +
                        "; the app auto-deploys its bundled models on startup.")
            }
            // The Flowable version decides which engine APIs exist — the file's own pitfall list says so
            // ("Flowable APIs differ across versions"), and it used to be detected from `pom.xml` only,
            // so every Gradle project was told "not auto-detected" about the one fact it warned about.
            var ver: String? = null
            fun accept(cand: String?): Boolean {
                val v = cand?.trim() ?: return false
                if (v.isEmpty() || !Regex("^[6-9]\\.").containsMatchIn(v)) return false
                ver = v
                return true
            }
            try {
                if (fileExists("pom.xml")) {
                    val pom = File(root, "pom.xml").readText()
                    val m = Regex("<flowable(?:[.-]engine|[.-]bom|[.-]platform)?\\.version>\\s*([0-9][^<\\s]+)").find(pom)
                        ?: Regex("(?:com|org)\\.flowable\\b[^<]*</groupId>\\s*<artifactId>[^<]*</artifactId>" +
                                "\\s*<version>\\s*([0-9][^<\\s]+)", RegexOption.DOT_MATCHES_ALL).find(pom)
                    accept(m?.groupValues?.get(1))
                }
                // Gradle: a `flowableVersion = "8.x"`-style property, a version catalog entry, or the
                // version on a `com.flowable:…` dependency coordinate.
                if (ver == null) {
                    for (f in listOf("gradle.properties", "gradle/libs.versions.toml",
                        "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")) {
                        if (!fileExists(*f.split("/").toTypedArray())) continue
                        val text = File(root, f).readText()
                        val hit = Regex("(?i)flowable[-_.]?version\\s*[=:]\\s*[\"']?([0-9][^\"'\\s,)]*)").find(text)
                            ?: Regex("(?:com|org)\\.flowable[^\"']*:([0-9][^\"'\\s:]*)[\"']").find(text)
                        if (accept(hit?.groupValues?.get(1))) break
                    }
                }
            } catch (_: Exception) { ver = null }
            // "not auto-detected" was a line about the absence of a fact; §5 already says to check the deps.
            if (!ver.isNullOrEmpty()) L.add("- **Flowable version:** $ver")
        }

        // Concrete wiring examples, auto-picked from the resolved graph — mirror these for new code.
        val rr = result["resolvedRefs"] as? List<Map<String, Any?>> ?: emptyList()
        fun first(pred: (Map<String, Any?>) -> Boolean): Map<String, Any?>? = rr.firstOrNull(pred)
        fun cls(fqn: Any?): String = if (truthy(fqn)) (fqn as String).substringAfterLast(".") else pystr(fqn)

        val examples = ArrayList<String>()
        val d = first { it["rel"] == "serviceTask-delegate" && truthy(it["targetFqn"]) }
        if (d != null) {
            examples.add("- **Delegate:** process `${d["from"]}` → `\${${d["value"]}}` → " +
                    "`${cls(d["targetFqn"])}` (`${d["targetFqn"]}`)")
        }
        val ls = first {
            val rel = it["rel"] as String
            (rel.startsWith("taskListener") || rel.startsWith("executionListener") ||
                    rel.startsWith("planItemLifecycleListener")) && truthy(it["targetFqn"])
        }
        if (ls != null) examples.add("- **Listener:** `${ls["from"]}` → `${cls(ls["targetFqn"])}` (${ls["rel"]})")
        val cm = first { (it["rel"] as String).startsWith("calls ") && truthy(it["targetFqn"]) }
        if (cm != null) {
            val method = (cm["rel"] as String).substring(6).trimEnd('(', ')')
            examples.add("- **Expression → method:** `${cm["from"]}` → `\${${cm["value"]}.$method(…)}` → " +
                    "`${cls(cm["targetFqn"])}`")
        }
        val edges = (result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>
        val bote = edges.firstOrNull { it["rel"] == "bot" && (it["t"] as String).startsWith("java:") }
        if (bote != null) {
            examples.add("- **Bot:** action `${(bote["s"] as String).substringAfter(":")}` → " +
                    "`${(bote["t"] as String).substringAfterLast(".")}` (BotService)")
        }
        val rc = (result["restCalls"] as? List<Map<String, Any?>> ?: emptyList())
            .firstOrNull { truthy(it["matches"]) && it["url"] is String }
        if (rc != null) {
            val u = rc["url"] as String
            val url = if (u.length > 60) u.take(60) + "…" else u
            val matches = rc["matches"] as List<*>
            examples.add("- **Form → REST:** `${rc["source"]}` calls `${rc["method"]} $url` → ${matches[0]}")
        }
        if (examples.isNotEmpty()) {
            L.add("\n**Wiring examples in this project — mirror these for new code:**")
            L.addAll(examples)
        }

        // Known problems. An agent that cannot see these copies them: it mirrors a broken expression as
        // if it were the house style, or "fixes" a script that was already reported. Counts + the worst
        // few here; the itemized list with file/line is in the overview and in `findings` in graph.json.
        val checks = result["checks"] as? Map<String, Any?> ?: emptyMap()
        val open = (checks["open"] as? Number)?.toInt() ?: 0
        if (open > 0) {
            val findings = (result["findings"] as? List<Map<String, Any?>> ?: emptyList())
                .filter { it["waived"] == null }
            val headline = Fmt.healthHeadline(result["stats"] as? Map<*, *>).ifEmpty { "$open" }
            L.add("\n**Known issues in this project ($headline) — do not copy these patterns, and expect " +
                    "them when something behaves oddly:**")
            // The per-check tallies are the summary's Health block verbatim; the worst few are what an
            // agent needs in front of it before it mirrors one of them as the house style.
            for (f in findings.filter { it["severity"] == "error" }.take(3)) {
                val at = listOfNotNull(f["label"]?.toString(), f["element"]?.toString())
                    .joinToString(" · ")
                L.add("- ⚠ ${f["message"]}" + (if (at.isEmpty()) "" else " — `$at`"))
            }
            L.add("- Full list: `${art("overview.md")}` §Findings, or `findings` in `${art("graph.json")}`.")
        }
        // An agent that re-reports an accepted finding wastes the reader's attention on a decision that
        // was already made, so the context says the decision exists and where it is written down.
        (checks["waived"] as? Number)?.toInt()?.takeIf { it > 0 }?.let { n ->
            L.add("- $n finding(s) are waived in `${com.flowable.atlas.graph.Waivers.FILE_NAME}` — " +
                "accepted deliberately, with a reason each. Do not re-report them.")
        }

        // A real HTML comment: Claude Code strips block comments before loading, so the reminder costs
        // the human editor nothing and the agent no tokens. In backticks it was text, and paid for.
        L.add("")
        L.add("<!-- Add house rules: code style, where business logic goes, what NOT to touch, " +
                "how this app is deployed/published to Work. -->\n")

        L.add(CLAUDE_RULES)
        // Reference material goes last: it is looked up, not read top to bottom.
        L.addAll(expressionCheatsheet(result))
        return L.joinToString("\n")
    }

    /**
     * `addCandidateGroup` + `addCandidateGroups` → `addCandidateGroup(s)`.
     *
     * These families dominate the `bpmn:`/`cmmn:`/`task:` namespaces, where naming both halves of a dozen
     * pairs costs a third of the list for no extra information. Sorted, deduplicated.
     */
    private fun foldPlurals(names: List<String>): List<String> {
        val all = names.distinct().toSet()
        val out = LinkedHashSet<String>()
        for (n in names.distinct().sorted()) {
            if (n.endsWith("s") && n.dropLast(1) in all) continue   // covered by its singular
            out.add(if ("${n}s" in all) "$n(s)" else n)
        }
        return out.toList()
    }

    /**
     * The expression and script surface an agent can actually call — from Atlas's own catalogs.
     *
     * This is the sharpest instance of the gap Atlas exists to close. `FlowableExpressionCatalog`,
     * `ScriptBindingsCatalog`, `ScriptPlatformApis` and `ScriptServiceApis` are a hand-curated inventory
     * of Flowable's EL namespaces, script bindings and platform beans — precisely the knowledge a model
     * lacks and invents instead. They were used only to *validate* expressions and to drive IDE
     * completion, so Atlas would tell you `${'$'}{vars:bogus()}` is wrong while never saying what is right.
     *
     * Names only, no prose: the point is to fix the vocabulary, and one line per namespace costs a
     * fraction of what a hallucinated function costs to debug. Project-specific functions discovered in
     * the repo are listed too, since those are the ones no amount of Flowable knowledge would supply.
     */
    private fun expressionCheatsheet(result: Map<String, Any?>): List<String> {
        val L = ArrayList<String>()
        L.add("## 6. Expressions & scripts — what you may call\n")
        L.add("_Needed only when you read or write `\${…}` / `{{…}}` expressions or scripts in models; skip " +
                "otherwise. Atlas validates against these catalogs, so anything outside them is very likely a " +
                "hallucination. Names only; check the docs for signatures._\n")

        // Complete lists, deliberately. A list cut at N with "(+25 more)" under a heading that calls
        // everything outside it a hallucination declares the 25 real functions hallucinations.
        fun all(names: List<String>) = names.joinToString(", ")

        L.add("**Backend `\${…}` (JUEL) — namespaced functions:**")
        for (prefix in FlowableExpressionCatalog.backendPrefixes().toSortedSet()) {
            val canonical = FlowableExpressionCatalog.resolvePrefix(prefix) ?: continue
            if (canonical != prefix) continue        // list each namespace once, under its canonical name
            val fns = FlowableExpressionCatalog.backendFunctionsForPrefix(prefix)
            if (fns.isEmpty()) continue
            val aliases = FlowableExpressionCatalog.backendPrefixes()
                .filter { it != prefix && FlowableExpressionCatalog.resolvePrefix(it) == canonical }
                .sorted()
            val head = "`$prefix:`" + (if (aliases.isEmpty()) "" else " (aka ${aliases.joinToString(", ") { "`$it:`" }})")
            L.add("- $head — " + all(foldPlurals(fns.map { it.name })))
        }
        val bare = FlowableExpressionCatalog.backendNoPrefixFunctions().map { it.name }
        if (bare.isNotEmpty()) L.add("- _(no prefix)_ — " + all(foldPlurals(bare)))
        val backendRoots = FlowableExpressionCatalog.roots(ExpressionDialect.BACKEND).map { it.name }.sorted()
        if (backendRoots.isNotEmpty()) L.add("- implicit objects — " + all(backendRoots))

        L.add("\n**Frontend `{{…}}` (forms/pages):**")
        val feMembers = FlowableExpressionCatalog.frontendMembers().map { it.name }
        if (feMembers.isNotEmpty()) {
            L.add("- `${FlowableExpressionCatalog.FRONTEND_NS}.` — " + all(foldPlurals(feMembers)))
        }
        val feRoots = FlowableExpressionCatalog.roots(ExpressionDialect.FRONTEND).map { it.name }.sorted()
        if (feRoots.isNotEmpty()) L.add("- implicit objects — " + all(feRoots))

        L.add("\n**Script bindings** (Groovy/JS in script tasks, listeners and bots) — what each context binds:")
        for (ctx in listOf(
            ScriptContext.BPMN_SCRIPT_TASK, ScriptContext.BPMN_TASK_LISTENER,
            ScriptContext.CMMN_SCRIPT_TASK, ScriptContext.ACTION_BOT,
        )) {
            val roots = ScriptBindingsCatalog.rootsFor(ctx).values.filter { !it.hidden }
            if (roots.isEmpty()) continue
            val scopes = roots.filter { !it.bean }.map { it.name }.sorted()
            L.add("- ${ctx.display} — " + all(scopes))
        }
        val beans = ScriptBindingsCatalog.rootsFor(ScriptContext.BPMN_SCRIPT_TASK).values
            .filter { it.bean && !it.hidden }.map { it.name }.sorted()
        if (beans.isNotEmpty()) {
            L.add("- platform beans (also injectable in Java) — " + all(beans))
        }

        // Whatever this project registers itself — the part no catalog can know.
        val custom = result["customFunctions"] as? Map<*, *>
        if (custom != null) {
            val lines = ArrayList<String>()
            (custom["namespaces"] as? Map<*, *>)?.forEach { (ns, members) ->
                lines.add("`$ns:` — " + Fmt.list(members))
            }
            (custom["flw"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { lines.add("`flw.` — " + Fmt.list(it)) }
            (custom["topLevel"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { lines.add("top-level — " + Fmt.list(it)) }
            if (lines.isNotEmpty()) {
                L.add("\n**This project's own expression functions** (discovered in the repo):")
                for (l in lines) L.add("- $l")
            }
        }
        L.add("")
        return L
    }

    /**
     * The project-independent primer on its own — the content of the repo's `CLAUDE.template.md`.
     *
     * That file used to be a second, hand-maintained copy of §1–§3/§5 with no test keeping it in step,
     * and it had already drifted (it pointed at `APP_SUMMARY.md` and `python3 flowable_atlas.py`, both
     * long gone, and documented a node field that does not exist). Now it is generated from the same
     * constants [render] uses, so the two cannot disagree: `ClaudeTemplateSyncTest` fails if the
     * committed file differs, and `./gradlew :core:updateGoldens` rewrites it.
     *
     * §4 is the only section that differs from a generated `<project>.CLAUDE.md`: here it is a FILL-IN
     * skeleton, there it is auto-discovered.
     */
    fun renderGeneric(): String {
        val L = ArrayList<String>()
        L.add("# CLAUDE.md — Flowable solution project (context for AI agents)\n")
        L.add("_Generic Flowable primer for AI agents. **Prefer the generated version:** " +
                "`atlas <project-dir>` writes `<project>.CLAUDE.md` — this same primer with §4 filled in " +
                "from the actual project (apps, inventory, where models/Java live, conventions, wiring " +
                "examples to mirror). Use this file only when you cannot run Atlas; then copy it to the " +
                "project root as `CLAUDE.md` (or `AGENTS.md`) and fill in §4 by hand._\n")
        L.add(CLAUDE_START_GENERIC)
        L.add(CLAUDE_PLATFORM)
        L.add(CLAUDE_FILL_IN)
        L.add(CLAUDE_RULES)
        return L.joinToString("\n")
    }

    /** Mirror of `os.path.splitext(base)[0]`: strip a trailing extension (last dot preceded by a non-dot). */
    private fun splitextName(base: String): String {
        val dot = base.lastIndexOf('.')
        if (dot < 0) return base
        for (i in 0 until dot) if (base[i] != '.') return base.substring(0, dot)
        return base
    }

    /** Python truthiness for `.get(...) or …` / `if x:` idioms (None/""/0/empty → false). */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun pystr(v: Any?): String = v?.toString() ?: "None"

    private val CLAUDE_PLATFORM = """## 1. What Flowable is (the mental model an LLM usually gets wrong)

Flowable is a Java process-automation platform. A solution project is custom Java + models that run
**on top of** the Flowable engines — you extend a platform, you don't build from scratch.

- **Work** = the **runtime *and* the end-user React frontend** (executes definitions, renders forms,
  hosts tasks/cases). Custom Java + REST controllers run here. **Design** = the visual modeler.
  Engines: BPMN (processes), CMMN (cases), DMN (decisions), Form, Content, IDM (users/groups), plus
  platform engines (data objects, actions, agents, indexing).

**Models vs Definitions (the key concept):** models are mutable design-time JSON; when an app is
published or exported and deployed, they become **immutable, versioned Definitions**. Everything is
referenced by **key** (process/case/form/decision key) — cross-references between models, from Java, and
from the frontend are all by key.

**In a solution project, models are authored in Design and *exported into this repo*** — the `.app`/`.zip`
and model files under `src/main/resources` are **exported build artifacts**, not the editing surface.
The Java app is built **together with** the bundled model and deploys it to Work on startup. The canonical
place to *change* a model is Flowable **Design**, then re-export.

## 2. How custom code attaches to models (extension points)

- **Service tasks / JavaDelegate** — `flowable:class="com.acme.X"` or `flowable:delegateExpression="${'$'}{bean}"` (a Spring `@Component`/`@Service`).
- **Expressions** — `${'$'}{bean.method(args)}` (backend, JUEL) in conditions/listeners/fields; `{{ ... }}` (frontend) in forms/pages.
- **Listeners** — Execution/Task/PlanItemLifecycle/CaseInstanceLifecycle/FlowableEventListener.
- **REST controllers** — `@RestController` endpoints the Work frontend (forms, data tables, buttons) calls.
- **Bots** (`BotService`) — invoked by **Actions** (`.action` models, via `botKey`).
- **Service-registry data objects** — `.data` backed by a `.service` (REST/DB); DB-backed ones map to
  **Liquibase** tables via `referencedLiquibaseModelKey` + `tableName`.
- **Forms** — bind fields to **variables**; outcomes drive flow; can call REST for options/data tables.
- **Queries** (`.query`) — index queries (tasks/case-instances/…), often gated by **user group**
  (`currentGroups?seq_contains(…)`).
- **Variables** — set in Java (`execution.setVariable(...)`), init-var mappings, in/out params, sequences; read in expressions.
- **Access** — candidate (starter) groups, task candidate groups/assignees, app/page permissions, security policies.

## 3. How models, code and deployment fit together (important — easy to get wrong)

- **Models are authored in Design, not here.** A modeler builds BPMN/CMMN/forms/etc. in Flowable
  **Design**, then **exports/publishes the app into this repo**. Treat the repo's model files as
  exported artifacts — don't hand-edit the deployed `.zip` as if you own it; model changes normally
  go back through Design and are re-exported.
- **Build = your Java + the bundled model, together.** The Maven build packages the custom Java **and**
  the exported app; on deploy/startup the app **auto-deploys** its definitions to Work.
- **Deploy via the built artifact, environment by environment** (dev → test → **prod**, via CI/CD). You
  typically do **not** publish from Design straight to Production — Design-publish is a dev-time
  convenience; production receives the built-and-deployed app.

**Your lane as an agent:** implement/adjust the **custom Java** (delegates, beans, listeners, REST
controllers, bots) to match the models, and **read** the models to understand the wiring. If a feature
needs a model change (new task/form/variable/decision), **say so explicitly and describe it** — it's
made in Design and re-exported, unless this project's convention is to edit the model files directly
(check existing commits/patterns first). Always mirror an existing similar case — find it via Atlas.
"""

    /** §0 for the standalone template — the generated file names its own sibling artifacts instead. */
    private val CLAUDE_START_GENERIC = """## 0. Understand this project — start here

Don't guess — build the picture in order:
1. Run **Flowable Atlas** on the project (`atlas <project-dir>`). It writes, next to each other:
   `<project>.summary.md` (compact orientation), `<project>.overview.md` (the full report),
   `<project>.graph.json` (the traversable model↔code graph) and `<project>.explorer.html`.
2. Read the **summary** — apps, inventory, entry points, integrations, hotspots, known issues.
3. Query the **graph** for specific questions (what calls X, who uses variable Y, which controller
   serves form Z) instead of reading everything.
4. Read the actual source to verify, then implement.
"""

    /** §4 for the standalone template: the facts a generated `<project>.CLAUDE.md` fills in itself. */
    private val CLAUDE_FILL_IN = """## 4. This project — `<!-- FILL IN -->`

> Atlas auto-fills all of this. Generate `<project>.CLAUDE.md` instead of hand-filling it.

- **Repo layout:** `<!-- where models live, where custom Java lives, where the frontend lives -->`
- **Key/naming conventions:** `<!-- e.g. processes ABC-P###, cases ABC-C###, forms ABC-F### -->`
- **Build/test:** `<!-- e.g. ./mvnw clean install -DskipTests -T 1C  /  ./mvnw test -pl <module> -am -->`
- **Run & verify:** `<!-- how to start it; the app auto-deploys its bundled models on startup -->`
- **Flowable version:** `<!-- matters: available APIs differ across versions -->`
- **Wiring examples to mirror:** `<!-- one real delegate, listener, bot, form→REST call -->`
- **House rules:** `<!-- code style, where business logic goes, what NOT to touch -->`
"""

    private val CLAUDE_RULES = """## 5. Rules for the agent

- **Understand before coding:** summary → graph → source. State which existing process/case/form/bean your feature builds on.
- **Verify, don't hallucinate:** Flowable APIs differ across versions — confirm class/method names against the actual dependencies and https://documentation.flowable.com; don't invent engine APIs.
- **Match model ↔ code:** a `delegateExpression`/`flowable:class` in a model needs the bean/class to exist (and vice-versa). The graph's unresolved references show mismatches.
- **Keys are contracts:** models/Java/frontend reference definitions by key. Before renaming a key, check the graph for who references it (both directions).
- **Respect access/security:** candidate groups, app/page permissions and security policies are part of the feature.
- **Variable scope:** `setVariable` writes to the process/case scope, `setVariableLocal` to the current execution/plan item — pick deliberately.
- **Minimal, consistent changes:** mirror existing patterns; touch only what's necessary.
"""
}
