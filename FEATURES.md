# Flowable Atlas — Features

*IntelliJ IDEA plugin, v0.12.0.* A summary of what Flowable Atlas provides, grouped by area.
Everything is resolved against the Flowable models that actually live in your repository.

## Atlas Explorer & Hub

- **Generate Atlas explorer** — scans a Flowable project and produces an interactive
  `*.explorer.html` dependency map of models, code, and their references.
- **Embedded Explorer tab** — renders the generated `*.explorer.html` as a JCEF "Atlas Explorer"
  tab inside the IDE editor, theme-synced with the IDE.
- **Atlas Hub tool window** — the plugin's control center: model-index status, list of generated
  explorers, and quick actions.
- **Pick what a pull fetches, right in the Hub** — the *Flowable Design* section lists the
  workspace's apps with checkboxes, pre-ticked from the configured default. Ticking differently is a
  **personal override**: it is stored workspace-locally (never in the VCS-shared
  `.idea/flowable-atlas.xml`), the status line marks it *(personal selection)*, and *Reset to
  configured* appears to go back. Both the Hub's *Pull from Design* link and the toolbar action fetch
  that effective selection, so they can never disagree; app names/versions load on demand
  (*Refresh apps*) — the Hub's status refresh itself never calls Design.
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
  unused forms / operations / custom functions, orphan or superseded changelogs, variables written but
  never read, variables only a script guess supports, and the count of uncertain (≈ / ƒ) links. Every
  block links to its drill-down list, and the dashboard's health cards jump straight into the matching
  block.
- **Unused variables tab** — under *Variables*, the variables something **writes** and nothing **reads**.
  Atlas now records a direction for every piece of variable evidence — `setVariable` vs `getVariable`, a
  DMN input vs its output, each side of an in/out mapping, a `<dataObject>` declaration, a form field
  (both, because a field is prefilled from the variable *and* writes it back) — and reports two things:
  a variable **written but never read anywhere**, and one **mapped into a called model that never reads
  it** (the classic dead `flowable:in`, where the caller's own use of the name says nothing about the
  callee). Every row names the write to delete in Design's words — *Result variable `Calculate total`*,
  *Decision output*, *In parameter `Fulfil`* — links the model, and jumps to the element on its diagram;
  chips narrow to one write construct. The variable's own page gains a **Written / read** list, so the
  verdict can be checked rather than trusted.
- **The unused-variable check would rather say nothing than guess** — it stays silent whenever a read
  could exist where it cannot look: a construct whose direction Flowable does not fix (a variable
  listener, `hasVariable`), a value consumed outside the models (an action's response payload, an
  extracted variable a query indexes, a form's outcome variable the task list shows, a loop counter), a
  name written into a container object, a bare-EL Init-Variables value, a name the `{{…}}` harvester
  deliberately ignores (`{{total}}`, `{{data}}` …), a name passed anywhere as a string literal, any scope
  whose script or Java code reads the *whole* variable map at once, and a mapping into a model that is not
  in the project. The page says **how many variables it declined to judge** and why — the honest
  denominator that makes the ones it does name worth acting on. On a 363-model real project this is ~4%
  of variables flagged, with the rest either clean or explicitly left alone.
- **Script tasks tab** — under *Integration*, one sidebar entry listing **every script in the project**: BPMN script tasks,
  CMMN `flowable:type="script"` plan items, execution/task-listener scripts and action bot scripts,
  grouped by model, each with its Design element name (*Script task*, *Execution listener · end*, *Bot
  script*), language, line count, documentation, result variable and the **variables it touches**
  (linked; a bare-identifier read marked `≈`). Chips narrow to one kind, one text filter searches names,
  languages *and the code itself*, one control expands or collapses every body, and *in model ↓* jumps to
  that element in its model. Reading all the code of a project no longer means opening every model in turn.
- **Script syntax validation** — a dependency-free structural check runs over every script during
  generation (Groovy, JavaScript and Python families): unterminated strings/comments, unbalanced or
  mismatched brackets, unclosed `${…}` interpolation, empty script tasks, and `scriptFormat` typos with
  a did-you-mean (`grooy` → *groovy*; a distant name is respected as a custom JSR-223 engine). Findings
  land in the Checks tab (its own *Script syntax* health card and block, with severity, line and the
  offending source line), as ⚠ badges on the Script tasks tab and the model detail panels, as a marker
  in the overview Markdown, a count in the summary and the CLI status line. Deliberately conservative:
  a heuristic that cannot decide stays silent, so valid exotic Groovy never gets flagged.
- **Script binding validation** — a hand-maintained catalog, transcribed from the Flowable engine and
  platform sources, of what each script context really binds: `execution` in BPMN script tasks and
  execution listeners, **only `task`** in BPMN task-listener scripts, `planItemInstance`/`caseInstance`
  in CMMN, `flw`/`flwActionContext` in action bots, plus the engine services. On top of it: member-typo
  checks with a did-you-mean across the full `DelegateExecution`/`DelegateTask`/`DelegatePlanItemInstance`/
  `CaseInstance`/`flw.*` API surfaces (`execution.setTransientVariabel(` → *setTransientVariable*),
  wrong-context warnings (`execution` used in a CMMN script), `flw` namespaces that exist only in EL
  (`flw.base64` & co.), case-sensitive `scriptFormat` names (`GROOVY` fails at runtime — the engine's
  JSR-223 lookup has no aliasing), and CMMN lifecycle listeners that declare a script (the engine
  silently ignores those). Locally declared names shadow the catalog, and only near-miss typos are
  reported — dynamic Groovy stays unflagged.
- **Binding-aware completion & quiet editors** — the same catalog feeds completion: after
  `execution.` / `flw.time.` the context's real API is offered with parameter signatures
  (`setTransientVariable(variableName, value)`), sub-objects chain with an auto-popup dot, and at
  the top level the context's root bindings complete — in the Script Playground (per its context
  picker) and inside injected script bodies in model files (context derived from the XML). And
  because `execution` & co. are dynamic bindings the Groovy/JS PSI can never resolve, the
  "unresolved" inspection noise (gray `execution`, *No candidates found for method call*) is
  suppressed exactly inside Flowable script bodies — nowhere else.
- **Platform beans, catalogued** — in a Work installation scripts resolve *any* Spring bean by name
  (the engine's beans map is the whole ApplicationContext), so the catalog ships the platform's
  default services with generated surfaces: `dataObjectRuntimeService`, `contentService`,
  `templateService`, `sequenceService`, `platformIdentityService`, `actionRuntimeService` and ~25
  more — completion (typed as *Spring bean*), member-typo checks and hover docs included. The
  playground shows them in their own capped **Beans:** chips row (a *+N more* tooltip lists the
  rest), and the docs note the sandbox strict-mode caveat (bean access can be whitelisted-off).
- **Find anything, and see where it matched** — ⌘K/Ctrl-K searches *every* string Atlas parsed, not a
  list of blessed fields: script bodies, element documentation, flow conditions, field injections,
  listener classes, DMN cells. Each hit says where it came from (`script · stampTask`,
  `doc · approveTask`, `DMN annotation · r1`), name matches always rank above free-text ones, and
  picking a hit opens that element's row in the detail panel. That includes the **endpoint a REST
  button calls** — search any fragment of the path and the page/form that calls it comes up, even
  though the URL is written as `{{endpoints.baseUrl}}/api/…/{{someVar}}`. The palette itself is
  **resizable from its bottom-right corner** (the size is remembered, double-click resets it), because
  a templated URL is longer than any fixed width can show.
- **Type the words you remember, in any order** — the query is split into words, and every word has to
  match *somewhere*; it is not one contiguous substring. So `shopping template` finds the data object
  named "DEMO-D05 Shopping list template", and `demo d05` finds `DEMO-D05` — a space, a hyphen and an
  underscore all mean the same thing, and a word may start after a separator or at a camelCase hump
  (`template` finds `outreachTemplateKey`). Matched text is **highlighted** in the name and the key, so
  it is visible *why* a row is listed. Results are grouped in a fixed order — models before integration
  before code — and the **best hit is preselected**, so Enter opens it without touching the mouse.
  Two rows of chips narrow further: first the section, then the category inside it (Data objects, Forms,
  Java classes …) with a count each; typed equivalents are `t:` `in:` `key:` `file:`, and `"…"` forces
  an exact phrase. A query that matches nothing offers the closest names instead of a dead end. In a
  sidebar category list the same engine applies, and because that list only ever searches the category
  you are standing in, it tells you when the words match **elsewhere** and hands the query to ⌘K.
- **Mark several results, open them as tabs** — in a sidebar category list *and* in the ⌘K palette,
  **⇧↑/↓** extends a selection from where you started, ⌘/Ctrl-click toggles one, ⇧-click takes a range
  and ⌘/Ctrl-A takes everything on screen; **Enter** then opens every marked result as its own detail
  tab. So working through a list of hits stops being a round trip through the list for each one.
  A marked row shows a checkmark and its own tint — distinct from the row the detail panel is
  currently showing. In the palette, **⌘/Ctrl-Enter** opens the marked hits and *keeps searching*, so
  several queries can be batched into tabs without reopening ⌘K.
- **Detail tabs** — the strip above the detail panel appears as soon as a second node is open, and each
  tab keeps its own scroll position *and* the search term it was opened with, so switching back to a
  hit from ⌘K still highlights the row that matched. A tab behaves like a browser tab: following a
  relationship chip moves the tab you are in, while ⌘/Ctrl-click or middle-click on a chip opens it in
  a background tab. Switch with **⌥1…9 / Alt+1…9**, step with **⌥←→ / ⌥[ ]**, close with **⌥W**,
  middle-click or the tab's ×; "close others" keeps just the one you are reading. Alt is deliberate:
  Chrome reserves ⌘/Ctrl+1…9, ⌘W and Ctrl+Tab for itself, and in the IDE ⌘W would close the JCEF
  editor tab. The open set survives a reload (per project) and up to 12 tabs stay open — the strip
  scrolls rather than squeezing them into slivers, and marking stops at that limit instead of
  promising an "open all" it cannot keep.
- **REST endpoints, both directions** — a form/page REST button, a `.service` operation, a REST data
  source and a BPMN HTTP task are all treated as outbound calls: each model lists the endpoints it
  calls (verb, URL, which button), and *Find Usages* / the gutter icon on a Spring
  `@GetMapping("/canEdit/{caseId}")` handler lists the models that call it — matching through
  `{{modelVar}}` ↔ `{pathVariable}` and a variable base URL. A path match that only shares a segment
  is reported as `≈ possibly`, never as *served by*.
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
  A process's `<dataObject>` variable declarations are parsed too, so the one place BPMN states outright
  that a process *has* a variable is no longer invisible; and a Java class evaluating Flowable EL from a
  string (`resolveValue(task, "${vars:get(flagReturn)}")`) counts as reading that variable.
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
- **⤢ Expand the card over the whole page** — docked in the corner there is nothing to the right to
  grow into, so ⤢ (or a double-click on the header) turns the card into a centered overlay over the
  entire app, dimmed behind and above even the full-screen diagram, as tall as its content needs and
  still resizable. Clicking the backdrop or Escape shrinks it back to the corner; a second Escape
  closes it. Expanded and docked sizes are remembered separately, so clicking through elements keeps
  whichever mode you chose.
- **Design vocabulary, explained** — node types, element types, parameter kinds and relationships are
  named the way Flowable Design names them (*Decision tables*, *AI agents*, *Services*, *Send payload
  map*, *Decision task → decision table*, *Execution listener*, *Case plan model*), each with a hover
  text explaining what it means — so the map is readable without having built Atlas. The same words are
  used in the generated Markdown (`overview.md` says *User task* / *Sequence flow*, the inventory counts
  *2 data objects*), and a test keeps the two tables in step and fails if Atlas ever emits an identifier
  nobody gave a Design term.

## Model navigation & validation

- **"Flowable Model" tab in Search Everywhere** — press Shift twice and tab across to search only
  Flowable models, unmixed with the project's classes, files and symbols. Models match on their
  **key** and on their **file path**, and every row names its origin
  (`app.zip → processes/invoice.bpmn`). From two characters on, the tab also greps model **content**
  live, one row per occurrence with its line number; Enter jumps there. This is the only way to
  search inside a `.bar`/`.zip` — an archive belongs to no content or library root, so its entries
  appear in neither the Files tab nor Find in Files. Model keys remain searchable in the Symbols tab
  as before. Reachable from *Tools → Flowable Atlas → Search Models…* and from the Atlas Hub's
  *Model Index* row, as well as by tabbing across in Search Everywhere.
- **Models open with their content** — `.bpmn`/`.cmmn`/`.dmn` are registered as XML and the JSON
  model types (`.data`, `.service`, `.agent`, `.event`, `.query`, `.app`, …) as JSON, so opening one
  shows the file instead of IntelliJ's "file type not associated" placeholder — most visibly for an
  entry opened out of an archive.
- **Go-to / Find Usages on model keys** — Ctrl-click a key literal (`calledElement`, `formKey`,
  `decisionRef`, …) in BPMN/CMMN XML jumps to the model file.
- **Key completion** — autocompletes model keys at cross-reference attributes in BPMN/CMMN XML.
- **Broken-reference inspection** — flags an unknown model key at a cross-reference attribute
  (broken deployment).
- **Real Groovy & JavaScript in script bodies** — BPMN `<scriptTask>` bodies, CMMN script-task fields,
  execution/task-listener scripts and action-bot `scriptInfo.script` strings get the IDE's own language
  support injected inline: compiler-grade syntax errors, full highlighting, completion and Alt-Enter
  *Edit Fragment*. Languages are resolved by ID at runtime, so there is no dependency on the Groovy or
  JavaScript plugins — Groovy is bundled in every IDEA, JavaScript lights up on Ultimate, and where a
  plugin is absent the body simply stays plain text. `juel` and unknown formats keep the `${…}`
  expression injection instead, and GStrings no longer double-inject the expression language.
- **Script Playground** — the *Scripts* tab of the Flowable Expressions tool window: paste or write a
  Groovy/JavaScript/Python script and get the IDE's real language editing (completion, coloring — where
  the language plugin is available), the structural validation the CLI/explorer run as live squiggles
  plus clickable problem rows, the scope variables the script touches as chips (API writes vs `≈`
  heuristic reads), and *Load Script from Model…* to pull any script task / listener / action-bot
  script out of the project's models. The last script and language persist per user (workspace.xml).
- **Hover / Ctrl-Q docs** — shows a model key's type, name, and file.
- **View model diagram** — a gutter icon on a model-key literal opens the model's diagram in the IDE,
  so you can see the process / case / decision without opening Flowable Design. It prefers the `.svg`
  bundled by an older Design export, and otherwise **renders the diagram itself** from the model's
  layout (BPMN/CMMN/DMN diagram interchange) — so it keeps working with newer Design exports that no
  longer bundle a `.svg`. The same rendering also powers the **Diagrams (SVG)** generation artifact and
  is embedded inline in each node's detail view in the Explorer HTML. The key may be passed as a
  **constant or local variable** — `.processDefinitionKey(PROCESS_KEY)` gets the icon on the call line,
  not only `.processDefinitionKey("KYC-P039")`.
- **Decision tables open as tables** — a Design decision table has no canvas, so it carries no DMN
  layout to draw (only a *decision requirement diagram* does). Clicking the gutter icon on a decision
  key therefore paints the **decision table itself** — hit policy, input band (label + the expression it
  evaluates) and output band, one numbered row per rule with its annotation — instead of reporting "no
  diagram layout". The Explorer keeps showing a decision's rules as its own searchable HTML table.
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
- **Inline hints for opaque keys** — a key string is labelled inline with what it actually is, so a
  constants class stops being a wall of `KYC-Dnnn`: a **data-object** key gets its physical table name
  (`"kyc-customer"` ‹CMM_CUSTOMER›, resolved through the backing `database` service) and an **action**
  key gets the action model's name (`"KYC-A033"` ‹Create support request›). Both match by value — every
  literal, not only API call sites — and are toggled under *Settings → Editor → Inlay Hints → Values*
  or *Settings → Tools → Flowable Atlas → Inline Hints*.
- **Generate a data-object DTO** — Alt-Enter on a data-object key generates a typed Java class from its
  fields (typed fields, a `fromContainer(…)` mapper and a fluent builder). The key is recognised at a
  data-object API call site as an inline literal **or** a constant — `definitionKey(ModelKeys.CUSTOMER)`,
  the shape *Generate Model Constants* produces — and, beyond call sites, on any string literal or
  constant whose value is an indexed data-object key.
- **Generate DTOs in bulk** — Tools → Flowable Atlas → Generate → *Data-Object DTOs* → *From App(s)…*
  or *From Data Object…*: a preview table of exactly what will be written (key, editable class name,
  owning app, field count, target file, new vs. overwrite) for a whole app at once or for hand-picked
  data objects. Target source root, package, an optional sub-package per app and the class-name suffix
  (default `Dto`) are configurable; a data object with no field mappings is listed but never generated.

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
- **Access-token or password auth** — the connection authenticates with a Design username/password *or*
  a personal access token (`Authorization: Bearer …`), the scheme Flowable's own CLI uses and the only
  one that works when Design sits behind SSO. *Create Token…* mints one straight from the settings page
  (name + validity), so no password has to stay in the keychain, and *Manage in Design…* opens Design's
  own token page. Both secrets live in the IDE PasswordSafe, never in a file — switching modes keeps
  each of them.
- **Post-pull drift warning** — after a pull, flags model keys that were present before but are now
  gone, so code (or models) still referencing them can be fixed before they break.

## Settings & housekeeping

- **Settings tree** — an app-level root page (core toggles) with three project-level child pages.
- **Model-constants regeneration** — rebuilds the model-constants class automatically when models
  are added, removed, or edited.

## Generated artifacts for AI agents

Atlas's original purpose: make a Flowable project understandable to an LLM quickly, accurately and
cheaply. `--all` (and the plugin's generation settings) write five artifacts, in four size tiers.

- **`<project>.CLAUDE.md`** (~10-14 KB) — drop-in agent context: a Flowable primer (models vs
  definitions, extension points, the Design → export → build → deploy path), this project's discovered
  facts (apps, inventory, where models and Java live, naming conventions that actually generalize,
  build/run commands, detected Flowable version), concrete **wiring examples to mirror**, the project's
  **open findings** ("do not copy these patterns"), and a **cheatsheet of every EL namespace, script
  binding and platform bean that exists** — generated from the same catalogs the expression and script
  validators use, so an agent is told what it may call instead of guessing. `CLAUDE.template.md` is the
  project-independent primer alone, generated from the same source (`--claude-template`); a test keeps
  the two in step.
- **`<project>.summary.md`** (a few KB) — orientation: apps, inventory, variables by scope, entry points
  grouped by audience, REST surface, integrations, Java glue, hotspots, external surface, and a **health
  block** naming the worst findings.
- **`--slice <type:key>`** — one node with its full context: what it uses, **who uses it** (the direction
  the report cannot show), the findings that touch it, and its attributes. The tier between a few KB of
  summary and megabytes of graph.
- **`<project>.overview.md`** — the full report: every process **in execution order** with each step's
  successors and branch conditions, CMMN plan trees with each criterion's sentry condition, DMN rule rows,
  form fields and data sources, the **data layer** (service ↔ Liquibase table ↔ data object, with gaps),
  variables with provenance (scope, where set, where read, script-inferred), expressions grouped by callee
  with invalid ones marked, the access map, and every finding with `file:line`. Per-section caps keep a
  large project's report readable.
- **`<project>.graph.json`** — the machine-readable graph, minified, with each model body stored once
  (`data.dataIn` names its bucket), a `usedBy` reverse index on every node, `findings`/`checks`, and a
  `_schema` key documenting the shape plus `jq` recipes. Query it; do not read it whole.

The health findings (`findings` / `checks`) are computed once in `:core` and used by every surface — the
Markdown artifacts, `graph.json`, the CLI status line and the explorer's Checks tab: invalid and suspect
expressions, script syntax errors, unparseable files, missing model references, orphan/superseded
changelogs, schema gaps, unused forms/operations/custom functions, and variables only a script mentions.
