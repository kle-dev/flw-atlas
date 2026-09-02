# Health checks

Atlas runs thirteen checks over every project it analyses. They are computed once, in `:core`, and
every surface reads the same result — the CLI status line, the summary's *Health* block, the
overview's *Findings* section, `graph.json`'s `findings` and `checks` keys, the generated `CLAUDE.md`
and the explorer's *Checks* page all agree by construction.

Two design rules are worth stating before the list, because they are why the findings are worth
reading at all:

- **Nothing is silent.** A file Atlas could not parse is a finding, not a gap. If Atlas did not
  understand something, it says so rather than quietly reporting a smaller project.
- **A check that cannot be sure stays quiet.** The unused-variable check in particular counts a
  *suspected* read as a read, and reports how many names it declined to judge — see
  [Variable analysis](../variables/).

The findings are ordered by how much they deserve your attention: broken first, then unfinished, then
noise.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/checks-page.png" alt="The explorer's Checks page: a card per check, then a block per finding kind" width="1400" height="900"><img class="only-dark" src="../assets/img/checks-page-dark.png" alt="The explorer's Checks page: a card per check, then a block per finding kind" width="1400" height="900"></div>
  <figcaption><b>The Checks page of the live demo.</b> Every card is clickable when its count is
  above zero, and every row navigates to the model it belongs to.
  <a href="../demo/explorer.html#/checks" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## The thirteen checks

| Check | Severity | What it means |
|---|---|---|
| `parseIssues` | error · warning | A file could not be read or fully parsed; as warnings, what Atlas decided not to read and a key shared by two model types. |
| `invalidExpr` | error | An expression has a structural syntax error. |
| `scriptIssues` | error / warning | A script body has a syntax problem, or calls something its context does not bind. |
| `missingRefs` | error | A model key is referenced but no model in the project defines it. |
| `changelogIssues` | warning | A Liquibase changelog is orphaned or superseded. |
| `schemaGaps` | warning | A database column and the model that should describe it disagree. |
| `suspectExpr` | warning | An expression calls a function or namespace Atlas does not know. |
| `unusedForms` | warning | A form nothing references. |
| `unusedOps` | warning | A service operation nothing calls. |
| `unusedFns` | warning | A custom expression function nothing uses. |
| `unusedVars` | warning | A variable is written and nothing reads it. |
| `unreadInputs` | warning | A variable is mapped into a called model that never reads it. |
| `guessedVars` | warning | A variable only a script mentions, by bare name. |

Each finding carries the node it belongs to, a message, and — where Atlas knows it — the file, the
element, the line and a snippet, so it is actionable rather than merely true.

## What each one detects

### `parseIssues` — files Atlas could not read

Every entry in the run's diagnostics: a model whose XML or JSON would not parse, a file that could not
be read, an archive entry that could not be opened, a Java source that could not be read, and any
failure while extracting custom functions. It also lists what Atlas decided **not** to read, as `skip`
entries at warning level — a file with a model extension that is not JSON at all (a Helm chart's
`_helpers.tpl`), a JSON in a Design export that is no model wrapper, a legacy wrapper without a body, a
process in the old editor's JSON format with no XML twin, an archive nested two levels deep, a model
file above the 32 MB limit — because a file that was skipped on purpose is no less absent from the
report than one that failed. An archive *inside* an archive (a Design export packing one `.bar` per
app) is opened one level down and its models are read like any other.

One entry is a warning rather than an error: a **key shared by two model types** — a form and a page
both called `customer`, say. Both models are read completely and a reference that states its type
(a `formKey`, a `calledElement`) reaches the right one; what stays ambiguous is anything that names the
key alone, and the variables and expressions Atlas harvests from a file, which are credited to whichever
of the two it registered first. The warning names both files so you know which pages to read with that
in mind.

This is the one check you should never carry. A parse failure does not just cost you that file — every
reference into and out of it disappears too, which makes the rest of the report quietly less complete.
That is why it is reported everywhere, including a clickable **⚠ parse issues** badge in the explorer
header.

### `invalidExpr` — the expression does not parse

An expression whose problems include at least one error: an unclosed bracket, an unterminated string,
a stray operator. It cannot evaluate at runtime, so this is a real defect rather than a style note.
Every problem on that expression is listed under this check, including any warnings it also has.

### `scriptIssues` — script bodies

Groovy and JavaScript bodies in BPMN script tasks, CMMN plan-item scripts, execution / task /
lifecycle listeners, and an action's bot script. Two families:

- **Structural** — unterminated string or comment, unclosed interpolation, unmatched or mismatched
  closer, unclosed opener.
- **Configuration and semantics** — an empty body, a missing or unknown `scriptFormat`, a format whose
  case is wrong, an unknown member on a bound root object, a root that does not exist in this script's
  context, an EL-only API called from a script, and a listener type that does not support scripts.

The count is a count of **findings**, not of scripts carrying them, which is what the CLI status line
and `stats.scriptIssues` also mean.

### `missingRefs` — a key with nothing behind it

A model references another model by key, and no model in the project defines that key. Typically a
typo, a model that was never exported, or a reference to something that lives in a different app.

### `changelogIssues` — Liquibase authority

A changelog is reported when it is:

- **orphan** — no service and no data object references it, so nothing in the models explains why that
  table exists;
- **superseded** — a later changelog provides the same table, and the finding names the successors.

### `schemaGaps` — the database and the models disagree

Per column, walking Liquibase → service → data object:

- **not mapped in service** — the column exists in the changelog, but the backing `.service` model does
  not map it, so no model can read or write it.
- **not in data object** — the service maps it, but no data object uses it.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/schema-page.png" alt="The schema gaps page: per service, a three-column table of Liquibase column, service mapping and data object field" width="1400" height="800"><img class="only-dark" src="../assets/img/schema-page-dark.png" alt="The schema gaps page: per service, a three-column table of Liquibase column, service mapping and data object field" width="1400" height="800"></div>
  <figcaption><b>Schema gaps</b>, per service — cleanly-mapped services collapse to chips, so the
  gaps are what you see.</figcaption>
</figure>

The explorer renders this as a three-column table per service, with cleanly-mapped services collapsed
to chips so the gaps are what you see.

### `suspectExpr` — an expression Atlas cannot vouch for

The expression parses, but it calls something that is not in the catalog: an unknown function
namespace, an unknown function inside a known namespace, or backend function syntax in a frontend
expression. If your project registers its own functions, tell Atlas with `--expr-allowlist` (or the
plugin's allowlist setting) and these stop being reported — see
[Expressions](../expressions/#the-allowlist).

### `unusedForms`, `unusedOps`, `unusedFns` — defined, never used

- A **form** with no incoming reference other than its app membership. Belonging to an app is not use.
- A **service operation** nothing calls.
- A **custom expression function** nothing calls.

These are the cheapest findings to act on and the easiest to ignore safely — they cost nothing at
runtime, they just make the project bigger than it needs to be.

### `unusedVars`, `unreadInputs`, `guessedVars` — variables

- **`unusedVars`** — something writes the variable and nothing anywhere reads it. The message names
  the write sites in Flowable Design's own wording, so you can find them.
- **`unreadInputs`** — the name *is* read somewhere, but not in the scope the value was written into:
  a mapping into a called model that never reads it.
- **`guessedVars`** — the only evidence for this variable is a bare identifier in a script, so Atlas
  inferred it rather than reading it from a declaration.

[Variable analysis](../variables/) explains how the direction of every variable is established and the
eight cases in which this check deliberately says nothing.

## Where to read them

| Surface | What you get |
|---|---|
| The CLI status line | The counts, in one line on stderr. |
| `<project>.summary.md` | A *Health* block: per-check counts and up to five errors. |
| `<project>.overview.md` | Section 14, *Findings*, with `file:line` for each. |
| `<project>.graph.json` | `findings` (itemised) and `checks` (per-check counts plus `open`). |
| `<project>.CLAUDE.md` | A findings summary, so an agent starts from what is already known to be wrong. |
| The explorer | The **Checks** page (`#/checks`): a card per check, then a block per finding kind, each row clicking through to the model it belongs to. |
| The IDE | The same findings, in the Atlas Hub's *Checks* tab. |
