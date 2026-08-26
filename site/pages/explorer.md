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

The URL hash is the single source of truth for navigation, so browser back and forward work, and any
view you are looking at can be copied as a link.

| Hash | View |
|---|---|
| *(empty)* or `#/overview` | The dashboard: inventory, health, hotspots, apps, entry points |
| `#/checks` | Every [finding](../checks/) in one place |
| `#/variables` | The [unused-variable](../variables/) report and what Atlas could not judge |
| `#/scripts` | Every script body in the project |
| `#/schema` | Schema gaps: Liquibase → service → data object, per column |
| `#/browse/<category>` | A category list — one per node type, Java role, variable scope and review list |
| `#<nodeId>` | Browse with that node selected. This is the permalink form |
| `#<nodeId>&q=<term>` | …with the search term that led there highlighted |
| `#<nodeId>&e=<elementId>` | …with a specific model element opened |

An unknown route or an unresolvable node id falls back to the overview rather than showing an error.

## Browse categories

The sidebar is generated from the graph, so it only ever shows categories this project actually has,
grouped into **Models · Integration · Code · Expressions · Checks · Variables · Access · Other**:

- one per **node type** present — process, case, decision, form, page, data object, service, agent,
  channel, event, action, bot, query, template, sequence, security policy, endpoint, method, Liquibase
  changelog, signal, message, error, escalation, topic, group, and more;
- one per **Java role** found — controller, delegate, listener, service, repository, configuration,
  component, bot;
- one per **variable scope**, plus the variables that travel through an in/out mapping;
- **external** buckets — API, route, missing, library;
- and the **review lists**: unused forms, invalid expressions, suspect expressions, changelog issues,
  guessed variables, unused variables, unread inputs, script syntax.

## The detail panel

Selecting a node gives you its attributes, its diagram, an ego graph of its immediate neighbourhood,
type-specific sections — and then the two lists that are the point of the whole thing:

- **Uses / references** — what this node points at.
- **Used by / referenced from** — what points at it.

Both directions, always, for every node type. That is the question a model file cannot answer on its
own, and it is why the graph carries `usedBy`.

Every section remembers whether you left it open, per section, across reloads. Up to twelve nodes can
be open as **detail tabs**, which are viewports with their own history rather than pins.

On a form or page, a row in **Fields** expands when the component does something: the model a button
invokes (as a chip you can follow), the payload it sends and stores back, the `{{binding}}` its result is
stored in, a REST button's endpoint with its verb and response path, an expression button's expression and
the interval it re-runs on, whether it fires by itself, and the note the modeller left on it. A plain
input has nothing to add and stays a one-line row.

Two kinds of honesty live on that row. **Hidden**, **disabled** and **not submitted** are stated on the
row itself, because a hidden button that auto-executes is a worker nobody presses and you should not have
to expand anything to learn that — when the state is a condition instead, the condition is in the body.
And when a button is configured to send the whole payload or store the whole response, that is said
first and the mapping it overrides is marked unused, because the runtime never reads it.

## Diagrams

Processes, cases and decisions render their diagram inline, from the layout already in your models —
deployment `bpmndi` / `cmmndi` / `dmndi`, or a Design workspace's ORYX JSON. Nothing is downloaded and
no Design instance is contacted.

Drag to pan, ⌘/Ctrl-scroll to zoom, `−` `fit` `+` to step, `⤢` for full screen. Clicking an element
opens a draggable, resizable info card — and the `⌖` buttons in the detail panel work the other way
round, locating an element on the diagram from its row in a list.

A decision table has no canvas, so there is nothing to lay out: its rules render as a real table
instead.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/scripts-page.png" alt="The script tasks page: every script body in the project, grouped by model, with its language, variables and problems" width="1400" height="900"><img class="only-dark" src="../assets/img/scripts-page-dark.png" alt="The script tasks page: every script body in the project, grouped by model, with its language, variables and problems" width="1400" height="900"></div>
  <figcaption><b>Every script in the project, on one page</b> — grouped by model, with the variables
  each one touches and a badge on the ones with problems.
  <a href="../demo/explorer.html#/scripts" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## Search

`⌘K` (or `/`) opens the palette. It is a proper search engine, not a filter:

- **Every term must match, in any order.** `order form demo` finds the same thing as `demo form order`.
- **Quoted phrases** are a hard, contiguous requirement.
- **Facets** narrow inline: `t:` / `type:`, `file:`, `key:`, `in:`, `id:`.
- **`id:` looks an element up by its identifier** — `id:save-button` finds the model that declares it and
  opens that element's row. It matches identifiers only, never a caption that happens to read *Save*.
- **Word boundaries are understood** — `demo d05`, `demo-d05` and `demo_d05` are the same query, because
  tokens split at camelCase and letter↔digit boundaries as well as at punctuation.
- **It searches inside models**, not just their names: element ids, in/out parameters, form fields,
  columns, permissions, bot keys, agent tools, REST endpoints, and a deep walk over each node's data.
- **It tells you why a row matched**, and on zero results it suggests the nearest real names.

Results are ranked — an exact name beats a prefix beats a substring, a model outranks a string literal,
and a heavily-referenced node outranks an isolated one. Facet chips appear in two tiers, section first,
then category. An empty query shows your eight most recent selections.

The list filter inside a category uses the same engine, and tells you how many matches exist *outside*
the category you are in, with a button to widen the search.

## Keyboard

| Keys | Where | Action |
|---|---|---|
| `⌘K` / `Ctrl+K`, or `/` | anywhere | Open the search palette |
| `↑` `↓` | palette, list | Move |
| `⇧↑` `⇧↓` | palette, list | Extend the marked range |
| `Enter` | palette, list | Open — or open everything marked, as tabs |
| `⌘/Ctrl+Enter` | palette, list | Open in a background tab |
| `Space` | list | Toggle the mark under the cursor |
| `⌘/Ctrl+A` | list | Mark every rendered row |
| `Home` / `End` | list | First / last row |
| `Escape` | palette, list | Clear marks, then close |
| `Alt+1`…`Alt+9` | browse | Activate that detail tab |
| `Alt+[` / `Alt+]` | browse | Previous / next tab |
| `Alt+W` | browse | Close the active tab |
| `+` `-` `0` | diagram (full screen) | Zoom in, out, fit |
| `Escape` | diagram, info card | Close |

Tab shortcuts are Alt-based on purpose: browsers reserve ⌘/Ctrl+1…9 and ⌘W for themselves.

## Badges and honesty markers

The explorer never presents a guess as a fact:

- **`≈` suspect** — the link was resolved through a loose or cross-type match. The chip is dashed.
- **`ƒ` dynamic** — the reference was an expression, so its target is only certain at runtime.
- The toolbar's **`≈` button** hides every uncertain link at once, so you can see what is left when only
  the certain relationships count. It appears only when there are any.
- **`⚠ N parse issues`** in the sidebar footer jumps straight to the parse findings. A file Atlas could
  not read is never silent.
- Liquibase changelogs carry **live / superseded / orphan**, and services carry per-column schema
  coverage badges.

## Themes

Light by default, with a `☀ / ☾ / ◐` toggle that cycles light → dark → auto and is remembered. Inside
the IDE the page starts in `auto` and follows the IDE's theme live, including a theme switch while it is
open — but an explicit choice you make in the page still wins.
