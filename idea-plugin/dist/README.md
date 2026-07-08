# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.7.0.zip`
3. **Restart**

> You do **not** need to open or build `idea-plugin/` just to use the plugin —
> installing this ZIP is enough.

## This build

- Version: **0.7.0**
- SHA-256: `539f3d91d72d020c6be679e3aad3a8b6e9299acaf3914c9bf955357fa0933866`

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
