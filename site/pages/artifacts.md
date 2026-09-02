# Generated artifacts

One run produces five files. They are not five formats of the same thing — they are five *sizes*,
meant for five different readers, and picking the right one is most of the value.

```
atlas-output/order-management/
  order-management.explorer.html   an offline interactive explorer — open by double-click
  order-management.summary.md      a few KB: apps, inventory, entry points, health
  order-management.overview.md     the full human report, every model in execution order
  order-management.graph.json      the traversable graph, for querying — not for reading
  order-management.CLAUDE.md       drop-in context for an AI agent
  order-management.diagrams/       one SVG per model that has a layout
```

Only `--all` names files after the project. A single-artifact run writes `APP_OVERVIEW.*` unless you
name the file yourself — see the [CLI reference](../cli/#output-format).

## `<project>.explorer.html` — the explorer

A single self-contained page: the CSS, the JavaScript, the embedded font and the whole graph as a JSON
island, inlined into one file with no external requests. Copy it, mail it, open it on a machine with no
network — it works. In the IDE the same file renders as an editor tab, theme-synced with the IDE. Its
footer says which Atlas version generated it and when, so a page that has travelled can still tell you
how old it is.

This is the artifact for a human who wants to *understand* a project. It has its own page:
[The Atlas explorer](../explorer/).

## `<project>.summary.md` — orientation

A few kilobytes, written for an LLM first and a human second. In order: the project's scale, a
parse-failure banner if there is one, the apps, the inventory (models counted in Design's wording, Java
by role, variables per scope, expressions by dialect), the entry points grouped by who can start what,
the REST surface, the service and messaging integrations, the Java glue wired to models, the hotspots,
the external surface, and a health block.

Use it to answer "what *is* this project" in one read. It is the right thing to paste into a
conversation.

## `<project>.overview.md` — the full report

Fourteen numbered sections, every model in **execution order** rather than alphabetical: apps,
processes, cases, decisions, forms and pages, data objects, dictionaries, AI agents and bots, the
integration surface (services, channels, events, REST), the Java glue, resolved references, unresolved
references (with the suspect and dynamic subsections separated out), the access map, the code map, the
variable and expression inventory, and every finding with its `file:line`.

It also carries the data-layer chain — service ↔ Liquibase table ↔ data object — which is the one view
that shows whether the database and the models still agree.

Use it when you need the whole picture and you are going to read it.

## `<project>.graph.json` — for querying

The complete model↔code graph. **Do not read it whole and do not paste it into a prompt** — a large
real project produces several megabytes. Query it.

It is designed to make that easy:

- A **`_schema`** key comes first and describes the file's own shape, including six ready-made `jq`
  recipes. An agent that opens the file learns how to use it from the file.
- A **`_generated`** key beside it says when the file was written and by which Atlas version, so a
  graph pasted into a ticket or kept as a pipeline artifact carries its own age.
- Every node carries **`usedBy`**, so relationships resolve in both directions without a second pass.
- It is **minified** by default, and a model's body is stored once in a top-level bucket with its graph
  node pointing there via `data.dataIn`. Together that roughly halves the file — 4.8 MB → 2.5 MB on a
  large real project. `--pretty` indents it when a human has to look.

Every node id is `<type>:<key>`, and every edge is `{s, t, rel}` plus the two honesty flags described
under [uncertain links](#uncertain-links).

```bash
# what does this process reference?
jq '.graph.edges[] | select(.s=="process:orderProcess")' graph.json

# what would break if I changed this form?
jq '.graph.nodes[] | select(.id=="form:orderForm") | .usedBy' graph.json

# every open finding, worst first
jq '.findings[] | select(.severity=="error")' graph.json
```

## `<project>.CLAUDE.md` — context for an agent

A Flowable primer *plus* this project's discovered facts, written to be dropped into a repository root
as `CLAUDE.md` (or `AGENTS.md`). It contains a starting procedure that names the project's real
filenames, the platform mental model an LLM usually gets wrong, how custom code attaches to models,
this project's inventory and conventions, its open findings, and a cheatsheet of the expression
namespaces, script bindings and platform beans that **actually exist** — which is the part that stops
an agent inventing APIs.

`CLAUDE.template.md` in the repository is the project-independent primer alone, generated from the same
source so the two cannot drift. `--claude-template` prints it without needing a project.

See [For LLMs & agents](../agents/) for how the four sizes fit together.

## `<project>.diagrams/` — SVG per model

Written by `--all` when any model carries a layout. One SVG per process, case or decision, rendered
from the model's diagram interchange — deployment `bpmndi` / `cmmndi` / `dmndi` XML, or a Flowable
Design workspace's ORYX JSON. A decision with no canvas has no layout to render, so in the IDE its
gutter icon paints the **decision table** instead.

Nothing is downloaded and no Design instance is contacted: the geometry is already in your models.

## `--slice <type:key>` — one model in context

Not a file the full run writes, but the tier between the summary and the graph, printed to stdout or to
`-o`:

```bash
java -jar cli-<version>-all.jar <project> --slice process:orderProcess --stdout
```

One node with what it uses, **who uses it**, the findings that touch it, and its own attributes — sized
for an agent that has been asked to change exactly one model. A bare key without a type matches any
type and renders every match.

## Uncertain links

Atlas distinguishes three states, in every artifact, rather than presenting a guess as a fact:

- **resolved** — the reference ties to a real node.
- **suspect** (`≈`) — resolved through a loose or cross-type match: an ambiguous Java simple name, a
  loose REST path match, a cross-type fallback, or a Java string literal that merely equals a model key
  without being passed to a key-taking API.
- **dynamic** (`ƒ`) — the reference was an expression, so its target is only known at runtime. Atlas
  resolves it when a constant backs it and otherwise records the placeholder.

Anything that could not be resolved at all is listed separately — platform beans, external REST
endpoints, missing keys — so a real gap stands out instead of hiding among the noise.
