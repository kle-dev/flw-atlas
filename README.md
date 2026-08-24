# Flowable Atlas

**📖 Documentation: [kle-dev.github.io/flw-atlas](https://kle-dev.github.io/flw-atlas/)**

Map **any** Flowable project — the app models **and** the Java code — into something you can read,
click through and query. An IntelliJ IDEA plugin and a command-line tool, sharing one engine.

```bash
./atlas /path/to/your-flowable-project
```

That writes five artifacts to `./atlas-output/<project>/` and opens the explorer in your browser:

| Artifact | What it is |
|---|---|
| `<project>.explorer.html` | A self-contained, offline, interactive explorer. Open by double-click; click through every relationship in both directions. |
| `<project>.summary.md` | A compact (~few KB) LLM-first overview: apps, inventory, entry points, integrations, hotspots, health. |
| `<project>.overview.md` | The full human report — every model in execution order, the access map, the data layer, every finding with `file:line`. |
| `<project>.graph.json` | The traversable model↔code graph, for agents to **query** rather than read: every node carries `usedBy`, and a `_schema` key documents the shape and ships `jq` recipes. |
| `<project>.CLAUDE.md` | Drop-in context for AI agents — a Flowable primer plus this project's discovered facts. Copy it to your repo root as `CLAUDE.md`. |

It resolves the relationships a Flowable repository does not state: app → models, process → the case,
decision or form it calls, `${bean.method()}` → the **Java class and method** with `file:line`, form →
REST endpoint → controller, action → bot, data object → service → Liquibase table, and who can start
what. Whatever cannot be resolved is listed separately, so a real gap stands out.

## Getting it

Both artifacts are attached to every
[**release**](https://github.com/kle-dev/flw-atlas/releases/latest) — they are not committed here.

- **IntelliJ plugin, the easy way** — add Atlas as a plugin repository once and it joins the IDE's
  normal update flow. *Settings → Plugins → ⚙ → Manage Plugin Repositories…*, add
  `https://github.com/kle-dev/flw-atlas/releases/latest/download/updatePlugins.xml`, then install
  **Flowable Atlas** from the *Marketplace* tab. The URL always resolves to the newest release.
- **IntelliJ plugin, by hand** — download `flowable-atlas-<version>.zip`, then *Settings → Plugins →
  ⚙ → Install Plugin from Disk…* and restart. Installs on IDEA 2026.1+, verified on 2026.2. See
  [`idea-plugin/dist/README.md`](idea-plugin/dist/README.md).
- **CLI** — download `cli-<version>-all.jar` into `lib/` and run `./atlas <project>`, or
  `java -jar cli-<version>-all.jar <project> --all`. Needs only a JRE 21+. See
  [`lib/README.md`](lib/README.md).

`SHA256SUMS.txt` in each release carries the checksums; releases are signed when a signing key is
configured. Building from source instead: `./gradlew :idea-plugin:buildPlugin :cli:shadowJar`.

## Documentation

Everything is on the [documentation site](https://kle-dev.github.io/flw-atlas/), including a live
explorer you can click through:

| | |
|---|---|
| [Getting started](https://kle-dev.github.io/flw-atlas/start/) | Install, first run, what you get |
| [What the plugin does](https://kle-dev.github.io/flw-atlas/plugin/) | The IntelliJ side, feature by feature, illustrated |
| [Plugin reference](https://kle-dev.github.io/flw-atlas/plugin/reference/) | Every action, inspection, setting and file type |
| [The Atlas explorer](https://kle-dev.github.io/flw-atlas/explorer/) | Every view, the search grammar, the keyboard map |
| [Generated artifacts](https://kle-dev.github.io/flw-atlas/artifacts/) | What each file is for, and `jq` recipes for the graph |
| [CLI reference](https://kle-dev.github.io/flw-atlas/cli/) | Every flag, the launcher, exit codes |
| [Health checks](https://kle-dev.github.io/flw-atlas/checks/) | All thirteen, and what each one detects |
| [For LLMs & agents](https://kle-dev.github.io/flw-atlas/agents/) | Four sizes of context, and which to use when |
| [Development](https://kle-dev.github.io/flw-atlas/develop/) | Build, tests, goldens, the compatibility gate, CI |

## Development

The short version — the [development page](https://kle-dev.github.io/flw-atlas/develop/) has the rest.

A Gradle multi-module JVM project: `:core` (the pure-Kotlin engine), `:cli` (the fat-jar `./atlas`
runs) and `:idea-plugin` (which consumes `:core` in-process). Needs a **JDK 21+** to build.

```bash
./gradlew build                      # goldens, unit tests, CLI contracts, browser tests, the plugin ZIP
./gradlew :core:updateGoldens        # re-baseline the goldens and every generated file, then review the diff
./gradlew :idea-plugin:verifyPlugin  # the compatibility gate — run it before every release
./gradlew site                       # build the documentation site into build/site
```

- The explorer frontend is `core/src/main/resources/frontend/explorer.{html,css,js}` — plain files
  read at render time and inlined into the generated page.
- `CHANGELOG.md` is the source of truth for the release history; the plugin descriptor's
  `<change-notes>` is generated from it by `:core:updateGoldens`.
- The documentation site lives in `site/`. Nothing it generates is committed — the screenshots and the
  live demo are built on every deploy by `.github/workflows/pages.yml`.

## Requirements

A **JRE 21+** to run `./atlas` (a **JDK 21+** to build from source). No third-party packages.

## Status

**Pre-release (`0.x`).** Atlas is used internally and carries no cross-version stability guarantee
yet: behaviour, settings and generated-artifact formats may change between minor releases. Versions
below 0.13.0 were never published outside the team. See [CHANGELOG.md](CHANGELOG.md).

## License

Copyright (c) 2026 Flowable AG. All rights reserved.

Proprietary — source available, **no license granted**. The source is published for visibility and for
use by Flowable AG and its authorized users; publication grants no right to use, copy, modify or
redistribute it. See [LICENSE](LICENSE) for the full terms and for licensing enquiries.
