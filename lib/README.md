# Atlas CLI — self-contained jar

The CLI fat-jar (Kotlin runtime and all dependencies bundled) is **not committed to this repository**.
Download `cli-<version>-all.jar` from the
[**Releases**](https://github.com/kle-dev/flw-atlas/releases/latest) page.

## Running it without building

Drop the downloaded jar into this folder and the `./atlas` launcher finds it automatically — it searches
`$ATLAS_JAR`, then next to the script, then `lib/`, then the local Gradle build output:

```bash
# from the repository root, with the jar in lib/
./atlas /path/to/flowable-project

# or point at it directly, from anywhere
java -jar cli-<version>-all.jar /path/to/flowable-project --all -o ./out
```

Requires only **Java 21+** on the target — no Gradle, no Maven, no network. On a locked-down machine set
`ATLAS_NO_BUILD=1` so the launcher never tries to build (which would need Maven Central).

Keep only **one** `*-all.jar` here: the launcher picks the newest by modification time, and two jars make
which one runs a matter of file timestamps.

> This folder is gitignored for `*.jar`, so a jar you put here stays local.

## Why it is not in the repository

It used to be committed for exactly the offline case above. But binaries do not delta-compress: every
version stored a full copy, and together with the plugin ZIP that had reached 152 MB — 86% of the
repository — for files where only the newest is useful. And the reason given for committing it
(*"reaches this git remote but not Maven Central"*) did not hold: the git remote and the Releases page
are the **same host**, so `git pull` and a release download face the same firewall.

## Building it locally

```bash
./gradlew :cli:shadowJar        # -> cli/build/libs/cli-<version>-all.jar
```
