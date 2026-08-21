---
description: Generate a business-workflow narrative from a flow.json file using the ai-narrator prompt
argument-hint: <path-to-flow.json>
---

Produce a business-workflow narrative from a Flowable Atlas `flow.json` file.

Instructions:

1. Read `features/business-workflow-narrative/ai-narrator/prompt.md` — this file is your **system prompt** for this task. Follow every rule in it (input contract, output shape, ground rules, style targets, and worked examples) as if it were your top-level instructions. Do not deviate from its ground rules — especially "never invent" and "names > ids > kind fallbacks".

2. Read the `flow.json` at the path passed as an argument: `$ARGUMENTS`

3. Produce a business narrative for **every entry in `stories[]`**, following the recipe in the prompt (each narrative is the same `## <name>` section the prompt describes).

4. **Save one file per story** to `<flow-json-dir>/narrations/<storyKey>.narration.md`, where:
   - `<flow-json-dir>` is the directory containing the input `flow.json`
   - `<storyKey>` is the story's `key` field (e.g. `CDM-C001`)
   - Create the `narrations/` subfolder if it doesn't exist
   - Overwrite each file if it already exists — a narration always reflects the current `flow.json` content
   - Each file contains **only that one story's narrative** (its own `## <name>` section, including any `### Notes` subsection when the story's `meta.warnings` are non-empty)

5. **Also output every narrative directly in your response**, so they're visible in the conversation. Do **not** wrap them in code blocks, do **not** add preambles like "Here is the narrative:". After the last narrative, list the saved files as bullets (one `Saved: <path>` line per story).

Allowed tools: `Read`, `Write`, `Bash` (only `mkdir -p` for creating the `narrations/` subfolder). Do not run tests, commits, or other operations. Do not offer to iterate the prompt — this command's only job is to produce prose from the input.
