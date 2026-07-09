# Flowable Atlas — IntelliJ IDEA Plugin

*(formerly “Flowable Keys”)*

**The complete Flowable companion for IntelliJ IDEA.** Flowable Atlas turns the model keys and
expressions scattered across your Java code and Flowable models into first-class, IDE-aware
references — completed, validated and navigable — and maps your whole project into a single
interactive explorer. Everything is resolved against the models that **actually live in your
repository**, so a wrong key or a broken expression is caught in the editor, long before deployment.

Zero configuration: open a project that contains the Flowable models you exported from Design (the
app `.zip` / deployment `-bar`, holding `.bpmn / .cmmn / .dmn / .form / .data / .service / …`) or the
Flowable Design `*-models` workspace, and start typing. The plugin indexes every model and puts that
knowledge to work across three areas.

```java
cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("<caret>");         // → case keys
runtimeService.startProcessInstanceByKey("<caret>");                                  // → process keys
dmnDecisionService.createExecuteDecisionBuilder().decisionKey("<caret>");             // → decision keys
formRepositoryService.getFormModelByKey("<caret>");                                   // → form keys

dataObjectRuntimeService.createDataObjectInstanceQuery()
        .definitionKey(ModelConstants.SHOPPING_LIST)   // → data-object keys (also resolves the constant)
        .operation("<caret>")                          // → the backing service's operations
        .value("<caret>", ...);                        // → that operation's input value fields
```

---

## 1 · Model-key intelligence

- **Context-aware key completion** for every public Flowable API that takes a model key
  (`org.flowable.*` **and** `com.flowable.*`): process, case, decision, form, event, channel, data
  object, master data, service, action, agent, knowledge base, template, security policy, query,
  variable extractor, sequence, SLA, dashboard component, data dictionary, page and unified work
  definitions. Fires both inside a string literal (`caseDefinitionKey("<caret>")`) and at a bare
  argument (`caseDefinitionKey(<caret>)` — inserts a quoted key), with Flowable keys ranked to the top.
- **Search by key, name or any infix** — name words are matched too, and typing `0061` at
  `definitionKey("<caret>")` matches `KYC-DO-0061` (start / word-boundary matches still rank first).
- **Press completion twice** (Ctrl+Space, Ctrl+Space) to list **all** model keys of every type,
  regardless of the call site.
- **Cascade completion** for fluent chains — `operation("…")` completes the operations of the data
  object / service resolved from the sibling `definitionKey(…)` / `serviceKey(…)` (constant refs like
  `ModelConstants.X` are resolved); `value("…", …)` completes that operation's input fields; DMN
  `variable("…", …)` offers the decision's input/output variables.
- **More completion domains** (toggle in Settings): BPMN **message** / **signal** names, process/case
  **variable** names, **task-definition keys** and **activity ids**. Task/activity completion is
  **scoped** to the sibling `processDefinitionKey` / `caseDefinitionKey` in the same query chain when
  present, falling back to the project-wide union otherwise.
- **Model → model references in BPMN/CMMN XML** — the key references that live *inside* the models
  (`callActivity calledElement`, `flowable:formKey`, `decisionRef` / `decisionTableReferenceKey`, CMMN
  `caseRef` / `processRef`) get the same **completion**, **Ctrl/Cmd-click navigation** and
  **broken-key inspection** as Java call sites, so a `calledElement="MISSING-PROC"` is caught before
  deployment.
- **Broken-key inspection** — a key that matches no indexed key of the expected type is flagged inline
  with a *“did you mean …?”* quick-fix to the closest real key.
- **Navigation & Find Usages** — Ctrl/Cmd-click a key literal to jump to the model file; hover / Ctrl-Q
  shows its type, name and location. Find Usages / Ctrl-B on a delegate class or a `${bean.method()}`
  expression lists the model files that reference it (and such Java is never reported as unused).
- **Generate a model-constants class** — one action writes a typed Java class holding every model key
  as a `public static final String`, grouped per model type (e.g. `FlowableModelKeys.Process.DEMO_P001`,
  model name in Javadoc). It is then **kept in sync automatically** (debounced) as models are added /
  removed / renamed / edited.
- **Generate a typed data-object bean** — Alt/Option-Enter on a data-object `definitionKey("…")` literal
  writes a Java POJO from the model's `fieldMappings`, so query results map onto a bean instead of the
  generic `DataObjectInstanceVariableContainer`.
- **Liquibase aware** — inside a `*.data.changelog.xml` it completes `<column name="…">` and
  `tableName="…"` from the backing `database` `.service` model, and flags columns that no backing
  Flowable model defines (matched loosely, `CREW_ID_` ≈ `crewId`; rename/drop replayed within a file).

## 2 · Flowable expression language support

First-class editor support for both Flowable expression dialects, wherever they appear:

- **Both dialects** — the backend JUEL dialect (`${…}` / `#{…}`) and the frontend form dialect
  (`{{…}}`) each get **syntax highlighting**, **rainbow parentheses** and **brace matching**.
- **Live validation** — structural syntax errors, plus unknown functions, namespaces or `flw.*`
  members, are flagged against a catalog of the real, verified Flowable function set.
- **Expression completion** — functions, engine root objects, and the project's own process/case
  variables and form fields.
- **Recognised inline, everywhere expressions live** — the expression languages are injected into
  BPMN/CMMN/DMN XML, `.form` / `.page` JSON, and (optionally, via Settings) Java string literals that
  carry `${…}` / `#{…}` — so the highlighting, validation and completion follow you into the models you
  edit, not just the playground.
- **Expression Playground** (the **Flowable Expressions** tool window) — type an expression body, pick
  Backend or Frontend, and get instant validation and completion. An optional *Scope to model* picker
  narrows variable/field completion to one model. The lower panel adds live evaluation:
  - **Frontend** — paste a form payload as JSON and the expression is evaluated live against it (empty
    payload → pure syntax check).
  - **Backend** — *Evaluate against app* runs the expression against a running instance via the Flowable
    Inspect REST API (needs a live process/case/task instance id). The connection (base URL, user,
    dev password) is **auto-detected** from the project's Spring configuration; *Detect from project*
    re-applies it on demand.
- **Optional backend codebase grounding** (Settings) — warn when a root identifier in a backend
  expression is not a known variable, referenced bean or engine root object. A hint, not an error,
  since process variables can be set at runtime without appearing in any model.

## 3 · Atlas explorer

Tools → **Flowable Atlas → Generate Atlas Explorer** maps the whole project (models + Java + Liquibase)
into a single self-contained, offline, interactive HTML page. Choose where to save it (a Save dialog,
defaulting into the project's `atlas-output/`); on success a balloon offers **Open in browser** and
**Open in IDE** — any `*.explorer.html` in the project also opens in an embedded JCEF **Atlas
Explorer** editor tab, where you can click through every relationship in both directions.

A **Generate** scope in Settings switches between *Explorer HTML only* and *All artifacts*, the latter
writing the full set into a chosen folder: `summary.md`, `overview.md`, `graph.json`, `explorer.html`
and a `CLAUDE.md` primer for AI agents.

Under the hood the action runs the shared Atlas engine **in-process** (the same pure-JVM `:core`
module the standalone `atlas` CLI uses), so the output is identical to the CLI — no external tools,
interpreters or subprocesses required.

## Settings

**Settings → Tools → Flowable Atlas** lets you:

- toggle the extra key-completion domains (messages, signals, variables, task/activity, DMN variables);
- toggle expression validation, inline injection into Java strings, and backend codebase grounding;
- opt into indexing the Flowable Design `*-models/` workspace JSON;
- choose how model constants are generated (naming scheme; String class vs. enum);
- choose the Atlas **Generate** scope (explorer only vs. all artifacts);
- pre-fill the Inspect connection (base URL and username) for the Expression Playground.

Verify indexing any time via **Tools → Flowable Atlas → Dump Key Index**.

## Requirements / compatibility

- Target IDE: **IntelliJ IDEA 2026.1+** (`since-build 261`, open until-build). Needs the Java plugin
  (bundled in Community and Ultimate).
- Runs on **JDK/JBR 21+** (compiled to Java 21 bytecode).
- The Atlas explorer generation runs entirely **in-process** (the bundled `:core` engine) — no
  external interpreter or subprocess required.

## Build

The build compiles against the **locally installed** IntelliJ IDEA (`local("/Applications/IntelliJ IDEA.app")`
in `build.gradle.kts`) — exact match to your IDE, no multi-GB SDK download.

Toolchain notes (all matter for a 2026.1 target):
- **IntelliJ Platform Gradle Plugin 2.17.0** (2.5.x fails `runIde` against 2026.1's
  `MultiRoutingFileSystemProvider` bootstrap). It requires **Gradle 9+** — the wrapper is pinned to
  Gradle 9.4.0.
- **Kotlin Gradle plugin ≥ the Kotlin the IDE ships** (2026.1 ships Kotlin 2.3.x → we use 2.3.21),
  otherwise the compiler can't read the platform's Kotlin metadata.
- Compile/run on **JDK 21** (`org.gradle.java.installations.paths` in `gradle.properties` points at
  the local JDK 21; adjust if yours is elsewhere).

```bash
./gradlew buildPlugin        # → build/distributions/flowable-atlas-0.4.1.zip (installable)
./gradlew test               # functional + unit tests
./gradlew runIde             # sandbox IDE with the plugin; open any Flowable project
```

## Install (from disk)

1. `./gradlew buildPlugin` → `build/distributions/flowable-atlas-0.4.1.zip`.
2. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip.
3. Restart. Open a Flowable project, then either generate the Atlas explorer
   (**Tools → Flowable Atlas → Generate Atlas Explorer**), open the **Flowable Expressions** tool
   window, or start typing a key argument at any of the API call sites above.
