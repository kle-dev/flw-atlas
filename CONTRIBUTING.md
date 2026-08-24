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

The rest of this file is the internal workflow, for Flowable engineers.

## Where the rest is written down

This file deliberately carries only what is not documented elsewhere. Two documents cover the ground
it used to repeat, and they are the ones to change when the workflow changes:

- **[`AGENTS.md`](AGENTS.md)** — the rules every change has to satisfy: the gate, which document a
  given change obliges you to update, the generated files that must never be hand-edited, the house
  rules, and the definition of done. Read it before your first change.
- **[The development page](https://kle-dev.github.io/flw-atlas/develop/)** (`site/pages/develop.md`) —
  the module layout, `./gradlew build` and what it runs, the goldens and how to read their diffs, the
  browser tests, the compatibility gate, the site build, and CI. The long-form version, public.

## The short version

Requires **JDK 21**; Node and Chrome for the three browser-driven tests.

```bash
./gradlew build                                  # the gate — green before anything is committed
ATLAS_REQUIRE_BROWSER_TESTS=1 ./gradlew build    # …and this when you touch the explorer frontend
./gradlew :idea-plugin:verifyPlugin              # before every release
```

`build` is the gate — `AGENTS.md` states the rule it enforces. The browser tests skip themselves when
node or Chrome is missing, so `build` stays green locally without them;
`ATLAS_REQUIRE_BROWSER_TESTS=1` turns that skip into a failure, and CI sets it — a green pipeline can
never mean "the frontend was never opened".

`verifyPlugin` is the **only** check that sees plugin-descriptor defects — an invalid structure, or
`<change-notes>` over its 65535-character cap — which `build` does not. Add
`-Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"` to verify against an IDE you already have
instead of downloading one, and `./gradlew :idea-plugin:runIdeLocal -Patlas.runIdePath=…` to smoke-test
in a real installation, which is the only check that exercises the real plugin classloader.

Cutting a release: write the `CHANGELOG.md` entry, bump `version` in the root `build.gradle.kts`, run
`./gradlew :core:updateGoldens`, get both gates green, then push a `v<version>` tag. CI builds from the
tag, signs, verifies, and publishes the ZIP, the CLI jar, `SHA256SUMS.txt` and `updatePlugins.xml` to a
GitHub release. The job fails if the tag and the built version disagree, so a mismatched tag cannot
produce a release.

`updatePlugins.xml` is what makes Atlas updatable without a Marketplace listing. It is generated from
the **patched** descriptor, never hand-written: a compatibility range that drifted from the plugin it
advertises is the one defect that makes an update channel worse than none, because the IDE would then
offer an update it cannot install.

## Signing

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
