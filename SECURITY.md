# Security policy

## Reporting a vulnerability

**Do not open a GitHub issue.** Issues on this repository are public, and a security report filed
there is a disclosure before there is a fix.

Report privately to **kevin.klever@flowable.com**, or through Flowable AG's regular support channel
if you are a Flowable customer. A report is most useful with:

- the Atlas version (Atlas Hub footer, or *Settings → Plugins → Flowable Atlas*) and the IDE build;
- what an attacker gains, not only what misbehaves;
- the smallest input that reproduces it — a model file, an expression, a project layout.

Expect an acknowledgement within five working days. If a fix ships, the release notes will say a
security issue was fixed; they will not carry reporter details unless you ask for credit.

## What is in scope

The IntelliJ plugin, the CLI, and the artifacts they generate (`*.explorer.html`, `*.graph.json`,
`*.summary.md`, `*.overview.md`, `*.CLAUDE.md`).

Out of scope: Flowable Design, Flowable Work, and any Flowable server product — those have their own
channel. Also out of scope is the licence status of this repository; see [LICENSE](LICENSE).

## Design decisions worth knowing before you report

These are deliberate, documented, and not vulnerabilities in themselves. Reporting them as findings
is fine, but knowing the reasoning first will save you time.

- **Atlas transmits nothing on its own.** There is no telemetry and no crash-report endpoint. The
  *"Report Flowable Atlas Problem…"* action copies the report to your clipboard and opens the issue
  tracker in your browser — the text never leaves the machine unless you paste it. A stack trace can
  contain model keys, file paths and expression text, which is exactly why the hand-off is manual.
- **Credentials go to the IDE PasswordSafe**, never into a settings file. This covers both the
  Flowable Design connection (password or personal access token) and the Inspect connection. Only
  the base URL, the username and the auth *mode* are stored in project settings.
- **Outbound connections happen only when you ask for one**: *Pull from Flowable Design*, the Design
  connection test, *Create Token…*, and the Expression Playground's *Evaluate Against App*. Each
  targets a base URL you configured. Nothing contacts Flowable AG.
- **XML is parsed with DTDs and external entities disabled** — see `AtlasXml` and
  `ModelMemberExtractor`. Model files are treated as untrusted input.
- **Generated artifacts contain your project's data by construction.** An `.explorer.html` embeds
  model keys, expressions, and code references. It is a report about your repository; treat sharing
  one the way you would treat sharing the repository. `.gitignore` keeps them out of this repo for
  that reason.
- **The plugin requires a restart on install/update** (`require-restart="true"`). It registers
  languages and file types, which cannot be loaded dynamically.

## Supported versions

Atlas is on the `0.x` line. Only the **latest release** is supported; fixes are not backported. See
[CHANGELOG.md](CHANGELOG.md).
