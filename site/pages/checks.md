# Health checks

Atlas runs twenty-six checks over every project it analyses. They are computed once, in `:core`, and
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

Every check is one of two **kinds**, and the split is the first thing every surface shows:

- **Defects** — something is wrong *now*: a file that does not parse, a key nothing answers, a gateway
  the engine cannot leave, a column two names disagree about. Fix or accept, but do not ignore.
- **Advice** — nothing is broken. The check names a pattern worth a look — a call with no error path, a
  form nothing references, an async job without its own retry policy — and acting on it is optional.

The Checks page, the summary, the overview and the generated `CLAUDE.md` all lead with the two numbers
(`3 defects · 41 advice`), the CLI status line ends with them, `graph.json` carries them as
`stats.defects` and `stats.advice`, and `--fail-on defects` makes a pipeline red on the first kind only.
Within each kind the checks are ordered by how much they deserve attention: broken first, then
unfinished, then how a process is configured to behave.

The explorer and the IDE's findings tool window label every advice finding *advice* and draw it grey. The
*Severity* column below is what `graph.json`, the summary and `--fail-on` see — every advice check is a
`warning` there, so the page would otherwise print *warning* under the *Advice* heading.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/checks-page.png" alt="The explorer's Checks page: a row per check, then a block per finding kind" width="1400" height="900"><img class="only-dark" src="../assets/img/checks-page-dark.png" alt="The explorer's Checks page: a row per check, then a block per finding kind" width="1400" height="900"></div>
  <figcaption><b>The Checks page of the live demo.</b> Every check row is clickable when its
  count is above zero, and every finding row navigates to the model it belongs to.
  <a href="../demo/explorer.html#/checks" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## The twenty-six checks

| Check | Kind | Severity | What it means |
|---|---|---|---|
| `parseIssues` | defect | error · warning | A file could not be read or fully parsed; as warnings, what Atlas decided not to read. (A key shared by two model types is recorded in `diagnostics`, not reported.) |
| `invalidExpr` | defect | error · warning | An expression has a structural syntax error, or a `variables:` call the engine cannot bind — every problem on that expression is listed, its warnings included. |
| `scriptIssues` | defect | error · warning | A script body has a syntax problem, or calls something its context does not bind. |
| `missingRefs` | defect | error | A model key is referenced but no model in the project defines it. |
| `crossedColumns` | defect | error · warning | A service maps a field to the column another field is named after — as an error when the pairing is a closed swap or rotation. |
| `hardcodedSecrets` | defect | error | A password, token or API key written into a model as plain text. |
| `unsafeQueries` | defect | warning | A query template interpolates a value without escaping it. |
| `changelogIssues` | defect | warning | A Liquibase changelog is orphaned or superseded. |
| `changelogDrift` | defect | warning | An app's copy of a changelog differs from the one in the code. |
| `schemaGaps` | defect | warning | A database column and the model that should describe it disagree. |
| `gatewayNoDefault` | defect | warning | An exclusive or inclusive gateway, or an activity, whose every outgoing flow is conditional, with no default. |
| `shadowedFlow` | defect | warning | A gateway flow that can never be taken, because an unconditional flow is chosen first. |
| `implicitSplit` | defect | warning | An activity with several outgoing flows and no gateway — a fork nobody drew. |
| `payloadOverwrite` | defect | warning | An expression or REST button whose value names no variable, so its result replaces the whole payload. |
| `suspectExpr` | defect | warning | An expression calls a function or namespace Atlas does not know. |
| `leftoverMarkers` | advice | warning | A TODO, FIXME or HACK left in a model file. |
| `nonExclusiveAsync` | advice | warning | An async element explicitly set `exclusive="false"`. |
| `unguardedTasks` | advice | warning | A service task leaves the engine and nothing catches its failure. |
| `asyncWithoutRetry` | advice | warning | Async work with no `failedJobRetryTimeCycle` of its own. |
| `unusedForms` | advice | warning | A form nothing references. |
| `unusedDecisions` | advice | warning | A decision table no process, case or decision service calls. |
| `unusedOps` | advice | warning | A service operation nothing calls. |
| `unusedFns` | advice | warning | A custom expression function nothing uses. |
| `unusedVars` | advice | warning | A variable is written and nothing reads it. |
| `unreadInputs` | advice | warning | A variable is mapped into a called model that never reads it. |
| `guessedVars` | advice | warning | A variable only a script mentions, by bare name. |

Each finding carries the node it belongs to, a message, and — where Atlas knows it — the file, the
element, the line and a snippet, so it is actionable rather than merely true. The line is the element's
declaration in the model file — `id="gw"` — so opening a finding lands on the element, inside an archive
too; a script problem is on the line of the script it sits on. A finding about a missing model points at
the first model that references it, which its message names along with how many more do, and a finding
about an expression names the models that hold the expression. Where one check fires more than once on
the same node it also carries a `subject` naming which one it is: the scope a variable was mapped into,
the column a schema gap is about, the name an expression could not resolve.
The message is prose and rewords as a model changes; the subject is meant to stay put.

## What each one detects

### `parseIssues` — files Atlas could not read

Every entry in the run's diagnostics: a model whose XML or JSON would not parse — a palette or a
dashboard component included, and a user definition (`.user.json`), tenant setup or master-data rows
file under `com/flowable/`, which the platform reads at startup — a file that could not be read, an
archive entry that could not be opened, a Java source that could not be read, and any failure while
extracting custom functions. It also lists what Atlas decided **not** to read, as `skip`
entries at warning level — a legacy wrapper without a body, a
wrapper in a folder Design does not use, a template variation whose template is not in the project, a
process in the old editor's JSON format with no XML twin, an archive nested two levels deep, a model
file above the 32 MB limit, a symbolic link that leads out of the project to a folder or to a file of
a kind Atlas reads, and changelog or template text past the 256 MB a run keeps for the Liquibase replay
(all files and archive entries together; the real projects hold a few MB) — because a file that was
skipped on purpose is no less absent from the report than one that failed. A folder inside the project
that cannot be listed (its permissions) is a `skip` as well: nothing under it was read. A file that is
not a Flowable model at all — a Helm chart's `_helpers.tpl` with a model extension, a palette or manifest
JSON in an export that is no model wrapper — is recorded in `diagnostics` (the CLI prints it with `-v`) but
is no finding: nothing about the project is wrong.
An archive *inside* an archive (a Design export packing one `.bar` per
app) is opened one level down and its models are read like any other. A Design-workspace export — the
legacy editor's `form-models` and `page-models` JSON, whose bodies are a tree of `childShapes` — is read
in full since 0.24.0: its components are rewritten into the shape the current Design writes and
parsed by the same reader, so such a form has its fields, subforms, references and REST calls instead of
being an empty shell registered by key; a subform an older export names by its Design model id
(`FORM_MODEL-<uuid>`) is the form whose key it keeps beside it in `form-id`. The same export keeps a
query as `query-models/<key>.query` and a variable extractor as
`variable-extractor-models/<key>.extractor`, wrapper and all: both are read from inside the wrapper, and
a template's variations are read in the shape Design's editor writes them, so a project whose only copy
is the export has its query parameters, extracted variables and template texts.

A UTF-8 byte-order mark at the start of a JSON model is not a parse failure; the model is read like any
other.

An XML model or a Liquibase changelog that is not well-formed is reported with the line the XML breaks
on, so the explorer and the IDE open the file there, and `-v` prints it as `path:line`. The XML
parser's own `[Fatal Error]` lines are never printed, so under `-q` the run says nothing.

A **key shared by two model types** is not one of them. A case and its start form both called
`DRA-C001`, a data object and the service Design generated for it — this is common and legal. Both
models are read completely; a reference that states its type (a `formKey`, a `calledElement`) reaches
the right one; and the expressions, bindings and variables harvested from each file are credited to the
model of that file's type. (Before 0.24.0 they went to whichever model was registered first, so a form
that shared its case's key showed none of its own bindings and the case showed all of them.) What stays
ambiguous is a Java string literal that names the key alone, outside a key-taking API — it reaches one of
the two and is marked *suspect*. The shared key is recorded in `graph.json`'s `diagnostics` as a `conflict`
entry so a reader knows, but it is not a finding: nothing failed.

The same model — one type, one key — in several files is one model: a Design export beside its deployed
`.bar`, a loose file beside its archive. Where those files reference different models — a test stub reusing
a production key, the older version of a migration test — the model's page lists the others under
*Other copies* and shows the references of every copy, and `diagnostics` records a `copy` entry. The
model is one of those files everywhere — the first one read outside a test source set: its own entry,
`graph.json`'s `modelIndex`, the `target` of every reference to it and the overview's listing of an app's
models all name it.

This is the one check you should never carry. A parse failure does not just cost you that file — every
reference into and out of it disappears too, which makes the rest of the report quietly less complete.
That is why it is reported everywhere, including a clickable **⚠ parse issues** chip in the
explorer's sidebar footer.

### `invalidExpr` — the expression does not parse

An expression whose problems include at least one error: an unclosed bracket, an unterminated string,
a stray operator — or a `variables:` call with a number of arguments the engine cannot bind, such as
`${vars:get('route', 'A')}`, which reads `'route'` as the variable container
([Expressions & scripts](../expressions/) has the rule). It cannot evaluate at runtime, so this is a real
defect rather than a style note. Every problem on that expression is listed under this check, including
any warnings it also has.

### `scriptIssues` — script bodies

Groovy and JavaScript bodies in BPMN script tasks, CMMN plan-item scripts, execution / task /
lifecycle listeners, an action's bot script, and the `config.script` of a script-type service
operation — which the service registry runs only when something calls the operation, so a body that
cannot run would otherwise first fail in front of that caller. The operation's input parameters are
bound by name there, so an input called `task` is not reported as a root the context lacks. Two
families:

- **Structural** — unterminated string or comment, unclosed interpolation, unmatched or mismatched
  closer, unclosed opener.
- **Configuration and semantics** — an empty body, a missing or unknown `scriptFormat`, a format whose
  case is wrong, an unknown member on a bound root object, a root that does not exist in this script's
  context, an EL-only API called from a script, and a listener type that does not support scripts.

The count is a count of **findings**, not of scripts carrying them, which is what the CLI status line
and `stats.scriptIssues` also mean.

A CMMN plan item's lifecycle listener, or a case's own `caseLifecycleListener`, that carries a script is
reported here too: the engine's listener factory has no script branch, so such a listener is a silent
no-op — and before 0.24.0 the parser's note about it was recorded on the plan item and read by nothing.

### `missingRefs` — a key with nothing behind it

A model references another model by key, and no model in the project defines that key. Typically a
typo, a model that was never exported, or a reference to something that lives in a different app.

A security policy or an SLA is a reference wherever the platform reads it: on the process, on the case
or its plan model — where Design writes a case's policy — and on a user task or human task, which may
name a policy and an SLA of its own. A renamed one is missing whichever of these names it.

An action's `channels` — `menu`, `quick-menu`, `slash-menu`, the mobile menus — say where in the UI the
action is offered; they are not channel models and are not looked up (before 0.24.0 every action with a
placement reported two missing models). Likewise the models the platform ships in its default-models
modules resolve to a platform-provided external node rather than to a missing model: its default forms
(such as the `F07-deactivateUser` form that a copied `deactivate-user.action` keeps), its permission
decisions, templates, documents, actions and security policies, and the events it ships —
`_flowableMailEvent` and the `_flowableEngage…Received…` family. The match is by kind and key: a
misspelling of one is still a missing model, and so is a call activity that names a platform *form*'s key.

Some references sit where nobody looks for one, and renaming the model behind them leaves the
referencing model silently empty at run time, so they are resolved like any other: a query's
Elasticsearch template filtering on `caseDefinitionKey`, `processDefinitionKey`, a scope key or the
instance index's `definitionKey` (in `templateFilter` or `templateContent`); a REST data table or select
whose query parameters name a `caseDefinitionKey`, `processDefinitionKey` or `dataObjectDefinitionKey`;
a variable extractor's `fullTextVariables`; and a Design export's action whose form is a `{id, key}`
object. A value the template or the form fills in at run time — `${caseKey}`, `{{$temp.filter}}` —
names nothing, and is left alone.

### `crossedColumns` — the column mapping pairs the wrong two names

A `.service` model pairs a logical field with a physical column. Nothing validates the *pairing*: if
two fields were entered with each other's column,

```json
{"name": "userName",  "columnName": "FIRST_NAME_"}
{"name": "firstName", "columnName": "USER_NAME_"}
```

then every name exists, every column is mapped, and `schemaGaps` reports the chain as complete — while
at runtime each field silently reads and writes the other one's column. This check is the one that
says so. The evidence is that the names already state what the pairing should be, and it comes in two
strengths:

- **swapped / rotated** (error) — the mappings form a closed cycle: `userName` takes `firstName`'s
  column and `firstName` takes `userName`'s. Field names and column names are the same set, paired
  wrongly, which no naming convention explains. A rotation of three or more is reported the same way.
- **crossed** (warning) — one direction only: the column this field's own name points at exists in the
  same table, mapped by another field or by none, and the field maps something else. Usually the same
  mistake; occasionally a deliberate mapping onto a legacy column, which is why it warns.

Names are compared case- and separator-blind, so `userName` and `USER_NAME_` are the same name. A
field mapped to a column of a *different* name is ordinary and stays silent — `customerName` ↔ `NAME_`
is only reported if the table also has a `CUSTOMER_NAME_` for the field name to point at. On a real
project of 120 column mappings, 5 of them deliberately abbreviated, the check reported nothing.

The service page and the schema report mark the row with `⇄ crossed`: a crossed mapping is not a
coverage gap, so the row that needs the reader's attention is otherwise the one that looks cleanest.

### `hardcodedSecrets` — a secret written into a model

A value under a key that names a secret — `password`, `token`, `apiKey`, `clientSecret`, `credential`
and their spellings — written as plain text: in a `.service` model's configuration or an operation's,
in a `.channel`, in an agent's model settings, in a knowledge base's vector store, and in the field
injections of a BPMN or CMMN service task. Credentials inside a URL (`https://user:secret@host/`) count
too, whatever the key is called. A secret in a model file is a secret in the repository, in every export
and in every report, and the same one on every environment the model is deployed to.

Only a *literal* is a finding, on both arms: `https://${user}:${password}@host` is the shape the fix
asks for, not a leak. A key that talks *about* a secret is not one either — `tokenizerModel`,
`maxTokens`, `useTokenAuth`, `passwordPolicyDescription`, `privateKeyAlias` — because a
`hardcodedSecrets` error that is wrong costs more trust than one that is missed.

The finding names the **path** and never the value. It stays quiet for a value that is an expression
(`${…}`, `#{…}`, `{{…}}` — resolved elsewhere, which is exactly the fix), for a key that merely talks
about a secret (`tokenUrl`, `passwordField`, `credentialsType`), and for anything that is not a string.
A placeholder such as `changeme` *is* reported: a committed literal is a literal. Move the value to an
expression or to the environment's configuration — and rotate what was committed.

No other artifact carries the value either. A secret-named field injection's literal, the credentials
in a URL (`https://user:secret@…`) and a query parameter named like a secret (`?access_token=…`) appear
as `***` in `graph.json`, the explorer, the Markdown artifacts and a `--slice`, wherever the model's
configuration is shown — a service's base URL, a script operation's body, a REST call and the external
node it reaches, an HTTP task's request URL, its `Authorization` header and a secret-named key in its
request body, a password field's literal default, the default a REST handler declares for an
`Authorization` header or a secret-named parameter — and the value of a Java constant named like a
secret is not carried at all. A credential no rule recognises — a mail body that says *your password
is …*, a password assigned inside a script — is no finding either, and is shown as written.

### `unsafeQueries` — a value that can change the query

A `.query` model's `templateContent` or `templateFilter` interpolating a parameter as `${name}` with no
FreeMarker built-in behind it. A value dropped raw into search JSON can close the string it sits in and
change the query — the injection shape, in the one place a project writes raw query text. Write
`${name?json_string}` for a string and `${name?c}` for a number; any built-in silences the check, since
which one is right depends on the type only the author knows. One finding per interpolation, so a
parameter used twice is reported twice.

### `changelogIssues` — Liquibase authority

The changelogs are first replayed the way Liquibase runs them (see [how changelogs are read](#how-changelogs-are-read)
below). A changelog is then reported when it is:

- **orphan** — nothing explains why its table exists: a schema definition no service or data object
  references, or an application changelog whose `serviceDefinitionReferences` names a service the project
  does not define, which the finding names. An application changelog that names no service, and whose
  tables no service maps, backs a table the code owns — a JPA entity, a lock table — and is not reported.
  In a repository that defines no service and no data object at all, the services live elsewhere — Design
  deploys them — and a changelog naming one is not reported either;
- **superseded** — the services of its table are bound to another changelog, of another Liquibase run,
  and the finding names it: typically an older schema definition the app still carries for a table the
  project's own changelog builds. Changelogs of one run are one history and never supersede each other —
  the file that adds a column to another file's table is part of that table.

Not reported: a schema definition with the same `logicalFilePath` as one of the application's changelogs
is the same Liquibase changelog. The app carries a copy, the application runs its own, and the explorer
marks it **copy**. The same file loose and inside a `.bar`, or exported into several folders, is one
changelog as well.

#### How changelogs are read

Changelogs are read wherever they are: loose under `src/main/resources`, and inside an archive — a Design
export packs `liquibase-<key>.data.changelog.xml` next to the models it belongs to. They reach the
database two ways, and are replayed the same two ways:

- **The application's changelogs** — loose files that are not a `.data.changelog.xml` — are one run into
  one database, as the application's Liquibase applies them at startup. A master runs its `<include>`s
  and `<includeAll>`s where they stand, and an `includeAll` takes its folder recursively in Liquibase's
  plain path order: `v10/` runs before `v2/`. A changelog no master includes runs after the masters, in
  natural path order. Changelogs under a test source set are replayed apart from the application's.
- **Each schema definition** — a `.data.changelog.xml` — is a run of its own: the data-object engine
  applies it on request, against a changelog table of its own. Every copy of one definition, such as the
  same app exported into `v2/` and `v3/`, runs into the same history, in natural path order.

Within a run, a change set is identified the way Liquibase identifies it: by id, author and
`logicalFilePath`, or the path it was included by. One that already ran does not run again, so a
changelog kept in several version folders under one logical path builds its table once. A precondition is
evaluated against the tables built so far: `tableExists`, `columnExists`, `changeSetExecuted`, `not`,
`and` and `or`. A table no changelog of the project creates, such as an engine table, counts as neither
there nor missing. A check that needs a live database, such as `sqlCheck` or `dbms`, passes. A change set
whose precondition fails is skipped unless `onFail` is `WARN`. Nothing inside a `<rollback>` or an XML
comment runs. For `<sql>`, `<sqlFile>` and formatted-SQL changelogs, the `CREATE TABLE`, `ALTER TABLE`
and `DROP TABLE` statements are read. Properties such as `${varchar.type}` are expanded. A changelog that
is not well-formed XML is a `parseIssues` error, because Liquibase stops on it at startup.

The table a service maps is the one the application builds: when one of the project's own changelogs
shapes the service's table, that changelog describes it, whichever schema definition the service model
names. The definition is applied on request only, and the project's copy is usually the newer one.

### `changelogDrift` — the app's copy differs from the code

A schema definition in an app with the same `logicalFilePath` as one of the application's own changelogs is
a copy of it (see [`changelogIssues`](#changelogissues-liquibase-authority)). The two are compared change set by
change set — matched by id and author, compared by what they do, with whitespace, attribute order and
comments set aside — and one finding names what differs:

> `db/changelog/data-objects/customer.xml` in the code does not match the app's copy
> `Demo-bar.zip!liquibase-customerSchema.data.changelog.xml`: change set `4` only in the code; change set `3`
> only in the app

The app deploys its copy as the table's schema definition, so where the two part ways the model describes a
table the database does not have: applying the definition creates, or fails on, what the application never
made. Carry the change to the other side — export the changelog from Design again after changing it in the
code, or copy the code's change sets into the model. Copies that match are not reported.

### `schemaGaps` — the database and the models disagree

Per column, walking Liquibase → service → data object:

The table is the one Liquibase leaves once every change set has run, so a column a later change set
drops is not a gap, and one it adds is compared too.

A service that no data object binds reports no "used by no data object" rows: it is used directly, and
the row would be true of every mapped column and say nothing about any one of them.

Only the service's own table is compared. A changelog that creates several tables used to hand every
column of the *other* tables to a service whose own table it did not describe, as "not mapped by the
service" — those rows are gone.

- **not mapped in service** — the column exists in the changelog, but the backing `.service` model does
  not map it, so no model can read or write it.
- **not in data object** — the service maps it, but no data object uses it. The field is matched by the
  mapping's *field* name, which is what a `.data` field binds to — not by the column name. A data object
  typed by a data-dictionary type has that type's properties as its fields; its own field mappings only
  add the lookup id and labels, so they are not what the column is matched against.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/schema-page.png" alt="The schema gaps page: per service, a three-column table of Liquibase column, service mapping and data object field" width="1400" height="800"><img class="only-dark" src="../assets/img/schema-page-dark.png" alt="The schema gaps page: per service, a three-column table of Liquibase column, service mapping and data object field" width="1400" height="800"></div>
  <figcaption><b>Schema gaps</b>, per service — cleanly-mapped services collapse to chips, so the
  gaps are what you see.</figcaption>
</figure>

The explorer renders this as a three-column table per service, with cleanly-mapped services collapsed
to chips so the gaps are what you see.

### `leftoverMarkers` — a promise nobody kept

A `TODO`, `FIXME` or `HACK` as a whole word anywhere in a model file — a documentation element, a
name, a script body, a template, a form's text. (`XXX` is left out on purpose: it is the placeholder of
every format hint far more often than a mark.) Each one is reported with its file and line and attributed
to the model it sits in — the process whose element holds it when a deployment file defines several,
else the model the file defines. The text after the marker — up to the end of its line, string or
element — is its subject, so a waiver survives the line moving; a marker with no text of its own in a
minified JSON model is named by the path of the element holding it (`rows[2].cols[0].label`), which
survives a re-export where a column would not. In a BPMN, CMMN or DMN file such a marker is named by the
element holding it and what carries it — `sentry2/documentation`, *TODO in the documentation* — so a
waiver survives a line moving there too; two of them in one element are told apart by their order in it
(`t/string#2`), and only one outside every element with an id falls back to its line. A marker in an XML
model also names its element, so the finding jumps to it — a sentry by the diamond of its criterion, the
element the diagram draws. In a JSON model the finding also names the element that
carries the marker — a form component, by its id — and quotes what it says: *TODO in the label: "Current
Pod (TODO)"*, where the path alone left a reader to count rows. A minified model is one line, so its
finding gives no line number. The same model loose, inside an archive, or as a Design export whose
components nest is one marker, not two. A marker is a promise someone made to come back; in a model that is deployed it is a promise
the process keeps running without. Do the thing, or record why it can wait — as a note in
`waivers.json`, where the next reader finds it.

### `gatewayNoDefault` — a choice with no way out

An exclusive or inclusive gateway with two or more outgoing flows, every one of them conditional, and no
`default`. When none of the conditions holds the engine has nowhere to go and throws *no outgoing
sequence flow* — the instance fails right there, on the data that reached it, which is usually the data
nobody thought of. A gateway with a default flow, or with any unconditional flow, has a way out and is
not reported; a parallel gateway takes every flow and an event gateway waits, so neither is asked.

A gateway with a *single* conditional flow and no default is reported too: it is no choice, but when
that one condition is false the engine throws the very same exception.

Conditional flows straight out of a task, an event or a subprocess are a choice drawn without a gateway,
and the engine leaves the activity the same way: the flows whose condition is false drop out, the
activity's own default flow is the fallback, and with nothing left it throws the same exception. So an
activity whose every outgoing flow is conditional, with no default flow among them, is reported on the
activity — a single conditional flow included, since when it is false the instance fails there.

Two flows whose conditions are exact opposites always leave one way out and are not reported:
`${x}` and `${!x}`, `${a == b}` and `${a != b}` (or `eq`/`ne`), `vars:isEmpty(v)` and
`vars:isNotEmpty(v)`. An unset value cannot slip between them: it makes the condition itself throw, which
no default would catch. `== true` and `== false` are *not* opposites — both are false when the value is
null — so the demo's `DEMO-onboarding` gateway stays reported, and so do `vars:equals`/`vars:notEquals`
and `> 10`/`<= 10`.

Mark one flow as the default — the gateway's `default`, or the activity's default flow — or add an
unconditional flow. Accept the finding when any other set of conditions is provably exhaustive.

### `shadowedFlow` — a flow the gateway never takes

An exclusive gateway leaves through the first outgoing flow, in the order the file lists them, whose
condition holds — and a flow with no condition always holds. The default is consulted only when nothing
was taken. So once an unconditional flow comes up, every flow listed after it and the gateway's default
can never be taken, and the routing silently ignores them: a flow labelled *≥ 15 km/h* with no condition
next to a default *< 15 km/h* means the slow branch never runs. The message names the unconditional flow
and every flow it shadows. An inclusive gateway takes every flow that holds, so there only its default is
dead.

The engine's own validator only warns about an unconditional flow out of an exclusive gateway, and the
deployment goes through. An unconditional flow written **last** is an implicit default — nothing comes
after it, and with no `default` set nothing is shadowed — and is not reported.

Give the unconditional flow its condition, or make it the gateway's default.

### `implicitSplit` — a fork nobody drew

An activity — a task, an event, a subprocess — with two or more outgoing sequence flows and no gateway
between. The engine takes every flow whose condition holds *and* every flow without one, so two
unconditional flows out of one task run in parallel: a fork that is invisible on the diagram and easy to
read as a choice. When every outgoing flow is conditional the check stays quiet — that is a choice someone
drew as conditions, and whether it has a way out is [`gatewayNoDefault`](#gatewaynodefault-a-choice-with-no-way-out)'s question.

An activity's own **default flow** — BPMN allows one on a task, and the engine takes it only when no
condition holds — is not an unconditional flow, so a task with conditions plus a default stays quiet. A
DMN service task, which the parser lists both as a service task and as a rule task, is judged once.

Put a parallel gateway there if the fork is meant, or an exclusive gateway with conditions if it is a
choice.

### `payloadOverwrite` — a button that replaces the payload

An expression button stores what its expression computes, and a REST button what its call returns, in the
variable its **Value** binds. The forms runtime takes the path to write from that binding, and only from a
plain one — braces around a variable path, `{{total}}`, `{{order.total}}`, `{{$temp.total}}`. `{{}}`,
`{{ }}`, an expression such as `{{a + b}}`, a literal, or no value at all binds no path, so the result is
written to the root of whatever the button sits in: the form's whole payload, or the whole value of a bound
panel or subform. It replaces it rather than merging into it, even when the result is empty. Every field
the user filled in is gone the moment the button is pressed — or, when it executes automatically, the
moment the form opens. A button meant only to *do* something still stores what it computed, which is how a
`{{}}` written as "no variable needed" turns into a form that loses its data.

A REST button with **Store response attributes** writes each mapped name instead of its response, and is
not reported. The message names the button, what its value says, and whether it runs on its own; the
finding opens on the button in the form.

Bind the value to the variable the result belongs in, or to `{{$temp.…}}` for a value only the form needs.
Find every expression button in the explorer with `has:"expression button"`, and every empty binding with
a search for `{{}}`. Accept the finding only when replacing the payload is the point.

### `nonExclusiveAsync` — async jobs that may run at the same time

`flowable:exclusive` defaults to **true**, and the exporter writes the attribute only to say `false`, so
its presence is an explicit opt-out: the jobs of one process instance may then execute concurrently.
That is occasionally what you want and usually an optimistic-locking problem waiting for load. The check
fires only on the opt-out, never on its absence — a check that flagged every async element would fire on
every project and be worth nothing.

### `unguardedTasks` — a call out of the engine with nothing catching it

A service task whose work happens outside the engine — an HTTP call, an external worker, an AI agent, a
mail task, a service-registry task whose service is REST, or code of your own (a `class`, a delegate
expression that is not a platform bean, an `expression` whose root is a bean of your own — one Java
declares, or one named the way beans are named, `…Service`, `…Task`) — with no error boundary event
attached to it. An `expression` whose root is a variable — `${requesterData.getName()}` — reads it and
stays in the engine. A failure then propagates to whatever
called the process.

The bean alone does not decide it. Flowable Design writes a platform bean into every task type's
delegate expression — `${initVariablesService}` sets variables, `${auditLogService}` writes a row,
`${dataObjectServiceTask}` reads the platform's own tables — and none of those depends on anyone else's
uptime, so they stay quiet; the engine's own services (`dataObjectRuntimeService`,
`platformIdentityService`, …) and a platform function namespace (`${bpmn:removeAssignee()}`) are engine
API, not code of your own. The set of platform beans is read off the platform's own auto-configuration —
the task delegates Design writes, the Engage conversation tasks, the platform services an expression may
call — and includes `flw`, the platform's scripting-API root, so `${flw.setOutput(…)}` in a task listener
is platform API as well. Measured on real projects before this distinction, 94 % of the findings sat
on such beans. The message names the fix for the kind of call: a synchronous mail task is told to go
async (a failed job is retried and reported), set `ignoreException`, or map the failure to an error code,
an HTTP, worker, agent or REST service task to get a boundary event, and a call into the project's own
code says so.

**Only a BPMN error reaches an error boundary event or an error event subprocess.** Any other exception
out of a delegate, a class, a mail task or an HTTP task is rethrown past them, unless the task maps it to
an error code with `flowable:mapException`. So a boundary counts as the error path of a mail task only
when the task declares a `mapException`, and of an HTTP task only when it maps the failure — with
`mapException`, `handleStatusCodes` or its own response handler; a mail or HTTP task behind a boundary
that can never fire is reported, and the message says why. Code of your own, an external worker and an
agent can throw or report a BPMN error themselves, so their boundary is taken at its word, and the
project-code message adds that the boundary catches only a `BpmnError` or a mapped exception.

An HTTP task that carries `ignoreException` swallows its failures and stays quiet. One with
`handleStatusCodes` does the opposite of saying what happens: the engine turns each listed status into
the BPMN error `HTTP<code>`, so it is reported until an error boundary event or an error event subprocess
catches that — even with `ignoreException`, which swallows connection failures but not those errors.

Any **async** task stays quiet too: its failure is a failed job, retried and then an incident for an
administrator, never an exception to whoever completed the previous step. That is its error path;
`asyncWithoutRetry` judges how good a one it is. The same holds for a synchronous task that only ever runs
inside a job: every path to it starts at a timer — a timer start, an intermediate timer catch, a timer
boundary — or at an async step, with no wait state in between. A wait state wins over async — an async
user task still waits for the user, whose completion calls what follows — and a task with no incoming
flow, or reached from any start or catch that is not a timer, keeps its caller.

Letting an error bubble up is a legitimate design, so this one is deliberately quiet wherever it cannot
be sure: it says nothing about a task whose failure an error event subprocess catches for the whole
process, and nothing about such a task in a process whose subprocess carries an error boundary — the
element lists are flat, so a task inside that subprocess cannot be told from one beside it, and reporting
a task that is already handled one level up is worse than missing one.

### `asyncWithoutRetry` — async work that does not say how often to try

An element marked `flowable:async` with no `failedJobRetryTimeCycle`. The engine default then applies,
which is a decision made elsewhere and easy to be unaware of. Many projects set retries globally, so
this is the most likely of the three to be a deliberate omission — waive it once for the project rather
than carrying the noise.

### `suspectExpr` — an expression Atlas cannot vouch for

The expression parses, but it calls something that is not in the catalog: an unknown function
namespace, an unknown function inside a known namespace, or backend function syntax in a frontend
expression. If your project registers its own functions, tell Atlas with `--expr-allowlist` (or the
plugin's allowlist setting) and these stop being reported — see
[Expressions](../expressions/#the-allowlist).

### `unusedDecisions` — a table nothing consults

A DMN decision no process, case or decision service references. Belonging to an app is not use. A
decision table nothing calls is a rule set the project maintains and never runs — or the trace of a call
that was renamed away from it, which is the case worth a look. Accept it when a decision service outside
this repository consults it.

A decision *service* — the DRD Design generates around a set of tables — is the caller of its tables, so
it is never reported as an unused table itself. A DRD Design exports holds a copy of each table under a
generated id (`decisionElement_1`, `decision1`) and names the table in `flowable:decisionKey` or, in an
older export, only in the service's JSON twin. Atlas follows either link to the table, so a table only a
decision service runs counts as called, and the copy is not a decision of its own. A copy whose table is
not in the project stays as the service's decision, marked `copyOf` that table: the service runs its
content, so nothing is missing.

### `unusedForms`, `unusedOps`, `unusedFns` — defined, never used

- A **form** with no incoming reference other than its app membership. Belonging to an app is not use.
- A **service operation** nothing calls.
- A **custom expression function** nothing names. A `$`-prefixed name (`{{$fmt(x)}}`) counts like any
  other, and so does a function handed on by reference (`{{names.map(fmt)}}`). An `additionalData` entry
  whose value is a literal — `$isDesktop: true`, a string, a number — is a value a binding reads, not a
  function, and is never reported.

A group allowed to press a button on a form is not a use of the form either: access says who may, not
that anything opens it. A data-object data table's four form keys — view, create, edit and delete — all
count, as long as the table's action is not switched off.

A service operation a **data object** binds to is used by that data object when it is one the engine
invokes itself — the operations keyed `findById`, `create`, `update` and `delete` (the service registry
picks an operation by key, so another operation of the lookup type is not one of them). Nothing in a model names those (a data-object
task, a page's data table and the REST API all go through the data object), so before 0.24.0 every
generated CRUD operation was "unused": 74 of 74 on one real project. A `search` operation is different —
something has to name it: a data table's operation key, a select's `searchOperationKey` and
`lookupOperationKey`, a data-object table's create/edit/delete operation keys while that action is on — and
it is credited only when something does.

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
| `<project>.graph.json` | `findings` (itemised), `checks` (per-check counts plus `open` and `waived`) and `stats.defects` / `stats.advice`. |
| `<project>.CLAUDE.md` | A findings summary, so an agent starts from what is already known to be wrong. |
| The explorer | The **Checks** page (`#/checks`): a row per check — worst first, its tone in words (*error*, *warning*, *advice*), the clean ones folded with what they examined — then a block per check with the finding's tone, model, element (a jump into the model), message and `file:line`, headed by one line saying what the check means with *why · what to do* beside it, and an **accept…** control on every row. A model's own page lists its findings on its Findings tab, with a ⌖ button that puts the element in view on the diagram. |
| The IDE | The same explorer page, opened as an editor tab. |

## Accepting a finding

Some findings are correct and still not worth acting on: a reference into a repository Atlas cannot
see, a form kept for a pilot, a task whose errors are meant to reach the caller. Deleting the check
for everyone is the wrong answer to one of those, so a project can accept individual findings in
`waivers.json`.

Atlas writes that file's folder for you. With `--all` it sits beside the artifacts, next to a
`.gitignore` that ignores exactly the artifacts Atlas writes there, by their suffixes (`/*.summary.md`,
`/*.graph.json`, `/*.diagrams/`, …) — the analysis is regenerated and may carry client data, the decisions
are yours and belong in review. Nothing else in the folder is ignored, so `waivers.json`, and any file of
yours that lands beside it, stays visible to git. A folder that already has a `.gitignore` keeps its own.

```json
{
  "version": 1,
  "waivers": [
    {
      "check": "missingRefs",
      "node": "external:DEMO-Shared-P001",
      "reason": "lives in the shared-processes repository, resolved at deploy time",
      "by": "team-orders",
      "at": "2026-09-11"
    }
  ],
  "notes": [
    { "node": "form:DEMO-F014", "text": "replace with the new intake form in Q3", "importance": "high" }
  ]
}
```

A waiver names **which** finding it accepts, not where that finding happened to appear: `check` plus
`node`, narrowed by `element` and [`subject`](#what-each-one-detects) when you want one of several
rather than all of them. Leave those out and the rule covers every finding of that check on that node.
The message is not part of the key — it is prose that rewords as a model changes, and a waiver keyed
on it would lapse for reasons that have nothing to do with the decision it records.

What a waiver does:

- The finding **stays in the report**, marked, in its own *Waived* section. Nothing is silent here
  either: a suppression that also hides what it suppressed leaves the next reader unable to see what
  the team decided to live with.
- It leaves the counts. `checks` reports the open findings plus a `waived` total, and `--fail-on` does
  not match it.
- `reason` is the only part a reviewer can actually review, so a rule without one is reported every
  run. It is not refused — a tool that will not run because its suppression list has a gap is a tool
  people stop running.
- `until: "2026-12-01"` makes it temporary. After that date it stops suppressing and starts being
  reported, which is what the author asked for by writing it.

Any severity can be waived, `error` included. The alternative is worse: a team that cannot silence one
un-actionable error drops `--fail-on error` altogether and loses the gate for everything.

A rule that matches nothing is **stale** — the model was renamed, or the problem was fixed — and says
so on every surface, because a suppression that quietly stops applying is the one failure this must
not have. `--fail-on-stale-waivers` turns that into a red build; `--no-waivers` reports everything, for
when the question is what the file is hiding.

A file that waives less than it seems to is reported the same way, on every run and never as a
failure: a syntax error, with its line and column; a rule whose `check` this Atlas does not know — it
can never match, so the nearest check id is suggested, and the rule is kept; a top-level key other
than `version`, `createdWith`, `updatedWith`, `waivers` and `notes` — above all `rules`, which is what
`graph.json` calls them — since nothing reads it and the explorer's *Save* and *Export* write the
file without it; and a `waivers` or `notes` that is not a list. A `--waivers` path that does not exist
is said on stderr, too.

You do not have to write the file by hand: in the explorer every finding row — on the Checks page and
on the model's own page — offers **accept…**, with the reason, an optional `until` and the author, and
narrowed to the element by default. A bar on every view counts the unsaved decisions; **Save to
waivers.json** writes the file inside the IDE and regenerates the explorer, **Export waivers.json**
downloads it anywhere else — see [Accepting a finding](../explorer/#accepting-a-finding). A parse
finding has no model: its `node` is the path of the file that would not read.

`notes` are the other half of a review: a remark that changes no count, carries an `importance`, and
travels with the project so the next person reads it instead of rediscovering it.
