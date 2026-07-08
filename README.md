# Flowable Atlas

Map **any** Flowable project (app models **+** Java code) into:

- 🧭 **`<project>.explorer.html`** — a self-contained, offline, interactive explorer (open by double-click). Browse processes, cases, forms, services, data objects, REST endpoints, Java delegates/bots/listeners, user groups — and click through every relationship in both directions.
- ⚡ **`<project>.summary.md`** — a compact (~few KB) LLM-first overview: apps, inventory, entry points, integrations, hotspots, external surface.
- 📄 **`<project>.overview.md`** — the full human/LLM Markdown report (every model, relationship and the access map).
- 🕸️ **`<project>.graph.json`** — the full traversable model↔code graph for agents/LLMs to query.
- 🤖 **`<project>.CLAUDE.md`** — drop-in context for AI agents: a generic Flowable primer **+** this project's auto-discovered facts (apps, inventory, where models/Java live, key conventions, entry points, build). Copy it into your project root as `CLAUDE.md` so an agent understands Flowable *and* this app. (See `CLAUDE.template.md` for the generic, hand-editable version.)

No dependencies — just **Python 3**. Works on a project directory, on loose model files, and on exported `.zip` / `.bar` archives.

The HTML explorer links each process's service tasks straight to the Java class & method (e.g. `${myService.doWork(...)}` → `MyService.doWork()`), and lets you click through every relationship in both directions.

## Quick start

```bash
./atlas /path/to/your-flowable-project
```

That analyzes the project, writes all four artifacts to `./atlas-output/<project>/`, and opens the HTML explorer in your browser.

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

```bash
python3 flowable_atlas.py <project> --summary --stdout     # compact overview to stdout
python3 flowable_atlas.py <project> --html  -o explorer.html
python3 flowable_atlas.py <project> --json  -o graph.json
python3 flowable_atlas.py <project> --claude               # writes a ready-to-use CLAUDE.md
python3 flowable_atlas.py <project>                         # full Markdown report (default)
python3 flowable_atlas.py <project> --all   -o ./out        # all artifacts (what ./atlas does)
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

- The explorer frontend lives in `frontend/explorer.{html,css,js}` (editable, lintable). After
  changing it, run `python3 tools/embed_frontend.py` to refresh the embedded copies inside
  `flowable_atlas.py` (the distributed single file). A pytest guard fails on drift.
- Tests: `python3 -m pytest` — golden tests against `tests/fixtures/miniproject`, parser unit
  tests, CLI contract tests (the artifact names the IDEA plugin depends on), and the expression
  validator parity suite shared with the IntelliJ plugin.
- Regenerate goldens after an intended output change: `ATLAS_UPDATE_GOLDEN=1 python3 -m pytest`.

## For LLMs / agents

- Start with **`--summary`** (small, high-signal) as first context.
- Then have the agent load **`--json`** and traverse the `graph` (`nodes` + `edges`) to answer specific questions, instead of pasting the whole report.

## Requirements

Python 3.8+. No third-party packages.
