# For LLMs & agents

Generating context for AI agents was Atlas's original purpose, and it still shapes the artifacts. A
Flowable solution project is exactly the kind of codebase an agent handles badly: the behaviour lives
in models rather than code, the models reference each other by opaque keys, and the Java is attached
through expression strings that no compiler checks. An agent that cannot see those relationships
invents them.

## Four sizes — use the smallest one that answers the question

**1 · `<project>.CLAUDE.md`** — drop it into the repository root as `CLAUDE.md` (or `AGENTS.md`), or
write the output into the project — `./atlas <project> <project>/atlas-output`, or the IDE's default
output folder — and import it from your own `CLAUDE.md` with the `@atlas-output/<project>.CLAUDE.md` line
its header prints. (The launcher's default folder is beside the launcher, not in the project, and Claude
Code skips an import it cannot resolve without saying so.) Roughly 10–15 KB. A Flowable primer, this
project's conventions and wiring examples, which model files are Design exports not to be edited, its
open findings, and the complete list of the expression namespaces, script bindings and platform beans
that actually exist. That last part is the one that earns its keep: it stops an agent inventing APIs.

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
touch it, what it contains in the overview's words, and the variables it sets and reads. A variable's
slice names the elements that set it and read it. The right size when the task is about one model, which
it usually is. An agent that knows the model by its file rather than its key can pass the path instead,
relative or absolute; a target that matches nothing names the nearest ids.

**4 · `--json`** — the full graph. **Never paste it whole**; query it with `jq`. The file documents
itself: a `_schema` key at the top describes the shape and ships seven ready-made recipes.

`<project>.overview.md` sits between 2 and 4 for a human reader.

## Why the graph is queryable rather than readable

Three properties make `graph.json` usable by an agent without a retrieval layer:

- **Both directions resolve.** Every node carries `usedBy`, so "what would break if I change this" is
  one lookup, not a scan — for a variable, an expression or a service operation too, which no edge
  points at.
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

# everything already known to be wrong, and not accepted by a waiver
jq '.findings[] | select(.kind=="defect" and (.waived|not)) | {check, label, message, file}' graph.json
```

## A workable agent loop

1. **Once per project:** `./atlas <project>`, then copy `<project>.CLAUDE.md` to the repository root as
   `CLAUDE.md`. Or write the output into the project — `./atlas <project> <project>/atlas-output` — keep
   your own `CLAUDE.md` and add the `@atlas-output/<project>.CLAUDE.md` line the file's header prints;
   then a regeneration is what Claude reads next time, with no copy step. (Atlas's output folder keeps
   its artifacts out of git; add `!<project>.CLAUDE.md` to its `.gitignore` if the team should get the
   file.) `--claude` alone writes a `CLAUDE.md` into the project, but never over one Atlas did not write.
   Either way, regenerate when the models change materially.
2. **Starting a task:** give the agent `--summary`, or let it read the `CLAUDE.md` you committed.
3. **Working on one model:** `--slice <type:key>`. It contains the callers, which is the context an
   agent most often lacks and most confidently guesses at. Before a rename, the slice of the variable
   says which elements set and read it.
4. **Answering a structural question:** `jq` over `graph.json`.
5. **Before believing the result:** check the [findings](../checks/). Atlas already knows which
   references are broken; an agent should not spend its context rediscovering that.
6. **After changing a model:** run Atlas again with `--baseline <project>.graph.json --fail-on defects`
   (the [CLI reference](../cli/#gate-the-change-not-the-backlog) has the details). It exits 1 and lists each
   defect the graph does not have yet — what the edit added — so the agent can fix it before it reports
   done. The generated `CLAUDE.md` gives the agent the exact command as the last step of its procedure.
7. **Reviewing a change:** `--changes --stdout --baseline <the base>` says what it did, model by model —
   the tasks, flows and fields added, removed or changed, the references, the findings it brought and
   resolved. It is the part of a pull request an agent cannot read when the models sit in a `.zip`; the
   [CLI reference](../cli/#review-a-pull-request) has the recipe.

## Working in Flowable Work itself

Everything above is about an agent that changes the *code*. A different request is just as common:
"bring this case into stage 5", "complete the review task for case X", "why is the approve button greyed
out". That work happens in Flowable Work's UI, and an agent with a browser can do it, provided it knows two
things a generic model does not:
- **how Work behaves.** A manual-activation item is a toolbar button, on a form from Design 3.8 or later
  the outcomes stay disabled until every required field is valid, and completing a task may jump straight
  to the next one.
- **how this project's cases move.** What starts each stage, and which outcome sets the variable a
  sentry waits for.

`<project>.playwright.md` carries both, with the project's details in `<project>.playwright-map.md` beside
it; [Generated artifacts](../artifacts/#playwrightmd-driving-flowable-work-in-a-browser) says what is in
them.

**Set it up once:**
1. **Give the agent a browser.** For Claude Code that is the Playwright MCP server:
   `claude mcp add playwright npx @playwright/mcp@latest`. A Playwright script works too.
2. **Generate the guide.** `./atlas` and `--all` write it with its map. In the IDE, tick *Playwright guide
   for Flowable Work (+ project map)* — and *CLAUDE.md primer*, if the agent should be told about it — under
   *Settings → Tools → Flowable Atlas → Generation*.
3. **Make it reachable.** With `./atlas <project> <project>/atlas-output`, the generated `CLAUDE.md`
   already says when to read the guide and where the map is. For the team, add `!<project>.playwright.md`
   and `!<project>.playwright-map.md` beside `!<project>.CLAUDE.md` in the output folder's `.gitignore`;
   otherwise a committed `CLAUDE.md` points every teammate's agent at files their checkout does not have.
   Alternatively, install it as a skill: copy the guide to `.claude/skills/flowable-work-ui/SKILL.md` and
   the map to `.claude/skills/flowable-work-ui/project-map.md`. Its front matter makes it a skill, which
   Claude Code loads when a request is about Work. A copied skill is a snapshot: copy both files again after
   each regeneration.
4. **Hand over the essentials in the prompt.** The URL of a **test** installation, the user to sign in
   as, and the case you mean, by business key or instance id. The guide tells the agent never to guess
   credentials.

**Then ask in plain words.** "Open the claim case with business key CLM-4711 on
http://localhost:8080/flowable-work and bring it into stage 5." The guide makes the agent work through
these steps:
1. Resolve the business key to the case's instance id over REST, and compare the groups of the user it
   signed in as with the ones the route needs, asking for missing accounts up front.
2. Find stage 5 in the case's stage bar. The guide numbers the bar for planning and marks with ⚠ where Work
   reorders it as the case runs; the agent counts in the live bar, and names the stage back to you when the
   stage names carry numbers of their own.
3. Walk the route backwards from the model: what starts stage 5, what that needs, and so on, back to
   where the case stands.
4. Read the case's current state over REST, in one call.
5. Do one step at a time in the UI: claim, fill the required fields, press the outcome the route needs,
   start a manual item.
6. Check over REST that the case moved before taking the next step.

The agent asks before a business decision such as approving or rejecting, and before anything
irreversible. It reports the path it took, step by step.

**It is version-aware.** The project's build file says which Flowable version it targets, and the guide
tells the agent to read the running version from the system itself. Locators and dialogs differ between
3.14 and older, 2025.1 with 3.15–3.17, 2025.2 and 2026.1 and newer, and the guide names what differs. Those
notes come from Work's frontend source for each line and its own end-to-end test helpers, not from
driving a running Work; when a locator finds nothing, the agent falls back on role and visible text and says which
one failed.

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
