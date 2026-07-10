# Prebuilt Atlas CLI (committed for offline / locked-down targets)

`cli-<version>-all.jar` here is the **self-contained** Atlas CLI fat-jar (Kotlin runtime + all
dependencies bundled). It is committed on purpose so that a machine which can reach this git remote
but **not** Maven Central / the Gradle Plugin Portal can still run Atlas without building:

```bash
git pull
./atlas /path/to/flowable-project        # the launcher finds lib/*-all.jar automatically
# or directly:
java -jar lib/cli-<version>-all.jar /path/to/flowable-project --all -o ./out
```

Requires only **Java 21+** on the target — no Gradle, no Maven, no network.

## Refreshing after a version bump
Rebuild and replace the jar (keep only one `*-all.jar` here so the launcher picks the right one):

```bash
./gradlew :cli:shadowJar
rm -f lib/*-all.jar
cp cli/build/libs/cli-*-all.jar lib/
git add lib/*.jar && git commit -m "lib: refresh prebuilt Atlas CLI jar"
```
