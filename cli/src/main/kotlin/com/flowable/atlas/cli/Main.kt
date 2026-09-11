package com.flowable.atlas.cli

import com.flowable.atlas.diagram.DiagramArtifacts
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.Waivers
import com.flowable.atlas.render.ClaudeRenderer
import com.flowable.atlas.render.ExplorerHtmlRenderer
import com.flowable.atlas.render.GraphJsonRenderer
import com.flowable.atlas.render.OverviewRenderer
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
 * (2 on a missing path / argument misuse) and the verbatim stderr status line. Argument parsing is
 * done by hand (no external arg library) to keep the runtime dependency-free.
 */

fun main(args: Array<String>): Unit = exitProcess(run(args))

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

/**
 * The CLI entry point returning a process exit code (0 ok, 2 on argument/path error), so it is unit
 * testable without spawning a JVM. Mirrors `flowable_atlas.py` `main`.
 */
fun run(args: Array<String>): Int {
    // ---- hand-rolled argument parsing (argparse-equivalent) ----
    var path: String? = null
    var output: String? = null
    var all = false; var json = false; var html = false; var summary = false; var claude = false
    var claudeTemplate = false
    var stdout = false; var open = false; var noCustom = false; var quiet = false
    var pretty = false
    var slice: String? = null
    var verbose = 0
    var exprAllowlist = ""
    var customFunctions: String? = null
    var failOn: String? = null
    var waiversPath: String? = null; var noWaivers = false; var failOnStaleWaivers = false
    var waiverAuthor: String? = null

    var i = 0
    var endOpts = false

    /** Value for an option: inline (`--opt=v` / `-ov`) or the next token; exit 2 if none. */
    fun value(name: String, inline: String?): String? {
        if (inline != null) return inline
        if (i + 1 >= args.size) { errln("error: argument $name: expected one argument"); return null }
        i++
        return args[i]
    }

    fun setPositional(tok: String): Boolean {
        if (path != null) { errln("error: unrecognized arguments: $tok"); return false }
        path = tok
        return true
    }

    while (i < args.size) {
        val tok = args[i]
        when {
            endOpts -> if (!setPositional(tok)) return 2
            tok == "--" -> endOpts = true
            tok.startsWith("--") -> {
                val eq = tok.indexOf('=')
                val name = if (eq >= 0) tok.substring(0, eq) else tok
                val inline = if (eq >= 0) tok.substring(eq + 1) else null
                when (name) {
                    "--output" -> output = value(name, inline) ?: return 2
                    "--all" -> all = true
                    "--json" -> json = true
                    "--html" -> html = true
                    "--summary" -> summary = true
                    "--claude" -> claude = true
                    "--claude-template" -> claudeTemplate = true
                    "--pretty" -> pretty = true
                    "--slice" -> slice = value(name, inline) ?: return 2
                    "--stdout" -> stdout = true
                    "--open" -> open = true
                    "--expr-allowlist" -> exprAllowlist = value(name, inline) ?: return 2
                    "--custom-functions" -> customFunctions = value(name, inline) ?: return 2
                    "--no-custom-functions" -> noCustom = true
                    "--fail-on" -> failOn = value(name, inline) ?: return 2
                    "--waivers" -> waiversPath = value(name, inline) ?: return 2
                    "--no-waivers" -> noWaivers = true
                    "--fail-on-stale-waivers" -> failOnStaleWaivers = true
                    "--waiver-author" -> waiverAuthor = value(name, inline) ?: return 2
                    "--verbose" -> verbose++
                    "--quiet" -> quiet = true
                    "--help" -> { System.out.write(usage().toByteArray(Charsets.UTF_8)); System.out.flush(); return 0 }
                    else -> { errln("error: unrecognized arguments: $tok"); return 2 }
                }
            }
            tok.startsWith("-") && tok.length > 1 -> {
                // short-flag cluster: -v (count), -q, -o (takes a value; consumes the cluster tail)
                var j = 1
                var consumedRest = false
                while (j < tok.length && !consumedRest) {
                    when (val c = tok[j]) {
                        'v' -> verbose++
                        'q' -> quiet = true
                        'h' -> { System.out.write(usage().toByteArray(Charsets.UTF_8)); System.out.flush(); return 0 }
                        'o' -> {
                            val inline = if (j + 1 < tok.length) tok.substring(j + 1) else null
                            output = value("-o", inline) ?: return 2
                            consumedRest = true
                        }
                        else -> { errln("error: unrecognized arguments: -$c"); return 2 }
                    }
                    j++
                }
            }
            else -> if (!setPositional(tok)) return 2
        }
        i++
    }

    // Mutually-exclusive format group (argparse errors with exit code 2).
    if (listOf(all, json, html, summary, claude, claudeTemplate).count { it } > 1) {
        errln("error: argument --all/--json/--html/--summary/--claude/--claude-template: " +
            "not allowed with one another")
        return 2
    }
    // `--all` used to win over `--slice` without a word — the one flag conflict that was not an error.
    if (all && slice != null) {
        errln("error: argument --slice: not allowed with --all")
        return 2
    }
    // `--fail-on` names severities and/or check ids; an unknown one is a misuse, not a silent no-match.
    val failOnTerms = failOn?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val knownChecks = com.flowable.atlas.graph.CheckCatalog.ORDER
    failOnTerms.firstOrNull { it != "error" && it != "warning" && it != "any" && it !in knownChecks }?.let { bad ->
        errln("error: argument --fail-on: unknown value '$bad' — expected error, any or one of ${knownChecks.joinToString(", ")}")
        return 2
    }

    // The generic Flowable primer describes the platform, not a project, so it needs no path: this is
    // the repo's `CLAUDE.template.md` (which is generated from the same source) available from the jar.
    if (claudeTemplate) {
        val text = ClaudeRenderer.renderGeneric().trimEnd('\n') + "\n"
        if (output != null) {
            File(output).writeText(text, Charsets.UTF_8)
            if (!quiet) errln("Flowable Atlas $EM_DASH generic primer $CHECK $output")
        } else {
            System.out.write(text.toByteArray(Charsets.UTF_8))
            System.out.flush()
        }
        return 0
    }

    if (path == null) {
        errln("error: the following arguments are required: path")
        return 2
    }
    val projectPath: String = path

    if (!File(projectPath).exists()) {
        errln("error: path not found: $projectPath")
        return 2
    }

    // ---- extract ----
    // The waiver file lives with the artifacts, because that is the folder a reviewer is handed. It is
    // read before the run so the counts, the report and the exit code all see the same decisions.
    // --all names a folder; a single artifact names a file, and the folder that counts is the one that
    // file is in; stdout writes nowhere, so the working directory is the only place left to look.
    val outputDir = when {
        all -> File(output ?: ".")
        output != null -> File(output).absoluteFile.parentFile ?: File(".")
        else -> File(".")
    }
    val waiverFile = waiversPath?.let { File(it) } ?: File(outputDir, Waivers.FILE_NAME)
    val waivers = if (noWaivers) Waivers.EMPTY else Waivers.load(waiverFile)
    val allow = exprAllowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val result = Atlas.extract(
        File(projectPath),
        exprAllowlist = allow.ifEmpty { null },
        discoverCustom = !noCustom,
        customPath = customFunctions?.let { File(it) },
        waivers = waivers,
    )

    // ---- status line (verbatim format) ----
    val stats = result["stats"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun stat(key: String): Int = (stats[key] as? Number)?.toInt() ?: 0
    val resolvedN = (result["resolvedRefs"] as? List<*>)?.size ?: 0
    val unresolvedN = (result["unresolvedRefs"] as? List<*>)?.size ?: 0
    val cf = result["customFunctions"] as? Map<*, *>
    val status = buildString {
        append("${com.flowable.atlas.render.Fmt.modelScale(stats)} $MIDDLE_DOT ${stat("java")} java $MIDDLE_DOT ${stat("nodes")} nodes $MIDDLE_DOT ")
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
        val waivedN = (checksOf(result)["waived"] as? Number)?.toInt() ?: 0
        if (waivedN > 0) append(" $MIDDLE_DOT $waivedN waived")
        if (staleWaivers(result).isNotEmpty()) {
            append(" $MIDDLE_DOT $WARN_SIGN ${staleWaivers(result).size} stale waiver(s)")
        }
    }
    // -v: the parse issues the status line counts, one per line — the flag was accepted and never read.
    val diagnosticLines: List<String> = if (verbose > 0) {
        (result["diagnostics"] as? List<*>).orEmpty().mapNotNull { d ->
            // a shared key is not counted, so it is not listed either
            (d as? Map<*, *>)?.takeIf { it["kind"] != "conflict" }?.let { "  ${it["kind"]} ${it["path"]}: ${it["message"]}" }
        }
    } else emptyList()

    // --fail-on: the findings that make this run a failure. Every artifact is still written — a CI job
    // wants the report AND the red build — and the exit code becomes 1 after the output.
    @Suppress("UNCHECKED_CAST")
    val findings = (result["findings"] as? List<Map<String, Any?>>).orEmpty()
    val failing = findings.filter { f ->
        if (f["waived"] != null) return@filter false
        val sev = f["severity"] as? String
        // `warning` has always meant "any finding at all", and pipelines were told to tighten to it —
        // narrowing it now would make those stop failing on errors. `any` is the honest spelling.
        failOnTerms.any { t ->
            (t == "error" && sev == "error") || t == "warning" || t == "any" || t == f["check"]
        }
    }
    fun exitAfterOutput(): Int {
        if (!quiet) {
            diagnosticLines.forEach(::errln)
            // A waiver file that no longer says anything true is worth a line whether or not it is
            // fatal: silence here is exactly how a suppression list rots.
            staleWaivers(result).forEach { errln("  $WARN_SIGN waiver $it") }
            waiverNotices(result).forEach { errln("  $WARN_SIGN waiver $it") }
        }
        if (failOnStaleWaivers && staleWaivers(result).isNotEmpty()) {
            if (!quiet) errln("$WARN_SIGN --fail-on-stale-waivers: ${staleWaivers(result).size} stale waiver(s)")
            return 1
        }
        if (failing.isEmpty()) return 0
        if (!quiet) {
            val byCheck = failing.groupingBy { it["check"].toString() }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
            errln("$WARN_SIGN --fail-on ${failOnTerms.joinToString(",")}: ${failing.size} finding(s) — $byCheck")
        }
        return 1
    }

    // name = os.path.splitext(os.path.basename(os.path.abspath(path.rstrip('/'))))[0] or "project"
    val abs = File(projectPath.trimEnd('/').ifEmpty { "." }).absoluteFile.normalize()
    val name = splitextName(abs.name).ifEmpty { "project" }
    val root = File(projectPath)

    // ---- --all: write all five artifacts into the -o directory (default ".") ----
    if (all) {
        val outdir = outputDir
        outdir.mkdirs()
        // The analysis here is regenerated and may carry client data; waivers.json is a decision a team
        // made and belongs in review. The .gitignore says so; see Waivers.OUTPUT_GITIGNORE.
        Waivers.ensureOutputGitignore(outdir)
        val artifacts = listOf(
            "$name.summary.md" to SummaryRenderer.render(result, root),
            "$name.overview.md" to OverviewRenderer.render(result, root),
            "$name.graph.json" to GraphJsonRenderer.render(result, pretty = pretty),
            "$name.explorer.html" to ExplorerHtmlRenderer.render(result, root, waiverAuthor = waiverAuthor),
            "$name.CLAUDE.md" to ClaudeRenderer.render(result, root),
        )
        val written = ArrayList<File>()
        for ((fn, content) in artifacts) {
            val p = File(outdir, fn)
            p.writeText(content, Charsets.UTF_8)
            written.add(p)
        }
        // Diagrams: render each process/case/decision's DI layout to an SVG in `<name>.diagrams/`.
        // Additive post-pass over the finished result (never touches the graph); a project with no
        // BPMN/CMMN/DMN layout produces no files here.
        val diagrams = DiagramArtifacts.render(result, root) { key, why ->
            if (!quiet) errln("  $WARN_SIGN diagram $key: $why")
        }
        if (diagrams.isNotEmpty()) {
            val diagramsDir = File(outdir, "$name.diagrams")
            diagramsDir.mkdirs()
            for ((fn, svg) in diagrams) {
                val p = File(diagramsDir, fn)
                p.writeText(svg, Charsets.UTF_8)
                written.add(p)
            }
        }
        if (!quiet) {
            errln("Flowable Atlas $EM_DASH $name: $status")
            for (p in written) errln("  $CHECK ${p.path}")
        }
        if (open) written.firstOrNull { it.path.endsWith(".html") }?.let { openFile(it.path) }
        return exitAfterOutput()
    }

    // ---- --slice: one node with its context, in both directions ----
    // The tier between "3 KB summary" and "megabytes of graph": what an agent actually needs when it has
    // been asked to change one process.
    if (slice != null) {
        val text = SliceRenderer.render(result, slice)
        if (text == null) {
            errln("error: no node matches '$slice' — try a `<type>:<key>` id, e.g. process:myProcess")
            return 2
        }
        if (output != null) {
            File(output).writeText(text + "\n", Charsets.UTF_8)
            if (!quiet) errln("Flowable Atlas $EM_DASH slice $CHECK $output")
        } else {
            System.out.write((text + "\n").toByteArray(Charsets.UTF_8))
            System.out.flush()
        }
        return exitAfterOutput()
    }

    // ---- single-artifact modes ----
    val (out, ext) = when {
        claude -> ClaudeRenderer.render(result, root) to "CLAUDE.md"
        summary -> SummaryRenderer.render(result, root) to "summary.md"
        html -> ExplorerHtmlRenderer.render(result, root, waiverAuthor = waiverAuthor) to "html"
        json -> GraphJsonRenderer.render(result, pretty = pretty) to "json"
        else -> OverviewRenderer.render(result, root) to "md"
    }

    if (stdout) {
        // sys.stdout.write(out + "\n")
        System.out.write((out + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
        return exitAfterOutput()
    }

    val target: String = if (output != null) {
        output
    } else {
        val base = if (root.isDirectory) projectPath else (root.absoluteFile.normalize().parentFile?.path ?: ".")
        // --claude single mode writes a ready-to-drop-in CLAUDE.md, not APP_OVERVIEW.CLAUDE.md
        File(base, if (claude) "CLAUDE.md" else "APP_OVERVIEW.$ext").path
    }
    File(target).writeText(out, Charsets.UTF_8)
    if (!quiet) errln("wrote $target $EM_DASH $status")
    if (open && ext == "html") openFile(target)
    return exitAfterOutput()
}

/** `--help`: the flags, in the order the reference page lists them. The launcher had a help; the jar answered exit 2. */
private fun usage(): String = """
Flowable Atlas — map a Flowable project (models and Java) into readable, clickable, queryable artifacts.

usage: java -jar cli-<version>-all.jar <path> [options]
       <path> is a project directory, a single model file, or an exported .zip / .bar archive

output (mutually exclusive):
  (none)              the full Markdown report (APP_OVERVIEW.md)
  --all               all five artifacts into a directory (-o, default .), plus <name>.diagrams/
  --summary           the compact LLM-first overview
  --html              the interactive explorer
  --json              the traversable graph
  --claude            drop-in agent context (CLAUDE.md)
  --claude-template   the project-independent Flowable primer (needs no path)

options:
  -o, --output <path>         output file — or, with --all, the output directory
  --slice <type:key>          render one node with its full context (not with --all)
  --stdout                    write the single artifact to stdout
  --open                      open the result in a browser (--all, --html)
  --pretty                    indent graph.json
  --expr-allowlist <list>     comma-separated expression namespaces/functions the project registers itself
  --custom-functions <path>   where to look for frontend customisation sources
  --no-custom-functions       do not discover custom functions
  --fail-on <list>            exit 1 when findings match: error, any, and/or check ids
                              (${com.flowable.atlas.graph.CheckCatalog.ORDER.joinToString(", ")})
                              (warning is an accepted spelling of any, kept for compatibility)
  --waivers <path>            the accepted-findings file (default: waivers.json beside the artifacts)
  --waiver-author <name>      the `by` of a rule accepted from the explorer page
  --no-waivers                ignore it — report every finding, for an audit
  --fail-on-stale-waivers     exit 1 when a waiver matched nothing or has expired
  -v, --verbose               list every parse issue the status line counts
  -q, --quiet                 silence the status lines on stderr
  -h, --help                  this text
  --                          end of options; the next token is the path

exit codes: 0 success · 1 a --fail-on finding matched (artifacts are still written) · 2 argument misuse
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

/**
 * Faithful port of `os.path.splitext(basename)[0]`: split off the last extension, but treat leading
 * dots as part of the name (a leading-dot file has no extension), matching CPython's `genericpath`.
 */
private fun splitextName(base: String): String {
    val dot = base.lastIndexOf('.')
    if (dot > -1) {
        var f = 0
        while (f < dot) {
            if (base[f] != '.') return base.substring(0, dot)
            f++
        }
    }
    return base
}

/** Best-effort open via `open`/`xdg-open`/`start` (Python `_open_file`); silently skips on failure. */
private fun openFile(path: String) {
    for (opener in listOf("open", "xdg-open", "start")) {
        if (which(opener)) {
            try {
                ProcessBuilder(opener, path)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: Exception) {
                // best-effort — never fail the run because a browser could not be launched
            }
            return
        }
    }
}

/** Minimal `shutil.which`: is [cmd] an executable file on any PATH entry? */
private fun which(cmd: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    return pathEnv.split(File.pathSeparatorChar).any { dir ->
        dir.isNotEmpty() && File(dir, cmd).let { it.isFile && it.canExecute() }
    }
}
