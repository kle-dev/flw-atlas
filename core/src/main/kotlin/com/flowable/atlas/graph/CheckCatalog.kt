package com.flowable.atlas.graph

/**
 * Every health check Atlas runs, described once.
 *
 * Until now a check existed as a string id in [Findings], a label in the summary renderer, a second
 * label plus one-line copy in the explorer, and a severity that was stated only on the documentation
 * page. Four copies of one fact, none of them checked against the others — and none of them saying
 * *why* a finding matters or *what to do about it*, which is the part a reader who did not write the
 * check actually needs.
 *
 * This is the one place. Every check is one of two [Check.kind]s, and the split is the first thing a
 * reader sees on every surface: a **defect** is something wrong *now* — a file that will not parse, a
 * key nothing answers, a gateway the engine cannot leave — and an **advice** is a pattern worth a look
 * while nothing is broken — a call with no error path, a form nothing references. The distinction was
 * asked for by name: a list that presents "task without a boundary event" beside "expression does not
 * parse" teaches a reader to skim both. The order of [CHECKS] is the reading order every surface uses:
 * defects first (broken, then unfinished, then how a process is configured to behave), then advice.
 * `graph.json`'s `checks` map iterates in it, the overview groups findings by it, and the explorer's
 * Checks page lists blocks in it.
 *
 * [Check.docs] is the heading slug on `site/pages/checks.md`, computed the way the site builder computes
 * anchors — `SiteDocsCoverageTest` asserts every slug exists, so a renamed heading is a red build rather
 * than a dead link.
 */
object CheckCatalog {

    /** Where the documentation site lives; [docsUrl] appends the checks page and the heading anchor. */
    const val DOCS_BASE = "https://kle-dev.github.io/flw-atlas/"

    /** [Check.kind]: something is wrong now — it fails, or two things disagree. */
    const val DEFECT = "defect"
    /** [Check.kind]: nothing is broken; a pattern worth a look, optional to act on. */
    const val ADVICE = "advice"

    data class Check(
        val id: String,
        /** [DEFECT] or [ADVICE] — the split every surface leads with. */
        val kind: String,
        /** `broken` — something will fail; `runtime` — configured to behave riskily; `unfinished`; `noise`. */
        val tier: String,
        /** `error`, `warning`, or `error · warning` when the check emits both. */
        val severity: String,
        /** Lower-case wording for the summary and overview count lines: `unparseable files: 2`. */
        val label: String,
        /** The explorer's row title. */
        val title: String,
        /** One line, when the count is above zero. */
        val what: String,
        /** One line, when the count is zero. */
        val clean: String,
        /** Why a reader should care — what happens at runtime, or what the finding usually means. */
        val why: String,
        /** What to do about it, including when accepting it is the right answer. */
        val fix: String,
        /** Heading slug on the checks page. */
        val docs: String,
    )

    private const val UNUSED_DOCS = "unusedforms-unusedops-unusedfns-defined-never-used"
    private const val VARS_DOCS = "unusedvars-unreadinputs-guessedvars-variables"

    val CHECKS: List<Check> = listOf(
        Check(
            id = "parseIssues", kind = DEFECT, tier = "broken", severity = "error · warning",
            label = "unparseable files", title = "Parse issues",
            what = "files the analyzer could not fully read",
            clean = "all files analyzed cleanly",
            why = "A file Atlas could not read is missing from the report, and so is every reference " +
                "into and out of it — the rest of the report quietly gets less complete.",
            fix = "Open the file at the line named and fix the XML or JSON. A file Atlas skipped on " +
                "purpose says why in its message.",
            docs = "parseissues-files-atlas-could-not-read",
        ),
        Check(
            id = "invalidExpr", kind = DEFECT, tier = "broken", severity = "error · warning",
            label = "invalid expressions", title = "Invalid expressions",
            what = "syntax errors in \${ } / {{ }}",
            clean = "no syntax errors",
            why = "An expression that does not parse cannot evaluate — the element it sits on fails the " +
                "moment it is reached.",
            fix = "Close the bracket or string the message points at. Every problem on the expression " +
                "is listed under this check, warnings included.",
            docs = "invalidexpr-the-expression-does-not-parse",
        ),
        Check(
            id = "scriptIssues", kind = DEFECT, tier = "broken", severity = "error · warning",
            label = "script syntax", title = "Script syntax",
            what = "syntax & binding findings in script bodies",
            clean = "all scripts scan clean",
            why = "A script with a structural error fails when its task runs; a call on a member the " +
                "context does not bind fails the same way, just later.",
            fix = "Open the script at the line named — the snippet shows the offending text — and check " +
                "the root object's API for the misspelt member.",
            docs = "scriptissues-script-bodies",
        ),
        Check(
            id = "missingRefs", kind = DEFECT, tier = "broken", severity = "error",
            label = "missing models", title = "Missing model refs",
            what = "a key is referenced but no model defines it",
            clean = "every referenced key resolves",
            why = "The engine resolves the key when the element is reached and fails the call activity, " +
                "form or decision task when nothing answers.",
            fix = "Fix the typo or export the missing model. Accept the finding when the model lives in a " +
                "repository Atlas cannot see and is resolved at deploy time.",
            docs = "missingrefs-a-key-with-nothing-behind-it",
        ),
        Check(
            id = "crossedColumns", kind = DEFECT, tier = "broken", severity = "error · warning",
            label = "crossed column mappings", title = "Crossed column mappings",
            what = "a field maps the column another field is named after",
            clean = "every column mapping matches its field name",
            why = "Every name exists and every column is mapped, so nothing else notices — while each " +
                "field silently reads and writes the other one's column.",
            fix = "Swap the column names back in the .service model. A one-directional cross onto a legacy " +
                "column is the case to accept, with the column's history as the reason.",
            docs = "crossedcolumns-the-column-mapping-pairs-the-wrong-two-names",
        ),
        Check(
            id = "hardcodedSecrets", kind = DEFECT, tier = "broken", severity = "error",
            label = "literal secrets", title = "Hardcoded secrets",
            what = "a password, token or API key written into a model as plain text",
            clean = "no secret is written into a model",
            why = "A secret in a model file is a secret in the repository, in every export and in every " +
                "report — and the same one on every environment the model is deployed to.",
            fix = "Move the value to an expression that resolves it at runtime, or to the environment's " +
                "configuration, and rotate what was committed.",
            docs = "hardcodedsecrets-a-secret-written-into-a-model",
        ),
        Check(
            id = "unsafeQueries", kind = DEFECT, tier = "broken", severity = "warning",
            label = "unescaped query parameters", title = "Unescaped query parameters",
            what = "a query template interpolating a value without escaping it",
            clean = "every query template escapes what it interpolates",
            why = "A value dropped raw into search JSON can close the string it sits in and change the " +
                "query — the injection shape, in the one place a project writes raw query text.",
            fix = "Write `\${name?json_string}` for a string and `\${name?c}` for a number, so the value " +
                "stays a value.",
            docs = "unsafequeries-a-value-that-can-change-the-query",
        ),
        Check(
            id = "changelogIssues", kind = DEFECT, tier = "unfinished", severity = "warning",
            label = "changelog problems", title = "Changelog issues",
            what = "orphan or superseded changelogs",
            clean = "all changelogs are authoritative",
            why = "An orphan table has nothing in the models explaining why it exists; a superseded " +
                "changelog describes a shape the database no longer has.",
            fix = "Reference the table from a service or data object, or retire the changelog. For a " +
                "superseded one, read the successors the message names.",
            docs = "changelogissues-liquibase-authority",
        ),
        Check(
            id = "schemaGaps", kind = DEFECT, tier = "unfinished", severity = "warning",
            label = "schema gaps", title = "Schema gaps",
            what = "columns not mapped through Liquibase → service → data object",
            clean = "all columns mapped through",
            why = "A column no service maps can be neither read nor written from a model; a mapped column " +
                "no data object uses is a field nothing can bind to.",
            fix = "Add the mapping to the .service model or the field to the data object — or drop the " +
                "column if nothing needs it.",
            docs = "schemagaps-the-database-and-the-models-disagree",
        ),
        Check(
            id = "gatewayNoDefault", kind = DEFECT, tier = "runtime", severity = "warning",
            label = "gateways with no way out", title = "Gateway without default",
            what = "an exclusive or inclusive gateway whose every outgoing flow is conditional, with no default",
            clean = "every choosing gateway has a way out",
            why = "When none of the conditions holds the engine has nowhere to go and throws \"no outgoing " +
                "sequence flow\" — the instance fails right there, on the data that reached it.",
            fix = "Mark one flow as the gateway's default, or add an unconditional flow. Accept it when the " +
                "conditions are provably exhaustive.",
            docs = "gatewaynodefault-a-choice-with-no-way-out",
        ),
        Check(
            id = "implicitSplit", kind = DEFECT, tier = "runtime", severity = "warning",
            label = "implicit splits", title = "Implicit split",
            what = "an activity with several outgoing flows and no gateway",
            clean = "every fork is drawn as a gateway",
            why = "The engine takes every unconditional flow, so two of them out of one task run in parallel " +
                "— a fork nobody drew, invisible on the diagram and easy to read as a choice.",
            fix = "Put a parallel gateway there if the fork is meant, or an exclusive gateway with conditions " +
                "if it is a choice.",
            docs = "implicitsplit-a-fork-nobody-drew",
        ),
        Check(
            id = "suspectExpr", kind = DEFECT, tier = "noise", severity = "warning",
            label = "suspect expressions", title = "Suspect expressions",
            what = "flagged for review by the catalog",
            clean = "nothing flagged",
            why = "The expression parses but calls a function or namespace Atlas does not know — either " +
                "a typo, or a function the project registers itself.",
            fix = "Fix the name, or tell Atlas about your functions with --expr-allowlist or the plugin's " +
                "allowlist setting; the findings then stop.",
            docs = "suspectexpr-an-expression-atlas-cannot-vouch-for",
        ),
        Check(
            id = "leftoverMarkers", kind = ADVICE, tier = "unfinished", severity = "warning",
            label = "leftover markers", title = "Leftover markers",
            what = "a TODO, FIXME or HACK left in a model file",
            clean = "no marker left behind",
            why = "A marker is a promise someone made to come back; in a model that is deployed it is a " +
                "promise the process keeps running without.",
            fix = "Do the thing, or turn the marker into a note in waivers.json with the reason it can wait.",
            docs = "leftovermarkers-a-promise-nobody-kept",
        ),
        Check(
            id = "nonExclusiveAsync", kind = ADVICE, tier = "runtime", severity = "warning",
            label = "non-exclusive async", title = "Non-exclusive async",
            what = "async elements that opted out of exclusive jobs",
            clean = "no async element opts out of exclusive",
            why = "With exclusive=false the jobs of one process instance may run at the same time — an " +
                "optimistic-locking failure waiting for load, unless it was intended.",
            fix = "Remove the attribute unless the work is meant to run concurrently. If it is, accept the " +
                "finding with that as the reason.",
            docs = "nonexclusiveasync-async-jobs-that-may-run-at-the-same-time",
        ),
        Check(
            id = "unguardedTasks", kind = ADVICE, tier = "runtime", severity = "warning",
            label = "calls with no error path", title = "Calls with no error path",
            what = "service tasks leaving the engine with nothing catching a failure",
            clean = "every outbound call is guarded",
            why = "A failure in an HTTP call, an external worker, an agent, a mail task, a REST service or " +
                "a delegate of your own propagates to whatever called the process, with nothing in the " +
                "model saying what happens then. A platform bean that stays inside the engine — init " +
                "variables, audit log, data object — is not one of them, and neither is an async task: its " +
                "failure is a failed job, retried and then reported, not an exception to the caller.",
            fix = "Attach an error boundary event or catch centrally in an error event subprocess. Accept " +
                "the finding when letting the error reach the caller is the design.",
            docs = "unguardedtasks-a-call-out-of-the-engine-with-nothing-catching-it",
        ),
        Check(
            id = "asyncWithoutRetry", kind = ADVICE, tier = "runtime", severity = "warning",
            label = "async without retry", title = "Async without retry",
            what = "async work with no failedJobRetryTimeCycle of its own",
            clean = "async work states its retry policy",
            why = "The engine default applies — a decision made elsewhere, easy to be unaware of, and " +
                "often different between environments.",
            fix = "Add a failedJobRetryTimeCycle to the element, or accept it once for the project when " +
                "retries are configured globally.",
            docs = "asyncwithoutretry-async-work-that-does-not-say-how-often-to-try",
        ),
        Check(
            id = "unusedForms", kind = ADVICE, tier = "noise", severity = "warning",
            label = "unused forms", title = "Unused forms",
            what = "no model links to them",
            clean = "every form is referenced",
            why = "A form nothing references costs nothing at runtime; it makes the project bigger than " +
                "it needs to be and the next reader slower.",
            fix = "Delete it, or accept the finding when it is kept on purpose — a pilot, or a form a " +
                "plugin opens by key.",
            docs = UNUSED_DOCS,
        ),
        Check(
            id = "unusedDecisions", kind = ADVICE, tier = "noise", severity = "warning",
            label = "unused decisions", title = "Unused decisions",
            what = "decision tables no process, case or decision service calls",
            clean = "every decision is called",
            why = "A decision table nothing calls is a rule set the project maintains and never runs — or " +
                "the trace of a call that was renamed away from it.",
            fix = "Delete it, or point the task that should call it at this key. Accept it when a decision " +
                "service outside this repository consults it.",
            docs = "unuseddecisions-a-table-nothing-consults",
        ),
        Check(
            id = "unusedOps", kind = ADVICE, tier = "noise", severity = "warning",
            label = "unused service operations", title = "Unused operations",
            what = "operations never called from a model",
            clean = "every operation is used",
            why = "A service operation nothing calls is dead configuration — harmless until someone " +
                "changes it believing it is live.",
            fix = "Remove the operation from the .service model, or accept it when code Atlas does not " +
                "read calls it.",
            docs = UNUSED_DOCS,
        ),
        Check(
            id = "unusedFns", kind = ADVICE, tier = "noise", severity = "warning",
            label = "unused custom functions", title = "Unused custom functions",
            what = "functions never called",
            clean = "every function is used",
            why = "A registered expression function nothing calls is code the project maintains for " +
                "no reader.",
            fix = "Remove the registration, or accept it when the function is meant for models outside " +
                "this repository.",
            docs = UNUSED_DOCS,
        ),
        Check(
            id = "unusedVars", kind = ADVICE, tier = "noise", severity = "warning",
            label = "variables never read", title = "Variables · never read",
            what = "variables written but nothing reads them",
            clean = "every variable that is written is read somewhere",
            why = "A write nothing reads is either a leftover or a misspelt name on the reading side — " +
                "and it is persisted with every instance.",
            fix = "Delete the write the message names, or fix the name where the value should have " +
                "been read.",
            docs = VARS_DOCS,
        ),
        Check(
            id = "unreadInputs", kind = ADVICE, tier = "noise", severity = "warning",
            label = "unread call parameters", title = "Variables · unread call input",
            what = "mapped into a called model that never reads them",
            clean = "every mapped input is read by its callee",
            why = "The value arrives in the called process or case and nothing there uses it — usually a " +
                "mapping kept after the callee changed.",
            fix = "Remove the in-parameter, or add the read the callee was supposed to have.",
            docs = VARS_DOCS,
        ),
        Check(
            id = "guessedVars", kind = ADVICE, tier = "noise", severity = "warning",
            label = "script-inferred variables", title = "Variables · script guess",
            what = "only a bare identifier in a script names them",
            clean = "every variable is declared somewhere",
            why = "The only evidence for the variable is a bare name in a script, so Atlas inferred it " +
                "rather than reading a declaration — a typo looks exactly the same.",
            fix = "Declare the variable where it is written, or read it through the execution API so " +
                "its name is stated.",
            docs = VARS_DOCS,
        ),
    )

    /** Check ids in reading order — the `--fail-on` vocabulary and the iteration order of `checks`. */
    val ORDER: List<String> = CHECKS.map { it.id }

    private val byId: Map<String, Check> = CHECKS.associateBy { it.id }

    operator fun get(id: String): Check? = byId[id]

    /** The kind of a check — [DEFECT], [ADVICE] — or null for an id the catalog does not know. */
    fun kind(id: String): String? = byId[id]?.kind

    /** Open findings of [kind] in a findings list, ignoring waived ones. */
    fun countOpen(findings: List<Map<String, Any?>>, kind: String): Int =
        findings.count { it["waived"] == null && kind(it["check"]?.toString().orEmpty()) == kind }

    /** The summary/overview wording for a check, or the id itself for one the catalog does not know. */
    fun label(id: String): String = byId[id]?.label ?: id

    fun docsUrl(check: Check): String = "${DOCS_BASE}checks/#${check.docs}"

    /** The catalog as the explorer receives it: one object per check, `docs` already a full URL. */
    fun payload(): List<Map<String, Any?>> = CHECKS.map { c ->
        linkedMapOf<String, Any?>(
            "id" to c.id,
            "kind" to c.kind,
            "tier" to c.tier,
            "severity" to c.severity,
            "label" to c.label,
            "title" to c.title,
            "what" to c.what,
            "clean" to c.clean,
            "why" to c.why,
            "fix" to c.fix,
            "docs" to docsUrl(c),
        )
    }
}
