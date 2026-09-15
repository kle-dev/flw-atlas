# For LLMs & agents

Generating context for AI agents was Atlas's original purpose, and it still shapes the artifacts. A
Flowable solution project is exactly the kind of codebase an agent handles badly: the behaviour lives
in models rather than code, the models reference each other by opaque keys, and the Java is attached
through expression strings that no compiler checks. An agent that cannot see those relationships
invents them.

## Four sizes — use the smallest one that answers the question

**1 · `<project>.CLAUDE.md`** — drop it into the repository root as `CLAUDE.md` (or `AGENTS.md`), or
keep your own `CLAUDE.md` and import it with `@atlas-output/<project>/<project>.CLAUDE.md`. Roughly
10–15 KB. A Flowable primer, this project's conventions and wiring examples, which model files are Design
exports not to be edited, its open findings, and the complete list of the expression namespaces, script
bindings and platform beans that actually exist. That last part is the one that earns its keep: it stops
an agent inventing APIs.

Deliberately *not* in it: the inventory, the counts, the directory layout. Those are the summary's, and a
repository overview is the one kind of context-file content that measurably does not help.
[Gloaguen et al. (ETH Zürich, 2026)](https://arxiv.org/abs/2602.11988) ran Claude Code, Codex and Qwen
Code over real issues with and without context files: agents follow a file's *instructions* — a named
tool is used, a stated rule is kept — but gain nothing from its *overviews*, which repeat what they would
read anyway, and the file costs about a fifth more per task. So the file holds instructions, conventions
and catalogs an agent cannot get out of the repository, and nothing it can.

**2 · `--summary`** — a few KB of orientation: apps, inventory, entry points, integrations, hotspots,
health. The health line reads *3 defects · 41 advice* — what is wrong now, and what is only worth a
look — so an agent knows which number to act on. This is the right thing to paste into a conversation
when the question is "what is this project".

**3 · `--slice <type:key>`** — one model with its full context, both directions, plus the findings that
touch it. The right size when the task is about one model, which it usually is.

**4 · `--json`** — the full graph. **Never paste it whole**; query it with `jq`. The file documents
itself: a `_schema` key at the top describes the shape and ships six ready-made recipes.

`<project>.overview.md` sits between 2 and 4 for a human reader.

## Why the graph is queryable rather than readable

Three properties make `graph.json` usable by an agent without a retrieval layer:

- **Both directions resolve.** Every node carries `usedBy`, so "what would break if I change this" is
  one lookup, not a scan.
- **Stable ids.** Every node is `<type>:<key>` — the same id in the graph, in a `--slice`, in a
  finding, and in an explorer permalink.
- **It says what it does not know.** Suspect and dynamic links are flagged rather than presented as
  facts, and unresolved references are listed separately. An agent that reads a `suspect` flag can ask
  instead of assuming.

```bash
# the recipes travel inside the file
jq '._schema.recipes' graph.json

# what does this process reference, and what references it?
jq '.graph.edges[] | select(.s=="process:orderProcess")' graph.json
jq '.graph.nodes[] | select(.id=="form:orderForm") | .usedBy'  graph.json

# everything already known to be wrong, worst first
jq '.findings[] | select(.severity=="error") | {check, label, message, file}' graph.json
```

## A workable agent loop

1. **Once per project:** `./atlas <project>`, then copy `<project>.CLAUDE.md` to the repository root as
   `CLAUDE.md`. Or write the output into the project — `./atlas <project> <project>/atlas-output` — keep
   your own `CLAUDE.md` and add the `@atlas-output/<project>.CLAUDE.md` line the file's header prints;
   then a regeneration is what Claude reads next time, with no copy step. (Atlas's output folder ignores
   itself in git; add `!<project>.CLAUDE.md` to its `.gitignore` if the team should get the file.) Either
   way, regenerate when the models change materially.
2. **Starting a task:** give the agent `--summary`, or let it read the `CLAUDE.md` you committed.
3. **Working on one model:** `--slice <type:key>`. It contains the callers, which is the context an
   agent most often lacks and most confidently guesses at.
4. **Answering a structural question:** `jq` over `graph.json`.
5. **Before believing the result:** check the [findings](../checks/). Atlas already knows which
   references are broken; an agent should not spend its context rediscovering that.

## The generic primer

`CLAUDE.template.md` in the repository is the project-independent half: what Flowable is, the mental
model an LLM usually gets wrong, how custom code attaches to models, how models and deployment fit
together, and rules for the agent. It is generated from the same source as the per-project file, so the
two cannot drift.

```bash
java -jar cli-<version>-all.jar --claude-template     # no project needed
```

Prefer the generated per-project version whenever you can run Atlas. Use the template only when you
cannot, and fill in its project section by hand.
