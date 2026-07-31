# Flowable Atlas

Map **any** Flowable project (app models **+** Java code) into:

- 🧭 **`<project>.explorer.html`** — a self-contained, offline, interactive explorer (open by double-click). Browse processes, cases, forms, services, data objects, REST endpoints, Java delegates/bots/listeners, user groups — and click through every relationship in both directions.
- ⚡ **`<project>.summary.md`** — a compact (~few KB) LLM-first overview: apps, inventory, entry points, integrations, hotspots, external surface.
- 📄 **`<project>.overview.md`** — the full human/LLM Markdown report (every model, relationship and the access map).
- 🕸️ **`<project>.graph.json`** — the full traversable model↔code graph for agents/LLMs to **query** (not to read whole): every node carries `usedBy`, so relationships resolve in both directions, and a `_schema` key documents the shape and ships `jq` recipes.
- 🤖 **`<project>.CLAUDE.md`** — drop-in context for AI agents: a generic Flowable primer **+** this project's auto-discovered facts (apps, inventory, where models/Java live, key conventions, entry points, build). Copy it into your project root as `CLAUDE.md` so an agent understands Flowable *and* this app. (`CLAUDE.template.md` is the project-independent primer alone — generated from the same source, so it cannot drift; `--claude-template` prints it without a project.)

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
java -jar cli/build/libs/*-all.jar --claude-template               # the generic primer, no project needed
java -jar cli/build/libs/*-all.jar <project> --slice process:myProcess --stdout
```

`--slice <type:key>` (or just `--slice <key>`) is the tier between the summary and the graph: one node
with its full context — what it uses, **who uses it**, and the findings that touch it — sized for an agent
that has been asked to change one model.

Useful flags:

- `-v` / `-vv` — progress and per-file diagnostics on stderr; `-q` silences the summary line.
- `--expr-allowlist myfns,util:format,flw.custom` — expression-function namespaces/functions your
  project registers itself; "unknown function" findings about them are suppressed instead of shown
  as *suspect* in the explorer.
- `--pretty` — indent `graph.json`. It is minified by default: a model's body is stored once in its
  top-level bucket and its graph node points there with `data.dataIn`, which together with minification
  roughly halves the file (4.8 MB → 2.5 MB on a large real project).

Every run ends with a one-line health check on stderr (`… 412 resolved / 23 unresolved refs · ⚠ 3
parse issue(s)`), and parse/read failures surface in **all** artifacts: a `diagnostics` list in
`graph.json`, a warning banner plus a Health block in the summary, the Findings section of the overview,
and a clickable **⚠ parse issues** badge in the explorer header — so missing data is never silent.

Beyond parse failures, every artifact now carries the same health findings (`findings` / `checks` in
`graph.json`): invalid and suspect expressions, script syntax errors, missing model references,
orphan/superseded Liquibase changelogs, schema gaps, unused forms/operations/custom functions, and
variables only a script mentions.

## Development

The tool is a Gradle multi-module JVM project: `:core` (the pure-Kotlin engine — parsing, graph,
expression validation, rendering), `:cli` (the standalone fat-jar `./atlas` runs) and `:idea-plugin`
(the IntelliJ plugin, which consumes `:core` in-process).

- The explorer frontend lives in `core/src/main/resources/frontend/explorer.{html,css,js}` — plain,
  editable files read at render time by `ExplorerHtmlRenderer` and inlined into the generated page.
  (There is no separate top-level `frontend/` copy; the README used to point at one.)
- Build & test everything: `./gradlew build`. This runs the `:core` golden tests against
  `core/src/test/resources/miniproject`, the parser unit tests, the `:cli` contract tests (the artifact
  names the IDEA plugin depends on) and the shared expression-validator parity suite.
- The goldens in `core/src/test/resources/golden/` pin the generated artifacts byte-for-byte. When a
  change to them is intended, re-baseline with **`./gradlew :core:updateGoldens`** and review the diff
  (it also regenerates `CLAUDE.template.md` from `ClaudeRenderer`).

## For LLMs / agents

Four tiers, smallest first — use the smallest one that answers the question:

1. **`<project>.CLAUDE.md`** — drop it in the repo root as `CLAUDE.md`. A Flowable primer, this project's
   discovered facts and wiring examples, its open findings, and a cheatsheet of the EL namespaces, script
   bindings and platform beans that actually exist (so the agent stops inventing them).
2. **`--summary`** (a few KB) — orientation: apps, inventory, entry points, integrations, hotspots, health.
3. **`--slice <type:key>`** — one model in context, both directions, when the task is about one model.
4. **`--json`** — query it with `jq` (see `_schema.recipes` inside the file). Never paste it whole.

`<project>.overview.md` sits between 2 and 4 for a human: every model in **execution order**, the access
map, the data layer (service ↔ Liquibase table ↔ data object) and every finding with `file:line`.

## Requirements

A **JRE 21+** to run `./atlas` (a JDK 21+ to build from source). No third-party packages.
