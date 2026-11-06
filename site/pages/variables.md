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
| `{{…}}` form bindings — every name in one, not just the first: `{{flw.formatDate(due, 'DD')}}` reads `due`, `{{a && b}}` reads both, `{{$payload.x}}` reads `x`. A name past the first counts only for a variable something else already names, so a lambda's parameter or a bare word never becomes one | read |
| `${root.x}`, `${parent.x}` and `${self.x}` in an expression, `{{root.x}}` and `{{parent.x}}` in a binding — the token says whose `x`. `id`, `name`, `businessKey`, `startTime` and the instance's other own fields are no variable there | read |
| In / out parameters, on both sides of the mapping, with the scope each belongs to | write and read |
| `resultVariable` / `resultVariableName` — either spelling the engine accepts, on BPMN service and script tasks and on CMMN Java and script tasks — `outputVariableName`, output and error-output parameters | write |
| Event in / out parameters, variable mappings, signal variables | write |
| Design's data-import column mappings and additional variables (`design:variablemapping`, `design:additionalvariables`), its report and output variable names | write |
| Response and error-response payload mappings — unless the button stores its response under its own `{{…}}` binding, in which case the mapping names are properties of that value: the binding's root is the write, and a `$temp` root is form-local | write |
| Script API calls, with the verb that decides direction | write or read |
| DMN inputs and outputs — a table with several results (rule order, output order, collect without an aggregation) writes one variable named after the decision, the list of matched rows, and no output of its own. Outputs are writes only where a process, a case or a decision service one of them calls runs the table: Java that executes it gets them back in a result map | read and write |
| Multi-instance variable aggregations — each `<variable source>` is read from every instance, and the aggregation's `target` is written | read and write |
| A query's literal `"variables.name": "x"` filter and its columns' `variableName` — of a path such as `customer.name`, its root | read |
| Form fields, form properties, form outcomes | write |
| Data objects, multi-instance elements and collections, the initiator, variable extractors | write |
| Java `setVariable` / `getVariable` calls — in production sources only; `src/test/**` and every other test source set are not scanned | write and read |

Three kinds of name are dropped before they ever become a site: beans, Flowable's own context roots,
and Java string literals. That last one matters specifically so a Java `setVariable("…", …)` does not
get reported as a write nothing reads, when the reader is the engine.

Neither is anything that names another scope than a process or case variable:

- a `{{$…}}` root — `$currentUser`, `$searchText`, `$route`, `$errors` and the rest belong to the forms
  runtime;
- a `${x}` or `{{x}}` in a service, a query or an agent, which is a parameter of the call it defines —
  an operation's input, a query's request parameter, an agent operation's input;
- an input or output parameter's `name`, which is the callee's field (its `value` side is the variable);
- `flw.getInput('x')` / `flw.setOutput('x', …)` in an action bot's script, the action's payload — in a
  script task the same calls do read and write variables;
- the id of a component that binds no value of its own — a data table, an HTML component, a work list;
- a field of a form that is only ever embedded through a bound subform: under `{{address}}` its `street`
  is `address.street`;
- an EL function's namespace (`json:object()`), a lambda's parameter, a script's own locals, the text
  of a script's strings — a Groovy `"""…"""` included — and the packages it imports, and in a template a
  `<#list … as x>` or `<#assign x>` local and a `?built_in`;
- a bean a script names bare — `demoLookupBean.createTaskQuery()`, Spring Boot's
  `springProcessEngineConfiguration` — when nothing but that mention backs the name;
- in Java, a Spring `@Value("${…}")` placeholder and any SpEL `#{…}`, and in a channel a SpEL `#{…}`;
- in Kotlin, a string template — `"Order total ${total}"` is filled in by the compiler, and only the
  escaped spelling of an expression (`"\${vars:get(flag)}"`, or `${'$'}{…}` in a raw string) is read.

A name several models use is only the same variable where a flow connects them. The explorer's
*Also in* and *Read by* columns list those models; the same name in a model nothing connects to is
counted apart as *unrelated*, because at run time it is another variable.

A name is a bean only when something says so: the platform declares it, Java declares it — a stereotype
on a class (`@Service`, `@Component`, `@Configuration`, …), a `@Bean` method, a Spring Data repository —
a delegate expression names it bare (`${notifier}`), or it is named the way Spring beans are named —
`orderService`, `pdfGeneratorTask`, `flwTimeUtils`. A class that merely shares the name does not make one:
`${customer.getName()}` beside a plain `Customer` class reads the variable `customer`. A method call on any other name is a **read of that variable**:
`${issue.asText()}`, `${attachments.size()}`, `${requesterData.getName()}` read `issue`, `attachments`
and `requesterData`. Before this rule every `${x.method()}` root was a bean, which took 25 variables on
four real projects out of the variable graph and listed them as beans nobody could find.

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
decide this. A called model reading `${root.x}` or `{{parent.x}}` reads the root's or the caller's `x`,
not the copy mapped into it, so that read does not count here. The remedy is to delete the mapping, not
the variable.

A third group appears on the explorer's page but is not a finding: **declared — readers live outside
the models**, where the value legitimately leaves the project.

## What Atlas cannot see

These eight cases silence the check. When one of them applies, the variable is not reported and is
counted instead as one Atlas *declined to judge* — the number quoted next to the findings, which is
what makes the names it does report worth acting on.

1. A construct whose direction Flowable does not fix (a variable listener, `hasVariable`).
2. A value whose consumer is outside the models — an action's response payload, an extracted variable
   a query indexes, a form's outcome variable the task list shows, a loop counter, a decision output
   only Java code receives.
3. A name written into a container object, where only field-level reads would show it is used.
4. A bare-EL Init-Variables value, which Atlas has no parser for in that position.
5. A name the `{{…}}` harvester deliberately ignores, so its frontend reads were never collected.
6. A name passed as a string literal somewhere, which may be a variable lookup Atlas cannot parse.
7. Any scope whose script or Java code reads the whole variable map at once, or that renders a template against its whole variable container.
8. A mapping into a called model that is not part of this project.

Rule 7 is the broadest and the most important: one `execution.getVariables()` in a delegate, or one
script that iterates the whole map, and every variable in that scope becomes unjudgeable. That is the
correct answer — the code genuinely might read any of them — and it is better than guessing.

A decision table whose outputs nothing in the models receives is not judged either way: where only Java
runs it, its outputs are counted under rule 2, and where nothing runs it at all, the
[`unusedDecisions`](../checks/) check reports the table itself.

Beyond the eight, two whole areas are out of scope by design: dashboard and master-data models, and a
query apart from the variables it names literally, are not analysed for variable references (a query's
`${…}` is its request parameter, see above), and the Work UI (or any REST client) is outside the project
Atlas can see.

## Where to read it

The explorer's **Unused variables** page (`#/variables`) is the place this analysis is meant to be
read. It shows the two verdict groups, a filter bar with a chip per write construct, the
declined-to-judge count, and the eight rules above rendered from the same list the engine holds — so
the page cannot describe a policy the code does not implement.

Each variable's own detail panel carries the full `writes` / `reads` table, which is where a
"never read" claim is checked rather than believed.

In the IDE the same findings are in the [Atlas Findings](../plugin/#atlas-findings) tool window under
*Advice* (switch it on with the eye → *Show Advice*), and the *Unused variables* page is in the
explorer the IDE opens as an editor tab. In the artifacts they are the `unusedVars`, `unreadInputs` and
`guessedVars` [checks](../checks/).
