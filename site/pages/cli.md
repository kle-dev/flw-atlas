# CLI reference

Atlas ships as a single self-contained fat-jar with no third-party dependencies. Everything the CLI
does, the IntelliJ plugin does too — both call the same `:core` engine in-process, so a report
generated from the terminal and one generated from the IDE are the same bytes.

There are two ways in, and it is worth knowing which you are using: the **`./atlas` launcher** is a
convenience wrapper that always produces the full artifact set, and the **fat-jar** is the real
interface with every flag.

## The `./atlas` launcher

```bash
./atlas /path/to/your-flowable-project
```

That analyses the project, writes all six artifacts (and the ER diagram page, for a project with tables)
to `atlas-output/<project>/` beside the launcher — not inside the analysed project — and opens the
explorer in your browser. `<project>` is the real directory's name, so `./atlas .` from inside a project
writes where `./atlas /path/to/project` would. The launcher is the `atlas` script at the repository's
root, and every [release](https://github.com/kle-dev/flw-atlas/releases/latest) carries it beside the
jar. Two optional positionals and one flag of its own:

```bash
./atlas /path/to/project ./reports      # write into ./reports instead
./atlas /path/to/app.zip --no-open      # analyse an exported archive, do not open a browser
./atlas --help                          # usage, then exit
```

Anything else you pass is forwarded to the jar unchanged, so `./atlas /path/to/project --pretty` works.

What it does, in order:

1. Refuses to run if `java` is not on the `PATH` — install a JDK 21+, or point `JAVA` at one.
2. Finds the fat-jar: `$ATLAS_JAR` if set, else the newest `*-all.jar` by modification time across the
   script's own folder, `lib/` and `cli/build/libs/` — one race, not an ordered search, so a fresh local
   build wins over an older download in `lib/`.
3. If there is no jar, builds one with `./gradlew :cli:shadowJar` — in a clone of the repository. A
   launcher downloaded from a release has no Gradle beside it and says what to download instead. Set
   `ATLAS_NO_BUILD=1` to turn the build into an error in a clone too — the right setting on a machine
   that only has a JRE.
4. Derives the output directory from the target's name unless you gave one — the name the jar gives the
   files, so the last line it prints, `✓ Done. Open: …`, names the explorer that was written.
5. Runs the jar with `--all -o <outdir>`, plus `--open` unless you passed `--no-open`.

| Environment variable | Effect |
|---|---|
| `JAVA` | The `java` binary to use. Default: `java` from the `PATH`. |
| `ATLAS_JAR` | Use exactly this jar and skip the search. |
| `ATLAS_NO_BUILD` | `1` = never invoke Gradle; fail with instructions instead. |

> `--no-open` is a **launcher** flag, not a CLI flag: the launcher strips it and omits `--open`. The
> jar itself has only the positive `--open`.

## The fat-jar

```bash
java -jar cli/build/libs/cli-*-all.jar <path> [options]
```

`<path>` is a project directory, a single model file, or an exported `.zip` / `.bar` archive. Exactly
one path is allowed; `--` ends option parsing, so a path that begins with a dash still works. A single
model file is a project of one: its references to models outside it are reported as missing. Any other
file, and a directory with no Flowable models, archives or Java sources in it, is a misuse (exit 2) —
a run that found nothing is the wrong directory, not a clean project.

A directory is read only inside itself: a symbolic link whose target lies outside it — a `vendor`
folder linked in from a sibling checkout, an `up -> ..` — is not followed, so one project's report never
carries another's models or Java literals. A linked folder, or a linked file of a kind Atlas reads, is
listed as a `skip` in the diagnostics; a linked `.editorconfig`, or a `build` folder Atlas would not
enter anyway, is not. A link that stays inside the project is followed. Analyse the folder the link
points at, or the parent of both, to include it.

Build the jar with `./gradlew :cli:shadowJar`, or download `cli-<version>-all.jar` from the
[latest release](https://github.com/kle-dev/flw-atlas/releases/latest). It needs nothing but a JRE 21+.

### Output format

These eight are mutually exclusive — passing two is an error, not a silent preference.

| Flag | Writes | Default file name |
|---|---|---|
| *(none)* | The full Markdown report | `APP_OVERVIEW.md` |
| `--all` | All six artifacts into a **directory** — the Playwright guide as `<name>.playwright.md` and its project map `<name>.playwright-map.md` — plus `<name>.diagrams/` when any model has a layout and `<name>.erd.html` when the project has a table | `<name>.*` in `-o`, default `./atlas-output/<name>/` |
| `--summary` | The compact LLM-first overview | `APP_OVERVIEW.summary.md` |
| `--html` | The interactive explorer | `APP_OVERVIEW.html` |
| `--erd` | The [ER diagram designer](../erd/): the project's tables and their relations, on a page of its own | `APP_OVERVIEW.erd.html` |
| `--json` | The traversable graph | `APP_OVERVIEW.json` |
| `--claude` | Drop-in agent context. Never replaces a `CLAUDE.md` Atlas did not write: if the project has its own, the run stops (exit 2) and asks for `-o` or `--stdout` | `CLAUDE.md` |
| `--playwright` | The [agent's guide to driving Flowable Work](../artifacts/#playwrightmd-driving-flowable-work-in-a-browser) in a browser, with this project's stages, tasks, required fields and button conditions; also a Claude Code skill. One file: the project map `--all` writes beside it is inline here, and with no `graph.json` beside it, its lookups say how to make one | `APP_OVERVIEW.playwright.md` |
| `--claude-template` | The project-independent Flowable primer. Needs **no** path | stdout |

Only `--all` derives file names from the project (`<name>.summary.md`, `<name>.explorer.html`, …). A
single-artifact run writes `APP_OVERVIEW.*` (or `CLAUDE.md`) unless you name the file with `-o`, and
without `-o` it writes it **into the analysed project directory** — for an archive or a model file, into
the folder it is in. See [Generated artifacts](../artifacts/) for what each one contains.

### Options

| Flag | Meaning |
|---|---|
| `-o <path>`, `--output <path>` | Output file — or, with `--all`, the output **directory**. Missing folders are created either way. Also accepts `-o<path>` and `--output=<path>`; a path that starts with a dash has to be given that way, since `-o --stdout` is a missing value, not a file called `--stdout`. An output that cannot be written — a file where `--all` needs a folder, a folder where a file goes, a read-only location — is refused before the analysis runs (exit 2). |
| `--slice <type:key>` | Render one node with its full context instead of a whole report. A bare `<key>` matches any type, and every match is rendered. When nothing matches exactly, the id or key in another case, a Java class's simple name, a model's file path (`processes/order.bpmn`, or the same file's absolute path) and a label are tried in that order, and the slice says what it matched by. A miss names the nearest ids (`did you mean: process:orderProcess`). |
| `--stdout` | Write the single artifact to stdout and touch no files. Not with `--all`, which writes a folder (exit 2). |
| `--open` | Open the result in a browser — with `open` on macOS, `xdg-open` on Linux, the default handler on Windows. Applies to `--all` (the first HTML written), `--html` and `--erd`. Where nothing can open a browser it says so, and the run still succeeds. |
| `--pretty` | Indent `graph.json`. It is minified by default. |
| `--expr-allowlist <list>` | Comma-separated expression namespaces / functions your project registers itself, so they stop being reported as *suspect*. See [Expressions](../expressions/#the-allowlist). |
| `--custom-functions <path>` | Where to look for frontend customisation sources, instead of the project root. |
| `--no-custom-functions` | Do not discover custom functions at all. |
| `--fail-on <list>` | Make the run **exit 1** when findings match — a comma-separated list of `error`, `any`, `defects`, `advice` and/or [check ids](../checks/) (`--fail-on error`, `--fail-on defects`, `--fail-on missingRefs,invalidExpr`). `defects` matches every finding of a defect-kind check and stays green on advice. Every artifact is still written first: a pipeline wants the report as well as the red build. An unknown value is a misuse (exit 2), and so is an empty list, so an unset CI variable (`--fail-on "$ATLAS_FAIL_ON"`) cannot switch the gate off. `warning` is accepted and means the same as `any`: it has always matched every finding regardless of severity, and pipelines were told to tighten to it, so narrowing it now would make them quietly stop failing on errors. Waived findings never match. A failed run lists the matching findings on stderr after its count line, worst first and one per line — severity, check, the file (or, for a finding with no file, its node id), the model and element, the message — the first 50 of them, then where to read all of them. |
| `--annotate github` | Also print the findings that fail the run as GitHub Actions workflow commands, on stdout, so GitHub shows them as annotations on the run and on the pull request's files: `::error` or `::warning` by severity, with the file relative to the working directory (run the job from the checkout's root) and the line where the file has one of its own — not for a script finding, whose line counts from the start of the script, and not for a model inside an archive, where GitHub can only point at the archive. `github` is the only value. It needs `--fail-on`, and refuses an artifact on stdout (`--stdout`, or a `--slice` without `-o`), which the commands would cut into. |
| `--waivers <path>` | The [accepted-findings file](../checks/#accepting-a-finding). Defaults to `waivers.json` beside the artifacts — the same folder the explorer's *Save* writes it to: the `--all` directory; the folder of the file `-o` names; without `-o`, the folder the single artifact is written to (the project directory, or the folder of the archive or model file); and the working directory for `--stdout` and a `--slice` without `-o`, which write no file. A path you name that does not exist is reported on stderr, and nothing is waived; the run goes on. A file that does not parse, a rule naming a check Atlas does not know and a key the file does not use are reported too — see [Accepting a finding](../checks/#accepting-a-finding). |
| `--waiver-author <name>` | Prefills the `by` of a rule accepted from the generated explorer page. Left out, a rule written from a CLI-generated page carries no author; inside the IDE the plugin supplies the project's git identity. |
| `--no-waivers` | Ignore that file and report every finding — the audit run, for answering "what are we hiding?". |
| `--fail-on-stale-waivers` | Make the run **exit 1** when a waiver matched nothing or has expired. Separate from `--fail-on` on purpose: a stale waiver is a problem with your file, not a finding about your project. With both, both verdicts are printed before the run exits. |
| `-q`, `--quiet` | Silence the status lines on stderr. Errors are still printed. |
| `-v`, `--verbose` | List every parse issue the status line counts, one per line, after it — as `path:line` when the parser says where a file broke; with `--all`, every diagram written instead of one line for the folder; and the stack trace of a run that failed (exit 3). |
| `-h`, `--help` | Print the usage text and exit 0. |
| `--` | End of options; the next token is the path. |

The one-line CI recipe:

```bash
java -jar cli-<version>-all.jar . --all -o atlas-output --fail-on error
```

writes the six artifacts (keep them as build artifacts) and fails the job on any error-level finding —
a model that would not parse, an expression with a syntax error, a reference to a model that does not
exist. Tighten to `--fail-on any` once the project is clean, and record what you decide to live with in
[`waivers.json`](../checks/#accepting-a-finding) rather than dropping the gate.

A red run names what made it red: after the count line, the job log lists the matching findings, one
per line, so the reason for the build is in the log and not only in the artifacts. On GitHub Actions,
add `--annotate github` and the same findings appear as annotations on the pull request.

Short flags cluster: `-vq`, `-qv` and `-oreport.md` all work, and `o` consumes the rest of its token
or the next one. A flag that takes no value refuses one: `--all=foo` and `--stdout=false` are errors,
not a silent `--all` or `--stdout`.

### Flag precedence

`--slice` and `--pretty` sit outside the mutually-exclusive format group, which has consequences
worth knowing because nothing warns you about the last three:

- `--all --slice X` is an error (exit 2), like any other conflict between two output requests.
- `--all --stdout` is an error too (exit 2): `--all` writes a folder of files, which stdout cannot hold.
- `--slice X --json` writes the slice and **ignores `--json`**.
- `--slice X --stdout -o file` prints the slice and writes no file: `--stdout` touches no files.
- `--claude-template` returns before the path is even checked, and ignores `--stdout` (it prints to
  stdout anyway when no `-o` is given).

### Exit codes

| Code | When |
|---|---|
| `0` | Success. |
| `1` | The run succeeded and wrote its artifacts, but a gate failed: a finding matched `--fail-on`, or `--fail-on-stale-waivers` found a stale waiver. Nothing else exits 1. |
| `2` | Misuse, found before anything is written: an unknown flag, two format flags, `--all` with `--slice` or `--stdout`, an unknown or empty `--fail-on` value, an `--annotate` value other than `github`, `--annotate` without `--fail-on` or beside an artifact on stdout, a missing option value (or one that is the next option), a value given to a flag that takes none, a second positional, a path that does not exist, a missing path, a file that is not a model or archive, a path with no Flowable models, archives or Java sources, an output that cannot be written, a hand-written `CLAUDE.md` that `--claude` would replace, or a `--slice` that is empty or matches no node. |
| `3` | The run failed once under way — an artifact could not be written (a disk that filled up, a name taken by a folder), or an unexpected error. Artifacts may be missing or partial. The reason is one line on stderr; `-v` adds the stack trace. |

## The status line

Every run that writes the report — `--all`, or one artifact to a file — ends with one line on stderr: the
health check. This is the fastest way to see whether Atlas understood your project:

```
Flowable Atlas — order-management: 412 models (9 files · 3 archives) · 88 java · 3104 nodes · 5192 links · 412 resolved / 23 unresolved refs · 17 suspect / 4 dynamic links · custom fns: myfns.* (12) from frontend/customisation.ts · ⚠ 2 script issue(s) · ⚠ 3 parse issue(s), see -v · 19 defects · 24 advice · 5 waived
```

`--all` lists each file it wrote before it — the diagrams as one line for their folder,
`✓ …/order-management.diagrams/ (249 SVGs)`, unless `-v` asks for each — and a single-artifact run
prints it as `wrote <file> — …`. Only the detail you asked for follows it: the parse issues with `-v`,
a stale or unexplained waiver, and the verdict of `--fail-on` with the findings it names. A `--stdout` run and a `--slice` keep
stderr for warnings and errors (a slice written with `-o` names its file): the summary carries the same
scale line.

- **java** — the Java *and Kotlin* sources read (one parser handles both); test source sets are not
  counted, because they are not scanned.
- **resolved / unresolved refs** — references Atlas could and could not tie to something real.
- **suspect / dynamic links** — resolved, but not certainly: *suspect* came from an ambiguous
  match, *dynamic* from an expression-valued reference.
- **custom fns** — the frontend expression functions the project registers itself, and where they were
  found; see [Expressions](../expressions/).
- **⚠ script issues** — script tasks and listeners whose code has a problem Atlas can see.
- **⚠ parse issues** — files that could not be read or fully parsed, and folders that could not be
  listed. These are never silent: they also appear in `graph.json`'s `diagnostics`, in the summary's
  Health block, in the overview's Findings section, and as a clickable badge in the explorer.
- **defects · advice** — the open findings of each [kind](../checks/): what is wrong now, and what is
  worth a look.
- **waived** — findings your [`waivers.json`](../checks/#accepting-a-finding) accepts; they are not
  counted above.
- **⚠ stale waivers** — rules in that file that matched nothing or have expired;
  `--fail-on-stale-waivers` makes them fail the run.

Add `-q` to suppress it. Everything it counts is also in the artifacts themselves, so a scripted
run does not need to parse this line.

## Examples

```bash
# The everyday case: everything, opened in a browser
./atlas ~/projects/order-management

# Orientation for an agent, straight to stdout
java -jar cli-<version>-all.jar ~/projects/order-management --summary --stdout

# One model in full context, because the task is about one model
java -jar cli-<version>-all.jar ~/projects/order-management --slice process:orderProcess --stdout

# An exported app archive, artifacts into ./reports, no browser
java -jar cli-<version>-all.jar ~/Downloads/order-app.zip --all -o ./reports

# The graph, readable, with your own EL namespaces accepted
java -jar cli-<version>-all.jar ~/projects/order-management --json --pretty \
  --expr-allowlist myfns,util:format -o graph.json
```
