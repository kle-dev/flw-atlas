# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.8.0.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.8.0**
- SHA-256: `64bf50a0cb4d0e52e369ef92a213498676848dbf8f42154642280702c82df03e`

Bundles the Atlas generator (the pure-Kotlin `:core` engine, run in-process). **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime — no external interpreter.

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-<version>.zip dist/
# then commit dist/ (remove the previous version's zip)
```
