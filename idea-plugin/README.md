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
./gradlew :idea-plugin:runIde        # sandbox IDE on the downloaded 2026.1 SDK
./gradlew :idea-plugin:verifyPlugin  # the compatibility gate — before every release
```

`../CONTRIBUTING.md` has the build, verification and release workflow;
[the development page](https://kle-dev.github.io/flw-atlas/develop/) has the same ground for readers
outside the team. `../AGENTS.md` is the rule list every change has to satisfy.

## Why the toolchain is pinned the way it is

The build compiles against a downloaded **IntelliJ IDEA 2026.1** SDK rather than a locally installed
IDE, so it builds on any machine and in CI. 2026.1 is deliberately the *oldest* supported platform:
compiling against the floor is what keeps a single artifact loadable on every later version.

- **IntelliJ Platform Gradle Plugin 2.17.0** — 2.5.x fails `runIde` against 2026.1's
  `MultiRoutingFileSystemProvider` bootstrap. It needs **Gradle 9+**; the wrapper is pinned to 9.4.0.
- **Kotlin Gradle plugin ≥ the Kotlin the IDE ships** (2026.1 ships Kotlin 2.3.x → 2.3.21), otherwise
  the compiler cannot read the platform's Kotlin metadata.
- **JDK 21** to compile and run (auto-detected; override with
  `org.gradle.java.installations.paths` in your *local* `~/.gradle/gradle.properties`).

**JCEF** — used by the Atlas Hub, the explorer editor tab and the Inspect sign-in browser — sits in the
platform core up to 2026.1 and in the bundled *Web Browser (JCEF)* plugin from 2026.2 on. The
descriptor declares that plugin as an **optional** dependency so one ZIP resolves it on 2026.2 and
ignores it on 2026.1, and every call site is additionally guarded by `JBCefApp.isSupported()`.
