# Flowable Atlas — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-atlas-0.4.1.zip`
3. **Restart**

> Do **not** open `idea-plugin/` as a Gradle project just to use the plugin.
> Building requires Kotlin **2.3.21** (ships with IDEA 2026.1, not published to the
> public Gradle Plugin Portal / Maven Central), so a machine without that in its
> Gradle cache can't compile it — but it doesn't need to: installing the ZIP is enough.

## This build

- Version: **0.4.1**
- SHA-256: `33f03319e0af4812c4700888b686a3c81b65bbfd525a0d533df0b7540295881f`

Bundles the Atlas generator (`flowable_atlas.py`). **Generate Atlas Explorer**
(Tools → Flowable Atlas) additionally needs a **Python 3.8+** interpreter on the machine
(auto-detected, or set it in Settings → Tools → Flowable Atlas).

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-atlas-0.4.1.zip dist/
# then commit dist/flowable-atlas-0.4.1.zip
```
