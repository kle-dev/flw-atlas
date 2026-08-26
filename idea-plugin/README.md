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
panels, not the plugin. Every call site is additionally guarded by `JBCefApp.isSupported()`.
