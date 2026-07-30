# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.10.15.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.10.15**
- SHA-256: `a0b8a40212ba2b387a8fddaf18cb84d0078a2981d84cf984390c992c82d91591`

Bundles the Atlas generator (the pure-Kotlin `:core` engine, run in-process). **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime — no external interpreter.

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-<version>.zip dist/
# then commit dist/ (remove the previous version's zip)
```
