# Flowable Keys — IntelliJ IDEA Plugin

Context-aware auto-complete for **Flowable model keys** at Flowable **public-API call sites in
Java** — searchable by **key or name**:

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
- ✅ Search candidates by **key or by name** (name words are matched too).
- ✅ **Generate a model-constants class** — Tools → "Flowable: Generate Model Constants" writes a
  Java class holding every model key as a `public static final String`, grouped into a nested class
  per model type (e.g. `FlowableModelKeys.Process.DEMO_P001`, with the model name in Javadoc). The
  class is then **kept in sync automatically** (debounced) whenever a model is added / removed /
  renamed / edited (`ModelConstantsAutoRefresher`; opt-out by deleting the class — refresh only
  updates a class that still exists). Target FQCN is remembered per project (`ModelConstantsSettings`).
- ✅ **More completion domains** (toggle in settings): BPMN **message** / **signal** names
  (`startProcessInstanceByMessage`, `signalEventReceived`), process/case **variable** names
  (`get/setVariable(id, "<caret>")` — arg 1), **task-definition keys** (`taskDefinitionKey`) and
  **activity ids** (`activityId`), plus **DMN decision variables** (`ExecuteDecisionBuilder.variable`,
  resolved from the sibling `decisionKey`).
- ✅ **Broken-key inspection**: a model key that matches no indexed key of the expected type is
  flagged (only when that type has indexed keys), with a **quick fix** to the closest known key.
- ✅ **Navigation**: Ctrl-click / Go-To-Declaration on a key literal jumps to the model file
  (`FlowableKeyReferenceContributor`); Find Usages works; hover / Ctrl-Q shows type, name and file.
- ✅ **Liquibase coverage inspection**: a `<column>` in a `*.data.changelog.xml` that is not mapped in
  the backing `database` `.service` model (matched loosely, `CREW_ID_` ≈ `crewId`) is flagged as
  "not defined in the model" — resolved via `serviceDefinitionReferences` / `referencedLiquibaseModelKey`
  / `tableName`. Ported from `flowable_atlas.py` (ops replay honours rename/drop within a file).
- ✅ **Liquibase column completion**: inside a changelog, `<column name="…">` (in `<insert>` /
  `<createTable>` / `<update>` / `<addColumn>`) and `tableName="…"` complete with the physical columns /
  table of the backing `database` `.service` model (`LiquibaseColumnCompletionContributor`).
- ✅ **Settings** (Settings → Tools → Flowable Keys): toggle the extra completions and opt into
  indexing the Flowable Design `*-models/` workspace JSON.
- ✅ Tests pass against IntelliJ IDEA 2026.1 (**51** total): `FlowableCompletionTest` (16) +
  `FlowableFeaturesTest` (10, new features) + `LiquibaseChangelogTest` (7) + `JsonUtilTest` (6) +
  `ModelExtractionTest` (3) + generation/usage tests.

Possible follow-ups: cross-file Liquibase include replay (v1→v2 directories), an event-payload
completion call-site (no stable Java builder today), and migrating the index storage to
`FileBasedIndex`.

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
./gradlew buildPlugin        # → build/distributions/flowable-keys-0.1.0.zip (installable)
./gradlew test               # 6 functional completion tests
./gradlew runIde             # sandbox IDE with the plugin; open any Flowable project
```

## Install (from disk)

1. `./gradlew buildPlugin` → `build/distributions/flowable-keys-0.1.0.zip`.
2. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip.
3. Restart. Open a Flowable project and type a key argument at any of the API call sites above.

Verify indexing via **Tools → "Flowable: Dump Key Index"**.
