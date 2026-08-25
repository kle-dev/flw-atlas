package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.ScriptContext
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/**
 * "Load Example…": a small, hand-written library of working Flowable scripts — one or more per
 * script context and per language — that the playground drops into the editor the same way
 * [ScriptPicker] drops in a real model's script.
 *
 * Why bundled rather than linked to the documentation: the point of an example here is to be
 * *edited*, against the same validation, completion and chips a real script gets, and the reference
 * documentation's snippets are prose fragments that do not survive a paste. Every body below is a
 * complete script that validates clean in its own context — `ScriptExamplesTest` runs :core's
 * `ScriptValidator` over all of them, so a binding renamed in the catalog fails the build here.
 *
 * The comments inside the bodies carry the teaching (why a transient variable, why `task` and never
 * `execution` in a task listener); a reader sees them the moment the example lands in the editor,
 * which is why the rows themselves stay one line.
 */
internal object ScriptExamples {

    data class Example(
        val title: String,
        val context: ScriptContext,
        /** The raw `scriptFormat` the body is written for — drives the editor language too. */
        val format: String,
        val body: String,
    ) {
        val label: String
            get() = "$title  —  ${context.display} (${PlaygroundScriptLanguage.fromFormat(format).display})"
    }

    private fun ex(title: String, context: ScriptContext, format: String, body: String) =
        Example(title, context, format, body.trimIndent())

    val ALL: List<Example> = listOf(

        // ---------------------------------------------------------------- BPMN script task

        ex("Read and write process variables", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // A script sees every process variable under its bare name — `orderAmount` below is a
            // variable, not a local. Writing one always goes through the scope object: `execution`.
            def amount = orderAmount as BigDecimal
            def discount = amount > 1000 ? amount * 0.1 : 0.0

            execution.setVariable('discount', discount)
            execution.setVariable('orderTotal', amount - discount)

            // getVariable is the explicit form — use it when the name may be absent or is computed,
            // because a bare name that was never set fails the script instead of reading null.
            if (execution.getVariable('couponCode') != null) {
                execution.setVariable('couponApplied', true)
            }
        """),

        ex("Transient and local variables", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // A transient variable lives for this transaction only: never persisted, never in the
            // history, gone at the next wait state. That makes it the right home for tokens, for
            // credentials and for bulky intermediates nobody needs to query later.
            execution.setTransientVariable('crmToken', apiToken)

            // A local variable belongs to this execution — the multi-instance instance, the
            // subprocess — instead of to the process instance, so parallel branches that run the
            // same script do not overwrite each other's value.
            def attempt = (execution.getVariableLocal('attempt') ?: 0) + 1
            execution.setVariableLocal('attempt', attempt)
        """),

        ex("Build and read JSON", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // flw.json builds Jackson nodes. Store the node itself, not a string: a JSON variable
            // stays queryable, and forms and expressions can walk into it.
            def order = flw.json.createObject()
            order.put('id', orderId)
            order.put('customer', customerName)

            def lines = flw.json.createArray()
            orderLines.each { line ->
                def node = flw.json.createObject()
                node.put('sku', line.sku)
                node.put('quantity', line.quantity)
                lines.add(node)
            }
            order.set('lines', lines)

            execution.setVariable('orderPayload', order)

            // The other direction: parse what a REST call returned, and read it defensively —
            // path() answers a missing-node instead of null, so a chain never throws.
            def reply = flw.json.stringToJson(crmReply)
            execution.setVariable('customerTier', reply.path('tier').asText('standard'))
        """),

        ex("Dates and durations", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // flw.time is the platform's date API: it speaks java.time, and it reads the engine's
            // clock — so a test that moves the clock moves these values with it.
            def today = flw.time.currentLocalDate()
            def due = flw.time.plusDays(today, 5)
            if (flw.time.isWeekend(due)) {
                due = flw.time.plusDays(due, 2)
            }

            // Store a java.util.Date when a form or a timer has to read it back.
            execution.setVariable('dueDate', flw.time.asDate(due))

            // Distances between two points, in the unit you actually need.
            def createdAt = execution.getVariable('createdAt')
            execution.setVariable('daysOpen', flw.time.daysBetween(createdAt, flw.time.now()))
        """),

        ex("Text and numbers", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // The flw string helpers are null-safe: they answer with a sensible value instead of
            // throwing on a variable that was never set.
            def name = flw.string.capitalize(flw.string.toLowerCase(customerName))

            execution.setVariable('displayName', name)
            execution.setVariable('isInternal', flw.string.containsIgnoreCase(customerEmail, '@example.com'))

            // flw.math takes a collection as happily as it takes numbers.
            def prices = orderLines.collect { it.price }
            execution.setVariable('orderTotal', flw.math.round(flw.math.sum(prices), 2))
        """),

        ex("Fail on purpose: BPMN error vs. client error", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // An exception out of a script fails the job and leaves it to the retry mechanism —
            // three retries, then a dead job an administrator has to find. To route the process
            // instead, raise a BPMN error and catch it with an error boundary event on this task.
            if (!flw.string.hasText(invoiceNumber)) {
                flw.bpmn.throwError('MISSING_INVOICE', "Order ${'$'}{orderId} has no invoice number")
            }

            // flw.error.* is the other family: it ends the *request* with a client error, which is
            // what a synchronous call — a form submit, an API call — should see.
            if (orderTotal < 0) {
                flw.error.throwIllegalArgument('orderTotal must not be negative')
            }
        """),

        ex("Use the engine services", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // Every engine service is bound by name — the same API a Java delegate uses. In a CMMN
            // script the same names resolve to the CMMN services.
            def openReviews = taskService.createTaskQuery()
                .processInstanceId(execution.getProcessInstanceId())
                .taskCandidateGroup('reviewers')
                .count()

            execution.setVariable('openReviews', openReviews)

            // Starting a second process instance is a builder call. Keep the business key: it is
            // what ties the two instances together in every list the user later looks at.
            runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey('DEMO-notification')
                .businessKey(execution.getProcessInstanceBusinessKey())
                .variable('orderId', orderId)
                .start()
        """),

        ex("Build a multi-instance collection", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // A multi-instance task iterates a collection variable, and a script task is the usual
            // place to compute it. Keep it to plain serializable values — a list of ids, not a list
            // of entities: the collection is persisted once per instance.
            def reviewerIds = candidateReviewers
                .findAll { it.active && it.department == department }
                .collect { it.userId }
                .unique()

            execution.setVariable('reviewerIds', reviewerIds)

            // An empty collection completes a multi-instance task immediately — usually not what the
            // model means, so decide the empty case here rather than downstream.
            execution.setVariable('reviewRequired', !reviewerIds.isEmpty())
        """),

        ex("Reach a Work platform service", ScriptContext.BPMN_SCRIPT_TASK, "groovy", """
            // In a Work installation the platform services resolve as Spring beans, in every script
            // context. They do not exist in a plain engine, and the script sandbox's strict mode
            // hides them — so guard the script, or keep it to installations that have them.
            def openClaims = dataObjectRuntimeService.createDataObjectInstanceQuery()
                .dataObjectDefinitionKey('DEMO-claim')
                .count()

            execution.setVariable('openClaims', openClaims)
        """),

        ex("The same, in JavaScript", ScriptContext.BPMN_SCRIPT_TASK, "javascript", """
            // Same bindings, JavaScript syntax (Nashorn or GraalJS, depending on the installation).
            // Java objects stay Java objects: `orderLines` is a java.util.List, not a JS array.
            var amount = execution.getVariable('orderAmount');
            var currency = execution.getVariable('currency') || 'EUR';

            execution.setVariable('summary', currency + ' ' + amount);
            execution.setVariable('needsApproval', amount > 10000);

            // Build JSON through flw.json — a JS object literal would be stored as an opaque script
            // object that nothing downstream can read.
            var payload = flw.json.createObject();
            payload.put('currency', currency);
            payload.put('amount', amount);
            execution.setVariable('orderPayload', payload);
        """),

        ex("The same, in Python", ScriptContext.BPMN_SCRIPT_TASK, "python", """
            # Jython, where the installation ships it: same bindings, Python syntax. Java getters
            # are always called explicitly — there is no property shorthand.
            amount = execution.getVariable('orderAmount')
            execution.setVariable('needsApproval', amount > 10000)

            lines = execution.getVariable('orderLines')
            execution.setVariable('lineCount', len(lines) if lines else 0)
        """),

        // ---------------------------------------------------------------- BPMN listeners

        ex("Stamp an audit trail", ScriptContext.BPMN_EXECUTION_LISTENER, "groovy", """
            // An execution listener binds `execution`, and getEventName() says which event fired —
            // so one script can serve the start and the end of the same activity.
            if (execution.getEventName() == 'end') {
                // A JSON array, not a Groovy list: the engine stores this one as a JSON variable
                // that queries and forms can read, where a list of maps becomes a serialized blob.
                def trail = execution.getVariable('auditTrail') ?: flw.json.createArray()

                def entry = flw.json.createObject()
                entry.put('step', execution.getCurrentActivityId())
                entry.put('at', flw.time.now().toString())
                trail.add(entry)

                execution.setVariable('auditTrail', trail)
            }
        """),

        ex("Assign a task and set its due date", ScriptContext.BPMN_TASK_LISTENER, "groovy", """
            // A task listener binds `task` — and never `execution`: the engine's scope key is one or
            // the other. Process variables are still readable by bare name, and task.setVariable
            // writes to the process instance (setVariableLocal keeps the value on the task).
            if (task.getEventName() == 'create') {
                task.setAssignee(preferredReviewer)
                task.addCandidateGroup('reviewers')

                def in2Days = flw.time.plusDays(flw.time.currentLocalDateTime(), 2)
                task.setDueDate(flw.time.asDate(in2Days))
            }

            // On 'complete' the outcome is already decided — a good place to fold it back into the
            // process before the token moves on.
            if (task.getEventName() == 'complete') {
                task.setVariable('reviewedBy', task.getAssignee())
            }
        """),

        // ---------------------------------------------------------------- CMMN

        ex("Case and plan-item variables", ScriptContext.CMMN_SCRIPT_TASK, "groovy", """
            // CMMN has no `execution`. A script task runs on its plan item, and that is also the
            // object variables are written through; `caseInstance` is bound beside it.
            def total = planItemInstance.getVariable('orderTotal')
            planItemInstance.setVariable('requiresApproval', total > 10000)

            // setVariableLocal keeps a value on this plan item instead of on the case.
            planItemInstance.setVariableLocal('checkedAt', flw.time.now())

            def label = caseInstance.getName() + ' / ' + caseInstance.getBusinessKey()
            planItemInstance.setVariable('caseLabel', label)

            // (`task` is bound here too, but in a CMMN script task it is a List of the tasks under
            // the plan item — usually empty. It is not a task object.)
        """),

        ex("Signal a fault instead of failing", ScriptContext.CMMN_SCRIPT_TASK, "groovy", """
            // The CMMN counterpart of flw.bpmn.throwError: report a modelled failure rather than
            // throwing, which would fail the job and hand it to the retry mechanism.
            def policyNumber = planItemInstance.getVariable('policyNumber')
            if (!flw.string.hasText(policyNumber)) {
                flw.cmmn.throwFault('MISSING_POLICY', 'The claim has no policy number')
            }
        """),

        ex("React to a task event", ScriptContext.CMMN_TASK_LISTENER, "groovy", """
            // A CMMN task listener sees `task`, its `planItemInstance` and the `caseInstance` —
            // three scopes, and the variable you write goes to whichever one you name.
            if (task.getEventName() == 'create' && caseInstance.getBusinessStatus() == 'urgent') {
                task.setPriority(100)

                def in4Hours = flw.time.plusHours(flw.time.currentLocalDateTime(), 4)
                task.setDueDate(flw.time.asDate(in4Hours))
            }
        """),

        // ---------------------------------------------------------------- action bots

        ex("Inputs and outputs", ScriptContext.ACTION_BOT, "groovy", """
            // A bot script hangs off neither a process nor a case: its whole world is the flw API.
            // Inputs come from the action's input mapping; outputs are what it returns to the caller.
            def orderId = flw.getInput('orderId')
            if (!flw.string.hasText(orderId)) {
                flw.error.throwIllegalArgument('orderId is required')
            }

            def result = flw.json.createObject()
            result.put('id', orderId)
            result.put('status', 'CONFIRMED')

            flw.setOutput('order', result)
            flw.setOutput('confirmedAt', flw.time.now())
        """),

        ex("Read the invocation itself", ScriptContext.ACTION_BOT, "javascript", """
            // flwActionContext is the invocation: the payload the caller sent and the intent this
            // bot was matched on. It exists in bot scripts and nowhere else.
            var payload = flwActionContext.getPayload();
            if (!payload.has('customerId')) {
                flw.error.throwIllegalArgument('customerId is required');
            }

            flw.setOutput('customerId', payload.path('customerId').asText());
            flw.setOutput('intent', flwActionContext.getIntent());
        """),
    )

    /**
     * Catalog order, with the examples that run in [context] first — picking one of those keeps the
     * playground's own context, so the list starts with what the user can load without a change of
     * subject. The sort is stable, so the grouping inside each half is the declaration order.
     */
    fun orderedFor(context: ScriptContext): List<Example> = ALL.sortedByDescending { it.context == context }

    fun show(panel: FlowableScriptPanel) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(orderedFor(panel.scriptContext))
            .setTitle("Load Example Script")
            .setRenderer(textListCellRenderer("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { example -> panel.loadScript(example.body, example.format, example.context) }
            .createPopup()
            .showCenteredInCurrentWindow(panel.project)
    }
}
