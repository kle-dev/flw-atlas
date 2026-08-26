# Getting started

Atlas comes in two halves that share one engine: an **IntelliJ IDEA plugin** and a **command-line
tool**. You can use either on its own. Both need Java 21 or later, and neither needs a running Flowable
instance, a database, or a network connection.

Both are attached to every [release](https://github.com/kle-dev/flw-atlas/releases/latest) — they are
not committed to the repository, and `SHA256SUMS.txt` in each release carries the checksums.

## The IntelliJ plugin

Atlas is not on the JetBrains Marketplace, but it ships its own plugin repository — which means it
still joins the IDE's normal update flow instead of going quietly stale in the background.

**The easy way, once:**

1. *Settings → Plugins → ⚙ → **Manage Plugin Repositories…*** and add:

   ```
   https://github.com/kle-dev/flw-atlas/releases/latest/download/updatePlugins.xml
   ```

2. Find **Flowable Atlas** in the *Marketplace* tab and install it.
3. Restart the IDE. (Atlas registers languages and file types, which cannot load dynamically.)

That URL is stable — it always resolves to the newest release — so from then on a new version arrives
as an update badge, with nothing to download by hand.

**By hand:** download `flowable-atlas-<version>.zip` from the
[latest release](https://github.com/kle-dev/flw-atlas/releases/latest), then *Settings → Plugins → ⚙ →
Install Plugin from Disk…* and restart.

Needs **IntelliJ IDEA 2026.2 or later** — the version Atlas is built against and verified on. A bug
report submitted from the IDE states what was actually verified, so a newer IDE is never presented as
covered. Releases are signed when a signing key is configured, and `SHA256SUMS.txt` in each release
carries the checksums.

There is nothing to configure. Open a project that contains Flowable models — an app `.zip` or
deployment `.bar`, loose `.bpmn` / `.cmmn` / `.dmn` / `.form` / `.data` / `.service` files, or a Flowable
Design `*-models` workspace — and start typing. The plugin indexes every model it finds and the rest
follows.

> Building it yourself instead: `./gradlew :idea-plugin:buildPlugin`.

## The CLI

Download `cli-<version>-all.jar` into `lib/` and use the launcher:

```bash
./atlas /path/to/your-flowable-project
```

Or run the jar directly, from anywhere:

```bash
java -jar cli-<version>-all.jar /path/to/your-flowable-project --all -o ./reports
```

The jar is self-contained — a JRE 21+ and nothing else. If the launcher cannot find a jar it will build
one with Gradle; set `ATLAS_NO_BUILD=1` on a machine that has no JDK, and it will tell you what to
download instead.

> First run not executable? `chmod +x atlas`

## Your first run

```bash
./atlas ~/projects/order-management
```

Atlas walks the project, resolves every relationship it can, and writes five files to
`./atlas-output/order-management/` before opening the explorer in your browser:

```
order-management.explorer.html   ← opens automatically
order-management.summary.md
order-management.overview.md
order-management.graph.json
order-management.CLAUDE.md
```

The last line on stderr is the health check, and it is the first thing worth reading:

```
412 models · 88 java · 3104 nodes · 5192 links · 412 resolved / 23 unresolved refs · ⚠ 3 parse issue(s)
```

If it says *parse issues*, start there — a file Atlas could not read costs you every reference into and
out of it. The [Checks page](../checks/) explains each finding.

## Where to go next

Once the explorer is open, three views answer most first questions:

- the **overview** — what this project is, what is central to it, who can start what;
- **`#/checks`** — everything already known to be wrong;
- any model's detail panel — and specifically its **Used by** list, which is the question a model file
  cannot answer about itself.

Then:

| If you want to… | Read |
|---|---|
| Understand the explorer properly | [The Atlas explorer](../explorer/) |
| Know what each generated file is for | [Generated artifacts](../artifacts/) |
| See every CLI flag | [CLI reference](../cli/) |
| Work in the IDE | [What the plugin does](../plugin/) |
| Feed a project to an AI agent | [For LLMs & agents](../agents/) |

## What Atlas understands

It discovers models — `.bpmn`, `.cmmn`, `.dmn`, `.form`, `.app`, `.service`, `.data`, `.agent`,
`.action`, `.sequence` and the rest — loose **and** inside `.zip` / `.bar` archives, plus `.java` sources
and Liquibase changelogs. Then it resolves the relationships between them:

- app → its models; process or case → the process, case, decision or form it calls;
- `${bean.method()}`, `delegateExpression` and listeners → the **Java class and method**, with
  `file:line`;
- form or process → **REST endpoint** → the controller that serves it;
- action → the Java `BotService` that implements it; agent → its tools;
- data object → its backing service, its columns and its **Liquibase** table;
- **who can do what** — candidate starter groups, app and page access, identity links, security
  policies;
- sequences → the cases and processes that use them; Java → Java, through dependency injection.

Whatever cannot be resolved inside the project — Flowable platform beans, external REST endpoints — is
listed separately, so a real gap stands out instead of hiding.
