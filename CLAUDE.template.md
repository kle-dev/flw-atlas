# CLAUDE.md — Flowable solution project (context for AI agents)

_Generic Flowable primer for AI agents. **Prefer the generated version:** `atlas <project-dir>` writes `<project>.CLAUDE.md` — this same primer with §4 filled in from the actual project (apps, inventory, where models/Java live, conventions, wiring examples to mirror). Use this file only when you cannot run Atlas; then copy it to the project root as `CLAUDE.md` (or `AGENTS.md`) and fill in §4 by hand._

## 0. Understand this project — start here

Don't guess — build the picture in order:
1. Run **Flowable Atlas** on the project (`atlas <project-dir>`). It writes, next to each other:
   `<project>.summary.md` (compact orientation), `<project>.overview.md` (the full report),
   `<project>.graph.json` (the traversable model↔code graph) and `<project>.explorer.html`.
2. Read the **summary** — apps, inventory, entry points, integrations, hotspots, known issues.
3. Query the **graph** for specific questions (what calls X, who uses variable Y, which controller
   serves form Z) instead of reading everything.
4. Read the actual source to verify, then implement.

## 1. What Flowable is (the mental model an LLM usually gets wrong)

Flowable is a Java process-automation platform. A solution project is custom Java + models that run
**on top of** the Flowable engines — you extend a platform, you don't build from scratch.

- **Work** = the **runtime *and* the end-user React frontend** (executes definitions, renders forms,
  hosts tasks/cases). Custom Java + REST controllers run here. **Design** = the visual modeler.
  (Control/Hub = admin consoles; Engage = the conversational/omnichannel layer, often unused.)
  Engines: BPMN (processes), CMMN (cases), DMN (decisions), Form, Content, IDM (users/groups), plus
  platform engines (data objects, actions, agents, indexing).

**Models vs Definitions (the key concept):** models are mutable design-time JSON; when deployed they
become **immutable, versioned Definitions**. Everything is referenced by **key**
(process/case/form/decision key) — cross-references between models, from Java, and from the frontend are
all by key.

```
DESIGN (models, editable JSON)              WORK (definitions, deployed & IMMUTABLE)
  App (package model) ── publish/export ──►   Deployment
    ├─ BPMN / CMMN / DMN / Form model ────►     Process/Case/Decision/Form definition (versioned)
    └─ data object / service / query / … ─►     platform definitions
```

Where state lives in the DB (rarely touched directly — use the engine services/APIs): `ACT_RU_*`
runtime, `ACT_HI_*` history, `ACT_RE_*` deployed definitions, `ACT_ID_*` identity, `ACT_DE_*` Design
models.

**In a solution project, models are authored in Design and *exported into this repo*** — the `.app`/`.zip`
and model files under `src/main/resources` are **exported build artifacts**, not the editing surface.
The Java app is built **together with** the bundled model and deploys it to Work on startup. The canonical
place to *change* a model is Flowable **Design**, then re-export.

## 2. How custom code attaches to models (extension points)

- **Service tasks / JavaDelegate** — `flowable:class="com.acme.X"` or `flowable:delegateExpression="${bean}"` (a Spring `@Component`/`@Service`).
- **Expressions** — `${bean.method(args)}` (backend, JUEL) in conditions/listeners/fields; `{{ ... }}` (frontend) in forms/pages.
- **Listeners** — Execution/Task/PlanItemLifecycle/CaseInstanceLifecycle/FlowableEventListener.
- **REST controllers** — `@RestController` endpoints the Work frontend (forms, data tables, buttons) calls.
- **Bots** (`BotService`) — invoked by **Actions** (`.action` models, via `botKey`).
- **Service-registry data objects** — `.data` backed by a `.service` (REST/DB); DB-backed ones map to
  **Liquibase** tables via `referencedLiquibaseModelKey` + `tableName`.
- **Forms** — bind fields to **variables**; outcomes drive flow; can call REST for options/data tables.
- **Queries** (`.query`) — index queries (tasks/case-instances/…), often gated by **user group**
  (`currentGroups?seq_contains(…)`).
- **Variables** — set in Java (`execution.setVariable(...)`), init-var mappings, in/out params, sequences; read in expressions.
- **Access** — candidate (starter) groups, task candidate groups/assignees, app/page permissions, security policies.

## 3. How models, code and deployment fit together (important — easy to get wrong)

- **Models are authored in Design, not here.** A modeler builds BPMN/CMMN/forms/etc. in Flowable
  **Design**, then **exports/publishes the app into this repo**. Treat the repo's model files as
  exported artifacts — don't hand-edit the deployed `.zip` as if you own it; model changes normally
  go back through Design and are re-exported.
- **Build = your Java + the bundled model, together.** The Maven build packages the custom Java **and**
  the exported app; on deploy/startup the app **auto-deploys** its definitions to Work.
- **Deploy via the built artifact, environment by environment** (dev → test → **prod**, via CI/CD). You
  typically do **not** publish from Design straight to Production — Design-publish is a dev-time
  convenience; production receives the built-and-deployed app.

**Your lane as an agent:** implement/adjust the **custom Java** (delegates, beans, listeners, REST
controllers, bots) to match the models, and **read** the models to understand the wiring. If a feature
needs a model change (new task/form/variable/decision), **say so explicitly and describe it** — it's
made in Design and re-exported, unless this project's convention is to edit the model files directly
(check existing commits/patterns first). Always mirror an existing similar case — find it via Atlas.

## 4. This project — `<!-- FILL IN -->`

> Atlas auto-fills all of this. Generate `<project>.CLAUDE.md` instead of hand-filling it.

- **Repo layout:** `<!-- where models live, where custom Java lives, where the frontend lives -->`
- **Key/naming conventions:** `<!-- e.g. processes ABC-P###, cases ABC-C###, forms ABC-F### -->`
- **Build/test:** `<!-- e.g. ./mvnw clean install -DskipTests -T 1C  /  ./mvnw test -pl <module> -am -->`
- **Run & verify:** `<!-- how to start it; the app auto-deploys its bundled models on startup -->`
- **Flowable version:** `<!-- matters: available APIs differ across versions -->`
- **Wiring examples to mirror:** `<!-- one real delegate, listener, bot, form→REST call -->`
- **House rules:** `<!-- code style, where business logic goes, what NOT to touch -->`

## 5. Rules for the agent

- **Understand before coding:** summary → graph → source. State which existing process/case/form/bean your feature builds on.
- **Verify, don't hallucinate:** Flowable APIs differ across versions — confirm class/method names against the actual dependencies and https://documentation.flowable.com; don't invent engine APIs.
- **Match model ↔ code:** a `delegateExpression`/`flowable:class` in a model needs the bean/class to exist (and vice-versa). The graph's unresolved references show mismatches.
- **Keys are contracts:** models/Java/frontend reference definitions by key. Before renaming a key, check the graph for who references it (both directions).
- **Respect access/security:** candidate groups, app/page permissions and security policies are part of the feature.
- **Minimal, consistent changes:** mirror existing patterns; touch only what's necessary.

**Common Flowable pitfalls (the knowledge gap):** don't invent engine APIs (verify against deps/docs);
don't hand-edit the exported app `.zip` (model changes go via Design); mind variable scope
(`setVariable` vs `setVariableLocal`); never rename a definition `key` without checking who references it
(both directions); don't forget candidate groups / access on new tasks & pages.
