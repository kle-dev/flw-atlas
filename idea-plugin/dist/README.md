# Flowable Atlas — installing the plugin

The plugin ZIP is **not committed to this repository**. Download it from the
[**Releases**](https://github.com/kle-dev/flw-atlas/releases/latest) page — each release has
`flowable-atlas-<version>.zip` attached, built by CI from that release's tag.

## Install (on any machine)

1. Download `flowable-atlas-<version>.zip` from the latest release.
2. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the downloaded ZIP.
4. **Restart** the IDE.

Installs on **2026.1+**; verified on **2026.2** (JetBrains' Plugin Verifier runs on every push). The
plugin bundles the Atlas generator — the pure-Kotlin `:core` engine, run in-process — so **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime, no external interpreter.

Each release also lists the ZIP's SHA-256, generated at build time. Compare it if you want to be sure the
download is intact:

```bash
shasum -a 256 flowable-atlas-<version>.zip
```

> This folder is a convenient place to keep the downloaded ZIP — it is gitignored, so anything you put
> here stays local.

## Why it is not in the repository

It used to be, so a `git pull` was enough to get it. But a binary does not delta-compress: every refresh
stored a whole new copy, and 87 of them had reached 152 MB — 86% of the repository — while only the newest
one is ever useful. The justification did not hold either. It read *"a machine that can reach this git
remote but not Maven Central"* — and the git remote and the Releases page are the **same host**, so
anything that can `git pull` can equally download a release asset.

## Publishing a release

Push a tag and CI does the rest — it builds the plugin ZIP and the CLI jar, computes their checksums and
creates the release with both attached:

```bash
git tag v0.13.0 && git push origin v0.13.0
```

See `.github/workflows/build.yml`. To build the ZIP locally without releasing:

```bash
./gradlew :idea-plugin:buildPlugin     # -> idea-plugin/build/distributions/
```
