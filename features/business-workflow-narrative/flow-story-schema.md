# FlowStory — display concept and JSON schema

Companion to `business-workflow-narrative-spec.md`. This document defines **how the narrative is displayed in `explorer.html`'s Storyline view** and then derives the **`FlowStory` JSON schema** from that display need. `FlowStory` is the payload that both gets embedded in `explorer.html` (Q1) and written to the standalone `<project>.flow.json` artifact (Q4).

## Guiding principle

The user must be able to see **the entire workflow with every branch visible at once**, without clicking through paths or picking scenarios. The overall business logic must be readable at a glance. This rules out any UI that reveals branches one path at a time (wizards, path-pickers) and pushes us toward a **fully-expanded tree view**.

## Display concept — Nested indented narrative

The Storyline view renders each `FlowStory` as a **single top-to-bottom nested list**. Every activity is a row; every branch nests under the gateway that spawned it; every branch is present simultaneously (no toggling). No diagram library needed — plain HTML + CSS carries the whole design.

### BPMN — worked example

```
Story: Order Approval  (process key=orderApproval)  •  started by: sales

  ▶  Start — "Order submitted"       [form: OrderForm]

  ▸  Validate order                   [service task]
       calls: OrderService.validate()

  ◆  Amount check                     [exclusive gateway]
     ├─ if  ${amount > 10000}
     │    ▸  Manager approves         [user task]  ← manager
     │         form: ApprovalForm
     │         ⏱  boundary (3d, interrupting): escalate to director
     │    ▸  Notify manager           [service task]
     │         calls: NotificationService.send()
     │
     └─ otherwise (default)
          ▸  Auto-approve             [service task]
               calls: OrderService.autoApprove()

  ▸  Ship order                       [service task]
       calls: ShippingService.ship()
       🔁 multi-instance: parallel, for each ${orderItems}

  ▶  End — "Order shipped"            [message: OrderShippedEvent]
```

Key visual affordances:

| Marker | Meaning                                             |
| ------ | --------------------------------------------------- |
| `▶`    | start / end event                                   |
| `▸`    | activity (userTask, serviceTask, script, rule, …)   |
| `◆`    | gateway (icon subtype indicates exclusive/parallel) |
| `⏱ ✉ 🔔` | boundary event (timer, message, signal)          |
| `🔁`    | multi-instance marker                               |
| `├─`   | branch alternative (exclusive gateway)              |
| `╠═`   | branch parallel (parallel gateway — thick, doubled) |
| `↳`    | inlined call-activity contents (one level, Q2)      |
| `→`    | reference to a deeper hop (not inlined — clickable) |

Everything with a source model (form, delegate class, called process, decision) is a **click target** that jumps to that item's existing explorer detail card — same interaction model the explorer already uses.

### BPMN — cross-process behaviour (Q2 = one level)

```
  ▸  Perform credit check           [call activity → CreditCheckProcess]
     ↳ inlined:
       ▶  Start
       ▸  Query bureau              [service task]
       ▸  Score decision            [rule task → creditScoring]
              → deeper call: AuditProcess  (3 more steps · click to open)
       ▶  End
```

The **deeper hop** is a leaf reference — clicking it navigates the Storyline view to that entry point's own narrative if it's startable, or to its explorer card otherwise.

### CMMN — worked example

Cases aren't sequential, so the Storyline view for a case does **not** render a top-to-bottom flow. Instead it renders **stages → plan items** with each plan item's entry/exit criteria stated inline. The "everything visible at once" principle stays; only the structural metaphor changes.

```
Story: Customer Onboarding  (case key=customerOnboarding)  •  started by: sales

  ▣  Stage — Data Collection
       ▸  Collect customer info     [human task]  ← onboarding-team
             form: CustomerForm
             entry: (auto-active on stage entry)
       ▸  Verify identity           [process task → IdVerificationProcess]
             entry: when 'Collect customer info' completes

  ●  Milestone — Data verified
       achieved: when all plan items in 'Data Collection' complete

  ▣  Stage — Contract Signing            [manual activation]
       entry: when 'Data verified' achieved
       ▸  Send contract              [service task]
       ▸  Await signature            [user task]  ← customer
```

Markers used for CMMN in addition to the BPMN set:

| Marker | Meaning                                         |
| ------ | ----------------------------------------------- |
| `▣`    | stage                                           |
| `●`    | milestone                                       |

### Convergence and joins

When branches of an exclusive gateway converge back onto a shared step, the shared step **is not duplicated** into each branch — it appears **after** the gateway in the parent scope. The tree literally represents scoping: whatever is unique to a branch is inside the branch; everything after the gateway is in the enclosing scope. See the "Ship order" step in the BPMN example.

If a branch never converges (e.g. it hits its own End event), it simply terminates and the enclosing scope continues from any other branches that do converge. Warnings are emitted (see `meta.warnings`) if the traversal detects unresolvable structural cases.

---

## JSON schema (v1)

The schema is described in TypeScript syntax for readability; the on-wire format is plain JSON. Discriminated unions use a `kind` field. Fields marked `?` are optional and omitted when absent (never `null` unless explicitly typed with `| null`).

### Top level

```ts
type FlowStoryFile = {
  version: 1
  project: string
  stories: FlowStory[]
}

type FlowStory = {
  kind: "process" | "case"       // top-level discriminator
  key: string                    // BPMN process id / CMMN case id
  name: string | null            // human name from name= attribute
  file: string                   // model file path, relative to project root
  startedBy: {
    groups: string[]             // candidate starter groups
    users: string[]              // candidate starter users
    unrestricted: boolean        // true when both arrays are empty
  }
  body: ProcessBody | CaseBody   // shape follows `kind`
  meta: {
    warnings: string[]           // e.g. "cycle detected at gateway_5", "unresolved call target 'foo'"
    truncated: boolean           // true if any deeper hops were omitted
  }
}

type ProcessBody = {
  kind: "process"
  steps: Step[]                  // ordered walk from start to end
}

type CaseBody = {
  kind: "case"
  planItems: CmmnPlanItem[]      // stages + non-stage plan items, in model order
  milestones: CmmnMilestone[]    // case-level milestones
}
```

### Step — BPMN (polymorphic)

```ts
type Step =
  | StartStep
  | EndStep
  | ActivityStep
  | GatewayStep
  | CallStep
  | SubProcessStep
  | EventStep

type StepBase = {
  id: string                     // element id in the model
  name: string | null
  sourceRef?: { file: string; line?: number }   // click-through target
  incoming?: { fromBranch: string }             // populated on the first step of a branch when useful; usually omitted
}

type StartStep = StepBase & {
  kind: "start"
  trigger: "none" | "message" | "timer" | "signal" | "conditional" | "error"
  triggerRef?: string            // message name, timer expression, signal name
  formKey?: string
}

type EndStep = StepBase & {
  kind: "end"
  reason: "none" | "message" | "signal" | "error" | "escalation" | "cancel" | "terminate"
  reasonRef?: string
}

type ActivityStep = StepBase & {
  kind:
    | "userTask"
    | "serviceTask"
    | "scriptTask"
    | "businessRuleTask"
    | "manualTask"
    | "receiveTask"
    | "sendTask"

  // Actor (userTask)
  assignee?: string
  candidateGroups?: string[]
  candidateUsers?: string[]
  formKey?: string

  // Delegate (serviceTask / sendTask)
  delegate?: {
    class?: string
    expression?: string
    delegateExpression?: string
    type?: string                // "mail", "camel", "rest", ...
    resultVariable?: string
  }

  // Business rule
  decisionRef?: string

  // Script
  script?: { language: string; resultVariable?: string }

  // Multi-instance marker (any activity)
  multiInstance?: {
    kind: "sequential" | "parallel"
    collection?: string
    elementVariable?: string
    completionCondition?: string
  }

  // Attached boundary events
  boundaryEvents?: BoundaryEvent[]
}

type BoundaryEvent = {
  id: string
  name: string | null
  eventType: "timer" | "message" | "signal" | "error" | "escalation" | "compensate" | "conditional" | "cancel"
  interrupting: boolean
  spec?: string                  // timer expression, message name, signal name, error code
  routesTo: Step[]               // the continuation triggered by this boundary; inlined
}

type GatewayStep = StepBase & {
  kind: "gateway"
  gatewayKind: "exclusive" | "inclusive" | "parallel" | "eventBased" | "complex"
  branches: Branch[]
}

type Branch = {
  condition: string | null       // null iff isDefault (Flowable rule — see spec)
  isDefault: boolean
  steps: Step[]                  // steps unique to this branch; ends where the branch converges
}

type CallStep = StepBase & {
  kind: "call"
  callType: "callActivity" | "signalThrow" | "messageThrow"
  targetKey: string              // process/case/decision key
  targetName?: string
  targetKind: "process" | "case" | "decision" | "external" | "unresolved"
  inline:
    | { resolved: true; steps: Step[] }
    | { resolved: false; reason: "deeper-than-one-level" | "unresolvable" | "external"; stepCount?: number }
  inputMappings?: Array<{ source: string; target: string }>
  outputMappings?: Array<{ source: string; target: string }>
}

type SubProcessStep = StepBase & {
  kind: "subProcess"
  subKind: "embedded" | "event" | "transaction" | "adhoc"
  steps: Step[]                  // always inlined; embedded sub-processes live in the same file
  triggeredBy?: BoundaryEvent["eventType"]   // for event sub-processes
}

type EventStep = StepBase & {
  kind: "event"
  eventKind: "intermediateCatch" | "intermediateThrow"
  eventType: "message" | "signal" | "timer" | "error" | "escalation" | "compensate" | "conditional" | "link"
  spec?: string
}
```

### CMMN

```ts
type CmmnPlanItem = StepBase & {
  kind: "planItem"
  planItemType:
    | "humanTask"
    | "processTask"
    | "caseTask"
    | "decisionTask"
    | "stage"
    | "milestone"
    | "userEventListener"
    | "timerEventListener"

  // Rules
  manualActivation: boolean
  repetitionRule?: string
  entryCriteria?: string[]       // sentry descriptions ("when 'Verify identity' completes")
  exitCriteria?: string[]

  // Stage-only
  stage?: {
    planItems: CmmnPlanItem[]
    milestones: CmmnMilestone[]
  }

  // Task-kind data (mirrors ActivityStep fields when relevant)
  formKey?: string
  assignee?: string
  candidateGroups?: string[]
  candidateUsers?: string[]

  // Reference to an external model
  targetKey?: string             // for processTask / caseTask / decisionTask
  targetKind?: "process" | "case" | "decision"
}

type CmmnMilestone = {
  id: string
  name: string | null
  sourceRef?: { file: string; line?: number }
  entryCriteria?: string[]
}
```

## What's deferred (post-v1)

Deliberately excluded from the v1 schema to keep it small and shippable:

- **Complex gateways** — modelled as `gatewayKind: "complex"` but no branch-condition semantics beyond what's already parsed.
- **Compensation events** — captured as `eventType: "compensate"` but no compensation-handler linking.
- **Multi-hop inlining** — `inline.resolved: false` is always emitted at hop 2+; changing this is a schema-compatible follow-up (bump `stepCount`, add nested `steps`).
- **Loop / back-edge modelling** — if a sequence flow forms a cycle at the same nesting level, the traversal breaks it, emits a `meta.warnings` entry, and inserts a `ReferenceStep`-style marker. Full loop modelling is deferred.
- **CMMN sentry expression detail** — v1 renders sentries as human-readable strings; the underlying `onPart` / `ifPart` structure isn't exposed until a consumer needs it.
- **Data variables flowing between steps** — the current graph doesn't track variable read/write per step, so `dataIn` / `dataOut` from the earlier schema sketch are dropped for v1. Adding them later is additive.

## Rendering notes for `explorer.js`

The Storyline view is a pure function of `FlowStory`. Key rendering rules:

1. Iterate `body.steps` (BPMN) or `body.planItems` (CMMN), recursing into `branches[].steps`, `boundaryEvents[].routesTo`, `inline.steps`, and `stage.planItems` respectively.
2. Indentation depth = nesting depth. Left border color varies by scope kind (gateway branch / parallel branch / call-activity inline / sub-process / stage).
3. Every step with a `sourceRef` renders its name as a click target that dispatches to the explorer's existing detail-panel handler.
4. Every step referencing another model (`targetKey`, `delegate.class`, `formKey`, `decisionRef`, boundary event `spec` if it's a message/signal, …) renders that reference as a click target to the corresponding explorer node.
5. `meta.warnings` renders as a small banner at the top of the story.
6. If `body` doesn't match `kind`, that's a schema-invariant violation — surface it clearly (developer feedback, not silent failure).

## Sizing sanity check

For the miniproject fixture (currently a single process with two branches and one service call), `FlowStory` should serialise to roughly **1–3 KB** as pretty JSON. For a realistic mid-size project with ~30 startable entry points, `flow.json` should stay well under **500 KB**, which keeps `explorer.html`'s embed size in the same order of magnitude as today's `graph.json` embed.
