# `:idea-plugin` — the IntelliJ IDEA plugin

*(formerly “Flowable Keys”)*

The IDE half of Flowable Atlas: model-key completion and validation, expression-language support, and
the Atlas Hub / explorer, all resolved against the Flowable models that actually live in the open
project. It consumes `:core` **in-process** — the same pure-Kotlin engine the `atlas` CLI runs — so the
IDE and the CLI can never disagree about a project, and generation needs no interpreter or subprocess.

**What it does, feature by feature:** [What the plugin does](https://kle-dev.github.io/flw-atlas/plugin/).
**Every action, inspection, setting and file type:** [Plugin reference](https://kle-dev.github.io/flw-atlas/plugin/reference/).
**Installing a release:** [Getting started](https://kle-dev.github.io/flw-atlas/start/).

## Working in this module

```bash
./gradlew :idea-plugin:buildPlugin   # -> build/distributions/flowable-atlas-<version>.zip
./gradlew :idea-plugin:runIde        # sandbox IDE on the downloaded 2026.2 SDK
./gradlew :idea-plugin:runIdeLocal   # sandbox on your installed IDE — no download, real classloader
./gradlew :idea-plugin:verifyPlugin  # the compatibility gate — before every release
```

`../CONTRIBUTING.md` has the build, verification and release workflow;
[the development page](https://kle-dev.github.io/flw-atlas/develop/) has the same ground for readers
outside the team. `../AGENTS.md` is the rule list every change has to satisfy.

## Why the toolchain is pinned the way it is

The build compiles against a downloaded **IntelliJ IDEA 2026.2** SDK rather than a locally installed
IDE, so it builds on any machine and in CI. 2026.2 is also the floor (`since-build 262`): compile
target, sandbox and Plugin Verifier all sit on the same branch, so what compiles is what runs. The
build used to compile one branch lower (2026.1) to keep a single artifact loadable there as well —
that constraint was dropped once the team moved to 2026.2, and an older IDE now declines the plugin
instead of running an unverified one.

- **IntelliJ Platform Gradle Plugin 2.18.1** — 2.5.x fails `runIde` against 2026.x's
  `MultiRoutingFileSystemProvider` bootstrap, and 2.17.0 assembles a **2026.2 test IDE that cannot load
  the bundled Java and JSON plugins** (they ask for `intellij.platform.structureView`, which it never
  installs), which takes every Atlas extension down with them: ~140 tests fail on empty completion lists
  and "Unregistered inspections requested" instead of on anything real. It needs **Gradle 9+**; the
  wrapper is pinned to 9.4.0.
- **Kotlin Gradle plugin ≥ the Kotlin the IDE is built with** (2.3.21 covers 2026.2), otherwise the
  compiler cannot read the platform's Kotlin metadata.
- **JDK 21** to compile and run (auto-detected; override with
  `org.gradle.java.installations.paths` in your *local* `~/.gradle/gradle.properties`).

**JCEF** — used by the Atlas Hub, the explorer editor tab and the Inspect sign-in browser — sat in the
platform core up to 2026.1 and lives in the bundled *Web Browser (JCEF)* plugin from 2026.2 on. That
plugin is therefore on the **compile** classpath (`bundledPlugin("com.intellij.modules.jcef")`), while
the descriptor keeps it as an **optional** runtime dependency: disabling it should cost the browser
panels, not the plugin. The explorer editor tab is registered from the optional descriptor
(`flowable-atlas-jcef.xml`), so the platform never asks it about a file when the browser plugin is off,
and every other call site goes through `JcefSupport.isAvailable()`, which catches the missing class link
— a direct `JBCefApp.isSupported()` in a class the main descriptor names fails to *link*, not to answer.

## Why the schemas are vendored, and what was changed in one of them

`src/main/resources/schemas/{bpmn,cmmn,dmn}/` holds the XML schemas the plugin registers for model
files. They are copies, taken by hand from the open-source engine at
`/modules/flowable-{bpmn,cmmn,dmn-xml}-converter/src/main/resources/org/flowable/impl/*/parser/`,
revision **`flowable-8.0.0-76-ga50dd0c581`**. A build-time pull is not possible: the engine is not a
dependency of this project, and adding one to obtain nineteen static files would be the larger cost.

Three directories rather than one, because `DC.xsd` and `DI.xsd` exist three times with different target
namespaces, and a relative `<xsd:include>` resolves against the directory of the file naming it.

### The one modified file

`bpmn/flowable-bpmn-extensions.xsd` carries Atlas changes, marked in place with
`MODIFIED BY FLOWABLE ATLAS` and stated in its header, as Apache-2.0 §4(b) requires. Everything else is
byte-identical to upstream.

| Change | Why |
|---|---|
| a service task's `type`: enumeration → `string` | The open-source list has eight values. A real corpus uses `service-registry` (260×), `agent` (86×), `init-variables` (50×), `audit` (47×), `data-object` (31×) and three document types besides — 488 tasks across **140 of 989** process files, none of them invalid. |
| an execution listener's and a task listener's `event`: enumeration → `string` | Same class of defect. Flowable accepts listener events the open-source list does not carry, and a red error on a model the engine runs is worse than a missing one. |
| a `<script>` element, added, and allowed inside both listeners | A listener can carry a script instead of a class; the engine both parses it (`FlowableListenerParser`) and writes it (`FlowableListenerExport`). The schema never declared the element, so such a listener validated as unexpected content. |

The measurement behind the first row, against a real checkout: **841 of 989** BPMN files clean with the
schema as published, **981 of 989** with it widened. The difference is the 140.

### Refreshing them from a newer engine

1. Copy the files again from the engine checkout, into the same three directories.
2. Re-apply the changes above and their `MODIFIED BY FLOWABLE ATLAS` markers — `SchemaResourcesTest`
   fails if the marked file stops being the only marked one, but nothing can tell you a *missing* change.
3. `./gradlew :idea-plugin:test --tests "com.flowable.atlas.schema.*"` — the shipped models must still
   validate and a realistic Design export must still highlight clean.
4. Run the corpus measurement (see `site/pages/develop.md`) and compare the percentage with the one
   above. A drop is a schema that grew stricter than the platform it describes.
5. Update the revision named at the top of this section.
