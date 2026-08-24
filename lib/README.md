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

## Building it locally

```bash
./gradlew :cli:shadowJar        # -> cli/build/libs/cli-<version>-all.jar
```

> Why the jar is not committed: see the release-artifacts comment in `.gitignore`.
