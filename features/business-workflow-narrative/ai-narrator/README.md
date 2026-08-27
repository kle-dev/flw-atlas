# AI narrator — prototype

The polish layer of the business-workflow-narrative feature. The Atlas CLI produces `<project>.flow.json` (the structured backbone); this folder holds the LLM prompt that turns a `flow.json` into a human-readable business narrative.

This is a **prototype for later extraction.** The intended home is a standalone agent skill (Claude Agent SDK, or an equivalent), not Atlas itself — Atlas's deliberate design decision is to stay offline / dependency-free, so the LLM never runs inside the CLI. Keeping the prototype here for now so the prompt sits next to the schema (`../flow-story-schema.md`) and the spec (`../business-workflow-narrative-spec.md`) that define what it consumes.

## Files

- **`prompt.md`** — the system prompt for the narrator agent. Edit this to iterate.
- **`examples/`** — paired input/expected pairs used as an informal test corpus while tuning the prompt:
  - `*.flow.json` — real output from the Atlas CLI on a project.
  - `*.expected.md` — the ideal narrative for that project, hand-authored, used as the target to compare LLM output against.

## How to run it today

### In Claude Code (this repo)

A project-scoped slash command wraps the prototype so it can be invoked directly:

```
/narrate <path-to-flow.json>
```

The command is defined at `.claude/commands/narrate.md`; it loads `prompt.md` as its system prompt and reads the given `flow.json`. Useful for iterating against the paired `examples/*.expected.md` without paste-shuffling.

### In any other LLM

Manually, in a chat with any capable LLM:

1. Paste the contents of `prompt.md` as the system prompt.
2. Paste one of `examples/*.flow.json` as the user message.
3. Compare the LLM's output to the paired `.expected.md`.
4. Iterate on `prompt.md`.

Once the prompt reliably produces something close to the expected output on both simple and complex examples, lift the folder out as a proper agent skill.

## When to extract

Extract to a standalone project / skill when at least one of these is true:

- The prompt runs cleanly on ≥ 3 varied real projects (BPMN-linear, BPMN-branching, CMMN-with-sentries).
- The examples corpus has grown enough that iteration inside this folder gets noisy.
- Someone else wants to plug into it independently of Atlas.

At that point: `cp -r ai-narrator/ /path/to/new-skill/` and adjust the README to describe the new location and the input contract (which stays: a `flow.json` matching `../flow-story-schema.md`).

## Ground rule for iteration

The prompt's job is **polish, not invent**. Every fact in the output must come from the input `flow.json`. If a field is missing, the narrative either omits that detail or says *"(not captured in the model)"* — never guesses. That's what keeps the AI layer trustworthy for business users who can't verify it against the BPMN diagram themselves.
