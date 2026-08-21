# Business workflow narrative — open implementations

*Companion to `business-workflow-narrative-spec.md` and `flow-story-schema.md`. Captures work that was intentionally deferred (either scoped out of v1 in the spec, called out as "deferred (post-v1)" in the schema, or surfaced during Phases A–C and pushed forward).*

## Status legend

- **Deferred by design** — the spec / schema explicitly scoped this out of v1.
- **Surfaced during implementation** — came up while building Phases A–C, not blocking today.
- **Needs a real project** — feature is coded but the miniproject fixture can't exercise it; verification requires a real Flowable project.

---

## Traversal & data model

### BPMN — boundary events
**Deferred by design.** The FlowStory schema defines `BoundaryEvent` (timer / message / signal / error / escalation, interrupting flag, `routesTo` continuation) but `FlowTraversal` doesn't populate it yet. A timer-boundary on a user task is a very common pattern in real projects; Storyline should show *"⏱ If 3 days elapse: escalate to director"* attached to the parent step. Requires: additional parsing of `<boundaryEvent>` in the raw process bucket (currently included in `events`) and a `routesTo` walk from the boundary's outgoing flow.

### BPMN — multi-instance markers
**Deferred by design.** Parser already exposes `multiInstance: [{activity, collection, elementVariable, cardinality, sequential}]`. The `ActivityStep` schema in `flow-story-schema.md` reserves a `multiInstance` field but the Kotlin data class doesn't carry it yet, and the renderer doesn't show a *"🔁 multi-instance: parallel, for each `${items}`"* badge. Additive.

### BPMN — sub-processes (embedded / event / transaction / ad-hoc)
**Deferred by design.** Schema defines `SubProcessStep` with `subKind` and a `steps: List<Step>` for the embedded flow. `FlowTraversal.makeActivity` currently produces a plain `ActivityStep` for sub-processes; it should recognise the `subProcess` element kind, walk its inner flow, and emit a `SubProcessStep` instead.

### BPMN — multi-hop cross-process inlining
**Deferred by design (Q2 decision).** v1 inlines one level; deeper hops render as clickable references. When a real chain like `A → CallB → (in B) CallC → (in C) CallD` matters, we'll want configurable depth. Requires: cycle detection (currently unneeded because one-level terminates), a depth parameter, and a UX affordance for "expand this reference to reveal its steps" without navigating away.

### BPMN — loop / back-edge modelling
**Deferred by design.** Sequence flows that form a cycle at the same nesting level are broken by the traversal's `visited` set — the second visit stops the branch. That's a safe cycle guard but it loses information (a real BPMN loop marker on an activity, or a back-edge to a gateway, are both meaningful). Requires: a first pass to detect strongly-connected components in the flow graph and represent them explicitly rather than silently truncating.

### BPMN — data-flow overlay
**Surfaced during implementation.** Called out as a differentiator over Flowable Design in the Phase B discussion. Idea: track process variable read/write per step (which service task writes `total`, which form reads it, where it's consumed by a gateway condition) and surface a *"data trace"* alongside the narrative. Requires: extending `Ctx.varUse` / `scriptVarUse` to attribute variables to specific step IDs (currently only per model), plus a UI layer on top of the FlowStory.

### CMMN — sentry structural detail
**Partially addressed in Phase C follow-up.** Entry criteria now render as human descriptions (*"when 'X' completes"* / *"when `${cond}` holds"*) and plan items are topologically sorted by dependency. Still deferred: `onPart.standardEvent` (parser drops the "complete" / "occur" / "terminate" transition and always assumes completion); `caseFileItemOnPart` (case-file item events, not currently emitted at all); milestone entry criteria (milestones are captured but their sentries aren't tied back into the story yet).

### CMMN — Flowable Design condition-builder syntax
**Surfaced during implementation.** When a modeller uses Flowable Design's visual condition builder on a sentry, the exported CMMN doesn't store a raw expression in `<ifPart><condition>...</condition></ifPart>`. Instead it stores an **attribute-driven structured block** inside `<extensionElements>`:
```xml
<sentry id="sentryEntryCriterion_3" name="Remaining amount available">
  <extensionElements>
    <flowable:condition group="true" match="all" targetAttribute="condition">
      <flowable:condition sourceVariableType="customVariable"
                          sourceName="triggerReimburesement"
                          operator="equals"
                          targetVariableType="value"
                          targetValue="true" targetType="boolean"/>
    </flowable:condition>
  </extensionElements>
</sentry>
```
The parser's `<sentry>` handler (`BackendModelParsers.kt`, around the `el.tag == "sentry"` branch) only reads text content out of `<condition>` / `<ifPart>` elements — so this structured shape yields `condition: null` in `graph.json` and the criterion renders in the Storyline as *"when 'X' completes"* without the *"and `${cond}` is true"* half. **Confirmed on a real project:** the Reimbursement stage in the "Umatched Case App" fixture had its entry-criterion condition silently dropped until the modeller re-authored it as a plain `${…}` expression by hand.

Fix shape (small, additive, no schema change): when the text-based lookup returns null, translate the structured block into an equivalent EL expression — e.g. `${triggerReimburesement == true}` for the block above, joined with `&&` / `||` based on the outer `match="all"|"any"`. Supported operators (equals, notEquals, greaterThan, lessThan, contains, …) map to their EL equivalents; unknown operators fall back to a human phrase (`"'X' <op> Y"`). Requires: new helper in the parser or (cleaner) in a small extraction step in `FlowTraversal` so the raw structured block stays in `graph.json` untouched. Also worth flagging in `meta.warnings` when a sentry has an unrecognised operator or an unsupported condition shape so translation gaps stay visible.

### CMMN — event listeners (timer / user / signal)
**Deferred by design.** Parser exposes `eventListeners: [{id, name, type, timer, eventType, signalRef}]` but `FlowTraversal` ignores this bucket entirely. A `<timerEventListener>` is essentially a trigger that fires an entry criterion; showing them alongside plan items would complete the "what starts this work" picture.

### CMMN — milestone participation in the narrative
**Deferred by design.** `CaseBody.milestones` is emitted but not rendered — the frontend shows an empty spot for it. Real cases use milestones as narrative anchors (*"Data verified — achieved when …"*); worth surfacing between plan-item groups.

### BPMN — complex gateway + compensation events
**Deferred by design.** Captured in the schema but not yet in the traversal. Low priority — complex gateways are rare; compensation is a specialty.

---

## Frontend

### Cross-references / click-through
**Deferred by design (Phase D scope).** Every step field that references another explorer node (`formKey`, `delegate.class` / `.delegateExpression`, `decisionRef`, `targetKey`, message/signal names) should be a clickable link that navigates to that node's detail card. Today those are all styled as `<code>` spans. Wiring is one JS event handler plus a resolver map from field values to `byId` keys.

### Mermaid diagram fallback
**Deferred by design.** Schema doc mentions Mermaid `flowchart` as a possible option for the Storyline view; we chose plain nested HTML instead (no dependency). If a diagram is later wanted for BPMN specifically (parallel/inclusive gateways read better graphically), Mermaid renders offline and is small enough to embed.

### `overview.md` / `CLAUDE.md` narrative integrations
**Deferred by design (follow-up scope from spec).** The spec's Q1 answer put the explorer first; markdown integrations were explicitly follow-ups. When we return to this, `OverviewRenderer.kt` gains a `## Business flow` section per startable entry point (deterministic templated prose from the FlowStory), and `ClaudeRenderer.kt` gains a reference to the same content so LLM agents pick it up from their primer.

### `SummaryRenderer.kt` one-liner per entry point
**Deferred by design.** A tiny one-liner per startable entry point in the summary (something like *"orderProcess — sales · 8 steps · 1 call activity"*) would help readers scan the compact summary. Low effort, low priority.

### Prose polish
**Surfaced during implementation.** The templated prose reads a bit stiff — *"The system runs …"* / *"Someone from the backoffice group handles this task"*. Two paths forward if it becomes an issue:
- Add small variations in phrasing across step kinds to reduce repetition.
- Add an opt-in LLM path: an external agent reads `flow.json` and rewrites into natural prose, keeping Atlas's zero-dep story intact. (This is the escape hatch the spec's "Key design tradeoff" already agreed to.)

---

## Testing & fixtures

### Real-project verification for CMMN criteria + sequencing
**Needs a real project.** The Phase C follow-up (entry criteria + topological sort + trigger prose) is fully coded, but the miniproject's `review.cmmn` has zero `<sentry>` elements — so no field of the new machinery is exercised by the golden test. Verification requires either:
- Pointing the CLI at a real Flowable project that uses sentries, or
- Adding a small `review-with-sentries.cmmn` fixture and a golden covering it.

### Real-project verification for gateway convergence
**Needs a real project.** The miniproject's `order.bpmn` is linear — no gateways at all. `walkFrom` handles exclusive/parallel/inclusive gateways and does simple visited-set convergence, but no golden exercises this path. A branching fixture (or a real project) would confirm.

### Real-project verification for call-activity inlining
**Needs a real project.** Miniproject has one call activity, but its target (`fulfilmentProcess`) isn't defined in the fixture, so it renders as unresolvable. A fixture with a call activity resolving to a real process would show the one-level inline working end-to-end from a BPMN entry point.

---

## Docs & housekeeping

### Pre-existing markdown golden drift
**Surfaced during implementation.** `miniproject.summary.md` and `miniproject.overview.md` were failing the golden test on the checkout at HEAD *before* any Phase A change (verified via `git stash`). Every phase since has had to refresh these goldens as a side-effect. Worth understanding why (was a renderer changed without a golden refresh at some point?), even if only to know whether to expect the drift to reappear on future upstream fetches.

### `.gitattributes` line-ending pain
**Surfaced during implementation.** `.gitattributes` (untracked at the start of this work) declares `* text=auto eol=lf`, but many goldens on disk were still CRLF from earlier checkouts. Each golden refresh has had to re-normalise. A one-shot repo-wide `git add --renormalize .` (with the user's authorisation) would end this permanently.

### FEATURES.md
**Not touched.** `FEATURES.md` lists the plugin's user-visible features up to 0.8.2 (per its own header). Once the Storyline view is considered ready to publicise, it belongs there — with the phrasing that emphasises the differentiators over Flowable Design (business narrative, cross-process end-to-end, LLM-consumable, offline).

### README.md
**Not touched.** README still says *"writes all five artifacts"* — should become *"writes all six"* now that `flow.json` is emitted alongside the existing set.
