# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** (verified on **2026.1** and **2026.2**) → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.12.2.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.12.2**
- SHA-256: `4736117cc82ef874f5f3d8f53c3f54944db45c0c574e012271f09dcdd3e0c78c`

Bundles the Atlas generator (the pure-Kotlin `:core` engine, run in-process). **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime — no external interpreter.

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-<version>.zip dist/
# then commit dist/ (remove the previous version's zip)
```
