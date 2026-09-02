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

A single panel on the right stripe showing what Atlas currently knows about your project — and letting
you act on it without opening a menu.

<figure class="fig mock">
  <div class="body">{{mockup:atlas-hub}}</div>
  <figcaption><b>Atlas Hub.</b> Every action sits next to the state it affects, and the panel answers
  the question that matters day to day: is what I am looking at still up to date?</figcaption>
</figure>

The Hub reports whether the model index has been built and when it was last scanned, which explorer was
generated and whether the models have changed since — any model in scope newer than the newest page
counts, whether it arrived through a Design pull, a `git pull`, an unzipped export or a hand edit — and
which environments this project is pointed at. The same comparison puts a banner above an open explorer
tab, with *Regenerate* on it, so a stale page says so where you are reading it.
**Rebuild**, **Generate Constants…**, **Generate…**, **Open in Browser** and **Pull from …** each sit
beside the thing they change. In a monorepo the *Flowable Project* section is a switcher: pick the
sub-project Atlas operates on, and the index, the output folder and the Design target follow. It is a
drop-down like the environment pickers below it, always offering the whole repository, so "is this mine
to change?" is answered by the control rather than by trying it.

Every list in the panel is sized to what it holds. The Hub shares one narrow stripe between five
sections, so a box reserving eight rows for the one generated explorer that the ordinary project has is
that stripe spent on nothing; an empty section is a single grey line instead. A workspace with twenty
apps scrolls at eight rows rather than pushing the pull button off the panel.

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

Its toolbar also opens the environments themselves: **Open Environment in Browser** lists every address
in the catalog — Design, the app, Control, Hub — grouped by stage, and hands the one you pick to your
browser. The Hub knows those URLs already; without this they stayed bookmarks, and *which one was QA's
Control again?* was a question answered in the browser rather than here. It follows neither of the two
pointers below it, because a third rule about which environment it means is one more thing that could
quietly be wrong — it asks, and with speed search the asking is a keystroke. Protected stages carry
their lock in the list but no prompt: opening a page changes nothing.

Its *Flowable Design* section is the whole pull, top to bottom, in the order the work is done: pick the
**environment**, pick the **workspace** in it, tick the **apps**, press **Pull from DEV1** — the link
names its target, so what is about to happen is readable without opening anything. Both pickers are
ordinary drop-downs, because switching is a choice made while working and should look like one; they
open instantly, since the environment list is held in memory, and the workspace list is fetched the
first time you open it rather than every time the panel is drawn. A *Expression Playground* section
below carries the runtime environment the same way. The two are independent on purpose: a runtime on QA
while models still come from DEV1 is a normal way to work, not a mistake to warn about.

What you pick there **is** the project's setting — there is no second copy of it in a settings page.
An earlier cut had a shared default in Settings and a personal override in the Hub, and the pair could
not be told apart on screen: "is this the setting, or my copy of it?" had no answer, and an override
that had drifted made every edit to the default look as if it had done nothing. The workspace and apps
are stored per environment, because a workspace key belongs to one server and cannot mean the same
thing on the next.

The index is built when the project opens, in the background, and the Hub asks for one whenever it
finds none — one build for any number of askers, and the editor's markers, hints and inspections are
re-run when it lands. Nothing that runs under the editor's read lock (a highlighting pass, a
reference, Find Usages) ever builds the index itself; only completion and the explicit actions may
wait for it. Apart from that the Hub never scans on its own: it subscribes to a project message bus that publishes
index-invalidated, generation-finished, design-pull-finished, sub-project-switched,
settings-applied, environments-changed and connection-switched events, so it reflects work started
anywhere in the IDE without polling. Every Atlas settings page publishes the settings-applied event
from a `final` method, so a page added later cannot forget to — which is how changing the output folder
once left the Hub listing artifacts from the old one. Its footer names the running Atlas version, and nothing else: the
platform range Atlas was verified against is a fact about the release, and it belongs
[in the reference](../plugin/reference/) and in a bug report — not in a panel that stays open all day.

Reach it from the right stripe, or **Tools → Flowable Atlas → Atlas Hub**.

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

The tab is the generated HTML in an embedded browser, handed the IDE's theme on load and restyled live
when you switch it. Every tooltip is drawn by the page's own bubble rather than the browser's, because
native tooltips never appear in the embedded viewer — and the plugin injects a clipboard bridge, because
copy is blocked for local files there.

Generate it from **Tools → Flowable Atlas → Generate → Generate Atlas Explorer…** — the page opens as a
tab the moment it is written — reopen it later with **Open Atlas Explorer**, and choose which artifacts a
run produces in *Settings → Tools → Flowable Atlas → Generation*. Full detail:
[The Atlas explorer](../explorer/).

### In and out parameter tracing

Not just *that* something is called — which variables cross the boundary, in which direction, under
which name on the other side, and what every caller passes in.

<figure class="fig mock">
  <div class="body">{{mockup:io-parameters}}</div>
  <figcaption><b>Parameters, and the mirror view.</b> <i>Called with</i> shows what every caller
  actually passes — the check that catches a payload name that does not line up.</figcaption>
</figure>

Renaming a variable is the quietest way to break a Flowable app, because the mapping that carries it
lives in a different model from the code that reads it. *Called with* is the part that pays for itself:
in one place you can check whether a form button's payload names line up with what the bot reads via
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
  <figcaption><b>Model structure sections</b> appear for whatever that model actually has: fields,
  tasks, criteria, permissions, agent tools.</figcaption>
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

A gutter icon next to any model key opens that model's diagram.

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
findable in Search Everywhere.

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

There is also a dedicated **Flowable Model** tab in Search Everywhere that searches model keys, paths
*inside* `.bar` / `.zip` archives, and — while that tab is open — the full text of every model, showing
the matched line.

The catalog of API positions is transcribed from the public Flowable interfaces and matched through
subinterfaces, so one entry covers every service that extends it. Both `org.flowable.*` and
`com.flowable.*`.

### Key validation

A key that matches no model in the project is flagged in the editor, with a quick fix to the closest
real key — in Java **and** inside model XML.

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
`calledElement`, `formKey`, `decisionRef`, `caseDefinitionKey` and the rest.

### Java ↔ model linking

Models reference Java by name, in text. Atlas makes that link visible in both directions — so the IDE
stops lying to you about what is used.

<figure class="fig mock">
  <div class="body">{{mockup:java-linking}}</div>
  <figcaption><b>Find Usages from Java into models</b>, plus a gutter icon on every
  model-referenced class and method.</figcaption>
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

Real Groovy and JavaScript are injected into script bodies too, so a script task inside a BPMN file gets
that language's own highlighting and inspections, plus completion for the root objects Flowable actually
binds in *that* context.

Custom functions declared by your own customisation source are discovered from the project and folded
into the catalog, so your team's own helpers complete and validate like built-ins. The rest —
including what can and cannot be allowlisted — is on the
[Expressions & scripts](../expressions/) page.

### The playgrounds

Type an expression, paste a payload, and watch it evaluate — or run it against the actually-running app.

<figure class="fig mock">
  <div class="body">{{mockup:playground}}</div>
  <figcaption><b>Expression Playground.</b> Sub-expression values inline turn “it returns nothing”
  into “the third argument is null, here”.</figcaption>
</figure>

Until now the only way to test an expression was to deploy and trigger the task that uses it. This
closes a loop measured in minutes down to one measured in keystrokes.

A second **Scripts** tab does the same for script bodies, with the variables, reads, bindings and beans
of the selected context as clickable chips, and *Load Script from Model…* to pull a real script out of
an indexed model. *Load Example…* is the other direction — a library of complete, working scripts, at
least one per context and per language, from variables and JSON through the engine services to an action
bot's inputs and outputs, each one commented with the decision it demonstrates and edited like any other
script in the tab.

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

Reach it from the bottom stripe, from **Tools → Flowable Atlas → Open Expression Playground**, or with
Alt-Enter on any expression in a model. It is also a second tab on every generated explorer.

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
can see every gap at once.

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
offers to regenerate the explorer.

Files are written the way Design names its own exports, each through a temp file and an atomic move, and
the model index is rebuilt afterwards. Authentication is a username and password, an access token — it
can create the token for you — or, behind an identity provider, your captured browser session. Note that
*creating* a token is itself a username-and-password call, so on a server where SSO has switched those
off, the browser session is the route that works. Credentials go to the IDE's password safe, never to a
shared file.

The pull names the environment it is running against, in the progress bar and in the notification. An
environment marked **Protected** asks first, modally, because a pull replaces archives in the working
tree — and it asks every time, since a guard you can switch off is not a guard.

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
anything.

Every option, inspection, action and file type is listed on the
[plugin reference](reference/) page.
