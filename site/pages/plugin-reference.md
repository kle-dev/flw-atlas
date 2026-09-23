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

All actions live under **Tools → Flowable Atlas**, and are also reachable through *Find Action*. The
Hub is the plugin's visible surface, so there are no main-toolbar entries and only one shortcut:
*Go to Model…* and *Find in Models…* are the same search with two endings, named after the platform's
own pair: *Go to File* takes you to one place and closes, *Find in Files* leaves a list. `⇧⏎` in the
popup switches from the first to the second without retyping.

**Go to Model…** is `Ctrl+Alt+Shift+M` (`⌥⇧⌘M` on macOS), because it is the one action that competes
with Shift-Shift for the hand; rebind it under *Settings → Keymap → Plugins → Flowable Atlas*. A
right-click on a **folder, a model file, an archive or a `.json`** in the Project view offers *Generate
Atlas Explorer…*, *Go to Model…* and *Compare Model with Archive* — the actions that are about the thing
under the cursor — and nothing on any other file. The `.json` is in that list for the comparison: a model
generated into the project folder is a `.json` that counts as a model only inside a Design `*-models/`
folder, which is precisely the file the comparison is wanted on.

| Action | Also in |
|---|---|
| Atlas Hub | — |
| Atlas Findings | — |
| Open Atlas Explorer | Atlas Hub toolbar and its *Explorer* block |
| Open Expression Playground | Atlas Hub toolbar and its *Playground* block |
| Go to Model… | Atlas Hub toolbar and the model count in its header; Project view context menu; `Ctrl+Alt+Shift+M`. Under Remote Development it opens the *Find in Models…* list, because Search Everywhere has no Flowable tab there |
| Find in Models… | The *Go to Model…* popup, with `⇧⏎` on any row |
| Copy Model Key | Editor context menu, on a key — a literal or constant at a Flowable API site in Java, a cross-reference or the file's own key in a model file; the Atlas Hub's *Recent Models* context menu |
| Compare Model with Archive | Project view context menu, on a model file or a `.json`; editor context menu, which is how an entry inside a `.bar`/`.zip` is reached. Its text names the direction it is about to take — *Compare with Model in Archive* on a file in the project, *Compare with Model in Project* on an entry in an archive |
| Generate → Generate Atlas Explorer… | Atlas Hub, *Explorer* block; Project view context menu |
| Generate → Regenerate Atlas Explorer | Atlas Hub attention line, when models changed since the last generation; the explorer tab's banner and toolbar; the balloon after a Design pull |
| Generate → Generate Model Constants… | Atlas Hub ⋮ menu |
| Generate → Liquibase → From Data Object… | — |
| Generate → Liquibase → From App(s)… | — |
| Generate → Data-Object DTOs → From Data Object… | — |
| Generate → Data-Object DTOs → From App(s)… | — |
| Switch Design Environment… | Atlas Hub, *Design Pull* block |
| Pull from Flowable Design | Atlas Hub, *Design Pull* block — the button names the environment |
| Switch Work Environment… | Atlas Hub, *Playground* block; the playground itself |
| Manage Environments… | Atlas Hub ⋮ menu, its attention line when an environment was removed, and the *Design Pull* block while none exists |
| Rebuild Model Index | Atlas Hub ⋮ menu, and beside the model count in its header |
| Dump Key Index (Internal) | Only visible in an internal-mode IDE |

Panel toolbars carry a few more that are not registered actions, so they do not appear in *Find
Action*: the Hub's *Refresh* (which also re-reads the Flowable Design workspace and app lists) and *Settings*; the Environments page's *Test Connection*; the explorer tab's *Back*, *Forward*, *Reload* and *Open in Browser*; the
Expression Playground's dialect toggles, scope selector, *Evaluate Against App* (Ctrl+Enter), *Show
Sub-Expression Values* and its gear menu with *Stack Panels*, *Expression Settings…* and *Environment
Settings…*; and the Script Playground's language and context selectors with *Load Script from Model…*,
*Load Example…* and the same gear.

## Tool windows

| Tool window | Where | Contents |
|---|---|---|
| **Atlas Hub** | Right stripe | A status header — Flowable project · model count (a link to *Go to Model…*, *Rebuild* beside it) · index age · one attention line when something needs a hand — over four blocks: Explorer · Recent Models (the models opened last, newest first; double-click opens at the key, the context menu copies the key, opens the explorer page, or removes one entry or all) · Design Pull (environment · workspace · apps · pull) · Playground. See [the Hub](../#the-atlas-hub) |
| **Atlas Findings** | Bottom stripe | Every finding of the project from the same in-process analysis the explorer is built from, as a tree: *Defects*, then — when asked for — *Advice* and *Accepted*, each by check. Double-click or Enter opens the finding's file at its line; *Accept…* asks for a reason and writes one rule per selected finding to the output folder's `waivers.json`, the file the explorer's Save writes, then analyses again. Swing, so it works under Remote Development and without JCEF |
| **Expression Playground** | Bottom stripe (secondary) | Two tabs on one shell — editor and problems · context · result: *Expressions* (Backend / Frontend) and *Scripts*. See [the playgrounds](../#the-playgrounds) |

The generated explorer opens as an editor tab, **Atlas Explorer** (the page, in the embedded browser);
its toolbar opens the Expression Playground. Inside the IDE the page can also write back: when
you [accept a finding](../../explorer/#accepting-a-finding), **Save to waivers.json** writes it beside the
report through the IDE's own file system, so it shows up in the Git tool window like any other edit, and
then regenerates the explorer so the counts, badges and the CI gate follow the decision. A balloon names
the file and opens it. Every generation from the IDE reads that `waivers.json` back, exactly as the CLI
does, and prefills the `by` of a new rule with the project's git identity (`git config user.name`). In a
plain browser the same button offers the file as a download instead.

## Inspections

*Settings → Editor → Inspections → **Flowable***.

| Inspection | Where | Default | Flags |
|---|---|---|---|
| Unknown Flowable model key | Java | on, warning | A key literal — or a constant reference — at a Flowable API call site that matches no indexed key of that site's type. Quick fixes: replace the literal with the nearest real key, or change the constant's value to it — in the constants class, with a preview saying so |
| Unknown Flowable model key (model XML) | BPMN / CMMN / DMN | on, warning | The same, for cross-reference attributes and extension-element text, with the quick fix on both. Values containing `${` or `#{` are skipped In a monorepo the message names the sub-project whose index the key is unknown in |
| Invalid Flowable data-object value field | Java | on, warning | A `value("field", …)` that is not an input parameter of the operation named earlier in the same fluent chain. Quick fix to the closest valid field |
| Liquibase column not defined in Flowable model | XML | on, warning | A changelog column that maps to no field of the backing `.service` model; the message names that service Quick fix: open the backing service model at its key; in a monorepo the message names the sub-project whose index was consulted |
| Unknown Flowable expression function or namespace | Expressions | on, warning | An unknown namespace, function or `flw.*` member, and dialect misuse. Quick fixes: replace with the nearest name, or add it to the project allowlist |
| Expression root is not a known variable, bean, or root object | Expressions | **off**, weak warning | A backend root identifier that is not a catalogued engine root, an indexed variable, or a name used elsewhere in the project |

Every inspection carries a description in *Settings → Editor → Inspections → Flowable*, and every fix a
family name that says what it does (*Replace with a known model key*), so *Fix all* groups fixes by kind, not by
the value they insert.

Two things are validated outside the inspection system. **Expression syntax** is painted by an
annotator, because the daemon's inspections do not run inside the playground's editor field. And Atlas
**suppresses** the unresolved-symbol inspections of Groovy and JavaScript inside Flowable script bodies
(`GrUnresolvedAccess`, `JSUnresolvedReference` and their siblings), since the bindings come from the
engine rather than from the file.

## Gutter icons

Four, each with a mark of its own so the gutter says which relationship it is — three on Java code, the
fourth on Java code and inside model files:

| Icon | On | Goes to |
|---|---|---|
| a link | A class or method referenced by a model | The models that reference it ("Flowable Models"), each opened at the reference itself — the `${bean…}` in a deployment XML, not its first line |
| a bot | A `BotService` implementation | The `.action` models that use that bot, each opened at its `botKey` |
| a globe | A Spring REST handler | The models that call that endpoint, each opened at the calling URL |
| a route | A model-key literal or resolvable constant in Java; inside a model file, the file's own key and every reference to a process, case or decision (`calledElement`, `caseRef`, `processRef`, a form's `processReference`, …) | That model's diagram, in the Images viewer — shown only when a diagram actually exists |

The tooltip says what the mark knows: how many actions use the bot, which verbs and paths the models call,
which model's diagram opens (*Process diagram: DEMO-P001*). When several models sit behind a mark, a chooser
lists each with its type's icon, its key and its file — `app.zip → processes/x.bpmn` for a packed model — and
typing filters by key or name.

## Navigation

| Gesture | From | To |
|---|---|---|
| Ctrl/⌘-click | A key literal or key-argument constant at a Flowable API site | The key's declaration in the model file(s) declaring it — the `id` of the process, the `"key"` of the form — narrowed to that site's types |
| Ctrl/⌘-click | `operation("…")` / `value("…", …)` | The backing `.service` model, at its key |
| Ctrl/⌘-click | A cross-reference in model XML — an attribute (`calledElement`, `formKey`, `caseRef`, …) or an extension element's text (`eventType`, `channelKey`, `sla-definition-key`, …), CDATA-wrapped or not | The referenced model, at its key |
| Ctrl/⌘-click | A model key in a JSON model — a data object's backing service or dictionary, a form component's subform, data object, service, action, process or case, a document's forms, an action's form, an app's models, an agent's tools, a channel's event | The referenced model, at its key. The sites are the ones the report draws edges for, from one shared catalog |
| Ctrl/⌘-click | The root of a backend expression that names a Spring bean — `orderService` in `${orderService.process(x)}`, injected in a model or in the playground | The project class the bean name denotes (Spring's default: the decapitalised simple class name); Ctrl-Q then shows that class's documentation. A root that is a variable resolves to nothing and stays unmarked |
| Ctrl/⌘-click | The key of `environment.getProperty('…')` / `propertyConfigurationService.getProperty("…")` in a backend expression | The line of every `application*.properties` / `application*.yml` of the project that sets it, one per profile, matched in Spring's relaxed form. Test profiles are left out; a property set nowhere resolves to nothing |
| Click | An element of a process, case or decision in the model editor's picture | The element's declaration in the text beside it, so the Structure tool window follows. Ctrl/⌘ + wheel zooms about the pointer, a drag pans, Ctrl/⌘ + `=`, `-`, `0` zoom in, out and fit |
| Ctrl/⌘-click | Any literal whose value is a known key | Its model, at its key — **only** with *Recognize model keys anywhere in code* enabled |
| Ctrl-Q / F1 | A key literal in Java; a cross-reference or the file's own key inside a model file | A documentation card: key and type, the name, the backing table for a service or data object, the project-relative file (archive → entry for a packed model) |
| Find Usages | A model's own key, in its file — the `id` of a process, case or decision, the `"key"` of a JSON model | Every model that references it (a call activity's `calledElement`, a `formKey`, a service mapping, an extension element's text) and every Java call site that names it at a Flowable API position |
| Find Usages | A Java method, field or class | Every model that references it by name, inside `${…}` / `#{…}` or a `class` / `delegateExpression` / `expression` attribute |
| Find Usages | A bot class | The `.action` models whose `botKey` matches |
| Find Usages | A Spring REST handler | The models whose HTTP task, REST button, service operation or REST data source calls that URL |
| Search Everywhere / Go to Symbol | — | Every model key, plus bot keys — from actions and from `BotService` implementations — plus every named element inside a model: user tasks, activities, variables, messages, signals, an event's payload fields, a form's fields and outcomes, each opening its model at the declaration and labelled *User task · in DEMO-P001*. A model row carries its type's icon, a bot row the bot icon, and opens the file at the key's declaration |
| Search Everywhere → **Flowable Model** tab | — | Model keys, archive-qualified paths inside `.bar` / `.zip`, the elements inside the models (ranked under the models, shown with their model in grey), and a live full-text search over model content showing the matched line. A model row carries its type's icon, a text hit a magnifier; the typed fragment is highlighted even mid-key, and the row's tooltip holds type, name and archive path. `⇧⏎` hands the whole result set to the Find tool window as a list that stays open |

Two more behaviours belong here even though they are not navigation. Renaming a Java symbol that models
reference raises a warning with *Show affected models*, because the refactoring engine cannot rewrite an
expression string. And anything models reference is reported as **implicitly used**, so the IDE stops
offering to delete code a process depends on.

## Code completion

Every item carries an icon: a model key its type's icon (the explorer's glyph in the explorer's colour), an
operation the method icon, an input value the parameter icon, a variable, message or outcome the platform's
variable, constant and property icons, a Liquibase column or table the database icons.

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

*Settings → Editor → Inlay Hints → Values.* All on by default, and that page is the only switch.

- **Data object table names** — the backing table beside an otherwise opaque data-object key, in Java.
- **Action names** — the action's display name beside its key, in Java.
- **Model names** — inside a BPMN, CMMN or JSON model, the referenced model's name beside the key that
  names it: after `calledElement`, `formKey`, an `eventType`'s text, a data object's backing service, a
  form component's subform. Listed once per language (XML, JSON).

## Intentions

*Alt-Enter*, category **Flowable**:

- **Generate Java DTO for this Flowable data object** — on a data-object key.
- **Open in Expression Playground** — on any injected expression, pre-filled with its dialect, its
  model's scope and the instance kind the model implies (process for BPMN, case for CMMN).
- **Open in Atlas Explorer** — on a model key in Java (a literal or a constant at a Flowable API site;
  any literal equal to a known key with *Recognize model keys anywhere in code*), and inside a model file
  on a cross-reference or on the file's own key: opens the newest generated explorer inside the IDE on
  that model's page — who references it, what it uses, its findings, its diagram. Offers to generate an
  explorer when there is none.

## Settings

### Settings → Tools → Flowable Atlas

Applies to every project.

| Option | Default |
|---|---|
| Also index raw Flowable Design workspace sources | **off** |
| Recognize model keys anywhere in code | **off** |
| List extra completion domains at an empty prefix | on |

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

What Atlas writes into the project, and where: which artifacts *Generate Atlas Explorer…* produces, and
the folder a Design pull lands in. The three generators with shapes of their own are child pages — on one
page they were four screens of fields with no hierarchy. Every folder field on these pages is project-relative, and its
browse button writes the chosen folder relative to the active Flowable project (an absolute path only
for a folder outside it). Unticking every artifact is not a way to generate nothing: the selection
falls back to the explorer HTML. Generating into the output folder also drops a `.gitignore` there that keeps
everything except `waivers.json` out of the repository — the same file the CLI writes.


| Option | Default |
|---|---|
| Atlas output folder | `atlas-output` |
| Artifacts: Explorer HTML · Summary · Overview · Graph JSON · CLAUDE.md · Diagrams (SVG) | Explorer HTML only |
| Pulled models folder — where *Pull from Flowable Design* writes the app archives; which environment and which apps is chosen in the Atlas Hub | `flowable-models` |

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
- *Settings → Editor → Inlay Hints → Values* — the hints above.
- *Settings → Editor → Inspections → Flowable* — the six inspections above.

### Scopes and monorepos

Every project setting is stored **per Flowable sub-project**. The unscoped fields are the
whole-project scope, so an older flat settings file loads unchanged and upgrading never asks anyone to
reconfigure anything. Which sub-project is active is stored workspace-locally, so one developer's
choice never lands in version control.

## File types

Every model extension below shows its type's icon in the Project view, the editor tabs and every file
list — the same glyph in the same colour as its explorer page, light and dark — and a `.bar` shows an
archive icon. The icon is decided from the file name alone, so the Project view never waits on it.

| Extension | Treated as | What Atlas adds |
|---|---|---|
| `.bpmn`, `.bpmn20.xml` | XML | Keys, members, expression and script injection, XML key completion and validation, diagram from `bpmndi`, and schema-backed element and attribute completion and validation |
| `.cmmn`, `.cmmn.xml` | XML | The same, plus CMMN script fields and criteria; diagram from `cmmndi`; schema-backed as above |
| `.dmn`, `.dmn.xml` | XML | Decision variables for `variable(…)` completion; diagram from `dmndi`, else the decision table is painted; schema-backed at DMN 1.1, 1.2 and 1.3 |
| `.form` | JSON | Frontend and backend expression injection; fields and outcomes feed completion |
| `.action` | JSON | Script injection into the bot script; `botKey` ↔ `BotService` linking; action-name inlay hint |
| `.data` | JSON | Field mappings drive DTO generation, `value(…)` validation, Liquibase synthesis and table hints |
| `.service` | JSON | Operations drive cascade completion; columns and table drive the Liquibase inspection |
| `.masterdata` | JSON | Field names for master-data query completion |
| `.event`, `.channel` | JSON | Payload names for event parameter completion |
| `.page`, `.dictionary`, `.query`, `.sequence`, `.sla`, `.agent`, `.tpl`, `.policy`, `.extractor`, `.knowledgebase`, `.dashboardcomponent`, `.document`, `.palette`, `.app` | JSON | Indexed by key, completed, navigable, hoverable, searchable — and they open with their content instead of a "file type not associated" panel |
| `.bar`, `.zip` | Archive | Entries are indexed, navigable, searchable by path and content, and can render diagrams — without unpacking |
| Liquibase changelog XML | XML | Column, table and type completion; the coverage inspection |
| Design workspace `*-models/*.json` | JSON | Inside a `.bar` / `.zip`, the forms, pages, actions and data objects are always models. Loose in the repository, indexed **only** with *Also index raw Flowable Design workspace sources* enabled |
| `*.explorer.html` | — | Opens as the Atlas Explorer tab; its toolbar opens the Expression Playground |

## Notifications

Two groups, so either can be silenced in *Settings → Appearance & Behavior → Notifications* without the
other. **Flowable Atlas Results** says a job finished: the model index was rebuilt, artifacts, DTOs or
changelogs were generated, waivers were saved. **Flowable Atlas** carries what asks for attention: a
failed pull or generation (with *Show details* or *Open log*), a rename not applied to the models, an
archive that could not be read.

## Reporting a problem

An exception raised by Atlas shows up in the IDE's error dialog with a **Report Flowable Atlas
Problem…** button. It assembles the report — the stack trace, the plugin and IDE versions, whether the
running IDE is inside the verified range — copies it to your clipboard and opens the
[issue tracker](https://github.com/kle-dev/flw-atlas/issues/new) in your browser. Nothing is transmitted
by the plugin itself: review the text before pasting, because a stack trace can carry model keys, file
paths and expression text from your project.
