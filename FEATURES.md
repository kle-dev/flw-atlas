# Flowable Atlas — Features

*IntelliJ IDEA plugin, v0.10.8.* A summary of what Flowable Atlas provides, grouped by area.
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
- **View model diagram** — a gutter icon on a model-key literal opens the model's diagram in the IDE,
  so you can see the process / case / decision without opening Flowable Design. It prefers the `.svg`
  bundled by an older Design export, and otherwise **renders the diagram itself** from the model's
  layout (BPMN/CMMN/DMN diagram interchange) — so it keeps working with newer Design exports that no
  longer bundle a `.svg`. The same rendering also powers the **Diagrams (SVG)** generation artifact and
  is embedded inline in each node's detail view in the Explorer HTML.
- **Recognize model keys anywhere** *(opt-in)* — enable *Settings → Tools → Flowable Atlas → "Recognize
  model keys anywhere in code"* and any Java string literal whose value equals a known model key gets
  the diagram icon, Ctrl-click navigation, Find Usages and hover — not only at a recognized Flowable API
  call such as `startProcessInstanceByKey("…")`.
- **Diagrams for archived models** — models packaged inside a `.zip`/`.bar`/Design app export render
  their diagram just like loose model files, in the Explorer HTML and the Diagrams (SVG) artifact.
- **Version at a glance** — the generated Explorer HTML footer and the Atlas Hub show the Atlas version,
  so it's clear which build produced a given page.

## Java ↔ model integration

- **Implicit usage provider** — stops Java referenced from models (delegates, `${bean.method()}`,
  listeners) being reported as unused.
- **Model usage search** — Find Usages / Ctrl-B on a delegate class or `${bean.method()}` lists the
  model files that use it.
- **Model-key completion in Java** — completes process / case / decision / form / … keys at Flowable
  API call sites, with cascade completion (operation → value fields), start variables
  (`builder.variable(…)`), and messages / signals / variables / task ids.
- **Go-to on Java literals** — Ctrl-click a key, an `operation(…)` or a `value(…)` literal at an API
  call site jumps to the model (operations / values resolve to the backing service model).
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

## Flowable Design

- **Pull from Design** — downloads the configured apps' exports from a Flowable Design server into the
  project folder and rebuilds the model index.
- **Post-pull drift warning** — after a pull, flags model keys that were present before but are now
  gone, so code (or models) still referencing them can be fixed before they break.

## Settings & housekeeping

- **Settings tree** — an app-level root page (core toggles) with three project-level child pages.
- **Model-constants regeneration** — rebuilds the model-constants class automatically when models
  are added, removed, or edited.
