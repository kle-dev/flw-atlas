# Flowable Keys — prebuilt plugin

The installable plugin ZIP, committed so you can install it after a `git pull`
**without building anything**.

## Install (on any machine)

1. IntelliJ IDEA **2026.1+** → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `flowable-keys-0.2.1.zip`
3. **Restart**

> Do **not** open `idea-plugin/` as a Gradle project just to use the plugin.
> Building requires Kotlin **2.3.21** (ships with IDEA 2026.1, not published to the
> public Gradle Plugin Portal / Maven Central), so a machine without that in its
> Gradle cache can't compile it — but it doesn't need to: installing the ZIP is enough.

## This build

- Version: **0.2.1**
- SHA-256: `4412e65814cb8df94d2fbf01ba0d5d11ae4a535d6e571d429f56e5bef8976b91`

## Refreshing this ZIP after code changes

```bash
cd ..                       # into idea-plugin/
./gradlew buildPlugin
cp build/distributions/flowable-keys-0.2.1.zip dist/
# then commit dist/flowable-keys-0.2.1.zip
```
