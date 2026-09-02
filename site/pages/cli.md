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

That analyses the project, writes all five artifacts to `./atlas-output/<project>/`, and opens the
explorer in your browser. Two optional positionals and one flag of its own:

```bash
./atlas /path/to/project ./reports      # write into ./reports instead
./atlas /path/to/app.zip --no-open      # analyse an exported archive, do not open a browser
./atlas --help                          # usage, then exit
```

Anything else you pass is forwarded to the jar unchanged, so `./atlas /path/to/project --pretty` works.

What it does, in order:

1. Refuses to run if `java` is not on the `PATH` — install a JDK 21+, or point `JAVA` at one.
2. Finds the fat-jar: `$ATLAS_JAR` if set, else the newest `*-all.jar` next to the script, then in
   `lib/`, then in `cli/build/libs/`.
3. If there is no jar, builds one with `./gradlew :cli:shadowJar`. Set `ATLAS_NO_BUILD=1` to turn that
   into an error instead — the right setting on a machine that only has a JRE.
4. Derives the output directory from the target's name unless you gave one.
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
one path is allowed; `--` ends option parsing, so a path that begins with a dash still works.

Build the jar with `./gradlew :cli:shadowJar`, or download `cli-<version>-all.jar` from the
[latest release](https://github.com/kle-dev/flw-atlas/releases/latest). It needs nothing but a JRE 21+.

### Output format

These six are mutually exclusive — passing two is an error, not a silent preference.

| Flag | Writes | Default file name |
|---|---|---|
| *(none)* | The full Markdown report | `APP_OVERVIEW.md` |
| `--all` | All five artifacts into a **directory**, plus `<name>.diagrams/` when any model has a layout | `<name>.*` in `-o`, default `.` |
| `--summary` | The compact LLM-first overview | `APP_OVERVIEW.summary.md` |
| `--html` | The interactive explorer | `APP_OVERVIEW.html` |
| `--json` | The traversable graph | `APP_OVERVIEW.json` |
| `--claude` | Drop-in agent context | `CLAUDE.md` |
| `--claude-template` | The project-independent Flowable primer. Needs **no** path | stdout |

Only `--all` derives file names from the project (`<name>.summary.md`, `<name>.explorer.html`, …). A
single-artifact run writes `APP_OVERVIEW.*` unless you name the file with `-o`. See
[Generated artifacts](../artifacts/) for what each one contains.

### Options

| Flag | Meaning |
|---|---|
| `-o <path>`, `--output <path>` | Output file — or, with `--all`, the output **directory** (created if missing). Also accepts `-o<path>` and `--output=<path>`. |
| `--slice <type:key>` | Render one node with its full context instead of a whole report. A bare `<key>` matches any type, and every match is rendered. |
| `--stdout` | Write the single artifact to stdout and touch no files. |
| `--open` | Open the result in a browser. Applies to `--all` (the first HTML written) and to `--html`. |
| `--pretty` | Indent `graph.json`. It is minified by default. |
| `--expr-allowlist <list>` | Comma-separated expression namespaces / functions your project registers itself, so they stop being reported as *suspect*. See [Expressions](../expressions/#the-allowlist). |
| `--custom-functions <path>` | Where to look for frontend customisation sources, instead of the project root. |
| `--no-custom-functions` | Do not discover custom functions at all. |
| `--fail-on <list>` | Make the run **exit 1** when findings match — a comma-separated list of `error`, `warning` and/or [check ids](../checks/) (`--fail-on error`, `--fail-on missingRefs,invalidExpr`). Every artifact is still written first: a pipeline wants the report as well as the red build. An unknown value is a misuse (exit 2). |
| `-q`, `--quiet` | Silence the status lines on stderr. |
| `-v`, `--verbose` | List every parse issue the status line counts, one per line, after it. |
| `-h`, `--help` | Print the usage text and exit 0. |
| `--` | End of options; the next token is the path. |

The one-line CI recipe:

```bash
java -jar cli-<version>-all.jar . --all -o atlas-output --fail-on error
```

writes the five artifacts (keep them as build artifacts) and fails the job on any error-level finding —
a model that would not parse, an expression with a syntax error, a reference to a model that does not
exist. Tighten to `--fail-on warning` once the project is clean.

Short flags cluster: `-vq`, `-qv` and `-oreport.md` all work, and `o` consumes the rest of its token
or the next one.

### Flag precedence

`--slice` and `--pretty` sit outside the mutually-exclusive format group, which has two consequences
worth knowing because nothing warns you:

- `--all --slice X` is an error (exit 2), like any other conflict between two output requests.
- `--slice X --json` writes the slice and **ignores `--json`**.
- `--claude-template` returns before the path is even checked, and ignores `--stdout` (it prints to
  stdout anyway when no `-o` is given).

### Exit codes

| Code | When |
|---|---|
| `0` | Success. |
| `1` | The run succeeded and wrote its artifacts, but a finding matched `--fail-on`. |
| `2` | Argument misuse: an unknown flag, two format flags, `--all` with `--slice`, an unknown `--fail-on` value, a missing option value, a second positional, a path that does not exist, a missing path, or a `--slice` that matches no node. |

## The status line

Every run ends with one line on stderr — the health check. This is the fastest way to see whether
Atlas understood your project:

```
412 models · 88 java · 3104 nodes · 5192 links · 412 resolved / 23 unresolved refs · 17 suspect / 4 dynamic links · ⚠ 3 parse issue(s)
```

- **java** — the Java *and Kotlin* sources read (one parser handles both); test source sets are not
  counted, because they are not scanned.
- **resolved / unresolved refs** — references Atlas could and could not tie to something real.
- **suspect / dynamic links** — resolved, but not certainly: *suspect* came from a loose or
  cross-type match, *dynamic* from an expression-valued reference.
- **⚠ parse issues** — files that could not be read or fully parsed. These are never silent: they
  also appear in `graph.json`'s `diagnostics`, in the summary's Health block, in the overview's
  Findings section, and as a clickable badge in the explorer.

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
