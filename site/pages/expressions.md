# Expressions & scripts

A Flowable expression is a string. Nothing compiles it, nothing type-checks it, and a typo in one
surfaces at runtime as a failed instance rather than as a build error. Atlas treats both expression
dialects as real languages: it parses them, validates them against a catalog of what actually exists,
and — in the IDE — highlights, completes and documents them.

## Two dialects

| | Backend | Frontend |
|---|---|---|
| Written as | `${…}` and `#{…}` | `{{…}}` |
| Where | BPMN / CMMN / DMN models, and `.form` string values | `.form` models |
| Functions | `namespace:name(args)` | `flw.name(args)`, including nested members like `flw.time.now()` |
| Pipes | — | `\|>` allowed |
| Resolves against | Process and case variables, engine root objects, Spring beans | The form payload |

The distinction matters because the same-looking string means different things in the two places, and
using one dialect's syntax in the other is a genuine bug. Atlas flags it: backend function syntax in a
frontend expression is reported as *dialect misuse*.

## Three layers of validation

**1 · Syntax** — the expression is parsed by a real per-dialect parser. A structural error (an unclosed
bracket, an unterminated string, a stray operator) is always an **error**, and can never be suppressed:
it cannot evaluate at runtime, so there is nothing to discuss. An empty interpolation (`{{}}`, `${}`) is
a runtime no-op and is not reported.

**2 · Dialect operators** — a `|>` pipe inside a backend expression is a warning.

**3 · The function catalog** — every function call is checked against what exists:

- an unknown backend namespace (`Unknown function namespace 'p'`);
- a known namespace with an unknown function (`Unknown function 'p:n'`);
- backend syntax in a frontend expression, with the `flw.` form suggested;
- an unknown `flw.` member, or an unknown member of one of your own namespaces — but **only when there
  is a plausible near-match**. A member with no near-match is assumed to be a function your project
  injected, and is left alone.

Bare identifiers are never flagged. They are almost always process variables or form fields, and
guessing there would produce noise instead of findings.

## Invalid vs suspect

The split is made per *expression*, not per problem, and it is the difference between "this is broken"
and "I could not vouch for this":

- **invalid** (`invalidExpr`) — at least one problem on the expression is an error. It will fail.
- **suspect** (`suspectExpr`) — the expression parses; only catalog warnings remain. It may well be
  fine, and it is worth a look.

The explorer keeps them as two separate review lists for exactly that reason.

## The allowlist

If your project registers its own expression functions, Atlas does not know about them and will report
them as suspect. Tell it:

```bash
java -jar cli-<version>-all.jar <project> --expr-allowlist myfns,util:format,flw.custom
```

An entry suppresses findings whose subject matches it verbatim, **or** whose namespace prefix matches —
so `myfns` covers `myfns:anything`. Only three finding kinds carry a subject and can be suppressed at
all: unknown namespace, unknown function, unknown grounding root. **Syntax errors and dialect misuse
can never be allowlisted**, which is deliberate: the allowlist is for teaching Atlas your vocabulary,
not for silencing real defects.

In the IDE the same list lives in *Settings → Tools → Flowable Atlas → Expressions* as an editable
table, and the Alt-Enter quick fix on an unknown-function warning writes into it.

Atlas also **discovers** frontend custom functions automatically by scanning your customisation sources
for `additionalData`. `--custom-functions <path>` points that scan somewhere specific;
`--no-custom-functions` turns it off.

## What is deliberately not validated

Expressions used only by `query`, `template` and `document` models are **Freemarker**, not JUEL. They
are not validated at all — validating them against the wrong grammar would produce nothing but false
positives.

Atlas also declines to judge an expression whose harvested text looks truncated: after unescaping, if
the body still contains a stray `{`, the harvester probably cut it short, so no verdict is issued.

## Scripts

Script bodies get the same treatment, per script language and per context. Atlas checks:

- **Structure** — unterminated strings and comments, unclosed interpolation, unmatched or mismatched
  closers, unclosed openers.
- **Configuration** — an empty body, a missing or unknown `scriptFormat`, a format whose case is wrong.
- **Semantics** — an unknown member on a bound root object, a root object that does not exist in this
  script's context, an EL-only API called from a script, and a listener type that does not support
  scripts at all.

Contexts are distinguished, because the bindings differ: script task (BPMN), execution listener,
task listener, script task (CMMN), CMMN task listener, and an action's bot script.

In the IDE the bodies are more than checked — real Groovy and JavaScript are **injected** into them, so
you get that language's own highlighting and inspections inside a BPMN file, and completion for the
root objects Flowable actually binds in that context.

## In the IDE

Two playgrounds, both in the *Flowable Expressions* tool window:

- **Expressions** — type an expression in either dialect and see it validated as you type. Frontend
  expressions evaluate live against a JSON payload you paste, with each sub-expression's value shown
  inline. Backend expressions evaluate against a running Flowable instance, on a real process, case or
  task scope.
- **Scripts** — a script editor with the variables, reads, bindings and beans of the selected context
  as clickable chips, *Load Script from Model…* to pull a real script body out of an indexed model, and
  *Load Example…* for a worked example instead: complete scripts for every context and language, each
  commented with what it demonstrates, and all of them validated by the build against this same catalog.

Alt-Enter on any expression in a model opens it in the playground, pre-filled with its dialect and its
model's scope. Full details in the [plugin reference](../plugin/reference/#expressions).
