# What the plugin does

**The complete Flowable companion for IntelliJ IDEA.** Atlas turns the model keys and expressions
scattered across your Java code and Flowable models into first-class, IDE-aware references —
completed, validated and navigable — and maps the whole project into a single interactive explorer.
Everything is resolved against the models that *actually live in your repository*, so a wrong key or a
broken expression is caught in the editor, long before deployment.

Zero configuration: open a project containing the models you exported from Design (an app `.zip`, a
deployment `.bar`, loose `.bpmn` / `.cmmn` / `.dmn` / `.form` / `.data` / `.service` files) or a Flowable
Design `*-models` workspace, and start typing.

> Every screenshot on this page is HTML and CSS rather than a captured PNG — vector-crisp at any zoom,
> and about 2 KB each. The project shown is the neutral `flowable-demo` sample.

---

## Understand your project

### The Atlas Hub

A single panel on the right stripe: what Atlas currently knows about your project, one line saying what
needs a hand, the models you opened last, and the three things you do with models from the IDE — each
with its actions beside its state, so nothing needs a menu.

It is built for a side stripe: every row fits **280 px**, and a wider stripe only gives the names more
room. It used to be laid out at the width its widest row asked for — a header, a pair of buttons and an
environment row that each wanted 450–570 px — so the stripe had to be dragged half across the screen
before nothing was cut off. Now one control stands in a row where two competed, a name too long for the
row ends in `…` with the whole of it in the tooltip, and a sentence wraps. Each block folds at its
title, and stays folded, per project, the next time the IDE starts.

<figure class="fig mock">
  <div class="body">{{mockup:atlas-hub}}</div>
  <figcaption><b>Atlas Hub.</b> A status header over four foldable blocks, at the width of a side
  stripe. The header answers the question that matters day to day — is what I am looking at still up to
  date? — and says so in one line when it is not.</figcaption>
</figure>

**The header** names the Flowable project Atlas is about, and under it how many models it knows and
how long ago it looked — *142 models · 2 min ago*; the per-type counts and the scope are in the tooltip.
The count is a link into the index (Search Everywhere's *Flowable Model* tab), and *Rebuild Model Index*
sits at the end of that row — the two things one does with the index, where the index is described.
While there is no index yet the row says *scanning…*, or *index failed*. The row under it is the
project's **health** — *3 defects · 41 advice*, a link into [Atlas Findings](#atlas-findings) — with
*Analyze Again* beside it once a model changed since the analysis. Before any analysis it offers *Analyze
findings*; the Hub never starts one on its own, because it is a full analysis of the project. In a monorepo the project is a switcher: pick the sub-project Atlas operates on, and the
index, the output folder and the Design target follow. It is a drop-down like the environment pickers
below it, always offering the whole repository, so "is this mine to change?" is answered by the control
rather than by trying it.

Under it sits **one attention line**, and only when there is something to do: an environment this
project points at was removed (*Manage Environments…*), several Flowable projects were found and none
chosen (*Choose*), the model index could not be built (*Rebuild Model Index*, with the reason — the
status reads *index failed* instead of *scanning…*, and the scan is not retried until you ask or a model
changes), archives the index could not read (*Show*), or the explorer is stale (*Regenerate Atlas
Explorer*). One at a time, in the order of what goes wrong first — the next click hitting the wrong
server, then the whole panel being about the wrong project, then an index that is not there, then data
Atlas could not see, then artifact drift. It is the only row in the panel that comes and goes; everything below keeps its place.

A stale explorer means a model in scope is newer than the newest generated page — whether it arrived
through a Design pull, a `git pull`, an unzipped export or a hand edit. Both places say **which**
models: the attention line names the first three by key (*3 models changed since the explorer:
DEMO-P001, DEMO-F002, DEMO-P007*), the banner above an open explorer tab the first five, then a count —
with *Regenerate Atlas Explorer* on it, so a stale page says what changed where you are reading it. Regenerate is one registered action, and every place that offers it uses its name.

**Explorer** lists the generated pages — name on the left, age on the right, folder and full timestamp
in the tooltip — with **Generate…** and **Open** under them: the block's title says what they generate
and open, and their tooltips carry the full names (*Generate Atlas Explorer…*, *Open Atlas Explorer*). Open takes the
selected page, or the newest; *Open in Browser* is in the list's context menu, where a browser can be
launched. With no page yet the list is one grey line naming the folder it searched, so a page saved
elsewhere is a findable mismatch rather than a wrong claim.

**Recent Models** lists the models opened last, newest first — the key on the left, the file on the
right, which gives way first when the stripe is narrow — the way back to the process you were reading
before a Ctrl+click took you three files away. Every route to a model ends in an editor tab, so
the list follows the editor; double-click opens the model at its key, and the context menu copies the
key or opens the model's explorer page. *Copy Model Key* is also in the editor's context menu, on a
key in Java or in a model file: the bare key, without quotes or the constant's name.

**Design Pull** is the whole pull, top to bottom, in the order the work is done: pick the
**environment**, pick the **workspace** in it, tick the **apps**, press **Pull from DEV1** — the button,
as wide as the block, names its target, so what is about to happen is readable without opening anything,
and *last pull: today 08:52* sits under it. The block is always the same four rows: with nothing defined yet the environment
combo says *no environments yet* beside a *Manage Environments…* link, with no environment chosen the
workspace combo is disabled, and an app list with nothing in it is one grey line — so switching state
moves nothing below it. Both pickers are ordinary drop-downs, because switching is a choice made while
working and should look like one; the environment list is held in memory, and the workspace list is
fetched the first time you open it rather than every time the panel is drawn — the combo says *loading
workspaces…* while it is. When a list cannot be read — no credentials, a server error, no workspace
visible to you — the reason appears under the workspace picker with **Retry** and *Manage
Environments…* beside it; it used to be a balloon, gone by the time anyone looked at the picker that had
not filled. An app row shows the app's name; its key and version are in the tooltip.

What you pick there **is** the project's setting — there is no second copy of it in a settings page.
An earlier cut had a shared default in Settings and a personal override in the Hub, and the pair could
not be told apart on screen: "is this the setting, or my copy of it?" had no answer, and an override
that had drifted made every edit to the default look as if it had done nothing. The workspace and apps
are stored per environment, because a workspace key belongs to one server and cannot mean the same
thing on the next.

**Playground** carries the runtime environment the Atlas Playground evaluates against, and under it the
button that opens it. The two environments are independent on purpose: a runtime on QA while models
still come from DEV1 is a normal way to work, not a mistake to warn about.

A repository can define environments of its own. **Share with Project** in *Settings → Environments*
writes the selected one into `.idea/flowable-environments.xml` — committed, like the Atlas project
settings beside it — and everyone who clones the repository finds it in every picker, marked
*(project)*, having configured nothing. The file holds a name, the *Protected* flag and one URL per
kind, and has **no field a credential could go in**: each developer signs in as themselves, from the
IDE password safe, which is what you want anyway. Your own list still wins — an environment you define
with the same name shadows the project's entirely, which is how *QA* points at your own instance
without arguing with the repository. Shared entries are read-only in the editor; *Copy Environment*
makes one yours in a click, and a `git pull` that moves a URL reaches the pickers without anyone
reopening Settings.

The toolbar reads left to right as what acts on the panel, then where the plugin takes you.
**Refresh** re-reads everything on the panel — the index status, the generated pages and the Flowable
Design workspace and app lists; there is no second reload button. **Settings** opens the Atlas pages.
Then, past a separator, the three destinations: **Open Atlas Explorer**, **Open Atlas Playground**
and **Go to Model…**, each one click rather than two. They stay reachable where they always were —
the Explorer and Playground blocks have their own buttons, the model count in the header is still a
link to the search — because a panel that offers a thing twice costs nothing, and a panel that hides
its three exits costs a visit to a menu every time. The **⋮** menu is *Tools → Flowable Atlas* itself — the same entries in the same order, so there is one
navigation to learn rather than two. It holds everything the toolbar does not: the *Generate* submenu,
the Flowable Design environment switches and pull, *Manage Environments…*, *Rebuild Model Index* — and
**Open Environment in Browser**, which lists every address in the catalog — Design, the app, Control, Hub — grouped by stage,
and hands the one you pick to your browser. The Hub knows those URLs already; without this they stayed
bookmarks, and *which one was QA's Control again?* was a question answered in the browser rather than
here. It follows neither of the two environment pointers, because a third rule about which environment
it means is one more thing that could quietly be wrong — it asks, and with speed search the asking is a
keystroke. Protected stages carry their lock in the list but no prompt: opening a page changes nothing.
Every button and link in the Hub takes its text from the action it runs, so the menu and the panel
cannot call one thing two names.

The index is built when the project opens, in the background, and the Hub asks for one whenever it
finds none — one build for any number of askers, and the editor's markers, hints and inspections are
re-run when it lands. Nothing that runs under the editor's read lock (a highlighting pass, a
reference, Find Usages) ever builds the index itself; only completion and the explicit actions may
wait for it. Apart from that the Hub never scans on its own: it subscribes to a project message bus that publishes
index-invalidated, generation-finished, design-pull-finished, sub-project-switched,
settings-applied, environments-changed and connection-switched events, so it reflects work started
anywhere in the IDE without polling. Every Atlas settings page publishes the settings-applied event
from a `final` method, so a page added later cannot forget to — which is how changing the output folder
once left the Hub listing artifacts from the old one. Its footer names the running Atlas version, and
nothing else: the platform range Atlas was verified against is a fact about the release, and it belongs
[in the reference](../plugin/reference/) and in a bug report — not in a panel that stays open all day.

Reach it from the right stripe, or **Tools → Flowable Atlas → Open Atlas Hub**.

### Atlas Findings

Every finding of the project — the explorer's [Checks page](../checks/) in a tool window on the bottom
stripe, in Swing, so it works under Remote Development and without JCEF. The tree reads **Defects** first
— what is wrong now — then, when asked for, **Advice** and **Accepted**, each by check in the checks
page's order; a double-click or Enter opens the finding's file at its line.

Beside the tree, the **detail pane** says what the page says about the selected check: which kind of
finding it is (*Defect · broken*, *Advice · noise*), the finding's own sentence and where it is, **why it
matters** and **what to do** — including when accepting it is the right answer — with *Read on the checks
page*, *Open in Atlas Explorer* (the finding's model page, or the check on the Checks page) and
*Accept…*. Accepting asks why, in a dialog that refuses an empty reason, and writes one rule per finding
to the output folder's `waivers.json` — the file the explorer's Save writes, so a rule accepted here is
accepted on the page and in the CLI's gate as well.

A **status line** over the tree says how current all this is — *17 defects · 24 advice · analyzed 2 min
ago* — and, once a model changed since, *3 models changed since* with **Analyze Again**. The analysis is
the explorer's own, so generating the explorer brings the window up to date without running it a second
time; nothing else re-runs it behind your back. The toolbar is the Problems view's: *Analyze Again*,
*Accept…*, expand and collapse, the eye with *Show Advice* and *Show Accepted*, and *Open in Atlas
Explorer*.

Reach it from the bottom stripe, the Hub's health row, or **Tools → Flowable Atlas → Open Atlas
Findings**.

### The Atlas Explorer, inside the IDE

Scan the project once and get a clickable, searchable map of every model, every Java class and every
reference between them — as a tab in the IDE.

<figure class="fig mock">
  <div class="body">{{mockup:atlas-explorer}}</div>
  <figcaption><b>The generated explorer as an editor tab.</b> One self-contained file: no server, no
  network, and it opens on a machine that has no IDE at all.</figcaption>
</figure>

This is the artifact nobody has today. It answers "what does this app consist of, and what breaks if I
touch this?" in seconds instead of an afternoon — and because the page is one self-contained file, you
can send it to a reviewer, an architect or a customer with no IDE. It uses **Flowable Design's
vocabulary** throughout, which is what makes that last part true: the recipient does not have to learn
our terms to read it.

The tab is the generated HTML in an embedded browser, handed the IDE's theme *and its colours* on load —
the page wears the look-and-feel's own panel, editor and selection colours rather than its browser
palette, so it sits in Darcula or the New UI like any other tab — and restyled live when you switch
either. It lays itself out for an editor tab's width, with a compact, labelled sidebar. Every tooltip is drawn by the page's own bubble rather than the browser's, because
native tooltips never appear in the embedded viewer — and the plugin injects two bridges: a clipboard
one, because copy is blocked for local files there, and **Open in IDE** — the `↗` beside every model's
and Java class's source path, and every `:line` on a method or REST handler, opens that file in an editor
tab, a model inside a `.bar` included. That is the jump from reading a model to editing the code around
it, and only the embedded tab offers it: the same page in a browser has nowhere to open a file.

The tab says what it is doing: a spinner at the right of its toolbar while the page loads, and — if the
embedded browser cannot load it — a panel in place of the page naming the file and the reason, with
**Reload** and **Regenerate Atlas Explorer**, instead of a blank tab. A tab left open when the IDE closes
comes back on the page it was left on — a model, the Checks page with its filter — rather than on the
dashboard. *Regenerate* wears the platform's build hammer and *Reload* the arrows, so the two neighbours
on the toolbar no longer look alike.

Under **Remote Development** the tab arrives a different way, because there the embedded browser is the
thin client's and fetches everything it shows — a local file included — from the host in 16 KB packets,
one round trip each; a 3 MB report over a 100 ms link took half a minute to appear. The editor loads a
small stand-in page instead, which pulls the report through the IDE bridge gzipped — a sixth to an eighth
of its size — in parts, a few in flight at a time, and shows how much has arrived, how much is left, the
speed and the time remaining. The connection can lose an answer without saying so: a part missing for 30
seconds is asked for again, and if it is still missing the card says what arrived and offers *Retry*, which
asks only for the missing parts. The compressed page is kept in the client's
browser storage under its content hash, so reopening the tab transfers nothing and a regenerated report is
fetched once; a report too large for that storage (above roughly 20 MB) is fetched on every open, and the
card says so. On a local IDE nothing changes: the file is read from disk.

Generate it from **Tools → Flowable Atlas → Generate → Atlas Explorer…** — the page opens as a
tab the moment it is written — reopen it later with **Open Atlas Explorer**, and choose which artifacts a
run produces in *Settings → Tools → Flowable Atlas → Generation*. Full detail:
[The Atlas explorer](../explorer/).

### In and out parameter tracing

Not just *that* something is called — which variables cross the boundary, in which direction, under
which name on the other side, and what every caller passes in.

<figure class="fig mock">
  <div class="body">{{mockup:io-parameters}}</div>
  <figcaption><b>Parameters, and the mirror view.</b> A caller's row under <i>Relations</i> shows what
  it actually passes — the check that catches a payload name that does not line up.</figcaption>
</figure>

Renaming a variable is the quietest way to break a Flowable app, because the mapping that carries it
lives in a different model from the code that reads it. The mirror view is the part that pays for
itself: the caller rows under *Relations* let you check, in one place, whether a form button's payload names line up with what the bot reads via
`flw.getInput(…)` — a mismatch that otherwise surfaces as a null at runtime, in a task nobody exercises
until month-end.

Extraction is namespace-agnostic, so it reads deployment XML and Flowable Design's export shapes alike,
and it covers models packed inside app archives without unpacking them. See
[Variable analysis](../variables/) for how direction is established.

### Model structure, without opening Design

Everything Atlas parses out of a model is on the page and linked into the graph — user tasks, script
bodies, case criteria, agent prompts — so you can read what a model *does* without opening Flowable
Design.

<figure class="fig mock">
  <div class="body">{{mockup:model-structure}}</div>
  <figcaption><b>Model structure</b> appears for whatever that model actually has: fields, tasks,
  criteria, permissions, agent tools — a process's or a case's elements as the groups of one section.</figcaption>
</figure>

<figure class="fig mock">
  <div class="body">{{mockup:model-structure-2}}</div>
  <figcaption><b>Case criteria</b> in full: the exact condition guarding each milestone, readable
  without a modelling tool.</figcaption>
</figure>

It puts a Flowable model in reach of people who do not have Design open — or do not have Design at all.
A reviewer can see that a script task mutates a variable, a tester can read the exact condition guarding
a case milestone, a support engineer can check an agent's prompt. That used to be a screen-share with a
modeller.

These sections render from the same parsed data the graph is built from, not a second pass — so if a
section shows an element, the graph already has an edge for it.

### Diagrams in the gutter

A gutter icon next to any model key opens that model beside its diagram — next to a key literal or
constant in Java, and inside a model file next to the file's own key and every call activity, case task,
decision or form reference, so a minified export opens its own diagram from its first line. A form or
page opens beside a wireframe of its layout (see below).

<figure class="fig mock">
  <div class="body">{{mockup:diagrams}}</div>
  <figcaption><b>From a key literal to the diagram</b> — without leaving the file you were working
  in.</figcaption>
</figure>

It removes a context switch that happens dozens of times a day. Reading a process key tells you
nothing; seeing the diagram tells you everything. The **type glyphs** are what make it trustworthy at a
glance: a diagram of identical rectangles tells you the shape of a process, not what it does.

Two extractors feed one geometry model — deployment-XML diagram interchange, and Flowable Design's
workspace JSON — so it also keeps working when a Design version stops shipping SVGs, which is exactly
when tooling normally breaks. A decision table has no canvas to lay out, so its **rules are painted as a
table** instead.

Turn on *Recognize model keys anywhere in code* to get the icon on any literal that matches a key, and
tick *Diagrams (SVG)* under Generation to also write them out as files.

### App archives, opened in the IDE

A Design export is a `.zip`, a deployment is a `.bar`, and both used to be closed boxes in the Project
view: the platform expands an archive only when it is a library, so the models inside could be searched
but not browsed. Both now expand into their folders and entries, and a model opens straight from the
tree, read-only.

A process, case, decision, form or page opens as **text and picture side by side** — loose in the
project or out of an archive. The picture is the diagram, the decision table, or for a form or page a
**wireframe of its layout**: Design's twelve-column grid at its real proportions, each component as a
placeholder of its kind with its caption, a star when it is required, and its id underneath, which is
the name a `{{…}}` or a script reaches it by. Panels, tabs and accordions keep their own grids, a data
table shows its column headers, and a subform names the form it embeds. A `visible` or `enabled` that
depends on an expression is spelled out beside the id; one that is plainly off greys the component out.
It is a developer's map of the form, not a preview of the Work UI.

The picture is painted in Swing on the IDE host, not in a browser panel, so it stays fast under Remote
Development, where every resource a JCEF view loads is a round trip to the client. It fits the width,
zooms from its toolbar, and redraws when the file changes on disk. A click on what it draws — a task, a
plan item, a decision rule, a form component — puts the caret on that element's declaration beside it:
the id itself, not a flow or a JSON key that happens to mention it first. It is the same picture the
explorer shows on the model's page and the diagrams folder holds. The editor's layout toggle hides
either half; going to a line brings the text back.

The **Structure** tool window outlines the same models: a form's or page's components as Design nests
them, each by its caption with its id beside it, and a process's or case's elements by name, without the
connectors and the diagram interchange. A click goes to the component or the element, in an archive
entry as much as in a loose file.

### Interactive diagrams

In the explorer the diagram is not a picture of the process — it is how you navigate it.

<figure class="fig mock">
  <div class="body">{{mockup:interactive-diagrams}}</div>
  <figcaption><b>Click an element</b> to see what it does; <code>⌖</code> takes you back the other
  way, from a row in the detail panel to the element on the canvas.</figcaption>
</figure>

A static diagram tells you the shape of a process; this tells you what it does. Clicking an element to
see the variables it reads and writes is the question people actually ask, and the `⌖` round trip means
you never lose your place between the picture and the detail.

Zoom with `+` / `−` / *fit*, or ⌘/Ctrl-scroll; drag to pan; `Esc` leaves full screen. The wheel
behaviour sounds like a footnote and is not: a diagram that swallows the scroll wheel makes a long
report miserable to read, which is why it was built the other way. The SVG is never re-rendered —
panning and zooming are pure transforms, which is what keeps it smooth on a diagram with hundreds of
elements.

---

## Navigate & validate

### Model-key intelligence

Every model key becomes a first-class reference: completed as you type, clickable, documented on hover,
findable in Search Everywhere. A click lands on the key's *declaration* — the `id` of the process, the
`"key"` of the form — not on line 1 of a minified export or at the top of a deployment file holding three
processes.

<figure class="fig mock">
  <div class="body">{{mockup:key-intelligence}}</div>
  <figcaption><b>Completion at every Flowable API position that takes a key</b> — searchable by key,
  by name, or by any fragment.</figcaption>
</figure>

Keys are the seams of a Flowable app, and they were entirely opaque. Completion means you stop
alt-tabbing to Design to copy a key; navigation means the model is one click from the code that starts
it; name search means you can find a key when all you remember is what the process is called.

It goes further than one key at a time. **Cascade completion** resolves fluent chains — `operation("…")`
completes the operations of the data object or service resolved from the sibling `definitionKey(…)` or
`serviceKey(…)`, and `value("…", …)` completes that operation's input fields. **More than keys**:
message and signal names, process variables, task-definition keys, activity ids and form outcomes, each
scoped to the model the call site names.

Search Everywhere finds the elements inside the models too — a user task id, a variable, a message, a
form field — each row saying which model it belongs to and landing on its declaration, so the
`approveTask` from a log line is one shortcut away from the task it names.

There is also a dedicated **Flowable Model** tab in Search Everywhere that searches model keys, paths
*inside* `.bar` / `.zip` archives, and — while that tab is open — the full text of every model, showing
the matched line.

Under **Remote Development** that popup has no Flowable tab: a tab contributed by a plugin on the host
does not reach the thin client where the popup renders. *Go to Model…* asks for a pattern there and
opens the result list below instead — the same index and the same model text, one dialog more. *Find in
Models…* works the same in both.

That popup is for reaching **one** place, and it closes when you do. For the other question — *where is
this string, everywhere?* — press **⇧⏎** on any row, or run **Find in Models…**: the same three kinds of
hit land in the Find tool window as a list that stays open, grouped by file, archive entries included,
so thirty places can be worked through instead of searched for thirty times. *Find in Models…* is
prefilled from the editor — the selection, else the model key under the caret — and the window says
which scope it searched, so a sub-project chosen in the Hub is stated rather than implied.

The catalog of API positions is transcribed from the public Flowable interfaces and matched through
subinterfaces, so one entry covers every service that extends it. Both `org.flowable.*` and
`com.flowable.*`.

### Schema support in model XML

A `.bpmn`, `.cmmn` or `.dmn` opens like any other schema-backed XML: `<` inside a process offers the
BPMN elements, `flowable:` offers the Flowable attributes, and a missing required attribute is said in
the editor rather than at deployment. The IDE ships none of these schemas itself, so before this the
same file opened with an unresolved namespace and no completion at all. The schemas are Flowable's own
copies, bundled with the plugin — nothing is fetched, and it works offline.

What is deliberately *not* validated: `http://flowable.org/design`, `http://flowable.org/cmmn` and
`http://flowable.org/modeler`. No schema for them exists anywhere, so they are registered as ignored —
the *URI is not registered* warning goes away without pretending we can check them. That costs nothing,
because all three formats allow foreign attributes at every element, so an undescribed namespace is
skipped rather than rejected. Measured against a real corpus, 99 % of models validate clean, and the
remainder are genuine defects: an id that is not a valid XML name, a diagram edge with no waypoints, a
required attribute nobody filled in. Those were always wrong; now they are visible.

### Key validation

A key that matches no model in the project is flagged in the editor, with a quick fix to the closest
real key — in Java **and** inside model XML. Not in test sources: a test that starts `no-such-process`
to assert the failure is doing its job, so `src/test/**` is left alone (test *models* are still judged).
In a monorepo the message names the scope it judged against — *not a known Process key in
apps/orders* — because a key from another module is unknown *here*, not nonexistent.

<figure class="fig mock">
  <div class="body">{{mockup:key-validation}}</div>
  <figcaption><b>An unknown key, in the editor</b>, with a “did you mean…?” fix to the nearest key
  that would actually be valid in that position.</figcaption>
</figure>

This is the single highest-value check in the plugin. A typo'd key is invisible to the compiler and to
your tests, then fails at deployment — often in the customer's environment. Moving that failure from
deployment to the editor removes an entire class of incident.

Nearest-candidate suggestions compare only against the indexed keys of the *expected type*, so the
proposal is always a key that would be valid there. Inside model XML the same check covers
`calledElement`, `formKey`, `decisionRef`, `caseDefinitionKey` and the rest — in attributes and in the
text of extension elements such as `eventType` alike.

Navigation reaches further than the check. A key inside a **JSON** model — a data object's backing
service, a form component's subform, data object, service or action, a document's forms, an app's
models — is a link: Ctrl+click opens the referenced model at its key, and Find Usages on a model's key
lists these sites. What counts as a reference is one catalog shared with the report, so the graph the
CLI draws and the links the editor offers can never disagree.

### Java ↔ model linking

Models reference Java by name, in text. Atlas makes that link visible in both directions — so the IDE
stops lying to you about what is used.

<figure class="fig mock">
  <div class="body">{{mockup:java-linking}}</div>
  <figcaption><b>Find Usages from Java into models</b>, plus a gutter icon on every
  model-referenced class and method — opening the model at the reference itself.</figcaption>
</figure>

Two real failure modes disappear. "Unused" code that a process depends on gets deleted in a cleanup
sprint and the process dies in production — Atlas reports it as implicitly used, so the IDE stops
greying it out. And a safe-looking rename silently breaks every model that named the old symbol —
Atlas raises a warning with *Show affected models*, because the refactoring engine never knew those
files were referring to it.

It works for delegates and beans, for **bot classes** (an action's `botKey` ↔ the `BotService` that
implements it) and for **REST handlers**: a Spring `@GetMapping` method is linked to the models whose
HTTP task, REST button or service operation calls that URL — matched by written short name, so Spring
does not even need to be on the classpath.

The model side answers too: Find Usages on a model's own key — the `id` of a process, the `"key"` of a
form — lists every model that references it (the call activity, the task's form key, the service
mapping) and every Java call site that names it. And Alt+Enter on a key — in Java, or in a model file on
a cross-reference or the file's own key — offers **Open in Atlas
Explorer**, which opens that model's page in the generated explorer inside the IDE — the relationship
view, where Ctrl+click opens the file.

The IDE and the generated explorer share one matching implementation, so Find Usages in the editor and
the reference list in the report can never disagree.

---

## Expressions

### Expressions as a language

Flowable expressions stop being strings. Both dialects get highlighting, matched brackets, completion,
documentation and validation — wherever they appear.

<figure class="fig mock">
  <div class="body">{{mockup:expression-language}}</div>
  <figcaption><b>Two dialects, validated in place.</b> Rainbow parentheses, completion after a
  namespace, and an unknown function underlined as you type.</figcaption>
</figure>

Expressions are where low-code projects break, and they were the one place with no tooling at all: no
colour, no completion, no validation, no way to tell a typo from a variable set at runtime. A misspelled
function or root now underlines as you type instead of failing on a task nobody exercises until
month-end.

The root of a backend expression that names a Spring bean is a link: Ctrl+click on `orderService`
in `${orderService.process(order)}` opens the class, Ctrl+Q shows its documentation, and completion
after the dot already offered its methods.

Real Groovy and JavaScript are injected into script bodies too, so a script task inside a BPMN file gets
that language's own highlighting and inspections, plus completion for the root objects Flowable actually
binds in *that* context.

Custom functions declared by your own customisation source are discovered from the project and folded
into the catalog, so your team's own helpers complete and validate like built-ins. The rest —
including what can and cannot be allowlisted — is on the
[Expressions & scripts](../expressions/) page.

### The playgrounds

Type an expression, see what it runs against, watch it evaluate — or run it against the app that is
actually running.

<figure class="fig mock">
  <div class="body">{{mockup:playground}}</div>
  <figcaption><b>Atlas Playground.</b> The code on one side, what it runs against and what came out
  on the other. Sub-expression values inline turn “it returns nothing” into “the third argument is null,
  here”.</figcaption>
</figure>

Until now the only way to test an expression was to deploy and trigger the task that uses it. This
closes a loop measured in minutes down to one measured in keystrokes.

Both tabs stand on one shell: the **editor** with its problems under it, and beside it — or below it,
when the window is docked at the side — the **context** the code runs against over the **result**. The
context has one summary line that is always there — *QA (project) · Case instance CAS-4711*, or
*payload, 14 lines, at orders[1].items[0]* — and the controls behind it fold away once they are set.
Switching **Backend** and **Frontend** changes the editor's language and the context's controls; the
layout never moves. The splitter is yours to drag and is remembered; *Stack Panels* in the gear menu
overrides what the dock suggested. Findings are painted with the editor colour scheme's own error and
warning attributes, in the editor's own font — the same wave a Java file gets, and the same colours
you tuned under *Errors and Warnings*.

In the **Frontend** dialect the context is the payload — JSON, optional — and the node the expression is
evaluated *at*, picked by path or *From Cursor* and tinted in the JSON itself; the result updates as you
type, and *Show Sub-Expression Values* puts `= 47.7` after every argument. In the **Backend** dialect
the context is the environment and the live instance, and **Evaluate Against App** (Ctrl+Enter) posts
the expression to the running app through the Flowable Inspect REST API. One result pane serves both:
it says *Evaluating against QA…* while it waits, and a value that cannot be previewed statically reads
as information, never as a failure.

A second **Scripts** tab does the same for script bodies on the same shell: the context is what the
selected script context *provides* — its bound root objects and the platform beans, as clickable chips
that insert at the caret — and the result is what the script *does*, the variables it writes through the
API and the ones it likely reads. *Load Script from Model…* pulls a real script out of an indexed model;
*Load Example…* is the other direction — a library of complete, working scripts, at least one per
context and per language, each commented with the decision it demonstrates.

Which app it evaluates against is a choice, not a form: the card names the environment in a drop-down,
and that is the whole connection UI. Everything else about getting there lives behind one button —
**Paste Work URL…** — which is also the fastest way to arrive anywhere: paste the address of a case,
process or task from Flowable Work and the dialog resolves the app, the scope and the instance id from
it. When the app is not one of your environments it asks how to get in and tests the connection before it
closes, so the next click actually evaluates — and it **creates nothing**: the app becomes a target for
this IDE session, marked *(this session)* in the picker, with its credentials kept in memory. A link
from a colleague should not leave an environment behind, and being made to name one first is a toll on
the common case. Environments are something you decide to have, in *Settings → Environments*.

Every pasted link keeps its own entry, so comparing two apps does not mean re-pasting the first one. The
button beside the picker is where those entries are managed: **Forget** one, forget all of them, or —
for the one that turns out to be somewhere you keep coming back to — **Save as an Environment…**, which
asks for the single thing that was missing. Give it a name that already exists and it joins that
environment instead of making a second one with the same label; credentials typed in the paste dialog go
along, into the password safe. Forgetting a target takes whatever was captured for it with it.

Evaluating against a **Protected** environment asks first — but from a small confirmation with *Cancel*
preselected, so declining is one keystroke, and the lock stays visible on the picker the whole time.

Signing in is the same question for every Flowable server Atlas talks to, and it is asked in one place:
the connection in *Settings → Environments*. A username and password, or an access token — and, for a
server behind single sign-on, your own browser session, captured either by an embedded login or by
pasting a request from your browser's dev tools. The session layers *on top of* a credential rather than
replacing it, because an SSO-fronted Flowable often wants both and its security chain takes whichever it
honours. Captured headers stay in memory for the IDE session only; passwords and tokens go to the OS
keychain, keyed by URL — so a Design server and an app are separate logins, as they always were.

Reach it from the bottom stripe, from **Tools → Flowable Atlas → Open Atlas Playground**, from the
toolbar of an open explorer tab, or with Alt-Enter on any expression in a model — which also presets the
instance kind from the model, a process instance for BPMN and a case instance for CMMN.

---

## Generate & sync

### Code generation

Turn models into the boilerplate you would otherwise hand-write: a typed constants class, data-object
DTOs, and Liquibase changelogs.

<figure class="fig mock">
  <div class="body">{{mockup:codegen}}</div>
  <figcaption><b>Generate before you commit to it.</b> Every generator previews exactly what it will
  write, and which files it would overwrite.</figcaption>
</figure>

A constants class is what turns every key into a compile-time-checked symbol — after which renaming a
model is a refactoring rather than a search-and-replace. It regenerates itself in place when the models
change, so it cannot go stale. The DTO generator emits typed fields, a `fromContainer(…)` mapper and a
fluent builder, either from one data object via Alt-Enter or in bulk for whole apps. The Liquibase
dialog exists because the alternative is unzipping app exports by hand to find out which changelogs are
in there; here you see the plan — including which files already exist — before anything is written.

Every generator is a pure function from model data to a string, which is why they are all unit-tested
and produce identical output in the IDE and on the command line. Names, packages and patterns are
configurable in *Settings → Tools → Flowable Atlas → Generation*.

### Liquibase awareness

Your changelogs and your Flowable data models are checked against each other, in the editor.

<figure class="fig mock">
  <div class="body">{{mockup:liquibase}}</div>
  <figcaption><b>Column completion from the backing model</b>, and an inspection when a changelog
  column maps to no field at all.</figcaption>
</figure>

Schema drift between a changelog and the model that reads the table is silent by nature — everything
deploys, then a query returns nothing or a write fails on a column that is not there. The explorer
surfaces the same comparison project-wide as a Liquibase → service → data object coverage table, so you
can see every gap at once — and shows each service's table on its data object's and its changelog's page
too, wherever you started from.

Changelogs are parsed and replayed with a small dedicated reader — no XSD, no Liquibase runtime on the
classpath — which is why this works regardless of how Liquibase is wired in your project.

### Flowable Design sync

Pull the app export straight from a Design server into the repository, and get told what disappeared.

<figure class="fig mock">
  <div class="body">{{mockup:design-sync}}</div>
  <figcaption><b>Drift detection at the moment of the pull</b> — the one moment when the person
  reading it still has the context to fix it.</figcaption>
</figure>

A modeller renames or deletes a model in Design; a developer pulls; the code that referenced the old key
still compiles and now points at nothing. Atlas names the keys that vanished since the last pull, and
offers to regenerate the explorer. The same balloon says what the pull *brought*, per app — how many
models changed, were added or removed against the export that was on disk before, and which ones when
there are few — so "what did I just pull?" is answered where you are standing. The progress bar moves
per app, and a pull refused with HTTP 401 offers **Sign out & retry**: it clears the stored credential
for that server, opens the connection to sign in again, and pulls once it can.

Files are written the way Design names its own exports, each through a temp file and an atomic move, and
the model index is rebuilt afterwards. Authentication is a username and password, an access token — it
can create the token for you — or, behind an identity provider, your captured browser session. Note that
*creating* a token is itself a username-and-password call, so on a server where SSO has switched those
off, the browser session is the route that works. Credentials go to the IDE's password safe, never to a
shared file.

The pull names the environment it is running against, in the progress bar and in the notification. An
environment marked **Protected** asks first, modally, because a pull replaces archives in the working
tree — and it asks every time, since a guard you can switch off is not a guard.

### Compare a model against the app archive

A model that was generated rather than modelled — a form written by an LLM, say — lands in the project
folder, not in the app export. *Compare Model with Archive* puts the two side by side in the IDE's own
diff viewer: the entry inside the app's `.zip` on the left, the file in the project on the right. It works
from either end, so an entry opened by *Go to Model* can be compared against the project just as well.
The counterpart is found by file name, by the name behind the `<kind>-` prefix a deployment `.bar` gives
its entries, and failing both by the model key inside the file — because a generator rarely names a file
the way Design does. One match opens straight away; several, or none, offer the archive's model entries in
a list that filters as you type.

**Both sides are laid out first, and that is the point.** A Design export is minified — one long line per
model — so compared as it stands, every line differs and the viewer has nothing to say. Atlas re-indents
both sides, whitespace only and never a value, so a number still reads the way its file spells it; the
pair is then read-only, because neither side is the file on disk any more. *Show Raw Files* in the diff
toolbar gives the two files themselves, where the project side stays editable. Processes, cases and
decisions are exported formatted already and are always shown as they are.

---

## Foundations

### Settings, scopes and monorepos

Sensible defaults, one settings tree, and a clear rule about what is shared with the team and what stays
yours.

<figure class="fig mock">
  <div class="body">{{mockup:settings}}</div>
  <figcaption><b>One tree:</b> a root page under Tools, with Environments, Expressions and Generation
  beneath it. Servers are defined once; which one you use is picked where you use it.</figcaption>
</figure>

This is what makes the plugin usable by a team rather than by one enthusiast. The allowlist a colleague
added arrives with a `git pull`; your pasted test payload does not. Shared settings live in a committed
file under `.idea`; your own choices live in the workspace file, and secrets in the OS keychain.

Servers are a third thing again, and they sit one level up: an environment list is **IDE-wide**, because
a DEV or QA URL is the same in every Flowable repository you open, and typing it once per project is the
tedium this removes. Settings holds only that list — which environment a given thing uses is picked at
that thing, in the Atlas Hub or the playground, which is what keeps the two from ever disagreeing.
An environment can hold four addresses: the **Design** server and the **Work** app that Atlas signs in
to and calls, plus **Control** and **Hub**, which it only ever hands to a browser. Those two are a URL
and nothing else — no username, no password, no *Test Connection* — because nothing is authenticated
and nothing is stored.
The choice itself stays in your workspace file, since the ids belong to your IDE and would mean nothing
in a colleague's; what a project pulls *from* an environment is committed like every other project
setting.

Monorepo scoping means the one repository holding four Flowable apps does not need four IDE profiles:
**every project setting is stored per sub-project**, and the Hub switches between them. Older flat
settings files still load without a migration step, so upgrading never asks anyone to reconfigure
anything. The chosen scope is the scope of *everything* — the model index, the Search Everywhere tab's
full-text half, Find Usages into models, the REST-endpoint gutter — and the Hub's index line names it
(*312 models indexed in apps/onboarding*), along with any archive in scope it could not read, because an
unreadable `.bar` must not look like an empty project.

Every option, inspection, action and file type is listed on the
[plugin reference](reference/) page.
