# Flowable Atlas

Map **any** Flowable project (app models **+** Java code) into:

- 🧭 **`<project>.explorer.html`** — a self-contained, offline, interactive explorer (open by double-click). Browse processes, cases, forms, services, data objects, REST endpoints, Java delegates/bots/listeners, user groups — and click through every relationship in both directions.
- ⚡ **`<project>.summary.md`** — a compact (~few KB) LLM-first overview: apps, inventory, entry points, integrations, hotspots, external surface.
- 📄 **`<project>.overview.md`** — the full human/LLM Markdown report (every model, relationship and the access map).
- 🕸️ **`<project>.graph.json`** — the full traversable model↔code graph for agents/LLMs to query.
- 🤖 **`<project>.CLAUDE.md`** — drop-in context for AI agents: a generic Flowable primer **+** this project's auto-discovered facts (apps, inventory, where models/Java live, key conventions, entry points, build). Copy it into your project root as `CLAUDE.md` so an agent understands Flowable *and* this app. (See `CLAUDE.template.md` for the generic, hand-editable version.)

A single self-contained **JVM** tool — no third-party dependencies, just a **JRE 21+**. The `./atlas` launcher runs the standalone CLI fat-jar (building it on first run). Works on a project directory, on loose model files, and on exported `.zip` / `.bar` archives.

The HTML explorer links each process's service tasks straight to the Java class & method (e.g. `${myService.doWork(...)}` → `MyService.doWork()`), and lets you click through every relationship in both directions.

## Quick start

```bash
./atlas /path/to/your-flowable-project
```

That analyzes the project, writes all five artifacts to `./atlas-output/<project>/`, and opens the HTML explorer in your browser. The first run builds the CLI fat-jar via Gradle; subsequent runs reuse it.

Optional output directory and flags:

```bash
./atlas /path/to/project ./reports          # write into ./reports
./atlas /path/to/app.zip --no-open           # analyze an exported archive, don't auto-open
```

> First run not executable? `chmod +x atlas`

## What it understands

It discovers models (`.bpmn`, `.cmmn`, `.dmn`, `.form`, `.app`, `.service`, `.data`, `.agent`, `.action`, `.sequence`, …) — loose **and** inside `.zip`/`.bar` — plus `.java` and Liquibase changelogs, then resolves the relationships between them:

- App → its models · process/case → called process/case/decision/form
- `${bean.method()}` / `delegateExpression` / listeners → the **Java class & method** (with `file:line`)
- form / process → **REST endpoint** → the serving **controller**
- action → **bot** (the Java `BotService` class) · agent → tools
- data object → backing **service**, columns & **Liquibase** table · service → Liquibase (`referencedLiquibaseModelKey` + `tableName`)
- **who can do what** — candidate (starter) groups, app/page access, data-object identity links, security policies
- sequences → the cases/processes that use them · Java → Java (dependency injection)

Whatever can't be resolved in the project (Flowable platform beans, external REST) is listed separately so real gaps stand out.

## Advanced — single artifacts

`./atlas` always writes the full `--all` set. For a single artifact, run the CLI fat-jar directly
(`cli/build/libs/*-all.jar`, built by `./atlas` on first run or `./gradlew :cli:shadowJar`):

```bash
java -jar cli/build/libs/*-all.jar <project> --summary --stdout   # compact overview to stdout
java -jar cli/build/libs/*-all.jar <project> --html  -o explorer.html
java -jar cli/build/libs/*-all.jar <project> --json  -o graph.json
java -jar cli/build/libs/*-all.jar <project> --claude             # writes a ready-to-use CLAUDE.md
java -jar cli/build/libs/*-all.jar <project>                       # full Markdown report (default)
java -jar cli/build/libs/*-all.jar <project> --all   -o ./out      # all artifacts (what ./atlas does)
```

Useful flags:

- `-v` / `-vv` — progress and per-file diagnostics on stderr; `-q` silences the summary line.
- `--expr-allowlist myfns,util:format,flw.custom` — expression-function namespaces/functions your
  project registers itself; "unknown function" findings about them are suppressed instead of shown
  as *suspect* in the explorer.

Every run ends with a one-line health check on stderr (`… 412 resolved / 23 unresolved refs · ⚠ 3
parse issue(s)`), and parse/read failures surface in **all** artifacts: a `diagnostics` list in
`graph.json`, a warning banner in the summary, the Warnings section of the overview, and a
clickable **⚠ parse issues** badge in the explorer header — so missing data is never silent.

## Development

The tool is a Gradle multi-module JVM project: `:core` (the pure-Kotlin engine — parsing, graph,
expression validation, rendering), `:cli` (the standalone fat-jar `./atlas` runs) and `:idea-plugin`
(the IntelliJ plugin, which consumes `:core` in-process).

- The explorer frontend lives in `frontend/explorer.{html,css,js}` (editable, lintable) and is bundled
  into `:core` as resources (`core/src/main/resources/frontend/`).
- Build & test everything: `./gradlew build`. This runs the `:core` golden tests against
  `tests/fixtures/miniproject`, the parser unit tests, the `:cli` contract tests (the artifact names
  the IDEA plugin depends on) and the shared expression-validator parity suite.

## For LLMs / agents

- Start with **`--summary`** (small, high-signal) as first context.
- Then have the agent load **`--json`** and traverse the `graph` (`nodes` + `edges`) to answer specific questions, instead of pasting the whole report.

## Requirements

A **JRE 21+** to run `./atlas` (a JDK 21+ to build from source). No third-party packages.
