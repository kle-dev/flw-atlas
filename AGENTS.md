# AGENTS.md — working in this repository

Instructions for AI agents (and humans) making changes to **Flowable Atlas itself**.

> **Not to be confused with `CLAUDE.template.md`.** That file is a *product output*: the primer Atlas
> writes into other people's Flowable solution projects. It says nothing about how to work on this
> repository, and it is generated — see [Generated files](#generated-files--never-hand-edit).
> `CONTRIBUTING.md` holds the full build/release workflow; this file is the short list of rules that
> must hold for every change.

## The repository in one screen

A Gradle multi-module JVM project, JDK 21, **no third-party runtime dependencies**:

| Module | What it is |
|---|---|
| `:core` | The engine — discovery, parsing, the graph, expression/script validation, diagrams, every renderer. Pure Kotlin. |
| `:cli` | The standalone fat-jar that `./atlas` runs. |
| `:idea-plugin` | The IntelliJ plugin. Consumes `:core` **in-process**, so the IDE and the CLI can never disagree. |

The explorer frontend is `core/src/main/resources/frontend/explorer.{html,css,js}` — plain files
inlined at render time. The docs site sources are in `site/`.

## The gate

```bash
./gradlew build                     # the gate: must be green before anything is committed
ATLAS_REQUIRE_BROWSER_TESTS=1 ./gradlew build   # …and set this when you touch the explorer frontend
./gradlew :idea-plugin:verifyPlugin # before every release; the only check that sees descriptor defects
node scripts/site-build.mjs         # when you touch site/ — see below (or ./gradlew site)
```

There is no "known failing test" list, and there should never be one. If a golden test fails, read the
diff before reaching for `updateGoldens` — that command accepts a change you already understand, it is
never a way to make a red test green.

## Documentation follows the change — always

**A change is not done until every document that describes the changed behaviour has been updated in
the same commit.** This is the rule that this file exists for. Docs here are not decoration: they are
what the plugin's marketplace description, the docs site, the release notes and the agent primers are
built from, and a stale claim in any of them is a defect like any other.

Three questions, every time:

1. **What did I change that a reader can observe?** A flag, an action, an inspection, an artifact
   field, a check, a route, a default, a requirement, a version.
2. **Which documents state something about it today?** `grep` for it. Do not assume; the same feature
   is usually described in four places at different levels of detail.
3. **Is what they now say still true?** Including the small print — version numbers, counts, file
   names, task names, "verified on …".

### Where things are documented

| If you change… | Update… |
|---|---|
| Anything user-visible, at all | `CHANGELOG.md` — the source of truth for the release history, hand-authored, one entry per user-visible change |
| A plugin feature (action, inspection, completion, hint, line marker, tool window, setting) | `FEATURES.md`, `idea-plugin/README.md`, `site/pages/plugin.md` + `site/pages/plugin-reference.md`; the `<description>` in `plugin.xml` if it is a headline feature |
| A CLI flag, or what an artifact contains | `README.md` (the artifact list and *Advanced — single artifacts*), `site/pages/cli.md`, `site/pages/artifacts.md` |
| A health check or finding | `site/pages/checks.md`, the findings paragraph in `README.md`, `FEATURES.md` |
| Variable read/write analysis or its silence rules | `site/pages/variables.md`, `FEATURES.md` |
| Expression/script validation, the function catalog, script bindings | `site/pages/expressions.md`, `FEATURES.md` |
| The explorer frontend — a route, page, facet or visible layout | `site/pages/explorer.md`; regenerate screenshots (`node scripts/site-shots.mjs`) or adjust the `site/mockups/*.html` if the change is visual |
| What agents receive (the `CLAUDE.md` renderer, `--summary`, `--slice`, `--json`) | `site/pages/agents.md`, the *For LLMs / agents* section of `README.md`; `CLAUDE.template.md` regenerates itself |
| Build, test, CI or release workflow | `CONTRIBUTING.md`, `site/pages/develop.md`, the *Development* section of `README.md`, and `.github/workflows/build.yml` if the commands moved |
| Supported IDE range, or what was actually verified | `AtlasPlatformSupport` (the verified range the Atlas Hub shows), `plugin.xml`, `README.md`, `CONTRIBUTING.md`, `site/pages/develop.md` |
| The version | `version` in the root `build.gradle.kts`, the `CHANGELOG.md` entry, the version line at the top of `FEATURES.md`, then `./gradlew :core:updateGoldens` |
| A newly bundled third-party component | `THIRD-PARTY-NOTICES.md` and the *License* section of `README.md` |
| A new docs page | `site/nav.json` (page order — it drives the sidebar, the pager and the search index) |

Three of these obligations are enforced, and `./gradlew build` fails when you skip them:
`SiteDocsCoverageTest` (every check id, silence rule, CLI flag, explorer route, inspection and action
appears on its page), `SiteDemoProjectTest` (the sample project still demonstrates every check) and
`DocsVersionSyncTest` (`FEATURES.md` states this build's version). Nothing to re-baseline — the fix is
to write the sentence.

Everything else is on you. When you add a page or move a claim, also run `node scripts/site-build.mjs`
(or `./gradlew site`): it fails on Markdown outside its supported subset, on an internal link or image
that does not resolve, and on a hardcoded version string (write `{{VERSION}}`).

**What does *not* need a doc update:** a refactor, a rename or a test that changes nothing a reader can
observe. Do not pad `CHANGELOG.md` with those — a release-notes list that mixes internals into user
changes is harder to read than a shorter one.

### Generated files — never hand-edit

Editing these works right up until the next regeneration silently reverts it. Sync tests fail when they
drift, and the relevant repo files are declared as test inputs so the gate actually gates.

| File | Generated from | Regenerate with |
|---|---|---|
| `core/src/test/resources/golden/*` | the current extractor/renderer output | `./gradlew :core:updateGoldens` |
| `CLAUDE.template.md` | `ClaudeRenderer.renderGeneric` | `./gradlew :core:updateGoldens` |
| `<change-notes>` in `plugin.xml` | `CHANGELOG.md` (a size-budgeted window onto its newest entries — the field is capped at 65535 chars) | `./gradlew :core:updateGoldens` |
| the Geist `@font-face` block in `explorer.css` | the platform's font files | `node scripts/embed-geist.mjs` |
| `<version>` in `plugin.xml` | the Gradle project version | `patchPluginXml`, at build time |
| the docs site, the demo artifacts, `updatePlugins.xml`, release ZIPs/jars | the sources + CI | never committed |

So: write release notes in `CHANGELOG.md` and run `updateGoldens` — never in the descriptor.

## House rules

- **No customer identifiers.** This repository is **public**. Model keys, namespaces, table names, app
  names and package names in code, tests, docs, mockups *and commit messages* use `DEMO-*` /
  `com.example.*` placeholders. Never a real one.
- **Comments explain *why*.** A comment restating what the line does is noise; one recording why it was
  done that way — including the alternative that was rejected — is the point. Match the surrounding
  density.
- **A cancelled action is not a failure.** Anything catching broadly rethrows `ProcessCanceledException`
  first, or a user pressing Cancel gets reported as an error.
- **Claims stay honest.** `untilBuild` is deliberately wide so the plugin keeps loading on newer IDEs;
  the range that was actually *verified* lives in `AtlasPlatformSupport`. Never let a document present
  "it loads" as "it was tested" — in either direction.
- **Mirror what is there.** Match the existing naming, structure and prose voice rather than
  introducing a second style beside it.
- **One source of truth per fact.** If a value must appear twice, generate the second copy or add a sync
  test — that is why `CHANGELOG.md`, `CLAUDE.template.md` and the Design vocabulary have one.
- Commit messages: `area: lowercase summary` (`core:`, `plugin:`, `build:`, `ci:`, `fix:`, `release:`),
  imperative, no trailing period.

## Releasing

1. Write the `CHANGELOG.md` entry, bump `version` in the root `build.gradle.kts`, run
   `./gradlew :core:updateGoldens`.
2. `./gradlew build` and `./gradlew :idea-plugin:verifyPlugin`, both green.
3. Push a `v<version>` tag. CI builds, signs, verifies and publishes the release; it fails if the tag
   and the built version disagree.

Details — signing, secrets, `updatePlugins.xml` — are in `CONTRIBUTING.md`.

## Definition of done

- [ ] `./gradlew build` green (with `ATLAS_REQUIRE_BROWSER_TESTS=1` if the frontend was touched).
- [ ] A test covers the change, or it is stated why one cannot.
- [ ] Every document listed above that describes the changed behaviour is updated **in the same commit**.
- [ ] `node scripts/site-build.mjs` clean, if `site/` was touched.
- [ ] No generated file hand-edited; `./gradlew :core:updateGoldens` diff read, not just accepted.
- [ ] No real customer identifier anywhere in the change, commit message included.
