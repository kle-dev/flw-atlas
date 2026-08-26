# Flowable Atlas — installing the plugin

The plugin ZIP is **not committed to this repository**. Download it from the
[**Releases**](https://github.com/kle-dev/flw-atlas/releases/latest) page — each release has
`flowable-atlas-<version>.zip` attached, built by CI from that release's tag.

## Install (on any machine)

1. Download `flowable-atlas-<version>.zip` from the latest release.
2. IntelliJ IDEA **2026.2+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the downloaded ZIP.
4. **Restart** the IDE.

Needs **2026.2+**, and that is also the version it is built and verified against (JetBrains' Plugin
Verifier runs on every push). The
plugin bundles the Atlas generator — the pure-Kotlin `:core` engine, run in-process — so **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime, no external interpreter.

Each release also lists the ZIP's SHA-256, generated at build time. Compare it if you want to be sure the
download is intact:

```bash
shasum -a 256 flowable-atlas-<version>.zip
```

> This folder is a convenient place to keep the downloaded ZIP — it is gitignored, so anything you put
> here stays local.

## Building it locally

```bash
./gradlew :idea-plugin:buildPlugin     # -> idea-plugin/build/distributions/
```

> Why the ZIP is not committed: see the release-artifacts comment in `.gitignore`. Publishing one is
> `../../CONTRIBUTING.md`.
