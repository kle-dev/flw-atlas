# Flowable Atlas — Features

*IntelliJ IDEA plugin, v0.8.2.* A summary of what Flowable Atlas provides, grouped by area.
Everything is resolved against the Flowable models that actually live in your repository.

## Atlas Explorer & Hub

- **Generate Atlas explorer** — scans a Flowable project and produces an interactive
  `*.explorer.html` dependency map of models, code, and their references.
- **Embedded Explorer tab** — renders the generated `*.explorer.html` as a JCEF "Atlas Explorer"
  tab inside the IDE editor, theme-synced with the IDE.
- **Atlas Hub tool window** — the plugin's control center: model-index status, list of generated
  explorers, and quick actions.
- **Post-generation balloon** — after generating, offers to open the explorer in the browser or in
  the IDE.

## Model navigation & validation

- **Go-to / Find Usages on model keys** — Ctrl-click a key literal (`calledElement`, `formKey`,
  `decisionRef`, …) in BPMN/CMMN XML jumps to the model file.
- **Key completion** — autocompletes model keys at cross-reference attributes in BPMN/CMMN XML.
- **Broken-reference inspection** — flags an unknown model key at a cross-reference attribute
  (broken deployment).
- **Hover / Ctrl-Q docs** — shows a model key's type, name, and file.

## Java ↔ model integration

- **Implicit usage provider** — stops Java referenced from models (delegates, `${bean.method()}`,
  listeners) being reported as unused.
- **Model usage search** — Find Usages / Ctrl-B on a delegate class or `${bean.method()}` lists the
  model files that use it.
- **Generate Java bean** — Alt-Enter on a data-object definitionKey generates a typed Java bean from
  its fields.

## Liquibase support

- **Column / table completion** — completes `<column name="…">` and `tableName` from the backing
  service model.
- **Coverage inspection** — flags changelog columns not mapped in the backing Flowable
  service/data-object model.

## Flowable expression support

- **Two dialects** — backend `${…}`/`#{…}` (JUEL) and frontend `{{…}}` (flw.*), injected inline
  wherever expressions live (including `.form` JSON).
- **Syntax highlighting** — token coloring, rainbow/matched parentheses and brackets, and
  unmatched-brace flagging.
- **Inspections** — structural syntax squiggles plus semantic findings with per-profile severity.
- **Completion & docs** — functions, root objects, and the project's variables/form-fields; Ctrl-Q
  shows a function's signature and doc line.
- **Expression Playground** — a scratch panel (also a second tab on explorers) for typing an
  expression to get live validation + completion; Alt-Enter reworks an injected fragment there,
  pre-filled.

## Settings & housekeeping

- **Settings tree** — an app-level root page (core toggles) with three project-level child pages.
- **Model-constants regeneration** — rebuilds the model-constants class automatically when models
  are added, removed, or edited.
