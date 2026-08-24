<div class="hero">
<h1>Read any Flowable project</h1>
<p class="sub">Atlas maps a Flowable solution — the app models <b>and</b> the Java code — into an
interactive explorer, four agent-ready artifacts, and an IntelliJ plugin that turns every model key
and expression into a real, checked reference.</p>
<div class="cta"><a class="btn" href="start/">Get started</a><a class="btn ghost" href="demo/explorer.html" target="_blank" rel="noopener">Open the live demo ↗</a><a class="btn ghost" href="https://github.com/kle-dev/flw-atlas/releases/latest">Download the latest release</a></div>
</div>

A Flowable project is hard to read for a reason that has nothing to do with skill: the behaviour lives
in models, the models reference each other by opaque keys, and the Java attaches through expression
strings that no compiler checks. Nothing in the repository tells you what calls a form, which delegate a
process actually runs, or what breaks if you rename a variable.

Atlas answers those questions by resolving the whole project — every model, every Java class, and every
reference between them — and then handing you the answer in whichever shape you need it.

<figure class="fig">
  <div class="body"><img class="only-light" src="assets/img/hero-explorer.png" alt="The Atlas explorer showing a project overview: inventory, health and hotspots" width="1600" height="1000"><img class="only-dark" src="assets/img/hero-explorer-dark.png" alt="The Atlas explorer showing a project overview: inventory, health and hotspots" width="1600" height="1000"></div>
  <figcaption><b>One self-contained HTML file.</b> No server, no network, no install — and it opens on
  a machine with no IDE at all. <a href="demo/explorer.html" target="_blank" rel="noopener">Try the live one ↗</a></figcaption>
</figure>

## One command

```bash
./atlas /path/to/your-flowable-project
```

That is the whole thing. Atlas walks the project, resolves what it can, tells you what it could not,
and writes five files:

<div class="cards">
<a class="card" href="explorer/" style="--tint:var(--c-process)"><h3>explorer.html</h3><p>An offline, interactive explorer. Click through every relationship in both directions, search inside models, read diagrams.</p></a>
<a class="card" href="artifacts/#projectsummarymd--orientation" style="--tint:var(--c-form)"><h3>summary.md</h3><p>A few KB of orientation: apps, inventory, entry points, integrations, hotspots, health. Written for an LLM first.</p></a>
<a class="card" href="artifacts/#projectoverviewmd--the-full-report" style="--tint:var(--c-case)"><h3>overview.md</h3><p>The full human report — every model in execution order, the access map, the data layer, every finding with file:line.</p></a>
<a class="card" href="artifacts/#projectgraphjson--for-querying" style="--tint:var(--c-service)"><h3>graph.json</h3><p>The traversable model↔code graph, for querying rather than reading. Every node carries <code>usedBy</code>, and the file documents itself.</p></a>
<a class="card" href="agents/" style="--tint:var(--c-agent)"><h3>CLAUDE.md</h3><p>Drop-in context for an AI agent: a Flowable primer plus this project's real conventions, findings and available APIs.</p></a>
</div>

Needs a **JRE 21+** and nothing else. No third-party dependencies, no database, no running Flowable
instance, no network.

## In the IDE

The same engine runs in-process inside IntelliJ IDEA, which is where most of it pays off — because a
broken key is worth catching while you are typing it, not after a deployment.

<div class="cards">
<a class="card" href="plugin/#model-key-intelligence" style="--tint:var(--c-service)"><h3>Keys become references</h3><p>Completion at every Flowable API position, Ctrl-click to the model, docs on hover, and a Search Everywhere tab that searches inside <code>.bar</code> archives.</p></a>
<a class="card" href="plugin/#key-validation" style="--tint:var(--c-invalidExpr,#be2323)"><h3>Typos caught in the editor</h3><p>A key that matches no model in the project is flagged with a fix to the nearest real key — in Java and inside model XML.</p></a>
<a class="card" href="expressions/" style="--tint:var(--c-expression)"><h3>Expressions as a language</h3><p>Both dialects highlighted, completed, documented and validated. Real Groovy and JavaScript inside script bodies. Two playgrounds.</p></a>
<a class="card" href="plugin/#java--model-linking" style="--tint:var(--c-java)"><h3>The IDE stops lying</h3><p>Find Usages from Java into models, gutter icons both ways, and a warning when a rename would silently break a model.</p></a>
<a class="card" href="plugin/#code-generation" style="--tint:var(--c-dataObject)"><h3>Less boilerplate</h3><p>A typed constants class that keeps itself in sync, data-object DTOs, and Liquibase changelogs — each previewed before anything is written.</p></a>
<a class="card" href="plugin/#flowable-design-sync" style="--tint:var(--c-channel)"><h3>Pull from Design</h3><p>Fetch app exports straight into the repository, and be told which model keys disappeared since the last pull.</p></a>
</div>

## It says what it does not know

This is the part that decides whether a tool like this is usable. Atlas distinguishes three states and
never quietly presents the third as the first:

- **resolved** — the reference ties to something real.
- **suspect** — resolved through a loose or cross-type match. Marked `≈`, and hideable in one click.
- **dynamic** — the target was an expression, so it is only certain at runtime. Marked `ƒ`.

A file it could not parse becomes a finding rather than a smaller project, and it appears in every
artifact — including a badge in the explorer header. Thirteen [health checks](checks/) run on every
project: broken references, invalid expressions, script errors, schema gaps, unused forms and
operations, and variables that are written and never read.

The [unused-variable check](variables/) is the clearest example of the principle: it counts a
*suspected* read as a read, and it publishes how many names it declined to judge — which is exactly why
the ones it does name are worth acting on.

## Start here

| | |
|---|---|
| [Getting started](start/) | Install the plugin or the CLI, and your first run |
| [What the plugin does](plugin/) | The IntelliJ side, feature by feature, illustrated |
| [The Atlas explorer](explorer/) | Every view, the search grammar, the keyboard map |
| [Generated artifacts](artifacts/) | What each of the five files is for, and `jq` recipes for the graph |
| [CLI reference](cli/) | Every flag, the launcher, exit codes |
| [For LLMs & agents](agents/) | Four sizes of context, and which to use when |
