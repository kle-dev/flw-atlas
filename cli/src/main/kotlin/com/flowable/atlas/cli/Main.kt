package com.flowable.atlas.cli

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.diagram.DiagramArtifacts
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.Baseline
import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.Findings
import com.flowable.atlas.graph.Waivers
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.parsing.ModelKinds
import com.flowable.atlas.render.ClaudeRenderer
import com.flowable.atlas.render.ErdHtmlRenderer
import com.flowable.atlas.render.ExplorerHtmlRenderer
import com.flowable.atlas.render.Fmt
import com.flowable.atlas.render.GraphJsonRenderer
import com.flowable.atlas.render.JUnitRenderer
import com.flowable.atlas.render.OverviewRenderer
import com.flowable.atlas.render.PlaywrightRenderer
import com.flowable.atlas.render.SliceRenderer
import com.flowable.atlas.render.SummaryRenderer
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone Flowable Atlas CLI — the JVM successor to `flowable_atlas.py`.
 *
 * A faithful, hand-rolled port of the Python `main(argv)` (`flowable_atlas.py` ~lines 4806-4917):
 * same positional `path`, the mutually-exclusive `--all/--json/--html/--summary/--claude` modes, the
 * five `--all` artifact names, the `-o/--output`, `--stdout`, `--open`, `--expr-allowlist`,
 * `--custom-functions`, `--no-custom-functions`, `-v/--verbose`, `-q/--quiet` flags, the exit codes
 * ([EXIT_OK] … [EXIT_FAILED]) and the verbatim stderr status line. Argument parsing is done by hand (no
 * external arg library) to keep the runtime dependency-free.
 */

fun main(args: Array<String>): Unit = exitProcess(run(args))

// The exit codes, as --help and site/pages/cli.md state them. A pipeline branches on them, so each means
// one thing: 1 in particular is a verdict about the project, never a symptom of a broken run.
/** Success. */
private const val EXIT_OK = 0
/** A gate failed — a `--fail-on` finding matched, or `--fail-on-stale-waivers` found a stale waiver. Every artifact was written. */
private const val EXIT_GATE = 1
/** Misuse: the command line asks for something that cannot be done. Found before or instead of writing anything. */
private const val EXIT_MISUSE = 2
/** The run failed once under way — an output could not be written, or Atlas itself broke. Artifacts may be missing. */
private const val EXIT_FAILED = 3

/** Where `--all` writes without `-o`: the launcher's layout, relative to the working directory. */
private const val DEFAULT_ALL_DIR = "atlas-output"

/** Codepoints reproduced verbatim from the Python status line / `--all` log. */
private const val MIDDLE_DOT = '·'   // ·
private const val WARN_SIGN = '⚠'    // ⚠
private const val EM_DASH = '—'      // —
private const val CHECK = '✓'        // ✓

/** Write a line to stderr as UTF-8 (the status line carries ·/⚠/—/✓). */
private fun errln(s: String) {
    System.err.write((s + "\n").toByteArray(Charsets.UTF_8))
    System.err.flush()
}

/** The command line, parsed. */
private class Options {
    var path: String? = null
    var output: String? = null
    var all = false; var json = false; var html = false; var erd = false; var summary = false; var claude = false
    var playwright = false
    var claudeTemplate = false
    var stdout = false; var open = false; var noCustom = false; var quiet = false
    var pretty = false
    var slice: String? = null
    var verbose = 0
    var exprAllowlist = ""
    var customFunctions: String? = null
    var failOnTerms: List<String> = emptyList()
    var waiversPath: String? = null; var noWaivers = false; var failOnStaleWaivers = false
    var waiverAuthor: String? = null
    var annotate: String? = null
    var baseline: String? = null
    var junit: String? = null
}

/** The flags that take no value: `--all=foo` or `--stdout=false` is a misuse, not a silent `--stdout`. */
private val BOOLEAN_FLAGS = setOf(
    "--all", "--json", "--html", "--erd", "--summary", "--claude", "--playwright", "--claude-template", "--pretty",
    "--stdout", "--open", "--no-custom-functions", "--no-waivers", "--fail-on-stale-waivers", "--verbose", "--quiet",
    "--help",
)

/**
 * The CLI entry point returning a process exit code ([EXIT_OK] … [EXIT_FAILED]), so it is unit testable
 * without spawning a JVM. Mirrors `flowable_atlas.py` `main`.
 */
fun run(args: Array<String>): Int {
    val o = Options()
    parse(args, o)?.let { return it }
    // Anything that escapes from here on is a failed run, and says so with a code of its own: the JVM's answer
    // to an uncaught exception is a stack trace and exit 1 — the code a pipeline reads as "findings matched,
    // the report is written", for a run that wrote nothing.
    return try {
        execute(o)
    } catch (e: Throwable) {
        errln("error: ${describe(e, o.verbose > 0)}")
        if (o.verbose > 0) errln(e.stackTraceToString().trimEnd())
        EXIT_FAILED
    }
}

/** One line for a failed run: a write that failed says what it could not write, anything else what broke. */
private fun describe(e: Throwable, verbose: Boolean): String = when (e) {
    // The JDK's messages name the file: `out/x.md (Permission denied)`, `… (No space left on device)`.
    is java.io.IOException -> "could not write the output: ${e.message ?: e.javaClass.simpleName}"
    is OutOfMemoryError -> "out of memory — give the JVM more, e.g. java -Xmx4g -jar …"
    else -> "the run failed: ${e.javaClass.simpleName}: ${e.message}" + if (verbose) "" else " (-v prints the stack trace)"
}

/** Fill [o] from [args]; null to go on, else the exit code to return (after `--help`, or a misuse). */
private fun parse(args: Array<String>, o: Options): Int? {
    var i = 0
    var endOpts = false

    /** Value for an option: inline (`--opt=v` / `-ov`) or the next token; null (exit 2) if none. */
    fun value(name: String, inline: String?): String? {
        if (inline != null) return inline
        // argparse's rule: an option never takes the next option as its value, so `-o --stdout` is a missing
        // value and not a file named `--stdout`. A value that starts with a dash goes inline: `--output=-x`.
        if (i + 1 >= args.size || (args[i + 1].startsWith("-") && args[i + 1] != "-")) {
            errln("error: argument $name: expected one argument")
            return null
        }
        i++
        return args[i]
    }

    /** `-o`'s value: an empty one would name the working directory's parent as the folder of the output. */
    fun outputValue(inline: String?): String? {
        val v = value("-o/--output", inline) ?: return null
        if (v.isEmpty()) { errln("error: argument -o/--output: expected a path"); return null }
        return v
    }

    fun setPositional(tok: String): Boolean {
        if (o.path != null) { errln("error: unrecognized arguments: $tok"); return false }
        o.path = tok
        return true
    }

    fun help(): Int { System.out.write(usage().toByteArray(Charsets.UTF_8)); System.out.flush(); return EXIT_OK }

    var failOn: String? = null
    while (i < args.size) {
        val tok = args[i]
        when {
            endOpts -> if (!setPositional(tok)) return EXIT_MISUSE
            tok == "--" -> endOpts = true
            tok.startsWith("--") -> {
                val eq = tok.indexOf('=')
                val name = if (eq >= 0) tok.substring(0, eq) else tok
                val inline = if (eq >= 0) tok.substring(eq + 1) else null
                if (inline != null && name in BOOLEAN_FLAGS) {
                    errln("error: argument $name: ignored explicit argument '$inline'")
                    return EXIT_MISUSE
                }
                when (name) {
                    "--output" -> o.output = outputValue(inline) ?: return EXIT_MISUSE
                    "--all" -> o.all = true
                    "--json" -> o.json = true
                    "--html" -> o.html = true
                    "--erd" -> o.erd = true
                    "--summary" -> o.summary = true
                    "--claude" -> o.claude = true
                    "--playwright" -> o.playwright = true
                    "--claude-template" -> o.claudeTemplate = true
                    "--pretty" -> o.pretty = true
                    "--slice" -> {
                        val v = value(name, inline) ?: return EXIT_MISUSE
                        // `--slice "$NODE"` with the variable unset: a blank target names no node and resembles
                        // none, so it is a misuse like an empty --fail-on, not a search.
                        if (v.isBlank()) { errln("error: argument --slice: expected a node id, key, file or label"); return EXIT_MISUSE }
                        o.slice = v
                    }
                    "--stdout" -> o.stdout = true
                    "--open" -> o.open = true
                    "--expr-allowlist" -> o.exprAllowlist = value(name, inline) ?: return EXIT_MISUSE
                    "--custom-functions" -> o.customFunctions = value(name, inline) ?: return EXIT_MISUSE
                    "--no-custom-functions" -> o.noCustom = true
                    "--fail-on" -> failOn = value(name, inline) ?: return EXIT_MISUSE
                    "--waivers" -> o.waiversPath = value(name, inline) ?: return EXIT_MISUSE
                    "--no-waivers" -> o.noWaivers = true
                    "--fail-on-stale-waivers" -> o.failOnStaleWaivers = true
                    "--waiver-author" -> o.waiverAuthor = value(name, inline) ?: return EXIT_MISUSE
                    "--annotate" -> {
                        val v = value(name, inline) ?: return EXIT_MISUSE
                        // One CI system, named rather than sniffed from GITHUB_ACTIONS: a run must not print
                        // differently on a machine that happens to set a variable.
                        if (v != ANNOTATE_GITHUB) { errln("error: argument --annotate: unknown value '$v' $EM_DASH expected $ANNOTATE_GITHUB"); return EXIT_MISUSE }
                        o.annotate = v
                    }
                    "--baseline" -> {
                        val v = value(name, inline) ?: return EXIT_MISUSE
                        // `--baseline "$BASE_DIR"` with the variable unset would compare against the working directory.
                        if (v.isBlank()) { errln("error: argument --baseline: expected a graph.json, a project directory or an archive"); return EXIT_MISUSE }
                        o.baseline = v
                    }
                    "--junit" -> {
                        val v = value(name, inline) ?: return EXIT_MISUSE
                        if (v.isBlank()) { errln("error: argument --junit: expected a file"); return EXIT_MISUSE }
                        o.junit = v
                    }
                    "--verbose" -> o.verbose++
                    "--quiet" -> o.quiet = true
                    "--help" -> return help()
                    else -> { errln("error: unrecognized arguments: $tok"); return EXIT_MISUSE }
                }
            }
            tok.startsWith("-") && tok.length > 1 -> {
                // short-flag cluster: -v (count), -q, -o (takes a value; consumes the cluster tail)
                var j = 1
                var consumedRest = false
                while (j < tok.length && !consumedRest) {
                    when (val c = tok[j]) {
                        'v' -> o.verbose++
                        'q' -> o.quiet = true
                        'h' -> return help()
                        'o' -> {
                            val inline = if (j + 1 < tok.length) tok.substring(j + 1) else null
                            o.output = outputValue(inline) ?: return EXIT_MISUSE
                            consumedRest = true
                        }
                        else -> { errln("error: unrecognized arguments: -$c"); return EXIT_MISUSE }
                    }
                    j++
                }
            }
            else -> if (!setPositional(tok)) return EXIT_MISUSE
        }
        i++
    }

    // Mutually-exclusive format group (argparse errors with exit code 2).
    if (listOf(o.all, o.json, o.html, o.erd, o.summary, o.claude, o.playwright, o.claudeTemplate).count { it } > 1) {
        errln("error: argument --all/--json/--html/--erd/--summary/--claude/--playwright/--claude-template: " +
            "not allowed with one another")
        return EXIT_MISUSE
    }
    // Two requests `--all` cannot honour: it is the mode that writes a folder of files, so it can neither
    // narrow to one node nor keep to stdout — both used to be dropped without a word, the second while
    // writing the whole set into the working directory.
    if (o.all && o.slice != null) {
        errln("error: argument --slice: not allowed with --all")
        return EXIT_MISUSE
    }
    if (o.all && o.stdout) {
        errln("error: argument --stdout: not allowed with --all")
        return EXIT_MISUSE
    }
    // `--fail-on` names severities and/or check ids; an unknown one is a misuse, not a silent no-match —
    // and so is none at all: `--fail-on "$ATLAS_FAIL_ON"` with the variable unset would switch the gate off.
    if (failOn != null) {
        val terms = failOn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) {
            errln("error: argument --fail-on: expected at least one value — error, any, defects, advice or a check id")
            return EXIT_MISUSE
        }
        val knownChecks = CheckCatalog.ORDER
        terms.firstOrNull { it !in CheckCatalog.FAIL_ON_TERMS && it !in knownChecks }?.let { bad ->
            errln("error: argument --fail-on: unknown value '$bad' — expected error, any, defects, advice or one of ${knownChecks.joinToString(", ")}")
            return EXIT_MISUSE
        }
        o.failOnTerms = terms
    }
    if (o.annotate != null) {
        // The commands go to stdout, so an artifact printed there would be cut into by them.
        if (o.stdout || (o.slice != null && o.output == null)) {
            errln("error: argument --annotate: not allowed with an artifact on stdout $EM_DASH write it to a file with -o")
            return EXIT_MISUSE
        }
        // It annotates the findings the run names — those that fail it, or the new ones of a baseline; with
        // neither there are none, and the flag would do nothing.
        if (o.failOnTerms.isEmpty() && o.baseline == null) {
            errln("error: argument --annotate: needs --fail-on or --baseline $EM_DASH it annotates the findings they name")
            return EXIT_MISUSE
        }
    }
    // The primer is no project's: there is nothing to compare, annotate or report.
    if (o.claudeTemplate) {
        listOfNotNull("--baseline".takeIf { o.baseline != null }, "--annotate".takeIf { o.annotate != null },
            "--junit".takeIf { o.junit != null }).firstOrNull()?.let {
            errln("error: argument $it: not allowed with --claude-template")
            return EXIT_MISUSE
        }
    }
    return null
}

private fun execute(o: Options): Int {
    // The generic Flowable primer describes the platform, not a project, so it needs no path: this is
    // the repo's `CLAUDE.template.md` (which is generated from the same source) available from the jar.
    if (o.claudeTemplate) {
        val text = ClaudeRenderer.renderGeneric().trimEnd('\n') + "\n"
        val out = o.output
        if (out != null) {
            fileTargetProblem(File(out))?.let { errln("error: $it"); return EXIT_MISUSE }
            writeFile(File(out), text)
            if (!o.quiet) errln("Flowable Atlas $EM_DASH generic primer $CHECK $out")
        } else {
            System.out.write(text.toByteArray(Charsets.UTF_8))
            System.out.flush()
        }
        return EXIT_OK
    }

    val projectPath = o.path
    if (projectPath == null) {
        errln("error: the following arguments are required: path")
        return EXIT_MISUSE
    }
    val root = File(projectPath)
    if (!root.exists()) {
        errln("error: path not found: $projectPath")
        return EXIT_MISUSE
    }
    // One file is analysed as a project of one, but only a file Atlas can read as one: anything else would be
    // a run that finds nothing and says ✅.
    if (root.isFile && !ModelPaths.isArchive(root.name) && ModelKinds.modelTypeFor(root.name) == null) {
        errln("error: $projectPath is not a project directory, a model file or a .zip/.bar archive")
        return EXIT_MISUSE
    }
    if (root.isDirectory && root.list() == null) {
        errln("error: cannot read $projectPath — the folder cannot be listed (permissions)")
        return EXIT_MISUSE
    }

    val name = Fmt.artifactName(root)
    // Where a single artifact lands without -o: the project itself, or the folder of the archive or model
    // file that was given.
    val projectDir = if (root.isDirectory) root else root.absoluteFile.normalize().parentFile ?: File(".")
    val toStdout = o.stdout || (o.slice != null && o.output == null)
    val allDir = if (o.all) File(o.output ?: File(DEFAULT_ALL_DIR, name).path) else null
    val target: File? = when {
        o.all || toStdout -> null
        o.output != null -> File(o.output!!)
        else -> File(projectDir, defaultFileName(o))
    }

    // Everything that would refuse the output is checked before the analysis, which can take seconds — a run
    // used to do all of that work, then die on its first write with a stack trace.
    allDir?.let { d -> dirTargetProblem(d)?.let { errln("error: $it"); return EXIT_MISUSE } }
    target?.let { t -> fileTargetProblem(t)?.let { errln("error: $it"); return EXIT_MISUSE } }
    val junitFile = o.junit?.let { File(it) }
    junitFile?.let { j -> fileTargetProblem(j)?.let { errln("error: argument --junit: $it"); return EXIT_MISUSE } }
    // `--claude` alone writes the project's CLAUDE.md: a drop-in. A CLAUDE.md the team wrote is theirs, and
    // replacing it silently is the one thing a drop-in must never do; only Atlas's own file is regenerated.
    if (o.claude && o.output == null && target != null && target.isFile &&
        !ClaudeRenderer.isAtlasWritten(runCatching { target.readText(Charsets.UTF_8).take(4096) }.getOrDefault(""))) {
        errln("error: ${target.path} exists and was not written by Atlas $EM_DASH name another file with -o <file>, or use --stdout")
        return EXIT_MISUSE
    }
    val baselineFile = o.baseline?.let { File(it) }
    if (baselineFile != null) {
        val problem = when {
            !baselineFile.exists() -> "${baselineFile.path} not found"
            baselineFile.isFile && !baselineFile.name.lowercase().endsWith(".json") && !ModelPaths.isArchive(baselineFile.name) &&
                ModelKinds.modelTypeFor(baselineFile.name) == null -> "${baselineFile.path} is not a graph.json, a project directory, a model file or a .zip/.bar archive"
            else -> null
        }
        problem?.let { errln("error: argument --baseline: $it"); return EXIT_MISUSE }
    }

    // ---- extract ----
    // The waiver file lives with the artifacts, because that is the folder a reviewer is handed and the one
    // the explorer's Save writes it to. It is read before the run so the counts, the report and the exit code
    // all see the same decisions. --all names a folder; a file — named with -o, or the project's own
    // APP_OVERVIEW.* — is in one; stdout writes nowhere, so the working directory is the only place left
    // to look (or, with -o beside --stdout, the folder -o names).
    val outputDir = allDir ?: target?.absoluteFile?.parentFile
        ?: o.output?.let { File(it).absoluteFile.parentFile } ?: File(".")
    val waiverFile = o.waiversPath?.let { File(it) } ?: File(outputDir, Waivers.FILE_NAME)
    val waivers = if (o.noWaivers) Waivers.EMPTY else Waivers.load(waiverFile)
    // A path the user named and that is not there: every finding it was meant to accept comes back into the
    // gate, which used to happen without a word. Said, not fatal — an unreadable waiver file never fails a
    // run (Waivers.Set), and a missing one is how a pipeline says "no waivers". The default stays silent:
    // most projects have no file at all.
    val waiverFileMissing = o.waiversPath != null && !o.noWaivers && !waiverFile.isFile
    val allow = o.exprAllowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun analyse(path: File, with: Waivers.Set) = Atlas.extract(
        path,
        exprAllowlist = allow.ifEmpty { null },
        discoverCustom = !o.noCustom,
        customPath = o.customFunctions?.let { File(it) },
        waivers = with,
    )
    // The baseline first: a graph.json that is not one is a misuse, said before the long part — and read
    // before anything is written, so `--all` can regenerate the very graph.json it is compared against. A
    // source tree is analysed with this run's options, so an allowlist or a function file does not make old
    // findings new.
    val baseline = baselineFile?.let { b ->
        try {
            Baseline.load(b) { analyse(it, Waivers.EMPTY) }
        } catch (e: Baseline.Unreadable) {
            errln("error: argument --baseline: ${e.message}")
            return EXIT_MISUSE
        }
    }
    val result = analyse(root, waivers)

    val stats = result["stats"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun stat(key: String): Int = (stats[key] as? Number)?.toInt() ?: 0

    // A run that found nothing at all is not a clean project: it is the wrong directory, and a CI job whose
    // path went stale must not stay green on it. A folder whose only model would not parse is not empty —
    // it has a diagnostic to report — and goes on.
    val models = stat("models") + stat("archives")
    if (models + stat("java") + stat("nodes") == 0 && (result["diagnostics"] as? List<*>).isNullOrEmpty()) {
        errln("error: no Flowable models, archives or Java sources under $projectPath $EM_DASH is this the project directory?")
        return EXIT_MISUSE
    }
    // Java alone is legitimate (a project that deploys from Design at runtime), but worth a word.
    if (models == 0 && stat("java") > 0 && !o.quiet) errln("$WARN_SIGN no Flowable models found under $projectPath $EM_DASH only Java was read")

    // ---- status line (verbatim format) ----
    val resolvedN = (result["resolvedRefs"] as? List<*>)?.size ?: 0
    val unresolvedN = (result["unresolvedRefs"] as? List<*>)?.size ?: 0
    val cf = result["customFunctions"] as? Map<*, *>
    val status = buildString {
        append("${Fmt.modelScale(stats)} $MIDDLE_DOT ${stat("java")} java $MIDDLE_DOT ${stat("nodes")} nodes $MIDDLE_DOT ")
        append("${stat("edges")} links $MIDDLE_DOT $resolvedN resolved / $unresolvedN unresolved refs")
        val suspectN = stat("suspectEdges")
        val dynN = stat("dynamicEdges")
        if (suspectN + dynN > 0) append(" $MIDDLE_DOT $suspectN suspect / $dynN dynamic links")
        if (cf != null) append(" $MIDDLE_DOT custom fns: ${cf["summary"]}")
        // From `checks`, not `stats`: `stats.scriptIssues` is the raw parse-level count and knows
        // nothing about waivers, so reading it here would let the status line contradict the report.
        val scriptIssuesN = (checksOf(result)["scriptIssues"] as? Number)?.toInt() ?: 0
        if (scriptIssuesN > 0) append(" $MIDDLE_DOT $WARN_SIGN $scriptIssuesN script issue(s)")
        // Same source, same reason: the raw diagnostics list would count a parse issue the team has
        // already accepted, and the line would contradict the report it summarises.
        val parseIssuesN = (checksOf(result)["parseIssues"] as? Number)?.toInt() ?: 0
        if (parseIssuesN > 0) append(" $MIDDLE_DOT $WARN_SIGN $parseIssuesN parse issue(s), see -v")
        // The two numbers the reports lead with, so the terminal and the summary say the same thing.
        val health = Fmt.healthHeadline(stats)
        if (health.isNotEmpty()) append(" $MIDDLE_DOT $health")
        val waivedN = (checksOf(result)["waived"] as? Number)?.toInt() ?: 0
        if (waivedN > 0) append(" $MIDDLE_DOT $waivedN waived")
        if (staleWaivers(result).isNotEmpty()) {
            append(" $MIDDLE_DOT $WARN_SIGN ${staleWaivers(result).size} stale waiver(s)")
        }
    }
    // -v: the parse issues the status line counts, one per line — the flag was accepted and never read.
    val diagnosticLines: List<String> = if (o.verbose > 0) {
        (result["diagnostics"] as? List<*>).orEmpty().mapNotNull { d ->
            // a shared key is not counted, so it is not listed either
            // `path:line`, the form a terminal and an editor open at the spot
            (d as? Map<*, *>)?.takeIf { it["kind"] != "conflict" }?.let {
                "  ${it["kind"]} ${it["path"]}${it["line"]?.let { l -> ":$l" } ?: ""}: ${it["message"]}"
            }
        }
    } else emptyList()

    // --fail-on: the findings that make this run a failure. Every artifact is still written — a CI job
    // wants the report AND the red build — and the exit code becomes 1 after the output.
    @Suppress("UNCHECKED_CAST")
    val findings = (result["findings"] as? List<Map<String, Any?>>).orEmpty()
    val gated = findings.filter { CheckCatalog.gates(o.failOnTerms, it) }
    // --baseline: what the base already had does not gate. The instances, by identity — two findings can be
    // equal maps, and one of them can be new.
    val fresh: Set<Map<String, Any?>>? = baseline?.let { b ->
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Map<String, Any?>, Boolean>())
            .apply { addAll(Findings.newSince(b.findings, findings)) }
    }
    val failing = if (fresh == null) gated else gated.filter { it in fresh }
    // What the run names on stderr (and annotates): what fails it — or, with a baseline and no gate, what is new.
    val named = if (fresh != null && o.failOnTerms.isEmpty()) findings.filter { it["waived"] == null && it in fresh } else failing
    // Where every finding can be read when the log stops listing them: the overview elides per check too.
    val everyFinding = when {
        junitFile != null -> "all of them are in ${junitFile.path}"
        allDir != null -> "all of them are under `findings` in ${File(allDir, "$name.graph.json").path}"
        o.json && o.slice == null && target != null -> "all of them are under `findings` in ${target.path}"
        else -> "--json writes all of them, under `findings`"
    }
    fun exitAfterOutput(): Int {
        val stale = staleWaivers(result)
        if (!o.quiet) {
            diagnosticLines.forEach(::errln)
            // A waiver file that no longer says anything true is worth a line whether or not it is
            // fatal: silence here is exactly how a suppression list rots.
            stale.forEach { errln("  $WARN_SIGN waiver $it") }
            waiverNotices(result).forEach { errln("  $WARN_SIGN waiver $it") }
            if (waiverFileMissing) {
                val why = if (waiverFile.isDirectory) "is a folder" else "not found"
                errln("  $WARN_SIGN waiver file ${waiverFile.path} $why, so no finding is waived")
            }
        }
        // Both gates are reported, then decided: a stale waiver used to return early and hide which findings
        // would have failed the run as well.
        val staleFatal = o.failOnStaleWaivers && stale.isNotEmpty()
        if (!o.quiet) {
            if (staleFatal) errln("$WARN_SIGN --fail-on-stale-waivers: ${stale.size} stale waiver(s)")
            // Identities are Atlas's own, and a check that changed between versions reads as new findings.
            baseline?.writtenBy?.takeIf { it != AtlasBuildInfo.VERSION }?.let {
                errln("$WARN_SIGN baseline written by Atlas $it, this is ${AtlasBuildInfo.VERSION} $EM_DASH regenerate it, " +
                    "or give the base's sources (a directory or archive), if unchanged findings show as new")
            }
            val byCheck = named.groupingBy { it["check"].toString() }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
            val gate = "--fail-on ${o.failOnTerms.joinToString(",")}"
            when {
                // A baseline says what it set aside, so a gate that passes on it is not mistaken for a clean project.
                fresh != null && o.failOnTerms.isNotEmpty() -> {
                    val known = "${gated.size - failing.size} in the baseline, not gating"
                    if (failing.isEmpty()) errln("$CHECK $gate: no new finding ($known)")
                    else errln("$WARN_SIGN $gate: ${failing.size} new finding(s) ($known) $EM_DASH $byCheck")
                }
                fresh != null ->
                    errln("--baseline: " + if (named.isEmpty()) "no new finding" else "${named.size} new finding(s) $EM_DASH $byCheck")
                failing.isNotEmpty() -> errln("$WARN_SIGN $gate: ${failing.size} finding(s) $EM_DASH $byCheck")
            }
            // Which ones: a red build that names only counts sends everyone to download the artifacts to learn
            // what to fix.
            failingLines(named, more = everyFinding).forEach(::errln)
        }
        // stdout, where GitHub reads workflow commands — and not silenced by -q, which is about stderr.
        if (o.annotate == ANNOTATE_GITHUB) {
            val commands = githubAnnotations(named, projectDir)
            if (commands.isNotEmpty()) {
                System.out.write(commands.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                System.out.flush()
            }
        }
        return if (staleFatal || failing.isNotEmpty()) EXIT_GATE else EXIT_OK
    }
    // --junit: in every mode, after the artifacts it reports on, for a green run as for a red one. Its
    // red/green split is the gate's, decided by the same predicate; without --fail-on it is `defects`, the
    // split every surface leads with — a report that cannot say what is wrong is not one a CI view can show.
    fun writeJunit(): File? = junitFile?.also {
        writeFile(it, JUnitRenderer.render(result, o.failOnTerms.ifEmpty { listOf("defects") },
            baselined = { f -> fresh != null && f !in fresh }, staleGate = o.failOnStaleWaivers))
    }

    // ---- --all: write all six artifacts (the Playwright guide in two files) into the output folder ----
    if (allDir != null) {
        val outdir = allDir
        outdir.mkdirs()
        // The analysis here is regenerated and may carry client data; waivers.json is a decision a team
        // made and belongs in review. The .gitignore says so; see Waivers.OUTPUT_GITIGNORE.
        Waivers.ensureOutputGitignore(outdir)
        val artifacts = mutableListOf(
            "$name.summary.md" to SummaryRenderer.render(result, root),
            "$name.overview.md" to OverviewRenderer.render(result, root),
            "$name.graph.json" to GraphJsonRenderer.render(result, pretty = o.pretty),
            "$name.explorer.html" to ExplorerHtmlRenderer.render(result, root, waiverAuthor = o.waiverAuthor),
            // The file names its siblings from the project root, so it needs to know where they land.
            "$name.CLAUDE.md" to ClaudeRenderer.render(result, root,
                ClaudeRenderer.Layout(outdir, siblings = true, playwright = true, playwrightMap = true)),
        )
        // The browser guide in two files: what a skill loads whole (rules, index), and the project map it
        // reads on demand — on a real project the map is most of the text.
        val guide = PlaywrightRenderer.Layout(outdir, graph = true, map = true)
        artifacts.add("$name.${PlaywrightRenderer.SUFFIX}" to PlaywrightRenderer.render(result, root, guide))
        artifacts.add("$name.${PlaywrightRenderer.MAP_SUFFIX}" to PlaywrightRenderer.renderMap(result, root, guide))
        // The ER diagram designer, beside the explorer it links into — only for a project with tables to draw:
        // on any other it would be a page that can only say it has nothing to show. A page left from a run
        // that still had tables goes, rather than stand beside this run's artifacts as if it were one of them.
        val erdPage = File(outdir, "$name.erd.html")
        if (ErdHtmlRenderer.hasTables(result)) {
            artifacts.add(erdPage.name to ErdHtmlRenderer.render(result, root, explorerFile = "$name.explorer.html"))
        } else if (erdPage.isFile) {
            erdPage.delete()
        }
        val written = artifacts.map { (fn, content) -> File(outdir, fn).also { writeFile(it, content) } } + listOfNotNull(writeJunit())
        // Diagrams: render each process/case/decision's DI layout to an SVG in `<name>.diagrams/`.
        // Additive post-pass over the finished result (never touches the graph); a project with no
        // BPMN/CMMN/DMN layout produces no files here — and loses the drawings of an earlier run.
        val diagrams = DiagramArtifacts.render(result, root) { key, why ->
            if (!o.quiet) errln("  $WARN_SIGN diagram $key: $why")
        }
        val diagramsDir = File(outdir, "$name.diagrams")
        val drawn = DiagramArtifacts.write(diagramsDir, diagrams)
        if (!o.quiet) {
            for (p in written) errln("  $CHECK ${p.path}")
            // One line for the folder: a large project draws hundreds, and their paths scrolled the status
            // line — the thing to read — out of sight. -v lists them.
            if (o.verbose > 0) drawn.written.forEach { errln("  $CHECK ${it.path}") }
            else if (drawn.written.isNotEmpty()) {
                val n = drawn.written.size
                errln("  $CHECK ${diagramsDir.path}/ ($n SVG${if (n == 1) "" else "s"})")
            }
            // Last, so a terminal shows it where the eye lands; only the detail -v or --fail-on asked for follows.
            errln("Flowable Atlas $EM_DASH $name: $status")
        }
        if (o.open) written.firstOrNull { it.path.endsWith(".html") }?.let { openOrSay(it, o.quiet) }
        return exitAfterOutput()
    }

    // ---- --slice: one node with its context, in both directions ----
    // The tier between "3 KB summary" and "megabytes of graph": what an agent actually needs when it has
    // been asked to change one process.
    val slice = o.slice
    if (slice != null) {
        val text = SliceRenderer.render(result, slice)
        if (text == null) {
            val near = SliceRenderer.suggest(result, slice)
            val hint = if (near.isNotEmpty()) "did you mean: ${near.joinToString(", ")}"
            else "try a `<type>:<key>` id, e.g. ${exampleId(result) ?: "process:myProcess"}"
            errln("error: no node matches '$slice' $EM_DASH $hint")
            return EXIT_MISUSE
        }
        if (target == null) {
            System.out.write((text + "\n").toByteArray(Charsets.UTF_8))
            System.out.flush()
        } else {
            writeFile(target, text + "\n")
        }
        val report = writeJunit()
        if (!o.quiet) {
            report?.let { errln("  $CHECK ${it.path}") }
            if (target != null) errln("Flowable Atlas $EM_DASH slice $CHECK ${target.path}")
        }
        return exitAfterOutput()
    }

    // ---- single-artifact modes ----
    val out = when {
        // Alone, `--claude` writes no summary/graph next to itself; the file must not pretend otherwise.
        o.claude -> ClaudeRenderer.render(result, root, ClaudeRenderer.Layout(siblings = false))
        // Alone, it has no graph.json beside it either (its lookups section says how to make one), and it
        // keeps the project map inline: one artifact, one file.
        o.playwright -> PlaywrightRenderer.render(result, root, PlaywrightRenderer.Layout(graph = false))
        o.summary -> SummaryRenderer.render(result, root)
        o.html -> ExplorerHtmlRenderer.render(result, root, waiverAuthor = o.waiverAuthor)
        o.erd -> ErdHtmlRenderer.render(result, root)
        o.json -> GraphJsonRenderer.render(result, pretty = o.pretty)
        else -> OverviewRenderer.render(result, root)
    }

    if (target == null) {
        // sys.stdout.write(out + "\n")
        System.out.write((out + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
        writeJunit()?.let { if (!o.quiet) errln("  $CHECK ${it.path}") }
        return exitAfterOutput()
    }
    writeFile(target, out)
    val report = writeJunit()
    if (!o.quiet) {
        report?.let { errln("  $CHECK ${it.path}") }
        errln("wrote ${target.path} $EM_DASH $status")
    }
    if (o.open && (o.html || o.erd)) openOrSay(target, o.quiet)
    return exitAfterOutput()
}

/** A single artifact's file name without -o. `--claude` writes a ready-to-drop-in CLAUDE.md, not APP_OVERVIEW.CLAUDE.md. */
private fun defaultFileName(o: Options): String = when {
    o.claude -> "CLAUDE.md"
    o.playwright -> "APP_OVERVIEW.playwright.md"
    o.summary -> "APP_OVERVIEW.summary.md"
    o.html -> "APP_OVERVIEW.html"
    o.erd -> "APP_OVERVIEW.erd.html"
    o.json -> "APP_OVERVIEW.json"
    else -> "APP_OVERVIEW.md"
}

/** Write [text] to [f], creating the folders it is in — as `--all` creates its output folder. */
private fun writeFile(f: File, text: String) {
    f.absoluteFile.parentFile?.mkdirs()
    f.writeText(text, Charsets.UTF_8)
}

/** The closest of [f] and its ancestors that exists — what decides whether the missing rest can be created. */
private fun nearestExisting(f: File): File {
    var d: File? = f.absoluteFile
    while (d != null && !d.exists()) d = d.parentFile
    return d ?: File(".").absoluteFile
}

/** Why `--all` could not write into [dir], or null when it can (a missing folder is created). */
private fun dirTargetProblem(dir: File): String? {
    if (dir.exists() && !dir.isDirectory) return "cannot write to ${dir.path}: it is a file, and --all writes into a folder"
    val at = nearestExisting(dir)
    if (!at.isDirectory) return "cannot write to ${dir.path}: ${at.path} is not a folder"
    if (!at.canWrite()) return "cannot write to ${dir.path}: ${at.path} is not writable"
    return null
}

/** Why a single artifact could not be written to [f], or null when it can (missing folders are created). */
private fun fileTargetProblem(f: File): String? {
    if (f.isDirectory) return "cannot write ${f.path}: it is a folder (--all is the mode that writes into one)"
    if (f.exists()) return if (f.canWrite()) null else "cannot write ${f.path}: the file is not writable"
    val at = nearestExisting(f.absoluteFile.parentFile ?: File("."))
    if (!at.isDirectory) return "cannot write ${f.path}: ${at.path} is not a folder"
    if (!at.canWrite()) return "cannot write ${f.path}: ${at.path} is not writable"
    return null
}

/** An id the graph really has — a process, else a case, else any — for the hint of a `--slice` that resembles nothing. */
@Suppress("UNCHECKED_CAST")
private fun exampleId(result: Map<String, Any?>): String? {
    val nodes = ((result["graph"] as? Map<String, Any?>)?.get("nodes") as? List<Map<String, Any?>>).orEmpty()
    return (nodes.firstOrNull { it["type"] == "process" } ?: nodes.firstOrNull { it["type"] == "case" } ?: nodes.firstOrNull())
        ?.get("id") as? String
}

/** `--help`: the flags, in the order the reference page lists them. The launcher had a help; the jar answered exit 2. */
private fun usage(): String = """
Flowable Atlas — map a Flowable project (models and Java) into readable, clickable, queryable artifacts.

usage: java -jar cli-<version>-all.jar <path> [options]
       <path> is a project directory, a single model file, or an exported .zip / .bar archive

output (mutually exclusive):
  (none)              the full Markdown report (APP_OVERVIEW.md)
  --all               all six artifacts into a directory (-o, default $DEFAULT_ALL_DIR/<name>) — the Playwright
                      guide as <name>.playwright.md plus its project map <name>.playwright-map.md — plus
                      <name>.diagrams/ and, when the project has tables, <name>.erd.html
  --summary           the compact LLM-first overview
  --html              the interactive explorer
  --erd               the ER diagram designer (the project's tables and their relations)
  --json              the traversable graph
  --claude            drop-in agent context (CLAUDE.md; never replaces a CLAUDE.md Atlas did not write)
  --playwright        the agent's guide to driving Flowable Work in a browser, with this project's
                      stages, tasks, required fields and button conditions (also a Claude Code skill);
                      one file, the project map inline
  --claude-template   the project-independent Flowable primer (needs no path)
  Without -o, a single artifact is written into the project directory (an archive's or model file's folder).

options:
  -o, --output <path>         output file — or, with --all, the output directory (missing folders are created)
  --slice <target>            render one node with its full context (not with --all): a <type>:<key> id, a key,
                              a model's file path, a Java class name or a label; a miss names the nearest ids
  --stdout                    write the single artifact to stdout and touch no files (not with --all)
  --open                      open the result in a browser (--all, --html, --erd)
  --pretty                    indent graph.json
  --expr-allowlist <list>     comma-separated expression namespaces/functions the project registers itself
  --custom-functions <path>   where to look for frontend customisation sources
  --no-custom-functions       do not discover custom functions
  --fail-on <list>            exit 1 when findings match: error, any, defects, advice, and/or check ids
                              (${CheckCatalog.ORDER.joinToString(", ")})
                              (warning is an accepted spelling of any, kept for compatibility);
                              a failed run lists the first $GATE_LISTED matching findings on stderr
  --baseline <path>           gate only what is new since then: a graph.json Atlas wrote, or the base's
                              sources (a project directory, a model file or a .zip/.bar archive)
  --annotate github           also print the findings the run names as GitHub Actions annotations on
                              stdout (needs --fail-on or --baseline; not with an artifact on stdout)
  --junit <file>              also write the findings as a JUnit XML report for CI test views: one suite
                              per check, a gated finding a failure, the rest skipped with the reason why
  --waivers <path>            the accepted-findings file (default: waivers.json beside the artifacts)
  --waiver-author <name>      the `by` of a rule accepted from the explorer page
  --no-waivers                ignore it — report every finding, for an audit
  --fail-on-stale-waivers     exit 1 when a waiver matched nothing or has expired
  -v, --verbose               list every parse issue the status line counts, every diagram written,
                              and the stack trace of a failed run
  -q, --quiet                 silence the status lines on stderr
  -h, --help                  this text
  --                          end of options; the next token is the path

exit codes: 0 success · 1 a gate failed: a --fail-on finding matched, or --fail-on-stale-waivers found a
            stale waiver (artifacts are still written) · 2 misuse: bad arguments, a path with nothing to
            analyse, an output that cannot be written, a --slice that matches nothing · 3 the run failed
            under way: an output could not be written, or an unexpected error (artifacts may be missing)
""".trimStart()

/** Rules a reviewer could not have reviewed, and anything wrong with the file itself. */
@Suppress("UNCHECKED_CAST")
private fun waiverNotices(result: Map<String, Any?>): List<String> {
    val w = result["waivers"] as? Map<String, Any?> ?: return emptyList()
    return ((w["unexplained"] as? List<String>).orEmpty() + (w["problems"] as? List<String>).orEmpty())
}

@Suppress("UNCHECKED_CAST")
private fun checksOf(result: Map<String, Any?>): Map<String, Any?> =
    result["checks"] as? Map<String, Any?> ?: emptyMap()

/** What the waiver file no longer covers — an expired rule, or one whose model is gone. */
@Suppress("UNCHECKED_CAST")
private fun staleWaivers(result: Map<String, Any?>): List<String> =
    ((result["waivers"] as? Map<String, Any?>)?.get("stale") as? List<String>).orEmpty()

/** `--open`, and a word when nothing could open it: a flag that silently did nothing looked like a hang. */
private fun openOrSay(file: File, quiet: Boolean) {
    if (!openFile(file.absolutePath) && !quiet) {
        errln("  $WARN_SIGN --open: nothing here opens a browser $EM_DASH open ${file.absolutePath} yourself")
    }
}

/**
 * The command that opens [path] in the default browser on [osName], or null when there is none. Windows is
 * `rundll32 url.dll,FileProtocolHandler`, which is always there and needs no shell: `start` is a cmd.exe
 * builtin, never a file a PATH probe finds, and `cmd /c start` would re-parse a `&` or `^` in the path.
 * java.awt.Desktop is not used: on macOS it starts AppKit and puts a Java icon in the Dock, and headless
 * JREs lack it.
 */
internal fun openerCommand(osName: String, path: String, onPath: (String) -> Boolean): List<String>? = when {
    osName.startsWith("Windows", ignoreCase = true) -> listOf("rundll32", "url.dll,FileProtocolHandler", path)
    osName.startsWith("Mac", ignoreCase = true) && onPath("open") -> listOf("open", path)
    onPath("xdg-open") -> listOf("xdg-open", path)
    onPath("open") -> listOf("open", path)
    else -> null
}

/** Best-effort open (Python `_open_file`); true when a browser was started. Never fails the run. */
private fun openFile(path: String): Boolean {
    val cmd = openerCommand(System.getProperty("os.name").orEmpty(), path, ::which) ?: return false
    return try {
        ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    } catch (_: Exception) {
        false
    }
}

/** Minimal `shutil.which`: is [cmd] an executable file on any PATH entry? */
private fun which(cmd: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    return pathEnv.split(File.pathSeparatorChar).any { dir ->
        dir.isNotEmpty() && File(dir, cmd).let { it.isFile && it.canExecute() }
    }
}
