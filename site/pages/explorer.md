# The Atlas explorer

`<project>.explorer.html` is one self-contained file — the stylesheet, the script, the font and the
whole graph, inlined, with no external requests. Double-click it and it works: on a machine with no
network, from a shared drive, out of an email attachment. Inside IntelliJ the same file opens as an
editor tab, following the IDE theme.

<figure class="fig">
  <div class="body">
    <iframe loading="lazy" title="The Atlas explorer, overview page"
            src="../demo/explorer.html#/overview"></iframe>
  </div>
  <figcaption><b>The live explorer</b>, generated from the demo project by the same command you would
  run. Click into it — this is the real page, not a picture of one.
  <a href="../demo/explorer.html" target="_blank" rel="noopener">Open it full-screen ↗</a></figcaption>
</figure>

## Views

The URL hash is the single source of truth for navigation, so browser back and forward work — and so do
the page's own ‹ › buttons in the top bar and `Alt+←` / `Alt+→`, which is what a JCEF tab inside the IDE
needs, having no browser chrome — and any
view you are looking at can be copied as a link.

| Hash | View |
|---|---|
| *(empty)* or `#/overview` | The dashboard: a health summary (the open defects and advice, each split by tier — broken, runtime risk, unfinished, noise — and the checks with the most to say, every part a way into the Checks page), hotspots (the project's own most-referenced models and code — platform beans, URLs and security policies aside), the inventory grouped as the sidebar groups it, apps and entry points |
| `#/tree` | The reference tree: what each app starts, and what those models reach |
| `#/checks` | Every [finding](../checks/) in one place, in two groups — **Defects** (wrong now) and **Advice** (a pattern worth a look): a block per check with its rows under one line saying what it means (*why · what to do* opens beneath it), the accept controls, and what the project already accepted — with its reasons |
| `#/variables` | The [unused-variable](../variables/) report and what Atlas could not judge |
| `#/scripts` | Every script body in the project |
| `#/schema` | Schema gaps: Liquibase → service → data object, per column |
| `#/erd` | The [ER diagram designer](#er-diagram-designer) — only in a page generated with it: the project's tables on a canvas, with the relations you draw between them |
| `#/browse/<category>` | A category — one per node type, Java role, variable scope and review list — as a sortable table: name, key, file, references in and out, open findings, and a column or two the type is worth more with (the bot an action runs and the actions that reach a bot, a service's data objects and a data object's service, the Liquibase changelog behind either, the app a model belongs to, a service's table, a review list's finding). Opening a row brings the list back beside the node |
| `#<nodeId>` | Browse with that node selected. This is the permalink form |
| `#<nodeId>&q=<term>` | …with the search term that led there highlighted |
| `#<nodeId>&e=<elementId>` | …with a specific model element opened — and selected on the diagram |
| `…&f=<filter>&s=<sort>` | On a node or a category route: the list's filter text and sort order (`name`, `key`, `file`, `refs`, `out`, `findings`; a leading `-` reverses it). Written by the page as you type or pick (no history entry), so a reload or a copied link brings the list back as you left it |
| `#/checks&f=…&c=error&a=1`, `#/tree&l=all&f=…`, `#/scripts&f=…&c=…`, `#/variables&f=…&c=…` | On a report route: the filter text (`f`), the active chip (`c` — a tone (`error`, `warning`, `advice`), a script group, a write construct), *show accepted* (`a`) and, on the tree, the lens (`l`). Written the same way, so a report can be reloaded or sent to a colleague exactly as it was left |

An unknown route or an unresolvable node id — a model renamed since the link was copied, a report
generated from a smaller scope — falls back to the overview, says so in a toast, and replaces the dead
hash in the address bar with the overview's.

## The reference tree

`#/tree` answers the question the single-hop views cannot: *what does this app actually start, and what
does that reach?* It has three levels of meaning:

1. **the app**, as a grouping header — plus **Outside any app**, which is a finding in itself
2. **its functional roots** — a model nothing points at except its app
3. **everything those reach**, recursively, over every relation except app membership

App membership deliberately is not the spine. `contains` is app → model and exactly one level deep, so
using it to nest would put every form of an app at depth 1 and make the `process → form` edge that
explains why the form exists arrive later, as a back-reference.

A node is expanded **once**, at the shallowest place it is reached; every later arrival is a leaf marked
*shown above* that jumps to it. That keeps the tree bounded — rows are roots plus traversed edges — where
rebuilding a shared subtree per path is exponential. An edge back into the path you are on is marked
*cycle* and not followed.

The filter takes the same [search grammar](#search) as ⌘K, and a row survives it if it matches **or** a
descendant does, so the path to a hit is never hidden. The **models** lens shows Design models only;
**everything** adds the expressions, variables and Java the graph also holds.

## Browse categories

The sidebar is generated from the graph, so it only ever shows categories this project actually has,
grouped into **Models · Integration · Code · Expressions · Checks · Variables · Access · Other**. A
category opens as a **table** of its nodes, sorted by any column header (a second click reverses it) and
filtered with the same engine as the search; rows are marked, walked and opened exactly like the list —
a click opens, `⌘/Ctrl`-click or `⇧`-click marks, a middle-click opens a background tab. The list column
steps aside while the table shows the same rows, and comes back beside the node a row opens, with the
filter and the sort.

The **Actions** table names the bot each action runs: a Java bot by its class, a bot the platform ships by
its key. The name is a link to the bot, with a copy button for its class name or key, and inside the IDE a
button that opens the Java bot's source. The **Bots** and **Java · bot** tables answer the other way round:
an *Actions* column links each bot to the actions that reach it. The same goes for a service and the data
objects stored through it: the **Services** table names them in *Data objects*, the **Data objects** table
names the *Service*, and both name the *Liquibase* changelog that creates the table — for a data object
through its service when it has no schema link of its own, the live definition before a superseded
revision. A link in a row opens its own target, not the row; `⌘/Ctrl`-click or a middle-click opens it in a
background tab. The categories are:

- one per **node type** present — process, case, decision, form, page, data object, service, agent,
  channel, event, action, bot, query, template, sequence, security policy, endpoint, method, Liquibase
  changelog, signal, message, error, escalation, topic, group, Spring property, user definition,
  tenant setup, and more;
- one per **Java role** found — controller, delegate, listener, service, repository, configuration,
  component, bot;
- one per **variable scope**, plus the variables that travel through an in/out mapping;
- **external** buckets — API, route, missing, library;
- and the **review lists**: unused forms, unused service operations, unused custom functions, invalid
  expressions, suspect expressions, changelog issues, guessed variables, unused variables, unread inputs,
  script syntax.

Every category carries a small icon for its type in the type's colour, and so does every node wherever it
appears — the browse list, the chips, the detail tabs, the breadcrumb, the search results. A process, a
form and a service tell apart at a glance in a long list, and in the collapsed sidebar rail the icons are
the navigation. The icons scale with the text (see [Themes and text size](#themes-and-text-size)).

The sidebar's width is yours: drag its edge, or focus the edge and press `←` / `→`. In a window of 1100px
or less — an editor tab inside the IDE — it starts **compact**: labelled, 184px wide, with denser rows,
and a label cut short shows in full on hover. Dragging the edge below 140px, or pressing `←` at the
narrowest width, collapses it to the icon rail, which flies out on hover; a double-click on the edge, or
`Home`, goes back to the automatic layout. At 800px or less with a mouse — an editor tab between two tool
windows — the rail is the automatic layout, so the navigation stays on the left at any width.

On a large screen the pages take the room: the overview, the report pages and a node's page grow up to
2000px wide, so tables, the health list and the diagram use the window rather than stopping at an IDE
tab's width. Descriptions and other prose keep a readable line length.

Each group folds — click its header, or press `←` on any of its entries — and the fold is remembered, so
a project with forty variable scopes need not show them on every visit. A folded group whose entry is
the one you are on says so with a dot on its header, and stays folded: the sidebar never reopens itself
behind your back. On a touch screen below 800px the page stacks instead: the list gives way to a picker
beside the search button, one row instead of fourteen rows of chips.

## The detail panel

Selecting a node opens its page: a header that says what the node is and whether it is fine, and under
it the page's **tabs**, one per question a reader brings. Every kind of node has the same four, and a tab
with nothing to show is left out:

- **Overview** — what the model is: the description the modeller wrote in Design (and a process's or
  case's documentation) as prose, the facts, and the picture — the drawing, or the table that *is* the
  model: a process's diagram, a form's layout, a decision table, a service's operations, an event's
  payload, a data object's properties.
- **Findings** — what Atlas reports on it, check by check, each row with its *accept…*.
- **Connections** — whether it fits what it meets, a section per question ([below](#does-it-fit)), and
  its **Relations**.
- **Details** — what it is made of: elements, fields, parameters, variables and expressions, the tests
  that deploy it, and *Other attributes*.

The header is the type's icon in a tinted tile, the title and one identity line (kind · key · file path,
each copyable, the path opening the file inside IntelliJ). The **health strip** under it answers "is this
one fine?" whichever tab is open: the open defects and advice, the gaps the Connections tables found (or
*✓ fits*, or *? unclear* when all Atlas can say is that it cannot tell), how many models it *uses* and is
*used by*, the apps that ship it — or *in no app* — and the tests that deploy it. Every item brings up the
tab and the section that explains it. The **facts** are the handful of properties that describe the model
itself: a data object's backing service and table, a form's outcomes and the tasks that open it, an
operation's call as one line and the endpoint that answers it, a sequence's first numbers. Counts are not
facts: a section carries its own count in its heading, and a tab in its label — Findings the open ones,
Connections the neighbours, with a dot when one of its tables found a gap.

The tab bar stays in reach while the page scrolls, with the page's two actions at its end: *expand all*,
for the tab on screen, and *copy link*. The tab is part of the link — `&p=findings`, `&p=connections`,
`&p=details` — and it carries over as you walk the graph: a reader going through callers on Connections
stays on Connections on the next page that has the tab, and Back returns to the tab the page was left on.
`1`–`4` pick a tab. A section is a heading with its count; what it shows is explained behind its ⓘ.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/detail-form.png" alt="A form's detail page: the header with icon tile, title, identity line and health strip, the tabs Overview, Connections and Details, and on Overview the facts and the form's layout — the wireframe of its grid" width="1400" height="1000"><img class="only-dark" src="../assets/img/detail-form-dark.png" alt="A form's detail page: the header with icon tile, title, identity line and health strip, the tabs Overview, Connections and Details, and on Overview the facts and the form's layout — the wireframe of its grid" width="1400" height="1000"></div>
  <figcaption><b>A form's page</b> — the health strip under the title, the tabs, and on Overview the facts
  and the form's layout: the wireframe the IDE's model preview draws, clickable like a diagram.
  <a href="../demo/explorer.html#form%3AorderForm" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

### Does it fit?

The question a model file cannot answer on its own is whether it fits what it meets — the callers that
hand it values, the models it calls, the code that answers it, the app that ships it. The Connections
tab answers it with one kind of table, a section per question — *Calls*, *Called by*, *Fields and the
variables they write* — the schema coverage table first: a row per thing that should line up, tinted by
how badly it does not, a pill per kind of gap in the section's heading, and *only gaps* to hide the rows
that are fine. A cell says ✓ it fits, a faint ✓ it fits by name (a decision reads its inputs by name, a button that
sends the whole form hands over every field), ✗ it is missing, ⚠ it looks wrong, or ? Atlas cannot tell —
and then why, in its tooltip: a callee outside the project, a call with no explicit mappings (Atlas
cannot see `variables="all"` or a Java start), a name only a script guesses at, a name Java writes, a
list longer than Atlas records. Every mark says what it means in its tooltip, ahead of that reason, and
every question's ⓘ carries the legend. The reasons are the silence rules of the
[unused-variable check](../variables/), so a table never contradicts a finding.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/detail-fit.png" alt="An operation's page: its parameter customerId is required, and the one task that calls it passes nothing — the Does it fit? table marks the row red with a cross, and the health strip counts one gap" width="1400" height="900"><img class="only-dark" src="../assets/img/detail-fit-dark.png" alt="An operation's page: its parameter customerId is required, and the one task that calls it passes nothing — the Does it fit? table marks the row red with a cross, and the health strip counts one gap" width="1400" height="900"></div>
  <figcaption><b>Does it fit?</b> — an operation against its callers: the task passes nothing for a
  required parameter, so the row is a gap, the pill names its kind, and the health strip counts it.
  <a href="../demo/explorer.html#serviceOperation%3AcustomerService%23findById&amp;p=connections" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

What each page asks:

- **a process or case** — every call it makes (sub-process, case, decision, operation, agent, form,
  event) with what it hands over and takes back, and every caller of it against what it reads: a value
  it reads that no caller passes, a value passed in that it never reads, a value mapped back that it never
  writes. A process nothing calls lists the values whoever starts it has to provide;
- **a service and its operations** — per operation, who calls it and whether their mappings fit its
  parameters (a required one left out is a defect, one the operation does not declare a mistake); which
  endpoint of the project answers it, whether that handler serves the operation's verb and every
  `{path variable}` has a parameter, and the handler method; and, where no changelog lets the schema
  coverage do it, the service's column mappings against the data object's fields;
- **a data object and a Liquibase changelog** — the schema coverage table: every changelog column
  through the service mapping to the data-object field, and where the chain breaks — a mapped column the
  table no longer has names the change set that dropped or renamed it;
- **a decision** — every model that runs it: an input the caller never writes, a result it never reads;
- **a form or page** — every call it makes (buttons, data sources, REST calls with the verb checked
  against the handler — a link, a link button and a data table's row link are where the form goes, not
  calls, and a setting left behind by a data source the component no longer uses is not read), the variables its fields write and who reads them, the data-object paths it binds
  that are no field of the object, and, for each task that shows it, the outcomes no downstream condition
  tests and the tested values that are no outcome;
- **an action** — the buttons that invoke it against what its script reads with `flw.getInput`;
- **an app** — every model its members reach and whether it ships them (in this app, another app, no app,
  not in the project), the models only packed beside it, and every group that may act on its models but
  cannot open it; **a group** — per model, what it may do and the app it gets there through;
- **an event** — every payload field against every element that publishes or consumes it, correlation
  included, and its channels against who uses them; **a channel** — the events it carries without a
  publisher or consumer for its direction; **a signal, message, error or escalation** — who throws it and
  who catches it (an error nobody catches is a gap);
- **an endpoint** — every call that reaches it, with its verb; **a class** — its bean names against the
  expressions that use them, and its methods against the models that call them;
- **an agent** — its tools against the models and operations they name, its callers against its
  operations; **an SLA** — the task it watches in each model it governs; **a query** — the variable behind
  each column against what the queried processes write; **a template** — every variable it prints
  against the models that render it.

### Relations

**Relations** lists both directions, always, for every node type, in one place: **Uses** and **Used
by**, with **a row per relation** — the relation on the left, its neighbours as chips on the right. An
app's five members are one *App contains* row, not five. A chip opens its model, `⌘`/`Ctrl`-click or a
middle-click opens it in a new tab. A row unfolds only where a neighbour has
more to say: the parameters a caller passes in (which is how to check that a button's payload names line
up with what the callee reads), every REST call with its verb and URL. An outgoing mapping's tally and an
agent tool's operation sit beside the chip. A long list gets a filter with chips — *uses →*, *← used by*,
*≈ uncertain* and *with mappings* — that act on the neighbours. An operation is related to its service,
and a variable to the models that write, read or mention it. That is why the graph carries `usedBy`.

The same relations as a drawing are one switch away — *graph*, above the list — and the switch stays on
from page to page until you turn it off. The drawing reads left to right: what the node uses in a column
on the left, what uses it on the right, the node in the middle, the arrows pointing the way each reference
goes; a dashed connector with `≈` or `ƒ` is an uncertain link, the most-referenced neighbours come first,
and *+N more* opens that side of the list.

### Details

A process or a case lists what it is made of in one **Elements** section: a group per kind — user,
service, script and decision tasks, call activities, events, gateways, sequence flows with their
conditions, lanes, multi-instance, declared data objects, listeners and documentation; for a case its
plan model, sentries and event listeners — each group with its own table and columns, a chip per kind
that keeps only that kind, and one filter over all of them. A group remembers whether you left it open,
like a section. **Parameters** lists every in/out mapping, grouped by the element that declares it, and
**Variables & expressions** the variables a model writes and reads — how it writes and reads each one,
which other models share it, the unused-variable verdict — before the expressions, bindings, functions
and literals it uses.

A few kinds of node have a page of their own beyond the model types:

- a **Spring property** a model reads with `environment.getProperty('…')` lists the file and line of
  every `application*.properties` / `application*.yml` that sets it. None is not a finding — the value may
  come from the environment;
- a **user definition** names the forms that create, show and edit such a user and the groups it joins;
  a **tenant setup** the groups it defines and how many users of each definition it creates — never who;
- a **master-data** definition lists its key and name fields and the files that load its rows;
- any model that a test deploys with `@Deployment(resources = …)` lists those tests under *Deployed by
  tests*, each opening at its annotation.

Every list is a **table with column headers** — names and captions in the text face, identifiers,
expressions, paths and code in monospace — and a row with more to say expands in place: a form button
into the model it invokes, the payload it sends and stores, its settings and its expression; a script
task into its code with line numbers and the validator's findings; a service task into its
implementation, the operation it calls and its field injections. A long table gets a filter of its own,
and past a hundred rows it shows the first ones and a *show all* button — the filter still searches
every row. In a narrow panel — an IntelliJ tool window — a table keeps the columns it cannot do without on
the row, drops the others under it, and a matrix cell that drops says whose it is; nothing scrolls
sideways. Text cut to fit — a cell, a chip, a tag, a card's title, a name in the list — says itself in
full when you hover it or tab to it, beside whatever tooltip the element already had.

The identifier in a row copies out of it: a node's key or a Java class's full name in a category table, an
element id, the key of the form, decision or process a row points at, a field id, a property or parameter
name. The copy button shows on the row under the pointer or the keyboard, so a long table is not a column
of icons.

On a form or page, a row in **Fields** expands when the component does something: the model a button
invokes (as a chip you can follow), the payload it sends and stores back, the `{{binding}}` its result is
stored in, a REST button's endpoint with its verb and response path, an expression button's expression and
the interval it re-runs on, whether it fires by itself, and the note the modeller left on it. A plain
input has nothing to add and stays a one-line row. **Hidden**, **disabled** and **not submitted** are
stated on the row itself, because a hidden button that auto-executes is a worker nobody presses; and when
a button is configured to send the whole payload or store the whole response, that is said first and the
mapping it overrides is marked unused, because the runtime never reads it.

Inside IntelliJ the panel also opens code: the `↗` beside a source path and every `:line` on a method
or endpoint open that file in an editor tab (see [the plugin](../plugin/#the-atlas-explorer-inside-the-ide)).
In a browser those affordances are not shown — the page cannot open a file there.

Every section starts open but *Other attributes*, and remembers it when you fold it, per section, across
reloads; a process's Elements groups start as they always did, the tasks open and the rest folded. On a
model's own page a finding says only what the page does not: no model, the line instead of the file, and
no severity column when its check's head already says it. Up to twelve nodes can
be open as **detail tabs**, which are viewports with their own history rather than pins. When the strip
holds more than it can show, its edges fade on the side that hides tabs, the wheel scrolls it sideways,
and a **+N** button lists the tabs out of view — pick one to switch to it. The split between the list and
the panel is yours to move — drag the handle between them, `←`/`→` nudge it, `Home` resets — and it is
remembered, which matters most in a narrow IDE tool window where the list used to take half the width.
In an editor tab the list starts at most 224px wide, and it **folds away**: the button in its head hides
it, as does dragging the handle shut, and the button at the page's top-left corner brings it back. The
fold is remembered too. At 800px or less with a mouse the list is a drawer instead: closed while a page is
open, opened over the page by that button, and closed again by the node you pick from it or by `Escape`.
In a bar too narrow for the whole path, the breadcrumb shows the page's own name alone.

Nothing the parser extracted is invisible: whatever no specific section consumed renders at the bottom
as a collapsed **Other attributes** key/value tree. When a new model attribute starts being parsed, it
shows up there by default — a dedicated section is an upgrade, not a precondition for seeing it. The
same rule holds structurally on the generator side: a parsed field the report would silently drop
fails the build.

The report pages — `#/checks`, `#/scripts`, `#/variables`, `#/schema` — are built from the same parts:
the same header with the page's own numbers as facts, the same sections, the same tables; there a row of
chips, one per section, is the page's map. A script on the Scripts page is the card the process page shows; a finding on the Checks page
is a section the health rows jump to.

## Diagrams

Processes, cases and decisions render their diagram inline, from the layout already in your models —
deployment `bpmndi` / `cmmndi` / `dmndi`, or a Design workspace's ORYX JSON. A form or page renders its
**layout**: a wireframe of its twelve-column grid, panels and tabs, every component with its caption and
id — the same picture the IDE's model preview and the generated diagrams folder show. Nothing is
downloaded and no Design instance is contacted. A page keeps its drawings within a budget, so a project
with hundreds of forms does not double in size; a form left out says so, and the IDE still draws it.

Drag to pan, ⌘/Ctrl-scroll or a trackpad pinch to zoom, `−` `fit` `+` to step, `⤢` for full screen.
The zoom follows how far the wheel turns, so a trackpad's many small steps zoom smoothly, a mouse notch is
one step, and a step with no vertical movement does nothing — inside IntelliJ, whose browser rounds a
trackpad's steps, many arrive as zero. On a touch screen a vertical swipe over an inline diagram scrolls
the page, a sideways drag pans, and two fingers zoom. Clicking an element opens a draggable, resizable
info card — and the `⌖` buttons in the detail panel work the other way round, locating an element on the
diagram from its row in a list, a form's field rows included, from whichever tab they are on: the
drawing comes up on Overview with the element selected, below the tab bar. An element the drawing does
not show — a sentry, an element without layout — keeps a faint `⌖` that says so, and a page without a
drawing offers none. Every shape is a keyboard stop:
Tab through them, Enter or Space opens the card. The selection joins the link (`#<node>&e=<element>`),
so a copied link or a reload lands on the element, on the canvas as well as in its rows; and the
drawing re-fits itself when the panel changes width — a splitter, a narrower IDE tool window — unless
you zoomed by hand. Full screen fits the height too, so a tall case diagram is not cut off at the bottom.
Inline, *fit* never goes below 40 %: a process six thousand pixels wide is shown legible and wider than
the panel — drag to pan, or open it full screen — with a line under it saying so, instead of as a strip
of boxes nobody can read.

A form's components are elements in the same way: clicking one opens its card — what it is bound to,
what it calls, its parameters — and *Show in details* lands on its row in Fields. A decision table has no
canvas, so there is nothing to lay out: its rules render as a real table instead, drawn as Design draws
it — the hit policy in the corner, an Input and an Output band, each column headed by its label,
expression and type, a number per rule.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/scripts-page.png" alt="The script tasks page: every script body in the project, grouped by model, with its language, variables and problems" width="1400" height="900"><img class="only-dark" src="../assets/img/scripts-page-dark.png" alt="The script tasks page: every script body in the project, grouped by model, with its language, variables and problems" width="1400" height="900"></div>
  <figcaption><b>Every script in the project, on one page</b> — grouped by model, with the variables
  each one touches and a badge on the ones with problems.
  <a href="../demo/explorer.html#/scripts" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## ER diagram designer

An optional page for explaining a data model to someone who will never read a changelog: drag the
project's tables onto a canvas, show each with its first columns, draw the relations between them with a
name and a cardinality, colour them, and present the result — or export it for a slide.

It is an **explorer extension**, so it is in a page only when it was asked for: `--extension erd` on the
[CLI](../cli/#options), or *Settings → Tools → Flowable Atlas → Generation → Explorer extensions* in the
[IntelliJ plugin](../plugin/reference/). A page generated without it carries none of its code, has no
sidebar entry, and treats a `#/erd` link like any page it does not have — it opens the overview. In the
IDE's explorer tab it works too, but the tab cannot download: *Export* copies instead (see below).

### Which tables

The tables are the ones Atlas already reads, not a second copy of the schema:

- every table a **Liquibase changelog** creates — XML or formatted SQL — as it stands once every change set
  has run (renames, dropped columns and changed types applied, see
  [how changelogs are read](../checks/#how-changelogs-are-read)), with each column's type exactly as the
  changelog writes it (`VARCHAR(255)`, `DECIMAL(19,2)`, `${varchar.type}(255)`) and a key mark on the
  primary key, which a card lists first.
  When two changelogs define one table, the live one wins over an orphaned one, and both over a
  superseded one (see [`changelogIssues`](../checks/#changelogissues-liquibase-authority));
- every table a **database service** names that no changelog creates. Its columns are the service's
  column mappings and its types the service's logical ones, and the list says *service model* beside it.

A table's default business name is the name of the data object that reads it, when exactly one does.

### Building a diagram

- **Add a table** by dragging it from the list onto the canvas, or with its **+**. A table is on a
  diagram once; dragging it again, or clicking it in the list, finds its card. The filter matches table
  names, business names and column names.
- **A card** shows the business name over the table name, then the first five columns — the primary key
  first, then the changelog's order — each with its type. **+ N more** unfolds the rest and folds them
  again. The width fits every column, shown or not, so unfolding makes a card longer, never wider.
- **Reorder columns** with the grip that appears beside a row, or with ↑ ↓ in the card's panel. The order
  is how you choose which five a folded card shows.
- **The card's panel** — `⋯` on the card, or a double click — sets the business name and the colour
  (eight swatches or any colour), lists every column in order, and links to the changelog, services and
  data objects behind the table.
- **Draw a relation** from the dot on a card's edge to another card (or back to the same one). Its panel
  opens with the name field focused: give it a name, a cardinality — `1:1`, `1:n`, `n:1`, `n:m` — and,
  if you like, the columns it joins; a sentence under the cardinality says what it means (*One Customer
  has many Orders*). Dropping onto a column's row joins that column. The line ends in crow's feet with the
  letters beside them, for whoever has never seen the notation.
- **Proposals.** When both ends of a relation the models already state are on the canvas — a data object
  field that refers to another data object (its one-to-one or one-to-many becomes the cardinality), or a
  service column relation — it shows as a dashed line: click it to take it, `×` to dismiss it for this
  diagram. One proposal per pair of tables, and none once you have drawn one.
- **Keys.** `Delete` removes the selected card or relation, `⌘Z` / `Ctrl+Z` undoes and `⇧⌘Z` /
  `Ctrl+Y` redoes (a name typed in one go is one step), `+` `−` `0` zoom and fit, `Esc` closes a panel.
  Drag the empty canvas to pan, scroll to move, `⌘`/`Ctrl`+scroll or pinch to zoom.

**Present** hides the sidebar, the list and the toolbar, goes full screen where the browser allows it and
fits the diagram to the room; `Esc` comes back.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/erd-page.png" alt="The ER diagram designer: the project's tables in a list on the left, and on the canvas an Order and a Customer table joined by a relation named placed by, n to 1" width="1400" height="820"><img class="only-dark" src="../assets/img/erd-page-dark.png" alt="The ER diagram designer: the project's tables in a list on the left, and on the canvas an Order and a Customer table joined by a relation named placed by, n to 1" width="1400" height="820"></div>
  <figcaption><b>The demo's own diagram</b>, from the <code>docs/orders.atlas-erd.json</code> it keeps —
  the page opens on it without an import. Business names over table names, the primary key first, the
  relation named, with its cardinality.
  <a href="../demo/explorer.html#/erd" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

### Keeping and sharing a diagram

What you draw is kept in the browser, per project: every change is saved as you make it, and a project can
have several diagrams — *Diagram → New, Rename, Duplicate, Delete*. A browser keeps it for one person on
one machine, though, so two more ways out:

- **The diagram file.** *Export → Diagram file* downloads `<name>.atlas-erd.json`; *Diagram → Import*,
  dropping the file onto the canvas or (in the IDE) pasting it opens it again, in any Atlas explorer.
- **Diagrams in the project.** Commit that file anywhere in the project (outside `build/`, `target/`,
  `node_modules/` and the like) and every explorer generated with the designer carries it: the page opens
  on it, listed with *— project* after its name, without an import. Changes you make stay in your browser
  (*changed here*) until you export the file again and commit it; *Revert* drops them.

A diagram refers to tables by name and keeps a snapshot of their columns only as a fallback, so it follows
the schema: a column the changelogs dropped disappears from its card, a new one joins at the end, and a
table this project does not have is drawn from the snapshot, dashed, marked *not in this project's schema*.

The file is plain JSON:

```json
{
  "format": "atlas-erd",
  "version": 1,
  "name": "Orders and customers",
  "project": "flowable-demo",
  "tables": [
    {"table": "ord_order", "alias": "Order", "x": 40, "y": 40, "color": "#e8590c", "expanded": false,
     "order": ["id_", "order_no_", "customer_id_"],
     "columns": [{"name": "id_", "type": "VARCHAR(64)", "pk": true}, {"name": "order_no_", "type": "VARCHAR(64)"}]}
  ],
  "relations": [
    {"id": "r1", "from": "ord_order", "to": "cust_customer", "fromColumn": "customer_id_", "toColumn": "id_",
     "cardinality": "n:1", "label": "placed by"}
  ],
  "dismissed": []
}
```

An import is forgiving — a table without a name, a relation to a table the diagram does not hold or an
unknown cardinality is dropped or defaulted rather than failing the file — and strict only about `format`
and `version`: a file from a newer Atlas is refused rather than half-read.

**Pictures.** *Export → SVG image* and *PNG image* write the diagram as it is drawn, in light colours
whatever the page's theme, without handles or proposals, sized to its content; the SVG carries its font,
so it looks the same wherever it is opened. Inside IntelliJ the browser cannot download: *Export* copies
the diagram file or the SVG to the clipboard instead, and *Open in Browser* has the rest.

## Search

`⌘K` (or `/`) opens the palette. It is a proper search engine, not a filter:

- **Every term must match, in any order.** `order form demo` finds the same thing as `demo form order`.
- **Quoted phrases** are a hard, contiguous requirement.
- **Facets** narrow inline: `t:` / `type:`, `file:`, `key:`, `in:`, `id:`, `label:`, `desc:` (spelled
  `description:` or `doc:` if you prefer). A space after the colon is fine — `desc: approval` is the same
  query as `desc:approval` — and nothing about a search is case-sensitive: not the terms, not the facet
  name, not its value. A multi-word value goes in quotes: `label: "Customer name"` matches that caption
  contiguously, exactly as a quoted phrase would.
- **The empty palette teaches the facets.** Before you type, the bar under the input offers one chip per
  facet, each glossed with what it searches — click one and the prefix is typed for you. A facet you have
  typed through the colon but not given a value yet says what it is waiting for instead of searching for
  the word `label`. And every facet that binds appears as a lit chip beside the result count: proof the
  filter took effect, and — clicked — the way to remove it from the query.
- **A facet is a filter, not a hint.** You have named the field, so the answer is everything that has the
  thing and nothing that merely mentions it: `label:` matches captions only, never the identifier of a
  variable, the text of an expression or binding, a Java class name or a REST path. Those stay findable
  by name, key and free text — they are simply not labels.
- **`id:` looks an element up by its identifier** — `id:save-button` finds the model that declares it and
  opens that element's row. It matches identifiers only, never a caption that happens to read *Save*.
- **`label:` is the other half of that pair** — every caption a person reads is searchable and ranked as
  one: a form field's label, a data object column's label, an outcome button's caption, a permission's
  label, a BPMN/CMMN element's name, a decision table's column headers, and a model's own name.
  `label:save` finds the button that reads *Save*, whatever its id is — and, like `id:`, opens that
  element's row rather than leaving you on the model.
- **`desc:` searches the prose somebody wrote about the thing** — Design's model **Description**, BPMN
  and CMMN `documentation` (on the model *and* on each element), a form component's description, a DMN
  rule's annotation. `desc:approval` finds the task documented as needing one. A plain query searches
  these too, so you do not have to know the facet exists.
- **Word boundaries are understood** — `demo d05`, `demo-d05` and `demo_d05` are the same query, because
  tokens split at camelCase and letter↔digit boundaries as well as at punctuation.
- **It searches inside models**, not just their names: element ids, in/out parameters, form fields,
  columns, permissions, bot keys, agent tools, REST endpoints, labels, descriptions, and a deep walk
  over each node's data.
- **It tells you why a row matched** — with the text that matched, and the element it belongs to after
  `@`: `label · Departure date @date1`. For a facet, with a field of that kind: `label:` is answered by
  the caption that matched, never by the id beside it. On zero results it suggests the nearest real
  names.

Every one of these works in the live demo linked above. `desc:approval` lands on the process documented
as needing one *and* on the DMN rule that says so; `label:volume` on the form field captioned *Expected
monthly volume*; `label:courier` on the task named *Book courier*; `desc:cite` on the knowledge base the
onboarding assistant may quote from.

Results are ranked — an exact name beats a prefix beats a substring, a model outranks a string literal,
and a heavily-referenced node outranks an isolated one. Labels rank just under the node's own name and
key, descriptions above the free-text walk: a caption somebody typed for a reader counts for more than
the same word occurring in a script body, and a caption that reads like the whole query counts for
nearly as much as the node's own name.

Results group by section in a fixed order, and **the page is shared out across the sections** rather
than cut off by score. That matters more than it sounds: searching for a form field's caption also
matches the variable and the `{{binding}}` Atlas derived from that same field, and those score higher,
because the words sit in their own *name* where the form carries them in a label. Ten forms and eighty
derived nodes is an ordinary result set, and a page filled by score alone is all derived nodes. A
section that runs out of hits leaves its share to the others, so a result that really is all one kind
still fills the page with it. Facet chips appear in two tiers, section first,
then category. An empty query shows your eight most recent selections.

The list filter inside a category uses the same engine, and tells you how many matches exist *outside*
the category you are in, with a button to widen the search.

## Keyboard

Press `?` anywhere outside a text field — or the **?** button in the top bar — for this list inside the
page, grouped by where each key works.

| Keys | Where | Action |
|---|---|---|
| `⌘K` / `Ctrl+K`, or `/` | anywhere | Open the search palette |
| `?` | anywhere | Every shortcut, in a sheet over the page |
| `Tab` / `⇧Tab` | palette | Cycle the dialog's controls — facet chips, ×, "Show more", "Did you mean" |
| `↑` `↓` | palette, list, category table | Move |
| `⇧↑` `⇧↓` | palette, list, category table | Extend the marked range |
| `Enter` | palette, list, category table | Open — or open everything marked, as tabs |
| `⌘/Ctrl+Enter` | palette, list, category table | Open in a background tab |
| `Space` | list, category table | Toggle the mark under the cursor |
| `⌘/Ctrl+A` | list, category table | Mark every rendered row |
| `Home` / `End` | list, category table | First / last row |
| `Escape` | palette, list, category table | Clear marks, then close |
| `↑` `↓` / `Home` `End` | sidebar | Move between group headers and entries (folded entries are skipped) |
| `Enter` / `Space` | sidebar group header | Fold or unfold the group |
| `←` / `→` | sidebar | Fold the group you are in and land on its header / unfold a folded header |
| `↑` `↓` | tree | Move between visible rows |
| `→` / `←` | tree | Expand, then move to the first child / collapse, then move to the parent |
| `Space` | tree | Expand or collapse the row |
| `Enter` | tree | Open the node the row names |
| `Home` / `End` | tree | First / last visible row |
| `Alt+←` / `Alt+→` | any view | Back / forward in the page's history — the ‹ › buttons in the top bar do the same |
| `Alt+1`…`Alt+9` | browse | Activate that detail tab |
| `Alt+[` / `Alt+]` | browse | Previous / next tab |
| `Alt+W` | browse | Close the active tab |
| `1`…`4` | browse, a node selected | Its Overview, Findings, Connections or Details tab — the tabs it has, in order |
| `c` | browse, a node selected | Copy the node's key |
| `o` | browse, a node selected, inside the IDE | Open the node's file in the IDE |
| `↓` / `↑` | *View* button | Open the View menu on its first / last switch |
| `↑` `↓` / `Home` `End` | View menu | Move between its switches |
| `Space` / `Enter` | View menu | Flip the switch and keep the menu open / flip it and close |
| `Escape` | View menu | Close and return to the button |
| `+` `-` `0` | diagram (full screen) | Zoom in, out, fit |
| `←` `→` / `Home` | list splitter (focused) | Nudge the list width / reset it |
| `←` `→` / `Home` | sidebar edge (focused) | Nudge the sidebar width, `←` at the narrowest collapses it to the rail / back to the automatic layout |
| `Tab` / `⇧Tab` | diagram | Move between elements |
| `Enter` / `Space` | diagram element | Open its info card |
| `Escape` | diagram, info card | Close |

Tab shortcuts are Alt-based on purpose: browsers reserve ⌘/Ctrl+1…9 and ⌘W for themselves.

## Badges and honesty markers

The explorer never presents a guess as a fact:

- **`≈` suspect** — the link was resolved through an ambiguous match (see
  [uncertain links](../artifacts/#uncertain-links)). The chip is dashed.
- **`ƒ` dynamic** — the reference was an expression, so its target is only certain at runtime.
- **View › Uncertain links** in the top bar hides every uncertain link at once — in the detail panel, the reference tree, the
  overview's hotspots and reference counts alike — so you can see what is left when only
  the certain relationships count. It appears only when there are any.
- **`⚠ N parse issues`** in the sidebar footer jumps straight to the parse findings. A file Atlas could
  not read is never silent.
- A **count pill** on a tree row or a list item says how many open findings that model carries, coloured
  by the worst of them and marked with its icon — a cross for an error, a triangle for a warning, a light
  bulb for advice; a clean model wears none, and hovering the pill says what it counts. The grey number
  with the link icon at the end of a list row is how many nodes reference it — the count *Most
  referenced* sorts by. A row whose key only repeats its name, as a REST endpoint's does, shows it once. On the model's diagram the same count sits as a
  **badge** on each element with a finding — red for an error, amber for a [defect](../checks/)'s
  warning, grey when the element carries advice alone — click it and the element's card lists them, with *restore*
  for an accepted one and *accept…* landing on the finding's row on the Findings tab. **View › Finding
  badges on diagrams** hides the badges on every diagram and is remembered, for a reader who wants the diagram
  as Design draws it.
- The overview's health summary leads with the two numbers, *N defects* and *M advice*, each split by
  tier; the health list at the top of the Checks page groups its rows under those two headings; the
  sidebar's *Checks* badge is
  red while an error is open, amber for a defect's warning and grey when only advice is.
- **One vocabulary for findings.** A defect is labelled by its severity, *error* or *warning*; an advice
  finding is labelled *advice* and drawn grey — in the pills, the health rows, the diagram badges and
  the findings filter alike. The severity `graph.json` records for advice (always `warning`) is what the
  summary and `--fail-on` read; the page only stops printing it where it contradicted the heading.
- Liquibase changelogs carry **live / copy / superseded / orphan**, and services carry per-column schema
  coverage badges. A changelog's page shows the columns of its tables as they stand once every change set
  has run, with the change set that added each one, a **PK** tag on the primary key (declared inline, by
  `addPrimaryKey` or in the SQL, and kept through a rename), and the columns and tables a later change set dropped
  or renamed away; its *Details* tab lists every change set in run order — what it changes, and whether it
  ran, was skipped by its precondition or had already run from another file (see
  [how changelogs are read](../checks/#how-changelogs-are-read)). An app's **copy** of one of the
  application's changelogs says which of its change sets differ from the code, if any. The **Schema coverage** table — every column from the changelog through the service
  mapping to the data object field — is on all three pages of the chain: the service's, and the data
  object's and the changelog's, which show the table of every service whose coverage names them. A column
  mapping that pairs a field with another field's column is marked **`⇄ crossed`** in that table and in
  the schema report — it is not a coverage gap, so the row
  would otherwise look like the cleanest one in the table (see
  [`crossedColumns`](../checks/#crossedcolumns-the-column-mapping-pairs-the-wrong-two-names)).

## Provenance

The sidebar footer names the Atlas version that generated the page and when — *Atlas {{VERSION}} ·
3 days ago*, the exact time on hover. A page mailed to a reviewer could otherwise be a day or
six months old and could not say. The same two facts sit in `graph.json` under `_generated`.

## Themes and text size

Light by default, with a `☀ / ☾ / ◐` toggle in the top bar that cycles light → dark → auto and is remembered. Inside
the IDE the page starts in `auto` and follows the IDE's theme live, including a theme switch while it is
open — but an explicit choice you make in the page still wins.

Inside the IDE the page also wears the IDE's colours: the look-and-feel's panel, editor, border, text,
link and selection colours replace the page's own palette (the type colours, tone colours and the search
highlight stay), so the tab reads like part of Darcula or the New UI instead of a website in a frame.
That holds while the page shows the IDE's mode — following it, or set to the same mode explicitly. Force
the other mode and the page falls back to its own palette for that mode; in a browser nothing changes.

Beside it, `A−` / `A+` step the text size (85 % to 150 %) and remember the choice. Every font size on
the page is a token that this one knob multiplies; the IDE's embedded browser applies none of the IDE's
own font scaling, so without it the element ids and hints stayed at 10–11 px on a dense monitor.

### Accepting a finding

On the Checks page, findings that share one cause fold into one row: the same missing comma in a
binding copied across 22 forms, the same missing error path on 66 mail tasks, read *22 ×* and *66 ×*
with the members a chevron away — the list has as many rows as it has causes. (A model's own page keeps
every row: the reader is there for the elements.)

Every finding row — on the Checks page, and under **Findings on this model** on the model's own page,
where the ⌖ button puts the element on the diagram — carries **accept…**; hovering it says what
accepting does, and the form repeats it in one line beside its buttons. It opens a form on the row:
the reason (required — it is the only part of a waiver a reviewer can review, and an empty one is
refused with a sentence, not a red border), an optional **until** date after which the finding comes
back, and **by**, prefilled with the project's git identity inside the IDE. A finding that names an
element or a subject is accepted for **this finding only** by default; the alternative covers every
finding of that check on the model. An accepted row stays where it was, muted, with the reason, author
and expiry beside the message and **edit** / **restore** in place of the button; the block folds its
accepted rows under *N accepted* and keeps counting only the open ones.

Nothing is written to disk by itself. The page keeps your decisions as a diff over the
[`waivers.json`](../checks/#accepting-a-finding) it was generated from, and a bar under the top bar
says on every view how many are unsaved. **Save to waivers.json** writes the file inside the IDE — which
then regenerates the explorer so the counts and the CI gate follow — and **Export waivers.json**
downloads it anywhere else; **discard** (twice) throws the unsaved decisions away. A rule keeps the day
it was made, so re-saving an unchanged file produces no diff, and the notes and hand-written fields the
file already had travel through untouched.

A decision taken on the page moves every count that depends on it: the Checks page, the health list and the overview's summary, the
sidebar's *Checks* badge and the review lists' own counts (an *Unused forms* entry counts the open findings,
not the forms), and a model whose every finding was accepted wears a small ✓ in lists and in the tree where an
open finding would show its count.

Inside the IDE, *Save to waivers.json* merges rather than overwrites: a rule or note the file gained after
this page was generated — from a colleague, the CLI, a text editor — is kept, while a rule the page saw and
restored is gone. The file is written as UTF-8, and an open, edited `waivers.json` is saved first.

Until the page is regenerated the decisions stay applied here — the counts, the health rows and the
sidebar badge move with them — while `summary.md`, `graph.json` and the gate still show the last run.
After a regeneration the diff finds itself in the file and empties.

### Deliberately accepted

The Checks page ends with what the project decided to live with, one row per rule — the file's rules
minus what you restored on this page, plus what you accepted here, so a decision taken a moment ago is
already in the table and one taken back is already gone. Each row names the check, the model, the
element or subject the rule is narrowed to (or *whole model*), the reason, who decided and when, how
long it holds, and how many findings it matched this run, with **restore** to take it back. A rule's
troubles sit on its row: **matched nothing** (the model was renamed, or the problem was fixed),
**expired**, **no reason**, **missing** (the model is gone), and **unsaved** for a decision no file has
yet. The accepted findings get this table of their own rather than a strike-through in the blocks above,
because "what is wrong" and "what did we agree to carry, and why" are two different questions.

A **Review notes** table follows when the file carries notes — remarks that change no count, with their
importance, scope and author — and a file Atlas could not fully read says so above both.
