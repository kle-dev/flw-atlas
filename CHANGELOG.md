# Changelog

Release notes for the Flowable Atlas IntelliJ plugin and CLI (one Gradle version drives both).

> **Pre-release.** Atlas is on the `0.x` line: it is used internally and has no
> stability guarantee across versions yet. Behaviour, settings and generated-artifact
> formats may change between minor releases. Version numbers below 0.13.0 were never
> published outside the team, which is why the history has gaps.

<!-- This file is the source of truth for the release history. Edit it here, then run
     `./gradlew :core:updateGoldens` to regenerate the plugin descriptor's <change-notes> from its
     newest entries (that field is capped at 65535 characters, so it holds a window, not everything).
     See ChangelogSyncTest. -->

## 0.13.0

- **Report a problem straight from the error dialog** — an Atlas exception used to reach the IDE's generic
  "Report to JetBrains" dialog, which discards third-party plugin reports, so the only way to hand one over
  was to dig `idea.log` out of *Help → Show Log*. The dialog now offers **Report Flowable Atlas Problem…**: it
  assembles the environment (Atlas and IDE version, verified platform range, OS, JRE) plus the stack traces,
  copies that to the clipboard and opens the issue tracker. Nothing is transmitted by the plugin itself — a
  trace can carry model keys and file paths from your project, so you see the text before it moves.
- **Failures leave a trail** — around forty places swallowed their exception silently. The worst were the
  credential stores: typing a Design or Inspect password and hitting *Apply* could fail to write it to the
  PasswordSafe (locked keychain, "do not save passwords" mode) and say nothing, so the next pull asked again
  for no visible reason. Those now log, as do a failed Design pull, custom-function extraction (whose failure
  made the inspection flag your own `flw.*` functions as unknown), sub-project detection and explorer
  discovery. Hot paths log at debug or once, never per file, so `idea.log` stays readable.
- **The Atlas Hub states what was actually verified** — its footer reads *"verified on 2026.2"* and flags
  the running IDE whenever it falls outside that, in either direction. Atlas installs on 2026.1 and later
  on purpose: it ships as a ZIP with no update channel, so a tight `until-build` would make it vanish on
  the day you upgrade the IDE rather than prompt for an update. That makes the range it *installs* on wider
  than the range it is *verified* on, so a 2026.1 install is flagged as untested too — "it loads" is no
  longer confused with "it was tested".
- **A CHANGELOG in the repository** — the release history only existed inside the plugin descriptor, where the
  IDE's plugin manager shows it, leaving CLI users and anyone reading the repo on GitHub with no way to see
  what changed. `CHANGELOG.md` is now generated from these very notes, so the two cannot drift.
- **Removed a platform API scheduled for deletion** — three combo/list renderers used a
  `SimpleListCellRenderer.create` overload JetBrains has marked for removal, which would have broken the
  plugin on a future IDE. Replaced with the supported `textListCellRenderer`. JetBrains' Plugin Verifier now
  reports no scheduled-for-removal usage at all.
- **Fixed two gates that were not gating** — the tests that keep `CLAUDE.template.md` and `CHANGELOG.md` in
  step with their generators compare files outside the module's source sets, which Gradle could not see:
  hand-editing either left `./gradlew build` green on exactly the drift those tests exist to catch. They are
  declared inputs now, verified by injecting a change and watching the build fail. Also added the repository's
  `LICENSE`.

## 0.12.2

- **Runs on IntelliJ IDEA 2026.2** — one ZIP for 2026.1 and 2026.2, both checked with JetBrains' Plugin
  Verifier. 2026.2 moved JCEF (the Atlas Hub, the explorer editor, the Inspect sign-in browser) out of the
  platform core into the bundled *Web Browser (JCEF)* plugin; Atlas now declares that plugin as an *optional*
  dependency, so the same build picks it up on 2026.2 and ignores it on 2026.1.

## 0.12.1

- **DTO class names come from a pattern** — *Generate → Data-Object DTOs* names its classes the way the
  Liquibase dialog names its changelog files: a token pattern (`{name} {shortName} {key} {app} {suffix}`) plus
  an optional regex rename, rendered live into the preview's *Class name* and *Target file* columns. The
  default `{name}{suffix}` is the name you got before, a class name typed into the table outranks the pattern
  for that row, and the Alt-Enter intention proposes the same name the bulk dialog would. Pattern and rename
  are remembered per project (and per sub-project) under *Settings → Flowable Atlas → Generation*.
- **Class names without the model key** — Design model names usually carry their key (`DEMO-D009 Pod Member`),
  which the derived class name repeated as noise: `DEMOD009PodMemberDto`. `{shortName}` drops it — the key
  itself when the name starts with it, otherwise a leading capitals-and-digits run before the first word, so a
  key written differently from the key model (`DEMO-D9` against `DEMO-D009`) also shortens while an acronym
  (`IBANCheck`) is left intact. `{shortName}{suffix}` is the one-token way to `PodMemberDto`.

## 0.12.0

- **A "Flowable Model" tab in Search Everywhere** — open it from *Tools → Flowable Atlas → Search Models…*,
  from the Atlas Hub's *Model Index* row, or by pressing Shift twice and tabbing across to it, and search
  *only* Flowable models, without your project's classes, files and symbols mixed in. Models are matched on
  their **key** and on their **file path**, and every row names where it came from: `app.zip →
  processes/invoice.bpmn`. Model keys stay searchable in the Symbols tab as before — the new tab is a second,
  focused way in, not a replacement.
- **Your archives are searchable at last** — a model packed in a `.bar`/ `.zip` is invisible to IntelliJ: it
  appears in neither the Files tab nor Find in Files, because an archive is not part of any content or library
  root. The new tab searches those entries — by name *and* by content — so you no longer unpack an archive by
  hand to find out which process references a variable.
- **Full text, live** — from two characters on, the tab also greps the content of every model, with each
  occurrence its own row showing the matched line and its line number; Enter jumps straight there. The scan is
  cancelled on the next keystroke and its results are cached, so it stays responsive on large repositories. It
  runs only while the tab is selected, never in the general "All" results.
- **Models open with their content** — `.bpmn`, `.cmmn` and `.dmn` are now recognised as XML, and the
  remaining JSON model types (`.data`, `.service`, `.agent`, `.event`, `.query`, `.app`, …) as JSON.
  Previously IntelliJ classified them as unknown and showed a "file type not associated" placeholder instead
  of the file — most visibly for an entry opened out of an archive.

## 0.11.3

- **Unused variables, on their own page** — the Explorer gains a *Variables → Unused variables* tab listing
  every variable something **writes** that nothing **reads**. Two findings: a variable no model reads
  anywhere, and one **mapped into a called model that never reads it** — the dead `flowable:in`, where the
  caller's own use of the name says nothing about the callee. Each row names the write to delete in Design's
  words (*Result variable "Calculate total"*, *Decision output*, *In parameter "Fulfil"*), links the model and
  jumps to the element on its diagram.
- **The variable graph now knows read from write** — it recorded only that a name occurred somewhere.
  Direction is now tracked for every kind of evidence: `setVariable` vs `getVariable`, a decision's inputs vs
  its outputs, each side of every in/out mapping, a process-level `<dataObject>` declaration, and a form field
  as *both* (a field is prefilled from the variable and writes it back). A variable's page gains a **Written /
  read** list, so the finding can be checked rather than trusted.
- **The check would rather say nothing than guess** — it stays silent wherever a read could exist out of view:
  a construct whose direction Flowable does not fix, a value consumed outside the models (an action's response
  payload, an extracted variable a query indexes, a form's outcome variable, a loop counter), a name written
  into a container object, a bare-EL Init-Variables value, a name the `{{…}}` harvester ignores, a name passed
  as a string literal, any scope whose script or Java code reads the whole variable map at once, and a mapping
  into a model outside the project. The page says how many variables it declined to judge, and why.
- **Also newly visible** — a process's `<dataObject>` variable declarations and legacy
  `<flowable:formProperty>` fields are parsed into the graph, an event task's `eventInParameter` source is
  recorded at all (both sides used to be dropped), and a Java class evaluating Flowable EL from a string
  (`resolveValue(task, "${vars:get(flagReturn)}")`) counts as reading that variable.

## 0.11.2

- **The diagram icon follows a key held in a constant or variable** — it used to appear only on an inline
  literal, so extracting the key (`String key = "DEMO-P039";` … `.processDefinitionKey(key)`) or using the
  generated model constants (`.processDefinitionKey(ModelKeys.ONBOARDING)`) lost it. The compile-time value is
  now resolved at every Flowable key call site, and the icon sits on the call line.
- **Action keys are labelled with the action's name** — an inline hint after a string literal that is an
  `.action` key, so a constants class of `DEMO-Annn` reads as what each one starts. The counterpart of the
  data-object table-name hint; toggle under *Settings → Editor → Inlay Hints → Values* or *Settings → Tools →
  Flowable Atlas → Inline Hints*.
- **Decision tables open as tables** — a Design decision table has no canvas and therefore no DMN layout to
  draw, so clicking the diagram icon on a decision key reported "no diagram layout" for nearly every decision.
  Atlas now paints the **decision table** itself: hit policy, input band (label plus the expression it
  evaluates), output band, one numbered row per rule with its annotation. A decision requirement diagram still
  renders from its layout, and the Explorer keeps showing rules as its own searchable table.

## 0.11.1

- **The diagram's element card can be expanded over the whole page** — it starts docked to the diagram's
  top-right corner, where its resize handle has nothing left to grow into: in a narrow tool window you could
  not drag it past the drawing. **⤢** in the header (or a double-click on the header) now lifts it out of the
  corner into a centered overlay across the entire app — dimmed behind, above even the full-screen diagram, as
  tall as its content needs and still resizable. Clicking the backdrop or `Esc` shrinks it back to the corner,
  a second `Esc` closes it, and the expanded and docked sizes are remembered separately, so clicking through
  elements stays in the mode you chose.

## 0.11.0

- **The generated artifacts say what Atlas actually knows** — the Markdown/JSON artifacts had been frozen
  since the Python port while the engine grew, so everything learned in 0.10.x lived only in the explorer and
  the IDE. Now: **processes are listed in execution order**, each step naming its successors and branch
  conditions (gateways, receive tasks and sub-processes are rendered at all for the first time, and an element
  no sequence flow reaches is marked as unreachable instead of looking sequenced); **CMMN criteria show their
  sentry's condition**; **DMN rule rows**, **service operation in/out contracts** and the **data layer**
  (service ↔ Liquibase table ↔ data object, with the gaps named) are included; **variables carry their
  provenance** — scope, where set, where read, and a mark when the name is only inferred from a script;
  expressions are grouped by callee with the invalid ones flagged.
- **Health findings are computed once, for every surface** — the eleven checks (invalid/suspect expressions,
  script syntax, unparseable files, missing model references, orphan/superseded changelogs, schema gaps,
  unused forms/operations/custom functions, script-inferred variables) were JavaScript inside the explorer, so
  no other artifact could state a single one. They are now `findings`/`checks` in `:core`, feeding the
  summary's Health block, the overview's Findings section (with `file:line` and the offending source line), a
  "known issues — do not copy these patterns" list in `CLAUDE.md`, `graph.json`, the CLI status line and the
  explorer's Checks tab. One definition, one number everywhere.
- **CLAUDE.md tells an agent what it may call** — a new cheatsheet, generated from the same catalogs the
  expression and script validators use: every backend EL namespace and its functions, the frontend `flw.`
  members, what each script context binds, the platform beans, and the project's own discovered functions.
  Atlas used to report that `${vars:bogus()}` is wrong while never saying what is right.
- **graph.json is half the size and queryable** — a model body is stored once in its bucket and its node
  points there via `data.dataIn`, output is minified (`--pretty` to indent), every node carries a `usedBy`
  reverse index, and a `_schema` key documents the shape and ships `jq` recipes. 4.8 MB → 2.5 MB on a large
  real project.
- **`--slice <type:key>`** — one node with its full context in both directions (what it uses, who uses it, the
  findings touching it): the tier between a few-KB summary and megabytes of graph, for when the task is about
  one model.
- **Truth fixes** — the overview no longer signs itself "Generated by flowable_project_overview.py"; Python
  `repr` output (`{'kind': 'rest', …}`, `['total']`, `None`) no longer reaches the page (a test now fails if
  it does); the summary prints the real variable scopes instead of the words "process / form / case / java";
  pointers name sibling files instead of CLI flags or explorer tabs; `CLAUDE.template.md` is generated from
  the same source as the primer, so the two can no longer drift; Gradle projects get their Flowable version
  detected; and a naming convention is only claimed when it generalises.

## 0.10.17

- **Choose what "Pull from Design" fetches — in the Hub** — the Atlas Hub's *Flowable Design* section now
  lists the workspace's apps with checkboxes, pre-ticked from the configured default. Ticking differently is a
  **personal override**: stored workspace-locally, never in the VCS-shared settings file, marked *(personal
  selection)* in the status line, with *Reset to configured* to go back. The Hub link and the toolbar action
  both pull that effective selection, and app names/versions load on demand so the Hub's own refresh never
  calls Design. The Connections settings keep the shared team default (and the first-time setup).
- **Generate data-object DTOs in bulk** — Tools → Flowable Atlas → Generate → *Data-Object DTOs* → *From
  App(s)…* / *From Data Object…*: a preview table of exactly what will be written — key, editable class name,
  owning app, field count, target file, and whether it is new or overwrites — for a whole app at once or for
  hand-picked data objects. Target source root, package, an optional *sub-package per app* and the class-name
  suffix (default `Dto`) live in Settings → Flowable Atlas → *Generation*. Each class is the same POJO the
  Alt-Enter action emits: typed fields, a `fromContainer(…)` mapper and a fluent builder.
- **The DTO quick action finds the key** — Alt-Enter now recognises a data object behind a **constant**
  (`definitionKey(ModelKeys.CUSTOMER)` — what *Generate Model Constants* produces), not just an inline
  literal, and beyond API call sites it offers itself on **any** string literal or constant whose value is an
  indexed data-object key. Availability is a plain index lookup, so it no longer parses model files during
  highlighting; resolving the fields moved into a cancellable progress that reports when a key has no model or
  no field mappings instead of doing nothing.

## 0.10.16

- **Platform beans in the script catalog** — Work scripts resolve any Spring bean by name, so the platform's
  default services (`dataObjectRuntimeService`, `contentService`, `templateService`, `sequenceService`,
  `platformIdentityService`, `actionRuntimeService`, ~25 more) come with generated method surfaces: completion
  with signatures (typed as *Spring bean*), member-typo checks and hover documentation — the sandbox
  strict-mode caveat is documented on hover.
- **Script Playground chips, redesigned and clickable** — the info strip under the editor is now a two-column
  layout (Variables · Reads · Bindings · Beans) with soft pill chips that wrap instead of clipping, a
  click-to-expand *+N more* per row — and every chip inserts its name at the caret when clicked (undoable,
  focus returns to the editor).
- **Explorer: work through search hits in bulk** — list rows support multi-selection (⇧-arrows, ⌘/Ctrl-click,
  ⇧-click ranges, ⌘/Ctrl-A) and Enter opens every marked row as its own detail tab; detail tabs remember
  scroll and search term, open in the background with ⌘/Ctrl-click, and switch with Alt+1…9.

## 0.10.15

- **Design access tokens** — "Pull from Flowable Design" can authenticate with a Flowable Design *personal
  access token* (`Authorization: Bearer …`) instead of a username and password: the scheme Flowable's own CLI
  uses, and the only one that works when Design sits behind SSO/OAuth2 (where basic auth is switched off).
  Pick the mode under Settings → Tools → Flowable Atlas → *Connections*; workspace list, app list and app
  export all go through it, and "Refresh Workspaces" still doubles as the connection test.
- **Mint a token without leaving the IDE** — *Create Token…* signs in once with your username/password,
  creates a named token with a chosen validity and drops it straight into the field, so afterwards **no
  password has to stay in the keychain**. *Manage in Design…* opens Design's own token page. Password and
  token are stored as two separate IDE PasswordSafe entries, never in a file, so switching auth modes back and
  forth loses neither.
- **Sharper sign-in errors** — an HTTP 401 now says which credential to fix: "check username/password" in
  password mode, "the access token is invalid or expired" in token mode. A blank token fails immediately
  instead of producing a pointless request.

## 0.10.14

- **Script Playground** — the Flowable Expressions tool window gains a *Scripts* tab: paste or write a
  Groovy/JavaScript/Python script and get real-language completion and coloring (where the IDE plugin is
  available), live structural validation with squiggles, clickable problem rows and an error stripe, the scope
  variables the script touches as chips (API writes vs ≈ heuristic reads), and *Load Script from Model…* to
  pull any script task, listener script or action-bot script out of the project's models for inspection.
- **Real script-task validation** — BPMN/CMMN script tasks, listener scripts and action-bot scripts get the
  IDE's own Groovy (bundled) / JavaScript (Ultimate) language injected inline: compiler-grade syntax errors,
  highlighting and Alt-Enter fragment editing right in the model file. GString `${…}` interpolation no longer
  double-injects the expression language inside script bodies.
- **Script syntax checks everywhere** — a dependency-free structural validator (unterminated strings/comments,
  unbalanced brackets, unclosed `${…}` interpolation, `scriptFormat` typos with a did-you-mean) runs over
  every script during generation: findings land in the explorer's Checks tab (new "Script syntax" card), as ⚠
  badges in the Script tasks tab and model detail panels, in the Markdown reports and the CLI status line.
- **Script binding validation** — a catalog transcribed from the Flowable engine and platform sources knows
  what each script context really binds (`execution` in BPMN script tasks, only `task` in task-listener
  scripts, `planItemInstance`/`caseInstance` in CMMN, `flw`/`flwActionContext` in action bots) and the full
  API of those objects: member typos get a did-you-mean (`setTransientVariabel` → *setTransientVariable*), a
  root used in the wrong context is explained, EL-only `flw.*` namespaces and case-sensitive `scriptFormat`
  values are flagged, and a CMMN lifecycle listener with a script — which the engine silently ignores — gets a
  warning. The Script Playground gains a context picker and shows the bound root objects as chips.
- **Binding-aware script completion** — after `execution.` / `flw.time.` the catalog offers the context's real
  API with parameter signatures (`setTransientVariable(variableName, value)`), in the Script Playground and
  inside injected script bodies in model files; the root bindings complete at the top level. The Groovy/JS
  "unresolved" noise on those dynamic bindings (gray `execution`, *No candidates found for method call*) is
  suppressed exactly inside Flowable script bodies.

## 0.10.12

- **No more multi-second UI freezes while the model index builds** — the scan now only holds the read lock to
  collect candidate files; parsing and regex work run lock-free, so typing and VFS refreshes are never queued
  behind it.
- **Quieter shutdown** — the index scan stops when the project closes and the embedded explorer editor no
  longer touches the VFS after disposal (no more AlreadyDisposedException warnings in the log).

## 0.10.11

- **Schema gaps tab** — the dashboard's "Schema gaps" number unfolded into a view of its own (sidebar, next to
  Overview): every service with Liquibase → Service → Data object coverage, its unmapped columns front and
  center, fully-mapped services collapsed to a chip row. The dashboard card now routes there.
- **Movable, resizable diagram info card** — the element card docks to the top-right of the diagram (stable
  while clicking through elements), can be dragged by its header and resized via the corner grip; width and
  position are remembered.
- **Entry/exit criteria show their condition** — in the plan-model tree (entry ◇ / exit ◆ chips), in the
  Sentries section (named "entry of Review" instead of a raw sentry id) and on the diagram: criterion diamonds
  are rendered properly (entry hollow, exit filled) and clicking one shows the guarded plan item and its
  sentry condition.

## 0.10.10

- **Legacy Design exports are no longer invisible** — the "typed-directory" export format (`form-models/`,
  `service-models/`, `action-models/`, … with each model wrapped in `{key, name, editorJson}`) is now
  unwrapped and parsed, from a zip or a loose workspace directory. Services, data objects, actions, events,
  channels, queries and policies go through their full parsers; old Oryx-editor forms/pages are at least
  registered by key so references resolve and their `{{…}}` bindings are indexed; the root app wrapper becomes
  the app node with *contains* membership.
- **Model data that was parsed but never shown** — new collapsible detail sections: a form/page's *Fields* (id
  → variable link, label, type, required, bound value) and *Data sources*; a process's *User tasks* (form,
  candidate groups, assignee, due date/priority), *Script tasks* (with the script body), *Events & timers*,
  *Multi-instance*, *Flow conditions* and *Listeners*; a case's *Plan model* tree (stages, milestones, tasks —
  each linking to the form/process/case/decision it uses), *Sentries* and *Event listeners & timers*; a
  security policy's *Permissions* (roles link to groups); an agent's *Tools* and *Operations* (with prompts);
  an app's *Variables* and *Pages*; an action's *Bot script*.
- **More links between the datasets** — form field ids and DMN decision inputs/outputs join the variable graph
  (a variable page now shows the forms and decision tables that touch it); events link to the channels that
  carry them and the data-dictionary types of their payload; agents' service tools count as uses of the exact
  operation; services link to the services their column relations join to; case-view/case-page `static-*-key`
  references and create-instance buttons resolve to their process/case/form/decision; watcher / participant /
  manual-activation / event-listener group permissions and form-button `permissionGroups` feed the Access
  view.
- **Hover explanations now work in the IDE** — every `title=` tooltip (Design vocabulary hints, copy buttons,
  badges) and the neighborhood graph's labels are served by the Explorer's own tooltip bubble, which renders
  in the embedded JCEF viewer where native tooltips never show.
- **Small things** — zero counts are no longer shown as rows; a decision service is labelled as such; CMMN
  tasks reuse the BPMN task-type vocabulary (*Data object task*, not *task · data-object*); search also
  matches form field ids/labels, app variables, agent tools, policy permissions and dictionary types; user
  tasks pick up due date / priority / category.

## 0.10.9

- **In/out parameters are mapped and searchable** — Atlas now reads every variable mapping a model passes
  into, or takes back out of, the things it calls: call activities and process/case tasks
  (`flowable:in`/`out`), Service-Registry, Agent, Data-Object and HTTP tasks (`inputParameter` /
  `outputParameter` / `errorOutputParameter` / `outputVariableName`), Send-/Receive-Event tasks
  (`eventInParameter` / `eventOutParameter`), Init-Variables (`variableMapping`), result variables, and Action
  Bots (`signalVariableNames`, bot `config`, `flw.getInput` / `flw.setOutput`). Previously only
  `flowable:in`/`out` on a call activity or a CMMN process/case task was read at all.
- **See them** — a node's detail view gets a *Parameters* section grouped per element with direction and
  `source → target` (variable names link to the variable), a collapsed *Field injections* section for a task's
  static configuration (an HTTP task's request URL/method), and each `.service` operation now shows its
  declared **outputs** next to its inputs.
- **Find them** — ⌘K/Ctrl-K matches parameter names on both sides of a mapping (the caller's variable *and*
  the callee's contract name) and shows which mapping matched; a variable's detail view lists every mapping
  that reads or writes it, and a new *Variable · parameter* sidebar category collects the variables that
  travel through one.
- **Form and page buttons too** — an Action, REST, Service, Agent or Create-Instance button's *Send payload
  map* and *Store response attributes* (`sendPayloadMapping` / `responsePayloadMapping` /
  `errorResponsePayloadMapping`, plus REST headers) are read and shown on the form. Previously only the
  button's `actionDefinitionKey` was, so the values it actually passes to the bot were invisible.
- **Every parameter group names what it calls** — `→ notifyCustomerAction`, `→ custSvc`, `→ subProcess`, an
  HTTP task's URL — with a link to that model when it is part of the project. And any called model (action,
  agent, service, data object, process, case, event, bot) gets the mirror view, **Called with**, listing what
  each caller sends — handy for checking that the names match a script's `flw.getInput(…)` or a service
  operation's declared inputs.
- **Detail pages fold up** — every section is collapsible and starts collapsed (except the diagram), so a node
  with 35 parameters stays skimmable. What you open stays open as you walk the graph, there is an *expand all*
  control, and a long parameter list gets its own text filter plus in / out / error direction chips.
- **Search jumps to the row** — picking a ⌘K/Ctrl-K hit now scrolls to the matching parameter, expands its
  section and highlights it instead of dropping you at the top of the page. The term rides along in the link,
  so *copy link* reproduces the highlight.
- **Diagrams show what each element is** — every shape now carries its Flowable type icon (User task, Service
  task, Service registry, AI Agent, Data object, HTTP, Script, Email, Timer / Message / Signal / Error events,
  …) plus the BPMN markers that belong to it: multi-instance, loop, a dashed non-interrupting boundary event,
  and a thick-bordered call activity instead of a sub-process marker. Hovering a shape names it in **Flowable
  Design's own words** ("Lookup customer — Service registry task"). The type comes from the Design stencil the
  model was drawn from, falling back to `flowable:type` and then to the element itself, so both app exports
  and Design workspace models are covered.
- **Diagram zoom and full screen** — zoom / fit buttons, scroll-to-zoom and drag-to-pan, and a full-screen
  view (`+` / `−` / `0`, Esc closes) for the diagrams that were simply too small to read in the panel.
- **Design's vocabulary, with explanations** — node types, element types, parameter kinds and relationships
  are now named as Design names them (*Decision tables*, *AI agents*, *Services*, *Send payload map*,
  *Decision task → decision table*) instead of Atlas's internal keys, and each carries a hover text saying
  what it means.
- **Also fixed** — a BPMN Service-Registry or Agent task's `serviceMapping` / `agentMapping` is now read (only
  the CMMN side was), so it links to the service or agent model and counts towards that operation's usages.
  Same for an **agent button** on a form, whose agent-model reference was not recorded at all.

## 0.10.8

- **Recognize model keys anywhere in code** (opt-in) — enable *Settings → Tools → Flowable Atlas → "Recognize
  model keys anywhere in code"* and any Java string literal whose value equals a known model key gets the
  diagram gutter icon, Ctrl-click navigation, Find Usages and hover — not only at a recognised Flowable API
  call like `startProcessInstanceByKey("…")`. Off by default; matches on value alone.
- **Diagrams for models inside an app archive** — a process/case/decision packaged in a `.zip`/`.bar`/Design
  app export now renders its diagram in the generated Explorer HTML and the *Diagrams (SVG)* artifact, the
  same as a loose model file; previously archived models showed no diagram section.
- **Version at a glance** — the generated Explorer HTML footer and the Atlas Hub now show the Atlas version,
  so it's clear which build produced a given page.

## 0.10.7

- **Diagrams render from the model layout** — the model-diagram gutter icon no longer depends on a `.svg`
  bundled by the Design export (newer Design exports no longer ship one). Atlas now renders the process / case
  / decision diagram itself from the model's BPMN/CMMN/DMN diagram-interchange layout — reading either
  deployment XML (`bpmndi`) or Design-workspace JSON (ORYX) — and still prefers a bundled `.svg` when one is
  present. The same rendering is available as a **Diagrams (SVG)** generation artifact and is embedded in each
  node's detail view in the Explorer HTML.

## 0.8.8

- **Payload scope in the Expression Playground** — evaluate a frontend expression as a component *inside a
  subform or list* would see it: enter a payload node path (e.g. `orders[2].items[0]`) or place the caret on
  the node and hit *From Caret*. `$item`, `$index` and the chained `$itemParent` are bound exactly like the
  form runtime binds them; `root` and `$payload` stay absolute. The scoped node is highlighted in the payload
  editor, and a path that no longer resolves is flagged on the field and reported as the evaluation result.
- **Resizable playground panels** — the divider between the expression editor and the payload/scope card is
  now grabbable (drag it to trade width when docked side-by-side, or height when stacked), and the frontend
  evaluation result sits under the payload behind its own drag handle so a large payload can be given more
  room; it still scrolls.

## 0.8.7

- **“Last scanned” time on the Model Index** — the Atlas Hub now shows when the model index was last built,
  next to the model count, so you can tell how fresh it is.

## 0.8.6

- **Works when the IDE backend runs remotely** (e.g. JetBrains Remote Dev in Kubernetes). *Generate Model
  Constants* no longer depends on a modal dialog that could silently fail on the thin client — it uses the
  class name from *Settings → Flowable Atlas → Generation*, reports any problem as a notification, and falls
  back to a folder picker when the project exposes no Java source root. *Open in Browser* now uses the IDE's
  own browser mechanism (like the built-in HTML action) and is shown only where a browser can actually be
  launched.
- **“Generate Model Constants” in the Atlas Hub** — right in the Hub's *Model Index* section, next to
  *Rebuild*.
- **Warning when you rename Java a model uses** — renaming a method or bean/delegate class referenced from a
  model expression (`${bean.method()}`, delegate expressions, …) warns that the model's text references are
  not updated by the rename, with a *Show affected models* action. A gutter icon also marks such methods/beans
  and navigates to the models that use them.
- **Stale-explorer hint after a Design pull** — pulling models from Flowable Design now offers a *Regenerate
  Atlas Explorer* action (and a hint in the Atlas Hub): the model index is rebuilt automatically, but the
  generated explorer is not.
- **Clearer settings wording** — the “index raw Design workspace sources” and “expressions in Java string
  literals” options now explain what they actually do.

## 0.8.5

- **"Paste session from browser" for "Evaluate Against App"** — the reliable way to evaluate backend
  expressions against an SSO/OAuth2-fronted app whose identity provider blocks the embedded browser login
  (e.g. Microsoft Entra Conditional Access). Log in to the app in your normal browser, copy any authenticated
  request (DevTools → Network → *Copy as cURL*, or just its `Cookie` header) and paste it: the plugin extracts
  the `Cookie`, `Authorization` and CSRF-token (`X-XSRF-TOKEN`) headers and replays them, so the Inspect
  request rides your already-authenticated session — CSRF-protected POSTs included. Captured headers live only
  for the current IDE session (nothing written to disk).
- **Embedded sign-in sends a desktop User-Agent** — the *Sign in via browser (SSO)* login now presents a
  normal desktop-Chrome User-Agent, which lets some IdPs accept the embedded login; where policy still blocks
  it, use *Paste session from browser*.

## 0.8.4

- **Sign in via browser (SSO) for “Evaluate Against App”** — the Expression Playground's backend evaluation
  now works against apps fronted by SSO/OAuth2, not just local basic-auth dev instances. A new *Sign in via
  browser (SSO)…* button opens the app in an embedded browser; you complete the real identity-provider login
  (Microsoft, Keycloak, …), and the resulting session cookie is reused for this IDE session so the Inspect
  request rides your authenticated session. Basic auth and the SSO cookie can be combined — for an OAuth2
  gateway in front of a Flowable that still wants basic auth. A login redirect now surfaces a clear “app is
  behind SSO/OAuth2 — sign in” message instead of a raw HTTP 302.

## 0.8.3

- **Monorepo detection refined** — a root-level build file (`pom.xml`/`settings.gradle`/…) that wraps a single
  app is now treated as one whole project and is never split into its modules; only a true reactor bundling
  two or more independent apps (each carrying its own `.app`) prompts you to pick a project. The Atlas Hub's
  *Change…* link now appears whenever a sub-project is detected, so you can always switch scope (previously it
  could lock you onto “Whole project”).
- **Fixes** — Atlas Explorer generation no longer fails with a `NullPointerException` on a service-model
  operation that declares an HTTP method but no URL. Resolved a plugin-load error (missing intention
  description) for the “Generate Java bean for this Flowable data object” intention.

## 0.8.2

- **Resizable Atlas Explorer sidebar** — grab the sidebar's right edge and drag it wider or narrower like an
  IntelliJ tool window; the width is remembered. Dragging it very narrow snaps it to the icon rail, and while
  collapsed, hovering the rail flies the full labelled menu out over the content — so a narrow window (or a
  narrow Atlas Hub tool window) no longer leaves you with unlabelled dots.

## 0.8.1

- **Multi-project (monorepo) support** — Atlas detects the distinct Flowable sub-projects under the project
  root; the Atlas Hub lets you switch the active one and the model index then scans just that subtree.
- **Flowable Inspect connection editor** — Settings → Tools → Flowable Atlas → *Connections* gains an embedded
  Inspect connection (base URL + credentials, with *Detect from project*) that the Expression Playground
  evaluates backend expressions against.
- **Fixes** — resolve a duplicate registration of the “Open in Expression Playground” intention that could
  abort the highlighting pass, and clear all remaining compiler warnings across the codebase.

## 0.8.0

- **Atlas Hub — one control center** — a new tool window (right stripe, or Tools → Flowable Atlas → *Atlas
  Hub*) shows the model-index status with per-type counts and a background *Rebuild*, every generated
  `*.explorer.html` (double-click opens the embedded viewer, with a browser fallback when JCEF is
  unavailable), and the Flowable Design sync state with last-pull time — refreshing live as models change,
  artifacts are generated or a pull finishes.
- **The explorer follows your IDE theme** — the embedded Atlas Explorer now opens in the IntelliJ light/dark
  theme and restyles live when you switch the LAF, without a reload. The in-page toggle still wins for
  explicit overrides; *auto* follows the IDE. In a plain browser nothing changes.
- **Explorer editor toolbar** — the embedded viewer gained a thin toolbar: *Regenerate* (re-runs the generator
  for exactly this file and reloads, no dialogs, no balloon), *Reload* and *Open in Browser*.
- **Settings, reorganized** — Settings → Tools → Flowable Atlas is now a small tree: the root page keeps the
  core toggles, and three project-level sub-pages hold the rest — *Expressions* (validation toggles and, for
  the first time, the expression allowlist as an editable table plus custom-function discovery), *Generation*
  (per-artifact selection instead of the old two-way switch, a configurable output folder, and the
  model-constants class options — now project-level and VCS-shared) and *Connections* (the full Flowable
  Design connection editor — replacing the old dialog — and the Inspect connection with *Detect from
  Project*).
- **Allowlist flows into generation** — the project's expression allowlist and the custom-function settings
  are now passed to the Atlas generator (as with the CLI's `--expr-allowlist` / `--custom-functions`), so the
  explorer's *Suspect* findings respect them.
- **Tools menu, tidied** — grouped into Open / Generate / Flowable Design / Maintenance; dialog-opening
  actions carry an ellipsis; the debug *Dump Key Index* became the user-facing *Rebuild Model Index*
  (background, balloon with counts) and the raw dump is internal-mode only. Consistent naming across dialogs
  and notifications; project settings consolidated into `.idea/flowable-atlas.xml`. The main-toolbar compass
  icon is gone — the Atlas Hub is the plugin's one visible surface (tool-window icons follow the native
  monochrome style).

## 0.7.6

- **Open an existing Atlas explorer from the menu** — Tools → Flowable Atlas → *Open Atlas Explorer* opens an
  already-generated `*.explorer.html` in the embedded in-IDE viewer, without regenerating it and without
  switching to a browser. It looks under `atlas-output/` first (where both the generator and the standalone
  `atlas` CLI write by default), so a page produced from the terminal opens straight in the IDE; if several
  exist you pick one, and if none is found it offers to generate. Opening now brings the rendered *Atlas
  Explorer* tab to the front immediately (instead of the HTML source), from the menu, the post-generation
  balloon and double-clicking the file alike.
- **One-click toolbar icon — opens in the center** — *Open Atlas Explorer* also sits as a compass icon in the
  main toolbar; clicking it opens the explorer as a **center editor tab** (like opening a database table),
  rendered right away, so it fills the main area and can be maximized like any editor.
- **One unified view — no “Text” tab, playground included** — the center Atlas Explorer no longer shows the
  raw-HTML *Text* sub-tab, and a second **Flowable Expressions** tab sits right next to *Atlas Explorer*, so
  the whole-project map and the expression playground share a single window instead of two separate entry
  points. (The standalone *Flowable Expressions* tool window still works too.)

## 0.7.5

- **Service-operation “Used by” now also finds Java callers** — Java code that invokes an operation through
  the data-object runtime builder (`…createDataObjectInstanceQuery().definitionKey(key).operation("op")`) is
  now detected and listed alongside the model consumers. The `definitionKey` is resolved even when it is a
  `static final String` constant reference (a generated model-keys class) rather than a string literal, and
  data objects resolve through their backing service — so the operation, the constant and the Java class all
  line up on the same node. Each class node in turn lists the operations it calls.

## 0.7.4

- **Service-operation “Used by” now finds usages hidden in data-source URLs** — forms and pages most often
  invoke an operation through a REST data source, lookup or navigation URL (e.g.
  `{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=…&dataObjectOperationKey=…`)
  rather than a structured field. The operation and target keys embedded as literal query params are now
  detected, so these usages appear in the operation's **“Used by”** list. Data-object references still resolve
  through their backing service. (Verified against a real project: operations with recorded usages went from
  none to dozens.)

## 0.7.3

- **Service operations are now first-class in the Explorer** — every service-registry operation (e.g.
  `findByPodId`) gets its own node under a new **Service operations** category, with a **“Used by”** list of
  every form service button, data-object field and CMMN service mapping that calls it. Data-object references
  are resolved through their backing service, so usages reached via a data object and via the service
  aggregate onto the same operation. Operation keys are searchable, and each operation in a service's
  Operations list links to its own where-used page.
- **Bot name links to its Java bot class** — in an action's detail, the Bot field is now a clickable chip that
  navigates to the backing Java bot class node (platform bots stay plain text).

## 0.7.2

- **Rainbow parentheses now work in the Expression Playground field too** — the paren colouring moved from an
  annotator into the syntax highlighter (the highlighting lexer tags each round paren with a colour).
  Annotator highlights do not reliably paint in the playground's embedded editor field; syntax-highlighter
  colours do, so parentheses are now coloured in the playground and in inline `${…}` / `{{…}}` fragments
  alike. Colouring is **per pair**: each opening `(` takes the next colour and its matching `)` reuses it, so
  neighbouring pairs are visually distinct while a pair's `(`/`)` always match.
- **Playground field is a proper scrollable editor** — the expression field is now a multi-line code editor
  with vertical and horizontal scrollbars, so long expressions can be scrolled and edited comfortably instead
  of being clipped.
- **Syntax errors point at the exact spot** — the playground now shows the first structural error with a caret
  under the offending offset (e.g. the unclosed `(` for a missing `)`), so you see where to fix without
  hunting. (Annotator squiggles don't reliably paint in the embedded field, so the position is surfaced
  directly.)
- **Autocomplete for project custom functions, with parameters** — the extracted `externals.additionalData`
  functions are offered in completion: custom namespaces and top-level helpers at the root, namespace members
  after `ns.`, and custom `flw.*` members after `flw.`. Each lists its parameter names and, on selection,
  inserts `(params)` with the parameters selected so they're easy to fill in. Real names are read from the
  source — including from a compiled bundle's **sourcemap** (`*.js.map` `sourcesContent`), so even a minified
  `custom.js` yields `findCommonAttribute(allItems, path, identifierPath?)` instead of `(e, t, r)`.

## 0.7.1

- **Custom functions show their arguments** — each custom-function node in the Atlas Explorer carries its
  signature (e.g. `flowdemo.findCommon(customer, docs)`), read from the source: inline arrows / method
  shorthands / function expressions, and — in a compiled bundle — an identifier member resolved to its
  `function name(…)` declaration.
- **Frontend bindings link to the custom functions they call** — a `{{…}}` binding now references not only its
  form/model but also each custom function it calls (*Calls custom functions*), and every custom function
  lists the exact bindings that call it (*Called in bindings*), in addition to the forms under *Used by*.

## 0.7.0

- **Rainbow parentheses render again in frontend `{{…}}` (and backend `${…}`) expressions** — each nesting
  level of `( )` is coloured so a matching pair shares a colour and a missing one stands out. The colours are
  now forced onto the annotation instead of relying on a colour-scheme default that silently stopped rendering
  after a platform upgrade, so they show reliably in the playground and inline in `.form` / model fragments.
- **Custom functions are found in compiled frontend bundles** — a project that ships only the built
  `static/ext/custom.js` (Rollup UMD: `var additionalData = {…}`) or a nested `externals: { additionalData:
  {…} }` config now has its `externals.additionalData` functions read too, not just uncompiled source. A React
  `<Form additionalData={…}>` prop is not mistaken for a registration.
- **Custom functions are cross-referenced in the Atlas Explorer** — each project custom function (a
  `flowdemo.*` namespace member, an extra `flw.*` member, or a top-level helper) is a first-class node: it
  lists the forms/models whose `{{…}}` bindings call it, and each form lists the custom functions it uses
  (navigable both ways).

## 0.6.0

- **Project custom functions are read from source and validated precisely** — functions a project registers
  via `flowable.externals.additionalData` (e.g. a `flowdemo.*` namespace, or extra `flw.*` members) are
  extracted from the frontend-customization source in the project. Calls to them now validate exactly: a known
  member is valid, a close typo is flagged (*did you mean …?*), and unknown names are left alone. Compiled
  bundles that can't be read are skipped; unresolved constructs (spreads, computed keys) are noted, never
  guessed.
- **Payload preview no longer marks valid expressions invalid** — an expression that is correct but can't be
  evaluated statically (a running-form/locale member such as `flw.getUser`, or a custom
  `externals.additionalData` function) now shows a neutral *“not available in the payload preview”* note
  instead of a red error.
- **Leniency for custom `flw.*`** — an unknown `flw.<member>` with no close match to a built-in is treated as
  a project-injected custom function and not flagged; only a plausible typo is surfaced.
- **Atlas Explorer** shows a *custom functions* badge/panel listing the project's `externals.additionalData`
  functions and where they were read from.

## 0.5.0

- **Expression warnings are now real inspections** — unknown function / namespace / `flw.*` findings and the
  opt-in codebase grounding moved from a fixed annotator to *Settings → Editor → Inspections → Flowable*:
  severity is adjustable, they can be disabled per profile/scope, and they appear in *Inspect Code*.
  Structural syntax errors are still flagged directly.
- **Project allowlist for custom functions** — Alt-Enter on a finding offers *Add … to Flowable expression
  allowlist*: functions/namespaces your project registers itself (which the built-in catalog cannot know) are
  silenced project-wide. Stored in `.idea/flowable-atlas.xml`, shareable via VCS. The "ground backend
  expressions" checkbox moved into the (default-off) grounding inspection.
- **Expression Playground** shows semantic findings (allowlist-aware) in a status line.
- **Modernized Atlas Explorer** (bundled generator) — light/dark theme with toggle, responsive layout,
  keyboard & screen-reader support, no more 600-item list cap (incremental scrolling), working browser
  back/forward + a copy-link button, an SVG neighborhood graph per node, and a *⚠ parse issues* badge that
  lists files the generator could not fully analyze. Structural syntax errors and "unknown function" findings
  are now separate *Invalid* / *Suspect* categories, and `--expr-allowlist` suppresses findings for
  project-provided functions.
- Internals: diagnostics logging at previously silent failure points (model indexing, generator, Inspect
  client), consolidated JSON/wrapper helpers.

## 0.4.1

- **Requires an IDE restart on install / update** — the plugin registers languages, file types and parser
  definitions (the two expression dialects) that cannot be loaded dynamically, so IntelliJ now prompts for a
  restart instead of silently half-loading (which left the Flowable Expressions tool window and its stripe
  icon missing).
- **Open the Expression Playground from the menu** — Tools → Flowable Atlas → *Open Expression Playground*
  shows the Flowable Expressions tool window directly, independent of the tool-window stripe button.
- **Fixed the tool-window icon** — a correctly sized (16×16) icon that renders reliably across display
  scalings, instead of the oversized plugin icon.

## 0.4.0

- **Flowable expression language support** — first-class editor support for both the backend JUEL dialect
  (`${…}` / `#{…}`) and the frontend form dialect (`{{…}}`): syntax highlighting, rainbow parentheses, brace
  matching, live validation (syntax + unknown functions/namespaces/`flw.*` members against the verified
  Flowable catalog) and completion of functions, root objects and the project's variables/form-fields.
  Expressions are recognised inline in BPMN/CMMN/DMN XML, `.form`/`.page` JSON and (optionally) Java strings.
- **Expression Playground** — a *Flowable Expressions* tool window to try an expression with live validation
  and completion, evaluate a frontend expression against a pasted JSON payload, or evaluate a backend
  expression against a running app via the Flowable Inspect REST API (connection auto-detected from the
  project's Spring config).

## 0.3.0

- **Renamed to Flowable Atlas** (formerly Flowable Keys) — the plugin now also produces the Atlas explorer,
  not just model-key tooling.
- **Generate Atlas Explorer** — Tools → Flowable Atlas → *Generate Atlas Explorer* runs the bundled Atlas
  generator over your project and writes a single self-contained, interactive HTML page. Choose where to save
  it in the project, then open it in the external browser or in an embedded in-IDE viewer. Any
  `*.explorer.html` in the project also opens in the embedded viewer.
- **All artifacts option** — a *Generate* scope in Settings → Tools → Flowable Atlas switches the action from
  "explorer HTML only" to writing the full Atlas set (summary.md, overview.md, graph.json, explorer.html,
  CLAUDE.md) into a folder you pick.

## 0.2.0

- **Infix key search** — completion now matches any fragment of a key, so typing `0061` at
  `definitionKey("…")` proposes `DEMO-DO-0061` (start / word-boundary matches still rank first).
- **Scoped task/activity completion** — `taskDefinitionKey` / `activityId` are narrowed to the sibling
  `processDefinitionKey` / `caseDefinitionKey` in the same query chain, falling back to the project-wide union
  otherwise.
- **Model → model references in BPMN/CMMN XML** — completion, Ctrl/Cmd-click navigation and a broken-key
  inspection (with quick fix) for `calledElement`, `flowable:formKey`, `decisionRef`,
  `decisionTableReferenceKey`, CMMN `caseRef` / `processRef`.
- **Generate a typed data-object bean** — Alt/Option-Enter on a data-object `definitionKey("…")` writes a Java
  POJO from the model's field mappings.
- Long project scans now honour cancellation (no UI stall while indexing / finding usages).

## 0.1.0

- Initial release: context-aware Flowable model-key completion, cascade completion, broken-key inspection, key
  navigation & Find Usages, model-constants generation, and Liquibase changelog awareness.
