# Generated artifacts

One run produces six artifacts. Five of them are not five formats of the same thing — they are five
*sizes*, meant for five different readers, and picking the right one is most of the value. The sixth
is a different kind: a guide, in two files, for an agent that drives Flowable Work in a browser. A
project with database tables also gets the ER diagram designer, and a run given a `--baseline` the
report of what changed since then.

```
atlas-output/order-management/
  order-management.explorer.html   an offline interactive explorer — open by double-click
  order-management.summary.md      a few KB: apps, inventory, entry points, health
  order-management.overview.md     the full human report, every model in execution order
  order-management.graph.json      the traversable graph, for querying — not for reading
  order-management.CLAUDE.md       drop-in context for an AI agent
  order-management.playwright.md   how an agent drives Flowable Work in a browser, and an index of this project
  order-management.playwright-map.md   …and the project map behind that index: stages, tasks, forms
  order-management.diagrams/       one SVG per model that can be drawn
  order-management.erd.html        the ER diagram designer — when the project has tables
  order-management.changes.md      what changed since the --baseline — when there is one
```

Only `--all` names files after the project — after the directory's real name, so a project analysed
from inside itself as `.` is named like any other. A single-artifact run writes `APP_OVERVIEW.*` into the
project unless you name the file yourself — see the [CLI reference](../cli/#output-format).

## `<project>.explorer.html` — the explorer

A single self-contained page: the CSS, the JavaScript, the embedded font and the whole graph as a JSON
island, inlined into one file with no external requests. Copy it, mail it, open it on a machine with no
network — it works. In the IDE the same file renders as an editor tab, theme-synced with the IDE. Its
footer says which Atlas version generated it and when, so a page that has travelled can still tell you
how old it is.

A large project's graph — anything above 1 MB of it — is embedded deflated and Base64-encoded, and the
page inflates it with its own code before it starts: the file is about a quarter of the size, and it
still opens the same way in any browser, from disk, from a web server or in the IDE. That is what keeps a
large project's explorer under the 20 MB above which the IDE, and a Remote Development client, refuse to
open a file.

This is the artifact for a human who wants to *understand* a project. It has its own page:
[The Atlas explorer](../explorer/).

## `<project>.erd.html` — the ER diagram designer

The project's tables — as the Liquibase changelogs leave them, and those only a database service names —
to drag onto a canvas — with databases and tables added by hand — relate, gather in named frames, colour, search
and arrange, for explaining a data
model to people who do not read changelogs; with every `*.atlas-erd.json` diagram the project keeps built in, so the page opens
on the diagrams the team has committed. It carries only the tables, not the graph, so it stays a few
hundred KB however large the explorer gets. `--all` writes it when the project has a table, and links its
tables into the explorer beside it; `--erd` writes it on its own. It has its own page:
[The ER diagram designer](../erd/).

## `<project>.summary.md` — orientation

A few kilobytes, written for an LLM first and a human second. In order: the project's scale, a
parse-failure banner if there is one, the apps, the inventory (models counted in Design's wording, Java
by role, variables per scope, expressions by dialect), the entry points grouped by who can start what,
the REST surface, the service and messaging integrations, the Java glue wired to models, the hotspots,
the external surface, and a health block that names the open defects under its *defects · advice*
headline, errors first.

Use it to answer "what *is* this project" in one read. It is the right thing to paste into a
conversation. It sends the reader on to the overview, the graph and the explorer only when they were
written beside it; a `--summary` run on its own — or a plugin run with them unticked — says how to
generate them instead, and the overview points at the graph and the diagrams the same way.

## `<project>.overview.md` — the full report

Fourteen numbered sections, every model in **execution order** rather than alphabetical: apps,
processes, cases, decisions, forms and pages, data objects (master-data lists are their own kind,
`masterData`), dictionaries, AI agents and bots, the
integration surface (services, channels, events, REST), the Java glue, resolved references, unresolved
references (with the suspect and dynamic subsections separated out), the access map, the code map, the
variable and expression inventory, and every finding with its `file:line`.

It also carries the data-layer chain — service ↔ Liquibase table ↔ data object — which is the one view
that shows whether the database and the models still agree.

What it says, it says once and in the words that mean it:
- **Processes.** A boundary event is listed right after the activity it hangs on, and says which and
  whether it interrupts; an embedded sub-process lists its contents before what follows it. Only an
  element no flow and no event reaches is marked *not reachable* — an event sub-process *starts on its
  event*.
- **Cases.** The plan tree is a list under its heading, each criterion with what its sentry waits for —
  the transition and the plan item by name, and the condition (`entry on complete "Assessment" if …`) —
  and each rule in words (*required*, *repeats while …*, *manual activation*). A name that repeats in
  the case is said with its stage, or with its id where a sibling carries the same one. The line says
  how its parts combine: the on-parts of one sentry are joined by *and*, a second criterion of the same
  kind starts with *or* (`· entry on complete "A" · or entry if …`). `(triggerMode=onEvent)` marks a
  sentry whose event and condition have to coincide, a sentry with neither reads *at once*, and an exit
  says what it does to its stage where the model sets it (`exitEventType=forceComplete`). A process or
  case task carries its in and out mappings, as a call activity does:
  `in(orderId→reviewedOrder) in("express"→priority) out(total→total)` — a quoted source is a literal,
  a bare one a variable. Only the sentries no plan item uses are listed after the tree.
- **Assignments.** A task that initializes variables says what it assigns, in a case and in a process:
  `set(approved=false) set(stage="Intake")` — a string quoted, a variable or an expression in a
  code span, and the type alone where the model sets no value (`set(draft: jsonObject)`).
- **Forms.** A data source several components share is listed once, with how many (`×3`).
- **REST calls.** One line per method and URL, with who calls it. ⚠ marks only a call on the
  application's own base that no endpoint of the project serves; calls to Flowable's own REST API and to
  other systems are listed apart.
- **Variables.** Past 60, the ones shared by the most models, each with the elements that set and read it.
- **Paths.** A model inside a Design export is named `Name-bar.zip!entry`, and one line under the header
  says where each archive lives.

Use it when you need the whole picture and you are going to read it.

## `<project>.graph.json` — for querying

The complete model↔code graph. **Do not read it whole and do not paste it into a prompt** — a large
real project produces several megabytes. Query it.

It is designed to make that easy:

- A **`_schema`** key comes first and describes the file's own shape, including eight ready-made `jq`
  recipes. An agent that opens the file learns how to use it from the file.
- A **`_generated`** key beside it says when the file was written and by which Atlas version, so a
  graph pasted into a ticket or kept as a pipeline artifact carries its own age.
- Every node carries **`usedBy`**, so relationships resolve in both directions without a second pass.
  That includes the nodes no edge points at — a variable, an expression, a service operation — whose
  `usedBy` lists the models that use them; a variable's `data.writes` and `data.reads` say which element
  sets and reads it.
- An `expression` node that is a Spring property placeholder — `${mail.imap-url:imap://localhost/inbox}`,
  the shape a channel URL takes when the environment fills it in — carries **`placeholder: true`**
  instead of a JUEL verdict, and its segments are not harvested as variables.
- An `endpoint` node carries **`params`**, what a caller sends it. Each entry has a `name`, `in` (`path`,
  `query`, `header`, `cookie`, `body`, `part` or `matrix`) and a `type`, plus `required`, `default`,
  `implicit` (no annotation, so Spring binds it by name) and `notInPath` where they apply. The path's
  `{variables}` come first. The key is missing when Atlas could not read the handler's signature, and an
  empty list means the handler takes nothing.
- The model bodies keep what Work does with them. In **`forms`**, a field keeps its **`validation`**
  (`pattern`, `mask`, bounds, lengths, a date's `format`, `custom` checks) and, as **`containerGate`**,
  the id of the nearest container around it that can hide, disable or ignore it — each described once in
  the form's **`containers`**, where a container's own `containerGate` names the next one out, so the
  chain is followed from the field outwards; an outcome keeps `visible`, `enabled`, `ignoreValidation`
  and `navigationUrl`; a form keeps `outcomeVariableName`, `doNotCreateOutcomeVariable` and
  `designVersion`. In **`cases`**, `planModel.exitCriteria` holds what ends the whole case; a sentry keeps
  its `triggerMode` and the `events` its on-parts wait for; every criterion keeps its `planItemId`, and an
  exit its `exitEventType` and `exitType`; a stage or milestone keeps `displayOrder`,
  `includeInStageOverview` and `overviewIndex`, its place in Work's stage overview. In **`processes`**, a
  user task, sub-process or event inside a sub-process keeps that sub-process's id as `parent`, and a
  boundary event its `attachedTo` and `cancelActivity`. In **`apps`**, an app
  keeps `flowApp`, `url` and `order`, and each page its `label` and `url`. The
  [Playwright guide](#playwrightmd-driving-flowable-work-in-a-browser) is built from them, and its §10
  queries them. A credential written into a model is the exception: a secret-named literal, the
  credentials in a URL and a secret-named query parameter are kept as `***` — see
  [`hardcodedSecrets`](../checks/#hardcodedsecrets-a-secret-written-into-a-model).
- **`findings`** is the itemised list every other surface summarises, each finding with the `kind` of
  its check — `defect` or `advice`, whatever its severity — **`checks`** the open count per check plus
  `open` and `waived`. A finding's `line` is a line of its `file`: the element's
  declaration, the line that names a missing model, or the line of the script a script problem sits on.
  A script problem whose file line cannot be found — the script of an action or of a service operation
  is a JSON string — carries `scriptLine`, its line within the script; a service operation's also has the
  `line` of the operation. An expression finding carries `models`, the models
  that hold the expression, and the `file` of the one model when there is one. **`waivers`** — present
  when the project carries a `waivers.json` — its rules with how many findings each matched, its notes,
  and what is wrong with the file. See [Health checks](../checks/) for the two kinds of check, `defect` and `advice`.
- **`stats`** has the scale: `modelCount` is how many models were read, `models` how many loose model
  *files* and `archives` how many `.zip`/`.bar` archives held the rest — and the health headline,
  `defects` and `advice`, the open findings of each kind.
- **`diagnostics`** lists what Atlas could not read (`parse`), what it skipped on purpose (`skip`),
  which keys two model types share (`conflict`) and which models are defined twice by files that reference
  different models (`copy` — a test stub or an older version with a production key; the model's node names
  the other file under *Other copies*, and shows the references of both). The last two are information,
  not findings. **`modelCopies`** — present when a model is in more than one file — gives each such
  model's files, each with a fingerprint of what that copy says: how the
  [changes report](#changesmd-what-a-change-changed) sees a change made to a copy the graph does not
  show. **`markers`** lists every
  `TODO`, `FIXME` or `HACK` it found in a model file, with file, line, the model it belongs to and — in a
  JSON model — the `path` of the element holding it; the raw material of two of the checks.
- It is **minified** by default, and a model's body is stored once in a top-level bucket with its graph
  node pointing there via `data.dataIn` — a Liquibase changelog's too, in **`liquibase`**
  (`.liquibase[] | select(.key=="K")`). Together that roughly halves the file — 4.8 MB → 2.5 MB on a
  large real project. `--pretty` indents it when a human has to look.
- A key whose value would be **null is not written**. `.file` or `.resultVariable` answers null either
  way, so a query reads the same; test a value rather than `has(...)`. The key cost bytes in the file and
  in every record cut from it — most of all in a form, whose fields each said `"value": null`.
- **A changelog's columns** are listed once. A changelog in `liquibase` lists the columns of the tables
  it shapes as they stand once its run is over, each with the change set it comes `from`. A table that
  several changelogs of one run shape — an application that grows a table by one file per release — is
  listed under one of them: the one a service reads the table from, else the one that created it. The
  others name that changelog in `columnsIn`.
- **Java classes** are references. `javaByRole` lists the classes by role (`controller`, `service`,
  `bot`, …) and `javaGlue` the delegates and listeners, each as `{fqn, file, line}`; what Atlas read from
  a class — its roles, bean names, methods and endpoints — is on its node `java:<fqn>`. The parser's raw
  record (imports, string literals, call sites) stays out of the file: on a project with a lot of Java it
  was half of it. Every expression and binding is a node of type `expression` or `binding`, so there is no
  separate list of them.

Every node id is `<type>:<key>`, and every edge is `{s, t, rel}` plus the two honesty flags described
under [uncertain links](#uncertain-links).

```bash
# what does this process reference?
jq '.graph.edges[] | select(.s=="process:orderProcess")' graph.json

# what would break if I changed this form?
jq '.graph.nodes[] | select(.id=="form:orderForm") | .usedBy' graph.json

# every open defect — what is wrong now, warnings included
jq '.findings[] | select(.kind=="defect" and (.waived|not))' graph.json
```

## `<project>.CLAUDE.md` — context for an agent

A Flowable primer *plus* this project's discovered facts, written to be dropped into a repository root
as `CLAUDE.md` (or `AGENTS.md`), or imported from one with `@<path>`. It contains a starting procedure
that names the project's real files by their path from the root (and, written by the CLI, ends with the
command that checks a model edit added no defect, `--baseline` against the generated `graph.json`), the
platform mental model an LLM usually gets wrong, how custom code attaches to models, where this project's
custom Java goes and which model files are Design exports not to be edited, its key conventions and
wiring examples, its open findings, and the complete list of the expression namespaces, script
bindings and platform beans that **actually exist** — which is the part that stops an agent inventing APIs.

Not in it: the inventory, the counts, the directory layout. They are the summary's, and an overview an
agent could derive itself is the one kind of context-file content measured not to help
([Gloaguen et al. 2026](https://arxiv.org/abs/2602.11988)); see [For LLMs & agents](../agents/).

`CLAUDE.template.md` in the repository is the project-independent primer alone, generated from the same
source so the two cannot drift. `--claude-template` prints it without needing a project.

See [For LLMs & agents](../agents/) for how the four sizes fit together.

## `<project>.playwright.md` — driving Flowable Work in a browser

What an agent needs to work *in* Flowable Work rather than on the code: start a case or process, open a
case or task, see which stage a case is in, fill and complete the task forms, press the case's actions,
bring a case to a given stage, find the open tasks of a user or group, explain why a button is missing or
greyed out. It is written for an agent with a browser — the Playwright MCP server, a Playwright script or
another browser tool — and it has two halves.

**How Work behaves, for any project (§0–§8).**
- **Quick start.** The base URL, how to start or open an instance, which groups the route needs, and a
  snippet that reads a case's whole state in one call: who you are, the stage bar, every plan item and every
  open task.
- **Rules.** Act through the UI and only *look* through REST. Never click a disabled button. Ask before
  anything irreversible, and report the path taken.
- **The UI.** The sign-in, the routes, *Create New*, the instance header and its stage bar, the toolbar
  where manual-activation items and user event listeners become buttons, and the task's outcome buttons.
- **REST, read-only.** The endpoints that explain a screen: `stage-overview`, plan item states, tasks, case
  variables, the deployed form, and the lookup that turns a business key into an instance id.
- **The CMMN rules that move a case.** Sentries and their trigger mode, manual activation, required items,
  repetition, and when a stage or the case completes.
- **Why a button is disabled**, and where on the page the invalid field hides.
- **The loop for reaching a stage**, the Playwright traps, and what each script call is in Playwright MCP.
- **Versions.** It describes 2026.1 and newer and marks what older lines do differently; its version table
  has one column each for 2026.1 and newer, 2025.2, 2025.1 with 3.15–3.17, and 3.14 and older. The version
  the project builds against is read from its `pom.xml` or Gradle files, and the note at the top of the file
  names the column to use. The notes are compiled from Work's frontend source for each line and its own
  end-to-end test helpers, not by driving a running Work, and the guide says what to fall back on when a
  locator finds nothing.

**This project (§9), read from the models.** §9 is an index: every deployed case and process with its
key, the link that starts it (`#/work?create=true&definitionKey=<key>`, which also tells apart two
definitions with one title), its stage bar or its number of user tasks, and who may start it; then the
apps the sidebar shows, with their pages. Behind it are the details:
- **Cases.** Each case's stage bar in Work's order: every stage and milestone, sorted by `displayOrder`
  with the unordered ones first, minus those with `includeInStageOverview="false"`. The order is exact when
  every shown entry has its own `displayOrder`; Work reorders entries that share one, or have none, by when
  they end, and the file marks such ties with ⚠. For each stage it says what starts it, read out of its
  sentries — *Assessment* completes **and** `${assessmentOutcome == 'approve'}` ← outcome **Approve** on
  task *Assess damage* — whether it completes by itself, and every task, manual item and listener inside
  it, with the groups that get them.
- **Processes.** Each process's user tasks in flow order, and what leads to each — for a task inside an
  embedded sub-process, the condition in front of that sub-process.
- **Actions and forms.** The project's own actions, and each form behind the tasks: its required fields and
  their rules, its outcomes and the variable they write, and its conditional buttons — flagging a required
  field that can be hidden, which, on a form saved by Design 3.8 or later, keeps the outcomes disabled with
  no error on the page.
- Models that only a test source set deploys are named once and left out.
- §10 gives the `jq` queries for the rest, run from the project root.

**One artifact, two files.** `--all`, the launcher and the plugin write the details to
`<project>.playwright-map.md` beside the guide, and §9 points into it: a skill is loaded whole, and on a
real project the details are most of the text. `--playwright` alone writes one file, with the details
inline. Written outside the project — the launcher's default — the guide names the map and the graph by
their full path, with the home directory as `~`.

The guide opens with skill front matter. Keep it beside `CLAUDE.md` — the generated `CLAUDE.md` says when
to read it — or install it as a Claude Code skill: copy the guide to
`.claude/skills/flowable-work-ui/SKILL.md` and the map to `.claude/skills/flowable-work-ui/project-map.md`,
and Claude Code loads it whenever a request is about Work. Copy both again after each regeneration;
[For LLMs & agents](../agents/#working-in-flowable-work-itself) has the setup.

The guide generated for the sample project is live: [playwright.md](../demo/playwright.md) and its
[project map](../demo/playwright-map.md). Its *Claim handling* case has five stages, an outcome-gated route
and a shortcut.

## `<project>.diagrams/` — SVG per model

Written by `--all` when any model can be drawn. One SVG per process, case or decision, rendered from
the model's diagram interchange — deployment `bpmndi` / `cmmndi` / `dmndi` XML, or a Flowable Design
workspace's ORYX JSON; one per form or page, as a **wireframe** of its twelve-column grid; and for a
decision with no canvas, its **decision table** drawn from its rules. They are the pictures the explorer
shows on a model's page and the IDE's model preview draws — one renderer for all three. The folder holds
this run's drawings and no others: a model that is gone loses its drawing on the next run, and a file in
it that is not an SVG is left alone.

Nothing is downloaded and no Design instance is contacted: the geometry is already in your models.

## `<project>.changes.md` — what a change changed

Written by `--all` when it is given a `--baseline`, and on its own by `--changes`: the difference
between the baseline and the project now, for the review of a change whose models sit in archives git
shows as binary. A header line counts what follows; then three sections, each only when it has something
to say:

```markdown
**Changed (2)**
- `process:orderProcess` Order Process — added: `checkStock`, `bookCourier`, `f9`, `f10` · changed: `badStamp` (script), `f7` (to)
- `form:orderForm` Order Form — added: `backofficeNote` · removed: `internalNote`
```

- **Models** added, removed and changed, by node id. A changed model's line names its elements — whatever
  in it carries an `id`: a task, a flow, a form field, a decision rule, a plan item, a changeset — that were
  added or removed, the ones that changed with the properties that did, and the model's own properties
  that changed. A renamed field is one removed and one added. Where a model is written and what the
  analysis derived from it — the variables it uses, the problems found in a script — are not compared:
  they change when another file does. A model the project holds in more than one file — a Design export
  committed beside the app's `-bar.zip` — is compared in the copy the graph shows, and its other copies
  by what they say: when only another copy changed, the line says `changed in another file` and names it.
- **References** added and removed — `process:orderProcess` → `external:DEMO-nowhere` (callActivity) —
  without app membership, which the app's own line says.
- **Findings** the change brought and the ones it resolved, worst first, by the identity a waiver uses —
  the same findings the `--baseline` gate of the same run calls new.

Every list is capped, so a change to hundreds of models still fits in a pull-request comment. A project
that did not change says so in one line. The [CLI reference](../cli/#review-a-pull-request) has the
recipe for a pull request.

## `--slice <type:key>` — one model in context

Not a file the full run writes, but the tier between the summary and the graph, printed to stdout or to
`-o`:

```bash
java -jar cli-<version>-all.jar <project> --slice process:orderProcess --stdout
```

One node with what it uses, **who uses it**, the findings that touch it, and its own attributes — sized
for an agent that has been asked to change exactly one model. A process, case, decision, form or page
also gets its *Contents*, exactly as the overview lists them — the steps in execution order with what
follows each, the plan tree, the rule rows, the fields — and its *Variables*, each with the elements of
the model that set and read it. A form's slice lists every field, where the overview stops at 40. A
variable's slice names every model that uses it with what the model does (*sets*, *reads*, *sets and
reads*), and under *Set by* and *Read by* the element, how, and the other end of a mapping: the value
an assignment sets, the variable of the called model an out-parameter copies, the variable a read is
copied to. A model's
*Contents* are one line per step, plan item or field, each complete in itself, so one element of a
large model is `… --stdout | grep -i '<its name>'`. A bare key without a type matches any type and
renders every match.
So does what an agent usually has in hand instead: the model's file path (`--slice processes/order.bpmn`,
or its absolute path), a Java class's simple name, a label, or the key in another case — the slice then
opens with a line saying what it matched by. A target that matches nothing names the nearest ids instead.

## Uncertain links

Atlas distinguishes three states, in every artifact, rather than presenting a guess as a fact:

- **resolved** — the reference ties to a real node.
- **suspect** (`≈`) — resolved, but not certainly: a Java simple name or bean name two classes share, a
  Java string literal that merely equals a model key without being passed to a key-taking API, a model
  referenced by id where a key is expected, or a REST call whose verb the handler for that path does not
  serve. A literal passed to a key-taking API reaches a model of the type that API takes —
  `.caseDefinitionKey("X")` is the case `X`, never a process or form of the same key — and a constant
  passed to one (`.caseDefinitionKey(ModelConstants.MAIN_CASE)`) is resolved to its `static final String`
  value and counts as the literal would, unless two classes give that name different values. A key that
  only a model of another type has is not a suspect link but a missing reference: a call activity
  naming a form's key starts no process.
- **dynamic** (`ƒ`) — the reference was an expression, so its target is only known at runtime. Atlas
  records the placeholder; `${subProcessKey}` reads a variable when it runs, and no Java constant of that
  name says which.

Anything that could not be resolved at all is listed separately — platform beans, external REST
endpoints, missing keys — so a real gap stands out instead of hiding among the noise.
