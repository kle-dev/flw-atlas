# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.8.10.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.8.10**
- SHA-256: `19c506b0405004482643de1676cf883cf85a4c0ed87b680cd02ffb43e917a6ac`

Bundles the Atlas generator (the pure-Kotlin `:core` engine, run in-process). **Generate Atlas
Explorer** (Tools → Flowable Atlas) needs only a **Java 21+** runtime — no external interpreter.

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-<version>.zip dist/
# then commit dist/ (remove the previous version's zip)
```
