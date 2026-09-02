# Plugin reference

Everything the plugin registers, in one place. For what it is *for*, read
[What the plugin does](../) first.

Plugin id `com.flowable.atlas`, version {{VERSION}}. Requires a restart after installation, because it
registers languages and file types.

## Compatibility

| | |
|---|---|
| Installs on | IntelliJ IDEA **2026.2** and later (`since-build 262`, `until-build 299.*`) |
| Verified against | **2026.2** — what `verifyPlugin` actually runs against; a bug report submitted from Atlas states the running IDE's branch and whether it is inside that range |
| Compiled against | The 2026.2 SDK — the floor, the compile target and the verified branch are one and the same, so one artifact loads on every later version |
| Requires | Java 21+; the Java, XML and JSON platform modules |
| Uses if present | The embedded browser (JCEF), for the explorer tab and the SSO login. Without it the explorer opens in an external browser instead |
| Distribution | A ZIP attached to each [release](https://github.com/kle-dev/flw-atlas/releases/latest), signed when a signing key is configured. Not on the JetBrains Marketplace, but the release publishes an `updatePlugins.xml`, so adding it as a plugin repository puts Atlas in the IDE's normal update flow — see [getting started](../../start/#the-intellij-plugin). `until-build` stays deliberately wide, so an IDE upgrade cannot make the plugin vanish |

## Actions

All actions live under **Tools → Flowable Atlas**, and are also reachable through *Find Action*.
**None of them has a keyboard shortcut** — the Hub is the plugin's visible surface, and there are no
context-menu or main-toolbar entries.

| Action | Also in |
|---|---|
| Atlas Hub | — |
| Open Atlas Explorer | Atlas Hub toolbar |
| Open Expression Playground | Atlas Hub toolbar |
| Search Models… | Atlas Hub, *Model Index* |
| Generate → Generate Atlas Explorer… | Atlas Hub link |
| Generate → Generate Model Constants… | Atlas Hub link |
| Generate → Liquibase → From Data Object… | — |
| Generate → Liquibase → From App(s)… | — |
| Generate → Data-Object DTOs → From Data Object… | — |
| Generate → Data-Object DTOs → From App(s)… | — |
| Switch Design Environment… | Atlas Hub, *Flowable Design* |
| Pull from Flowable Design | Atlas Hub link |
| Switch Work Environment… | Atlas Hub, *Expression Playground*; the playground itself |
| Manage Environments… | Atlas Hub link |
| Rebuild Model Index | Atlas Hub link |
| Dump Key Index (Internal) | Only visible in an internal-mode IDE |

Panel toolbars carry a few more that are not registered actions, so they do not appear in *Find
Action*: the Hub's *Refresh* and, in its *Flowable Design* section, *Reload from Flowable Design*; the Environments page's *Test Connection*; the explorer tab's *Regenerate*, *Reload* and *Open in Browser*; the
Expression Playground's dialect toggles, scope selector, *Evaluate Against App*, *Show Sub-Expression
Values* and settings popup; and the Script Playground's language and context selectors with *Load Script
from Model…* and *Load Example…*.

## Tool windows

| Tool window | Where | Contents |
|---|---|---|
| **Atlas Hub** | Right stripe | Flowable Project · Flowable Design (environment · workspace · apps · pull) · Expression Playground · Model Index · Atlas Explorer · footer. See [the Hub](../#the-atlas-hub) |
| **Flowable Expressions** | Bottom stripe (secondary) | Two tabs: *Expressions* and *Scripts*. See [the playgrounds](../#the-playgrounds) |

The generated explorer also opens as an editor tab with two tabs of its own: **Atlas Explorer** (the
page, in the embedded browser) and **Flowable Expressions**.

## Inspections

*Settings → Editor → Inspections → **Flowable***.

| Inspection | Where | Default | Flags |
|---|---|---|---|
| Unknown Flowable model key | Java | on, warning | A key literal — or a constant reference — at a Flowable API call site that matches no indexed key of that site's type. Quick fix to the nearest real key (literals only) |
| Unknown Flowable model key (model XML) | BPMN / CMMN / DMN | on, warning | The same, for cross-reference attributes and extension-element text. Values containing `${` or `#{` are skipped |
| Invalid Flowable data-object value field | Java | on, warning | A `value("field", …)` that is not an input parameter of the operation named earlier in the same fluent chain. Quick fix to the closest valid field |
| Liquibase column not defined in Flowable model | XML | on, warning | A changelog column that maps to no field of the backing `.service` model |
| Unknown Flowable expression function or namespace | Expressions | on, warning | An unknown namespace, function or `flw.*` member, and dialect misuse. Quick fixes: replace with the nearest name, or add it to the project allowlist |
| Expression root is not a known variable, bean, or root object | Expressions | **off**, weak warning | A backend root identifier that is not a catalogued engine root, an indexed variable, or a name used elsewhere in the project |

Two things are validated outside the inspection system. **Expression syntax** is painted by an
annotator, because the daemon's inspections do not run inside the playground's editor field. And Atlas
**suppresses** the unresolved-symbol inspections of Groovy and JavaScript inside Flowable script bodies
(`GrUnresolvedAccess`, `JSUnresolvedReference` and their siblings), since the bindings come from the
engine rather than from the file.

## Gutter icons

Four, all on Java code:

| Icon on | Goes to |
|---|---|
| A class or method referenced by a model | The models that reference it ("Flowable Models") |
| A `BotService` implementation | The `.action` models that use that bot |
| A Spring REST handler | The models that call that endpoint |
| A model-key literal or resolvable constant | That model's diagram, in the Images viewer — shown only when a diagram actually exists |

## Navigation

| Gesture | From | To |
|---|---|---|
| Ctrl/⌘-click | A key literal or key-argument constant at a Flowable API site | The model file(s) declaring it, narrowed to that site's types |
| Ctrl/⌘-click | `operation("…")` / `value("…", …)` | The backing `.service` model |
| Ctrl/⌘-click | A cross-reference attribute in model XML | The referenced model |
| Ctrl/⌘-click | Any literal whose value is a known key | Its model — **only** with *Recognize model keys anywhere in code* enabled |
| Ctrl-Q / F1 | A key literal | Key, model type, name, backing table for a service or data object, and the file |
| Find Usages | A Java method, field or class | Every model that references it by name, inside `${…}` / `#{…}` or a `class` / `delegateExpression` / `expression` attribute |
| Find Usages | A bot class | The `.action` models whose `botKey` matches |
| Find Usages | A Spring REST handler | The models whose HTTP task, REST button, service operation or REST data source calls that URL |
| Search Everywhere / Go to Symbol | — | Every model key, plus bot keys — from actions and from `BotService` implementations |
| Search Everywhere → **Flowable Model** tab | — | Model keys, archive-qualified paths inside `.bar` / `.zip`, and a live full-text search over model content showing the matched line |

Two more behaviours belong here even though they are not navigation. Renaming a Java symbol that models
reference raises a warning with *Show affected models*, because the refactoring engine cannot rewrite an
expression string. And anything models reference is reported as **implicitly used**, so the IDE stops
offering to delete code a process depends on.

## Code completion

| Where | Completes |
|---|---|
| Any Flowable API string argument | Model keys of that position's type(s) — searchable by key, name, or any fragment, so `0061` finds `DEMO-DO-0061` |
| `operation(…)`, `operationKey(…)`, `delete(…)` | The operations of the model resolved from the sibling `definitionKey(…)` / `serviceKey(…)`, including a variant that inserts `.value(…)` placeholders for every input |
| `value(…)`, `originalValue(…)` | That operation's input parameter names |
| Vocabulary positions | Messages, signals, variables, user-task ids, activity and plan-item ids, form outcomes — scoped to the process or case the call site names |
| Member positions | DMN decision variables, event payload names, master-data field names |
| Model XML | Cross-reference attribute values, extension-element text, and event payload names from the sibling `eventType` |
| Liquibase changelogs | Column names and table names from the backing service, plus the matching Liquibase column type ranked first |
| Expressions | Root objects, namespaces, functions, `flw.*` members (including nested), your own custom functions, variables and form fields, and — after `bean.` — that Java class's methods and getter-derived properties |
| Script bodies | The root objects the selected script context binds, and their members **with parameter signatures** |

The catalog covers `org.flowable.*` and `com.flowable.*` across process, case, decision, form, event,
channel, data object, master data, service registry, action, agent, knowledge base, template, security
policy, page, query, variable extractor, sequence, SLA, dashboard component and data dictionary APIs,
plus the unified work-definition queries. Receiver matching walks subinterfaces, so one catalog entry
covers every service that extends it.

## Inlay hints

*Settings → Editor → Inlay Hints → Values.* Both on by default.

- **Data object table names** — the backing table beside an otherwise opaque data-object key.
- **Action names** — the action's display name beside its key.

## Intentions

*Alt-Enter*, category **Flowable**:

- **Generate Java DTO for this Flowable data object** — on a data-object key.
- **Open in Expression Playground** — on any injected expression, pre-filled with its dialect and its
  model's scope.

## Settings

### Settings → Tools → Flowable Atlas

Applies to every project.

| Option | Default |
|---|---|
| List extra completion domains at an empty prefix | on |
| Inline hint: data object table names | on |
| Inline hint: action names | on |
| Recognize model keys anywhere in code | **off** |
| Also index raw Flowable Design workspace sources | **off** |

### → Expressions

| Option | Default |
|---|---|
| Validate expression syntax | on |
| Treat `${…}` / `#{…}` in Java string literals as Flowable expressions | **off** |
| Project allowlist — a table of entries typed *Namespace*, *Function* or *Grounding root* | empty |
| Discover project custom functions | on |
| Customisation source (file or folder) | empty = auto-discover |

The allowlist is the same store the Alt-Enter quick fix writes to. See
[the allowlist](../../expressions/#the-allowlist).

### → Generation

Which artifacts *Generate Atlas Explorer…* produces and where, plus the folder a Design pull writes
into. The three generators with shapes of their own are child pages — on one page they were four
screens of fields with no hierarchy. Every folder field on these pages is project-relative, and its
browse button writes the chosen folder relative to the active Flowable project (an absolute path only
for a folder outside it). Unticking every artifact is not a way to generate nothing: the selection
falls back to the explorer HTML.


| Option | Default |
|---|---|
| Atlas output folder | `atlas-output` |
| Artifacts: Explorer HTML · Summary · Overview · Graph JSON · CLAUDE.md · Diagrams (SVG) | Explorer HTML only |
| Liquibase output folder | `src/main/resources/liquibase` |

#### → Flowable Design

| Option | Default |
|---|---|
| Pulled models folder | `flowable-models` |

Its own page, not a group under *Generation*: a pull downloads what someone else authored, so it is
the opposite direction of travel from everything on that page. Which environment it pulls from, and
which of its apps, is chosen in the Atlas Hub.

#### → Generation → Model Constants

| Option | Default |
|---|---|
| Model constants class (FQCN) — validated as you type; renaming it leaves the file generated under the old name where it is, no longer kept in sync, and says so | blank → `flowable.FlowableModelKeys` |
| Keep the generated class in sync | on |
| Constant identifier: key / name / name and key | name and key |
| Constant format: class of `String`s, or enum | class |

#### → Generation → Liquibase

| Option | Default |
|---|---|
| Liquibase output folder | `src/main/resources/liquibase` |
| File name pattern (`{key} {name} {service} {servicePrefix} {serviceNo} {table}`) | `{key}` |
| Rename (regex find / replace) | empty |

#### → Generation → Data-Object DTOs

| Option | Default |
|---|---|
| DTO package | `flowable.dto` |
| Class name suffix | `Dto` |
| Class name pattern (`{name} {shortName} {key} {app} {suffix}`) | `{name}{suffix}` |
| Rename (regex find / replace) | empty |
| Sub-package per app | off |

### → Environments

The DEV/QA/UAT/PROD list, **shared by every project in this IDE**. A tree of environments on the left,
the selected node's form on the right; `+` adds an environment or a connection, the copy button clones
an environment with its connections, and the arrows reorder — the list is a pipeline, and alphabetical
would put PROD second.

| Node | Fields |
|---|---|
| Environment | Name · *Ask before pulling from or evaluating against this environment* (**Protected**) |
| Flowable Design | Server URL · authentication (username and password *or* an access token, with a *Create Token…* dialog and a link to Design's own token page) · *Test Connection* |
| Flowable Work | App base URL · *Detect from Project* · username · password · browser session (*Sign in via Browser…*, *Paste Session…*) · *Test Connection* |

An environment holds **at most one connection of each kind, and may hold only one of the two** — a QA
stage with a running app and no Design server is an ordinary thing, shown without any warning. Two
environments **may** point at the same server: one Design server commonly hosts a DEV workspace and a
QA workspace, and they share the one saved credential that URL has, which is right — same server, same
login.

### Where the rest is chosen

There is no page for "which environment this project uses". The Design environment, its workspace and
its apps are picked in the Atlas Hub, beside the models they fetch; the runtime environment is picked in
the Expression Playground, beside the expression it evaluates. That is deliberate: a settings page
holding a second copy of those choices could not be told apart from the Hub's, and the pair drifting was
the whole reason the feature was rebuilt.

The workspace and app selection is stored **per environment** in the committed `.idea` settings — a
workspace key belongs to one server, so one value could be right for at most one environment. Which
environment is selected lives in your workspace file, since connection ids are per IDE.

### Elsewhere

- *Settings → Editor → Color Scheme → **Flowable Expression*** — parentheses levels 1–5, brackets,
  strings, numbers, operators, dot, comma, identifiers.
- *Settings → Editor → Inlay Hints → Values* — the two hints above.
- *Settings → Editor → Inspections → Flowable* — the six inspections above.

### Scopes and monorepos

Every project setting is stored **per Flowable sub-project**. The unscoped fields are the
whole-project scope, so an older flat settings file loads unchanged and upgrading never asks anyone to
reconfigure anything. Which sub-project is active is stored workspace-locally, so one developer's
choice never lands in version control.

## File types

| Extension | Treated as | What Atlas adds |
|---|---|---|
| `.bpmn`, `.bpmn20.xml` | XML | Keys, members, expression and script injection, XML key completion and validation, diagram from `bpmndi` |
| `.cmmn`, `.cmmn.xml` | XML | The same, plus CMMN script fields and criteria; diagram from `cmmndi` |
| `.dmn`, `.dmn.xml` | XML | Decision variables for `variable(…)` completion; diagram from `dmndi`, else the decision table is painted |
| `.form` | JSON | Frontend and backend expression injection; fields and outcomes feed completion |
| `.action` | JSON | Script injection into the bot script; `botKey` ↔ `BotService` linking; action-name inlay hint |
| `.data` | JSON | Field mappings drive DTO generation, `value(…)` validation, Liquibase synthesis and table hints |
| `.service` | JSON | Operations drive cascade completion; columns and table drive the Liquibase inspection |
| `.masterdata` | JSON | Field names for master-data query completion |
| `.event`, `.channel` | JSON | Payload names for event parameter completion |
| `.page`, `.dictionary`, `.query`, `.sequence`, `.sla`, `.agent`, `.tpl`, `.policy`, `.extractor`, `.knowledgebase`, `.dashboardcomponent`, `.document`, `.palette`, `.app` | JSON | Indexed by key, completed, navigable, hoverable, searchable — and they open with their content instead of a "file type not associated" panel |
| `.bar`, `.zip` | Archive | Entries are indexed, navigable, searchable by path and content, and can render diagrams — without unpacking |
| Liquibase changelog XML | XML | Column, table and type completion; the coverage inspection |
| Design workspace `*-models/*.json` | JSON | Indexed **only** with *Also index raw Flowable Design workspace sources* enabled |
| `*.explorer.html` | — | Opens as the Atlas Explorer tab, plus a Flowable Expressions tab |

## Notifications

One group, **Flowable Atlas**, used for generation results, model-index rebuilds, Design pulls, rename
warnings, and the code generators. Generation failures offer *Show details* or *Open log*.

## Reporting a problem

An exception raised by Atlas shows up in the IDE's error dialog with a **Report Flowable Atlas
Problem…** button. It assembles the report — the stack trace, the plugin and IDE versions, whether the
running IDE is inside the verified range — copies it to your clipboard and opens the
[issue tracker](https://github.com/kle-dev/flw-atlas/issues/new) in your browser. Nothing is transmitted
by the plugin itself: review the text before pasting, because a stack trace can carry model keys, file
paths and expression text from your project.
