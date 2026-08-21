# Feature proposal — Business workflow narrative

*Status: proposal (not yet implemented). Author-in-conversation: 2026-07-17.*

## One-line

Turn Atlas's flat per-process inventory into an **ordered, branch-aware, cross-process business narrative** — the "what happens, in what order, under what condition, to what data" story of each workflow.

## Why this is worth building

Atlas today produces a **complete relationship index**: every task, gateway, form, condition, delegate, and data object is in the graph, and every cross-reference resolves. But the artifacts stop one step short of Flowable's actual value proposition: *business processes*. The current per-process section in `overview.md` reads like a developer inventory (an unordered bullet list of user tasks / service tasks / gateways / conditions), not a workflow.

Three audiences get an immediate step-change in value the moment ordered narrative exists:

1. **Business analysts & solution architects** onboarding to an unfamiliar Flowable project.
   Today: open Flowable Modeler, click through every `.bpmn` / `.cmmn` diagram, mentally stitch call activities together.
   With narrative: read one paragraph per process, follow cross-process links, done in minutes.

2. **LLM agents** consuming Atlas's LLM-first artifacts.
   Today: the agent gets a bag of nodes and must infer order from `sourceRef`/`targetRef` fields — brittle and token-expensive.
   With narrative: the agent gets an ordered path with branch conditions and human touchpoints already synthesised. In v1 this is served directly by the new `<project>.flow.json` artifact (see Decisions Q4); the `overview.md` / `CLAUDE.md` integrations follow. Atlas already positions itself as "LLM-first" via `summary.md` and `CLAUDE.md`, and this closes the biggest remaining gap in that positioning.

3. **Auditors & compliance reviewers** answering "who touches what data, when, under which condition."
   Today: manual tracing across models + Java + forms.
   With narrative: the answer is in the document.

## Scope — narratives are anchored at startable entry points

**A narrative is generated only for a process or case that can be *started*.** Sub-processes, call-activity targets, and other purely-internal models do **not** get their own top-level narrative. When a startable narrative traverses into a call activity (or a signal/message correlation whose target is another model), the target is **inlined inside** the parent narrative — it never becomes an independent top-level story.

**Source of truth — reuse, don't recompute:** the set of startable entry points is already computed by Atlas today. It lives in the `access` bucket of `Atlas.extract()`'s output, filtered by `action == "start"`, and is what already drives:

- `summary.md` → `## Entry points — who can start what` (`SummaryRenderer.kt:97–111`)
- `overview.md` → `### Who can start (processes & cases)` (`OverviewRenderer.kt:460+`)
- `CLAUDE.md` → the startable-entry-points count line (`ClaudeRenderer.kt:102–104`)
- `explorer.html` → the "Entry points — who can start what" section (rendered from the same `access` data)

`FlowTraversal` **reuses the exact same predicate** — no new "startability" logic and therefore no divergence risk between the entry-points listing already in the explorer/summary and the set of narratives this feature generates.

**Why this scope is right for a business narrative:**

- A business narrative answers *"what business events kick off work, and what happens then."* Called sub-processes are implementation detail — worth *inlining* to complete the story, but not worth surfacing as top-level stories in their own right.
- The count of top-level narratives becomes a natural sanity check: it should match (or be very close to) the "Entry points" count already displayed in the explorer.
- Consumers (business analysts, LLMs, auditors) get a curated set of business-meaningful workflows instead of drowning in shared utility sub-processes.

## What's already in the graph (so this is cheap to build)

The raw material is essentially all present:

- **Sequence-flow conditions** — parsed in `core/.../BackendModelParsers.kt:292–298` and carried into the graph.
- **All BPMN node kinds** — start/end events, user tasks, service tasks, script/rule tasks, gateways (exclusive/parallel/inclusive), boundary events, timers, message/signal/error/escalation events, call activities, sub-processes, multi-instance — first-class in `GraphBuilder.kt`.
- **Cross-process links** — call activities carry `calledElement`; message/signal events carry correlation names.
- **Actor & data anchors** — user tasks have `assignee` / `formKey`; service tasks have `delegate` / `expression` / `resultVariable`; process variables are listed in `overview.md` section 13.

## What's missing (the actual delta)

There is **no traversal** of the flow. Sequence flows are stored keyed by `sourceRef`/`targetRef` but never walked. There is **no ordered path**, no branching structure, no cross-process stitching, no prose generation. `OverviewRenderer.kt:76–136` emits a flat inventory per process.

## Proposed shape (for discussion, not commitment)

Add a `FlowTraversal` step that turns each process's flat nodes + sequence flows into an ordered, branch-aware walk, producing a `FlowStory`:

```
FlowStory
  process: <key>
  steps: [
    Step { kind, actor, action, dataIn[], dataOut[], branches[], calls[] }
  ]
```

Everything downstream falls out of that:

- **`<project>.explorer.html`** *(v1 primary — see Decisions Q1)* — a new "Storyline" view/tab in the interactive explorer. The FlowStory JSON is embedded in the self-contained HTML the same way today's explorer data already is; the Storyline view renders the narrative client-side, one entry per startable entry point.
- **`<project>.flow.json`** *(v1 new artifact — see Decisions Q4)* — the FlowStory JSON emitted as a standalone sibling of `graph.json`. Same data that's embedded in `explorer.html`; enables direct consumption by LLMs, CI, and external tooling without HTML-scraping.
- **`overview.md`** *(follow-up — not v1)* — new `## Business flow` section per startable entry point, deterministic templated prose.
- **`CLAUDE.md`** *(follow-up — not v1)* — condensed narrative or a pointer to `flow.json` so agents pick it up as part of the primer.
- **`graph.json`** — **untouched.** See "Architecture decision" below for why the structured `FlowStory` does not get added to it, and where the LLM-structured form lives instead.

**Cross-process stitching (one level inlined, deeper hops referenced — see Decisions Q2)** — when a step is a call activity or a signal/message throw whose target is another model, the target's flow is **inlined one level deep** inside the parent narrative. Anything called from *that* target appears as a **clickable reference** (e.g. *"→ AuditProcess (N more steps)"*) rather than being further inlined. Since target sub-processes never get their own top-level narrative (see "Scope" above), one-level inlining is how they surface at all, while the reference form preserves the trail without exploding narrative size. Cycle detection is not needed in v1: one-level inlining terminates automatically.

## Architecture decision — consumer-first, with one minimal parser addition

**Decision:** build `FlowTraversal` and all narrative rendering as a **consumer** of `Atlas.extract()`'s output. `GraphBuilder.kt` is untouched; the high-level graph structure (`graph.nodes` / `graph.edges`) is unchanged.

**One necessary exception — sequence-flow storage.** During implementation research it became clear that `BackendModelParsers.kt:292–298` only stores sequence flows that carry a `conditionExpression`; **unconditional flows are dropped entirely.** The high-level graph doesn't need them, but ordered traversal fundamentally does — without them, activities are disconnected. v1 therefore includes **one minimal, purely-additive parser change**: alongside the existing `conditions` list, emit a `sequenceFlows` list with every sequence flow (`id`, `from`, `to`, and `condition` when present). No existing field changes shape; no existing consumer of `conditions` is affected.

**Consequence for `graph.json`:** it gains **one new field per process** (`sequenceFlows`). Everything already there stays byte-identical; the change is purely additive. The `core/src/test/resources/golden/miniproject.graph.json` fixture is refreshed as a mechanical follow-up. `graph.json` is still **input-only** for this feature — the feature writes to `<project>.flow.json`, never into `graph.json`.

**Why this works:** with the additive parser change, every field needed for ordered, branch-aware traversal is available:

- `id` / `from` / `to` / optional `condition` — new `processes[].sequenceFlows[]` from the parser addition. Existing `conditions` list stays untouched for backward compatibility.
- All BPMN activity metadata (userTask, serviceTask, gateway, callActivity, boundary/intermediate events, multi-instance) — `processes[]`, `BackendModelParsers.kt:145–281`.
- Call activities carry `calledElement`; message/signal events carry `messageRef` / `signalRef`.

**Correct entry point:** consume the **raw `processes` bucket** from `Atlas.extract()` — not `graph.nodes` / `graph.edges`. The high-level graph captures model-to-model relationships (process → called-process); the raw bucket is where per-activity fidelity lives.

### Default-flow handling on exclusive gateways

Flowable enforces that an exclusive gateway has **exactly one default flow, and the default flow always has no condition**. The parser already stores only flows that carry a `conditionExpression` (`BackendModelParsers.kt:293–294`), so the traversal rule is exact: **an outgoing flow with no stored condition is the default.** No `default="…"` attribute lookup is needed, and no parser change is required.

### Minor heads-up — the extractor returns untyped maps

`Atlas.extract()` returns `LinkedHashMap<String, Any?>` (`Extractor.kt:37`). The new module will need to either define typed wrapper data classes over the map (cleanest), or accept untyped map access (fastest to prototype). This is a property of the current codebase, not a drawback of the consumer approach — and it argues *for* the consumer approach: introducing wrapper types inside a new `flow/` module is additive, whereas typing the extractor's output would be a much bigger cross-cutting change.

### Net effect on the plan

- New module: `core/src/main/kotlin/com/flowable/atlas/flow/` — `FlowStory.kt`, `FlowTraversal.kt` (covering both BPMN and CMMN — see Decisions Q3), wrapper types.
- New writer: `core/src/main/kotlin/com/flowable/atlas/render/FlowJsonRenderer.kt` (or similar) — serialises `FlowStory` to `<project>.flow.json`.
- v1 primary consumer: `ExplorerHtmlRenderer.kt` + `core/src/main/resources/frontend/explorer.{html,js,css}` — embeds the same FlowStory JSON into the self-contained HTML and renders the Storyline view client-side.
- **One minimal parser addition** in `BackendModelParsers.kt`: emit a `sequenceFlows` list per process alongside the existing `conditions` list (additive; see "one necessary exception" above). Refresh the `miniproject.graph.json` golden fixture accordingly.
- Follow-up consumers (not v1): `OverviewRenderer` (`## Business flow` section in `overview.md`) and `ClaudeRenderer` (narrative reference in `CLAUDE.md`).
- **No changes to `GraphBuilder.kt`.** `graph.json` gains only one additive `sequenceFlows` field per process; every existing field stays byte-identical. The structured narrative lives in `flow.json`, not in `graph.json` (see Decisions Q4).

## Key design tradeoff to resolve before building

**Deterministic templated prose vs. LLM-authored prose.**

- Templated prose keeps Atlas's "single JVM, no third-party deps, JRE 21+" story intact but reads a bit stiff.
- LLM prose reads naturally but breaks the zero-dependency story and introduces cost / offline concerns.

**Resolution (informed by Decisions Q1 and Q4):** emit a structured `FlowStory` JSON that both drives the explorer's Storyline rendering (client-side, from the embedded blob) and gets written to the standalone `<project>.flow.json` artifact. The explorer renders deterministic prose from that JSON, keeping Atlas offline and dependency-free. LLMs consume the structured form directly from `flow.json` — no polishing step required. If richer natural-language prose is later wanted, downstream LLMs can be pointed at `flow.json` on demand. `graph.json` gains only the one additive `sequenceFlows` field required for traversal (see "one necessary exception" in the architecture decision); every other field stays byte-identical.

## Decisions (Q1–Q4)

Four questions were resolved during design conversation. Each is referenced throughout the sections above.

1. **Primary target artifact — `<project>.explorer.html`.** The interactive explorer gets a new "Storyline" view/tab as v1 primary landing zone. `overview.md` and `CLAUDE.md` integrations are follow-ups, not v1.

2. **Depth of cross-process inlining — one level.** Immediate call-activity / signal-message targets are inlined into the parent narrative; deeper hops appear as clickable references (e.g. *"→ AuditProcess (N more steps)"*). Cycle detection is not needed in v1 — one-level inlining terminates automatically.

3. **BPMN vs CMMN in v1 — both.** CMMN cases get their own narrative shape structured around stages, plan items, and milestones (event-based, non-linear) so every entry point in "Entry points — who can start what" has a matching narrative from day one.

4. **Structured JSON location — both `<project>.flow.json` and embedded in `<project>.explorer.html`.** A new standalone `flow.json` artifact serves external LLM/tooling consumers; the same JSON is embedded inside the self-contained HTML for the explorer's Storyline view. `graph.json` gets only the one additive `sequenceFlows` field required by the traversal (see architecture decision); no other changes to `graph.json`.

## Rough scope

Given Q1–Q4, v1 scope covers:

- Filter to startable entry points via `access` bucket (`action == "start"`) — same predicate the existing summary/overview use.
- `FlowTraversal` producing `FlowStory` for:
  - each startable **BPMN process** — ordered linear walk with branch conditions.
  - each startable **CMMN case** — non-linear stage / plan-item / milestone shape.
- Standalone `<project>.flow.json` artifact writer.
- Explorer "Storyline" view — new tab/panel rendering FlowStory client-side from the embedded JSON, with clickable references for deeper hops.
- One-level cross-process inlining (call activities and signal/message throws).

**Estimate.** An earlier note put v1 at 2–4 days when the target was markdown-only and BPMN-only. With (a) explorer frontend work, (b) CMMN as first-class, and (c) two persistence targets (`flow.json` + embedded), the honest estimate is closer to **6–10 focused days** for v1.

**Natural follow-ups (not v1):** multi-hop inlining with cycle detection, `overview.md` and `CLAUDE.md` integrations, richer Storyline UX (Mermaid diagrams, expand-in-place for referenced hops), LLM-polished prose mode.

## Reference — files that will be touched

**v1 (new files):**
- `core/src/main/kotlin/com/flowable/atlas/flow/FlowStory.kt` — data classes (BPMN and CMMN variants).
- `core/src/main/kotlin/com/flowable/atlas/flow/FlowTraversal.kt` — traversal producing `FlowStory` per startable entry point.
- `core/src/main/kotlin/com/flowable/atlas/render/FlowJsonRenderer.kt` (or similar) — serialises `FlowStory` to `<project>.flow.json`.

**v1 (existing files modified):**
- `core/src/main/kotlin/com/flowable/atlas/parsing/BackendModelParsers.kt` — one minimal, additive change: emit a `sequenceFlows` list per process alongside the existing `conditions` list (see architecture decision).
- `core/src/main/kotlin/com/flowable/atlas/render/ExplorerHtmlRenderer.kt` — embed the FlowStory JSON blob into the generated HTML.
- `core/src/main/resources/frontend/explorer.html` — new Storyline tab/panel structure.
- `core/src/main/resources/frontend/explorer.js` — Storyline view logic (render narrative, handle clickable references).
- `core/src/main/resources/frontend/explorer.css` — Storyline view styling.
- `cli/src/main/kotlin/com/flowable/atlas/cli/Main.kt` (around line 171) — register the new `flow.json` emission alongside the existing `graph.json` / `summary.md` / etc.
- `core/src/test/resources/golden/miniproject.graph.json` — refresh to include the new additive `sequenceFlows` field.

**Follow-up (not v1):**
- `core/.../render/OverviewRenderer.kt` — `## Business flow` section in `overview.md`.
- `core/.../render/ClaudeRenderer.kt` — narrative reference in `CLAUDE.md`.
- `core/.../render/SummaryRenderer.kt` — optional condensed one-liner per entry point.

**Files that will explicitly *not* be touched:**
- `core/.../graph/GraphBuilder.kt` — graph structure unchanged.
- The emitted `<project>.graph.json` for existing fields — every field that exists today stays byte-identical; the only change is the one additive `sequenceFlows` field per process. `graph.json` is **input-only** for this feature — never written into as an output.
