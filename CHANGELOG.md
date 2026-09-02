# Changelog

Release notes for the Flowable Atlas IntelliJ plugin and CLI (one Gradle version drives both).

> **Pre-release.** Atlas is on the `0.x` line: it is used internally and has no
> stability guarantee across versions yet. Behaviour, settings and generated-artifact
> formats may change between minor releases. Version numbers below 0.13.0 were never
> published outside the team, which is why the history has gaps.

<!-- This file is the source of truth for the release history. Edit it here, then run
     `./gradlew :core:updateGoldens` to regenerate the plugin descriptor's <change-notes> from its
     newest entries (that field is capped at 65535 characters, so it holds a window, not everything).
     See ChangelogSyncTest. -->

## 0.21.0

- **Every node type has a face.** A process, a form, a service and forty other kinds of node were told
  apart by a coloured dot, and forty hues nobody can tell apart is no distinction at all. Every place a
  node appears — the sidebar, the browse list, the chips, the detail tabs, the breadcrumb, the search
  results — now carries a small stroke icon for its type ([Lucide](https://lucide.dev), ISC) in the
  type's colour, and in the collapsed sidebar rail the icons are the navigation. They scale with `A−` /
  `A+` like the text beside them. In the same pass every label that names a section or a column speaks
  in one voice — Geist, small, semibold, tracked capitals — and monospace is reserved for what a machine
  reads: keys, paths, code, expressions. Section headings, list heads, table headers, pills and filter
  chips used to pick their own face and tracking, and the sidebar footer read like a terminal.
- **The chrome gets out of the way.** The top bar and the logo row are 48px instead of 64, the search
  field and the buttons sized to match, and there is one theme toggle — in the top bar — instead of one
  there and another in the sidebar footer. That footer is two rows on a grid now (project · parse-issue
  chip / *Atlas 0.21.0 · 3 days ago* · `A−` `A+`): the old single row overflowed the 240px sidebar as
  soon as the version stamp grew a date, squeezing the project name to one letter and pushing the
  buttons out over the page. A node's detail page opens with one sticky bar — its kind on the left,
  *back* / *expand all* / *copy link* on the right — instead of a row of buttons above a kind chip above
  the title. Smaller things: the attribute grid no longer shows a grey box where its last row runs out
  of cells, list rows no longer animate in on every re-render, and the last five hard-coded font sizes
  now follow `A−` / `A+` like everything else.
- **The sidebar folds, and fits a narrow window.** Every group header — Models, Integration, Code,
  Expressions, Checks, Variables, Access, Other — is a button that folds its entries, and the fold is
  remembered, so a project with forty variable scopes need not show them on every visit. A folded group
  whose entry is the active one marks that on its header and stays folded; the sidebar never reopens
  itself behind your back. The keyboard walks it too: `↑` `↓` skip folded entries, `←` folds the group
  you are in, `→` unfolds a header. Below 800px — a split editor, a narrow tool window — the category
  list used to wrap into fourteen rows of chips above the content; it is a picker beside the search button
  now, one row. The UI test covers both: the desktop run folds and re-renders, a second run at 800×600
  picks a category.
- **The overview reads top to bottom.** The thirteen health cards — the same shape thirteen times, a
  28px number for a count of one — are a list: one row per check with its tone bar, count, name and
  one-line reason, worst first, the clean ones folded under a single line. The four inventory cards are
  one strip with a chip per node type the report found, each opening that type's list. Health sits beside
  the hotspots and the apps beside the entry points, so the four things a reader came for are above the
  fold on an ordinary screen. The Checks page and the unused-variables report open with the same list.
- **A node's neighbourhood reads left to right.** What the node uses stands in a column on the left, what
  uses it on the right, the node in the middle, and the arrows point the way each reference goes. The
  radial star it replaces put every neighbour on a circle and told direction apart by dashing, which
  nobody read; it also took 340px whatever it showed and was the one collapsible block the page did not
  remember. The drawing is a section now like every other — remembered, part of *expand all* — the
  most-referenced neighbours come first, each side stops at twelve with a *+N more* that opens the full
  list below, and every neighbour carries its type icon and is a link.

## 0.20.0

- **A model's "Uses — variables & expressions" section is back.** The generator strips the `_uses` map
  from the explorer payload — correctly, it is a byte-for-byte transpose of the `usedBy` lists every
  variable, expression, binding, string literal, custom function and service operation already carries —
  but the detail panel still read that key, so for a whole run of releases no process, case or form
  listed what it touches. The page now rebuilds the map from those `usedBy` lists on first use; the
  payload did not grow by a byte, and the release build fails if the section's builder ever goes missing.
- **A reference lands on the model of its own type.** The graph looked every target up by key alone,
  first model registered wins — so with a process and a form both called `orderX`, a `formKey` of `orderX`
  drew a clean process → process edge, although the resolver had already worked out the right type. The
  resolved type now travels with the reference and the edge follows it; the key-only map is the fallback,
  not the rule. In the same pass, two models of different types sharing one key both survive — a form and
  a page, or a query and a template, live in one result bucket and the second used to be dropped as a
  "duplicate" without a word — and the shared key is reported once, as a `parseIssues` *warning* naming
  both files, because a key-only lookup (and the harvested variables and expressions) can still only go
  to one of them.
- **A file with several processes credits each one with its own text.** The raw-text harvests — every
  `${…}` and `{{…}}`, every `${bean.method()}` call, every declared or mapped variable — worked on the
  whole file and attributed it to every model in it, so a deployment `.bpmn20.xml` holding two processes
  gave each the other's expressions and variables: `usedBy` inflated, a bean-call edge from a process that
  never calls the bean, and a variable written in one process and read in the next judged in the wrong
  scope. Each process, case or decision now gets the text inside its own element; only what stands
  outside all of them — the definitions header, its messages and signals — still belongs to every one.
- **Nothing is dropped silently, and an archive inside an archive is read.** A Design export that packs
  one `.bar` per app produced a clean run with zero models: the inner archive matched no model extension
  and was skipped without a word. It is opened now, one level down, and its models carry a
  `export.zip!apps/inner.bar!processes/x.bpmn` label the diagram renderers resolve too. Everything else
  Atlas decides not to read leaves a `skip` diagnostic behind, at warning level because nothing failed —
  a file with a model extension that is not JSON at all (a Helm chart's `_helpers.tpl` used to be a
  parse *error*, and its `{{ }}` were harvested as frontend bindings), a JSON in an export that is no
  model wrapper, a legacy wrapper without a body or of a type Atlas has no parser for, a process in the
  old editor's JSON format whose XML twin never turned up, an archive nested two levels deep, a model
  file above a 32 MB limit — where each of those used to be indistinguishable from an empty project. An
  unreadable Java source costs that one file instead of aborting the run with a stack trace, and a model
  whose diagram could not be produced says so on its page (`diagramError`) and on the CLI, instead of
  looking like a model that simply has no layout.
- **Test code is not the project.** Java under `src/test`, `src/integrationTest` and any other test
  source set is no longer scanned: a JUnit class calling `setVariable("foo", …)` registered a production
  write, so a variable only a test ever set could be reported as written-but-never-read, and a test
  `@RestController` became an endpoint the models could supposedly reach. Models under
  `src/test/resources` are still read — a test process is a model somebody has to keep in step.
- **`\${…}` is not an expression, and "not judged" is no longer silent.** A backslash before the dollar
  sign — in a Groovy string, a Java string, a JSON body — means *literal, do not evaluate*; Atlas
  harvested it like any other expression and validated it, which could only ever come out wrong. It is
  skipped now. And an expression whose harvested body still holds a `{` (the harvester may have cut it
  short at the first `}`) gets no verdict, as before — but it is counted (`stats.exprSkippedNested`) and
  its page says *not validated* and why, so an unjudged expression cannot pass for a clean one.
- **The explorer fails loudly, and its links cannot freeze it.** Any error during the page's boot —
  a truncated data island, a malformed node — left the *Loading…* overlay up forever with no word; the
  overlay now shows the error and what to do about it. A malformed `%` in a hand-edited or truncated
  link threw out of the router on every navigation, freezing the page for good; an undecodable part
  resolves to the overview instead. Following a chip out of a filtered list into another category
  carried the filter text along and often read *Nothing here*; the filter is per category. When a
  followed link pushes the tab strip past twelve, the tab that made room is named — the other opening
  path always said so, this one destroyed the oldest tab silently. Recents are kept per report, so two
  reports on one machine no longer eat each other's eight slots. An agent page no longer reads
  *Vendor / model: /* or *API endpoint: undefined*, a custom function without a namespace no longer reads
  `namespace undefined.*`, an endpoint without a handler no longer shows a bare `#`. The Checks page's
  *open the list* for unused operations and unused custom functions opens a review list of exactly
  those, instead of the full category.
- **Shortcuts, copying and the fullscreen diagram stop getting in each other's way.** Alt+←/→ and
  Alt+W cycled and closed detail tabs even while the cursor sat in the list filter — on a Mac that is
  word-wise caret movement — and now leave text fields alone. *copy link* was the one copy button that
  bypassed the IDE's clipboard bridge and fell through to a prompt inside the embedded viewer. ⌘K
  opened the search palette *underneath* the fullscreen diagram, so you typed into an invisible input;
  searching leaves full screen first.
- **Diagrams re-fit, remember the element in the link, and work from the keyboard.** The drawing kept
  its scale when the panel changed width — small in a tall box, or clipped in a narrower IDE tool
  window — and re-fits now unless you zoomed by hand; full screen fits the height too. Clicking an
  element puts it in the link (`#<node>&e=<element>`), and following such a link locates the element on
  the canvas, not only in its rows. Every shape is a keyboard stop with a name for a screen reader, and
  Enter or Space opens its card. A cancelled drag no longer leaves the diagram panning with no button
  held, and each info card's size observer is disconnected with the card instead of piling up.
- **The CLI can fail a build.** Every run exited 0 whatever it found, so Atlas could describe a broken
  project but never stop one — and none of the four reference projects had a model check in CI.
  `--fail-on error`, `--fail-on warning`, or a list of check ids (`--fail-on missingRefs,invalidExpr`)
  makes the run exit 1 when a finding matches, *after* writing every artifact, so a pipeline gets the
  report and the red build; an unknown value is a misuse. Three smaller truths on the way: `java -jar
  … --help` prints the usage instead of exit 2 (only the launcher had a help), `-v` lists the parse
  issues the status line has been counting all along instead of being parsed and never read, and
  `--all --slice` is the argument error every other flag conflict already was rather than a silent win
  for `--all`.
- **From the explorer straight into the code.** Inside IntelliJ the embedded explorer offered a
  clipboard bridge and nothing else, so the source path it shows for every model and Java class, and the
  line it shows for every method and REST handler, could be copied but not followed. The plugin now
  injects a second bridge: `↗` beside a source path opens the file in an editor tab, a `:line` opens it
  at that line, a model inside a `.bar` opens read-only from the archive. Read the model here, edit the
  code there — the seam Atlas is built on, one click wide. The same page in a browser shows none of it,
  because it could not honour the click.
- **A reload brings the list back as you left it.** The link carried the view, the category, the node
  and the search term — not the filter you had typed nor the sort you had picked, so coming back to a
  report mid-investigation cost you the whole setup. Both travel in the link now (`&f=`, `&s=`), written
  as you type or pick without a history entry, and a category change resets them instead of carrying
  "Most referenced" silently into the next list, where it had been overriding the relevance ranking of
  every later filter.
- **Text size, and a list you can resize.** `A−` / `A+` in the sidebar footer step every font size on
  the page between 85 % and 150 % — the IDE's embedded browser applies none of the IDE's font scaling,
  so the 10–11 px metadata stayed 10–11 px on a dense monitor. The split between the list and the detail
  panel, fixed at 330 px, is a drag handle now (arrow keys nudge it, Home resets it) and is remembered.
- **A report says when it was made.** The explorer's payload carried the project name, the graph and
  the counts — not the moment it was generated, so a page mailed to a reviewer could be a day or six
  months old and could not say. The page now carries `generatedAt` and `atlasVersion`; the sidebar
  footer reads *Atlas 0.20.0 · generated 3 days ago* with the exact time on hover, and `graph.json`
  gains a `_generated` block beside `_schema` for whatever reads it by machine.
- **`@Bean` factory methods are beans, and Kotlin sources are read.** Bean resolution knew only the
  stereotype annotations on a class — `@Component`, `@Service`, `@Repository`, `@Named` — so a delegate
  registered the standard way for code you do not own, a `@Bean` method in a `@Configuration` class, was
  invisible: every model naming it resolved to nothing. Such a bean now resolves to *its method's line*,
  named after the method unless the annotation says otherwise. And `.kt` files go through the same pass
  as `.java` — package, `class`/`object`/`enum class`, the supertype list after the primary constructor,
  `fun`, `val`/`var` properties and `const val` constants are all read — so a Kotlin `JavaDelegate` or
  `@RestController` is a real node with real edges instead of an unresolved external. A bean name two
  classes both claim resolves, but the edge is flagged suspect, as an ambiguous class name always was.
- **The model index has one build policy, and it no longer freezes the editor.** The index was built
  by whoever asked first — and when that was the unused-declaration inspection, a reference resolve or
  Find Usages, the whole model scan ran under the daemon's read lock, which on a large repository is
  "the IDE freezes while I type". Five other consumers each launched their own background build on a
  cold index, two never asked for one at all, and nothing built it when a project opened, so the Atlas
  Hub greeted you with *Not scanned yet* until you clicked Rebuild or happened to open a Java file.
  Now: the index starts building when the project opens; every read-context consumer reads the cache
  and calls `ensureBuilding()` — one background build for any number of askers — and answers "nothing
  yet" until it lands, at which point the editor's markers, hints and inspections are re-run. Only
  completion and the explicit actions may wait for a build. The Hub says *Scanning the project…* and
  resolves itself.
- **Inspections stop re-reading the model files per literal.** The key inspections rebuilt the set
  of known keys for every literal they looked at, the value-field inspection re-read and re-parsed the
  backing `.service` model for every `value("…")` in a file — a DAO with twenty query builders parsed
  the same JSON twenty times per highlighting pass — and the Liquibase coverage inspection parsed every
  `.service` model in the project for every changelog. All of that is now computed once per index
  snapshot and dropped with it.
- **The settings pages say what they do.** Unticking every artifact on the Generation page left an
  empty selection — the checkboxes mutated the set in place and skipped the setter where "an empty
  selection falls back to the explorer HTML" lives — so *Generate Atlas Explorer…* wrote nothing and
  reported success; the page writes through the setter now. The four folder fields documented as
  project-relative wrote an absolute path whenever the browse button was used, which a pull then
  resolved outside the repository; the button writes the folder relative to the active project. The
  constants class name is validated as you type, and renaming it no longer silently ends auto-refresh —
  the file generated under the old name is named, with a *Generate now* action for the new one. The
  inlay-hint, key-recognition and Java-expression toggles re-run highlighting when applied instead of
  waiting for you to type into every open file, and the "invalid class name" balloon opens the page the
  class name is actually on.
- **A stale explorer says so — after any change, not only a Design pull.** The Hub's "models changed
  since the last generation" row compared the page against the last pull alone, and only with a Design
  connection configured, so a team that gets its models through git never saw it, and the explorer tab
  itself never said anything. The index now carries the newest modification time of the models and
  archives it scanned; the Hub row compares against that (or the pull, whichever is newer) with no
  connection required, and an open explorer tab shows a banner with *Regenerate* on it. Both clear
  themselves when the page is regenerated.
- **A pull says what it brought, not only what disappeared.** The post-pull balloon named the model
  keys that vanished project-wide — the code-impact signal — and nothing else, so "what did I just pull?"
  had no answer. Each pulled app now reports how many of its model files changed, were added or removed
  against the export that was on disk before (by content, entry by entry), naming them when there are
  few; a first export says so. The progress bar advances per app instead of sitting still for the whole
  download, and a pull refused with HTTP 401 — the expired token, the changed password — offers *Sign out
  & retry*, which clears the stored credential, opens the connection to sign in, and pulls again.
- **One shortcut, and a context menu on the models.** The reference page said it plainly: no action had
  a shortcut and there was no context menu, the Hub being the surface. That stays true for everything
  except the two that earn an exception: **Search Models…** has `Ctrl+Alt+Shift+M` (`⌥⇧⌘M`), because it
  is the one action that competes with Shift-Shift for the hand, and a right-click on a folder, a model
  file or an archive in the Project view offers *Generate Atlas Explorer…* and *Search Models…* — on
  nothing else, so the menu stays as short as it was on every other file.
- **One scope for everything, and the Hub says what it could not read.** With a sub-project chosen in
  a monorepo, the model index narrowed itself to it — but the Search Everywhere tab's full-text half,
  Find Usages into models and the REST-endpoint gutter still walked the whole repository, so one query
  answered from two scopes. Every walk goes through one definition now. The Hub's index line names the
  scope when it is narrower than the repository, and lists the `.bar`/`.zip` archives in scope it could
  not open — which were logged at debug level, leaving a repository whose only archive is unreadable
  looking exactly like one with no models.
- **Generating the explorer opens it.** The first generation ended in a balloon whose *Open in IDE*
  action expired with the balloon; the page you asked for now opens as a tab the moment it is written —
  from the menu action as well as from the editor's Regenerate. In the Hub, *Open in Browser* is hidden
  while there is nothing to open instead of quietly turning into the generate dialog, and a double-click
  on an artifact where neither an embedded nor an external browser exists (a Remote Dev backend) says so
  rather than doing nothing.
- **Menu actions stay usable while the IDE indexes, and never freeze it.** Every Atlas action greyed out
  in dumb mode — right after opening a project, when *Pull from Design* and *Rebuild Model Index* are
  what you want and neither needs the IDE's indices; the actions that need no PSI are `DumbAware` now.
  *Open Atlas Explorer* walked the project six levels deep on the UI thread when the output folder was
  empty (a visible freeze on a cold monorepo) — it searches in the background. The generation-failure
  log opens as an in-memory tab instead of a temp file written on the UI thread, sub-project detection
  runs once at a time instead of once per Hub event, and two dead fields left the Hub.
- **The error reporter is finally in the IDE.** 0.13.0 announced *Report a problem straight from the
  error dialog*, and the reporter was there — but never registered in the plugin descriptor, so the button
  never appeared and the reference page had to carry a "known gap" paragraph. It is registered now:
  **Report Flowable Atlas Problem…** copies the report to the clipboard and opens the issue tracker,
  transmitting nothing on its own. The reference page also stops claiming 2026.1 support: 2026.2 has been
  the floor since 0.17.
- **Two `overview.md` lines stop leaking the generator's internals.** An AI agent's heading read
  `(None)` when the model states no type, and its model line `model: None/None (temp None)` when the
  settings are absent — both omit what is unset now. A data dictionary's line printed its types as a
  Python list literal, `types: ['Address', 'Customer']`; it is a code list. Both surfaced the moment
  the test fixture gained an agent and a dictionary — it now carries every model type the parsers
  know, plus a Design export with a `.bar` nested inside it.
- **An expanded sub-process no longer wears the collapsed `[+]` marker.** The diagram painted it on
  every sub-process, over the children an expanded one lays out inside itself. It is read from the
  diagram interchange now (`isExpanded="false"`, or Design's collapsed stencil) and drawn only there.

## 0.19.0

- **Ten model types stop being name-only stubs** — queries, sequences, SLAs, templates, knowledge
  bases, variable extractors and document (content) models are parsed structurally: a query's
  parameters, sort keys and search-template body; a sequence's number format and counters; an SLA's
  due-date targets, escalations and lifecycle actions (a start-process/start-case escalation is a real
  model reference now); a template's variations and their actual text; a knowledge base's retrieval
  settings (credentials never leave the model — only their *type* is kept); a variable extractor's
  extracted variables (each an honest "write whose readers are out of reach"); a document model's
  per-action forms, permissions and variables. Palettes keep their `Palette-Id`/`title` identity
  instead of coming out as `key: None`. Master data and dashboard components stay generic for now —
  no corpus in reach contains a body to design against.
- **What the parser knows, the page shows — structurally guaranteed.** Every parsed attribute now
  either has a renderer or lands in a collapsed **Other attributes** key/value tree on the detail
  page, tracked at render time, so a future parser field is visible by default. The Kotlin mirror:
  `PayloadCompletenessTest` fails the build when a parser emits a container key the explorer payload
  would silently drop — each key must be allowlisted (visible + searchable) or consciously stripped
  with a reason. Six parsed-but-invisible keys render now: a process's full **sequence-flow
  topology** (default flows marked), its declared **data objects**, its model-level **references**
  (SLA, security policy, event, channel, dictionary, sequence), an app's **pages**, a decision
  service's **decisions**, a form's **subforms**.
- **BPMN/CMMN extraction closes its attribute gaps** — boundary events name the activity they hang
  on (and whether they interrupt it), conditional events keep their condition, gateways and flows
  keep the default-flow marker, `flowable:async`/`skipExpression` surface when set, lanes are
  extracted (and searchable via `label:`/`id:`), event payloads carry type/required/correlation
  instead of bare names, DMN columns keep their declared types and allowed values, service-operation
  parameters keep `required` and defaults, data-dictionary types keep their properties, form selects
  keep their options and every localised caption (each searchable as a `label:`), and agent prompts
  are no longer cut off at 200 characters.
- **Search: a facet hit lights up what it matched** — the bound value of `label:` / `desc:` / `key:`
  / `id:` highlights in the result rows like any term, and the row's hint leads with the matched text
  itself (`label · Recalculate @orderTotal`). The detail page highlights faceted and multi-word
  queries too — it used to look for the raw query as one substring, so exactly the queries the engine
  is best at highlighted nothing there. "Did you mean" matches each word on its own and reaches
  captions, not just names and keys.
- **The browse list explains its hits** — a row matched through a script body or a mapping says why
  (`script · stampTask`), exactly like the palette, and clicking it opens the detail panel with that
  element revealed and highlighted.
- **The search dialog is keyboard-complete** — Tab cycles the dialog's own controls (facet chips,
  their ×, "Show more", "Did you mean") instead of being swallowed, the page behind the open dialog
  is inert to focus and screen readers, arrows move the selection without rebuilding the whole list
  (noticeable on 3000-node reports), and the `/` shortcut is finally written down — on the search
  button, in the zero-result tip and on the empty detail panel.

## 0.18.1

- **Labels and descriptions are searchable, and ranked as what they are** — a form field's label, a data
  object column's label, an outcome button's caption, a permission's label, a BPMN/CMMN element's name and
  a decision table's column headers are one ranked field (`label:`), just under the node's own name; the
  prose somebody wrote *about* a thing is another (`desc:`, also spelled `description:` / `doc:`) —
  Design's model **Description**, `documentation` on a process/case *and* on each of its elements, a form
  component's description, a DMN rule's annotation. Both were reachable only through the free-text walk
  before, at the same weight as a script body, so searching for a caption a user reads on screen ranked
  below any script that happened to mention the word. Both are collected by field *name* during the walk,
  so a label a parser starts emitting somewhere new is searchable without a change to the engine.
- **A facet is a filter: it answers with all of them, and with nothing else** — you have named the field,
  so `label:` matches captions only. The display name of a variable, an expression, a binding, a string
  literal, a Java class, a method, a REST endpoint, a changelog, a worker topic or a group is an
  identifier Atlas synthesised out of something else, not a caption anybody wrote; those stay findable by
  name, key and free text, but they are not labels. A space after the colon is fine — `desc: approval` is
  the same query as `desc:approval` — and nothing about a query is case-sensitive: not the terms, not the
  facet name, not its value. The row says *why* it matched with a field of the kind you asked for, and
  `label:save` opens the field that reads "Save" rather than leaving you on the form.
- **Design's model Description is no longer thrown away** — every parser built its own record and only the
  app parser kept the `description` the modeller wrote, so for a process, case, form, page, service, data
  object, action, agent, channel, event, dictionary, policy or decision it never reached the report: not
  shown, not searchable. It is carried through now and shown at the top of the detail panel for every
  model type that has one.
- **A form in a Design workspace export has its fields** — Design persists a model's body as an escaped
  JSON *string* (`editorJson`), and a form's components are reached by walking maps, so the string was
  never opened: every form and page exported that way came out with an empty field list. No ids, no
  labels, no descriptions, no outcomes. A `.form` from an app zip or a deployment bar was never affected,
  and neither was an export that happens to nest `editorJson` as an object, which is why it survived this
  long. The model's own metadata header — including its description — is kept rather than overwritten.
- **The result page is shared out across the sections** — searching for a form field's caption also
  matches whatever else carries the word, and a page cut off purely by score could fill itself with one
  kind and leave the group holding the answer undrawn. Every section with hits now gets a share of the
  page, and a section that runs out leaves its share to the others, so a result that really is all one
  kind still fills the page with it.
- **`scripts/search-diagnose.mjs`** — point it at an existing report and a query and it separates the three
  things a search failure can be: the string never reached the report, it is there but does not match, or
  it matches and the page does not draw it. It uses the engine embedded in *that* report, so it diagnoses
  the version in the file rather than the checkout's. Developer tool; not shipped in the plugin or the CLI.
- **A facet takes a quoted value** — `label: "Customer name"` is the only way to ask a facet for a
  multi-word caption, and it fell apart: the phrase pass stripped the quotes before the facet pass ran,
  the colon was left with nothing to bind, and the word `label` degraded into a free-text term — the
  query answered with whichever nodes happened to *mention* the word "label" instead of everything that
  carries the caption. The quoted value now binds to its facet first, stays contiguous like any phrase,
  and works spaced, unspaced and in any casing. (`"label: thing"` entirely inside quotes is still a
  literal phrase.)
- **The palette teaches its own filters** — before you type, the bar under the input offers one chip per
  facet (`label:`, `desc:`, `key:`, `id:`, `type:`, `file:`, `in:`), each glossed with what it searches;
  clicking one types the prefix for you. A facet typed through the colon but not given a value yet says
  what it is waiting for instead of searching for the word `label`. And every facet that binds shows as a
  lit chip beside the result count — proof the filter took effect, a reminder it is still on, and, when
  clicked, the way to remove it from the query.

## 0.18.0

- **A form's buttons say what they do** — the Fields list named a button and its type, and stopped there.
  The form's references said *an action is triggered*; which button triggered it, with which values,
  under which condition, and where you land afterwards were nowhere on the page. A button row expands in
  place now: the model it invokes as a chip you can follow, the payload it sends and stores back, the
  endpoint of a REST button with its verb and response path, an expression button's expression and the
  interval it re-runs on, whether it fires by itself or runs even while disabled, the scope an action runs
  against, and where it navigates when it returns. A plain input has nothing to add and stays the
  one-line row it always was.
- **A hidden button is no longer drawn as a button** — `visible: false` is the commonest configuration a
  button has: **252 of 338** in one real project, because a hidden button that auto-executes is how a form
  calls an endpoint or computes a value on its own. Atlas listed them exactly like a button someone
  presses. Every component now states the three things that decide whether it is there at all —
  **hidden**, **disabled**, **not submitted** on the row itself when the model settles it, and the
  condition in the body when it is an expression (`visible when {{…}}`). It applies to inputs too: a
  hidden field was just as invisible.
- **Where the result is stored** — an expression button's computed value and a REST button's response land
  in the button's own `{{binding}}`: 265 buttons in that project write one. The row names the target, and
  the write is now in the variable graph, so a variable that only a button ever sets is no longer
  invisible on both counts. An action button's `value` is Design's placeholder `"."` and is deliberately
  *not* read as a target.
- **Localised captions, and the modeller's own note** — a caption may exist only as an `i18n` override,
  which left some buttons with no name at all in the report; that override is now the fallback. And a
  component's `description` — *"Disabled for privileged users, because …"* — is shown where it explains
  everything else on the row.
- **A full-payload button no longer shows a map it never uses** — an action button can send the whole form
  payload (or the whole scope) and store the whole response, in which case the runtime ignores the send
  and response maps entirely. Atlas rendered those maps as the contract regardless. The override is
  stated first now, and the map it beats is marked as unused rather than presented as the truth.
- **Buttons with no caption are on the page at all** — a component needed a `label` or an
  `extraSettings.text` to be listed, which is not something a button has to have: measured over one real
  project, **50 of 87 REST buttons and every link button** are icon-only or captioned by their `value`, so
  they were dropped from the model data — invisible in the report, unsearchable, and unable to explain the
  action reference they were the source of. A button is now listed on its `type` alone, and takes its
  caption from `value` when that is where Design put it.
- **`id:` searches identifiers, and only identifiers** — ⌘K gained a facet beside `t:` / `key:` / `in:`:
  `id:save-button` finds the model that declares that element and opens its row, and it will not match a
  *caption* that reads "Save", which is what made looking a button up by its id hopeless before. What a
  button invokes and the expression it evaluates are indexed too, so `notifyCustomerAction` finds the
  forms whose buttons call it.
- **An expression button's result counts as a write** — every expression button hands its value to its own
  `{{binding}}`, and buttons were excluded from the variable graph wholesale, so that target looked
  neither read nor written (178 of them in one real project). The write is recorded with the button as its
  site; the read side needed nothing, as whoever renders the binding was already picked up.
- **An action reference written the new way resolves** — the Design editor also persists a button's action
  as `{key, id}` rather than a bare key, the shape already unwrapped for process, case and query
  references. That one was not, so such an export produced a reference key nothing could match.

## 0.17.1

- **Atlas needs IntelliJ IDEA 2026.2 from here on** — it used to be *compiled* against 2026.1 and only
  *verified* on 2026.2, which kept a single ZIP loadable on the older branch at the price of never being
  able to use anything the newer one added. Nobody on the team runs 2026.1 any more, so that trade stopped
  paying: compile target, sandbox and JetBrains' Plugin Verifier now all sit on 2026.2, and an IDE below
  that declines the plugin outright instead of loading a build nobody checked there. The visible
  consequence is the requirement itself; everything else is the same plugin.
- **No more IDE error after opening a model out of a `.bar`/`.zip`** — the IDE's Reader Mode reformats
  read-only files *virtually*, and on 2026.x that machinery throws on **minified single-line JSON**, which
  is exactly what a Flowable Design export is. It looked like an Atlas defect because Atlas is what had
  just opened the file: the *Flowable Model* search tab is the only thing in the IDE that reaches inside an
  archive. Atlas now switches the virtual reformatting off for model files — where it could never have
  applied anything anyway, the file being read-only. The defect itself is the platform's.

## 0.17.0

- **"Open Environment in Browser" works under Remote Dev** — it was greyed out for every remote
  developer, because one availability check served two different questions. Opening a generated
  `explorer.html` really is impossible from a Remote-Dev backend: the file is on the backend's disk and
  the client cannot see that path. Opening a **URL** is not the same thing — it means the same on the
  client, and `BrowserLauncher` is precisely the API that routes it there, which is why Atlas uses it.
  The two questions are now asked separately.
- **The generated page is named after the project it describes** — with the IDE opened on a folder that
  *contains* the Flowable project, the save dialog proposed the parent folder's name, which says nothing
  about what is in the page, while the Atlas Hub had been analysing the sub-project all along. The name
  follows the analysed scope now, and falls back to the project's own name when that scope is the whole
  repository — so a renamed project keeps its name, and a sub-project that was moved away never names a
  folder that is gone.
- **The Atlas Hub stops claiming nothing was generated** — *Generate…* writes wherever you point it,
  while the Hub lists one folder: the active Flowable project's output folder. A page saved anywhere
  else was therefore reported as *"No explorer generated yet"*, which is a claim the panel is in no
  position to make. It names the folder it searched instead, so a mismatch is visible rather than
  mystifying.
- **A Design pull is not "Generation"** — the pulled-models folder sat on the *Generation* settings page,
  which had to cover both what Atlas produces from your models and where your models come from: opposite
  directions of travel under one heading. Flowable Design is its own page now, and Generation is exactly
  what its name says.
## 0.16.0

- **One way to sign in, for Design and for the running app** — the two halves of the plugin had drifted
  into teaching different things about the same product. Design offered a username and password or an
  access token; the app offered a username and password, an embedded browser login and a pasted browser
  session. Neither list was a subset of the other, so whichever page you learnt first, the other one had
  controls missing and controls you had never seen, and nothing on either said why.
  There is one model now: a **credential** — username and password, or an access token — plus, for a
  server behind an identity provider, your **captured browser session**, which layers on top rather than
  replacing it (an SSO-fronted Flowable often wants both, and its security chain takes whichever it
  honours). Both kinds get all of it, in one form that differs only where the *servers* differ: what
  *Test Connection* calls, *Create Token…* for Design, and *Detect from Project* for the app.
- **A Design server behind OAuth2 can be pulled from at all** — this was the hole the asymmetry was
  hiding. Design's answer for an identity provider is an access token, and *creating* one is itself a
  username-and-password call: on the very server where a token is the only way in, the button could not
  work, and the browser-session route that would have solved it lived a few classes away, wired only to
  the playground. `DesignClient` set exactly one `Authorization` header and could not carry a cookie at
  all. It carries the session now, the sign-in and paste-session controls are on the Design form, and
  *Create Token…* says out loud that it needs the credentials SSO switches off.
- **One password safe entry per server, not per feature** — there were two stores, `Flowable Atlas
  Design` and `Flowable Atlas Inspect`, identical but for their name. Nothing about a password depends
  on whether the server behind the URL serves models or runs processes. Records are keyed by URL, as
  they already were, so Design and an app remain separate logins; only the redundant second lookup is
  gone. **You may have to enter a stored password once more.**
- **The shared machinery is no longer named after one of its users** — `AuthMode`, `AuthContext`,
  `AtlasCredentials`, `BrowserSessions`, `BrowserSignInDialog`, `PasteSessionDialog` and the cURL parser
  live in one `environment.auth` package. Half of them were sitting in the Expression Playground's
  `expr.inspect`, which is exactly why the Design side never found them. Header precedence — a captured
  `Authorization` beats a configured one, and is never sent twice — is decided in one tested place
  instead of being re-derived at each call site.

## 0.15.0

- **Flowable environments, defined once for every project** — the plugin knew exactly one Design server
  and exactly one running app, so working against DEV1, DEV2, QA, UAT and PROD meant retyping a URL, an
  auth mode, a workspace and an app list every time you switched, and doing it again in the next
  repository. There is now an IDE-wide list of environments under *Settings → Tools → Flowable Atlas →
  Environments*: a tree of environments, each holding a **Flowable Design** connection, a **Flowable
  Work** connection, or one of the two — a QA stage with a running app and no Design server is an
  ordinary thing, shown without any warning. Copy an environment to clone it for the next stage; reorder
  them, because DEV → QA → UAT → PROD is a pipeline and alphabetical puts PROD second. Passwords and
  tokens stay in the IDE password safe, keyed by URL, so they are never in a shared file.
- **Control and Hub addresses belong in the environment too** — a stage is not only the two servers
  Atlas talks to; it is also the Flowable **Control** and Flowable **Hub** pages you open by hand, and
  keeping those in bookmarks while the URLs beside them live in the IDE was the obvious gap. They are a
  URL and nothing else: no username, no password, no *Test Connection*, because Atlas never calls them —
  a form asking for a password nothing would read is worse than no form. Everything else about an
  environment applies unchanged, including copying it for the next stage.
- **Open an environment in the browser from the Atlas Hub** — its toolbar's *Open Environment in
  Browser* lists every address in the catalog, grouped per stage, and hands the one you pick to the
  browser: Design, the app, Control, Hub. Not just the two new kinds — a Design base URL *is* the Design
  UI and a Work base URL *is* the app, so leaving them out would have meant keeping bookmarks for
  exactly the two addresses Atlas knows best. It follows neither the pull's environment nor the
  playground's: a third rule about which one it means is one more thing that can silently be wrong, so
  it asks, and speed search makes the asking a keystroke. A protected stage shows its lock in the list
  and nothing more — opening a page changes nothing, and a confirmation on a link would be theatre.
- **Which environment a project uses is two choices, not one** — the Design pull and the Expression
  Playground point independently, and that is the point rather than an oversight: running against QA
  while models still come from DEV1 is a normal way to work. Every picker only offers environments that
  actually have a server of that kind, so choosing a runtime can never leave you unable to pull. With a
  single environment configured, nothing has to be chosen at all and no dropdown appears.
- **Pasting a Work URL is now the fastest way to switch environment** — the playground's *App URL* field
  already filled in the scope and instance id from a pasted link; it now recognises which of your
  environments the link belongs to and switches to it, so one paste moves the connection, the scope and
  the instance together. A link matching no environment is used as-is, as a target that lives for this
  IDE session. What it no longer does is write that URL into the project's committed settings — a QA
  link pasted for one evaluation used to become the whole team's configured server.
- **Every pasted link keeps its own entry** — the playground held exactly one pasted target, so the
  second link silently evicted the first, which is backwards: pasting two links is what you do when you
  are comparing two apps, and re-pasting the one you just looked at was the cost of a decision you never
  made. They all sit in the picker now, marked *(this session)*, and the button beside it is where they
  are managed: **Forget** one, forget all of them, or — for the one that turns out to be a target you
  keep coming back to — **Save as an Environment…**, which asks for the single thing that was missing.
  The name may be one that already exists: a stage with a Design server and no app joins it rather than
  making a second environment with the same label. Credentials typed in the paste dialog go with it,
  into the password safe, so nothing has to be typed twice. Forgetting a target takes whatever was
  captured for it along — a cookie left behind for a URL that is in no list any more is a credential
  nobody can see and nobody asked to keep.
- **Protected environments** — tick *Ask before pulling from or evaluating against this environment* and
  Atlas asks first, and marks it with a lock wherever it can be picked. A pull asks modally, because it
  replaces archives in the working tree; an evaluation asks from a small confirmation with *Cancel*
  preselected, so declining is one keystroke. There is no "don't ask again": a guard you can switch off
  is not a guard. The check follows the **URL**, not the picked connection, so it cannot be walked around
  by pasting a link.
- **Settings and the Atlas Hub cannot drift apart any more** — configuring something and seeing no effect
  in the Hub was a real defect, not a feeling. *Generation* had no notification at all, so changing the
  Atlas output folder left the Hub listing artifacts from the old one; every Atlas settings page now
  publishes from a `final` method, which makes forgetting a compile error rather than something a review
  has to catch. Switching connection now also drops the Hub's cached workspace and app lists, which used
  to keep showing the previous server's app names. The playground re-reads on a sub-project switch. And a
  personal app selection that the shared default has caught up with is dropped instead of masking it —
  that mask was most of what "editing Settings does nothing" was made of.
- **Settings holds the environment list and nothing else** — no page for "which environment this project
  uses", because a second copy of that choice could not be told apart from the one in the Atlas Hub.
  There was a shared default in Settings and a personal override in the Hub, and the pair drifting is
  most of what "I configure something and it has no effect" was made of: the honest question — *is this
  the setting, or my copy of it?* — had no answer on screen. Now the environment, its workspace and its
  apps are picked in the Hub, beside the models they fetch; the runtime environment is picked in the
  playground, beside the expression it evaluates; and what you pick **is** the setting. The one
  project-level field left, the folder pulled archives are written to, moved to *Generation* next to the
  other output folders.
- **The Atlas Hub's Design section is the whole pull, in order** — environment, workspace, apps, *Pull
  from DEV1*. Both pickers are ordinary drop-downs rather than a status line with a *Change…* link: the
  link cost the same clicks but did not look like a choice, and switching is the gesture the panel is
  organised around. They open instantly, since the environment list is in memory; the workspace list is
  fetched when you switch environment or open the drop-down, not every time the panel is drawn. Everything a
  drop-down offers can be selected — an entry that sends you to Settings to first create what it
  promised is not a choice. The runtime environment sits in its own section beside *Open Playground*.
- **The workspace and app selection is per environment** — a workspace key belongs to one server, so a
  single project-wide value was right for at most one environment and silently wrong on the next.
  Switching environment lands in the two pickers below it: the new server's workspaces are read right
  then, and that environment's saved workspace and apps come back ticked. A workspace or app list that
  could not be read is no longer remembered as read, so the next refresh tries again instead of leaving
  an empty list looking like an answer.
- **Environments a repository ships to its team** — an environment list was a thing every developer
  built by hand, from a wiki page or a colleague's screenshot, and four people typing four URLs get at
  least one of them wrong. A project can now define its own: *Settings → Environments → **Share with
  Project*** writes the selected environment into `.idea/flowable-environments.xml`, and everyone who
  clones the repository has it in every picker, marked *(project)*, with no configuration at all.
  Committed like `.idea/flowable-atlas.xml` beside it, so a URL that moves is a commit rather than six
  settings dialogs.
  **No credentials, and not as a rule someone has to remember** — the file's schema has a name, the
  *Protected* flag and one URL per kind, and no field a username or a password could go in. Each
  developer signs in as themselves, from the IDE password safe as before; a shared login is one audit
  trail with everyone's name missing from it.
  Your own list still wins: define an environment of the same name and it shadows the project's
  entirely, which is how you point *QA* at your own instance without arguing with the repository. A
  shared entry is read-only here — *Copy Environment* turns it into one of yours in one click — and the
  file is re-read when a `git pull` changes it, so the Hub cannot go on offering a URL that has moved.
- **The Environments page fits its dialog again** — username and password shared one row, and a text
  field with no column count reports its *text* as its minimum width, so a long Design URL made the page
  demand more room than the settings dialog has and pushed the password field off the right edge. A row
  per field, and every URL field bounded.
- **The Hub stops spending its height on empty boxes** — the explorer list reserved eight rows whether
  or not anything had been generated, and the app list a fixed ~140px for a workspace that usually holds
  one app: between them, most of a panel that has five sections to fit into one narrow stripe. Both are
  sized to what they hold now, an empty section is a single grey line, and a workspace with twenty apps
  scrolls at eight rows instead of pushing *Pull from …* off the bottom.
- **“Which Flowable project” is a drop-down** — it was a line of text with a *Change…* link underneath,
  and the link was hidden whenever detection had not turned anything up, so in a repository holding
  several apps the answer to "can I pick one?" was a blank space. Now it is the same kind of picker as
  the environment rows, always offering the whole repository, and it costs one line instead of two. The
  amber *"3 found — choose one"* stays until a choice is actually made — including a deliberate
  *whole repository*, which the plugin can tell apart from a default nobody looked at.
- **“Not set” is now something the plugin can actually hold** — with a single environment defined,
  picking *not set* did nothing: it unset the pointer, and "nothing stored" already meant *"you have one
  environment, use it"*, so the rule answered with the same environment the user had just deselected.
  The picker read *not set* while the workspace and the ticked apps underneath it stayed, and a pull
  would have run against that environment. A deliberate *not set* is stored as such now — it beats the
  single-environment convenience, which still applies when no choice was ever made — and it empties the
  two pickers below, because a panel still showing the previous environment's apps reads as a selection
  that carried over, which is exactly what a pull must never do. Choosing the environment again brings
  its workspace and apps back; saying *not set* is not deleting anything.
- **Two environments may point at the same server** — one Design server hosting a DEV workspace and a
  QA workspace is an ordinary setup, and the first cut refused to save it on the grounds that the two
  would share a saved password. They do share it, and that is correct: same server, same login. A rule
  derived from how credentials happen to be keyed had no business forbidding a real-world layout.
- **A pasted link to a task inside a case evaluates** — it answered *"Internal server error"* before.
  Flowable's `subScopeId` is a **plan item instance** id, and no Work route exposes one, so putting the
  task id from `…/case/CAS-1/task/TSK-2` there made the engine look for a plan item that does not exist.
  A named task is the more specific scope and the engine evaluates it directly, so that is what a link
  now resolves to. The playground's *Sub-scope id* field says what it wants, too: it read "Optional",
  which was true and useless.
- **The paste dialog can fix credentials for an app it already knows** — it hid the username and
  password once the app was recognised, which looked tidy and was a dead end: a saved password that was
  empty or wrong could only be corrected in Settings, and the only clue was a 401 from the next
  evaluation. The fields are shown either way, prefilled from what is stored.
- **An app or Design server bound to IPv6 only is reachable again** — and this was the most misleading
  error Atlas could give: *"nothing is listening on that host and port"*, about a server the user had
  open in a browser. The JDK's HTTP client connects to the **first** address a name resolves to and
  never falls back to the others; on macOS `localhost` resolves to `127.0.0.1` first, so a Vite/node dev
  server listening on `[::1]` was unreachable from the IDE while `curl` reached it happily. Both clients
  now retry the host's remaining addresses when the connection itself fails — an HTTP answer of any
  status is still the server's own answer and is never retried.
- **A connection test says something a person can act on** — *Test Connection* on a Flowable Work
  connection probed the Inspect endpoint with a `GET`, which Flowable answers with a *500*: a perfectly
  healthy local app was reported as an internal server error, spelled out as a slab of truncated JSON.
  It now probes the app itself and says what happened — reachable, credentials rejected, wrong context
  path, or unreachable and why — and no status line anywhere prints a raw response body. A failed test
  never blocked saving and now says so, since an app that is simply not running yet is the most ordinary
  reason to see one.
- **The connection fields are the width of the dialog** — a URL field rendered about as wide as the word
  `http:`, because a filled cell still sits at its minimum width unless its column may grow.
- **The environment editor's Add button opens its menu at the button** — it opened in the bottom-left
  corner of the screen when the list was still empty, which is exactly when a first-time user needs it,
  and a popup stranded there holds the whole Settings dialog, so every other page looked as if it were
  loading forever.
- **The two expression dialects keep their own expression** — switching the playground between Backend
  and Frontend carried the text across, which looked like "your work is preserved" and was the opposite:
  the expression already parked in the other dialect was replaced, with no way back to it. They are
  different languages against different scopes, and each now has its own scratch text.
- **Pasting a Work URL is one dialog, and it checks the connection** — the playground's backend card was
  carrying the whole flow in the open: a URL field, a sentence explaining what the field does, a *Save as
  environment…* link that only sometimes applied, and an "unsaved" state in the environment picker. Four
  controls and three sentences for something that happens in one gesture. **Paste Work URL…** now opens a
  dialog that resolves the app, the scope and the instance from the link, and — when the app is not one
  of your environments yet — asks how to get in, and *tests the connection before it closes*. Getting in
  means the same two routes the environment editor offers: a username and password in the open, since
  that is the common case, and *Sign in via Browser…* / *Paste Session…* beside the test for an app
  behind an identity provider — or for a bearer token pasted from a cURL. They are links rather than a
  mode selector because they are not alternatives: an SSO-fronted Flowable can want the session *and*
  basic auth behind it, and the request sends whatever is there.

  A one-off target stays a listed choice in the picker for as long as it lives, marked *(this session)*
  — it is never added to the environment list, so if it vanished the moment you glanced at another
  environment there would be no way back to it short of pasting the link again. And it outranks the
  "there is only one environment, use it" convenience: with a single Work environment defined, that
  fallback used to quietly re-select it the instant a link was pasted, so the pasted app never became
  the target and the picker showed an environment nobody had chosen. An *explicit* pick still wins —
  that is the user changing their mind.

  It **creates nothing**. A link from a colleague, a one-off look at a stage you do not work against, an
  app you will never open again: none of those should leave an environment behind, and being made to
  name one before you can evaluate is a toll on the common case. An unknown app becomes a target for
  this IDE session — the picker says *"(this session)"* — and its credentials go to the same in-memory
  store a browser sign-in uses, so nothing typed there reaches the disk. An environment is something you
  decide to have, in *Settings → Environments*. The card is down to the environment, the scope and the instance id, with no explanatory
  text left to read. An app you have not registered is therefore usable, with authentication, in one
  pass; before, an unknown URL could only be evaluated against if its password happened to already be in
  the keychain.
- **Generation is a page with three child pages** — Model Constants, Liquibase and Data-Object DTOs each
  get their own. On one page they were four screens of fields with no hierarchy, so finding the DTO
  class-name pattern meant scrolling past the Liquibase rename regex.
- **Two new actions and one rename** — *Switch Design Environment…* and *Switch Work Environment…* put
  the switcher in the Tools menu and in Find Action; *Configure Design Connection…* became *Manage
  Environments…*.

- **The Atlas Hub footer is just the version now** — it also read *"verified on 2026.2 — 2026.1 is
  untested"*, which is our release process on display in a panel people keep open all day, and nothing a
  reader can act on. The verified range has not moved: it is still stated in the reference documentation,
  and every bug report submitted from Atlas carries the running IDE's branch and whether it is inside
  that range — which is the one place the distinction changes what happens next.
- **A fresh Design connection no longer reads as "Not configured"** — the Hub called a connection
  unconfigured until a workspace *and* at least one app had been saved, and hid the whole section while it
  did. So a configured server whose default workspace holds no apps left nothing to tick and no way to tick
  it: the pickers that finish the setup sat behind the condition they exist to satisfy. *Not configured* now
  means *no server*; the workspace and app pickers appear as soon as there is one, reading *none selected*
  and *Choose a workspace* until something is picked. A pull that is missing a workspace or an app now says
  which of the two it is, instead of reopening Settings.
- **Pick the Design workspace in the Hub** — the *Flowable Design* section let you choose which apps a pull
  fetches, but not the workspace they come from: that was fixed to whatever *Settings → Connections* held, so
  working against a second workspace for an afternoon meant editing the shared project settings, which is a
  VCS-tracked file the whole team reads. The workspace now has its own picker in the Hub, right above the app
  list, and it is part of the same **personal override** — kept workspace-locally, marked *(personal
  selection)*, undone by *Reset to configured*, and dropped by itself as soon as the pick lands back on the
  configured workspace. A switch starts with nothing ticked, because one workspace's apps do not exist in
  the next one.
- **A Design server on a plain `http://` port no longer times out** — pointing the connection at, say,
  `http://localhost:10014` could fail with *"Cannot reach … request timeout"* while the very same URL
  answered a `curl` instantly. The JDK's HTTP client defaults to HTTP/2, which over cleartext is an h2c
  *upgrade* request, and a server that neither completes nor declines that upgrade leaves the request
  hanging until the timeout. The Design and Inspect clients now speak plain HTTP/1.1; `https://` still
  negotiates HTTP/2 through ALPN, so nothing is given up for it.
- **One reload icon for the two Design lists** — the *Refresh apps* link sat under the app list next to
  *Reset to configured*, where it read as part of resetting rather than as a reload. It is now a refresh icon
  beside the workspace it re-reads, and it reloads both server lists: the workspaces and the apps.
- **The Hub stops asking to be widened** — it is read in a side panel a few hundred pixels wide, and a
  lot of it was sized as if that were negotiable. The app list pinned 320px of width and now takes the
  width it is given; the Design status line spelled out every ticked app key and now says *N apps
  selected* past three; a long workspace name pushed *Change…* and the reload icon past the right edge and
  is now shortened, with the full name and key in its tooltip. An explorer row showed the full
  project-relative path and a timestamp behind the file name — it now shows the tail of the folder, with
  path and generation time in the row's tooltip. An app list with nothing in it is a single line of text
  instead of a tall empty box holding a centred label the panel then clipped.
- **Configuring Inspect in Settings now reaches the Expression Playground** — the playground's backend
  card and *Settings → Connections* edit one and the same connection, but the card read the settings only
  when it was first built and then outlived every trip to Settings. So typing a base URL there changed
  nothing you could see, and the next *Evaluate Against App* wrote the card's stale value straight back
  over what you had just configured. Applying the settings now updates an open playground; a field you
  have typed into yourself keeps its text, because the settings only reclaim what they put there.
- **The Frontend dialect no longer wears the Atlas Explorer's icon** — in the Expression Playground the
  *Frontend* toggle used `AllIcons.General.Web`, the same globe the *Open Atlas Explorer* button uses a few
  pixels away. It is now the form icon, which is also where a frontend expression actually sits.
- **The Hub toolbar is openers only** — *Generate Atlas Explorer* and *Pull from Design* were there twice
  over: once as a toolbar icon, once as a link in the very section whose state they change. Doing
  something to the project belongs next to that thing's state, so the toolbar now holds only ways to get
  somewhere: *Open Atlas Explorer*, the new *Open Expression Playground* (the tool window's own icon —
  it had no button anywhere, only a link at the very bottom of the panel), and *Refresh*. Both removed
  actions stay where they were in the sections and under Tools → Flowable Atlas.
- **The Script Playground has examples to start from** — the *Scripts* tab could load a script out of
  the project's own models, which is exactly the wrong direction when the question is *how does one write
  these*: it can only show you what somebody already wrote. *Load Example…* now offers a library of
  complete, working scripts, at least one for every script context and every language the tab edits, from
  reading and writing variables through transient and local scope, JSON, dates, the engine services, a
  multi-instance collection, raising a BPMN error rather than failing the job, a CMMN plan item and an
  action bot's inputs and outputs. Each one lands in the editor as ordinary text to edit, with the
  comments that explain the decision it demonstrates — why a transient variable, why a task listener binds
  `task` and never `execution`. They are held to the same standard as the code around them: the build runs
  every example through the script validator in its own context and fails on a single warning, so an
  example can never ship the squiggle it would draw.

## 0.14.0

- **Atlas can update itself** — with no Marketplace listing, nothing ever told you a new version existed;
  the ZIP you installed months ago just kept running, silently old. Every release now publishes a custom
  plugin repository, so adding one URL under *Settings → Plugins → ⚙ → Manage Plugin Repositories…* puts
  Atlas in the normal update flow — update badge, one click, no download. The URL always resolves to the
  newest release, and the compatibility range in it is read out of the built plugin rather than written
  by hand, so it cannot advertise a version the IDE would then refuse to install.
- **Cancelling is no longer reported as a failure** — pressing *Cancel* on a running Atlas action told you it
  had broken. Generating the explorer or the artifact set said *"Failed to generate the Atlas explorer"*, a
  Design pull or connection test said *"Design request failed"*, and an Inspect evaluation reported an empty
  error. All three were the cancellation itself being caught and dressed up as an error. Eleven places that
  catch broadly now let a cancellation through untouched. The same bug had a quieter form in the editor: when
  a background scan was cancelled mid-inspection, the *implicit usage* check read that as "no index", and a
  class referenced only from a model could be greyed out as unused.
- **Find Usages no longer blocks typing** — invoking *Find Usages* on a delegate class or bean while the model
  index was cold built the whole index while holding the read lock, so every keystroke and file refresh queued
  behind a full model scan. The lookup now reads the PSI under a short lock, builds the index outside it, and
  takes the lock again only to report results. The index service had always split itself this way internally;
  this one caller had been wrapping the whole thing back up again.
- **Stricter XML parsing** — model files are treated as untrusted input, and the parser said so, but only
  *external* DTDs and entities were actually refused. An internal DTD subset still expanded, which is the shape
  a decompression-bomb document takes. A `DOCTYPE` is now rejected outright — real model files never carry one.
  The parser also gets one instance per thread instead of one shared across the IDE's index scan and the CLI's
  walk, where neither class is specified as thread-safe.
- **Releases can be signed** — Atlas installs by side-loading a ZIP, with no Marketplace vouching for it, so
  nothing distinguished our build from a substituted one. Release artifacts are now signed when a key is
  configured, and the signature is verified before publishing. `SHA256SUMS.txt` alone could never establish
  this: whoever can replace the download can replace its checksum line in the same breath.
- **Attribution for the embedded typeface** — the explorer HTML embeds the Geist font, which ships under the
  SIL Open Font License and obliges its notice to travel with the font. Because a generated `.explorer.html`
  is a redistribution with no repository attached, the notice now lives inside the generated stylesheet as
  well as in the new `THIRD-PARTY-NOTICES.md`. The repository `LICENSE` had also claimed that everything in
  it was Flowable AG's property, which stopped being true the day the font was embedded; it now carves out
  the two third-party components and says in plain words that the source is readable, not usable.
- **`SECURITY.md` and `CONTRIBUTING.md`** — a security problem now has somewhere to go that is not a public
  issue, together with the design decisions worth knowing before reporting one (Atlas transmits nothing on its
  own, credentials live in the PasswordSafe, generated artifacts contain your project's data by construction).
  `CONTRIBUTING.md` is honest about what the licence permits and documents the build, the release gates and
  which files are generated rather than written.
- **Placeholder keys everywhere** — every example model key, namespace and table name in the code, tests and
  documentation is a `DEMO-*` placeholder. Some had been carried over from a real project, and this repository
  is public.

## 0.13.0

- **Report a problem straight from the error dialog** — an Atlas exception used to reach the IDE's generic
  "Report to JetBrains" dialog, which discards third-party plugin reports, so the only way to hand one over
  was to dig `idea.log` out of *Help → Show Log*. The dialog now offers **Report Flowable Atlas Problem…**: it
  assembles the environment (Atlas and IDE version, verified platform range, OS, JRE) plus the stack traces,
  copies that to the clipboard and opens the issue tracker. Nothing is transmitted by the plugin itself — a
  trace can carry model keys and file paths from your project, so you see the text before it moves.
- **Failures leave a trail** — around forty places swallowed their exception silently. The worst were the
  credential stores: typing a Design or Inspect password and hitting *Apply* could fail to write it to the
  PasswordSafe (locked keychain, "do not save passwords" mode) and say nothing, so the next pull asked again
  for no visible reason. Those now log, as do a failed Design pull, custom-function extraction (whose failure
  made the inspection flag your own `flw.*` functions as unknown), sub-project detection and explorer
  discovery. Hot paths log at debug or once, never per file, so `idea.log` stays readable.
- **The Atlas Hub states what was actually verified** — its footer reads *"verified on 2026.2"* and flags
  the running IDE whenever it falls outside that, in either direction. Atlas installs on 2026.1 and later
  on purpose: it ships as a ZIP with no update channel, so a tight `until-build` would make it vanish on
  the day you upgrade the IDE rather than prompt for an update. That makes the range it *installs* on wider
  than the range it is *verified* on, so a 2026.1 install is flagged as untested too — "it loads" is no
  longer confused with "it was tested".
- **A CHANGELOG in the repository** — the release history only existed inside the plugin descriptor, where the
  IDE's plugin manager shows it, leaving CLI users and anyone reading the repo on GitHub with no way to see
  what changed. `CHANGELOG.md` is now generated from these very notes, so the two cannot drift.
- **Removed a platform API scheduled for deletion** — three combo/list renderers used a
  `SimpleListCellRenderer.create` overload JetBrains has marked for removal, which would have broken the
  plugin on a future IDE. Replaced with the supported `textListCellRenderer`. JetBrains' Plugin Verifier now
  reports no scheduled-for-removal usage at all.
- **Fixed two gates that were not gating** — the tests that keep `CLAUDE.template.md` and `CHANGELOG.md` in
  step with their generators compare files outside the module's source sets, which Gradle could not see:
  hand-editing either left `./gradlew build` green on exactly the drift those tests exist to catch. They are
  declared inputs now, verified by injecting a change and watching the build fail. Also added the repository's
  `LICENSE`.

## 0.12.2

- **Runs on IntelliJ IDEA 2026.2** — one ZIP for 2026.1 and 2026.2, both checked with JetBrains' Plugin
  Verifier. 2026.2 moved JCEF (the Atlas Hub, the explorer editor, the Inspect sign-in browser) out of the
  platform core into the bundled *Web Browser (JCEF)* plugin; Atlas now declares that plugin as an *optional*
  dependency, so the same build picks it up on 2026.2 and ignores it on 2026.1.

## 0.12.1

- **DTO class names come from a pattern** — *Generate → Data-Object DTOs* names its classes the way the
  Liquibase dialog names its changelog files: a token pattern (`{name} {shortName} {key} {app} {suffix}`) plus
  an optional regex rename, rendered live into the preview's *Class name* and *Target file* columns. The
  default `{name}{suffix}` is the name you got before, a class name typed into the table outranks the pattern
  for that row, and the Alt-Enter intention proposes the same name the bulk dialog would. Pattern and rename
  are remembered per project (and per sub-project) under *Settings → Flowable Atlas → Generation*.
- **Class names without the model key** — Design model names usually carry their key (`DEMO-D009 Pod Member`),
  which the derived class name repeated as noise: `DEMOD009PodMemberDto`. `{shortName}` drops it — the key
  itself when the name starts with it, otherwise a leading capitals-and-digits run before the first word, so a
  key written differently from the key model (`DEMO-D9` against `DEMO-D009`) also shortens while an acronym
  (`IBANCheck`) is left intact. `{shortName}{suffix}` is the one-token way to `PodMemberDto`.

## 0.12.0

- **A "Flowable Model" tab in Search Everywhere** — open it from *Tools → Flowable Atlas → Search Models…*,
  from the Atlas Hub's *Model Index* row, or by pressing Shift twice and tabbing across to it, and search
  *only* Flowable models, without your project's classes, files and symbols mixed in. Models are matched on
  their **key** and on their **file path**, and every row names where it came from: `app.zip →
  processes/invoice.bpmn`. Model keys stay searchable in the Symbols tab as before — the new tab is a second,
  focused way in, not a replacement.
- **Your archives are searchable at last** — a model packed in a `.bar`/ `.zip` is invisible to IntelliJ: it
  appears in neither the Files tab nor Find in Files, because an archive is not part of any content or library
  root. The new tab searches those entries — by name *and* by content — so you no longer unpack an archive by
  hand to find out which process references a variable.
- **Full text, live** — from two characters on, the tab also greps the content of every model, with each
  occurrence its own row showing the matched line and its line number; Enter jumps straight there. The scan is
  cancelled on the next keystroke and its results are cached, so it stays responsive on large repositories. It
  runs only while the tab is selected, never in the general "All" results.
- **Models open with their content** — `.bpmn`, `.cmmn` and `.dmn` are now recognised as XML, and the
  remaining JSON model types (`.data`, `.service`, `.agent`, `.event`, `.query`, `.app`, …) as JSON.
  Previously IntelliJ classified them as unknown and showed a "file type not associated" placeholder instead
  of the file — most visibly for an entry opened out of an archive.

## 0.11.3

- **Unused variables, on their own page** — the Explorer gains a *Variables → Unused variables* tab listing
  every variable something **writes** that nothing **reads**. Two findings: a variable no model reads
  anywhere, and one **mapped into a called model that never reads it** — the dead `flowable:in`, where the
  caller's own use of the name says nothing about the callee. Each row names the write to delete in Design's
  words (*Result variable "Calculate total"*, *Decision output*, *In parameter "Fulfil"*), links the model and
  jumps to the element on its diagram.
- **The variable graph now knows read from write** — it recorded only that a name occurred somewhere.
  Direction is now tracked for every kind of evidence: `setVariable` vs `getVariable`, a decision's inputs vs
  its outputs, each side of every in/out mapping, a process-level `<dataObject>` declaration, and a form field
  as *both* (a field is prefilled from the variable and writes it back). A variable's page gains a **Written /
  read** list, so the finding can be checked rather than trusted.
- **The check would rather say nothing than guess** — it stays silent wherever a read could exist out of view:
  a construct whose direction Flowable does not fix, a value consumed outside the models (an action's response
  payload, an extracted variable a query indexes, a form's outcome variable, a loop counter), a name written
  into a container object, a bare-EL Init-Variables value, a name the `{{…}}` harvester ignores, a name passed
  as a string literal, any scope whose script or Java code reads the whole variable map at once, and a mapping
  into a model outside the project. The page says how many variables it declined to judge, and why.
- **Also newly visible** — a process's `<dataObject>` variable declarations and legacy
  `<flowable:formProperty>` fields are parsed into the graph, an event task's `eventInParameter` source is
  recorded at all (both sides used to be dropped), and a Java class evaluating Flowable EL from a string
  (`resolveValue(task, "${vars:get(flagReturn)}")`) counts as reading that variable.

## 0.11.2

- **The diagram icon follows a key held in a constant or variable** — it used to appear only on an inline
  literal, so extracting the key (`String key = "DEMO-P039";` … `.processDefinitionKey(key)`) or using the
  generated model constants (`.processDefinitionKey(ModelKeys.ONBOARDING)`) lost it. The compile-time value is
  now resolved at every Flowable key call site, and the icon sits on the call line.
- **Action keys are labelled with the action's name** — an inline hint after a string literal that is an
  `.action` key, so a constants class of `DEMO-Annn` reads as what each one starts. The counterpart of the
  data-object table-name hint; toggle under *Settings → Editor → Inlay Hints → Values* or *Settings → Tools →
  Flowable Atlas → Inline Hints*.
- **Decision tables open as tables** — a Design decision table has no canvas and therefore no DMN layout to
  draw, so clicking the diagram icon on a decision key reported "no diagram layout" for nearly every decision.
  Atlas now paints the **decision table** itself: hit policy, input band (label plus the expression it
  evaluates), output band, one numbered row per rule with its annotation. A decision requirement diagram still
  renders from its layout, and the Explorer keeps showing rules as its own searchable table.

## 0.11.1

- **The diagram's element card can be expanded over the whole page** — it starts docked to the diagram's
  top-right corner, where its resize handle has nothing left to grow into: in a narrow tool window you could
  not drag it past the drawing. **⤢** in the header (or a double-click on the header) now lifts it out of the
  corner into a centered overlay across the entire app — dimmed behind, above even the full-screen diagram, as
  tall as its content needs and still resizable. Clicking the backdrop or `Esc` shrinks it back to the corner,
  a second `Esc` closes it, and the expanded and docked sizes are remembered separately, so clicking through
  elements stays in the mode you chose.

## 0.11.0

- **The generated artifacts say what Atlas actually knows** — the Markdown/JSON artifacts had been frozen
  since the Python port while the engine grew, so everything learned in 0.10.x lived only in the explorer and
  the IDE. Now: **processes are listed in execution order**, each step naming its successors and branch
  conditions (gateways, receive tasks and sub-processes are rendered at all for the first time, and an element
  no sequence flow reaches is marked as unreachable instead of looking sequenced); **CMMN criteria show their
  sentry's condition**; **DMN rule rows**, **service operation in/out contracts** and the **data layer**
  (service ↔ Liquibase table ↔ data object, with the gaps named) are included; **variables carry their
  provenance** — scope, where set, where read, and a mark when the name is only inferred from a script;
  expressions are grouped by callee with the invalid ones flagged.
- **Health findings are computed once, for every surface** — the eleven checks (invalid/suspect expressions,
  script syntax, unparseable files, missing model references, orphan/superseded changelogs, schema gaps,
  unused forms/operations/custom functions, script-inferred variables) were JavaScript inside the explorer, so
  no other artifact could state a single one. They are now `findings`/`checks` in `:core`, feeding the
  summary's Health block, the overview's Findings section (with `file:line` and the offending source line), a
  "known issues — do not copy these patterns" list in `CLAUDE.md`, `graph.json`, the CLI status line and the
  explorer's Checks tab. One definition, one number everywhere.
- **CLAUDE.md tells an agent what it may call** — a new cheatsheet, generated from the same catalogs the
  expression and script validators use: every backend EL namespace and its functions, the frontend `flw.`
  members, what each script context binds, the platform beans, and the project's own discovered functions.
  Atlas used to report that `${vars:bogus()}` is wrong while never saying what is right.
- **graph.json is half the size and queryable** — a model body is stored once in its bucket and its node
  points there via `data.dataIn`, output is minified (`--pretty` to indent), every node carries a `usedBy`
  reverse index, and a `_schema` key documents the shape and ships `jq` recipes. 4.8 MB → 2.5 MB on a large
  real project.
- **`--slice <type:key>`** — one node with its full context in both directions (what it uses, who uses it, the
  findings touching it): the tier between a few-KB summary and megabytes of graph, for when the task is about
  one model.
- **Truth fixes** — the overview no longer signs itself "Generated by flowable_project_overview.py"; Python
  `repr` output (`{'kind': 'rest', …}`, `['total']`, `None`) no longer reaches the page (a test now fails if
  it does); the summary prints the real variable scopes instead of the words "process / form / case / java";
  pointers name sibling files instead of CLI flags or explorer tabs; `CLAUDE.template.md` is generated from
  the same source as the primer, so the two can no longer drift; Gradle projects get their Flowable version
  detected; and a naming convention is only claimed when it generalises.

## 0.10.17

- **Choose what "Pull from Design" fetches — in the Hub** — the Atlas Hub's *Flowable Design* section now
  lists the workspace's apps with checkboxes, pre-ticked from the configured default. Ticking differently is a
  **personal override**: stored workspace-locally, never in the VCS-shared settings file, marked *(personal
  selection)* in the status line, with *Reset to configured* to go back. The Hub link and the toolbar action
  both pull that effective selection, and app names/versions load on demand so the Hub's own refresh never
  calls Design. The Connections settings keep the shared team default (and the first-time setup).
- **Generate data-object DTOs in bulk** — Tools → Flowable Atlas → Generate → *Data-Object DTOs* → *From
  App(s)…* / *From Data Object…*: a preview table of exactly what will be written — key, editable class name,
  owning app, field count, target file, and whether it is new or overwrites — for a whole app at once or for
  hand-picked data objects. Target source root, package, an optional *sub-package per app* and the class-name
  suffix (default `Dto`) live in Settings → Flowable Atlas → *Generation*. Each class is the same POJO the
  Alt-Enter action emits: typed fields, a `fromContainer(…)` mapper and a fluent builder.
- **The DTO quick action finds the key** — Alt-Enter now recognises a data object behind a **constant**
  (`definitionKey(ModelKeys.CUSTOMER)` — what *Generate Model Constants* produces), not just an inline
  literal, and beyond API call sites it offers itself on **any** string literal or constant whose value is an
  indexed data-object key. Availability is a plain index lookup, so it no longer parses model files during
  highlighting; resolving the fields moved into a cancellable progress that reports when a key has no model or
  no field mappings instead of doing nothing.

## 0.10.16

- **Platform beans in the script catalog** — Work scripts resolve any Spring bean by name, so the platform's
  default services (`dataObjectRuntimeService`, `contentService`, `templateService`, `sequenceService`,
  `platformIdentityService`, `actionRuntimeService`, ~25 more) come with generated method surfaces: completion
  with signatures (typed as *Spring bean*), member-typo checks and hover documentation — the sandbox
  strict-mode caveat is documented on hover.
- **Script Playground chips, redesigned and clickable** — the info strip under the editor is now a two-column
  layout (Variables · Reads · Bindings · Beans) with soft pill chips that wrap instead of clipping, a
  click-to-expand *+N more* per row — and every chip inserts its name at the caret when clicked (undoable,
  focus returns to the editor).
- **Explorer: work through search hits in bulk** — list rows support multi-selection (⇧-arrows, ⌘/Ctrl-click,
  ⇧-click ranges, ⌘/Ctrl-A) and Enter opens every marked row as its own detail tab; detail tabs remember
  scroll and search term, open in the background with ⌘/Ctrl-click, and switch with Alt+1…9.

## 0.10.15

- **Design access tokens** — "Pull from Flowable Design" can authenticate with a Flowable Design *personal
  access token* (`Authorization: Bearer …`) instead of a username and password: the scheme Flowable's own CLI
  uses, and the only one that works when Design sits behind SSO/OAuth2 (where basic auth is switched off).
  Pick the mode under Settings → Tools → Flowable Atlas → *Connections*; workspace list, app list and app
  export all go through it, and "Refresh Workspaces" still doubles as the connection test.
- **Mint a token without leaving the IDE** — *Create Token…* signs in once with your username/password,
  creates a named token with a chosen validity and drops it straight into the field, so afterwards **no
  password has to stay in the keychain**. *Manage in Design…* opens Design's own token page. Password and
  token are stored as two separate IDE PasswordSafe entries, never in a file, so switching auth modes back and
  forth loses neither.
- **Sharper sign-in errors** — an HTTP 401 now says which credential to fix: "check username/password" in
  password mode, "the access token is invalid or expired" in token mode. A blank token fails immediately
  instead of producing a pointless request.

## 0.10.14

- **Script Playground** — the Flowable Expressions tool window gains a *Scripts* tab: paste or write a
  Groovy/JavaScript/Python script and get real-language completion and coloring (where the IDE plugin is
  available), live structural validation with squiggles, clickable problem rows and an error stripe, the scope
  variables the script touches as chips (API writes vs ≈ heuristic reads), and *Load Script from Model…* to
  pull any script task, listener script or action-bot script out of the project's models for inspection.
- **Real script-task validation** — BPMN/CMMN script tasks, listener scripts and action-bot scripts get the
  IDE's own Groovy (bundled) / JavaScript (Ultimate) language injected inline: compiler-grade syntax errors,
  highlighting and Alt-Enter fragment editing right in the model file. GString `${…}` interpolation no longer
  double-injects the expression language inside script bodies.
- **Script syntax checks everywhere** — a dependency-free structural validator (unterminated strings/comments,
  unbalanced brackets, unclosed `${…}` interpolation, `scriptFormat` typos with a did-you-mean) runs over
  every script during generation: findings land in the explorer's Checks tab (new "Script syntax" card), as ⚠
  badges in the Script tasks tab and model detail panels, in the Markdown reports and the CLI status line.
- **Script binding validation** — a catalog transcribed from the Flowable engine and platform sources knows
  what each script context really binds (`execution` in BPMN script tasks, only `task` in task-listener
  scripts, `planItemInstance`/`caseInstance` in CMMN, `flw`/`flwActionContext` in action bots) and the full
  API of those objects: member typos get a did-you-mean (`setTransientVariabel` → *setTransientVariable*), a
  root used in the wrong context is explained, EL-only `flw.*` namespaces and case-sensitive `scriptFormat`
  values are flagged, and a CMMN lifecycle listener with a script — which the engine silently ignores — gets a
  warning. The Script Playground gains a context picker and shows the bound root objects as chips.
- **Binding-aware script completion** — after `execution.` / `flw.time.` the catalog offers the context's real
  API with parameter signatures (`setTransientVariable(variableName, value)`), in the Script Playground and
  inside injected script bodies in model files; the root bindings complete at the top level. The Groovy/JS
  "unresolved" noise on those dynamic bindings (gray `execution`, *No candidates found for method call*) is
  suppressed exactly inside Flowable script bodies.

## 0.10.12

- **No more multi-second UI freezes while the model index builds** — the scan now only holds the read lock to
  collect candidate files; parsing and regex work run lock-free, so typing and VFS refreshes are never queued
  behind it.
- **Quieter shutdown** — the index scan stops when the project closes and the embedded explorer editor no
  longer touches the VFS after disposal (no more AlreadyDisposedException warnings in the log).

## 0.10.11

- **Schema gaps tab** — the dashboard's "Schema gaps" number unfolded into a view of its own (sidebar, next to
  Overview): every service with Liquibase → Service → Data object coverage, its unmapped columns front and
  center, fully-mapped services collapsed to a chip row. The dashboard card now routes there.
- **Movable, resizable diagram info card** — the element card docks to the top-right of the diagram (stable
  while clicking through elements), can be dragged by its header and resized via the corner grip; width and
  position are remembered.
- **Entry/exit criteria show their condition** — in the plan-model tree (entry ◇ / exit ◆ chips), in the
  Sentries section (named "entry of Review" instead of a raw sentry id) and on the diagram: criterion diamonds
  are rendered properly (entry hollow, exit filled) and clicking one shows the guarded plan item and its
  sentry condition.

## 0.10.10

- **Legacy Design exports are no longer invisible** — the "typed-directory" export format (`form-models/`,
  `service-models/`, `action-models/`, … with each model wrapped in `{key, name, editorJson}`) is now
  unwrapped and parsed, from a zip or a loose workspace directory. Services, data objects, actions, events,
  channels, queries and policies go through their full parsers; old Oryx-editor forms/pages are at least
  registered by key so references resolve and their `{{…}}` bindings are indexed; the root app wrapper becomes
  the app node with *contains* membership.
- **Model data that was parsed but never shown** — new collapsible detail sections: a form/page's *Fields* (id
  → variable link, label, type, required, bound value) and *Data sources*; a process's *User tasks* (form,
  candidate groups, assignee, due date/priority), *Script tasks* (with the script body), *Events & timers*,
  *Multi-instance*, *Flow conditions* and *Listeners*; a case's *Plan model* tree (stages, milestones, tasks —
  each linking to the form/process/case/decision it uses), *Sentries* and *Event listeners & timers*; a
  security policy's *Permissions* (roles link to groups); an agent's *Tools* and *Operations* (with prompts);
  an app's *Variables* and *Pages*; an action's *Bot script*.
- **More links between the datasets** — form field ids and DMN decision inputs/outputs join the variable graph
  (a variable page now shows the forms and decision tables that touch it); events link to the channels that
  carry them and the data-dictionary types of their payload; agents' service tools count as uses of the exact
  operation; services link to the services their column relations join to; case-view/case-page `static-*-key`
  references and create-instance buttons resolve to their process/case/form/decision; watcher / participant /
  manual-activation / event-listener group permissions and form-button `permissionGroups` feed the Access
  view.
- **Hover explanations now work in the IDE** — every `title=` tooltip (Design vocabulary hints, copy buttons,
  badges) and the neighborhood graph's labels are served by the Explorer's own tooltip bubble, which renders
  in the embedded JCEF viewer where native tooltips never show.
- **Small things** — zero counts are no longer shown as rows; a decision service is labelled as such; CMMN
  tasks reuse the BPMN task-type vocabulary (*Data object task*, not *task · data-object*); search also
  matches form field ids/labels, app variables, agent tools, policy permissions and dictionary types; user
  tasks pick up due date / priority / category.

## 0.10.9

- **In/out parameters are mapped and searchable** — Atlas now reads every variable mapping a model passes
  into, or takes back out of, the things it calls: call activities and process/case tasks
  (`flowable:in`/`out`), Service-Registry, Agent, Data-Object and HTTP tasks (`inputParameter` /
  `outputParameter` / `errorOutputParameter` / `outputVariableName`), Send-/Receive-Event tasks
  (`eventInParameter` / `eventOutParameter`), Init-Variables (`variableMapping`), result variables, and Action
  Bots (`signalVariableNames`, bot `config`, `flw.getInput` / `flw.setOutput`). Previously only
  `flowable:in`/`out` on a call activity or a CMMN process/case task was read at all.
- **See them** — a node's detail view gets a *Parameters* section grouped per element with direction and
  `source → target` (variable names link to the variable), a collapsed *Field injections* section for a task's
  static configuration (an HTTP task's request URL/method), and each `.service` operation now shows its
  declared **outputs** next to its inputs.
- **Find them** — ⌘K/Ctrl-K matches parameter names on both sides of a mapping (the caller's variable *and*
  the callee's contract name) and shows which mapping matched; a variable's detail view lists every mapping
  that reads or writes it, and a new *Variable · parameter* sidebar category collects the variables that
  travel through one.
- **Form and page buttons too** — an Action, REST, Service, Agent or Create-Instance button's *Send payload
  map* and *Store response attributes* (`sendPayloadMapping` / `responsePayloadMapping` /
  `errorResponsePayloadMapping`, plus REST headers) are read and shown on the form. Previously only the
  button's `actionDefinitionKey` was, so the values it actually passes to the bot were invisible.
- **Every parameter group names what it calls** — `→ notifyCustomerAction`, `→ custSvc`, `→ subProcess`, an
  HTTP task's URL — with a link to that model when it is part of the project. And any called model (action,
  agent, service, data object, process, case, event, bot) gets the mirror view, **Called with**, listing what
  each caller sends — handy for checking that the names match a script's `flw.getInput(…)` or a service
  operation's declared inputs.
- **Detail pages fold up** — every section is collapsible and starts collapsed (except the diagram), so a node
  with 35 parameters stays skimmable. What you open stays open as you walk the graph, there is an *expand all*
  control, and a long parameter list gets its own text filter plus in / out / error direction chips.
- **Search jumps to the row** — picking a ⌘K/Ctrl-K hit now scrolls to the matching parameter, expands its
  section and highlights it instead of dropping you at the top of the page. The term rides along in the link,
  so *copy link* reproduces the highlight.
- **Diagrams show what each element is** — every shape now carries its Flowable type icon (User task, Service
  task, Service registry, AI Agent, Data object, HTTP, Script, Email, Timer / Message / Signal / Error events,
  …) plus the BPMN markers that belong to it: multi-instance, loop, a dashed non-interrupting boundary event,
  and a thick-bordered call activity instead of a sub-process marker. Hovering a shape names it in **Flowable
  Design's own words** ("Lookup customer — Service registry task"). The type comes from the Design stencil the
  model was drawn from, falling back to `flowable:type` and then to the element itself, so both app exports
  and Design workspace models are covered.
- **Diagram zoom and full screen** — zoom / fit buttons, scroll-to-zoom and drag-to-pan, and a full-screen
  view (`+` / `−` / `0`, Esc closes) for the diagrams that were simply too small to read in the panel.
- **Design's vocabulary, with explanations** — node types, element types, parameter kinds and relationships
  are now named as Design names them (*Decision tables*, *AI agents*, *Services*, *Send payload map*,
  *Decision task → decision table*) instead of Atlas's internal keys, and each carries a hover text saying
  what it means.
- **Also fixed** — a BPMN Service-Registry or Agent task's `serviceMapping` / `agentMapping` is now read (only
  the CMMN side was), so it links to the service or agent model and counts towards that operation's usages.
  Same for an **agent button** on a form, whose agent-model reference was not recorded at all.

## 0.10.8

- **Recognize model keys anywhere in code** (opt-in) — enable *Settings → Tools → Flowable Atlas → "Recognize
  model keys anywhere in code"* and any Java string literal whose value equals a known model key gets the
  diagram gutter icon, Ctrl-click navigation, Find Usages and hover — not only at a recognised Flowable API
  call like `startProcessInstanceByKey("…")`. Off by default; matches on value alone.
- **Diagrams for models inside an app archive** — a process/case/decision packaged in a `.zip`/`.bar`/Design
  app export now renders its diagram in the generated Explorer HTML and the *Diagrams (SVG)* artifact, the
  same as a loose model file; previously archived models showed no diagram section.
- **Version at a glance** — the generated Explorer HTML footer and the Atlas Hub now show the Atlas version,
  so it's clear which build produced a given page.

## 0.10.7

- **Diagrams render from the model layout** — the model-diagram gutter icon no longer depends on a `.svg`
  bundled by the Design export (newer Design exports no longer ship one). Atlas now renders the process / case
  / decision diagram itself from the model's BPMN/CMMN/DMN diagram-interchange layout — reading either
  deployment XML (`bpmndi`) or Design-workspace JSON (ORYX) — and still prefers a bundled `.svg` when one is
  present. The same rendering is available as a **Diagrams (SVG)** generation artifact and is embedded in each
  node's detail view in the Explorer HTML.

## 0.8.8

- **Payload scope in the Expression Playground** — evaluate a frontend expression as a component *inside a
  subform or list* would see it: enter a payload node path (e.g. `orders[2].items[0]`) or place the caret on
  the node and hit *From Caret*. `$item`, `$index` and the chained `$itemParent` are bound exactly like the
  form runtime binds them; `root` and `$payload` stay absolute. The scoped node is highlighted in the payload
  editor, and a path that no longer resolves is flagged on the field and reported as the evaluation result.
- **Resizable playground panels** — the divider between the expression editor and the payload/scope card is
  now grabbable (drag it to trade width when docked side-by-side, or height when stacked), and the frontend
  evaluation result sits under the payload behind its own drag handle so a large payload can be given more
  room; it still scrolls.

## 0.8.7

- **“Last scanned” time on the Model Index** — the Atlas Hub now shows when the model index was last built,
  next to the model count, so you can tell how fresh it is.

## 0.8.6

- **Works when the IDE backend runs remotely** (e.g. JetBrains Remote Dev in Kubernetes). *Generate Model
  Constants* no longer depends on a modal dialog that could silently fail on the thin client — it uses the
  class name from *Settings → Flowable Atlas → Generation*, reports any problem as a notification, and falls
  back to a folder picker when the project exposes no Java source root. *Open in Browser* now uses the IDE's
  own browser mechanism (like the built-in HTML action) and is shown only where a browser can actually be
  launched.
- **“Generate Model Constants” in the Atlas Hub** — right in the Hub's *Model Index* section, next to
  *Rebuild*.
- **Warning when you rename Java a model uses** — renaming a method or bean/delegate class referenced from a
  model expression (`${bean.method()}`, delegate expressions, …) warns that the model's text references are
  not updated by the rename, with a *Show affected models* action. A gutter icon also marks such methods/beans
  and navigates to the models that use them.
- **Stale-explorer hint after a Design pull** — pulling models from Flowable Design now offers a *Regenerate
  Atlas Explorer* action (and a hint in the Atlas Hub): the model index is rebuilt automatically, but the
  generated explorer is not.
- **Clearer settings wording** — the “index raw Design workspace sources” and “expressions in Java string
  literals” options now explain what they actually do.

## 0.8.5

- **"Paste session from browser" for "Evaluate Against App"** — the reliable way to evaluate backend
  expressions against an SSO/OAuth2-fronted app whose identity provider blocks the embedded browser login
  (e.g. Microsoft Entra Conditional Access). Log in to the app in your normal browser, copy any authenticated
  request (DevTools → Network → *Copy as cURL*, or just its `Cookie` header) and paste it: the plugin extracts
  the `Cookie`, `Authorization` and CSRF-token (`X-XSRF-TOKEN`) headers and replays them, so the Inspect
  request rides your already-authenticated session — CSRF-protected POSTs included. Captured headers live only
  for the current IDE session (nothing written to disk).
- **Embedded sign-in sends a desktop User-Agent** — the *Sign in via browser (SSO)* login now presents a
  normal desktop-Chrome User-Agent, which lets some IdPs accept the embedded login; where policy still blocks
  it, use *Paste session from browser*.

## 0.8.4

- **Sign in via browser (SSO) for “Evaluate Against App”** — the Expression Playground's backend evaluation
  now works against apps fronted by SSO/OAuth2, not just local basic-auth dev instances. A new *Sign in via
  browser (SSO)…* button opens the app in an embedded browser; you complete the real identity-provider login
  (Microsoft, Keycloak, …), and the resulting session cookie is reused for this IDE session so the Inspect
  request rides your authenticated session. Basic auth and the SSO cookie can be combined — for an OAuth2
  gateway in front of a Flowable that still wants basic auth. A login redirect now surfaces a clear “app is
  behind SSO/OAuth2 — sign in” message instead of a raw HTTP 302.

## 0.8.3

- **Monorepo detection refined** — a root-level build file (`pom.xml`/`settings.gradle`/…) that wraps a single
  app is now treated as one whole project and is never split into its modules; only a true reactor bundling
  two or more independent apps (each carrying its own `.app`) prompts you to pick a project. The Atlas Hub's
  *Change…* link now appears whenever a sub-project is detected, so you can always switch scope (previously it
  could lock you onto “Whole project”).
- **Fixes** — Atlas Explorer generation no longer fails with a `NullPointerException` on a service-model
  operation that declares an HTTP method but no URL. Resolved a plugin-load error (missing intention
  description) for the “Generate Java bean for this Flowable data object” intention.

## 0.8.2

- **Resizable Atlas Explorer sidebar** — grab the sidebar's right edge and drag it wider or narrower like an
  IntelliJ tool window; the width is remembered. Dragging it very narrow snaps it to the icon rail, and while
  collapsed, hovering the rail flies the full labelled menu out over the content — so a narrow window (or a
  narrow Atlas Hub tool window) no longer leaves you with unlabelled dots.

## 0.8.1

- **Multi-project (monorepo) support** — Atlas detects the distinct Flowable sub-projects under the project
  root; the Atlas Hub lets you switch the active one and the model index then scans just that subtree.
- **Flowable Inspect connection editor** — Settings → Tools → Flowable Atlas → *Connections* gains an embedded
  Inspect connection (base URL + credentials, with *Detect from project*) that the Expression Playground
  evaluates backend expressions against.
- **Fixes** — resolve a duplicate registration of the “Open in Expression Playground” intention that could
  abort the highlighting pass, and clear all remaining compiler warnings across the codebase.

## 0.8.0

- **Atlas Hub — one control center** — a new tool window (right stripe, or Tools → Flowable Atlas → *Atlas
  Hub*) shows the model-index status with per-type counts and a background *Rebuild*, every generated
  `*.explorer.html` (double-click opens the embedded viewer, with a browser fallback when JCEF is
  unavailable), and the Flowable Design sync state with last-pull time — refreshing live as models change,
  artifacts are generated or a pull finishes.
- **The explorer follows your IDE theme** — the embedded Atlas Explorer now opens in the IntelliJ light/dark
  theme and restyles live when you switch the LAF, without a reload. The in-page toggle still wins for
  explicit overrides; *auto* follows the IDE. In a plain browser nothing changes.
- **Explorer editor toolbar** — the embedded viewer gained a thin toolbar: *Regenerate* (re-runs the generator
  for exactly this file and reloads, no dialogs, no balloon), *Reload* and *Open in Browser*.
- **Settings, reorganized** — Settings → Tools → Flowable Atlas is now a small tree: the root page keeps the
  core toggles, and three project-level sub-pages hold the rest — *Expressions* (validation toggles and, for
  the first time, the expression allowlist as an editable table plus custom-function discovery), *Generation*
  (per-artifact selection instead of the old two-way switch, a configurable output folder, and the
  model-constants class options — now project-level and VCS-shared) and *Connections* (the full Flowable
  Design connection editor — replacing the old dialog — and the Inspect connection with *Detect from
  Project*).
- **Allowlist flows into generation** — the project's expression allowlist and the custom-function settings
  are now passed to the Atlas generator (as with the CLI's `--expr-allowlist` / `--custom-functions`), so the
  explorer's *Suspect* findings respect them.
- **Tools menu, tidied** — grouped into Open / Generate / Flowable Design / Maintenance; dialog-opening
  actions carry an ellipsis; the debug *Dump Key Index* became the user-facing *Rebuild Model Index*
  (background, balloon with counts) and the raw dump is internal-mode only. Consistent naming across dialogs
  and notifications; project settings consolidated into `.idea/flowable-atlas.xml`. The main-toolbar compass
  icon is gone — the Atlas Hub is the plugin's one visible surface (tool-window icons follow the native
  monochrome style).

## 0.7.6

- **Open an existing Atlas explorer from the menu** — Tools → Flowable Atlas → *Open Atlas Explorer* opens an
  already-generated `*.explorer.html` in the embedded in-IDE viewer, without regenerating it and without
  switching to a browser. It looks under `atlas-output/` first (where both the generator and the standalone
  `atlas` CLI write by default), so a page produced from the terminal opens straight in the IDE; if several
  exist you pick one, and if none is found it offers to generate. Opening now brings the rendered *Atlas
  Explorer* tab to the front immediately (instead of the HTML source), from the menu, the post-generation
  balloon and double-clicking the file alike.
- **One-click toolbar icon — opens in the center** — *Open Atlas Explorer* also sits as a compass icon in the
  main toolbar; clicking it opens the explorer as a **center editor tab** (like opening a database table),
  rendered right away, so it fills the main area and can be maximized like any editor.
- **One unified view — no “Text” tab, playground included** — the center Atlas Explorer no longer shows the
  raw-HTML *Text* sub-tab, and a second **Flowable Expressions** tab sits right next to *Atlas Explorer*, so
  the whole-project map and the expression playground share a single window instead of two separate entry
  points. (The standalone *Flowable Expressions* tool window still works too.)

## 0.7.5

- **Service-operation “Used by” now also finds Java callers** — Java code that invokes an operation through
  the data-object runtime builder (`…createDataObjectInstanceQuery().definitionKey(key).operation("op")`) is
  now detected and listed alongside the model consumers. The `definitionKey` is resolved even when it is a
  `static final String` constant reference (a generated model-keys class) rather than a string literal, and
  data objects resolve through their backing service — so the operation, the constant and the Java class all
  line up on the same node. Each class node in turn lists the operations it calls.

## 0.7.4

- **Service-operation “Used by” now finds usages hidden in data-source URLs** — forms and pages most often
  invoke an operation through a REST data source, lookup or navigation URL (e.g.
  `{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=…&dataObjectOperationKey=…`)
  rather than a structured field. The operation and target keys embedded as literal query params are now
  detected, so these usages appear in the operation's **“Used by”** list. Data-object references still resolve
  through their backing service. (Verified against a real project: operations with recorded usages went from
  none to dozens.)

## 0.7.3

- **Service operations are now first-class in the Explorer** — every service-registry operation (e.g.
  `findByPodId`) gets its own node under a new **Service operations** category, with a **“Used by”** list of
  every form service button, data-object field and CMMN service mapping that calls it. Data-object references
  are resolved through their backing service, so usages reached via a data object and via the service
  aggregate onto the same operation. Operation keys are searchable, and each operation in a service's
  Operations list links to its own where-used page.
- **Bot name links to its Java bot class** — in an action's detail, the Bot field is now a clickable chip that
  navigates to the backing Java bot class node (platform bots stay plain text).

## 0.7.2

- **Rainbow parentheses now work in the Expression Playground field too** — the paren colouring moved from an
  annotator into the syntax highlighter (the highlighting lexer tags each round paren with a colour).
  Annotator highlights do not reliably paint in the playground's embedded editor field; syntax-highlighter
  colours do, so parentheses are now coloured in the playground and in inline `${…}` / `{{…}}` fragments
  alike. Colouring is **per pair**: each opening `(` takes the next colour and its matching `)` reuses it, so
  neighbouring pairs are visually distinct while a pair's `(`/`)` always match.
- **Playground field is a proper scrollable editor** — the expression field is now a multi-line code editor
  with vertical and horizontal scrollbars, so long expressions can be scrolled and edited comfortably instead
  of being clipped.
- **Syntax errors point at the exact spot** — the playground now shows the first structural error with a caret
  under the offending offset (e.g. the unclosed `(` for a missing `)`), so you see where to fix without
  hunting. (Annotator squiggles don't reliably paint in the embedded field, so the position is surfaced
  directly.)
- **Autocomplete for project custom functions, with parameters** — the extracted `externals.additionalData`
  functions are offered in completion: custom namespaces and top-level helpers at the root, namespace members
  after `ns.`, and custom `flw.*` members after `flw.`. Each lists its parameter names and, on selection,
  inserts `(params)` with the parameters selected so they're easy to fill in. Real names are read from the
  source — including from a compiled bundle's **sourcemap** (`*.js.map` `sourcesContent`), so even a minified
  `custom.js` yields `findCommonAttribute(allItems, path, identifierPath?)` instead of `(e, t, r)`.

## 0.7.1

- **Custom functions show their arguments** — each custom-function node in the Atlas Explorer carries its
  signature (e.g. `flowdemo.findCommon(customer, docs)`), read from the source: inline arrows / method
  shorthands / function expressions, and — in a compiled bundle — an identifier member resolved to its
  `function name(…)` declaration.
- **Frontend bindings link to the custom functions they call** — a `{{…}}` binding now references not only its
  form/model but also each custom function it calls (*Calls custom functions*), and every custom function
  lists the exact bindings that call it (*Called in bindings*), in addition to the forms under *Used by*.

## 0.7.0

- **Rainbow parentheses render again in frontend `{{…}}` (and backend `${…}`) expressions** — each nesting
  level of `( )` is coloured so a matching pair shares a colour and a missing one stands out. The colours are
  now forced onto the annotation instead of relying on a colour-scheme default that silently stopped rendering
  after a platform upgrade, so they show reliably in the playground and inline in `.form` / model fragments.
- **Custom functions are found in compiled frontend bundles** — a project that ships only the built
  `static/ext/custom.js` (Rollup UMD: `var additionalData = {…}`) or a nested `externals: { additionalData:
  {…} }` config now has its `externals.additionalData` functions read too, not just uncompiled source. A React
  `<Form additionalData={…}>` prop is not mistaken for a registration.
- **Custom functions are cross-referenced in the Atlas Explorer** — each project custom function (a
  `flowdemo.*` namespace member, an extra `flw.*` member, or a top-level helper) is a first-class node: it
  lists the forms/models whose `{{…}}` bindings call it, and each form lists the custom functions it uses
  (navigable both ways).

## 0.6.0

- **Project custom functions are read from source and validated precisely** — functions a project registers
  via `flowable.externals.additionalData` (e.g. a `flowdemo.*` namespace, or extra `flw.*` members) are
  extracted from the frontend-customization source in the project. Calls to them now validate exactly: a known
  member is valid, a close typo is flagged (*did you mean …?*), and unknown names are left alone. Compiled
  bundles that can't be read are skipped; unresolved constructs (spreads, computed keys) are noted, never
  guessed.
- **Payload preview no longer marks valid expressions invalid** — an expression that is correct but can't be
  evaluated statically (a running-form/locale member such as `flw.getUser`, or a custom
  `externals.additionalData` function) now shows a neutral *“not available in the payload preview”* note
  instead of a red error.
- **Leniency for custom `flw.*`** — an unknown `flw.<member>` with no close match to a built-in is treated as
  a project-injected custom function and not flagged; only a plausible typo is surfaced.
- **Atlas Explorer** shows a *custom functions* badge/panel listing the project's `externals.additionalData`
  functions and where they were read from.

## 0.5.0

- **Expression warnings are now real inspections** — unknown function / namespace / `flw.*` findings and the
  opt-in codebase grounding moved from a fixed annotator to *Settings → Editor → Inspections → Flowable*:
  severity is adjustable, they can be disabled per profile/scope, and they appear in *Inspect Code*.
  Structural syntax errors are still flagged directly.
- **Project allowlist for custom functions** — Alt-Enter on a finding offers *Add … to Flowable expression
  allowlist*: functions/namespaces your project registers itself (which the built-in catalog cannot know) are
  silenced project-wide. Stored in `.idea/flowable-atlas.xml`, shareable via VCS. The "ground backend
  expressions" checkbox moved into the (default-off) grounding inspection.
- **Expression Playground** shows semantic findings (allowlist-aware) in a status line.
- **Modernized Atlas Explorer** (bundled generator) — light/dark theme with toggle, responsive layout,
  keyboard & screen-reader support, no more 600-item list cap (incremental scrolling), working browser
  back/forward + a copy-link button, an SVG neighborhood graph per node, and a *⚠ parse issues* badge that
  lists files the generator could not fully analyze. Structural syntax errors and "unknown function" findings
  are now separate *Invalid* / *Suspect* categories, and `--expr-allowlist` suppresses findings for
  project-provided functions.
- Internals: diagnostics logging at previously silent failure points (model indexing, generator, Inspect
  client), consolidated JSON/wrapper helpers.

## 0.4.1

- **Requires an IDE restart on install / update** — the plugin registers languages, file types and parser
  definitions (the two expression dialects) that cannot be loaded dynamically, so IntelliJ now prompts for a
  restart instead of silently half-loading (which left the Flowable Expressions tool window and its stripe
  icon missing).
- **Open the Expression Playground from the menu** — Tools → Flowable Atlas → *Open Expression Playground*
  shows the Flowable Expressions tool window directly, independent of the tool-window stripe button.
- **Fixed the tool-window icon** — a correctly sized (16×16) icon that renders reliably across display
  scalings, instead of the oversized plugin icon.

## 0.4.0

- **Flowable expression language support** — first-class editor support for both the backend JUEL dialect
  (`${…}` / `#{…}`) and the frontend form dialect (`{{…}}`): syntax highlighting, rainbow parentheses, brace
  matching, live validation (syntax + unknown functions/namespaces/`flw.*` members against the verified
  Flowable catalog) and completion of functions, root objects and the project's variables/form-fields.
  Expressions are recognised inline in BPMN/CMMN/DMN XML, `.form`/`.page` JSON and (optionally) Java strings.
- **Expression Playground** — a *Flowable Expressions* tool window to try an expression with live validation
  and completion, evaluate a frontend expression against a pasted JSON payload, or evaluate a backend
  expression against a running app via the Flowable Inspect REST API (connection auto-detected from the
  project's Spring config).

## 0.3.0

- **Renamed to Flowable Atlas** (formerly Flowable Keys) — the plugin now also produces the Atlas explorer,
  not just model-key tooling.
- **Generate Atlas Explorer** — Tools → Flowable Atlas → *Generate Atlas Explorer* runs the bundled Atlas
  generator over your project and writes a single self-contained, interactive HTML page. Choose where to save
  it in the project, then open it in the external browser or in an embedded in-IDE viewer. Any
  `*.explorer.html` in the project also opens in the embedded viewer.
- **All artifacts option** — a *Generate* scope in Settings → Tools → Flowable Atlas switches the action from
  "explorer HTML only" to writing the full Atlas set (summary.md, overview.md, graph.json, explorer.html,
  CLAUDE.md) into a folder you pick.

## 0.2.0

- **Infix key search** — completion now matches any fragment of a key, so typing `0061` at
  `definitionKey("…")` proposes `DEMO-DO-0061` (start / word-boundary matches still rank first).
- **Scoped task/activity completion** — `taskDefinitionKey` / `activityId` are narrowed to the sibling
  `processDefinitionKey` / `caseDefinitionKey` in the same query chain, falling back to the project-wide union
  otherwise.
- **Model → model references in BPMN/CMMN XML** — completion, Ctrl/Cmd-click navigation and a broken-key
  inspection (with quick fix) for `calledElement`, `flowable:formKey`, `decisionRef`,
  `decisionTableReferenceKey`, CMMN `caseRef` / `processRef`.
- **Generate a typed data-object bean** — Alt/Option-Enter on a data-object `definitionKey("…")` writes a Java
  POJO from the model's field mappings.
- Long project scans now honour cancellation (no UI stall while indexing / finding usages).

## 0.1.0

- Initial release: context-aware Flowable model-key completion, cascade completion, broken-key inspection, key
  navigation & Find Usages, model-constants generation, and Liquibase changelog awareness.
