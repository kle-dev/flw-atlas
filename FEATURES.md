# Flowable Atlas — Features

*IntelliJ IDEA plugin, v0.10.12.* A summary of what Flowable Atlas provides, grouped by area.
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
- **In/out parameters** — every variable mapping a model passes into, or takes back out of, the things
  it calls: call activities and process/case tasks (`flowable:in`/`out`), Service-Registry, Agent,
  Data-Object and HTTP tasks (`inputParameter` / `outputParameter` / `errorOutputParameter` /
  `outputVariableName`), Send-/Receive-Event tasks (`eventInParameter` / `eventOutParameter`),
  Init-Variables (`variableMapping`), result variables, Action Bots (`signalVariableNames`, bot `config`,
  `flw.getInput` / `flw.setOutput`) and **form/page buttons** — an Action, REST, Service, Agent or
  Create-Instance button's "Send payload map" and "Store response attributes"
  (`sendPayloadMapping` / `responsePayloadMapping` / `errorResponsePayloadMapping`, plus REST headers).
  Shown per element in the node's detail view; a task's static **field injections** (an HTTP task's
  request URL/method) live inside its entry in the *Service tasks* section, next to its implementation,
  result variable and callee — one place per task instead of three parallel lists. The declared in/out
  contract is shown on each `.service` operation.
  Each group names **what it calls** — `→ notifyCustomerAction`, `→ custSvc`, `→ subProcess`, an HTTP
  task's URL — with a link to that model when it's in the project.
- **Called with** — the mirror view: any called model (action, agent, service, data object, process, case,
  event, bot) also lists the payload its *callers* pass, so you can check in one place whether a form
  button's names line up with what the bot reads via `flw.getInput(…)`.
- **Find a parameter** — ⌘K/Ctrl-K matches parameter names on both sides of a mapping (the caller's
  variable or `{{binding}}` *and* the callee's contract name) and shows which mapping matched; picking a
  hit **jumps straight to the matching row**, expands its section and highlights it — and "copy link"
  keeps that highlight for whoever you send it to. A variable's detail view lists every mapping that
  reads or writes it, and a **Variable · parameter** sidebar category collects the variables that travel
  through one.
- **Checks — everything worth a look, in one tab** — its own sidebar section (*Models · Integration ·
  Code · Expressions · **Checks** · …*) holding the review lists, plus a tab that collects every finding
  on one page: parse issues with the analyzer's own message, expressions flagged as invalid or suspect
  (with what is wrong and who uses them), schema gaps per service, keys referenced but never defined,
  unused forms / operations / custom functions, orphan or superseded changelogs, variables only a script
  guess supports, and the count of uncertain (≈ / ƒ) links. Every block links to its drill-down list, and
  the dashboard's health cards jump straight into the matching block.
- **Script tasks tab** — under *Integration*, one sidebar entry listing **every script in the project**: BPMN script tasks,
  CMMN `flowable:type="script"` plan items, execution/task-listener scripts and action bot scripts,
  grouped by model, each with its Design element name (*Script task*, *Execution listener · end*, *Bot
  script*), language, line count, documentation, result variable and the **variables it touches**
  (linked; a bare-identifier read marked `≈`). Chips narrow to one kind, one text filter searches names,
  languages *and the code itself*, one control expands or collapses every body, and *in model ↓* jumps to
  that element in its model. Reading all the code of a project no longer means opening every model in turn.
- **Find anything, and see where it matched** — ⌘K/Ctrl-K searches *every* string Atlas parsed, not a
  list of blessed fields: script bodies, element documentation, flow conditions, field injections,
  listener classes, DMN cells. Each hit says where it came from (`script · stampTask`,
  `doc · approveTask`, `DMN annotation · r1`), name matches always rank above free-text ones, and
  picking a hit opens that element's row in the detail panel.
- **Collapsible detail sections** — every section on a node's page folds away and starts collapsed
  (except the diagram), so a process with 35 parameters is still skimmable; what you open stays open as
  you walk the graph, there's an *expand all* control, and long parameter lists get their own text filter
  and in/out/error direction chips.
- **Model structure sections** — the data Atlas parses is on the page, linked into the graph: a
  form/page's **Fields** (id → variable, label, type, required, bound value) and **Data sources**;
  a process's **User tasks** (form, candidate groups, assignee, due date/priority), **Script tasks**
  (with the Groovy/JS body), **Service tasks** (implementation, callee model, result variable, field
  injections — a script-typed task shows its script as code), **Events & timers**, **Multi-instance**,
  **Flow conditions** (element *names*, not ids, each locatable on the diagram) and **Listeners**; a
  case's **Plan model** tree (stages/milestones/tasks with links to the models they use, each item's
  **entry ◇ / exit ◆ criteria with their sentry conditions inline**), **Script tasks** (CMMN
  `flowable:type="script"` bodies), **Sentries** (named by what they guard — *entry of Review*, not
  *sentry3*) and **Event listeners & timers**; criterion diamonds on the diagram are clickable and
  show their condition; a security policy's **Permissions** (roles link to groups); an agent's
  **Tools** and **Operations** (with prompts); an app's **Variables** and **Pages**; an action's
  **Bot script**; a decision's **Rules** — the decision table itself, every input/output cell with its
  annotation. **Element documentation** (what the modeller wrote about a task, event or gateway) and
  **Listeners** are listed per element, including the execution listeners on service tasks, gateways and
  events that used to be dropped.
- **Variable graph covers forms, decisions and scripts** — form field ids and DMN inputs/outputs are
  indexed as variables, so a variable's page lists the forms and decision tables that read or write it
  (and vice versa), alongside its in/out parameter flows. Script bodies count too: a
  `setVariable('x', …)` in a script task, a CMMN script item or a listener script names the variable
  **and the element** it happens on, and the bare identifiers a Groovy/JS script reads out of its scope
  are reported as what they are — a good guess, marked `≈ read` rather than presented as a declaration.
- **Legacy Design exports** — the "typed-directory" export format (`form-models/`, `service-models/`, …
  with each model wrapped in `{key, name, editorJson}`) is unwrapped and parsed, from a zip or a loose
  workspace; old Oryx-editor forms/pages are registered by key so references resolve and their
  `{{…}}` bindings are indexed, and the root app wrapper becomes the app node with *contains* membership.
- **Design vocabulary everywhere, with working hover help in the IDE** — labels follow Flowable Design's
  wording, and every explanation tooltip is rendered by the Explorer's own bubble, which also works in
  the embedded JCEF viewer (native tooltips never show there).
- **Diagrams with real type icons** — each element carries its Flowable type glyph (User task, Service
  task, Service registry, AI Agent, Data object, HTTP, Script, Email, Timer/Message/Signal/Error events,
  …), the BPMN markers that belong to it (multi-instance, loop, non-interrupting boundary, thick-bordered
  call activity), and a hover tooltip naming the element in **Design's own words**. The type is taken from
  the Design stencil where the model has one, else from `flowable:type`, else from the element itself.
- **Zoom and full screen** — the diagram has zoom / fit controls, ⌘/Ctrl-scroll-to-zoom (a plain scroll
  keeps scrolling the page — the diagram never captures it) and drag-to-pan, plus a full-screen view
  (`+` / `−` / `0`, Esc to close) for the diagrams that are too big for the panel.
- **Interactive diagram** — click any element (task, gateway, event, sequence flow) for an info card
  with its element id, in/out parameter mappings, the variables they touch, its implementation / callee
  model / form (all linked), and — on gateways and flows — the flow conditions with resolved target
  names. *Show in details ↓* jumps to the element's rows in the sections below; the ⌖ button on
  parameter groups, tasks, events and flow conditions pans the diagram to that element and highlights
  it. Element ids and names are indexed in ⌘K search. The card also shows the element's own
  documentation and the listeners it runs. It is a **free-floating window**: it starts docked to the
  diagram's top-right corner but can be dragged and resized anywhere in the app — well past the
  drawing area, which is what makes it usable when the diagram panel is narrow (an IDE tool window).
  Size and position are remembered.
- **Design vocabulary, explained** — node types, element types, parameter kinds and relationships are
  named the way Flowable Design names them (*Decision tables*, *AI agents*, *Services*, *Send payload
  map*, *Decision task → decision table*, *Execution listener*, *Case plan model*), each with a hover
  text explaining what it means — so the map is readable without having built Atlas. The same words are
  used in the generated Markdown (`overview.md` says *User task* / *Sequence flow*, the inventory counts
  *2 data objects*), and a test keeps the two tables in step and fails if Atlas ever emits an identifier
  nobody gave a Design term.

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
- **Schema gaps tab** — a dedicated Explorer view (sidebar section *Checks*; the Checks tab and the
  dashboard's "Schema gaps" card route there): every service's Liquibase → Service → Data object
  coverage, its unmapped columns front and center, fully-mapped services collapsed to a chip row.

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
