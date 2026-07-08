# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.5.0.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.5.0**
- SHA-256: `911128c811846276dc4d7770fe56fa8dee0661377dec36a96f508864ea497034`

Bundles the Atlas generator (`flowable_atlas.py`). **Generate Atlas Explorer**
(Tools → Flowable Atlas) additionally needs a **Python 3.8+** interpreter on the machine
(auto-detected, or set it in Settings → Tools → Flowable Atlas).

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-<version>.zip dist/
# then commit dist/ (remove the previous version's zip)
```
