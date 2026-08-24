# Contributing

## Read this first

This repository is **source-available, not open source**. [LICENSE](LICENSE) grants no rights to use,
build, modify or redistribute the code, which means **pull requests from outside Flowable AG cannot
be accepted** — there is no licence under which we could take the contribution, and none under which
you could have produced it.

That is not a brush-off, and it does not make the repository read-only in spirit:

- **Bug reports are welcome.** Open an issue. The plugin's error dialog has a
  *"Report Flowable Atlas Problem…"* button that assembles the environment block for you.
  For anything security-related, use [SECURITY.md](SECURITY.md) instead — not an issue.
- **Feature ideas are welcome** as issues.
- **Questions about how something works are welcome.** The code is public so it can be read.

The rest of this file documents the internal workflow, for Flowable engineers and for anyone reading
along who wants to know how the project holds itself together.

## Build and test

Requires **JDK 21**. Node and Chrome are needed for the three browser-driven tests.

```bash
./gradlew build          # compiles, runs the JVM suite + goldens + CLI contracts + the browser tests,
                         # and produces idea-plugin/build/distributions/flowable-atlas-<version>.zip
```

`build` is the gate. It must be green before anything is committed — there is no "known failing test"
list, and there should never be one.

The three browser tests (`searchSelfTest`, `explorerUiTest`, `diagramUiTest`) **skip themselves** when
node or Chrome is missing, so `build` stays green without them locally. CI sets
`ATLAS_REQUIRE_BROWSER_TESTS=1`, which turns that skip into a failure — a green pipeline can never
mean "the frontend was never opened". Set it locally too when you touch the explorer frontend.

## Before a release

```bash
./gradlew :idea-plugin:verifyPlugin
```

JetBrains' Plugin Verifier, against a downloaded IntelliJ IDEA 2026.2. It is the **only** check that
sees plugin-descriptor defects — an invalid structure, or `<change-notes>` over its 65535-character
cap — which `build` does not. Run it every time. Add
`-Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"` to verify against an IDE you already have
instead of downloading one.

To smoke-test inside a real IDE against another platform version:

```bash
./gradlew :idea-plugin:runIdeLocal -Patlas.runIdePath="/Applications/IntelliJ IDEA.app"
```

## Cutting a release

1. Write the entry in `CHANGELOG.md`, bump `version` in the root `build.gradle.kts` to match, and run
   `./gradlew :core:updateGoldens` so the plugin descriptor's `<change-notes>` follows.
2. `./gradlew build` and `./gradlew :idea-plugin:verifyPlugin`, both green.
3. Push a `v<version>` tag. CI builds from the tag, signs, verifies the signature, and publishes the
   ZIP, the CLI jar, `SHA256SUMS.txt` and `updatePlugins.xml` to a GitHub release. The job fails if the
   tag and the built version disagree, so a mismatched tag cannot produce a release.

`updatePlugins.xml` is what makes Atlas updatable without a Marketplace listing. It is generated from
the **patched** descriptor, never hand-written: a compatibility range that drifted from the plugin it
advertises is the one defect that makes an update channel worse than none, because the IDE would then
offer an update it cannot install.

### Signing

Release artifacts are signed when a key is configured, and the job publishes an unsigned ZIP with a
loud warning when one is not. Atlas is side-loaded, so nothing else distinguishes our build from a
substituted one — `SHA256SUMS.txt` cannot, since whoever can replace the asset can replace its checksum
line in the same breath.

The key lives outside the repository (`~/.flowable-atlas/signing/`, mode 600) and its passphrase in the
macOS Keychain. A local signed build:

```bash
export ATLAS_PRIVATE_KEY_PASSWORD="$(security find-generic-password -s flowable-atlas-signing -w)"
./gradlew :idea-plugin:signPlugin :idea-plugin:verifyPluginSignature \
  -Patlas.signing.certificateChainFile="$HOME/.flowable-atlas/signing/chain.crt" \
  -Patlas.signing.privateKeyFile="$HOME/.flowable-atlas/signing/private.pem"
```

Pass the passphrase through the environment, never as `-Patlas.signing.password`: Gradle echoes its own
command line at `--info`, so the property form puts it in the build log and in shell history.

For CI the three values are repository secrets — `ATLAS_CERTIFICATE_CHAIN` and `ATLAS_PRIVATE_KEY` hold
the PEM **contents** (there is no file to point at on a fresh runner), `ATLAS_PRIVATE_KEY_PASSWORD` the
passphrase:

```bash
gh secret set ATLAS_CERTIFICATE_CHAIN     < ~/.flowable-atlas/signing/chain.crt
gh secret set ATLAS_PRIVATE_KEY           < ~/.flowable-atlas/signing/private.pem
security find-generic-password -s flowable-atlas-signing -w | gh secret set ATLAS_PRIVATE_KEY_PASSWORD
```

Losing the key is not fatal — generate a new one and keep releasing. It only means a colleague's IDE
sees a different signer than before.

## Generated files — do not hand-edit

Several files in this repository are outputs, not sources. Editing them by hand works right up until
the next regeneration silently reverts it.

| File | Generated from | Regenerate with |
|---|---|---|
| `core/src/test/resources/golden/*` | the current extractor/renderer output | `./gradlew :core:updateGoldens` |
| `<change-notes>` in `plugin.xml` | `CHANGELOG.md` (the source of truth for release history) | `./gradlew :core:updateGoldens` |
| the Geist `@font-face` block in `explorer.css` | the platform's font files | `node scripts/embed-geist.mjs` |
| `<version>` in `plugin.xml` | the Gradle project version | `patchPluginXml`, at build time |

`updateGoldens` **rewrites** the baselines from whatever the code currently produces. That makes it a
tool for accepting a change you already understand, never a way to make a red test go green. Always
read the resulting diff: goldens re-sort when a new node key appears, so a large diff for a small
change is normal, but a *semantic* change buried in one is exactly what the goldens exist to catch.

## Conventions that are not obvious from the code

- **`CHANGELOG.md` is the source of truth**, not the plugin descriptor. Write the entry there, then
  run `updateGoldens` to push a size-budgeted window of the newest entries into `<change-notes>`.
- **No customer identifiers.** This repository is public. Model keys, namespaces, table names and app
  names in code, tests, docs and commit messages use `DEMO-*` placeholders. Never a real one.
- **Comments explain *why*.** The codebase is written so that a decision that looks odd carries the
  reason it was made — including the alternatives that were rejected. Match that when you add code:
  a comment restating what the line does is noise, one recording why it does it that way is the
  point.
- **A cancelled action is not a failure.** Anything catching broadly rethrows
  `ProcessCanceledException` first, or a user pressing Cancel is reported as an error.
- **The compatibility claim stays honest.** `untilBuild` is deliberately wide so the plugin keeps
  loading on newer IDEs; the range that was actually *verified* lives in `AtlasPlatformSupport` and is
  what the Atlas Hub shows. Bump that constant when the verifier target changes — never present
  "it loads" as "it was tested".
