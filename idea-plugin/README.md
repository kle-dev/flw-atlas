# Flowable Atlas — IntelliJ IDEA Plugin

*(formerly “Flowable Keys”)*

Two things in one IDE plugin for Flowable projects:

1. **Generate the Atlas explorer** — Tools → **Flowable Atlas → Generate Atlas Explorer** maps the
   whole project (models + Java + Liquibase) into a single self-contained, offline, interactive HTML
   page. Save it into the project, then open it in your browser or in an embedded in-IDE viewer. It
   runs the bundled `flowable_atlas.py` generator, so the output is identical to the standalone
   `atlas` CLI. Requires a **Python 3.8+** interpreter (auto-detected, or set it in Settings).
2. **Context-aware key tooling** — completion, navigation, inspections and constant generation for
   **Flowable model keys**, searchable by **key or name**:

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

## Status — v1

- ✅ **Atlas explorer generation** — Tools → Flowable Atlas → **Generate Atlas Explorer** runs the
  bundled Atlas generator (`flowable_atlas.py`, shipped as a plugin resource) via a located Python 3
  interpreter, writing a self-contained `*.explorer.html` to a location you choose (a Save dialog,
  defaulting into the project's `atlas-output/`). On success a balloon offers **Open in browser** and
  **Open in IDE**; any `*.explorer.html` in the project also opens in an embedded JCEF **Atlas
  Explorer** editor tab. A **Generate** scope in settings (`AtlasArtifactScope`) switches between
  *Explorer HTML only* (`--html` to a chosen file) and *All artifacts* (`--all` into a chosen folder:
  `summary.md`, `overview.md`, `graph.json`, `explorer.html`, `CLAUDE.md`). Python is auto-detected
  (`python3` / `python` on PATH) or set explicitly in Settings → Tools → Flowable Atlas. See
  `AtlasGeneratorService`, `PythonLocator`, `AtlasScript`, `GenerateAtlasExplorerAction`,
  `AtlasFileEditorProvider`.
- ✅ **Model index** (`FlowableModelIndexService`): scans the project for every Flowable model
  file — deployment `…-bar/` artifacts (`.bpmn/.cmmn/.dmn/.form/.action/.data/.service/…`) **and**
  the Flowable Design `*-models/` workspace — and extracts each model's `key` + `name`.
- ✅ **Java key completion** for all public Flowable APIs that take a model key
  (`org.flowable.*` + `com.flowable.*`): process, case, decision, form, event, channel,
  data object, master data, service, action, agent, knowledge base, template, security policy,
  query, variable extractor, sequence, SLA, dashboard component, data dictionary, page, work.
  See `FlowableApiCatalog.kt`.
- ✅ **Cascade**: `operation(...)` completes the operations of the data object / service resolved
  from the sibling `definitionKey(...)` / `serviceKey(...)` in the fluent chain (constant refs
  like `ModelConstants.X` are resolved); `value("...", …)` completes that operation's input fields.
- ✅ Fires both inside a string literal (`caseDefinitionKey("<caret>")`) and at a bare argument
  (`caseDefinitionKey(<caret>)` — inserts a quoted key). Flowable keys are ranked to the top.
- ✅ **Press completion twice** (Ctrl+Space, Ctrl+Space) inside any string / argument to list
  **all** model keys of every type — not just the ones matching the current API method.
- ✅ Search candidates by **key or by name** (name words are matched too), and by **any infix** of
  the key — typing `0061` at `definitionKey("<caret>")` matches `KYC-DO-0061` (start / word-boundary
  matches still rank first). See `FlowableInfixMatcher`.
- ✅ **Generate a model-constants class** — Tools → "Flowable: Generate Model Constants" writes a
  Java class holding every model key as a `public static final String`, grouped into a nested class
  per model type (e.g. `FlowableModelKeys.Process.DEMO_P001`, with the model name in Javadoc). The
  class is then **kept in sync automatically** (debounced) whenever a model is added / removed /
  renamed / edited (`ModelConstantsAutoRefresher`; opt-out by deleting the class — refresh only
  updates a class that still exists). Target FQCN is remembered per project (`ModelConstantsSettings`).
- ✅ **Generate a typed data-object bean** — Alt-Enter on a data-object `definitionKey("…")` literal
  writes a Java POJO from the model's `fieldMappings`, so query results map onto a bean instead of the
  generic `DataObjectInstanceVariableContainer` (`GenerateDataObjectBeanIntention`,
  `DataObjectBeanGenerator`).
- ✅ **More completion domains** (toggle in settings): BPMN **message** / **signal** names
  (`startProcessInstanceByMessage`, `signalEventReceived`), process/case **variable** names
  (`get/setVariable(id, "<caret>")` — arg 1), **task-definition keys** (`taskDefinitionKey`) and
  **activity ids** (`activityId`), plus **DMN decision variables** (`ExecuteDecisionBuilder.variable`,
  resolved from the sibling `decisionKey`). `taskDefinitionKey` / `activityId` are **scoped to the
  sibling `processDefinitionKey` / `caseDefinitionKey`** in the same query chain when present (only
  that model's ids), falling back to the project-wide union otherwise.
- ✅ **Broken-key inspection**: a model key that matches no indexed key of the expected type is
  flagged (only when that type has indexed keys), with a **quick fix** to the closest known key.
- ✅ **Navigation**: Ctrl-click / Go-To-Declaration on a key literal jumps to the model file
  (`FlowableKeyReferenceContributor`); Find Usages works; hover / Ctrl-Q shows type, name and file.
- ✅ **Model → model key references in BPMN/CMMN XML**: the key references that live *inside* the
  models — `callActivity calledElement`, `flowable:formKey`, `decisionRef` /
  `decisionTableReferenceKey`, CMMN `caseRef` / `processRef` — get the same **completion**,
  **Ctrl-click navigation** and **broken-key inspection** (with quick fix) as Java call sites, so a
  `calledElement="MISSING-PROC"` is caught before deployment. Namespace-agnostic, only inside model
  XML. See `FlowableXmlKeyCatalog`, `FlowableXmlKeyCompletionContributor`,
  `FlowableXmlKeyReferenceContributor`, `FlowableXmlBrokenKeyInspection`.
- ✅ **Liquibase coverage inspection**: a `<column>` in a `*.data.changelog.xml` that is not mapped in
  the backing `database` `.service` model (matched loosely, `CREW_ID_` ≈ `crewId`) is flagged as
  "not defined in the model" — resolved via `serviceDefinitionReferences` / `referencedLiquibaseModelKey`
  / `tableName`. Ported from `flowable_atlas.py` (ops replay honours rename/drop within a file).
- ✅ **Liquibase column completion**: inside a changelog, `<column name="…">` (in `<insert>` /
  `<createTable>` / `<update>` / `<addColumn>`) and `tableName="…"` complete with the physical columns /
  table of the backing `database` `.service` model (`LiquibaseColumnCompletionContributor`).
- ✅ **Settings** (Settings → Tools → Flowable Atlas): toggle the extra completions, opt into
  indexing the Flowable Design `*-models/` workspace JSON, choose the Atlas **Generate** scope
  (explorer only vs. all artifacts), and set the Python 3 interpreter used for the Atlas explorer
  (empty = auto-detect).
- ✅ Tests pass against IntelliJ IDEA 2026.1 (**67** total): `FlowableCompletionTest` (16) +
  `FlowableFeaturesTest` (13) + `FlowableInfixAndXmlTest` (8, infix + scoped vocab + XML cross-refs) +
  `LiquibaseChangelogTest` (8) + `JsonUtilTest` (6) + `DataObjectBeanGeneratorTest` (4) +
  `ModelExtractionTest` / `ModelConstantsGeneratorTest` / `ModelUsageScannerTest` /
  `FlowableImplicitUsageTest` (3 each).

Robustness: the full project scan (`FlowableModelIndexService.build`, `FlowableModelUsageSearcher`)
now honours `ProgressManager.checkCanceled()`, so a long scan can be interrupted (e.g. during
completion) instead of blocking.

Possible follow-ups: cross-file Liquibase include replay (v1→v2 directories), an event-payload
completion call-site (no stable Java builder today), migrating the index storage to `FileBasedIndex`
(incremental / persisted, so a model save no longer drops the whole cache), and gutter icons + rename
refactoring for model keys.

## Requirements / compatibility

- Target IDE: **IntelliJ IDEA 2026.1+** (`since-build 261`, open until-build). Needs the Java
  plugin (bundled in Community and Ultimate).
- Runs on **JDK/JBR 21+** (compiled to Java 21 bytecode).

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
./gradlew buildPlugin        # → build/distributions/flowable-atlas-0.3.0.zip (installable)
./gradlew test               # functional + unit tests
./gradlew runIde             # sandbox IDE with the plugin; open any Flowable project
```

## Install (from disk)

1. `./gradlew buildPlugin` → `build/distributions/flowable-atlas-0.3.0.zip`.
2. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip.
3. Restart. Open a Flowable project, then either generate the Atlas explorer
   (**Tools → Flowable Atlas → Generate Atlas Explorer**) or start typing a key argument at any of
   the API call sites above.

Verify indexing via **Tools → Flowable Atlas → Dump Key Index**.
