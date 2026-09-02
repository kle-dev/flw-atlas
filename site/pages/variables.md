# Variable analysis

"This variable is never used" is an easy claim to make and a hard one to make *trustworthy*. In a
Flowable project a variable can be written by an in-parameter, read by a form field, passed through a
DMN output and consumed by a script — across four file formats and two expression dialects. Miss one
of those paths and the report starts crying wolf, at which point nobody reads it.

So Atlas does two things. It records **where every name is written and where it is read**, with the
construct and element for each. And it only delivers a verdict when it has seen a write and nothing
that could possibly be a read — then it states, out loud, how many names it declined to judge.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/variables-page.png" alt="The explorer's Unused variables page, showing the two verdict groups and the silence rules" width="1400" height="900"><img class="only-dark" src="../assets/img/variables-page-dark.png" alt="The explorer's Unused variables page, showing the two verdict groups and the silence rules" width="1400" height="900"></div>
  <figcaption><b>The Unused variables page.</b> The two verdict groups, a filter chip per write
  construct, and — at the bottom — what Atlas admits it cannot see.
  <a href="../demo/explorer.html#/variables" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## Reads and writes

Every variable node carries `writes` and `reads`: one entry per site, naming the model, the construct
(`via`), and the element where it happened. `writeCount` and `readCount` are the true totals; the
listed entries are capped at 25 per direction, because the whole list travels inside the generated
HTML page.

The sites come from everywhere a name can appear:

| Source | Counts as |
|---|---|
| `${…}` expressions, and `${variables:get('x')}`-style calls | read |
| `{{…}}` form bindings | read |
| In / out parameters, on both sides of the mapping, with the scope each belongs to | write and read |
| `resultVariable`, `outputVariableName`, output and error-output parameters | write |
| Event in / out parameters, variable mappings, signal variables | write |
| Response and error-response payload mappings | write |
| Script API calls, with the verb that decides direction | write or read |
| DMN inputs and outputs | read and write |
| Form fields, form properties, form outcomes | write |
| Data objects, multi-instance elements and collections, the initiator, variable extractors | write |
| Java `setVariable` / `getVariable` calls | write and read |

Three kinds of name are dropped before they ever become a site: beans, Flowable's own context roots,
and Java string literals. That last one matters specifically so a Java `setVariable("…", …)` does not
get reported as a write nothing reads, when the reader is the engine.

Identical sites collapse, so a variable written twice from the same element in the same way is one row,
not two.

A deployment file may hold several processes (or cases, or decisions). Each of them is credited only
with what stands inside its own element — its expressions, its bean calls, its declared and mapped
variables — and only what sits outside every element, the definitions header with its messages and
signals, belongs to all of them. Crediting the whole file to each model would make a variable written
in one process and read in the next look like a read and a write in both.

## The two verdicts

The check is deliberately lopsided. A **proven** read counts. A **suspected** read counts. A construct
whose direction Atlas cannot determine counts as a read too. Only what survives all of that is
reported.

**Written, never read** (`unusedVars`) — a write exists, and nothing anywhere reads the name. The
remedy is to delete the variable. The finding names up to three write sites in Flowable Design's own
wording, so you can go and find them.

**Mapped into a model that never reads it** (`unreadInputs`) — the name *is* read somewhere, but not in
the scope the value was written into. Atlas follows up to two model hops from the write's scope to
decide this. The remedy is to delete the mapping, not the variable.

A third group appears on the explorer's page but is not a finding: **declared — readers live outside
the models**, where the value legitimately leaves the project.

## What Atlas cannot see

These eight cases silence the check. When one of them applies, the variable is not reported and is
counted instead as one Atlas *declined to judge* — the number quoted next to the findings, which is
what makes the names it does report worth acting on.

1. A construct whose direction Flowable does not fix (a variable listener, `hasVariable`).
2. A value whose consumer is outside the models — an action's response payload, an extracted variable
   a query indexes, a form's outcome variable the task list shows, a loop counter.
3. A name written into a container object, where only field-level reads would show it is used.
4. A bare-EL Init-Variables value, which Atlas has no parser for in that position.
5. A name the `{{…}}` harvester deliberately ignores, so its frontend reads were never collected.
6. A name passed as a string literal somewhere, which may be a variable lookup Atlas cannot parse.
7. Any scope whose script or Java code reads the whole variable map at once.
8. A mapping into a called model that is not part of this project.

Rule 7 is the broadest and the most important: one `execution.getVariables()` in a delegate, or one
script that iterates the whole map, and every variable in that scope becomes unjudgeable. That is the
correct answer — the code genuinely might read any of them — and it is better than guessing.

Beyond the eight, two whole areas are out of scope by design: query, dashboard and master-data models
are not analysed for variable references, and the Work UI (or any REST client) is outside the project
Atlas can see.

## Where to read it

The explorer's **Unused variables** page (`#/variables`) is the place this analysis is meant to be
read. It shows the two verdict groups, a filter bar with a chip per write construct, the
declined-to-judge count, and the eight rules above rendered from the same list the engine holds — so
the page cannot describe a policy the code does not implement.

Each variable's own detail panel carries the full `writes` / `reads` table, which is where a
"never read" claim is checked rather than believed.

In the IDE the same data appears in the Atlas Hub's *Unused variables* tab, and in the artifacts as
the `unusedVars`, `unreadInputs` and `guessedVars` [checks](../checks/).
