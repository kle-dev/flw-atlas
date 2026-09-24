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

## 0.27.1

- **The Atlas Hub's ⋮ menu shows the right entries again.** 0.27.0 handed the menu unloaded placeholders of
  its actions instead of the actions, so none of them got to decide whether it applies: the *Open Environment
  in Browser* submenu drew as an empty row, and *Copy Model Key* and *Compare Model with Archive* showed in a
  panel with no key or model to act on. The menu is *Tools → Flowable Atlas* again, entry for entry, with the
  same entries hidden. *Dump Key Index* stays out of it outside internal mode.

## 0.27.0

- **The Atlas Hub fits a side stripe.** It was laid out at the width its widest row asked for — 567 px
  with an ordinary environment name — so the stripe had to be dragged half across the screen before nothing
  was cut off. Every row now fits 280 px and a wider stripe only gives the names more room: the project
  picker has the first row to itself, the model count and *Rebuild* the second, the attention line wraps,
  the buttons under a block's title say *Generate…* and *Open* (the full names are their tooltips), the
  *Pull* button spans the block, and a name too long for its row ends in `…` with the whole of it in the
  tooltip. Each block folds at its title and stays folded. Dragging the stripe narrow again after it had
  been wide now works too — the platform's foldable group reported its last width as its minimum.
- **The Hub says what it could not do, where it could not do it.** A Design workspace list that cannot be
  read shows the reason under the workspace picker with *Retry*, instead of a balloon that was gone by the
  time anyone looked; the picker says *loading workspaces…* while it asks. The model count's row says
  *scanning…* or *index failed* while there is no index — it used to go blank, because a disabled link
  hides itself. A removed environment is said once, in the attention line and in the picker, not also in a
  red note beside it.
- **Atlas Findings says why, and stays current.** A pane beside the tree explains the selected check the
  way the explorer's Checks page does — the kind of finding, why it matters, what to do, a link to the
  checks page — and opens the finding's model in the explorer. A status line says how current the analysis
  is (*17 defects · 24 advice · analyzed 2 min ago*) and, once a model changed since, how many and *Analyze
  Again*. Generating the explorer brings the window up to date without a second analysis; it used to
  analyse only when opened and after an accept. *Accept…* asks in a dialog that refuses an empty reason, the
  two filters sit behind the Problems view's eye instead of drawing as text buttons, and the tree expands and
  collapses from the toolbar. The Hub shows the counts in a health row of its own — a link into the window,
  or *Analyze findings* before any analysis.
- **The explorer tab loads, fails and comes back where it was.** A spinner at the right of its toolbar says
  the page is loading — the tab used to stay blank until the browser painted — and a page the embedded
  browser cannot load is replaced by a panel naming the file and the reason, with *Reload* and *Regenerate
  Atlas Explorer*. A tab open when the IDE closes reopens on the page it was left on instead of the
  dashboard. A link to a file the page no longer finds offers *Regenerate Atlas Explorer* on the balloon
  that says so, and opening a file from the page no longer resolves it on the UI thread.
- **The explorer opens under Remote Development whatever its size, and says how far it has got.** A
  17.8 MB report sat on *Loading … over the IDE connection* and never arrived: it was sent uncompressed, as
  35 one-megabyte answers at once. It now travels gzipped, at a sixth to an eighth of its size, a few parts
  at a time, and the card shows how much has arrived, how much is left, the speed and the time remaining. The
  IDE connection can lose an answer without a word, so a part missing for 30 seconds is asked for again;
  if it is still missing, a card says what arrived, and its *Retry* asks only for the missing parts. It
  used to be a spinner for good. The thin client keeps the compressed
  page, so a report of up to about 20 MB opens without any transfer the next time; a larger one is fetched
  on every open, and the card says so.
- **The model picture is a sheet you can point at.** The drawing stays white in every theme, as in the
  explorer, but now sits on the panel as a framed sheet rather than a white block with no edge in a dark
  IDE. The element under the pointer is outlined, the pointer turns into a hand and the tooltip names the
  element, so what can be clicked is visible before a click. A click leaves the focus on the picture, which
  is what the zoom keys needed — they were registered on a component that never got the focus. The toolbar
  gains *Actual Size* and a zoom readout. Half-typed text that does not parse keeps the last picture up,
  with a line saying so, instead of flipping to "no layout" at every keystroke. Opening a subform is ⌘-click
  on macOS, where Ctrl-click is the context click.
- **The playground's panes each say one thing.** What just happened to the context — *Using QA · CAS-4711*
  after a paste, *Saved as QA* — is said on a line under the context's summary instead of being written
  over the last result, and a value's type sits beside the result's caption (*Result · string*) instead of
  being padded onto the value. *Evaluate Against App* is *Evaluate Against Work*, its shortcut is spelled
  the way your keymap spells it, and it — like *Show Sub-Expression Values* — stays on the toolbar, disabled
  with a reason, in the dialect it does not apply to, so switching the dialect no longer moves the buttons.
  The backend row keeps *Paste Work URL…* and puts naming or forgetting targets and *Manage Environments…*
  behind one ⋮; the gear's *Environment Settings…* became that same *Manage Environments…*, and the Scripts
  tab, which has no environment, lost it. The Scripts tab's context says how much it holds (*5 bindings · 34
  beans*) rather than repeating the toolbar, remembers whether it is folded, says so when nothing is bound or
  touched, opens its pickers under their buttons, and tells a failed model scan apart from a project without
  scripts.
- **Settings say whose they are.** Every project settings page names the Flowable project its values
  belong to when that is a question — a sub-project is chosen, or the repository holds several — since the
  choice is made elsewhere, in the Atlas Hub. *Apply* on the Environments page with something missing
  selects the environment or connection it is about. The model-constants identifier and format read as
  choices with an example (*Name and key — ORDER_FULFILMENT_P_0001*) instead of enum names, the
  custom-functions source is only editable while discovery is on, the copy button is disabled on a
  connection, and *Share with Project* wears a share icon rather than a save icon. The sign-in form lost two
  paragraphs to help marks, the Work connection reads *Server URL* like the Design one and is called Work
  wherever it was called "app", and settings paths read *Settings → Tools → Flowable Atlas → …*.
- **Dialogs check before they close.** *Paste Session…* refuses a paste that carries no session header —
  it used to close and report the problem afterwards, with the pasted text gone — and *Sign In to Flowable*
  (no longer "Flowable App": Design uses it too) enables *Use this session* once a session cookie has been
  seen. *Create Access Token* is laid out in one column of labels, says where the token goes (the
  access-token field; Apply stores it) and that a blank validity never expires. The two Generate dialogs are
  one column of labels from the source to the footer, name their patterns as the settings pages do, and
  point a problem with the selected rows at the table. The Liquibase dialog's *Browse* no longer writes an
  absolute path, and an absolute folder typed in is refused — the check trimmed the leading slash first, so
  one got through and every row read "new".
- **One name for every Atlas surface, one verb for opening it.** The tool windows are *Atlas Hub*, *Atlas
  Findings* and *Atlas Playground* — the playground used to be called *Expression Playground* although half
  of it is scripts — and every entry that opens one reads *Open Atlas …*. Under *Generate* the entries no
  longer repeat the submenu's verb (*Atlas Explorer…*, *Model Constants*, *Liquibase Changelogs → From Data
  Object…*), while *Find Action* shows the whole sentence, so the two *From Data Object…* entries are no
  longer lookalikes there. *Generate Model Constants* lost an ellipsis it never earned: it asks for nothing.
- **Atlas Findings wears the explorer's Checks glyph** instead of the IDE's own Problems icon, and
  *Regenerate Atlas Explorer* wears the platform's build hammer, so it can be told from *Reload* beside it.
- **Every gutter mark can be switched off.** The four marks are listed under *Settings → Editor → General →
  Gutter Icons* by name — *Flowable: Java referenced by models*, *bot used by actions*, *REST handler called
  by models*, *model picture* — and a click on a mark that finds no model any more says so instead of doing
  nothing.
- **Balloons instead of dialogs.** *Open Atlas Explorer* with nothing to open, the Open-in-Explorer intention
  in the same situation, and an explorer file that went missing say so in a balloon with the fix on it
  (*Generate Atlas Explorer…*, *Regenerate Atlas Explorer*) instead of a modal question. *Show Details* opens
  a read-only editor tab whatever the text's length; it used to be a dialog when short. The two notification
  groups are listed as *Flowable Atlas: needs attention* and *Flowable Atlas: finished jobs*, and every
  balloon now lands in the group its kind belongs to.
- **Context menus about what was right-clicked.** A model file in the Project view offers *Open in Atlas
  Explorer*; *Go to Model…*, which ignores the selection, left that menu. In the editor, *Copy Model Key* and
  *Compare Model with Archive* sit together behind one separator. The generate actions stay available while
  the IDE indexes.
- **The actions table says which bot runs each action.** `#/browse/action` gains a *Bot* column: a Java bot
  by its class, a platform bot by its key, as a link to the bot, with a copy button for the class name or
  key and, inside the IDE, a button that opens the Java bot's source. The *Bots* and *Java · bot* tables
  answer the other way round with an *Actions* column. A link inside a category row now opens its own
  target; it used to open the row, or, after a detail page had been shown, both.
- **The identifier in a table row copies out of it.** A category table copies each node's key — a Java
  class's full name — and the tables on a model's page copy the element id, the key of the form, decision
  or process a row points at, a field id, a property or parameter name. The button shows on the row under
  the pointer or the keyboard. The copy buttons on the overview's chips were never wired, so a click on one
  opened the chip's node; they copy now.
- **The explorer uses a wide window.** In a browser on a large screen the overview and the report pages
  stopped at 1160px and a node's page at 1000px, which left about half of a 2560px window empty beside a
  squeezed table. They now grow up to 2000px; descriptions keep a readable line length, and a check's
  description on the health list hugs its words instead of stretching a pill across the row. An IDE tab's
  width is unchanged.
- **A narrow explorer keeps its navigation on the left.** An editor tab of 800px or less — the explorer
  between two tool windows — got the phone layout: no sidebar, the whole navigation in one drop-down at the
  top. With a mouse it now keeps the sidebar as its icon rail, which flies out on hover or focus, and the
  list becomes a drawer over the page that closes once it has opened a node. Only a touch screen still
  stacks. The breadcrumb no longer runs into itself when squeezed: the path above the page gives way first,
  and in a bar too narrow for a path it shows the page's own name alone.
- **Services, data objects and changelogs name each other in their tables.** The *Services* table gains a
  *Data objects* column, the *Data objects* table a *Service* column, and both a *Liquibase* column with the
  changelog that creates the table — for a data object through its service, the live definition first.
  Each is a link with its key to copy. A copy button shows the ordinary pointer now, not the copy cursor
  with its plus badge.

## 0.26.0

- **A `.zip` or `.bar` in the Project view opens up.** A Design export in the repository could be searched
  but not browsed: the platform expands an archive only when it is a library, and for one inside a content
  root it mounts the archive and then drops every entry. A `.bar` was not even an archive to it, so a
  double-click asked which file type it was. Both now expand into their folders and entries, and a model
  opens straight from the tree, read-only.
- **A model opens beside its picture.** A process, case, decision, form or page, loose or out of an
  archive, opens as text and picture side by side: the diagram, the decision table, or a form's wireframe.
  The picture is painted in Swing on the IDE host with the SVG library the platform draws its icons with,
  not in the IDE's SVG viewer, which is a browser panel — slow under Remote Development, and one that
  opens on the markup until its layout toggle is clicked. It fits the width, zooms from its toolbar and
  redraws when the file changes on disk. The diagram gutter icon opens this editor with the picture
  showing.
- **Forms and pages have a picture at last.** A wireframe of Design's twelve-column grid at its real
  proportions: each component as a placeholder of its kind with its caption, a star when it is required,
  and its id underneath — the name a `{{…}}` or a script reaches it by. Panels, modals, tabs and
  accordions keep their own grids, a data table shows its column headers, a subform names the form it
  embeds. A `visible` or `enabled` that depends on an expression is spelled out beside the id; one that is
  plainly off greys the component out. It is a developer's map of the form, not a preview of the Work UI.
  The gutter icon on a form key — in Java, on a user task's `flowable:formKey`, on a subform reference —
  opens it like any other diagram. All 153 forms and pages of two real app corpora render.
- **The Structure tool window outlines a model.** A form's or page's components as Design nests them, by
  caption with the id beside it; a process's or case's elements by name, without the connectors, the plan
  items and the diagram interchange. A click goes to the component or the element, in an archive entry as
  much as in a loose file. Any other JSON or XML keeps its usual outline.
- **Any file opened out of an archive is safe from the formatting-layer crash.** Since the Project view
  expands archives, a `manifest.json` or a model under a name Atlas does not recognise opens just as
  easily, and is just as read-only and minified. The platform's visual formatting layer throws on such a
  file (`Wrong line: 1. Available lines count: 1`); the guard that kept it off model files now keeps it
  off every file inside an archive.
- **A detail page in four tabs.** Under the title, a page is *Overview* — Design's description and a
  process's documentation as prose, the facts, and the picture: the drawing, or the table that *is* the
  model — *Findings*, *Connections* — whether it fits what it meets, and its relations — and *Details*:
  elements, fields, parameters, variables, the tests that deploy it, *Other attributes*. A tab with
  nothing to show is left out, Findings counts what is open and Connections carries a dot when a table
  found a gap. The tab is part of the link (`&p=connections`), carries over to the next page that has it,
  and `1`–`4` pick one. A section is a heading with its count and its explanation behind an ⓘ, and every
  section starts open but *Other attributes*. On a model's own page a finding drops the model, the file
  and a severity its check's head already says.
- **Nothing cut off is out of reach.** Text cut to fit — a table cell, a chip, a tag, a card's title, a
  name in the list — says itself in full on hover and on keyboard focus. In a narrow panel a table keeps
  every column it cannot do without on the row: a table with three of them put the third into a 1em
  track, a Liquibase column's type reading "v…" and a finding's message one letter per line.
- **⌖ always lands.** From any tab, a *Show on diagram* button brings the drawing up on Overview with the
  element selected and in view below the tab bar; a form's field rows have one too. An element the
  drawing does not show keeps a faint ⌖ that says so, and a page with no drawing offers none — both used
  to be buttons that did nothing.
- **Zooming a drawing on a trackpad.** ⌘/Ctrl + scroll zooms by how far the wheel turns instead of a fixed
  step per event, so a trackpad no longer jumps; a pinch zooms too. Inside IntelliJ, whose browser rounds a
  trackpad's steps and delivers many as zero, every zero step zoomed *out* — zooming in barely worked
  there. A zero step is no step now.
- **A leftover TODO says where it is.** In a JSON model the finding names the element that carries the
  marker and quotes it — *TODO in the label: "Current Pod (TODO)"* on the field, which ⌖ finds — instead
  of a path like `rows[1].cols[0].label` and "TODO left in the model". The same form as an app's `.form`
  and as a Design export is one finding per marker, not two, and a one-line model gives no line number.
- **Detail pages ask whether a model fits.** On the Connections tab each question is a section of its own
  — *Calls*, *Called by*, *Fields and the variables they write* — and every gap table is drawn one way,
  the schema coverage table first: a row per thing that should line up, tinted by how badly it does not,
  a pill per kind of gap in its heading and *only gaps* to hide the rows that are fine. A cell says ✓ it
  fits, ✗ it is missing, ⚠ it looks wrong, or ? Atlas cannot tell — and its tooltip says what the mark
  means and why.
- **A health strip under every title.** Whichever tab is open, a page says whether the model is fine: its
  open defects and advice, the gaps its tables found (or *✓ fits*), how many models it uses and is used
  by, the apps that ship it — or *in no app* — and the tests that deploy it; each brings up the tab and
  the section that explains it. The facts stop repeating what a section says: a form states its outcomes,
  who opens it and where the outcome lands; an operation shows its call as one line and the endpoint that
  answers it; a class its bean names; a sequence what its numbers look like; a query the groups it
  filters by, where it said *0* before. The orphan banners on operations and functions are gone — the
  finding says it.
- **Relations take a row per relation.** A relation lists its neighbours as chips on one row — *App
  contains* five models is one row, not five — and unfolds only where a neighbour has more to say: a
  caller's parameter mappings, every REST call with its verb. The drawing is a switch away and stays on
  from page to page. An operation's page is related to its service, and a variable's page lists the
  models that write, read or merely mention it, where both used to say they had no relationships.
- **A process's page says whether its calls fit.** *Does it fit?* lists every call a process or case makes
  — sub-process, case, decision, operation, agent, form, event — with what it hands over and takes back,
  and every caller of it against what it reads: a value it reads that no caller passes, a value passed in
  that it never reads (the same verdict as the *unread call input* finding), a value mapped back that it
  never writes, a required operation parameter left out or one the operation does not declare. A process
  nothing calls lists the values whoever starts it has to provide. Where Atlas cannot see far enough — a
  callee outside the project, a call with no explicit mappings, a name only a script guesses at — the cell
  is a ? that says why.
- **A service's page says whether its operations fit.** Per operation: who calls it and whether what they
  pass fits its parameters, the endpoint of this project that answers it, whether that handler serves the
  operation's verb and every `{path variable}` has a parameter, and the handler method with its line. An
  operation's own page lists every caller against every parameter — a form button, a task, a Java class or
  an agent that fills them itself. Where no changelog lets the schema coverage compare them, the service's
  column mappings are held against the data object's fields; a data object's properties name the service
  column behind each field and the forms that show it.
- **Decisions, forms and actions say whether they fit.** A decision table is drawn as Design draws it —
  the hit policy in the corner, Input and Output bands, each column headed by its label, expression and
  type, a number per rule — and lists every model that runs it: an input the caller never writes before, a
  result it never reads. A form's page lists every call it makes — buttons, data sources, REST calls with
  the verb checked against the handler — the variables its fields write and who reads them, the
  data-object paths it binds that are no field of the object, and, for each task that shows it, the
  outcomes no condition tests and the tested values that are no outcome. An action lists the buttons that
  invoke it against what its script reads with `flw.getInput`.
- **Apps and groups say who can reach what.** An app's page lists every model its members reach and
  whether it ships them: in this app, in another app, in no app at all, or not in the project — and the
  models only packed beside it that its definition does not list. It also lists every group with a right
  on it or its members, and flags one that may start a process or work on a task of the app but cannot
  open the app. A group's page shows, per model, what the group may do and the app it gets there through —
  or that it can open none of the apps that ship it.
- **Events, signals, endpoints and classes meet their counterparts.** An event's page holds every payload
  field against every element that publishes or consumes it — a field no publisher sends, a correlation a
  receiver does not supply — and its channels against who uses them; a channel lists the events it carries
  without a publisher or consumer. A signal, message, error or escalation lists who throws and who catches
  it, and an error thrown but never caught is a gap. An endpoint lists every call that reaches it with the
  verb each uses; a class's methods name the models that call them, and its bean names the expressions
  that use them. An agent's page holds its tools against the models and operations they name, and its
  callers against the operations it has.
- **A form's page opens with its layout.** The wireframe the IDE's model preview draws — the twelve-column
  grid, panels and tabs, every component with its caption and id — is the picture of a form or page in the
  explorer, clickable like a diagram: a component opens its card (what it is bound to, what it calls, its
  parameters) and *Show in details* lands on its row; finding badges sit on it. The explorer, the IDE and
  the diagrams folder draw every model through one renderer, so the diagrams folder now holds forms, pages
  and layout-less decision tables too. A page keeps its drawings within a budget, so a project with
  hundreds of forms does not double in size; a form left out says so.
- **What a model's variables are for.** *Variables & expressions* lists a model's variables as a table —
  how this model writes and reads each one, which other models share it, and the unused-variable verdict —
  before the expressions, bindings and functions it uses. An SLA checks that the task it watches exists in
  each model it governs, a query that some queried model writes the variable behind each column, and a
  template that the models rendering it provide every variable it prints.
- **A REST call links to the handler for its verb.** `GET /api/orders/{orderNumber}` and `POST
  /api/orders/archive` share a path shape — the variable takes `archive` — and path-only matching linked
  each call to both handlers. A call now reaches the handler for its verb, and of those the one that
  spells out most of the path, as Spring routes it; a verb no handler serves is a suspect link that says
  *verb differs*. The IDE's endpoint gutter and Find Usages use the same rule.
- **A service or agent task configured by fields calls its model.** A service-registry task whose service
  and operation are field injections (`serviceKey`, `operationKey`) — or an agent task naming its
  `agentModelKey` that way — was linked to nothing, so its operation was reported unused. It is linked
  like a task with a mapping element now, and a data-object task's operation counts as used too. And a
  task's `in` into a service or agent names the callee's parameter, an `out` its result field: neither is
  a variable of any scope, so neither is reported as an input the callee never reads.
- **Scrolling over a form's layout no longer stutters in the IDE.** With the pointer resting on a drawing,
  every wheel step waited for the page's zoom handler, and each form row sliding under the cursor redrew a
  blurred highlight — which the IDE's off-screen browser copies into Swing frame by frame. A plain wheel
  over a drawing now scrolls without the page's script in the way (⌘/Ctrl + wheel still zooms), the
  drawing ignores the pointer while the pane scrolls, and a form's cells highlight with an outline instead
  of a blur.
- **A subform shows the form it embeds, and opens it.** A subform was a dashed box with its id: nothing
  said what it held, and nothing opened the form behind it. The wireframe now draws the embedded form inside
  the box (nested subforms three levels deep, a form embedding itself once), in the explorer, the IDE's
  preview and the diagrams folder alike. A double click on the box opens that form — in the explorer, and
  in the IDE, where Ctrl/⌘-click does too. Its card and its row in *Fields* link the form. The element card
  of any drawing also closes on a click outside it, not only on its ✕.
- **Every operand of a long `||` gets its value.** With *Show Sub-Expression Values* on, `a || b || c ||
  d` showed values for its last operands only, and one spot carried two: the chain parses as `((a || b) ||
  c) || d`, so the first operands sank below the depth the hints stop at, and every inner `(…) || c` ended
  where `c` ends. The operands of a chain are siblings now, each with its own value; an operand of `||` or
  `&&` is hinted even when it is a plain flag, one the evaluation short-circuited says *skipped*, and a
  spot never carries two values.
- **The playground's panes can be resized after a long expression.** The message rows under the expression
  field — and, under Remote Development, its sub-expression rows — asked for their whole text's width, and
  the playground's splitters honour that: after a long expression the divider to the payload was stuck.
  The rows are clipped now, with the full text on hover, and at most eight sub-expression rows are listed.
- **Flowable's own beans are not an external library.** A bean the platform ships — `flwTimeUtils`,
  `initVariablesService` — was listed under *External / library* with the third-party classes. It has a
  category of its own, *Flowable platform*, beside *Flowable API*; a model key given as an expression is a
  *Dynamic reference*.
- **Operations Java calls through the service registry are used.** A service-registry invocation —
  `.serviceKey(…).operationKey(…)` — was invisible, so an operation only Java called was reported unused and
  nothing linked it to the code. The call is rarely one statement — a helper sets the service key and a
  lambda elsewhere names the operation, or the class hands its own `SERVICE_KEY` constant to a shared
  client — and each shape is followed now. On one real project twelve operations show the Java class that
  calls them and six *unused operation* findings are gone. In the IDE, `.serviceData("name", v)` is
  checked against the operation's input parameters, as a data object's `.value(…)` already was.
- **A Spring property a model reads links to where it is set.** `environment.getProperty('crm.base-url')`
  and `propertyConfigurationService.getProperty("…")` in a model are edges to a property node whose page
  lists the file and line of every `application*.properties` and `application*.yml` that sets it, in
  Spring's relaxed spelling. Ctrl/⌘-click on the key in the expression opens those lines. A property set
  nowhere in the repository is not a finding: the value may come from the environment.
- **User definitions and tenant setups join the graph.** A `.user.json` user definition links to the forms
  that create, show and edit such a user and to the groups it joins; a tenant setup to the groups it
  defines and the user definitions its users are of. A tenant setup contributes how many users of each
  kind it creates, never their logins or passwords. A form the platform ships is not reported missing.
- **More of what Java hands the engine is seen.** A map passed to `startProcessInstanceByKey`, `complete`,
  `.variables(…)` or `setVariables` writes every name put into it. A model that renders a template against
  its whole variable container reads every variable it holds, so none of them is reported unread.
  `mainContentTemplate(…)` and `userDefinitionKey(…)` name a model like the other key-taking calls.
- **A model's page names the tests that deploy it**, from `@Deployment(resources = …)` and its CMMN, DMN
  and app siblings; a master-data definition lists the files that load its rows.
- **Explorer pages for endpoints and groups.** An endpoint lists who calls it, with the verb, the URL as
  the model spells it and the button that does; a group lists what its members may do per model. A form's
  REST call or a service operation whose URL lands on a project endpoint links to it. A table past a
  hundred rows, or a relation past sixty neighbours, shows the first ones and *show all*. On a touch screen
  a vertical swipe over a diagram scrolls the page and two fingers zoom.
- **One vocabulary for findings.** A defect is labelled by its severity, *error* or *warning*; an advice
  finding is labelled *advice* and drawn grey — in the health rows, the finding pills, the diagram badges,
  the Checks filter (which gains an *advice* chip) and the Atlas Findings tool window, where advice wears
  the information icon. Every advice row used to read WARNING under the *Advice* heading. `graph.json`,
  the summary and `--fail-on` keep the severity; a saved `#/checks&c=warning` link now keeps a defect's
  warnings only, and the sidebar's *Checks* badge is grey while only advice is open.
- **A View menu in the explorer's top bar.** The two switches that sat there as bare glyphs — `≈` for
  uncertain links and a flag for the finding badges on diagrams — are labelled items of one *View* menu,
  each with a line saying what it shows, and the button carries a dot while something is hidden. It is
  keyboard-driven like a menu: `↓` opens it, `Space` flips a switch, `Escape` closes it.
- **The explorer fits an editor tab.** A window of 1100px or less — the IDE editor tab the page is
  mostly read in — gets a compact, labelled sidebar instead of the rail of 27 unlabelled icons; the rail
  is still there by dragging the sidebar's edge below 140px, and a double-click on the edge goes back to
  the automatic layout instead of pinning 240px. A tab strip holding more than it can show fades the edge
  that hides tabs, scrolls with the wheel, and lists the hidden tabs behind a **+N** button. The browse
  list is narrower there and folds away from a button in its head — or by dragging its edge shut — to give
  the page the whole width; a long name no longer pushes its findings pill out of sight.
- **A node's relations in one section.** A page told its relations three and four times — a neighbourhood
  drawing, *Uses / references*, *Used by / referenced from*, and a type's own copies (*Called by*,
  *Access*, *Subforms*, *Tools*, *Used by*, *Called with*). *Relations* is one section on the Connections
  tab: a list per direction with a row per relation and neighbour, carrying what the old
  sections added — the element that makes a reference, a REST call's verb and URL, a tool's operation, and
  a caller's parameter mappings in its expanded row — with chips for *uses*, *used by*, *uncertain* and
  *with mappings*. An expression, a binding, a function and a service operation get the same section from
  their own lists. The section listing the variables and expressions a model touches is called
  *Variables & expressions* now; as *Uses* it collided with the drawing's USES column.
- **A process's elements in one section.** User tasks, service tasks, script tasks, call activities,
  events, gateways, sequence flows, lanes, listeners and documentation — for a case its plan model,
  sentries and event listeners — were a section each, seventeen on a big process. They are the groups of
  one *Elements* section on the Details tab now, each keeping its own table, with a chip per kind that
  keeps only that kind and one filter over all of them.
- **The overview summarises instead of repeating.** Its health block was the Checks page's list and its
  inventory the sidebar's entries as ungrouped chips. Health is now two numbers — the open defects and
  advice, each split by tier (broken, runtime risk, unfinished, noise) — the five checks with the most
  to say and one line for the rest, every part a way into the Checks page; the inventory lists the
  sidebar's entries grouped as the sidebar groups them. The two-column layout follows the overview's own
  width, so an editor tab stacks it where the room runs out.
- **A category opens as a table.** Picking a category no longer shows its list beside an empty page: the
  page is a sortable table of the category — name, key, file, references in and out, open findings, and
  a column the type is worth more with (a model's app, a service's table, a Java class's package, a
  review list's finding). A header sorts, a second click reverses; the filter is the search engine's;
  marking, the keyboard and middle-click work as in the list, which steps aside and comes back beside the
  node a row opens. The sort travels in the link, now also by key, out-degree and findings.
- **Every shortcut behind `?`.** `?` — or the new **?** button in the top bar — opens a sheet of every
  keyboard shortcut, grouped by where it works and generated from one table. It replaces the sentence in
  the empty detail pane, which only someone with nothing selected ever saw, and which said tabs switch
  with Alt+←→ (that is history; tabs are Alt+[ and Alt+]).
- **The schema coverage table on every link of the chain.** A data object's page and a Liquibase
  changelog's page show the same *Schema coverage* table the service page has — every column from the
  changelog through the service mapping to the data object field — for each service whose coverage names
  them, so the table no longer has to be looked for one hop away. Its `⇄ crossed` marker wraps instead of
  being cut off in a narrow column.
- **Smaller things in the explorer.** A list row's findings pill carries its tone's icon and says what it
  counts, the reference count beside it carries a link icon and says so too, and a key that only repeats
  the name is shown once. What accepting a finding does is said on the *accept…* button and in its form,
  instead of in a paragraph above every model's findings. Empty states draw their icon instead of a
  glyph the embedded font did not have, the Checks page's subtitle states its numbers and leaves the
  explanation to its two headings, and an accept button's focus ring is no longer clipped by its cell. A
  check's block opens with one line — what it means, then its buttons and *why · what to do* — where it
  had four, and no longer repeats its rows' severities as a row of chips.
- **The findings in a tool window of their own.** *Atlas Findings* lists every defect — and, when asked,
  the advice and the accepted findings — from the same analysis the explorer is built from, grouped by
  check. A double-click opens the finding's file at its line, and *Accept…* writes the selected findings,
  with a reason, to the `waivers.json` the explorer's Save writes, so the page, the CLI gate and this list
  agree. It is Swing, so it works under Remote Development and in an IDE without JCEF, where the explorer
  was the only place to see or accept a finding.
- **The model preview is interactive.** A click on a process, case or decision element — and on a form
  component or a decision table's rule — puts the caret on its declaration beside it, the id itself
  rather than a flow or a JSON key that mentions it first (Go to Symbol and Search Everywhere land there
  too), Ctrl/⌘ + wheel zooms about the pointer, a drag pans, and Ctrl/⌘ + `=` `-` `0`
  zoom in, out and fit. The picture follows the editor's unsaved text, and a renderer that fails says so
  instead of claiming the model has no layout.
- **Smaller things in the IDE.** The Hub's *archives could not be read* line shows which archive and why
  when one is picked; Recent Models can drop one entry or be cleared. Success balloons have their own
  notification group, *Flowable Atlas Results*, so they can be muted without muting warnings, and the
  restart-the-IDE notices are balloons instead of dialogs. The playground tool window is called
  *Expression Playground*, like the action that opens it. The model outline sorts by name, and an archive
  packed inside an archive says it is not expanded.
- **A condition written on its own line is read.** Design indents a sequence flow's condition, a
  script and a decision entry onto their own line inside a CDATA section, and only the first piece of
  text after the tag was read, which was the indentation. The flow lost its condition, the gateway was
  reported as an implicit parallel split instead of a missing default, and a script body came back
  empty.
- **The model index follows whole folders, and only this project's.** A folder created, deleted or moved
  (a checkout adding `process-models/`, an unzipped export) arrives as one file event for the folder, and
  the index did not notice it until a Rebuild. A zip landing in `~/Downloads` or a model saved in another
  open project, on the other hand, dropped the index and rescanned every archive. A project that itself
  lives under a folder named `build`, `out`, `bin` or `target` (a CI agent's workspace, a dev container)
  had every file excluded and an empty index.
- **Forms inside a Design export are models without a setting.** An app export keeps its forms, pages,
  actions and data objects only as `form-models/X.json` and the like. The command line always read them;
  the IDE did only with *Also index raw Flowable Design workspace sources* on, so with the default setting
  those forms had no key, no wireframe, no outline and no icon, and code naming them was flagged as
  broken.
- **Generation does what it says.** It saves open edits first. *Cancel* stops it, and nothing is
  overwritten, where the run used to finish and write regardless. The written files are refreshed in one
  go off the UI thread, not one by one on it. *Regenerate* re-analyses the folder each page was made from,
  once per report, where it analysed whichever sub-project was active for every page it found, all at once.
- **The "keys removed by this pull" warning fires.** The pull took its before-snapshot after the refresh
  that had already dropped the index, so the snapshot was always empty.
- **No more blank explorer.** A control character in a model string (a vertical tab pasted from Word
  into a description) or a commented-out `<script>` tag in an HTML component broke the page's data
  island, and the page showed nothing. `graph.json` was invalid JSON for the same reason.
- **Java behind a `"/api/**"` or `"http://…"` string is read.** The `/*` inside a mapping or a URL
  opened a comment that swallowed the endpoints, beans and key literals after it.
- **The explorer opens Java where it is.** A class opens at its declaration's line, a method page opens
  the method (it had no file at all), and the dashboard's "+ N more" entry points expand in place.
- **Fewer freezes.** A gutter click on a Java symbol or an endpoint, and the rename warning, held the read
  lock for the whole model scan, so typing stopped until it finished. Project custom functions were read
  inside the highlighting pass; they are now read in the background, honour the custom-function settings
  and are read again when a `.js`/`.ts` source changes, so a new function is known without a restart.
  A call argument in Java is resolved only when its method name is a Flowable API, Go to Symbol no longer
  repeats its searches for every matching name, the Hub no longer walks the project for explorer pages on
  every refresh, and opening a result reads the model off the UI thread.
- **Large files and odd input no longer take the run down.** A multi-megabyte form, a database dump in
  the repository or an image inside a nested archive was read whole before its size was checked, and one
  of them could exhaust memory. A symlink back to a parent folder looped the walk. An entity past
  U+10FFFF dropped its model, and a broken `\u` escape or JSON nested thousands deep failed with an error
  nothing caught.
- **Smaller fixes.** A qualified `flowable:class` that matched only a project class of the same simple
  name is marked uncertain. A waiver whose `until` date cannot be read is reported instead of never
  expiring. Two models whose keys clash (or differ only in case) each keep their diagram file. A decision
  rule's annotation is the rule's own. A form's `"visible": "true"` is the literal it says. A model in a
  folder whose name holds `!` is found. The recent-models list is safe to read during a tab switch, a
  failed Rebuild says so in a balloon instead of an IDE error, and a page's `{{…}}` bindings get the
  expression support a form's have.

## 0.25.1

- **A pull that cannot run no longer traps the IDE.** *Pull from Flowable Design* answers missing
  configuration by opening the environment editor and running again once that closes, and the check in
  front of the second attempt asked whether a workspace and some apps were selected — which a missing
  password does not change. So a connection with nothing signed in reopened the page every single time
  it was closed, with nothing to break the loop but closing the project. Two ordinary routes led
  straight into it: the action opens the editor itself when no environment exists yet and pulls the
  moment one does, and a connection created in that dialog has no secret stored yet; and *Sign out &
  retry* on a failed pull clears the secret before doing exactly the same thing. The editor is now
  offered once per pull, and the attempt that follows it reports instead of reopening anything —
  `Not signed in to <server>`, `No Flowable Design environment is selected`, `This project has no folder
  on disk to pull into`, each on the usual balloon with its *Configure…* action for a second try that
  the reader asks for.

## 0.25.0

- **A model in the project, compared against the model in the app.** Models are increasingly generated
  rather than modelled — a form written by an LLM, say — and the file lands in the project folder, not in
  the app export. The question that follows is "what does this change against the app we have?", and that
  is a diff the IDE can draw, except that one of its two sides lives inside `<App>.zip` and nothing
  offered that pairing: the route was to unzip by hand into a temp folder. *Compare Model with Archive* is
  that pairing, in the platform's own diff viewer, from either end — a file in the project is matched
  against the model entries of every `.bar`/`.zip` in scope, an entry inside an archive against the
  project's own model files. The counterpart is found by file name, by the name behind the `<kind>-` prefix
  a deployment archive gives its entries, and failing both by the model key inside the file, because a
  generator rarely names a file the way Design does. One match opens straight away; several, or none,
  offer the archive's model entries in a list that filters as you type. It is in the Project view's
  context menu — which now also comes up on a `.json`, the one file the comparison is most wanted on and
  the one it was hidden from — and in the editor's, which is how an entry opened by *Go to Model* is
  reached.
- **Both sides are laid out before they are compared.** A Design export is minified: one long line per
  model, and the same goes for every `.form` in a deployment archive. Held against a file that some
  generator or formatter wrote out over hundreds of lines, *everything* differs and the viewer has nothing
  to say — so without this the comparison would have been an answer nobody can read. Both sides are
  re-indented first, whitespace only and never a value, so a number still reads the way its own file
  spells it; the pair is then read-only, because neither side is the file on disk any more. *Show Raw
  Files* in the diff toolbar gives the two files themselves, where the project side stays editable.
  Processes, cases and decisions are exported formatted already and are always shown as they are.

## 0.24.3

- **`CLAUDE.md` says less, and none of it is wrong.** Gloaguen et al. (ETH Zürich, *Evaluating AGENTS.md*,
  2026) measured what a repository-level context file does for a coding agent: its instructions are
  followed, its repository overview adds nothing the agent would not read anyway, and the file costs about
  a fifth more per task. Held against that, a third of the generated file was overview — the scale line,
  the app list, the model inventory, the Java-by-role tally, the directory counts and the per-check health
  tallies, every one a copy of the summary that §0 has the agent read whole — and §5 restated four of its
  own six rules in a "pitfalls" paragraph. All of that is gone. What stays is what the agent cannot get
  from the repository: the procedure, the Design-vs-repo convention, the wiring examples to mirror, the
  known issues not to copy, and the catalogs. §1 lost its ASCII diagram and the `ACT_*` table paragraph on
  the same grounds.
- **The catalog is complete.** §6 cut every list at a fixed length — `flw.` showed 34 of its 59 members,
  `bpmn:` and `cmmn:` ended in "(+4 more)" — under a sentence that calls anything outside the list a
  hallucination. That sentence was declaring real functions hallucinations. The lists are whole now, and
  the section opens by saying when it is needed at all.
- **The file works from where it is read.** It is meant to be the project's `CLAUDE.md`, read from the
  repository root, but it named its siblings (`<project>.summary.md`, `.graph.json`, …) as bare file names
  relative to wherever Atlas had written them — which, with the launcher's default, is a folder next to
  the Atlas checkout. From the root not one of them resolved. The renderer now knows the output directory:
  inside the project the paths are spelled from the root (`atlas-output/<project>/<project>.summary.md`)
  and the header offers the `@`-import line for a hand-kept `CLAUDE.md`; outside it, one line says where
  the files are and how to regenerate them. `--claude` on its own, which writes nothing but this file, no
  longer sends the agent to read a summary that does not exist — its first step is to generate the
  artifacts. And the `--slice` recipe is written in the form that runs: through the launcher it never
  did, because the launcher adds `--all`, which `--slice` refuses.
- **What not to touch is stated, not hedged.** §3 said "unless this project's convention is to edit the
  model files directly (check existing commits)". Atlas knows whether the models came out of Design
  export archives or sit in the repository as loose files, so §4 now says which — "Design exports packed
  in `apps/demo.zip` — never edit the exports", or "unpacked files under `processes/` — check `git log`
  before you decide" — next to where the custom Java goes. The "Flowable version: not auto-detected" line,
  a sentence about the absence of a fact, is dropped when nothing is found; so is the generic *Run &
  verify* fallback that fitted every Flowable project and told this one nothing. The house-rules reminder
  is a real HTML comment now, which Claude Code strips before loading, instead of the same text in
  backticks that every session paid for.

## 0.24.2

- **Go to Model does something under Remote Development.** It still did nothing there after 0.24.1, and
  the reason was one layer below the one that release fixed: on a remote IDE the **Flowable Model** tab
  is not in Search Everywhere at all. The popup renders in the thin client, a tab contributed by a plugin
  running on the host does not reach it, so there was nothing for the action to select. Routing the
  action to the client — what 0.24.1 did — is worse rather than better: the plugin is not loaded there,
  so the action has no implementation to run. On a remote host *Go to Model…* now asks for a pattern and
  opens the result list, which is the same search reaching the same index; a dialog and a tool window are
  ordinary UI that Remote Development mirrors. Locally nothing changes. Putting the tab itself in the
  remote popup means republishing the contributor through the platform's newer provider API, which is
  its own piece of work.

## 0.24.1

- **The model search works under Remote Development.** *Search Models…* did nothing at all on a remote
  host: the Search Everywhere popup is a frontend component, an ordinary action runs on the backend, and
  the call reached no UI — it failed silently, which is the worst way for a button to fail. It is built
  on the platform's own `SearchEverywhereBaseAction` now, the same base every *Go to Class / File /
  Symbol* uses, whose whole purpose is to route the action to the thin client.
- **One menu, in two places.** *Tools → Flowable Atlas* and the Atlas Hub's **⋮** were two hand-kept
  lists over the same actions, and they had drifted into different contents *and* a different order — so
  the same plugin had two navigations to learn, and an entry was reliably in the one you were not
  looking at. The ⋮ now renders the Tools group itself: an action added to the descriptor appears in
  both, in the same place, or in neither. *Open Environment in Browser*, which only the Hub had, is a
  registered group now and is in both.
- **The two searches say which is which.** *Search Models…* and *Find in Models…* differed by one word,
  and nothing in the pair said that the first takes you to one place and closes while the second leaves
  a list. They are **Go to Model…** and **Find in Models…** now, after the platform's own *Go to File* /
  *Find in Files* — and they no longer share a magnifier: the list carries the Find tool window's own
  icon, which is the window it opens. The keyboard shortcut is unchanged.

## 0.24.0

- **Findings can be accepted, in a file you commit.** Some findings are correct and still not worth
  acting on, and until now the only answer was to drop the check for everyone or stop running
  `--fail-on` at all. A project can now carry `waivers.json` next to its artifacts, beside a generated
  `.gitignore` that ignores everything in the folder *except* that file — the analysis is regenerated
  and may hold client data, the decisions are yours and belong in review. A waiver names check + node,
  narrowed by element and subject, so it survives the message rewording; an accepted finding stays in
  the report, in a section of its own, and leaves the counts and the gate. A rule that matched nothing,
  expired, or gives no reason is reported on every surface, and matching is counted per run, so a second
  rule covering the same finding is not "matched nothing". The explorer and `Waivers.serialize` write
  the same bytes — `WaiverWriterParityTest` runs one set through both — and a save from the IDE merges
  into the file rather than over it: a rule a colleague committed, or the CLI added, after the page was
  generated survives. New flags: `--waivers` (the folder the artifact lands in), `--no-waivers`,
  `--fail-on-stale-waivers`, `--waiver-author` to prefill the `by` of a rule accepted from a
  CLI-generated page, and `any` as the honest spelling of what `--fail-on warning` has always meant.
- **Accept a finding where you read it.** Every finding row — on the Checks page, under *Findings on this
  model* (right under the diagram), on a diagram element's card — carries **accept…**: a labelled reason
  that is refused in words when empty, an optional *until*, *by* prefilled with the project's git
  identity in the IDE, and, for a finding that names an element or a subject, a choice between this
  finding only (the default) and every finding of the check on the model. Accepted rows stay in place
  with *edit* and *restore*; a bar under the top bar says on every view how many decisions are unsaved,
  with *Save to waivers.json* in the IDE or *Export* elsewhere and a two-step *discard*. After a save the
  IDE confirms, regenerates the page — on the block or node you were reading, not back on the dashboard
  — and the diff reconciles itself against the file. The IDE reads `waivers.json` on both generate paths,
  so a page generated from the IDE no longer shows every accepted finding as open. The *Deliberately
  accepted* table shows every decision as it stands — scope, reason, author, expiry, how many findings it
  covers — with its troubles as marks on the row and *restore* beside it; notes get a table of their own.
- **A model with findings looks like one.** A count pill on tree rows and list items, coloured by the
  worst open finding; a marker on every diagram element with a finding, whose card lists them and hands
  *accept…* to the finding's row. A review list that mirrors a check carries the check's open count — so
  accepting the three unused forms empties the list instead of leaving *Unused forms 3* in the sidebar —
  and a model whose every finding was accepted wears a small ✓ in lists and in the tree.
- **Findings explain themselves.** Every check is described once, in `CheckCatalog`: what it is, its
  severity, why a finding matters, what to do about it — accepting included — and where the docs are.
  The summary, the overview, the agent primer, the CLI's `--fail-on` vocabulary and the explorer all read
  it, where until now a check was a string id in one place, a label in a second, a second label in a
  third and a severity stated only on the documentation page. A renamed docs heading is now a red build
  rather than a dead link.
- **Every check is a defect or an advice, and every surface leads with the split.** A list that put
  "task without a boundary event" beside "expression does not parse" taught a reader to skim both. Each
  of the 23 checks now has a *kind*: a **defect** is wrong now — a file that does not parse, a key
  nothing answers, a gateway the engine cannot leave, a column two names disagree about — and an
  **advice** is a pattern worth a look while nothing is broken — a call with no error path, a form
  nothing references. The Checks page, the summary, the overview, the generated `CLAUDE.md` and the CLI
  status line say `3 defects · 41 advice` instead of `44 findings`; `graph.json` carries
  `stats.defects` and `stats.advice`; the catalog reads defects first, then advice; and
  `--fail-on defects` makes a pipeline red on what is wrong and green on what could be better.
- **The explorer shows the findings :core computed.** The Checks page rebuilt its blocks from the live
  nodes and took the counts from :core — two computations of one thing, which disagreed the moment
  anything was accepted, and left the runtime checks with rows that went nowhere. The page now receives
  the itemised findings and renders one block per check: severity in words, model, element (a jump into
  the model), message, `file:line` with the open-in-IDE button, the catalog's explanation and docs link.
  It leads with `3 defects · 41 advice` and groups its rows under those two headings — the four tier
  labels are gone — and the sidebar's *Checks* badge turns red only while a defect is open. Every count
  on the page moves with a decision taken on it, one filter covers the whole page, and every check has a
  face — an *open the list* button, and an "N checked" denominator when it is clean, where half the
  health list used to say what it had examined and half did not. Unused decision tables get a review
  list of their own beside the unused forms and operations, and a review list is named exactly like its
  check.
- **Identical findings fold into one row.** The same `22 "hours"` missing its comma, copied across 22
  forms, was 22 rows of the Checks page; 66 mail tasks with the same missing error path were 66. Three
  or more findings with one message shape — names and numbers aside — read *22 ×* on one row now, the
  members and their accept controls a chevron away, so the page has as many rows as it has causes. A
  model's own page keeps every row.
- **Findings carry a `subject`.** Several checks fire more than once on one node, and the only thing
  telling those apart was the message — generated prose that rewords when something unrelated changes.
  A finding now names which one it is: the scope, the column, the name an expression could not resolve.
- **A badge toggle on the diagram.** An element's badge takes the tone of its worst finding — red for an
  error, amber for a defect, grey for advice alone — so a process whose every task lacks a boundary event
  no longer wears an orange badge on every task; a ⚑ button in the top bar hides the badges on every
  diagram, remembered like the ≈ toggle beside it.

- **Three checks about how a process behaves, not whether it resolves.** `nonExclusiveAsync` — an async
  element that explicitly set `exclusive="false"`, so the jobs of one process instance may run
  concurrently; it fires on the opt-out only, never on the absence, because `exclusive` defaults to
  true. `asyncWithoutRetry` — async work with no `failedJobRetryTimeCycle` of its own. And
  `unguardedTasks` — a call that leaves the engine with nothing catching its failure. Design writes a
  platform bean into every service task's delegate expression, so "a service task with a class or a
  delegate" would have been 94 % noise (275 findings on one project of 81 processes): it fires on the
  task types that call out (HTTP, external worker, agent, mail), on a class or a bean of the project's
  own, on an `expression` whose root is neither an engine context nor a platform bean, and on a
  service-registry task whose service is REST. An HTTP task carrying `ignoreException` or
  `handleStatusCodes` has its error path and is quiet, and so is any async task — its failure is a failed
  job, retried and then reported, never an exception to the caller. The message names the fix for the
  kind of call it found: a synchronous mail task is told to go async or get a boundary event.
- **Six more checks — questions, not verdicts, each quiet where it cannot be sure.** `hardcodedSecrets`
  (error): a password, token or API key written as plain text into a `.service`, `.channel`, agent or
  knowledge-base model or a task's field injections, or credentials inside a URL — paths only, never
  values; quiet for expression-valued credentials (`https://${user}:${password}@host` is what the finding
  asks you to write) and for a key that merely talks *about* a secret (`tokenizerModel`, `maxTokens`,
  `passwordPolicyDescription`). `gatewayNoDefault`: an exclusive or inclusive gateway whose every
  outgoing flow is conditional and that names no default — "no outgoing sequence flow" waiting for the
  data nobody thought of; a single conditional flow throws the same error and counts. `implicitSplit`:
  two unconditional flows out of one activity, a fork nobody drew; quiet when every flow is conditional
  and when the activity carries a *default* flow beside its conditions, which BPMN allows. `unsafeQueries`:
  `${name}` in a query template with no escaping behind it; any built-in silences it. `leftoverMarkers`:
  a `TODO`, `FIXME` or `HACK` in a model file, attributed to the model whose element holds it — in a
  deployment file holding several processes, not to the first — with its line, or, where the text is
  minified away, the path of the element (`rows[2].cols[0].label`) rather than a column that moves on
  every export. `unusedDecisions`: a decision table nothing calls, app membership not counting as use and
  the decision *service* Design generates around a set of tables counting as the caller it is.
  FindingsTest pins each one's quiet path, and the three runtime checks' too.
- **Two corrections to "defined, never used", and a rule task counted once.** A data table's *delete*
  form was the one of its four form keys Atlas did not follow, so a form used only to confirm a
  deletion was "unused". A group allowed to press a button on a form counted as a use of the form, so a
  form nothing opens but a group may use was never reported. And a DMN service task, listed by the
  parser as both a service task and a rule task, produced every topology finding twice.
- **`schemaGaps` compares a service against its own table only.** When a changelog creating several tables
  matched none of a service's columns, the coverage pass fell back to *every* column of the changelog, so
  another table's columns were reported as this service's unmapped ones. And a service no data object
  binds at all no longer reports every mapped column as "used by no data object": the service is used
  directly, and the row said nothing about the column.
- **A variable with many write sites is judged on all of them.** The write list on a variable node is
  capped at 25 for the page, and the unused-variable decision read the capped list — so a variable
  whose 26th write was the one that silences the check (a mapping into a model outside the project, a
  scope that reads everything) was reported as never read. The decision runs on the full list now; the
  cap is applied afterwards, for display.
- **A CMMN lifecycle listener's script problem is a finding.** The parser has always flagged a
  `planItemLifecycleListener` that carries a script — the engine's listener factory has no script branch,
  so it is a silent no-op — but the note sat on the plan item, and the check read only a process's task
  buckets. It reaches the case's page now, on the plan item that holds the listener.

- **Model XML gets its schemas.** A `.bpmn`, `.cmmn` or `.dmn` opened with an unresolved namespace and
  no completion at all: the IDE ships none of these schemas, and nothing supplied them. The plugin now
  bundles Flowable's own copies and registers them, so `<` inside a process offers BPMN elements,
  `flowable:` offers the Flowable attributes, and a missing required attribute is said in the editor
  instead of at deployment. Nothing is fetched; it works offline. `http://flowable.org/design`,
  `.../cmmn` and `.../modeler` have no schema anywhere and are registered as *ignored* rather than left
  unknown — the warning goes, without pretending they can be checked, and because all three formats
  allow foreign attributes everywhere, an undescribed namespace is skipped rather than rejected. One
  schema needed widening before this was safe: the open-source `flowable:type` is an enumeration of
  eight values, while a real project also writes `service-registry`, `agent`, `init-variables`, `audit`
  and `data-object` — validating against it as published reported 140 of 989 real process files as
  errors, where the widened copy reports 8. A listener's `<script>`, which the engine both reads and
  writes, was missing from the schema entirely and is declared now. Measured across 1 784 real models,
  99.1 % validate clean and every remaining finding is a genuine defect — an id that is not a valid XML
  name, a diagram edge with no waypoints. Five such defects were in this repository's own demo models,
  and are fixed.
- **A search result list that stays open.** The *Flowable Model* tab finds a string in every model —
  keys, element ids, and the full text, archive entries included — and then closes on the first result
  you open, so a string that sits in thirty places could only be walked one query at a time. **⇧⏎** on
  any row, or the new **Find in Models…**, puts the whole result set into the Find tool window instead:
  every occurrence its own row, grouped by file, `.bar`/`.zip` entries among them, and the window names
  the scope it searched. *Find in Models…* is prefilled from the editor — the selection, else the model
  key under the caret. The platform's own *Open in Find Tool Window* button would have been the obvious
  route and is a trap: its flag only lights the button, while the action behind it can only render three
  item shapes, none of which this tab produces — it would have opened an empty window. The list is built
  on the usage-view API that Find Usages on a model key already uses, so an offset is found again in the
  file's own text rather than carried over from the scanner, which decodes UTF-8 where the editor uses
  the file's charset.
- **The Hub's three exits are on its toolbar.** *Open Atlas Explorer*, *Open Expression Playground* and
  *Search Models…* sat two clicks down in the ⋮ menu, which is where a reader who has not memorised the
  menu stops looking. They are buttons now, past a separator from Refresh and Settings — what acts on
  the panel first, then where the plugin takes you — and the ⋮ keeps what is left: the environments,
  *Generate Model Constants…*, *Rebuild Model Index*, *Manage Environments…*. They stay reachable where
  they already were, in the Explorer and Playground blocks and on the header's model count. Each of the
  three is `DumbAware`, which had to be checked rather than assumed: a toolbar button is visible the
  whole time, where a menu entry is only visible while the menu is open.
- **The platform-bean set is read off the platform's own configuration.** `${flw.setOutput(…)}` in a
  task listener is the platform's scripting API root, and `planItemInstances` the CMMN query root; both
  were listed as beans of the project's own — 36 times on one real project — and each was a call out of
  the engine with no error path. The set is declared once now (it lived in three renderers) and carries
  every task delegate the platform and Engage auto-configurations declare (`mergeDocumentService`,
  `housekeepingServiceTask`, `generateSequenceServiceTask`, `processSendMessageTask` and the other
  conversation tasks…), the platform services an expression calls (`commentService`, `queryService`,
  `platformFormService`, `coreContentService`…), the `flw*Utils` expression helpers,
  `propertyConfigurationService`, Spring Boot's `jacksonObjectMapper` and the actuator's
  `environmentEndpoint`. None of them appear under *Review — unresolved in project* any more, and the
  KDoc names the source files so the list can be regenerated instead of grown by complaint.
- **A method call on a variable is a read of the variable, not a bean.** `${issue.asText()}`,
  `${attachments.size()}`, `${requesterData.getName()}` read like bean calls to the harvest, and every
  such root became a bean: on four real projects 25 variables vanished from the variable graph, stood
  under *Review — unresolved in project* as beans nobody could find, and 15 service tasks that read a
  variable were "calls out of the engine with no error path". A root is a bean only when something says
  so — the platform declares it, Java declares it, a delegate expression names it bare, or it is named
  the way Spring beans are named (`orderService`, `pdfGeneratorTask`); everything else is the variable
  it always was, with the call recorded as its read. A service task's `expression` and its
  `delegateExpression` now carry different relations (`serviceTask-expression`, `serviceTask-delegate`),
  and `${true}` on a service task is a literal, not a bean called `true`.
- **A key names a model only together with its type.** A case and its start form both called
  `DRA-C001`, a data object and the service Design generated for it: two models of different types
  sharing one key is common — 23 times across three real projects — and everything Atlas harvested from
  a file (its `${…}` and `{{…}}`, its variables, the operations it calls, the scripts that touch a name)
  was credited to whichever model of that key it had registered first. Measured: 34 bindings and 69
  expressions of a start form on the case's page, none on the form's. Every such record now carries the
  model's type, so the form's bindings are the form's. The shared key is still recorded in
  `diagnostics`, but it stopped being a *parse issue*: nothing failed to parse, and the summary no longer
  says "17 files could not be fully analyzed" about 16 shared keys. Only a Java string literal that names
  the bare key stays ambiguous, and that edge was already marked suspect.
- **A Design-workspace export is read in full.** The legacy editor stores a form or page body as a
  tree of `childShapes` with hyphenated properties (`form-ref`, `rest-button-url`, `actiondefinitionkey`),
  and Atlas registered such a model by key with no fields: 47 forms across the measured projects were
  empty shells — blank pages in the explorer, at least 25 false "unused form" findings because the
  subform that embedded them was never read, and ~1 000 references that never reached the graph. The old
  shape is rewritten into the one the current Design writes — stencil to type as real exports pair them,
  property to setting by spelling — and parsed by the same reader, so fields, subforms, data-object and
  service references, action buttons, REST calls and payload mappings come out as from a current export.
- **A data object typed by a dictionary type has the type's properties.** A service-registry data
  object names its type — `referencedDataDictionaryModelKey` plus `dataDictionaryTypeName` — and its
  own `fieldMappings` say only what needs saying about a field: the lookup id, a label. Atlas read the
  mappings alone, so a twelve-property object had one field, its page listed one property, and eleven
  columns the service maps were "used by no data object" — 15 of the 16 `schemaGaps` findings on two
  real projects. The type's properties are the object's fields now, labels and lookup flags merged
  from the mappings, and `dictionaryType` sits on the data object in `graph.json`.
- **A data table names its operations, and a data object uses its service's.** A form select carries
  `searchOperationKey` (its options) and `lookupOperationKey` (the stored id back to a row), a
  data-object table its `dataObjectDataTable{Create,Edit,Delete}OperationKey`; Atlas recorded only the
  table's main operation and the four form keys, so 75 such keys across the real projects were "called by
  no model or code". And the engine calls a bound service's `lookup`, `create`, `update` and `delete` for
  every data-object instance, which nothing in a model names: `unusedOps` listed every generated CRUD
  operation — 74 of 74 on one real project, 41 of 64 on another. Both are followed now (the operation's
  `type` travels with it in `graph.json`); a `search` operation is still only used when something names
  it, which now something can.
- **A reference written as `{id, key}` is a reference to `key`.** Newer Design writes a document
  model's forms as `"edit": {"id": "FORM_MODEL-…", "key": "X"}` where older exports wrote `"X"`; Atlas
  turned the map into the text `{id=FORM_MODEL-…, key=X}` and reported a *missing model* of that name
  — two error findings on one real project, and the real form lost two inbound edges. Every reference
  now passes through one door that unwraps the shape, so no parser can make that mistake again.
- **A REST button's response lands where the button stores it.** A button bound to `{{$temp.info}}` with
  *Store response attributes* `deploymentId` writes `$temp.info.deploymentId` — the platform's button
  calls back onto its own binding — and the same form reads it as `{{$temp.info.deploymentId}}`. Atlas
  recorded a variable `deploymentId` written by the mapping and read by nothing: eleven "written but
  never read" on two real projects. A mapping stored under a binding is a property of that value now —
  form-local under `$temp`, a field of the bound variable otherwise — and the payload table shows the
  full path it lands on.
- **A property table is not a component.** The legacy Design editor keeps a `pathProperties` map on
  every form body — `{"id": "id", "url": "extraSettings.url", …}`, property name to JSON path. Walked as
  content, that map has an `id` and a `url`, so every such form called `GET extraSettings.url`: 59 REST
  calls to a path that is not a URL, each with its own external node, across five real projects. The
  editor's bookkeeping maps are skipped now, and a REST call needs a component with a type behind it.
- **References Atlas did not read yet.** A select over a master-data table (`tableKey`), a page's work
  list (`scopeDefinitionKey` — six processes on one page that looked less used than they are), Design's
  own namespace on a process or case element — `design:securitypolicy`, `design:processdefinitionkey`,
  `design:casedefinitionkey`, `design:inboundchannelreference`, 34 references on the real projects — and
  `<flowable:eventCorrelationParameter>`, the other half of what a send- or receive-event task
  correlates on, are references and payload now. A data import's column mappings
  (`design:variablemapping`), its report variable and a task's `additionalvariables` are variable
  writes. And an in/out parameter tag is matched by its lower-cased name, the way form keys already were,
  so an export that lower-cases `<flowable:inputparameter>` loses nothing.
- **Master data is master data.** A `.data` whose `dataObjectType` is `masterData` — a managed
  reference list — was a data object like any other: one real project read "143 data objects" for 143
  lists, and each list's `variables` map (`lang`, `color`) became project variables, 35 of them. It is its
  own kind now, `masterData`, counted apart ("143 master data"), and a select over such a table
  (`tableKey`) links to it.
- **Templates have their bodies.** A deployment archive keeps a template's text outside the `.tpl` —
  in `template-<key>.tplvariation` — and an attached document's name in `.tplfile-metadata`; Atlas
  dropped both without a word, so 23 of 23 template nodes on the real projects had no body: nothing to
  search, and the `${root.travelerFirstName}` a mail template reads never reached the variable graph. The
  parts are read wherever the `.tpl` is, loose or archived; the variations, their parameters and the
  attachments sit on the template, its `${…}` are the template's expressions, and `${root.x}` is a read
  of `x`. A legacy wrapper in a folder Design does not use (`decision-service-models`, on one real export)
  is said instead of vanishing, and the folders Design added since — SLA, knowledge base, master data,
  dashboard component, palette — are typed.
- **Liquibase changelogs inside an archive are read.** A Design export packs
  `liquibase-<key>.data.changelog.xml` next to the models, and Atlas only ever read changelogs on disk —
  so every app that listed its own changelog reported a *missing model* (six on one project), and the
  services those changelogs describe had no schema coverage. Archive entries are read like loose files
  now, and a changelog's key is in the index before references resolve — and the same changelog loose and
  archived is one changelog, not two that supersede each other.
- **A constant at a key position names the model.** `startProcessInstanceByKey(ModelConstants.MAIN_CASE)`,
  `.caseDefinitionKey(MAIN_CASE)`, `.decisionKey(Keys.GROUP_MAPPING)`: Atlas followed only a *literal* in
  those positions, so a project whose Java layer is written against a generated constants class — one
  real one, wholesale — had no code → model edge at all. A constant is resolved to its
  `static final String` value once every source is read and recorded as the literal would be; a name two
  classes define differently is left alone rather than guessed.
- **An action's `channels` are UI placements, not channel models.** `menu`, `quick-menu`, `slash-menu` and
  the mobile menus say where an action is offered; Atlas looked each one up as a channel model and
  reported two *missing models* per action. They are a fact on the action now, and nothing else. In the
  same pass, the platform's own event models — `_flowableMailEvent` and the `_flowableEngage…Received…`
  events, which ship with the palette — resolve to a platform-provided external node instead of a
  *missing model* error.
- **A property before a ternary's colon is not a function namespace.** `… ? findUser(x).displayName :
  findUser(y).displayName` reads, around the colon, exactly like `date:now()` — `name : name (` — and the
  catalog walk reported "Unknown function namespace 'displayName'" on every conditional shaped that way.
  A name reached through a dot is a property, and a colon that closes a `?` is the ternary's; neither is
  looked up in the catalog now. (A *bare* name before the colon is different: JUEL's own parser reads
  `flag ? name : fmt(y)` as the function `name:fmt`, so that stays the one syntax error it always was.)
- **A script's `${…}` is not an expression.** The text harvest read every `${…}` in a model file, script
  bodies included, so a Groovy `"${user?.firstName ?: ''}"` in an action's script came back as an invalid
  backend expression (`?.` is not JUEL), `flw` and `flwTimeUtils` were listed as beans the action calls,
  and the script's locals became variables. Script bodies — `<script>`, an action's `scriptInfo.script`,
  a script operation's `config.script`, a form script button's `script` — have their `${…}` blanked out
  of the harvest now; the script checker still reads every one of them, and a `{{…}}` in a form script
  stays the binding it is.
- **A Spring placeholder is configuration, not an expression.** A channel's
  `${email.inbound.channel.imap-url:imap://localhost:3143/inbox}` was validated as JUEL (an error at the
  colon) and harvested as four variables — `email`, `imap`, `localhost`, `inbox`. A `${dotted.key:default}`
  is now marked `placeholder` in the graph, gets no verdict, and yields no variables; `${order.total}`
  is still the property path it always was. A Java `@Value("${mail.from}")` no longer makes `mail` a
  project variable either.
- **A byte-order mark is not a parse failure.** A JSON model beginning with a UTF-8 BOM — an export
  touched by a Windows editor — failed with "Expecting value at char 0", an error-level parse issue, and
  vanished from the report with every reference into and out of it. It is read like any other file now.
- **The status line counts models, not files, and agrees with the report.** `3 models · 4 java` was the
  first thing Atlas said about a repository of 27 Design exports holding 610 models — `stats.models`
  counted loose files. The CLI, the summary, the overview and the agent primer now say
  `610 models (3 files · 27 archives)`, and `graph.json` carries the count as `stats.modelCount`. The
  line's finding counts come from the same numbers the report does, rather than from second counters
  (`stats.scriptIssues`, the raw diagnostics) that know nothing about what was accepted.
- **Hotspots are the project's own artifacts.** The most-referenced list on the overview and in the
  summary was led by `initVariablesService`, two IDM URLs, `flwTimeUtils` and a security policy — things
  every model references and nobody navigates to. Externals and security policies are left out now.
- **Small corrections.** A file that is not a Flowable model at all — a Helm chart's `.tpl`, a palette
  or manifest JSON — is recorded in `diagnostics` and printed by `-v`, no longer a warning finding about
  somebody else's file. A variable written by three tasks of the same name reads "3 init-variables
  mappings on `Initialize variables`", not the same phrase three times. A custom-function diagnostic
  carries a file, so it can be accepted like any other finding. And a form button's `invokeServiceUrl` /
  `invokeActionUrl` are REST calls to the IDE's scanner, as they were to the report.

- **A reference tree.** The explorer could not answer "what does this app actually start, and what does
  that reach?" — relationships were single-hop everywhere, so following a chain meant clicking through
  it and losing your place at every step. `#/tree` walks the graph instead: the app as a grouping
  header (plus *Outside any app*, which is a finding in itself), its functional roots — a model nothing
  points at except its app — and everything those reach. App membership is deliberately not the spine:
  `contains` is app → model and one level deep, so nesting by it buries the edge that explains why a
  form exists. A node is expanded once, at the shallowest place it is reached; later arrivals are
  leaves marked *shown above* that jump to it, which keeps the tree bounded where rebuilding a shared
  subtree per path is exponential. Cycles terminate and say so. The filter takes the palette's search
  grammar, and a row survives it if it matches **or** a descendant does.
- **A report page can be reloaded, and sent.** The browse list round-tripped its filter and sort through
  the URL; the report pages did not, so a reload of `#/checks` dropped the severity chip and the filter,
  and "the four crossed mappings I found" could only be shared as a screenshot. `#/checks`, `#/tree`,
  `#/scripts`, `#/variables` and `#/schema` now carry their filter text, their chip, *show accepted* and
  the tree's lens in the URL, written as you type or pick and read back on arrival.
- **A report page scrolls inside the page.** The Checks, Tree, Variables, Scripts and Schema pages had no
  scroll container of their own, so a page taller than the window scrolled the whole document — and took
  the sidebar, the breadcrumb, ⌘K and the *Save to waivers.json* bar along, while a model's page kept
  its menu in place. Every view scrolls inside the shell now.
- **Back and forward, on every page.** Inside the IDE the explorer has no browser chrome, and the only
  way back was a small button in a model page's header that hides its label in a narrow tab — a report
  page had none at all, so a jump from the Checks table to a model was a dead end. The top bar carries
  ‹ › on every view, `Alt+←` / `Alt+→` drive them (the brackets stay the tab keys), the explorer tab's
  toolbar in the IDE has Back and Forward, and the page's place in the history survives a reload.
- **Long text never clips a control.** A table cell ellipsises text but slices an inline box, so a long
  key cut its model chip mid-chip in the checks table, a type tag mid-word in a fields table, and a
  chip's type wrapped onto a second line; a 2 900-character binding label overprinted the project crumb
  and filled a page as a 26px headline. A chip now shrinks by its name only and never past its cell, a
  tag ellipsises, the current crumb has a width, and an expression's or binding's label is shown as code
  — three lines, with *show all* for the rest.
- **One unit per number, and a stale link says so.** `unreadInputs` fires once per callee, so a
  variable mapped into seven processes was seven findings on one row: the sidebar said 72, the page 66,
  the health row 16 and the list it opened 10. A review list's badge now counts the rows it has, a
  health row whose findings outnumber their models says "on 10 variables", the Unused-variables page
  says "16 findings on 10 of 95 variables", and a filtered list heads "12 of 332" like every other filter.
  And a link to a model this report does not contain — renamed, or a report from a smaller scope —
  landed on the overview without a word and kept the dead hash; it says so now and cleans the address bar.
- **Hiding uncertain links hides them everywhere.** The ≈ toggle repainted the detail panel and nothing
  else: the reference tree kept its shape and the overview kept counting suspect edges in its hotspots
  and reference counts until the next navigation. Every view follows the toggle now.
- **A list that matches nothing says so.** Typing a filter no row matched left a blank column; it now
  says *No match in Forms* and offers to search everything. And a deep link into a CMMN diagram
  (`&e=`) lights up the plan item like the ⌖ button does — the link never passed the element's name,
  and CMMN's diagram references plan items by a different id than the parsed tree.
- **A diagram stays legible, and the search says what matched.** *Fit* fitted the width alone, so a
  process 6 700px wide landed at 12 % in an ordinary panel — a grey strip of boxes; inline it now stops
  at 40 %, wider than the panel, with a line saying to drag or open full screen. And the search palette's
  "why it matched" hint for a plain word named the field's *id* (`label · date1`, twelve times in a row)
  where the facet path already named the caption; both paths lead with the matched text now.
- **The search grammar stays a click away, tabs show from the first, and two keys for the two things
  you do next.** The `label:` / `key:` / `type:` / `in:` chips appeared only while the query matched
  nothing, so a reader who always got some result never saw them — a *narrow ▾* chip keeps the row
  reachable under any result set. The tab strip appeared only from the second tab, which is how the
  tabs and their Alt shortcuts stayed undiscovered; it shows from the first. And on a selected node,
  `c` copies its key and, inside the IDE, `o` opens its file.
- **A smaller page.** Every explorer shipped the run's `diagnostics` and the custom-function catalog in
  its data island, and nothing on the page read either (the checks come as findings, the functions as
  nodes) — on a project with parse problems that was kilobytes of dead weight per page.
- **Small explorer corrections.** The Checks filter's "N of M" counted the review-notes rows; a *not
  mapped* cell in a coverage table looked like an ordinary value and a tree's *+N more parents* badge
  like plain text; *close others* was offered with one tab open; the Unused-variables page opened on a
  35-row table of what is not wrong and said "nothing flagged" underneath; a column empty in every row
  kept its header; "All 7 checks clean" did not say that 16 more had nothing to judge; a note meant for
  Atlas's developers was shown as help; and the heading focus after a navigation was drawn as a text
  field.

- **Ctrl+click lands on the key.** A model key resolved to its *file*: line 1 of a minified Design JSON,
  or the top of a deployment XML holding three processes, with nothing saying where the key is. Every
  key reference — a Java literal, a constant, a cross-reference attribute in model XML, an operation or
  value field — and the Search Everywhere row now land on the key's declaration: the `id` of the
  process, case or decision, the `"key"` of a JSON model.
- **A key in a JSON model is a link.** Ctrl+click worked on a model key in Java and on the attributes
  of model XML — not on the JSON models that carry most of a project's references: a data object's
  backing service, a form component's subform, data object, service or action, a document's forms, an
  app's models. Every one of those is a reference now, at the sites the CLI's parsers record (one shared
  catalog in `:core`, so the graph and the editor cannot disagree), and Find Usages on a model's key
  lists them. In model XML the key in an extension element's text — `eventType`, `channelKey`,
  `sla-definition-key` — is a link too, CDATA-wrapped as Design writes it; the broken-key inspection
  reads that text the same way (a CDATA-wrapped key was flagged as unknown, markers and all).
- **Model files get the hover, the inlay and the gutter.** The documentation card, the inline name and
  the diagram mark were Java-only: a `calledElement="DEMO-P002"` in a BPMN said nothing about which
  process that is, and the diagram of the process you were reading was a Java literal away. Inside a
  model file — XML or JSON — a cross-reference and the file's own key now carry the same card on hover
  (type, name, backing table, file), the referenced model's name as an inline hint (*Model names* under
  Inlay Hints → Values, off in one click), and the diagram mark on the file's own key and on every
  process, case or decision reference.
- **Find Usages on a model's own key.** Selected in its file — the `id` of a process, case or decision,
  the `"key"` of a JSON model — a key answered "no usages". It lists every model that references it (a
  call activity, a form key, a service mapping, an extension element's text) and every Java call site
  that names it at a Flowable API position.
- **Search Everywhere finds elements, not only models.** The index has carried every model's user task
  ids, activity ids, variables, messages, signals, payload fields, form fields and outcomes since the
  completion work — and Search Everywhere and Go to Symbol listed model keys alone, so the `approveTask`
  from a log line or a test led nowhere. Every one of those is a symbol now, labelled with its kind and
  its model (*User task · in DEMO-P001*), ranked under the models and over the text hits in the
  *Flowable Model* tab, and opening the model at the element's declaration.
- **`${bean}` goes to the bean.** Completion after `orderService.` has offered the class's methods since
  the expression language arrived, but the name itself was inert: no Ctrl+click, and Ctrl+Q showed
  catalog documentation or nothing. The root of a backend expression that names a Spring bean is a
  reference to the bean's class now — resolved the way completion resolves it, in the project only —
  inside an injected model expression and in the playground alike; a root that is a variable resolves
  to nothing and is not painted, that stays the grounding inspection's call.
- **The gutter opens at the usage.** The scan behind the reference, bot and endpoint gutter marks
  computed where in each model the Java symbol, bot or URL is used — and then opened the file at line
  1, while Find Usages on the same data landed on the offset. Each model now opens at its first usage:
  the `${bean…}` in a deployment XML holding three processes, the action's `botKey`, the calling URL.
- **Open in Atlas Explorer, from Java and from a model file.** Alt+Enter on a model key — a literal or a
  constant at a Flowable API site, or any literal equal to a known key when that recognition is on —
  opens the newest generated explorer inside the IDE on that model's page: who references it, what it
  uses, its findings, its diagram. The reverse of the page's own "open in IDE" button; it offers to
  generate an explorer when there is none. Inside a BPMN or a form it is offered on a cross-reference
  and on the file's own key, so the page of the process you are reading is one Alt+Enter away.
- **Copy a key, find your way back, click the count.** *Copy Model Key* — in the editor's context menu
  on a key in Java or in a model file — puts the bare key on the clipboard, the way the explorer page's
  copy button has since the IDE bridge. The Hub gains a *Recent Models* block: the models opened last,
  newest first, fed by the editor (every route to a model ends in a tab), double-click to the key, the
  context menu to copy it or open its explorer page. And the header's *142 models* is no longer dead
  text: it links into the index — Search Everywhere's *Flowable Model* tab — with *Rebuild Model Index*
  beside it instead of two levels down in the ⋮ menu.
- **What changed since the last generation.** The stale check compared the newest model time with the
  newest page time and said *stale* — a verdict with no subject. The index keeps every scanned file's
  time now, so the banner above an explorer tab says *3 models changed since this page was generated:
  DEMO-F002, DEMO-P001, DEMO-P007* (the first five, then a count) and the Hub's attention line names the
  first three; a packed model is named by its key, an unreadable file by its name.
- **Inspections leave test sources alone, and say which scope they judged against.** A test that starts
  `no-such-process` to assert the failure was flagged like production code; `src/test/**` is skipped now
  (test *models* are still judged, as the CLI always did), and the two value-matching inlays follow the
  same rule. In a monorepo the message reads *not a known Process key in apps/orders* — a key from
  another module is unknown here, not nonexistent.
- **The Liquibase inspection has a way out.** *Column 'BOGUS_' is not mapped in Flowable service model
  'DEMO-S010'* was a verdict with no door: no quick fix, and in a monorepo no word on which sub-project's
  index had judged. The warning offers *Open the Flowable service model 'DEMO-S010'* — the model whose
  mappings decide, at its key — and names the sub-project like the key inspections do.
- **The model index's lifecycle is honest, and nothing builds it under a read lock.** A cancelled build
  came back as an error (the cancellation wrapped in an `ExecutionException`), and Cancel in Find Usages
  did not stop the scan; a build that failed left the Hub at *scanning…* forever, retrying a doomed scan
  on every refresh with nothing in the log — it says *index failed*, names the reason and offers Rebuild
  now; a lookup memo written after a Design pull's invalidation could carry the pre-pull answer until the
  next change; a model renamed away from its extension (`x.bpmn` → `x.bpmn.bak`) stayed indexed; and a
  folder added to a monorepo appeared in the project picker after a restart only. Ctrl+click on a model
  key used to build a cold index inline, freezing the IDE for the length of a model scan, and the
  constants auto-refresher did the same after every pull: hovering a key, the value-field inspection and
  the Liquibase coverage inspection all read the cached index or wait for the next pass, like every other
  reference already did.
- **No file-system refresh while holding the read lock, and a cancellation is not a failure.** Find Usages
  and the index build opened archives with a synchronous jar refresh under the read lock — the deadlock
  the scanner's own documentation warns about. And a cancelled scan was reported as an unreadable archive
  (the Hub's *archives could not be read* line), an empty script picker, or a Design pull bounced to
  Settings as "not configured".
- **The model-constants class follows the index.** Its refresher listened to the file system and ran
  1.5 s after a model changed — on the very change that had just dropped the index, so it found none,
  gave up, and nothing brought it round again unless the rebuild finished inside the window: kept in
  sync on small repositories only. It listens to the index now and regenerates when a rebuild lands.
- **Nothing slow on the EDT.** *Regenerate Atlas Explorer* — the menu item, the Hub's line, the tab's
  banner — walked the project six levels deep on the UI thread looking for pages; the first click on a
  diagram gutter icon rendered the SVG there; and every rendered diagram was kept for the session. Both
  run in the background now, the cache holds the 32 most recent drawings, and *Regenerate* with no page
  on disk says so and offers the generator instead of writing the whole artifact set.
- **JCEF is optional for real.** The plugin declares the bundled *Web Browser (JCEF)* plugin optional,
  yet named its classes in the editor provider the IDE asks on every file open — with that plugin
  disabled, the class failed to link (`NoClassDefFoundError`) instead of bowing out. The explorer editor
  is registered from the optional descriptor now, every other check goes through one probe that catches
  the missing link, and the explorer tab no longer touches its browser client after it was disposed.
- **Guards.** The output, pull and Liquibase folders in Settings must be relative to the project and stay
  inside it — an absolute path or a `..` made a pull write outside the repository. An `*.explorer.html`
  inside an archive opens as a plain file rather than in a viewer whose Regenerate and Open-in-browser
  throw. And `waivers.json` is written as UTF-8 (it was written in the project's encoding and read as
  UTF-8), with an open, edited file saved first instead of fighting the write.
- **Docs.** The checks table said `invalidExpr` is error-only (the golden carries a warning), named an
  *Atlas Hub Checks tab* that never existed, and left `waived` out of the `checks` key; the README still
  counted fourteen checks.

## 0.23.1

- **A column mapping that pairs the wrong two names is a finding.** A `.service` model says which
  physical column each logical field maps, and nothing checked the *pairing*: two fields entered with
  each other's column — `userName` → `FIRST_NAME_`, `firstName` → `USER_NAME_` — left every name
  spelled correctly and every column mapped, so the schema report called the chain complete while at
  runtime each field read and wrote the other one's column. The new `crossedColumns` check reads the
  names as the evidence they are: a closed swap or rotation is an **error** (the field names and the
  column names are the same set, paired wrongly, which no naming convention explains), and a
  one-directional cross — the column this field's name points at exists in the same table and the
  field maps something else — is a **warning**, because that one is occasionally a deliberate mapping
  onto a legacy column. A field mapped to a column of a genuinely different name stays silent:
  `approverApproval` ↔ `APPR_APPROVAL_` is not evidence of anything.
- **The data-object column of the schema table names the field the service actually maps.** It matched
  data-object fields against the *column* name as well as the mapping's field name, so a crossed
  mapping's row claimed two fields — `FIRST_NAME_` listed both `userName`, which maps it, and
  `firstName`, which does not — in exactly the row that has to be exact. A `.data` field binds to the
  mapping's name, so that is what the row now uses; without a mapping there is no field name and the
  column name is still all there is to match on. Audited against a real project's 120 mapped columns,
  the column name never contributed a field the mapping name did not already give.
- **The crossed row no longer looks like the cleanest one.** A crossed mapping is not a coverage gap,
  so the service page's *Schema coverage* table, its *Column mappings* table and the `#/schema` report
  showed it as fully mapped through. All three now mark it `⇄ crossed`, with the reason on hover, count
  it in the service's badges, and keep it in the schema report's gaps-only view instead of collapsing
  the service into *Fully mapped*. The *Checks* page spells out both halves of every crossed pairing.

## 0.23.0

- **Every icon means one thing.** The plugin drew the Hub's glyph for the Hub, for "a model" in Search
  Everywhere and Go to Symbol, and for three gutter markers that meant three different relationships. Each
  has an icon of its own now: a model key carries the icon and colour of its type — the same glyph its
  explorer page shows — in Search Everywhere, Go to Symbol and completion; a Java symbol referenced from
  models, a bot class, a REST handler and a key with a diagram wear four distinct marks at gutter size; the
  explorer, the Hub and the playground have their own tool-window and menu icons, light and dark.
- **A model file looks like what it is.** `.bpmn`, `.form`, `.app` and every other model extension carried
  the stock XML or JSON icon, so a folder of models read as a folder of config. Each shows its type's icon
  now — the explorer's glyph in the explorer's colour — in the Project view, the editor tabs and every
  file list, and a `.bar` shows an archive. The icon is decided from the name alone, so nothing waits on it.
- **Regenerate has one name.** Regenerating the explorer was offered in four places under two labels. It is
  a registered action now — *Generate → Regenerate Atlas Explorer*, reachable from Find Action — and the Hub,
  the explorer tab's banner and toolbar and the balloon after a Design pull all say exactly that.
- **The Atlas Hub is a status header over three task blocks.** It was five form-like sections whose height
  swung with their state, links in two casings, the same action under two names and two Refresh buttons
  sharing one icon. It opens now with which Flowable project Atlas is about, how many models it knows and how
  long ago it looked, and — only when something needs a hand — one attention line naming the one thing to
  do: a removed environment, an unchosen project, archives that could not be read, a stale explorer. Below
  it, three blocks that keep their shape whatever their state: *Explorer* (the generated pages, name and
  age, Generate and Open), *Design Pull* (environment, workspace, apps, *Pull from DEV1* — always the same
  four rows), *Playground* (its runtime environment and Open). Every button takes its text from the action it
  runs, so the menu and the Hub cannot disagree; the maintenance actions moved to a ⋮ menu; Refresh is one
  button that also re-reads the Flowable Design lists.
- **A gutter mark says which relationship it is, and its popup says which model.** Three markers shared one
  glyph and their popups listed raw file paths, which in a project that keeps its models in archives were
  identical up to the last segment. A referenced Java symbol, a bot class, a REST handler and a key with a
  diagram wear four marks now; the tooltip says how many actions use the bot, which verbs and paths the models
  call, and which model's diagram opens; and when several models are behind a mark, the chooser shows each with
  its type's icon, its key and its file — type to filter by key or name.
- **Every inspection explains itself, and every finding of a kind has the same fix.** Four of the six Flowable
  inspections showed an empty description panel in Settings; all six have one now. An unknown key in a model's
  extension-element text and an unknown key behind a constant were flagged like their attribute and literal
  siblings but offered no fix — the first gets *Replace with '…'*, the second *Change constant value to '…'*,
  which edits the constants class and says so in its preview. Quick fixes have stable family names, so *Fix
  all* and the Alt+Enter list group them by what they do rather than by the value they insert, and the
  Liquibase finding names the service model whose columns it compared against.
- **Completion items carry icons all the way down.** Expression and script completion were fully iconed while
  model keys, operations, value fields, vocabularies and Liquibase columns had none — two aesthetics in one
  popup, where the eye could not tell a process key from a variable. A key wears its type's icon; an operation
  the method icon, its input the parameter icon; messages, signals, variables and outcomes the platform's
  constant, variable and property icons; columns and tables the database icons; and a scraped bean in an
  expression the same bean icon the script playground uses.
- **Search Everywhere highlights what you typed, and the hover is a card.** In the *Flowable Model* tab a
  mid-key hit — `0061` finding `DEMO-DO-0061` — matched but rendered unhighlighted, so the row you typed for
  looked like the one that merely happened to be there; it is highlighted now, and the row's tooltip carries
  the type, the name and the archive path the columns leave out. Ctrl-Q on a key shows the platform's
  documentation card — key and type, name, backing table, project-relative file — instead of a hand-built
  stack of line breaks with an absolute path.
- **Inline hints have one switch — the IDE's.** The two Atlas hints could be turned off on the platform's Inlay
  Hints page and, separately, on the Atlas settings page; turning one off left the other on. Settings → Editor →
  Inlay Hints → Values is the only switch now.
- **The settings tree says what it has.** The root page names its children correctly (a *Connections* page it
  pointed at had been *Environments* for a while) and puts what the index reads first. The one-row *Flowable
  Design* page is a group on *Generation* — where Atlas writes into the project is one question — and the three
  generator pages drop the heading that repeated their title. A Liquibase or DTO generation's balloon opens the
  settings page it was about, not the parent.
- **The two Generate dialogs share one shape.** *Generate Liquibase Changelogs* and *Generate Data-Object DTOs*
  were near-twins that had grown apart in small ways — a hand-laid header, radio labels disagreeing on plurals,
  unscaled table sizes. Both stand on one base now: source radios with *Select All* and *Clear* on the right,
  the preview table with the include box first and *new* / *overwrite* last, the generator's own fields
  below, *Generate (N)* counting what it is about to write, and one validation order. Every dialog title is
  Title Case (*Sign In to Flowable App*, *Paste Session from Browser*).
- **The playground is one shell: the code, what it runs against, what came out.** The Expressions tab used
  to flip its splitter's orientation on every resize and swap two whole cards on the dialect toggle — the
  panel rearranged itself under the cursor. It is a fixed two-pane layout now, remembered, with *Stack
  Panels* overriding what the dock suggested; the dialect changes only the editor's language and the
  context's controls. The context has one summary line that is always there — *QA (project) · Case instance
  CAS-4711*, *payload, 14 lines, at orders[1].items[0]* — and folds its controls away once they are set. One
  result pane serves both dialects and says *Evaluating against QA…* while it waits. *Evaluate Against App*
  has Ctrl+Enter. Findings are painted with the editor colour scheme's error and warning attributes in the
  editor's own font — the field used to render in the Swing label font, which is why the squiggles were
  hand-painted in four hex colours. The Scripts tab stands on the same shell: what the context provides
  beside what the script touches, in theme-derived chip tints.
- **The explorer opens the playground instead of embedding a second one.** Every `*.explorer.html` used to
  carry a *Flowable Expressions* editor tab holding a complete second playground — its own alarms, its own
  listeners, last writer wins on the saved state. The explorer tab's toolbar opens the tool window instead.
  Alt-Enter on an expression in a BPMN or CMMN model now also presets the instance kind — process or case.

## 0.22.0

- **A detail page has a header.** A node's page used to open with a bare title over a grid of uppercase
  stat cells — *Fields 7 · Data sources 3* — that repeated the counts of the sections below it. It opens
  now with the type's icon in a tinted tile, the title, one identity line (kind · key · path, each
  copyable, the path opening the file inside IntelliJ), Design's description as prose, and the facts as a
  definition list — the handful of properties that describe the model itself, never a count. The sticky
  bar keeps the kind and the actions in reach.
- **Sections come in reading order, and the page says which ones it has.** Every node type has an ordered
  list of sections now: what the model *is* first (a form's fields, a data object's properties, a
  process's tasks, a decision table's inputs, outputs and rules, a service's operations), then how it
  behaves, then what flows through it, then what it is connected to. A process page used to list its
  service tasks last, after the sequence flows; it has twenty sections and had no map. A row of chips
  under the header lists every section the page rendered, with its count, and opens the one you click.
  The section that *is* the model starts open; the rest start folded, remembered as before.
- **Every list on a detail page is a table with column headers.** Names, captions and labels are set in
  the text face, identifiers, expressions, paths and code in monospace — a data object's properties read
  *name · label · type · relation* instead of three monospace words whose meaning was their position.
  A row with more to say expands in place: a form button into the model it invokes, the payload it sends
  and stores, its settings and its expression; a script task into its code with line numbers and the
  validator's findings; a service task into its implementation, the operation it calls and its field
  injections. A long table gets a filter of its own. In a narrow panel — an IntelliJ tool window — the
  optional columns drop under the row instead of being clipped, and nothing scrolls sideways.
- **A process page shows what its diagram alone does not.** Call activities and sub-processes with the
  process each one calls, gateways with their default flow, receive, send and manual tasks, every event
  (not only the named ones) with what it is attached to, every service task (not only the implemented
  ones), the `async` and skip flags on the rows that carry them, and a script listener's code — all of it
  parsed for releases and rendered nowhere. Sequence flows and their conditions are one table. A decision
  table gets an *Inputs & outputs* table, a service operation its parameter table, a document model its
  variables. Every section renders every record it summarises, or says *+N more*.
- **The report pages share the detail page's bones.** Checks, Script tasks, Unused variables and Schema
  gaps open with the same header — icon, title, one line saying what the page is, the page's own numbers
  as facts — and their findings are sections: remembered, folded with one click, mapped by the same chips.
  Each finding that was a pile of monospace rows is a column-headed table; the Scripts page shows every
  script as the card the process page shows, grouped under one section per model, and its filter folds
  away the models it empties. The health rows' jump targets open the section they land on.
- **A checkbox you can see is a checkbox you can click.** The box that appears when you hover a list or
  palette row toggled nothing on a plain click — only a modifier-click on the row did. It is the toggle
  now, in the browse list and in the ⌘K palette, and the UI test clicks it.

## 0.21.1

- **The explorer opens in seconds under Remote Development, not minutes.**
 On a Remote Dev host the
  embedded browser is the thin client's, and everything it shows — a local file included — crosses the
  IDE connection in 16 KB packets, one round trip each: a 3.5 MB report was 200+ sequential round trips,
  half a minute on a 150 ms link, for a file the host reads in milliseconds. The editor now loads a small
  stand-in page at the report's URL, which pulls the report through the IDE bridge in a few large parts
  fired together — one round trip however large the report — and keeps it in the client's browser storage
  under its content hash, so reopening the tab (or the IDE) transfers nothing and a regenerated report is
  fetched once. *Reload* re-reads the file there, so a page rewritten by the CLI shows up too. A local IDE
  is untouched: there the file is a disk read, and the generated page stays one self-contained file for
  browsers and the CLI.

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
- **Inside the IDE, the page wears the IDE's colours.** The embedded explorer took the IDE's light or
  dark mode and then painted Hub navy next to Darcula grey. The plugin now hands the page nine colours
  of the current look-and-feel — panel, editor and tool-window background, border, text, secondary text,
  link accent, selection — on the URL (so nothing flashes before the first frame) and on every theme or
  editor-scheme change, and the page maps them onto its tokens and derives hover, selection and focus
  from them. The type colours, the tone colours and the search highlight stay. This holds while the page
  shows the IDE's mode — following it, or set to the same mode by hand; force the other mode and you get
  the page's own palette for it. A browser sees none of this.

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
