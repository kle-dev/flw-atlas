package com.flowable.atlas.cli

import com.flowable.atlas.diagram.DiagramArtifacts
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.render.ClaudeRenderer
import com.flowable.atlas.render.ExplorerHtmlRenderer
import com.flowable.atlas.render.OverviewRenderer
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
    var stdout = false; var open = false; var noCustom = false; var quiet = false
    @Suppress("UNUSED_VARIABLE") var verbose = 0
    var exprAllowlist = ""
    var customFunctions: String? = null

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
                    "--stdout" -> stdout = true
                    "--open" -> open = true
                    "--expr-allowlist" -> exprAllowlist = value(name, inline) ?: return 2
                    "--custom-functions" -> customFunctions = value(name, inline) ?: return 2
                    "--no-custom-functions" -> noCustom = true
                    "--verbose" -> verbose++
                    "--quiet" -> quiet = true
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
    if (listOf(all, json, html, summary, claude).count { it } > 1) {
        errln("error: argument --all/--json/--html/--summary/--claude: not allowed with one another")
        return 2
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
    val allow = exprAllowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val result = Atlas.extract(
        File(projectPath),
        exprAllowlist = allow.ifEmpty { null },
        discoverCustom = !noCustom,
        customPath = customFunctions?.let { File(it) },
    )

    // ---- status line (verbatim format) ----
    val stats = result["stats"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun stat(key: String): Int = (stats[key] as? Number)?.toInt() ?: 0
    val resolvedN = (result["resolvedRefs"] as? List<*>)?.size ?: 0
    val unresolvedN = (result["unresolvedRefs"] as? List<*>)?.size ?: 0
    val nDiag = (result["diagnostics"] as? List<*>)?.size ?: 0
    val cf = result["customFunctions"] as? Map<*, *>
    val status = buildString {
        append("${stat("models")} models $MIDDLE_DOT ${stat("java")} java $MIDDLE_DOT ${stat("nodes")} nodes $MIDDLE_DOT ")
        append("${stat("edges")} links $MIDDLE_DOT $resolvedN resolved / $unresolvedN unresolved refs")
        val suspectN = stat("suspectEdges")
        val dynN = stat("dynamicEdges")
        if (suspectN + dynN > 0) append(" $MIDDLE_DOT $suspectN suspect / $dynN dynamic links")
        if (cf != null) append(" $MIDDLE_DOT custom fns: ${cf["summary"]}")
        if (nDiag > 0) append(" $MIDDLE_DOT $WARN_SIGN $nDiag parse issue(s), see -v")
    }

    // name = os.path.splitext(os.path.basename(os.path.abspath(path.rstrip('/'))))[0] or "project"
    val abs = File(projectPath.trimEnd('/').ifEmpty { "." }).absoluteFile.normalize()
    val name = splitextName(abs.name).ifEmpty { "project" }
    val root = File(projectPath)

    // ---- --all: write all five artifacts into the -o directory (default ".") ----
    if (all) {
        val outdir = File(output ?: ".")
        outdir.mkdirs()
        val artifacts = listOf(
            "$name.summary.md" to SummaryRenderer.render(result, root),
            "$name.overview.md" to OverviewRenderer.render(result, root),
            "$name.graph.json" to MiniJson.stringify(result, 2),
            "$name.explorer.html" to ExplorerHtmlRenderer.render(result, root),
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
        val diagrams = DiagramArtifacts.render(result, root)
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
        return 0
    }

    // ---- single-artifact modes ----
    val (out, ext) = when {
        claude -> ClaudeRenderer.render(result, root) to "CLAUDE.md"
        summary -> SummaryRenderer.render(result, root) to "summary.md"
        html -> ExplorerHtmlRenderer.render(result, root) to "html"
        json -> MiniJson.stringify(result, 2) to "json"
        else -> OverviewRenderer.render(result, root) to "md"
    }

    if (stdout) {
        // sys.stdout.write(out + "\n")
        System.out.write((out + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
        return 0
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
    return 0
}

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
