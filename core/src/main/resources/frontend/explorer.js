// Data arrives as a JSON island (<script type="application/json" id="atlas-data">):
// JSON.parse is faster than a JS literal for large payloads and needs no JS escaping.
const DATA = JSON.parse(document.getElementById('atlas-data').textContent);
const nodes = DATA.nodes, edges = DATA.edges;
const byId = new Map(nodes.map(n => [n.id, n]));
const diags = DATA.diagnostics || [];
const cfns = DATA.customFunctions;
const cfnDiags = (cfns && cfns.diagnostics) || [];
// Node-type labels. Wording follows Flowable Design's own `modelType.*` strings so a term you read here
// is the term you look for in Design — "Decision tables", not "Decisions"; "AI agents", not "Agents".
const TM = {
  app:['Apps','Models'],process:['Processes','Models'],case:['Cases','Models'],
  decision:['Decision tables','Models'],form:['Forms','Models'],page:['Pages','Models'],
  dataObject:['Data objects','Models'],dataDictionary:['Data dictionaries','Models'],
  masterData:['Master data','Models'],
  service:['Services','Integration'],serviceOperation:['Service operations','Integration'],agent:['AI agents','Integration'],
  channel:['Channels','Integration'],event:['Events','Integration'],knowledgeBase:['Knowledge bases','Integration'],
  signal:['Signals','Integration'],message:['Messages','Integration'],error:['Errors','Integration'],
  escalation:['Escalations','Integration'],topic:['External Worker topics','Integration'],
  endpoint:['REST endpoints','Code'],java:['Java classes','Code'],method:['Java methods','Code'],liquibase:['Liquibase changelogs','Code'],
  action:['Actions','Integration'],bot:['Bots','Integration'],
  query:['Queries','Other'],template:['Templates','Other'],sequence:['Sequences','Other'],
  document:['Content','Other'],variableExtractor:['Variable extractors','Other'],
  sla:['SLAs','Other'],dashboardComponent:['Dashboard components','Other'],
  securityPolicy:['Security policies','Access'],group:['User groups','Access'],
  variable:['Variables','Variables'],
  expression:['Backend expressions ${ }','Expressions'],binding:['Frontend bindings {{ }}','Expressions'],
  string:['String literals','Expressions'],customFunction:['Custom functions 🧩','Expressions'],
  external:['External / library','Other'],
};

// ---------- Flowable Design vocabulary ----------
// Atlas's internal names (`ruleTask-decision`, `sendPayloadMapping`, `workAction`) are precise but only
// mean something if you built Atlas. This table gives each one the word Design uses plus a sentence
// explaining it, shown as a tooltip. Namespaces: `type:` node kinds, `el:` model elements,
// `kind:` parameter mappings, `rel:` relationships. `term()` falls back to the raw key, so an entry that
// is missing here degrades to today's behaviour instead of disappearing.
const DESIGN_TERMS = {
  // --- node kinds: the hint, the label lives in TM ---
  'type:decision': [null, 'A DMN decision table — inputs, outputs and the rules between them.'],
  'type:agent': [null, 'An AI agent model: the LLM, its instructions, tools and operations.'],
  'type:service': [null, 'A Service Registry entry — a reusable REST, MCP, database, script or expression integration with named operations.'],
  'type:dataObject': [null, 'A structured business object, backed by a service or by master data.'],
  'type:action': [null, 'An action that a user or the system can trigger on a scoped object; it is dispatched to a bot.'],
  'type:bot': [null, 'The BotService that performs an action at runtime, looked up by its bot key.'],
  'type:document': [null, 'A content/document model.'],
  'type:page': [null, 'A FlowApp page — the same component model as a form, but for navigation targets.'],
  'type:securityPolicy': [null, 'Permission definitions that gate what a role may see and do.'],
  'type:sla': [null, 'Service-level thresholds attached to a process, case or task.'],
  'type:sequence': [null, 'A number sequence used to generate business keys and references.'],
  'type:variableExtractor': [null, 'Extracts variables out of a payload so they can be indexed and queried.'],
  'type:knowledgeBase': [null, 'The document collection an AI agent retrieves from.'],
  'type:masterData': [null, 'A managed reference list — countries, currencies, categories.'],
  'type:dataDictionary': [null, 'Reusable typed structures that data objects, services and forms share.'],
  'type:serviceOperation': [null, 'One named operation of a service, with its declared input and output parameters.'],
  'type:topic': [null, 'The queue name an External Worker task publishes to.'],
  // --- model elements (elementType / elementSubType of a parameter group) ---
  'el:userTask': ['User task', 'A task a person completes, usually through a form.'],
  'el:humanTask': ['Human task', 'The CMMN equivalent of a user task.'],
  'el:serviceTask': ['Service task', 'Runs logic automatically — Java, an expression or one of the Flowable task types.'],
  'el:serviceTask/service-registry': ['Service registry task', 'Calls an operation of a Service Registry entry.'],
  'el:serviceTask/agent': ['AI Agent', 'Hands the mapped values to an AI agent model and maps its answer back.'],
  'el:serviceTask/http': ['HTTP task', 'Calls a URL directly, configured through field injections.'],
  'el:serviceTask/dmn': ['Decision task', 'Evaluates a decision table; the mapping is derived from the table itself.'],
  'el:serviceTask/mail': ['Email task', 'Sends an email, optionally rendered from a template model.'],
  'el:serviceTask/data-object': ['Data object task', 'Creates, looks up, updates, deletes or searches a data object.'],
  'el:serviceTask/init-variables': ['Initialize variables', 'Declares variables and their initial values.'],
  'el:serviceTask/send-event': ['Send event task', 'Publishes an event onto a channel.'],
  'el:serviceTask/external-worker': ['External Worker task', 'Parks the work on a topic for an external worker to pick up.'],
  'el:serviceTask/case': ['Case task', 'Starts a case from a process.'],
  'el:serviceTask/audit': ['Audit', 'Writes an audit entry.'],
  'el:serviceTask/script': ['Script task', 'Runs an inline script and can store its result in a variable.'],
  'el:serviceTask/generate-document': ['Generate Document', 'Renders a document from a template model.'],
  'el:scriptTask': ['Script task', 'Runs an inline script and can store its result in a variable.'],
  'el:sendTask': ['Send task', 'Sends a message.'],
  'el:manualTask': ['Manual task', 'Work done outside the engine — recorded, not executed.'],
  'el:subProcess': ['Sub-process', 'A group of elements that runs inside the parent instance.'],
  'el:transaction': ['Transaction', 'A sub-process whose work is undone by compensation if it fails.'],
  'el:adhocSubProcess': ['Ad-hoc sub-process', 'Contained activities run in any order, chosen at runtime.'],
  'el:exclusiveGateway': ['Exclusive gateway', 'Takes exactly one outgoing flow — the first condition that is true.'],
  'el:parallelGateway': ['Parallel gateway', 'Splits into all outgoing flows and joins by waiting for all incoming ones.'],
  'el:inclusiveGateway': ['Inclusive gateway', 'Takes every outgoing flow whose condition is true.'],
  'el:eventBasedGateway': ['Event gateway', 'Waits for whichever of the following events happens first.'],
  'el:complexGateway': ['Complex gateway', 'Custom split/join behaviour.'],
  'el:sequenceFlow': ['Sequence flow', 'The arrow that orders two elements; a condition makes it optional.'],
  'el:callActivity': ['Call activity', 'Invokes another process; in and out parameters move variables between the two.'],
  'el:processTask': ['Process task', 'Starts a process from a case.'],
  'el:caseTask': ['Case task', 'Starts a sub-case from a case.'],
  'el:startEvent': ['Start event', 'Where an instance begins.'],
  'el:endEvent': ['End event', 'Where a path finishes.'],
  'el:boundaryEvent': ['Boundary event', 'Attached to an activity and triggered while it runs.'],
  'el:receiveTask': ['Receive task', 'Waits for a message or an event.'],
  'el:workAction': ['Action button', 'A button on a form or page that invokes an action.'],
  'el:restButton': ['REST button', 'A button that calls a URL directly.'],
  'el:workInvokeService': ['Service button', 'A button that calls a Service Registry operation.'],
  'el:workAgentButton': ['Agent button', 'A button that asks an AI agent.'],
  'el:actionBot': ['Action bot', 'The bot an action is dispatched to at runtime.'],
  'el:task': ['Task', 'A plain task; its flowable:type decides what it does.'],
  'el:decisionTask': ['Decision task', 'Evaluates a decision table from a case.'],
  'el:humanTaskWithService': ['Human task with service', 'A human task combined with a service call.'],
  'el:milestone': ['Milestone', 'A named point the case reaches when its conditions are met.'],
  'el:entryCriterion': ['Entry criterion', 'The plan item becomes available once this sentry is satisfied.'],
  'el:exitCriterion': ['Exit criterion', 'The plan item (or stage) terminates once this sentry is satisfied.'],
  'el:stage': ['Stage', 'A group of plan items that activates and completes together.'],
  'el:planFragment': ['Plan fragment', 'A reusable group of plan items.'],
  'el:timerEventListener': ['Timer', 'Fires on a schedule or after a duration.'],
  'el:userEventListener': ['User event listener', 'Triggered manually by a user.'],
  'el:signalEventListener': ['Signal listener', 'Waits for a signal by name.'],
  'el:variableEventListener': ['Variable listener', 'Fires when a variable changes.'],
  'el:intermediateCatchEvent': ['Intermediate catch event', 'Waits mid-flow for a timer, message or signal.'],
  'el:intermediateThrowEvent': ['Intermediate throw event', 'Publishes a signal/message mid-flow.'],
  'el:eventListener': ['Event listener', 'A case element that waits for something — a timer, a user, a signal or a variable change.'],
  'el:casePlanModel': ['Case plan model', 'The root stage of a case: everything the case can do lives inside it.'],
  // --- listeners: what runs alongside an element rather than as one ---
  'el:executionListener': ['Execution listener', 'Runs when the element starts or ends — a Java class, an expression or a script.'],
  'el:taskListener': ['Task listener', 'Runs on a user task’s lifecycle: create, assignment, complete or delete.'],
  'el:planItemLifecycleListener': ['Lifecycle listener', 'Runs when a plan item changes state — available, active, completed, terminated.'],
  // --- data-source kinds on a form/page component ---
  'kind-ds:dataObject': ['Data object', 'Rows or options come from a data object lookup.'],
  'kind-ds:service': ['Service', 'Rows or options come from a Service Registry operation.'],
  'kind-ds:rest': ['REST', 'Rows or options come from a URL.'],
  // --- parameter mapping kinds ---
  'kind:in': ['In parameter', 'Copies a variable from the calling scope into the called one.'],
  'kind:out': ['Out parameter', 'Copies a variable from the called scope back into the caller.'],
  'kind:inputParameter': ['Input parameter', 'A value handed to the call, named as the callee declares it.'],
  'kind:outputParameter': ['Output parameter', 'A value from the response, stored in a variable.'],
  'kind:errorOutputParameter': ['Error output parameter', 'Mapped only when the call fails; the regular output mapping is then skipped.'],
  'kind:outputVariableName': ['Output variable', 'The variable the whole result is stored in.'],
  'kind:resultVariable': ['Result variable', 'The variable the task writes its result to.'],
  'kind:variableMapping': ['Variable', 'A variable declared with its initial value.'],
  'kind:eventInParameter': ['Event payload (out)', 'Fills a field of the event payload being published.'],
  'kind:eventOutParameter': ['Event payload (in)', 'Reads a field of the received event payload into a variable.'],
  'kind:sendPayloadMapping': ['Send payload map', 'The values handed to the call — a script-based action reads them with flw.getInput(…).'],
  'kind:responsePayloadMapping': ['Store response attributes', 'Writes parts of the response back into the form; a script action sets them with flw.setOutput(…).'],
  'kind:errorResponsePayloadMapping': ['Error response map', 'Mapped instead of the response when the call fails.'],
  'kind:dataObjectDataTableCreatePayloadMapping': ['Create payload map', 'The values a data table sends when creating a row.'],
  'kind:header': ['HTTP header', 'Sent as a request header rather than in the body.'],
  'kind:signalVariable': ['Signal variable', 'Copied into the signalled instance as a variable.'],
  'kind:config': ['Bot configuration', 'A bot-specific setting from the action model, not a variable.'],
  'kind:flwScript': ['Script payload', 'Read or written by the action script through flw.getInput(…) / flw.setOutput(…).'],
  'kind:field': ['Field injection', 'Static configuration on the task rather than a variable mapping.'],
  // --- relationships ---
  'rel:contains': ['App contains', 'The app packages this model for deployment.'],
  'rel:callActivity': ['Call activity → process', 'A call activity in this process invokes that process.'],
  'rel:processTask': ['Process task → process', 'A process task in this case starts that process.'],
  'rel:caseTask': ['Case task → case', 'A case task starts that case.'],
  'rel:decisionTask': ['Decision task → decision table', 'A decision task evaluates that decision table.'],
  'rel:ruleTask-decision': ['Decision task → decision table', 'A decision task evaluates that decision table.'],
  'rel:serviceTask-class': ['Service task → Java class', 'The task runs that class as a JavaDelegate.'],
  'rel:serviceTask-delegate': ['Service task → bean', 'The task runs that Spring bean via a delegate expression.'],
  'rel:task-delegate': ['Task → bean', 'The case task runs that Spring bean via a delegate expression.'],
  'rel:serviceMapping': ['Service registry task → service', 'The task calls an operation of that service.'],
  'rel:dataObjectMapping': ['Data object task → data object', 'The task creates, reads, updates, deletes or searches that data object.'],
  'rel:agentMapping': ['AI Agent → agent model', 'The task hands its input to that agent model.'],
  'rel:userTask-form': ['User task → form', 'That form is rendered when the task is worked on.'],
  'rel:humanTask-form': ['Human task → form', 'That form is rendered when the task is worked on.'],
  'rel:task-form': ['Task → form', 'That form is rendered for the task.'],
  'rel:start-form': ['Start form', 'That form is filled in before the instance starts.'],
  'rel:work-form': ['Work form', 'The form shown while working on the instance.'],
  'rel:casePage-form': ['Case page → form', 'A tab of the case page renders that form.'],
  'rel:task-form-mapping': ['Form key passed in', 'The form is chosen at runtime by an in-mapping onto formKey.'],
  'rel:subform': ['Contains subform', 'That form is embedded as a subform.'],
  'rel:outcome-form': ['Outcome → form', 'Choosing that outcome opens the form.'],
  'rel:field-dataObject': ['Field → data object', 'A component reads its options or rows from that data object.'],
  'rel:field-service': ['Field → service', 'A component reads its options or rows from that service operation.'],
  'rel:field-agent': ['Field → agent model', 'An agent button on this form asks that agent.'],
  'rel:triggers-action': ['Action button → action', 'A button on this form or page invokes that action.'],
  'rel:starts-process': ['Bot starts process', 'The action’s bot starts an instance of that process.'],
  'rel:starts-case': ['Bot starts case', 'The action’s bot starts an instance of that case.'],
  'rel:triggers-signal': ['Sends signal', 'The action signals a waiting instance by that signal name.'],
  'rel:sends-event': ['Publishes event', 'This model publishes that event onto a channel.'],
  'rel:receives-event': ['Consumes event', 'This model is triggered by, or waits for, that event.'],
  'rel:trigger-event': ['Triggered by event', 'The event that resumes a send-and-receive task.'],
  'rel:via-channel': ['Uses channel', 'Events travel over that channel.'],
  'rel:external-topic': ['External Worker topic', 'Work is parked on that topic for an external worker.'],
  'rel:queries-dataObject': ['Queries data object', 'A data-source URL queries that data object.'],
  'rel:runs-query': ['Runs query', 'A data source runs that query model.'],
  'rel:uses-sequence': ['Uses sequence', 'Business keys come from that number sequence.'],
  'rel:data-dictionary': ['Uses data dictionary', 'Types are taken from that data dictionary.'],
  'rel:typed-by-dictionary': ['Typed by data dictionary', 'A parameter’s type is defined in that data dictionary.'],
  'rel:backed-by-service': ['Backed by service', 'The data object reads and writes through that service.'],
  'rel:schema': ['Table schema', 'The Liquibase changelog that defines the physical table.'],
  'rel:serves': ['Serves endpoint', 'That controller method handles the endpoint.'],
  'rel:rest-call': ['Calls endpoint', 'A component or task calls that REST endpoint.'],
  'rel:bot': ['Dispatched to bot', 'The action is executed by that bot.'],
  'rel:action-form': ['Action → form', 'That form collects the action’s payload before it runs.'],
  'rel:action-channel': ['Offered on channel', 'Where the action appears in the UI.'],
  'rel:assign': ['Assigned to', 'Who may work on it.'],
  'rel:start': ['May start', 'Who may start an instance.'],
  'rel:owner': ['Owner', 'Who owns the instance or task.'],
  'rel:watcher': ['Watcher', 'Who follows it without working on it.'],
  // data-object permissions, as the security policy spells them
  'rel:createInstances': ['May create', 'Who may create instances of that data object.'],
  'rel:queryInstances': ['May query', 'Who may search instances of that data object.'],
  'rel:updateInstances': ['May update', 'Who may change instances of that data object.'],
  'rel:deleteInstances': ['May delete', 'Who may delete instances of that data object.'],
  'rel:read': ['May read', 'Who may read it.'],
  'rel:query': ['May query', 'Who may search it.'],
  'rel:update': ['May update', 'Who may change it.'],
  'rel:open-app': ['May open app', 'Who may open the app.'],
  'rel:references': ['Code references key', 'A Java string literal equal to that model key.'],
  'rel:relates-to': ['Relates to', 'A field of this model points at that model.'],
  'rel:declared-in': ['Declared in', 'Where the method is declared.'],
  'rel:requires': ['Requires decision', 'This decision needs that decision’s result (DRD).'],
  'rel:contains-decision': ['Contains decision', 'The decision service bundles that decision table.'],
  'rel:knowledgeBase': ['Uses knowledge base', 'The agent retrieves from that document collection.'],
  'rel:tool': ['Uses tool', 'The agent may call that model as a tool.'],
  'rel:guardrail': ['Guardrail', 'That model checks the agent’s input or output.'],
  'rel:evaluator': ['Evaluator', 'That model scores the agent’s answers.'],
  'rel:message-template': ['Prompt template', 'The agent’s prompt is rendered from that template.'],
  'rel:documentAgent': ['Document agent', 'Documents are delegated to that agent.'],
  'rel:classifies-document': ['Classifies document', 'The agent files documents into that content model.'],
  'rel:agent-event': ['Agent event', 'The external agent communicates through that event.'],
  'rel:channel-event': ['Carries event', 'The channel delivers that event type.'],
  'rel:service-dataObject': ['Service → data object', 'The service declares that data object as its reference type.'],
  'rel:body-template': ['Body template', 'The operation’s request body is rendered from that template.'],
  'rel:queryModel': ['Runs query', 'That query model provides the rows.'],
  'rel:extracts-from': ['Extracts from', 'Variables are extracted from instances of that model.'],
  'rel:template-form': ['Template form', 'The template’s parameters are collected with that form.'],
  'rel:worker-topic': ['Polls topic', 'The Java worker subscribes to that External Worker topic.'],
  'rel:filters-by-group': ['Filters by group', 'The query restricts results to members of that group.'],
  'rel:navigates-to': ['Navigates to', 'A button or link opens that in-app route.'],
  'rel:calls': ['Calls method', 'An expression or task calls that Java method.'],
  'rel:uses': ['Uses class', 'The class depends on that class.'],
  'rel:throws-signal': ['Throws signal', 'Publishes that signal for others to catch.'],
  'rel:catches-signal': ['Catches signal', 'Waits for that signal.'],
  'rel:throws-message': ['Sends message', 'Sends that message.'],
  'rel:catches-message': ['Receives message', 'Waits for that message.'],
  'rel:throws-error': ['Throws error', 'Raises that error code.'],
  'rel:catches-error': ['Catches error', 'Handles that error code.'],
  'rel:throws-escalation': ['Throws escalation', 'Raises that escalation.'],
  'rel:catches-escalation': ['Catches escalation', 'Handles that escalation.'],
  'rel:sla-definition-key': ['SLA', 'That SLA model’s thresholds apply here.'],
  'rel:security-policy-model': ['Security policy', 'That policy gates what roles may see and do here.'],
  'rel:eventType': ['Event type', 'The model publishes or consumes that event.'],
  'rel:channelKey': ['Channel', 'Events travel over that channel.'],
  'rel:datatable-detail-form': ['Data table detail form', 'The expandable row detail renders that form.'],
  'rel:static-form': ['Static form', 'A case-view element renders that form.'],
  'rel:manual-start-form': ['Manual start form', 'Manually starting the plan item opens that form.'],
  'rel:static-decision': ['Static decision table', 'A case-view element evaluates that decision table.'],
  'rel:inbound-channel': ['Received on channel', 'The event arrives over that channel.'],
  'rel:outbound-channel': ['Sent on channel', 'The event is published over that channel.'],
  'rel:relates-to-service': ['Relates to service', 'A column relation joins to that service’s table.'],
  'rel:watch': ['May watch', 'Who is added as a watcher.'],
  'rel:participate': ['May participate', 'Who participates in the instance.'],
  'rel:trigger': ['May trigger', 'Who may trigger the event listener.'],
  'rel:manually-start': ['May start manually', 'Who may manually start the plan item.'],
  'rel:use': ['May use', 'Who may use it.'],
  'rel:view': ['May view', 'Who may view it.'],
  'rel:document-create-form': ['Document create form', 'Creating a document opens that form.'],
  'rel:document-edit-form': ['Document edit form', 'Editing a document opens that form.'],
  'rel:dataObjectDataTableCreateFormKey': ['Data table create form', 'Creating a row opens that form.'],
  'rel:dataObjectDataTableEditFormKey': ['Data table edit form', 'Editing a row opens that form.'],
  'rel:dataObjectDataTableViewFormKey': ['Data table view form', 'Viewing a row opens that form.'],
};
// [label, hint] for a namespaced key, falling back to the raw key with no hint.
function term(ns, key){
  if(key==null||key==='') return {label:'', hint:''};
  const e=DESIGN_TERMS[ns+':'+key];
  if(e) return {label:e[0]||String(key), hint:e[1]||''};
  // Relations Atlas *composes* instead of taking from a vocabulary: a listener relation carries its
  // event (`taskListener:complete`) and a bean call names the method (`calls asText()`). Resolve them
  // from their stem — dumping the raw string on the reader is what the vocabulary is here to prevent.
  if(ns==='rel'){
    const m=String(key).match(/^([A-Za-z]+):(.+)$/);
    const base=m&&DESIGN_TERMS['el:'+m[1]];
    if(base) return {label:base[0]+' ('+m[2]+')', hint:base[1]||''};
    if(/^calls .+\(\)$/.test(key)) return {label:String(key), hint:(DESIGN_TERMS['rel:calls']||[])[1]||''};
  }
  return {label:String(key), hint:''};
}
// A term rendered as text plus a native tooltip, so hovering explains it.
function termHtml(ns, key, cls){
  const t=term(ns,key);
  if(!t.label) return '';
  const c='term'+(cls?' '+cls:'');
  return '<span class="'+c+'"'+(t.hint?' title="'+esc(t.hint)+'"':'')+'>'+esc(t.label)+'</span>';
}
// Section headings say "Execution listeners" where a single row says "Execution listener".
const plural = s => !s ? s : (/s$/.test(s) ? s : s+'s');
const SECTIONS = ['Models','Integration','Code','Expressions','Checks','Variables','Access','Other'];
// Colors are emitted as var() references, not resolved values: the browser resolves them
// at paint time, so a theme switch restyles everything without any re-render (and there is
// no getComputedStyle per node, which used to force a style recalculation in large lists).
const color = t => 'var(--c-'+t+', #79848f)';
const covColor = k => 'var(--cov-'+k+', #79848f)';
const debounce = (fn,ms) => { let t; return function(){ clearTimeout(t); t=setTimeout(()=>fn.apply(this,arguments),ms); }; };
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform||'');
const MODK = IS_MAC ? '⌘' : 'Ctrl';
const looseCol = s => String(s==null?'':s).toLowerCase().replace(/[^a-z0-9]/g,'');
// external nodes split into Flowable API / navigation routes / real third-party deps.
const nodeColor = n => (n && n.type==='external')
  ? (n.data&&n.data.flowableApi?color('endpoint'):n.data&&n.data.route?color('page'):color('external'))
  : color(n?n.type:'');
const nodeKind = n => (n.type!=='external')
  ? (TM[n.type]?TM[n.type][0]:n.type)
  : (n.data.flowableApi?'Flowable API':n.data.route?'Navigation route':'External / library');

// adjacency — entries carry the edge's suspect/dynamic flags so chips, relation lists and the
// ego graph can mark uncertain links; rebuilt when the uncertain-links toggle flips.
const outM = new Map(), incM = new Map();
let hideUncertain = false;
try{ hideUncertain = localStorage.getItem('atlas-uncertain')==='hide'; }catch(e){}
const push = (m,k,v)=>{ if(!m.has(k)) m.set(k,[]); m.get(k).push(v); };
function rebuildAdj(){
  outM.clear(); incM.clear();
  edges.forEach(e=>{
    if(hideUncertain && (e.suspect||e.dynamic)) return;
    push(outM,e.s,{rel:e.rel,id:e.t,sus:!!e.suspect,dyn:!!e.dynamic});
    push(incM,e.t,{rel:e.rel,id:e.s,sus:!!e.suspect,dyn:!!e.dynamic});
  });
}
rebuildAdj();

// bean name -> java node id (for direct links from ${bean.method()} expressions)
const beanToNode = new Map();
nodes.filter(n=>n.type==='java').forEach(n=>{
  (n.data.beanNames||[]).forEach(b=>beanToNode.set(b,n.id));
  const dc=n.label.charAt(0).toLowerCase()+n.label.slice(1);
  if(!beanToNode.has(dc)) beanToNode.set(dc,n.id);
});

// a form is "unused / unlinked" when nothing functionally references it — i.e. it
// has no incoming edge other than app 'contains' membership (every form sits in an
// app, so that edge alone does not count as being used).
const isUnusedForm = n => n.type==='form' && !(incM.get(n.id)||[]).some(e=>e.rel!=='contains');

// state — the URL hash is the single source of truth for navigation (routes below);
// `view` mirrors the active route, `cat`/`sel` drive the browse columns.
// `focus` is the search term the current selection was reached with — highlighted in the detail panel.
// `focusEl` is the model element a search hit came from — the detail panel opens that row directly.
let state = {view:'overview', cat:null, sel:null, filter:'', sort:'name', focus:'', focusEl:''};

// ---------- categories ----------
function categories(){
  const byType = {};
  nodes.forEach(n => (byType[n.type] = byType[n.type]||[]).push(n));
  const cats = [];
  Object.keys(byType).forEach(t=>{
    if(t==='java'){
      const roles = {};
      byType.java.forEach(n=>(n.data.roles||[]).forEach(r=>roles[r]=(roles[r]||0)+1));
      Object.keys(roles).sort().forEach(r=>cats.push({
        id:'java::'+r, label:'Java · '+r, sec:'Code', color:color('java'), count:roles[r],
        match:n=>n.type==='java' && (n.data.roles||[]).includes(r)}));
    } else if(t==='variable'){
      // group variables by the model type(s) that use them (process / form / case / java …)
      const scopes = {};
      byType.variable.forEach(n=>(n.data.scopes||[]).forEach(s=>scopes[s]=(scopes[s]||0)+1));
      Object.keys(scopes).sort().forEach(s=>cats.push({
        id:'variable::'+s, label:'Variable · '+s, sec:'Variables',
        color:color('variable'), count:scopes[s], match:n=>n.type==='variable' && (n.data.scopes||[]).includes(s)}));
      // Cross-cutting lens: the variables that actually travel through an in/out parameter mapping.
      const isParamVar=n=>n.type==='variable' && ((n.data||{}).ioParams||[]).length>0;
      const pc=byType.variable.filter(isParamVar).length;
      if(pc) cats.push({id:'variable::parameter', label:'Variable · parameter', sec:'Variables',
        color:color('variable'), count:pc, match:isParamVar});
    } else if(t==='external'){
      // external nodes are not all "library": split out Flowable platform API calls
      // (endpoints.*) and in-app navigation routes (#/...) from real third-party deps.
      [{id:'external::api',  label:'Flowable API',        sec:'Integration', color:color('endpoint'), match:n=>n.type==='external'&&n.data.flowableApi},
       {id:'external::route',label:'Navigation · routes', sec:'Other',       color:color('page'),     match:n=>n.type==='external'&&n.data.route},
       {id:'external::missing',label:'Missing model refs',sec:'Checks',      color:color('external'), match:n=>n.type==='external'&&n.data.missingModel},
       {id:'external::lib',  label:'External / library',  sec:'Other',       color:color('external'), match:n=>n.type==='external'&&!n.data.flowableApi&&!n.data.route&&!n.data.missingModel}
      ].forEach(c=>{ const count=byType.external.filter(c.match).length; if(count) cats.push(Object.assign({count}, c)); });
    } else {
      const m = TM[t]||[t,'Other'];
      cats.push({id:t,label:m[0],sec:m[1],color:color(t),count:byType[t].length,match:n=>n.type===t});
    }
  });
  // a review list: forms that nothing links to (orphaned UI models worth pruning)
  const unusedForms = nodes.filter(isUnusedForm);
  if(unusedForms.length) cats.push({id:'unused-form', label:'Forms · unused', sec:'Checks',
    color:color('form'), count:unusedForms.length, match:isUnusedForm});
  // Review lists for flagged expressions/bindings. Structural syntax errors make an
  // expression *invalid*; catalog findings (unknown function/namespace — the catalog may
  // simply not know a project-registered function) only make it *suspect*.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  const hasErr = n => isExprN(n) && (n.data.problems||[]).some(p=>p.severity==='error');
  const hasWarnOnly = n => isExprN(n) && (n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity==='error');
  const invalidExprs = nodes.filter(hasErr);
  if(invalidExprs.length) cats.push({id:'invalid-expr', label:'Invalid — syntax ⚠', sec:'Checks',
    color:color('invalidExpr'), count:invalidExprs.length, match:hasErr});
  const suspectExprs = nodes.filter(hasWarnOnly);
  if(suspectExprs.length) cats.push({id:'suspect-expr', label:'Suspect — review', sec:'Checks',
    color:color('suspectExpr'), count:suspectExprs.length, match:hasWarnOnly});
  // A changelog nobody references, or one superseded by a later revision, is a schema surprise waiting.
  const isChangelogIssue = n => n.type==='liquibase' &&
    ['orphan','superseded'].indexOf(((n.data||{}).authority||{}).status)>=0;
  const clIssues = nodes.filter(isChangelogIssue);
  if(clIssues.length) cats.push({id:'changelog-issue', label:'Changelogs · orphan / superseded', sec:'Checks',
    color:color('liquibase'), count:clIssues.length, match:isChangelogIssue});
  // Variables whose only evidence is a bare identifier in a script — probably real, not provable.
  const isGuessedVar = n => n.type==='variable' && (n.data||{}).heuristic===true;
  const guessed = nodes.filter(isGuessedVar);
  if(guessed.length) cats.push({id:'guessed-var', label:'Variables · script guess ≈', sec:'Checks',
    color:color('variable'), count:guessed.length, match:isGuessedVar});
  // Models with a script whose body (or scriptFormat) fails the structural syntax check.
  const scriptIssueModels = new Set(allScripts().filter(s=>(s.problems||[]).length).map(s=>s.model));
  if(scriptIssueModels.size) cats.push({id:'script-syntax', label:'Scripts · syntax ⚠', sec:'Checks',
    color:color('invalidExpr'), count:scriptIssueModels.size, match:n=>scriptIssueModels.has(n.id)});
  cats.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) || a.label.localeCompare(b.label));
  return cats;
}
const CATS = categories();

// ---------- insights (dashboard fuel) — one edge pass + one node pass at boot ----------
let INSIGHTS = null;
function computeInsights(){
  const indeg = new Map(), containsByApp = new Map(), openAppByApp = new Map(), entryPoints = [];
  edges.forEach(e=>{
    if(e.rel==='contains'){ containsByApp.set(e.s,(containsByApp.get(e.s)||0)+1); return; }
    const src = byId.get(e.s);
    if(src && src.type==='group'){
      if(e.rel==='open-app') openAppByApp.set(e.t,(openAppByApp.get(e.t)||0)+1);
      else if(e.rel==='start' && byId.get(e.t)) entryPoints.push({group:e.s, model:e.t});
      return;                                    // access edges don't count as "references"
    }
    if(byId.get(e.t)) indeg.set(e.t,(indeg.get(e.t)||0)+1);
  });
  const hotspots = [...indeg.entries()].filter(x=>x[1]>0 && byId.get(x[0]))
    .sort((a,b)=> b[1]-a[1] || byId.get(a[0]).label.localeCompare(byId.get(b[0]).label))
    .slice(0,10).map(x=>({id:x[0], count:x[1]}));
  const isExprN = n => n.type==='expression'||n.type==='binding';
  let invalidExpr=0, suspectExpr=0, unusedForms=0, changelogIssues=0, schemaGaps=0,
      unusedOps=0, unusedFns=0, missingRefs=0, guessedVars=0,
      totalExprs=0, totalForms=0, totalChangelogs=0,
      totalCovServices=0, totalOps=0, totalFns=0;
  nodes.forEach(n=>{
    const d=n.data||{};
    if(isExprN(n)){ totalExprs++; const pr=d.problems||[];
      if(pr.length){ if(pr.some(p=>p.severity==='error')) invalidExpr++; else suspectExpr++; } }
    else if(n.type==='form'){ totalForms++; if(isUnusedForm(n)) unusedForms++; }
    else if(n.type==='liquibase'){ totalChangelogs++; const st=(d.authority||{}).status;
      if(st==='orphan'||st==='superseded') changelogIssues++; }
    else if(n.type==='service'){ const c=(d.schemaCoverage||{}).counts;
      if(c){ totalCovServices++; schemaGaps+=(c.noService||0)+(c.noDataObject||0); } }
    else if(n.type==='serviceOperation'){ totalOps++; if(!(d.usedBy||[]).length) unusedOps++; }
    else if(n.type==='customFunction'){ totalFns++; if(!(d.usedBy||[]).length) unusedFns++; }
    else if(n.type==='external' && d.missingModel) missingRefs++;
    if(n.type==='variable' && d.heuristic===true) guessedVars++;
  });
  const apps = nodes.filter(n=>n.type==='app')
    .map(a=>({id:a.id, models:containsByApp.get(a.id)||0, groups:openAppByApp.get(a.id)||0}))
    .sort((a,b)=>b.models-a.models);
  // scripts are not nodes — count their structural syntax findings from the flattened rows
  const scripts = allScripts();
  const scriptIssues = scripts.filter(s=>(s.problems||[]).length).length;
  const health = { parseIssues: diags.length+cfnDiags.length, invalidExpr, suspectExpr, scriptIssues,
                   unusedForms, changelogIssues, schemaGaps, missingRefs, guessedVars, unusedOps, unusedFns };
  INSIGHTS = { indeg, hotspots, apps, entryPoints,
    totalExprs, totalForms, totalChangelogs, totalCovServices, totalOps, totalFns,
    totalScripts: scripts.length,
    health,
    // what the Checks tab counts in its badge: every open finding, in one number
    checksOpen: Object.keys(health).reduce((a,k)=>a+health[k],0) };
}

// ---------- router — the hash is the single source of truth and the history ----------
// ''              -> overview (default)
// #/overview      -> overview
// #/schema        -> schema-gaps report (Liquibase → Service → Data object)
// #/checks        -> everything worth a look, in one place
// #/scripts       -> every script body in the project
// #/browse/<cat>  -> browse, category list without selection
// #<nodeId>       -> legacy permalink format: browse with that node selected (kept so
//                    every previously copied link keeps working). enc() escapes '/', so
//                    dispatching on the RAW leading '/' before decoding is unambiguous.
function parseHash(){
  const raw = location.hash.slice(1);
  if(!raw || raw==='/overview') return {view:'overview'};
  if(raw==='/schema') return {view:'schema'};
  if(raw==='/scripts') return {view:'scripts'};
  if(raw==='/checks') return {view:'checks'};
  if(raw.indexOf('/browse/')===0){
    const cat = dec(raw.slice(8));
    return CATS.some(c=>c.id===cat) ? {view:'browse', cat} : {view:'overview'};
  }
  if(raw.charAt(0)==='/') return {view:'overview'};      // unknown route
  // A node route may carry the search term that led here and the element the hit came from:
  // `#<encId>&q=<encTerm>&e=<encElementId>`. Every part is URI-encoded, so a literal '&' cannot occur
  // inside one and the splits are unambiguous.
  const amp = raw.indexOf('&q=');
  const ael = raw.indexOf('&e=');
  const cut = amp<0 ? ael : (ael<0 ? amp : Math.min(amp, ael));
  const id  = dec(cut<0 ? raw : raw.slice(0, cut));
  const q   = amp<0 ? '' : dec(ael>amp ? raw.slice(amp+3, ael) : raw.slice(amp+3));
  const e   = ael<0 ? '' : dec(raw.slice(ael+3));
  return byId.get(id) ? {view:'browse', sel:id, q, e} : {view:'overview'};
}
function showView(v){
  document.getElementById('view-overview').hidden = v!=='overview';
  document.getElementById('view-schema').hidden = v!=='schema';
  document.getElementById('view-scripts').hidden = v!=='scripts';
  document.getElementById('view-checks').hidden = v!=='checks';
  document.getElementById('view-browse').hidden = v!=='browse';
}
let _navCount = 0;
function route(){
  closePalette();
  _navCount++;
  const r = parseHash();
  state.focus = r.q || '';
  state.focusEl = r.e || '';
  if(r.view==='overview'){
    state.view='overview'; state.sel=null;
    showView('overview'); renderDashboard();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='schema'){
    state.view='schema'; state.sel=null;
    showView('schema'); renderSchema();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='scripts'){
    state.view='scripts'; state.sel=null;
    showView('scripts'); renderScripts();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='checks'){
    state.view='checks'; state.sel=null;
    showView('checks'); renderChecks();
    renderSidebarActive(); renderCrumbs();
  } else if(r.sel){
    applySelection(r.sel);                                // handles view/list/detail/crumbs
  } else {
    state.view='browse';
    if(state.cat!==r.cat){ state.cat=r.cat; state.filter=''; }
    state.sel=null;
    showView('browse'); renderList(); renderDetail();
    renderSidebarActive(); renderCrumbs();
  }
}

// ---------- sidebar ----------
function renderSidebar(){
  const nav = document.getElementById('nav'); nav.innerHTML='';
  const mkItem = (html, title) => {
    const el=document.createElement('div');
    el.className='side-item'; el.setAttribute('role','button'); el.tabIndex=0;
    // No tooltip on a menu entry: the label and count are right there, and a bubble popping up under
    // the cursor while you slide down the list is pure noise. In rail mode the sidebar flies out on
    // hover, so the label is never actually hidden. The text stays available to screen readers.
    el.setAttribute('aria-pressed','false'); el.setAttribute('aria-label', title); el.innerHTML=html;
    el.onkeydown=e=>{
      if(e.key==='Enter'||e.key===' '){ e.preventDefault(); el.click(); }
      else if(e.key==='ArrowDown'||e.key==='ArrowUp'){
        e.preventDefault();
        const items=[...nav.querySelectorAll('.side-item')];
        const i=items.indexOf(el)+(e.key==='ArrowDown'?1:-1);
        if(items[i]) items[i].focus();
      }
    };
    return el;
  };
  const ov = mkItem('<span class="dot" style="background:var(--accent)"></span><span class="lbl">Overview</span>','Overview');
  ov.dataset.route='/overview';
  ov.onclick=()=>{ location.hash='/overview'; };
  nav.appendChild(ov);
  // A tab belongs to a section like any other list — "Script tasks" is an Integration thing, the
  // review reports belong under Checks. `pri` keeps a section's tabs above its drill-down lists.
  const items=[...CATS];
  const scriptCount=allScripts().length;
  if(scriptCount) items.push({route:'/scripts', label:'Script tasks', sec:'Integration', pri:0,
    color:color('process'), count:scriptCount,
    tip:'Script tasks ('+scriptCount+') — every script task, listener script and bot script'});
  const openChecks=INSIGHTS.checksOpen;
  items.push({route:'/checks', label:'Checks', sec:'Checks', pri:0,
    color:covColor(openChecks?'bad':'good'), count:openChecks,
    tip:'Everything worth a look — parse issues, flagged expressions, schema gaps, unused and unproven models'});
  if(INSIGHTS.totalCovServices>0){
    const gaps=INSIGHTS.health.schemaGaps;
    items.push({route:'/schema', label:'Schema gaps', sec:'Checks', pri:1,
      color:covColor(gaps?'bad':'good'), count:gaps,
      tip:'Schema gaps — Liquibase → Service → Data object coverage'});
  }
  items.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) ||
                     ((a.pri==null?2:a.pri)-(b.pri==null?2:b.pri)) || a.label.localeCompare(b.label));
  let cur='';
  items.forEach(c=>{
    if(c.sec!==cur){ cur=c.sec; const h=document.createElement('div'); h.className='side-group'; h.textContent=cur; nav.appendChild(h); }
    const el = mkItem('<span class="dot" style="background:'+c.color+'"></span><span class="lbl">'+esc(c.label)+'</span>'+
                      (c.count?'<span class="n">'+c.count+'</span>':''),
                      c.tip||(c.label+' ('+c.count+')'));
    if(c.route){ el.dataset.route=c.route; el.onclick=()=>{ location.hash=c.route; }; }
    else { el.dataset.cat=c.id; el.onclick=()=>{ location.hash='/browse/'+enc(c.id); }; }
    nav.appendChild(el);
  });
  // footer warning chip — routes to the dashboard and reveals the diagnostics list
  if(diags.length+cfnDiags.length){
    const chip=document.getElementById('diagchip');
    const n=diags.length+cfnDiags.length;
    chip.hidden=false;
    chip.innerHTML='⚠<span class="wtxt">&nbsp;'+n+' parse issue'+(n>1?'s':'')+'</span>';
    chip.setAttribute('aria-label',
      n+' parse issue'+(n>1?'s':'')+' — files the generator could not fully analyze');
    chip.onclick=()=>{
      _checkJump='chk-parse';
      if(state.view==='checks') renderChecks(); else location.hash='/checks';
    };
  }
}
function renderSidebarActive(){
  document.querySelectorAll('#nav .side-item').forEach(el=>{
    const on = el.dataset.route ? el.dataset.route==='/'+state.view
                                : (state.view==='browse' && state.cat===el.dataset.cat);
    el.classList.toggle('on', on);
    el.setAttribute('aria-pressed', on?'true':'false');
  });
}

// ---------- topbar breadcrumbs ----------
function renderCrumbs(){
  const c=document.getElementById('crumbs');
  const sep='<span class="crumb-sep">/</span>';
  const link=(txt,href)=>'<a class="crumb" href="'+href+'">'+esc(txt)+'</a>';
  const cur=(txt)=>'<span class="crumb cur">'+esc(txt)+'</span>';
  let h, title;
  if(state.view==='overview'){
    h=link(DATA.project,'#/overview')+sep+cur('Overview');
    title='Flowable Atlas — '+DATA.project;
  } else if(state.view==='schema'){
    h=link(DATA.project,'#/overview')+sep+cur('Schema gaps');
    title='Schema gaps — Flowable Atlas';
  } else if(state.view==='scripts'){
    h=link(DATA.project,'#/overview')+sep+cur('Script tasks');
    title='Script tasks — Flowable Atlas';
  } else if(state.view==='checks'){
    h=link(DATA.project,'#/overview')+sep+cur('Checks');
    title='Checks — Flowable Atlas';
  } else {
    const cat=CATS.find(x=>x.id===state.cat);
    const n=state.sel&&byId.get(state.sel);
    h=link(DATA.project,'#/overview');
    if(cat) h+=sep+(n?link(cat.label,'#/browse/'+enc(cat.id)):cur(cat.label));
    if(n) h+=sep+cur(n.label);
    title=(n?n.label:(cat?cat.label:'Browse'))+' — Flowable Atlas';
  }
  c.innerHTML=h;
  document.title=title;
}

// ---------- dashboard (#/overview) ----------
// A health card on the overview is a shortcut into the Checks tab: remember which block it wants and
// let `renderChecks()` scroll there once the route has landed.
let _checkJump=null;
function renderDashboard(){
  const v=document.getElementById('view-overview');
  const st=DATA.stats||{}, H=INSIGHTS.health;
  let h='<div class="dash">';
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  const uncertain=(suN||dyN)?' · '+[suN?suN+' suspect':'',dyN?dyN+' dynamic':''].filter(Boolean).join(' + ')
    +' <span title="suspect = loose/cross-type match — dynamic = expression-valued reference">link'+((suN+dyN)>1?'s':'')+'</span>':'';
  h+='<div class="dash-title">'+esc(DATA.project)+'</div>'+
     '<div class="dash-sub">'+nodes.length+' nodes · '+edges.length+' links'+uncertain+' across the model &amp; code graph</div>';
  // inventory
  h+='<div class="seclabel">Inventory</div><div class="metrics">';
  [['Models',st.models,'published model files'],['Java classes',st.java,'scanned source classes'],
   ['REST endpoints',st.endpoints,'served by controllers'],['User groups',st.groups,'referenced in access rules']]
    .forEach(m=>{ h+='<div class="metric"><div class="mk">'+m[0]+'</div><div class="mv">'+(m[1]||0)+'</div><div class="ms">'+m[2]+'</div></div>'; });
  h+='</div>';
  // health — the same cards the Checks tab shows; the overview stays a summary and links there for the
  // findings themselves (one place to review, instead of two that drift apart)
  const cardsHtml=healthCardsHtml();
  if(cardsHtml){
    const open=INSIGHTS.checksOpen;
    h+='<div class="seclabel" style="display:flex;align-items:center;gap:var(--space-2)">Health'+
       '<button class="dgbtn" data-route="/checks">'+
       (open?open+' finding'+(open>1?'s':'')+' to review ↗':'open Checks ↗')+'</button></div>'+cardsHtml;
  }
  // hotspots
  if(INSIGHTS.hotspots.length){
    h+='<div class="seclabel">Hotspots — most referenced</div><div class="dashrows">';
    INSIGHTS.hotspots.forEach(x=>{
      const n=byId.get(x.id);
      h+='<div class="dashrow" data-id="'+enc(x.id)+'" role="link" tabindex="0">'+
         '<span class="dot" style="background:'+nodeColor(n)+'"></span>'+
         '<span class="nm">'+esc(n.label)+'</span><span class="ty">'+esc(nodeKind(n))+'</span>'+
         '<span class="pill">'+x.count+' refs</span></div>';
    });
    h+='</div>';
  }
  // apps
  if(INSIGHTS.apps.length){
    h+='<div class="seclabel">Apps</div><div class="dashrows">';
    INSIGHTS.apps.forEach(a=>{
      const n=byId.get(a.id); if(!n) return;
      h+='<div class="dashrow" data-id="'+enc(a.id)+'" role="link" tabindex="0">'+
         '<span class="dot" style="background:'+color('app')+'"></span>'+
         '<span class="nm">'+esc(n.label)+'</span>'+
         (a.groups?'<span class="ty">'+a.groups+' group'+(a.groups>1?'s':'')+' can open</span>':'')+
         '<span class="pill">'+a.models+' models</span></div>';
    });
    h+='</div>';
  }
  // entry points — who can start what
  if(INSIGHTS.entryPoints.length){
    const eps=INSIGHTS.entryPoints.slice(0,50);
    h+='<div class="seclabel">Entry points — who can start what</div><div class="dashrows">';
    eps.forEach(ep=>{
      h+='<div class="dashrow">'+nodeChip(ep.group)+'<span class="sep">can start</span>'+nodeChip(ep.model)+'</div>';
    });
    if(INSIGHTS.entryPoints.length>eps.length)
      h+='<div class="dashrow muted">+ '+(INSIGHTS.entryPoints.length-eps.length)+' more</div>';
    h+='</div>';
  }
  h+='</div>';
  v.innerHTML=h;
  v.onclick=e=>{
    const jump=e.target.closest('[data-jump]');
    if(jump){ _checkJump=jump.dataset.jump; location.hash='/checks'; return; }
    const idEl=e.target.closest('[data-id]');
    if(idEl){ select(dec(idEl.dataset.id)); return; }
    const rtEl=e.target.closest('[data-route]');
    if(rtEl){ location.hash=rtEl.dataset.route; return; }
    const catEl=e.target.closest('[data-cat]');
    if(catEl){ location.hash='/browse/'+enc(catEl.dataset.cat); return; }
  };
  v.onkeydown=e=>{
    if(e.key!=='Enter'&&e.key!==' ') return;
    const t=e.target.closest('[data-id],[data-route],[data-cat],[data-jump]');
    if(t){ e.preventDefault(); t.click(); }
  };
}

// ---------- schema coverage: one renderer for the service detail AND the schema tab ----------
// `onlyGaps` filters the table to the problem rows (the schema tab's view of the world);
// `leadChipId` puts the owning service's chip first on the meta line.
function schemaCoverageHtml(sc, onlyGaps, leadChipId){
  const ct=sc.counts||{};
  let b='';
  // owning service / source changelog / backing data objects (clickable)
  let meta=leadChipId?nodeChip(leadChipId):'';
  if(sc.liquibase){ const lc=nodeChip('liquibase:'+sc.liquibase); if(lc) meta+='<span class="muted">changelog</span>'+lc; }
  (sc.dataObjects||[]).forEach(k=>{ const dc=nodeChip('dataObject:'+k); if(dc) meta+=dc; });
  if(meta) b+='<div class="covmeta">'+meta+'</div>';
  // gap summary
  let badges='';
  if(ct.noService) badges+='<span class="cov-badge cov-bad">'+ct.noService+' not mapped in service</span>';
  if(ct.noDataObject) badges+='<span class="cov-badge cov-warn">'+ct.noDataObject+' not in data object</span>';
  if(ct.extra) badges+='<span class="cov-badge cov-info">'+ct.extra+' not in Liquibase</span>';
  if(ct.ok) badges+='<span class="cov-badge cov-good">'+ct.ok+' mapped through</span>';
  if(badges) b+='<div class="covbadges">'+badges+'</div>';
  const rowCls={'no-service':'cov-bad','no-dataobject':'cov-warn','extra-service':'cov-info','ok':''};
  const miss='<span class="miss">✗ not mapped</span>';
  const rows=onlyGaps?(sc.rows||[]).filter(r=>r.status!=='ok'):(sc.rows||[]);
  if(rows.length){
    b+='<div class="covwrap"><table class="cov"><thead><tr>'+
       '<th>Liquibase column</th><th>Service mapping</th><th>Data object field</th></tr></thead><tbody>';
    rows.forEach(r=>{
      const lbCell = r.inLiquibase
        ? '<span>'+esc(r.sql)+'</span>'+(r.sqlType?' <span class="muted">'+esc(r.sqlType)+'</span>':'')
        : '<span class="miss">— not in changelog</span>';
      const svCell = r.inService
        ? '<span>'+esc(r.service||r.serviceCol||'')+'</span>'+
          (r.serviceCol&&looseCol(r.serviceCol)!==looseCol(r.service||'')?' <span class="muted">'+esc(r.serviceCol)+'</span>':'')+
          (r.serviceType?' <span class="muted">'+esc(r.serviceType)+'</span>':'')
        : miss;
      const doCell = (r.dataObjects&&r.dataObjects.length)
        ? r.dataObjects.map(x=>'<span>'+esc(x.field)+'</span>'+
            ((sc.dataObjects||[]).length>1?' <span class="muted">'+esc(x.do)+'</span>':'')).join(', ')
        : (r.inLiquibase||r.inService?miss:'');
      b+='<tr class="'+(rowCls[r.status]||'')+'"><td>'+lbCell+'</td><td>'+svCell+'</td><td>'+doCell+'</td></tr>';
    });
    b+='</tbody></table></div>';
  }
  if(onlyGaps&&ct.ok) b+='<div class="muted" style="font-size:var(--text-xs);margin:var(--space-1) 0 0">+ '+ct.ok+' column'+(ct.ok>1?'s':'')+' mapped through cleanly — full table on the service page</div>';
  return b;
}

// ---------- schema-gaps tab (#/schema) ----------
// The dashboard's "Schema gaps" number, unfolded: every service with coverage data, its problem rows
// front and center, fully-mapped services collapsed to a chip row at the bottom.
function renderSchema(){
  const v=document.getElementById('view-schema');
  const svcs=nodes.filter(n=>n.type==='service'&&(n.data||{}).schemaCoverage&&((n.data.schemaCoverage.rows||[]).length))
    .map(n=>{ const c=n.data.schemaCoverage.counts||{};
      return {n, sc:n.data.schemaCoverage, gaps:(c.noService||0)+(c.noDataObject||0), extra:c.extra||0}; })
    .sort((a,b)=> (b.gaps+b.extra)-(a.gaps+a.extra) || a.n.label.localeCompare(b.n.label));
  const dirty=svcs.filter(s=>s.gaps||s.extra), clean=svcs.filter(s=>!s.gaps&&!s.extra);
  const total=svcs.reduce((a,s)=>a+s.gaps,0);
  let h='<div class="dash">';
  h+='<div class="dash-title">Schema gaps</div>'+
     '<div class="dash-sub">Liquibase → Service → Data object — '+
     (svcs.length===0?'no service declares schema coverage data'
      :total?total+' column'+(total>1?'s':'')+' not mapped through, in '+dirty.length+' of '+svcs.length+' service'+(svcs.length>1?'s':'')
      :'every column of all '+svcs.length+' service'+(svcs.length>1?'s':'')+' maps through cleanly')+'</div>';
  if(!svcs.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">▦</div>'+
       '<div class="et">Nothing to check</div>'+
       '<div class="eh">No service in this project references a Liquibase changelog, so there is no schema to compare against.</div></div>';
  }
  dirty.forEach(s=>{
    h+='<div class="seclabel">'+esc(s.n.label)+(s.sc.table?' — <span class="mono">'+esc(s.sc.table)+'</span>':'')+'</div>'+
       '<div class="schemasvc">'+schemaCoverageHtml(s.sc, true, s.n.id)+'</div>';
  });
  if(clean.length){
    h+='<div class="seclabel">Fully mapped ('+clean.length+')</div><div class="nodechips">'+
       clean.map(s=>nodeChip(s.n.id)).join('')+'</div>';
  }
  h+='</div>';
  v.innerHTML=h;
  v.onclick=e=>{ const idEl=e.target.closest('[data-id]'); if(idEl) select(dec(idEl.dataset.id)); };
  v.onkeydown=e=>{ if(e.key!=='Enter'&&e.key!==' ') return;
    const t=e.target.closest('[data-id]'); if(t){ e.preventDefault(); t.click(); } };
}

// ---------- checks view (#/checks) ----------
// Everything Atlas cannot answer for you, on one page: parse issues, flagged expressions, schema gaps,
// models nothing references, references to models that do not exist, and the variables only a script
// guess supports. The sidebar's Checks section holds this tab plus the drill-down list per finding.
const CHECK_CARDS = [
  {k:'parseIssues', label:'Parse issues', bad:true, jump:'chk-parse',
   sub:c=>c?'files the analyzer could not fully read':'all files analyzed cleanly', show:()=>true},
  {k:'invalidExpr', label:'Invalid expressions', bad:true, cat:'invalid-expr', jump:'chk-invalid',
   sub:c=>c?'syntax errors in ${ } / {{ }}':'no syntax errors', show:()=>INSIGHTS.totalExprs>0},
  {k:'suspectExpr', label:'Suspect expressions', cat:'suspect-expr', jump:'chk-suspect',
   sub:c=>c?'flagged for review by the catalog':'nothing flagged', show:()=>INSIGHTS.totalExprs>0},
  {k:'scriptIssues', label:'Script syntax', bad:true, cat:'script-syntax', jump:'chk-scripts',
   sub:c=>c?'syntax & binding findings in script bodies':'all scripts scan clean', show:()=>INSIGHTS.totalScripts>0},
  {k:'schemaGaps', label:'Schema gaps', bad:true, route:'/schema', jump:'chk-schema',
   sub:c=>c?'columns not mapped through Liquibase → service → data object':'all columns mapped through',
   show:()=>INSIGHTS.totalCovServices>0},
  {k:'missingRefs', label:'Missing model refs', bad:true, cat:'external::missing', jump:'chk-missing',
   sub:c=>c?'a key is referenced but no model defines it':'every referenced key resolves', show:()=>true},
  {k:'unusedForms', label:'Unused forms', cat:'unused-form', jump:'chk-unusedforms',
   sub:c=>c?'no model links to them':'every form is referenced', show:()=>INSIGHTS.totalForms>0},
  {k:'changelogIssues', label:'Changelog issues', cat:'changelog-issue', jump:'chk-changelogs',
   sub:c=>c?'orphan or superseded changelogs':'all changelogs are authoritative',
   show:()=>INSIGHTS.totalChangelogs>0},
  {k:'guessedVars', label:'Variables · script guess', cat:'guessed-var', jump:'chk-guessed',
   sub:c=>c?'only a bare identifier in a script names them':'every variable is declared somewhere',
   show:()=>true},
  {k:'unusedOps', label:'Unused operations', cat:'serviceOperation', jump:'chk-unusedops',
   sub:c=>c?c+' of '+INSIGHTS.totalOps+' operations are never called from a model':'every operation is used',
   show:()=>INSIGHTS.totalOps>0},
  {k:'unusedFns', label:'Unused custom functions', cat:'customFunction', jump:'chk-unusedfns',
   sub:c=>c?c+' of '+INSIGHTS.totalFns+' functions are never called':'every function is used',
   show:()=>INSIGHTS.totalFns>0},
];
function healthCardsHtml(){
  const H=INSIGHTS.health;
  const cards=CHECK_CARDS.filter(c=>c.show());
  if(!cards.length) return '';
  return '<div class="health">'+cards.map(c=>{
    const n=H[c.k], tone=n===0?'ok':(c.bad?'bad':'warn');
    const attrs=n>0?' data-jump="'+c.jump+'" role="button" tabindex="0"':'';
    return '<div class="hcard tone-'+tone+(n>0?' click':'')+'"'+attrs+'>'+
      '<div class="mk">'+esc(c.label)+'</div><div class="mv">'+n+'</div>'+
      '<div class="ms">'+esc(c.sub(n))+'</div></div>';
  }).join('')+'</div>';
}
function renderChecks(){
  const v=document.getElementById('view-checks');
  const H=INSIGHTS.health, st=DATA.stats||{};
  const open=INSIGHTS.checksOpen;
  let h='<div class="dash">';
  h+='<div class="dash-title">Checks</div>'+
     '<div class="dash-sub">'+(open
       ? open+' finding'+(open>1?'s':'')+' worth a look — none of them is automatically a bug, each one is a '+
         'question Atlas cannot answer on its own'
       : 'nothing flagged — no parse issues, no broken expressions, nothing unused or unproven')+'</div>';
  h+=healthCardsHtml();
  // one block per finding, with the actual items rather than only a number
  const block=(id,title,count,body,cat)=>{
    if(!count) return '';
    const list=cat&&CATS.some(x=>x.id===cat)
      ? '<button class="dgbtn" data-cat="'+esc(cat)+'">open the list ↗</button>' : '';
    return '<div class="seclabel" id="'+id+'" style="display:flex;align-items:center;gap:var(--space-2)">'+
      esc(title)+' <span class="muted">'+count+'</span>'+list+'</div>'+body;
  };
  const chips=list=>'<div class="nodechips">'+list.map(n=>nodeChip(n.id)).join('')+'</div>';
  // parse issues — the analyzer's own honesty about what it could not read
  if(diags.length+cfnDiags.length){
    h+=block('chk-parse','Parse issues', diags.length+cfnDiags.length,
      '<div class="dashrows">'+
      diags.map(d=>'<div class="dp-row"><span class="dp-kind">'+esc(d.kind)+'</span>'+
        '<span class="dp-path mono">'+esc(d.path)+'</span><span class="dp-msg">'+esc(d.message)+'</span></div>').join('')+
      cfnDiags.map(m=>'<div class="dp-row"><span class="dp-kind">custom-fn</span>'+
        '<span class="dp-msg">'+esc(m)+'</span></div>').join('')+'</div>');
  }
  // flagged expressions: the message and who uses them, so the fix is one click away
  const exprRows=list=>'<div class="dashrows">'+list.slice(0,60).map(n=>{
    const pr=(n.data||{}).problems||[];
    return '<div class="dashrow" style="align-items:flex-start;flex-wrap:wrap">'+
      '<span class="nm mono" data-id="'+enc(n.id)+'" role="link" tabindex="0" style="flex:1;min-width:200px">'+
        esc(n.label)+'</span>'+
      '<span class="ty">'+esc(pr.map(p=>p.message).join(' · '))+'</span>'+
      ((n.data||{}).usedBy||[]).slice(0,4).map(id=>byId.get(id)?nodeChip(id):'').join('')+
      '</div>';
  }).join('')+(list.length>60?'<div class="dashrow muted">+ '+(list.length-60)+' more — open the list</div>':'')+'</div>';
  const byCat=id=>{ const c=CATS.find(x=>x.id===id); return c?nodes.filter(c.match):[]; };
  h+=block('chk-invalid','Invalid expressions — syntax', H.invalidExpr, exprRows(byCat('invalid-expr')), 'invalid-expr');
  h+=block('chk-suspect','Suspect expressions — review', H.suspectExpr, exprRows(byCat('suspect-expr')), 'suspect-expr');
  // script syntax findings: model, element, language, then each finding with its line and code
  if(H.scriptIssues){
    const rows=allScripts().filter(s=>(s.problems||[]).length);
    h+=block('chk-scripts','Script syntax findings', H.scriptIssues,
      '<div class="dashrows">'+rows.map(s=>{
        const kind=scriptKindLabel(s);
        const title=s.elName||s.el||(s.group==='bot'?s.modelLabel:kind);
        return '<div class="dashrow" style="align-items:flex-start;flex-wrap:wrap">'+
          nodeChip(s.model)+
          '<span class="nm mono" style="min-width:140px">'+esc(title)+'</span>'+
          '<span class="pt">'+esc(kind)+'</span>'+
          (s.lang?'<span class="pt">'+esc(s.lang)+'</span>':'')+
          '<span class="ty" style="flex-basis:100%;display:flex;flex-direction:column;gap:2px">'+
            s.problems.map(p=>'<span><span style="color:var(--'+(p.severity==='error'?'bad':'warn')+'-text)">'+
              esc(p.severity)+'</span> '+esc(p.message)+
              (p.line?' <span class="muted">· line '+p.line+'</span>':'')+
              (p.snippet?' <span class="mono muted">'+esc(p.snippet)+'</span>':'')+'</span>').join('')+
          '</span></div>';
      }).join('')+
      '<div class="dashrow"><button class="dgbtn" data-route="/scripts">open the scripts tab ↗</button></div>'+
      '</div>', 'script-syntax');
  }
  // schema gaps: the per-service summary; the full column table lives in its own tab
  if(H.schemaGaps){
    const svcs=nodes.filter(n=>n.type==='service'&&((n.data||{}).schemaCoverage||{}).counts)
      .map(n=>{ const c=n.data.schemaCoverage.counts; return {n, gaps:(c.noService||0)+(c.noDataObject||0)}; })
      .filter(x=>x.gaps).sort((a,b)=>b.gaps-a.gaps);
    h+=block('chk-schema','Schema gaps', H.schemaGaps,
      '<div class="dashrows">'+svcs.map(x=>'<div class="dashrow">'+nodeChip(x.n.id)+
        '<span class="ty">'+x.gaps+' column'+(x.gaps>1?'s':'')+' not mapped through</span></div>').join('')+
      '<div class="dashrow"><button class="dgbtn" data-route="/schema">open the full report ↗</button></div></div>');
  }
  h+=block('chk-missing','Missing model references', H.missingRefs, chips(byCat('external::missing')), 'external::missing');
  h+=block('chk-unusedforms','Unused forms', H.unusedForms, chips(byCat('unused-form')), 'unused-form');
  h+=block('chk-changelogs','Changelogs · orphan / superseded', H.changelogIssues,
    chips(byCat('changelog-issue')), 'changelog-issue');
  h+=block('chk-guessed','Variables · only a script guess ≈', H.guessedVars, chips(byCat('guessed-var')), 'guessed-var');
  h+=block('chk-unusedops','Unused service operations', H.unusedOps,
    chips(nodes.filter(n=>n.type==='serviceOperation'&&!((n.data||{}).usedBy||[]).length)), 'serviceOperation');
  h+=block('chk-unusedfns','Unused custom functions', H.unusedFns,
    chips(nodes.filter(n=>n.type==='customFunction'&&!((n.data||{}).usedBy||[]).length)), 'customFunction');
  // uncertain edges are a property of the graph, not of one node — say so once
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  if(suN+dyN){
    h+='<div class="seclabel">Uncertain links <span class="muted">'+(suN+dyN)+'</span></div>'+
       '<div class="dashrows"><div class="dashrow muted">'+
       (suN?suN+' suspect (≈ resolved by a loose or cross-type match)':'')+
       (suN&&dyN?' · ':'')+(dyN?dyN+' dynamic (ƒ expression-valued reference)':'')+
       ' — the ≈ button in the toolbar hides them everywhere.</div></div>';
  }
  if(!open) h+='<div class="estate"><div class="estate-ic" aria-hidden="true">✓</div>'+
    '<div class="et">Nothing to check</div>'+
    '<div class="eh">No parse issue, no flagged expression, no unused or unresolved model.</div></div>';
  h+='</div>';
  v.innerHTML=h;
  v.onclick=e=>{
    const jump=e.target.closest('[data-jump]');
    if(jump){ const t=document.getElementById(jump.dataset.jump);
      if(t) t.scrollIntoView({block:'start'}); return; }
    const cat=e.target.closest('[data-cat]');
    if(cat){ location.hash='/browse/'+enc(cat.dataset.cat); return; }
    const route=e.target.closest('[data-route]');
    if(route){ location.hash=route.dataset.route; return; }
    const idEl=e.target.closest('[data-id]');
    if(idEl) select(dec(idEl.dataset.id));
  };
  v.onkeydown=e=>{ if(e.key!=='Enter'&&e.key!==' ') return;
    const t=e.target.closest('[data-jump],[data-cat],[data-route],[data-id]');
    if(t){ e.preventDefault(); t.click(); } };
  // arrived from a health card or the parse-issue chip: land on the block it asked for
  if(_checkJump){
    const target=document.getElementById(_checkJump);
    _checkJump=null;
    if(target) requestAnimationFrame(()=>target.scrollIntoView({block:'start'}));
  }
}

// ---------- script tasks view (every script body in the project, in one place) ----------
// A script is not a node of its own — it lives inside a script task, a CMMN plan item, a listener or a
// bot — so "show me all the code in this project" used to mean opening every model in turn. Rebuilt on
// each visit from the payload; there is nothing to cache and the counts stay honest.
// `elKind` is the Design element the script belongs to (`scriptTask`, `executionListener`, …); `group`
// is the coarse bucket the filter chips work on.
function allScripts(){
  const out=[];
  // a row with findings but no body (an empty script task) still deserves a row — that IS the finding
  const add=(n,o)=>{ if(o.body||(o.problems||[]).length)
    out.push(Object.assign({model:n.id, modelLabel:n.label, modelType:n.type}, o)); };
  nodes.forEach(n=>{
    const d=n.data||{};
    if(n.type==='process'){
      (d.scriptTasks||[]).forEach(t=>add(n,{group:'script', elKind:'scriptTask', el:t.id, elName:t.name,
        lang:t.format||t.scriptFormat, body:t.script, doc:t.documentation, out:t.resultVariable,
        problems:t.problems||[]}));
    }
    if(n.type==='case' && d.planModel){
      // CMMN keeps its script tasks in the plan tree (`<task flowable:type="script">`)
      (function walk(nd){
        if(nd.script||(nd.problems||[]).length) add(n,{group:'script', elKind:'serviceTask/script',
          el:nd.id, elName:nd.name, lang:nd.scriptFormat, body:nd.script, doc:nd.documentation,
          problems:nd.problems||[]});
        (nd.children||[]).forEach(walk);
      })(d.planModel);
    }
    if(n.type==='process'||n.type==='case'){
      const listener=(r,l)=>({group:'listener', elKind:l.kind, event:l.event,
        el:r?r.id:null, elName:r?r.name:null, body:l.script, problems:l.problems||[]});
      (d.listeners||[]).forEach(l=>add(n, listener(null,l)));
      elementRecords(n).forEach(r=>(r.listeners||[]).forEach(l=>add(n, listener(r,l))));
    }
    if(n.type==='action') add(n,{group:'bot', lang:d.scriptLanguage, body:d.script,
      problems:d.scriptProblems||[]});
  });
  return out;
}
/** Chip buckets, in reading order. A bot script has no Design element of its own — it *is* the action. */
const SCRIPT_GROUPS=[{id:'script', label:'Script tasks'},{id:'listener', label:'Listeners'},
                     {id:'bot', label:'Bot scripts'}];
/** The Design words for one script row: its element term plus the lifecycle event it hangs off. */
function scriptKindLabel(s){
  if(s.group==='bot') return 'Bot script';
  const base=term('el', s.elKind).label || 'Script';
  return s.event ? base+' · '+s.event : base;
}
/** ⚠ n — red when any finding is an error, amber when everything is a warning. */
function scriptIssueBadge(problems){
  const pr=problems||[];
  if(!pr.length) return '';
  const tone=pr.some(p=>p.severity==='error')?'bad':'warn';
  return '<span class="pt" style="color:var(--'+tone+'-text)">⚠ '+pr.length+'</span>';
}
/** The findings of one script, as rows: severity, message, line and the offending source line. */
function scriptProblemsHtml(problems){
  const pr=problems||[];
  if(!pr.length) return '';
  return '<div style="display:flex;flex-direction:column;gap:2px;padding:4px 10px 0">'+
    pr.map(p=>'<div><span style="color:var(--'+(p.severity==='error'?'bad':'warn')+'-text)">'+
      esc(p.severity)+'</span> '+esc(p.message)+
      (p.line?' <span class="muted">· line '+p.line+'</span>':'')+
      (p.snippet?' <span class="mono muted">'+esc(p.snippet)+'</span>':'')+'</div>').join('')+'</div>';
}
// ---------- tiny script highlighter — display only, so the worst case is a token staying plain ----------
const HL_KEYWORDS={
  groovy:'def var final if else for while do switch case break continue return try catch finally throw '+
    'new class interface enum extends implements import package assert in instanceof null true false this super void',
  js:'const let var function if else for while do switch case break continue return try catch finally throw '+
    'new class extends import from export await async yield typeof instanceof delete void in of null undefined true false this super',
  py:'def class if elif else for while try except finally raise return import from as with lambda pass '+
    'break continue global nonlocal yield assert in is not and or del None True False',
};
function hlFamily(lang){
  const l=String(lang||'').toLowerCase();
  if(l==='groovy') return 'groovy';
  if(['javascript','js','ecmascript','nashorn','graal.js'].indexOf(l)>=0) return 'js';
  if(l==='python'||l==='jython') return 'py';
  return null;
}
/** Escaped HTML with comment/string/number/keyword tokens wrapped; `${…}` interpolation inside a
 *  string is colored as code. Multi-line tokens close and reopen their span on every line, so the
 *  result can be split on '\n' without breaking markup. */
function hlScript(src, lang){
  const fam=hlFamily(lang);
  if(!fam) return esc(src);
  const kw=new Set(HL_KEYWORDS[fam].split(' '));
  const wrap=(cls,text)=>text.split('\n')
    .map(seg=>seg?'<span class="tok-'+cls+'">'+esc(seg)+'</span>':'').join('\n');
  const string=text=>{
    if(fam==='py') return wrap('s', text);
    let out='', i=0, m; const re=/\$\{[^}\n]*\}/g;
    while((m=re.exec(text))){ out+=wrap('s',text.slice(i,m.index))+wrap('i',m[0]); i=m.index+m[0].length; }
    return out+wrap('s',text.slice(i));
  };
  const re= fam==='py'
    ? /(#[^\n]*)|('''[\s\S]*?(?:'''|$)|"""[\s\S]*?(?:"""|$)|'(?:\\.|[^'\\\n])*'?|"(?:\\.|[^"\\\n])*"?)|\b(\d[\w.]*)\b|\b([A-Za-z_]\w*)\b/g
    : /(\/\*[\s\S]*?(?:\*\/|$)|\/\/[^\n]*)|('''[\s\S]*?(?:'''|$)|"""[\s\S]*?(?:"""|$)|`[\s\S]*?(?:`|$)|'(?:\\.|[^'\\\n])*'?|"(?:\\.|[^"\\\n])*"?)|\b(\d[\w.]*)\b|\b([A-Za-z_$]\w*)\b/g;
  let out='', last=0, m;
  while((m=re.exec(src))){
    out+=esc(src.slice(last, m.index));
    if(m[1]) out+=wrap('c', m[1]);
    else if(m[2]) out+=string(m[2]);
    else if(m[3]) out+=wrap('n', m[3]);
    else out+= kw.has(m[4]) ? wrap('k', m[4]) : esc(m[4]);
    last=m.index+m[0].length;
  }
  return out+esc(src.slice(last));
}
/** The read-only code viewer: line numbers, syntax colors, and the problem lines marked with the
 *  finding's message on hover. Replaces the bare `<pre class="scriptbox">` wherever a script shows. */
function codeBoxHtml(body, lang, problems){
  if(body==null||body==='') return '';
  const byLine={};
  (problems||[]).forEach(p=>{ if(p.line) (byLine[p.line]=byLine[p.line]||[]).push(p); });
  const lines=hlScript(String(body), lang).split('\n');
  return '<pre class="scriptbox code">'+lines.map((l,i)=>{
    const pr=byLine[i+1];
    const cls='cl'+(pr?(pr.some(p=>p.severity==='error')?' cl-bad':' cl-warn'):'');
    const tip=pr?' title="'+esc(pr.map(p=>p.message).join(' · '))+'"':'';
    return '<span class="'+cls+'"'+tip+'><span class="lno">'+(i+1)+'</span>'+l+'</span>';
  }).join('')+'</pre>';
}
/** `"<model>|<element>"` → the variables that script touches, inverted from the variable nodes. */
function scriptVarIndex(){
  const m=new Map();
  nodes.forEach(n=>{
    if(n.type!=='variable') return;
    ((n.data||{}).scriptSites||[]).forEach(s=>{
      const k=s.model+'|'+(s.element==null?'':s.element);
      if(!m.has(k)) m.set(k,[]);
      m.get(k).push({name:n.label, api:s.api});
    });
  });
  return m;
}
function renderScripts(){
  const v=document.getElementById('view-scripts');
  const all=allScripts(), varIdx=scriptVarIndex();
  const lines=s=>String(s.body).split('\n').length;
  const byModel=new Map();
  all.forEach(s=>{ if(!byModel.has(s.model)) byModel.set(s.model,[]); byModel.get(s.model).push(s); });
  const models=[...byModel.keys()].sort((a,b)=>{
    const la=(byId.get(a)||{}).label||a, lb=(byId.get(b)||{}).label||b;
    return la.localeCompare(lb);
  });
  const totalLines=all.reduce((a,s)=>a+lines(s),0);
  const withIssues=all.filter(s=>(s.problems||[]).length).length;
  let h='<div class="dash">';
  h+='<div class="dash-title">Script tasks</div>'+
     '<div class="dash-sub">'+(all.length
       ? all.length+' script'+(all.length>1?'s':'')+' in '+models.length+' model'+(models.length>1?'s':'')+
         ' · '+totalLines+' line'+(totalLines>1?'s':'')+
         (withIssues?' · <span style="color:var(--bad-text)">⚠ '+withIssues+' with syntax findings</span>':'')+
         ' — script tasks, listener scripts and bot scripts, with the variables each one touches'
       : 'no model in this project carries a script')+'</div>';
  if(!all.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">{ }</div>'+
       '<div class="et">No script tasks</div>'+
       '<div class="eh">Nothing to show — no script task, listener script or bot script was found.</div></div>';
  } else {
    // chips narrow by kind (same single-select pattern as the parameter sections), the text box searches
    // names, languages and the code itself
    const chip=(id,label,n)=>'<button class="pchip'+(id==='all'?' on':'')+'" data-group="'+id+'">'+
      esc(label)+'<span class="pchipn">'+n+'</span></button>';
    h+='<div class="pbar"><input class="pf" type="search" placeholder="filter scripts — name, language, code…" '+
       'aria-label="Filter script tasks">'+
       chip('all','All',all.length)+
       SCRIPT_GROUPS.filter(g=>all.some(s=>s.group===g.id))
         .map(g=>chip(g.id,g.label,all.filter(s=>s.group===g.id).length)).join('')+
       '<button class="pchip" id="scriptsall"></button><span class="pcount"></span></div>';
    models.forEach(mid=>{
      const rows=byModel.get(mid);
      h+='<div class="seclabel" style="display:flex;align-items:center;gap:var(--space-2)">'+
         nodeChip(mid)+'<span class="muted">'+rows.length+' script'+(rows.length>1?'s':'')+'</span></div>';
      h+=rows.map(s=>{
        const vars=varIdx.get(s.model+'|'+(s.el==null?'':s.el))||[];
        const chips=vars.map(x=>'<span class="'+(x.api?'':'muted ')+'">'+
          vlink('variable:'+x.name, (x.api?'':'≈ ')+x.name)+'</span>').join(' ');
        // a bot script IS its model, and a model-level listener has only its kind to go by
        const kind=scriptKindLabel(s);
        const title=s.elName||s.el||(s.group==='bot'?s.modelLabel:kind);
        const jump=s.el?'<span class="opref" data-goto="'+enc(s.model)+'" data-goto-el="'+esc(String(s.el))+
          '" tabindex="0" role="link" style="cursor:pointer" data-tip="Open this element in its model">'+
          'in model ↓</span>':'';
        const hay=[title, kind, s.lang||'', s.el||'', (byId.get(mid)||{}).label||'', s.body||'',
          (s.problems||[]).map(p=>p.message).join(' ')].join(' ').toLowerCase();
        // a handful of scripts: show the code straight away; a big project starts collapsed
        return '<details class="op" data-scriptrow data-group="'+esc(s.group)+'"'+
          (all.length<=6||(s.problems||[]).length?' open':'')+' data-hay="'+esc(hay)+'">'+
          '<summary><span class="opname">'+esc(title)+'</span>'+
          (s.el&&s.el!==title?'<span class="opid">'+esc(String(s.el))+'</span>':'')+
          '<span class="pt">'+esc(kind)+'</span>'+
          (s.lang?'<span class="pt">'+esc(s.lang)+'</span>':'')+
          '<span class="pt">'+lines(s)+' line'+(lines(s)>1?'s':'')+'</span>'+
          scriptIssueBadge(s.problems)+
          (s.out?'<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+
            paramSide(s.out)+'</span>':'')+
          (chips?'<span style="flex:1;display:flex;gap:6px;flex-wrap:wrap;min-width:0">'+chips+'</span>':'')+
          jump+'</summary>'+
          (s.doc?'<div class="muted" style="padding:4px 10px 0">'+esc(s.doc)+'</div>':'')+
          scriptProblemsHtml(s.problems)+
          codeBoxHtml(s.body, s.lang, s.problems)+'</details>';
      }).join('');
    });
  }
  h+='</div>';
  v.innerHTML=h;
  // kind chips + one text filter over every row: model, element, language and the code itself
  const pf=v.querySelector('.pf'), count=v.querySelector('.pcount');
  if(pf){
    const chips=[...v.querySelectorAll('.pchip[data-group]')];
    const apply=()=>{
      const q=(pf.value||'').trim().toLowerCase();
      const group=(chips.find(c=>c.classList.contains('on'))||{dataset:{}}).dataset.group||'all';
      let shown=0;
      v.querySelectorAll('[data-scriptrow]').forEach(r=>{
        const on=(group==='all'||r.dataset.group===group) && (!q||(r.dataset.hay||'').indexOf(q)>=0);
        r.hidden=!on; if(on) shown++;
        if(q&&on) r.open=true;
      });
      // hide a model heading whose scripts are all filtered out
      v.querySelectorAll('.seclabel').forEach(lab=>{
        let any=false;
        for(let e=lab.nextElementSibling; e&&!e.classList.contains('seclabel'); e=e.nextElementSibling){
          if(e.hasAttribute('data-scriptrow')&&!e.hidden) any=true;
        }
        lab.hidden=!any;
      });
      count.textContent=(q||group!=='all')?shown+' of '+all.length:'';
      syncAll();
    };
    pf.oninput=debounce(apply,120);
    chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
    // one control for every body at once — reading a project's scripts top to bottom is the point of
    // this view, and clicking 40 triangles is not
    const all2=()=>[...v.querySelectorAll('[data-scriptrow]')].filter(r=>!r.hidden);
    const toggle=v.querySelector('#scriptsall');
    const syncAll=()=>{ const rows=all2();
      toggle.textContent=(rows.length&&rows.every(r=>r.open))?'⇕ collapse all':'⇕ expand all'; };
    toggle.onclick=()=>{ const rows=all2(), open=!rows.every(r=>r.open);
      rows.forEach(r=>{ r.open=open; }); syncAll(); };
    v.querySelectorAll('[data-scriptrow]').forEach(r=>r.addEventListener('toggle',syncAll));
    syncAll();
  }
  v.onclick=e=>{
    const go=e.target.closest('[data-goto]');
    if(go){ e.preventDefault(); select(dec(go.dataset.goto), '', go.dataset.gotoEl||''); return; }
    const idEl=e.target.closest('[data-id]');
    if(idEl) select(dec(idEl.dataset.id));
  };
  v.onkeydown=e=>{ if(e.key!=='Enter'&&e.key!==' ') return;
    const t=e.target.closest('[data-goto],[data-id]'); if(t){ e.preventDefault(); t.click(); } };
}

// ---------- browse: list column ----------
function renderList(){
  const cat = CATS.find(c=>c.id===state.cat);
  const list = document.getElementById('list'); list.innerHTML='';
  if(!cat) return;
  const head=document.createElement('div'); head.className='listhead';
  head.innerHTML='<div class="t"><span>'+esc(cat.label)+'</span><span class="muted">'+cat.count+'</span></div>'+
    '<div class="lh-controls"><input id="lf" placeholder="filter '+esc(cat.label.toLowerCase())+'…" aria-label="Filter list">'+
    '<select id="lsort" aria-label="Sort list"><option value="name">Name</option>'+
    '<option value="refs">Most referenced</option><option value="file">File</option></select></div>';
  list.appendChild(head);
  const wrap=document.createElement('div'); wrap.id='listitems';
  wrap.setAttribute('role','listbox');
  wrap.setAttribute('aria-label',cat.label);
  list.appendChild(wrap);
  renderItems(cat, wrap);
  // The input lives outside the re-rendered items wrap, so typing never loses focus.
  const lf=document.getElementById('lf'); lf.value=state.filter;
  lf.oninput=debounce(()=>{ state.filter=lf.value; renderItems(cat, wrap); },120);
  const ls=document.getElementById('lsort'); ls.value=state.sort;
  ls.onchange=()=>{ state.sort=ls.value; renderItems(cat, wrap); };
  // Arrow/Enter keyboard navigation over the items (roving focus).
  wrap.onkeydown=e=>{
    const els=[...wrap.querySelectorAll('.item[data-id]')];
    const i=els.indexOf(document.activeElement);
    if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const j=e.key==='ArrowDown'?Math.min(i+1,els.length-1):Math.max(i-1,0);
      if(els[j]) els[j].focus();
    } else if(e.key==='Home'&&els[0]){ e.preventDefault(); els[0].focus(); }
    else if(e.key==='End'&&els[els.length-1]){ e.preventDefault(); els[els.length-1].focus(); }
    else if((e.key==='Enter'||e.key===' ')&&i>=0){ e.preventDefault(); select(els[i].dataset.id); }
  };
}

// Incremental rendering: 200 rows at a time, the IntersectionObserver on a trailing
// sentinel appends the next chunk when it scrolls into view — every item of a large
// category is reachable by scrolling (the old hard cap cut off at 600).
const LIST_CHUNK=200;
let _listIO=null;
function renderItems(cat, wrap){
  if(_listIO){ _listIO.disconnect(); _listIO=null; }
  wrap.innerHTML='';
  let items = nodes.filter(cat.match);
  const f = state.filter.toLowerCase();
  if(f) items = items.filter(n => (n.label+' '+n.key+' '+(n.file||'')+' '+((n.data&&n.data.botKey)||'')).toLowerCase().includes(f));
  if(state.sort==='refs')
    items.sort((a,b)=>(INSIGHTS.indeg.get(b.id)||0)-(INSIGHTS.indeg.get(a.id)||0)||a.label.localeCompare(b.label));
  else if(state.sort==='file')
    items.sort((a,b)=>String(a.file||'').localeCompare(String(b.file||''))||a.label.localeCompare(b.label));
  else
    items.sort((a,b)=>a.label.localeCompare(b.label));
  const sentinel=document.createElement('div'); sentinel.className='sentinel';
  wrap.appendChild(sentinel);
  let idx=0;
  function makeItem(n,i){
    const el=document.createElement('div'); el.className='item'+(state.sel===n.id?' on':'');
    el.dataset.id=n.id;
    el.setAttribute('role','option');
    el.setAttribute('aria-selected', state.sel===n.id?'true':'false');
    el.tabIndex=-1;
    el.style.animationDelay=Math.min(i*8,300)+'ms';
    const rn=INSIGHTS.indeg.get(n.id)||0;
    el.innerHTML='<span class="dot" style="margin-top:5px;background:'+nodeColor(n)+'"></span>'+
      '<div class="meta"><div class="nm">'+esc(n.label)+authBadge(n)+'</div><div class="sub">'+esc(n.key)+'</div></div>'+
      (rn?'<span class="refn" title="referenced by '+rn+' node'+(rn>1?'s':'')+'">'+rn+'</span>':'');
    el.onclick=()=>select(n.id);
    return el;
  }
  function append(){
    const slice=items.slice(idx, idx+LIST_CHUNK);
    slice.forEach((n,i)=>wrap.insertBefore(makeItem(n,i), sentinel));
    if(idx===0 && wrap.querySelector('.item')) wrap.querySelector('.item').tabIndex=0;
    idx+=slice.length;
    if(idx>=items.length){ if(_listIO){ _listIO.disconnect(); _listIO=null; } sentinel.remove(); }
  }
  _listIO=new IntersectionObserver(es=>{ if(es.some(e=>e.isIntersecting)) append(); },
                                   {root: wrap.closest('.listcol'), rootMargin:'600px'});
  _listIO.observe(sentinel);
  append();
}

// Selection within the current category only toggles classes — no full list rebuild.
function syncListSelection(){
  let hit=null;
  document.querySelectorAll('#list .item[data-id]').forEach(el=>{
    const on = el.dataset.id===state.sel;
    el.classList.toggle('on', on);
    el.setAttribute('aria-selected', on?'true':'false');
    if(on) hit=el;
  });
  if(hit) hit.scrollIntoView({block:'nearest'});
}

// ---------- detail ----------
// `f` (optional) is the adjacency entry — a suspect/dynamic link gets a marker + dashed chip.
function nodeChip(id,f){
  const n=byId.get(id); if(!n) return '';
  const cls=f&&f.sus?' nc-sus':f&&f.dyn?' nc-dyn':'';
  const flag=f&&f.sus?'<span class="ncflag" title="suspect — loose or cross-type match">≈</span>'
           :f&&f.dyn?'<span class="ncflag" title="dynamic — reference is an expression">ƒ</span>':'';
  return '<span class="nc'+cls+'" data-id="'+enc(id)+'" tabindex="0" role="link"><span class="dot" style="background:'+nodeColor(n)+'"></span>'+
    '<span class="nm">'+esc(n.label)+'</span>'+flag+'<span class="ty">'+esc(nodeKind(n))+'</span>'+copyBtn(n.key,nodeKind(n)+' key')+'</span>';
}
// rel -> Map(id -> adjacency entry) — the Map keeps per-target flags while deduping ids.
function groupRels(arr){ const g={}; (arr||[]).forEach(x=>{ (g[x.rel]=g[x.rel]||new Map()).set(x.id,x); }); return g; }
// Small badge marking a changelog as the live definition of its table vs a superseded/orphan revision.
function authBadge(n){
  if(n.type!=='liquibase') return '';
  const a=(n.data||{}).authority; if(!a||!a.status) return '';
  if(a.status==='live'){ const by=(a.referencedBy||[]).join(', ');
    return '<span class="pill pill-ok" title="Live / authoritative'+(by?' — referenced by '+esc(by):'')+'">live</span>'; }
  if(a.status==='superseded'){ const by=(a.supersededBy||[]).join(', ');
    return '<span class="pill pill-warn" title="Superseded — the same table is provided by '+esc(by||'a referenced changelog')+'">superseded</span>'; }
  return '<span class="pill pill-bad" title="Orphan — not referenced by any service or data object">orphan</span>';
}

// inline link to a node id if it exists in the graph, else plain escaped text —
// so every conversion below degrades to the old static text when the target isn't resolved.
function vlink(id, text, title){
  return byId.get(id)
    ? '<span class="vlink" data-id="'+enc(id)+'"'+(title?' title="'+esc(title)+'"':'')+
      ' tabindex="0" role="link">'+esc(text)+'</span>'
    : esc(text==null?'':text);
}
// first neighbor id reachable from `id` over relation `rel` (outgoing / incoming) — used when a
// value can't be turned into a node id directly but the resolver already computed the edge.
const outTo  = (id,rel)=>{ const e=(outM.get(id)||[]).find(x=>x.rel===rel); return e&&e.id; };
const incFrom= (id,rel)=>{ const e=(incM.get(id)||[]).find(x=>x.rel===rel); return e&&e.id; };

// ---------- collapsible detail sections ----------
// Every block in the detail panel is a <details> so a node with 35 parameters can still be skimmed.
// Open/closed is remembered per SECTION (not per node) in localStorage: a section you open stays open as
// you walk the graph. Everything defaults to closed except the diagram — see DEFAULT_OPEN_SECTIONS.
const SECT_STORE='atlas-sect';
const DEFAULT_OPEN_SECTIONS={diagram:true};
function sectAll(){ try{ return JSON.parse(localStorage.getItem(SECT_STORE)||'{}')||{}; }catch(e){ return {}; } }
function sectRemember(id, open){
  try{ const m=sectAll(); m[id]=open; localStorage.setItem(SECT_STORE, JSON.stringify(m)); }catch(e){}
}
function sectIsOpen(id){
  const m=sectAll();
  return id in m ? !!m[id] : !!DEFAULT_OPEN_SECTIONS[id];
}
// `titleHtml` is pre-built markup (it carries counts/summaries); an empty body renders nothing at all.
function section(id, titleHtml, bodyHtml){
  if(!bodyHtml) return '';
  return '<details class="sect" data-sect="'+enc(id)+'"'+(sectIsOpen(id)?' open':'')+'>'+
    '<summary>'+titleHtml+'</summary><div class="sb">'+bodyHtml+'</div></details>';
}

// ---------- in/out parameters ----------
// A model's `parameters` is one flat list of {element,elementName,elementType,elementSubType,dir,kind,
// source,target,…} records — every flavour of Flowable variable mapping normalised to source -> target.
const PDIR_COLOR={'in':'--info-text','out':'--ok-text','error-out':'--bad-text'};
// "3 in · 1 out" — direction tally in a fixed order, so the label reads the same everywhere.
function paramSummary(list){
  const c={}; (list||[]).forEach(p=>{ c[p.dir]=(c[p.dir]||0)+1; });
  return ['in','out','error-out'].filter(k=>c[k]).map(k=>c[k]+' '+k).join(' · ');
}
// group by declaring element, first-seen order (which is document order)
function paramGroups(list){
  const g=new Map();
  (list||[]).forEach(p=>{
    const k=p.element==null?'':String(p.element);
    if(!g.has(k)) g.set(k,{element:p.element,name:p.elementName,type:p.elementType,sub:p.elementSubType,
                           refKind:p.refKind,refKey:p.refKey,rows:[]});
    g.get(k).rows.push(p);
  });
  return [...g.values()];
}
// The model a group's parameters are mapped onto. `rest` is a URL, not a model — there is no node to link.
function calleeNodeId(g){
  if(!g.refKey || !g.refKind || g.refKind==='rest') return null;
  const id=g.refKind+':'+g.refKey;
  return byId.get(id) ? id : null;
}
// One collapsible group of rows, headed by the declaring element and *what it calls*.
// `hasDg`: the node has a diagram — the group gets a ⌖ locate button targeting its element.
function paramGroupHtml(g, extraBody, hasDg){
  const label=g.name||g.element||'—';
  // "serviceTask · service-registry" is exact but internal; Design calls it a "Service registry task"
  const ty=elementTerm(g.type, g.sub);
  // The element id, when the label isn't already it: that is what you search for in the BPMN/CMMN XML or
  // pick out on the diagram, and a named task would otherwise never show it. Click to copy.
  const eid=(g.element!=null&&String(g.element)!==label)
    ? '<span class="opid">'+esc(String(g.element))+'</span>'+copyBtn(String(g.element),'element id') : '';
  const loc=(hasDg&&g.element!=null)?locateBtn(String(g.element), g.name):'';
  // the callee by name in the summary (visible without expanding) …
  const callee=g.refKey?'<span class="opref">→ '+esc(String(g.refKey))+'</span>':'';
  const cid=calleeNodeId(g);
  // … and as a chip in the body, where a click cannot fight the summary's own toggle
  const chip=cid?'<div class="opchips">'+nodeChip(cid)+'</div>':'';
  return '<details class="op" open'+dataEl(g.element)+'><summary><span class="opname">'+esc(label)+'</span>'+eid+loc+callee+
    '<span class="opcount">'+g.rows.length+' param'+(g.rows.length>1?'s':'')+'</span>'+
    '<span class="opkey">'+ty+'</span></summary>'+(extraBody||'')+chip+
    '<div class="parmgrid">'+g.rows.map(paramRow).join('')+'</div></details>';
}
// data-el attribute for a detail row/group attributed to a model element — the reveal contract with
// the diagram (revealByEl / dgCardHtml match on it).
function dataEl(id){ return (id==null||id==='')?'':' data-el="'+esc(String(id))+'"'; }
// ⌖ — pans the diagram to the element and highlights it (wired in renderDetail).
function locateBtn(id, name){
  return '<button type="button" class="dgloc" data-el-ref="'+esc(String(id))+'"'+
    (name?' data-el-name="'+esc(String(name))+'"':'')+
    ' data-tip="Show on diagram" aria-label="Show on diagram">'+LOC_SVG+'</button>';
}
// A mapping side may be a backend variable, a frontend `{{…}}` binding (form buttons map bindings), or
// neither — a callee-side contract name or an expression. Try each node kind, then fall back to text.
function paramSide(x){
  const asVar=vlink('variable:'+x, x);
  if(byId.get('variable:'+x)) return asVar;
  if(String(x).indexOf('{{')>=0 && byId.get('binding:'+x)) return vlink('binding:'+x, x);
  return asVar;                                   // vlink already degraded to escaped text
}
// split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
const groupLinksHtml=v=>String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g))
  .map(g=>vlink('group:'+g,g)).join(', ');
// Design's name for an element type, from `elementType` plus the `flowable:type` refinement.
function elementTerm(type, sub){
  if(!type) return '';
  if(DESIGN_TERMS['el:'+type+'/'+sub]) return termHtml('el', type+'/'+sub);
  // a CMMN <task flowable:type="…"> is the same thing as a BPMN service task of that type,
  // so the serviceTask/* terms cover both dialects
  if(sub && type==='task' && DESIGN_TERMS['el:serviceTask/'+sub]) return termHtml('el', 'serviceTask/'+sub);
  if(DESIGN_TERMS['el:'+type]) return termHtml('el', type)+(sub?'<span class="opsub"> · '+esc(sub)+'</span>':'');
  return esc([type,sub].filter(Boolean).join(' · '));
}
function paramFlowHtml(p){
  const arrow=' <span class="pa">→</span> ';
  const has=x=>x!=null&&x!=='';
  // A one-sided mapping still gets its arrow: `→ total` reads as "the result lands in total", where a bare
  // `total` would leave you guessing which end of the flow you are looking at.
  if(has(p.source)&&has(p.target)) return paramSide(p.source)+arrow+paramSide(p.target);
  if(has(p.target)) return arrow.trimStart()+paramSide(p.target);
  if(has(p.source)) return paramSide(p.source)+arrow.trimEnd();
  return '';
}
function paramRow(p){
  // the mapping kind gets Design's wording plus a tooltip; type/transient stay as the model spells them
  const tags=termHtml('kind', p.kind, 'pt')+
    [p.type,p.transient?'transient':''].filter(Boolean).map(t=>'<span class="pt">'+esc(t)+'</span>').join('');
  // data-dir / data-hay let the filter and the search highlight work without re-rendering or text parsing
  return '<div class="pc" data-dir="'+esc(p.dir)+'" data-hay="'+esc(paramHaystack(p).toLowerCase())+'">'+
    '<span class="pd" style="color:var('+(PDIR_COLOR[p.dir]||'--ink-faint')+')">'+esc(p.dir)+'</span>'+
    '<span class="pn">'+paramFlowHtml(p)+'</span>'+tags+'</div>';
}
// Above this many rows a flat list stops being readable, so the section gets a filter of its own.
const PARAM_FILTER_FROM=12;
function paramSection(list, hasDg){
  const gs=paramGroups(list);
  let head='';
  if(list.length>=PARAM_FILTER_FROM){
    const c={}; list.forEach(p=>{ c[p.dir]=(c[p.dir]||0)+1; });
    const chip=(d,lbl,n)=>'<button class="pchip'+(d==='all'?' on':'')+'" data-dir="'+d+'">'+esc(lbl)+
      '<span class="pchipn">'+n+'</span></button>';
    head='<div class="pbar"><input class="pf" type="search" placeholder="filter parameters…" '+
      'aria-label="Filter parameters">'+chip('all','all',list.length)+
      ['in','out','error-out'].filter(d=>c[d]).map(d=>chip(d,d,c[d])).join('')+'</div>';
  }
  return section('params','Parameters ('+list.length+') — '+esc(paramSummary(list)),
    head+gs.map(g=>paramGroupHtml(g, null, hasDg)).join(''));
}

// Live filter over an already-rendered Parameters section: text + direction, pure show/hide. Element
// groups whose every row is filtered out collapse away so the remaining ones stay easy to scan.
function wireParamFilter(det){
  const bar=det.querySelector('.pbar');
  if(!bar) return;
  const input=bar.querySelector('.pf'), chips=[...bar.querySelectorAll('.pchip')];
  const sect=bar.closest('.sb');
  const apply=()=>{
    const q=(input.value||'').trim().toLowerCase();
    const dir=(chips.find(c=>c.classList.contains('on'))||{}).dataset.dir||'all';
    sect.querySelectorAll('details.op').forEach(grp=>{
      let shown=0;
      grp.querySelectorAll('.pc').forEach(row=>{
        const ok=(dir==='all'||row.dataset.dir===dir) && (!q||(row.dataset.hay||'').indexOf(q)>=0);
        row.hidden=!ok; if(ok) shown++;
      });
      grp.hidden=!shown;
      if(shown) grp.open=true;
    });
  };
  input.addEventListener('input', debounce(apply,120));
  chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
}

function describe(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  // count rows only when there is something to count — a grid of zeros is noise, not information
  const addCount=(k,v)=>{ if(v) rows.push([k,v]); };
  // split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
  const addStarters=v=>{ const p=String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g));
    if(p.length) rows.push(['Starter groups',{html:p.map(g=>vlink('group:'+g,g)).join(', ')}]); };
  // a list of names, each linked to its variable node when one exists (else plain text)
  const varList=a=>({html:(a||[]).filter(x=>x!=null&&x!=='').map(x=>vlink('variable:'+String(x).split('.')[0], x)).join(', ')});
  if(n.type==='process'){ addStarters(d.candidateStarterGroups); addCount('User tasks',(d.userTasks||[]).length);
    addCount('Service tasks',(d.serviceTasks||[]).length); addCount('Call activities',(d.callActivities||[]).length);
    addCount('Script tasks',(d.scriptTasks||[]).length); addCount('Decision tasks',(d.ruleTasks||[]).length);
    addCount('Subprocesses',(d.subProcesses||[]).length);
    addCount('Events',(d.events||[]).filter(e=>e.def||e.name).length);
    add('Parameters', paramSummary(d.ioParameters)); add('Documentation',d.documentation); }
  else if(n.type==='case'){ addStarters(d.candidateStarterGroups);
    if(d.initiatorVariableName) rows.push(['Initiator var',{html:vlink('variable:'+d.initiatorVariableName, d.initiatorVariableName)}]);
    addCount('Milestones',(d.milestones||[]).length); addCount('Event listeners',(d.eventListeners||[]).length);
    add('Parameters', paramSummary(d.ioParameters)); add('Documentation',d.documentation); }
  else if(n.type==='decision'){ if(d.decisionService) add('Kind','Decision service');
    add('Hit policy',d.hitPolicy); addCount('Rules',d.ruleCount);
    if((d.inputs||[]).length) rows.push(['Inputs',varList(d.inputs)]);
    // the expression behind a labelled input — that is what actually reads a variable
    if((d.inputExpressions||[]).length && String(d.inputExpressions)!==String(d.inputs))
      rows.push(['Input expressions',varList(d.inputExpressions)]);
    if((d.outputs||[]).length) rows.push(['Outputs',varList(d.outputs)]); }
  else if(n.type==='form'||n.type==='page'){ addCount('Fields',(d.fields||[]).length);
    addCount('Data sources',(d.dataSources||[]).length);
    add('Outcomes',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(', ')); }
  else if(n.type==='app'){ add('Description',d.description); add('Theme',d.theme);
    addCount('Variables',(d.variables||[]).length); addCount('Pages',(d.pages||[]).length);
    const ga=String(d.groupsAccess||'').split(/[,;]/).map(s=>s.trim()).filter(Boolean);
    if(ga.length) rows.push(['Groups with access',{html:ga.map(g=>vlink('group:'+g,g)).join(', ')}]); }
  else if(n.type==='dataDictionary'){ add('Types',(d.types||[]).length&&(d.types||[]).join(', ')); }
  else if(n.type==='securityPolicy'){ add('Type',d.type); addCount('Permissions',(d.permissions||[]).length); }
  else if(n.type==='dataObject'){ add('Type',d.dataObjectType); add('Data source',d.sourceId);
    if(d.service) rows.push(['Backing service',{html:vlink('service:'+d.service, d.service, 'Service model '+d.service)}]);
    // When backed by a service, surface that service's physical table here and link the name back to the service node.
    const svc=d.service&&byId.get('service:'+d.service), tbl=d.serviceTableName||(svc&&(svc.data||{}).tableName);
    if(tbl) rows.push(['Table',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Provided by service '+esc(d.service)+'">'+esc(tbl)+'</span>', copy:tbl}]);
    if(d.dictionary) rows.push(['Data dictionary',{html:vlink('dataDictionary:'+d.dictionary, d.dictionary)}]);
    addCount('Columns',(d.fields||[]).length); }
  else if(n.type==='service'){ add('Type',d.type); add('Base URL',d.baseUrl); add('Auth',d.auth); add('Table',d.tableName);
    if(d.referencedLiquibaseModelKey){ const lid=(byId.get('liquibase:'+d.referencedLiquibaseModelKey)&&'liquibase:'+d.referencedLiquibaseModelKey)||outTo(n.id,'schema');
      rows.push(['Liquibase model',{html:vlink(lid, d.referencedLiquibaseModelKey)}]); }
    addCount('Columns',(d.columns||[]).length); addCount('Operations',(d.operations||[]).length);
    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) add('Schema gaps',g+' of '+(c.total||0)+' columns'); } }
  else if(n.type==='serviceOperation'){
    if(d.service) rows.push(['Service',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Defined by service '+esc(d.service)+'">'+esc(d.service)+'</span>'}]);
    add('Name',d.name); add('Method',d.method); add('URL',d.fullUrl||d.url);
    add('Params',(d.params||[]).map(p=>p.name+(p.type?': '+p.type:'')).join(', '));
    add('Used by', (d.usedBy||[]).length+' model(s)'); }
  else if(n.type==='agent'){ add('Vendor / model',(d.aiVendor||'')+' / '+(d.modelName||'')); add('Temperature',d.temperature); add('API endpoint',String(d.enableApiEndpoint));
    addCount('Tools',(d.tools||[]).length); addCount('Operations',(d.operations||[]).length);
    if(d.knowledgeBase) rows.push(['Knowledge base',{html:vlink('knowledgeBase:'+d.knowledgeBase, d.knowledgeBase)}]); }
  else if(n.type==='channel'){ add('Direction',d.channelType); add('Type',d.type); add('Topics',(d.topics||[]).join(', ')); add('Destination',d.destination);
    if(d.eventKey&&d.eventKey.fixedValue) rows.push(['Event',{html:vlink('event:'+d.eventKey.fixedValue, d.eventKey.fixedValue)}]); }
  else if(n.type==='event'){ if((d.payload||[]).length) rows.push(['Payload',varList(d.payload)]);
    add('Correlation',(d.correlation||[]).join(', ')); }
  else if(n.type==='java'){ add('Package',d.package); add('Roles',(d.roles||[]).join(', ')); add('Bot key',d.botKey); add('Implements',(d.interfaces||[]).join(', ')); addCount('Methods',(d.methods||[]).length); add('Called from models',(d.calledMethods||[]).join(', ')); }
  else if(n.type==='endpoint'){ add('Method',d.http); add('Path',d.path);
    rows.push(['Handler',{html:vlink(incFrom(n.id,'serves'), (d.controller||'')+'#'+(d.handler||'')), copy:d.controller||undefined}]); }  // FQN for 'Go to Class'
  else if(n.type==='method'){ if(d.name) rows.push(['Method',{html:esc(d.name)+'()', copy:d.name}]);  // copy the bare name for IntelliJ 'Go to Symbol'
    if(d.class) rows.push(['Declared in',{html:vlink(d.declaredIn||'java:'+d.class, d.class), copy:d.class}]); }  // FQN for 'Go to Class'
  else if(n.type==='query'){ add('Source index',d.sourceIndex); add('Parameters',(d.parameters||[]).join(', ')); add('Filters by groups',(d.groups||[]).length); }
  else if(n.type==='action'){
    // Link the bot to whatever the graph resolved (action --bot--> java:<fqn> | bot:<key> | model node):
    // a Java bot keeps its class chip; any other resolved bot gets an inline link; only a truly
    // unresolved bot stays plain text.
    const be=(outM.get(n.id)||[]).find(e=>e.rel==='bot');
    if(be && byId.get(be.id)){ const bl=d.botKey||byId.get(be.id).label;
      rows.push(['Bot',{html: be.id.indexOf('java:')===0 ? jchip(be.id, bl) : vlink(be.id, bl)}]); }
    else add('Bot',d.botKey);
    if(d.formKey){ const fid=(byId.get('form:'+d.formKey)&&'form:'+d.formKey)||(byId.get('page:'+d.formKey)&&'page:'+d.formKey)||outTo(n.id,'action-form');
      rows.push(['Form',{html:vlink(fid, d.formKey)}]); }
    if(d.signalName){
      // start-instance bots carry a model key in signalName; other bots a real signal name
      const isP=d.botKey==='bpmn-start-process-instance-bot', isC=d.botKey==='cmmn-start-case-instance-bot';
      const sid=isP?'process:'+d.signalName:isC?'case:'+d.signalName:'signal:'+d.signalName;
      rows.push([isP?'Starts process':isC?'Starts case':'Triggers signal',{html:vlink(sid, d.signalName)}]);
    }
    add('Scope',d.scopeType); add('Parameters', paramSummary(d.ioParameters));
    if(d.script) add('Script',d.scriptLanguage||'script');
    const pg=(d.permissionGroups||[]).filter(g=>typeof g==='string'&&g);
    if(pg.length) rows.push(['Allowed groups',{html:pg.map(g=>vlink('group:'+g,g)).join(', ')}]);
    const chs=(d.channels||[]).map(c=>typeof c==='string'?c:(c&&c.key)).filter(Boolean);
    if(chs.length) rows.push(['Channels',{html:chs.map(c=>vlink('channel:'+c,c)).join(', ')}]); }
  else if(n.type==='bot'){ add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); }
  else if(n.type==='liquibase'){ const a=d.authority||{};
    add('Status', a.status==='live'?'live (authoritative)':a.status==='superseded'?'superseded revision':a.status==='orphan'?'orphan — unreferenced':undefined);
    if((a.referencedBy||[]).length) rows.push(['Referenced by',{html:a.referencedBy.map(k=>vlink('service:'+k, k)).join(', ')}]);
    if((a.supersededBy||[]).length) rows.push(['Live definition',{html:a.supersededBy.map(k=>vlink('liquibase:'+k, k)).join(', ')}]);
    add('Tables',(d.effectiveTables||d.tables||[]).join(', ')); add('Columns',(d.columns||[]).length); }
  else if(n.type==='expression'||n.type==='binding'){ add('Used by', (d.usedBy||[]).length+' model(s)');
    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity==='error').length, wc=pr.length-ec;
      add('Problems',[ec?ec+' error'+(ec>1?'s':''):'', wc?wc+' warning'+(wc>1?'s':''):''].filter(Boolean).join(', ')); } }
  else if(n.type==='variable'){ add('Scope',(d.scopes||[]).join(', ')); add('Used in', (d.usages||[]).length+' model(s)');
    add('As parameter', paramSummary(d.ioParams));
    // nothing but a bare identifier in a script says this exists — same ≈ vocabulary as uncertain links
    if(d.heuristic) rows.push(['Evidence',{html:'<span class="pt" data-tip="Only a bare identifier in a '+
      'script body names this variable — Flowable puts scope variables into the script binding, so it is '+
      'probably real, but Atlas cannot prove it.">≈ script read only</span>',copy:null}]); }
  else if(n.type==='string'){ add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='customFunction'){
    add('Kind', d.kind==='namespace'?('namespace '+d.namespace+'.*'):d.kind==='flw'?'flw.* member':'top-level');
    add('Signature', d.member+'('+(d.signature!=null?d.signature:'…')+')');
    add('Registered in',(d.sources||[]).join(', ')); add('Used by', (d.usedBy||[]).length+' form(s) / model(s)'); }
  else if(n.type==='external'){ add('Kind',d.flowableApi?'Flowable platform API':d.route?'In-app navigation route':d.platform?'Flowable platform bean':d.missingModel?'Missing model reference ('+(d.kind||'model')+')':d.dynamic?'Dynamic reference (expression) — expected '+(d.kind||'model'):(d.external_url?'External URL':d.kind||'external')); if(d.method&&d.method!=='(button)') add('Method',d.method); }
  else { Object.keys(d).forEach(k=>{ const v=d[k]; if(typeof v==='string'||typeof v==='number') add(k,v); }); }
  return rows;
}

function detailExtra(n){
  const d=n.data||{}; let h='';
  const hasDg=!!d.diagram;                      // rows for diagram elements get a ⌖ locate button
  const EM=elementNames(n);                     // element id -> name/type, for readable references
  const loc=(id,name)=>hasDg&&id!=null&&id!==''?locateBtn(String(id), name):'';
  // What this model passes into, and takes back out of, everything it calls.
  if((d.ioParameters||[]).length) h+=paramSection(d.ioParameters, hasDg);
  // The mirror image: what this node actually receives from its callers. A payload is modelled on the
  // *calling* side (a form button, a call activity), so without this you would have to visit every caller
  // to see whether the names line up with what the callee expects. `refKind` mirrors the node type, so
  // matching on both is what keeps a service and a data object of the same key apart.
  {
    const callers=[];
    (incM.get(n.id)||[]).forEach(e=>{
      const src=byId.get(e.id); if(!src) return;
      const rows=((src.data||{}).ioParameters||[]).filter(p=>p.refKind===n.type && p.refKey===n.key);
      if(rows.length) callers.push({id:e.id, rows});
    });
    const total=callers.reduce((a,c)=>a+c.rows.length,0);
    if(total) h+=section('called-with','Called with ('+total+') — '+esc(paramSummary(callers.flatMap(c=>c.rows))),
      // here the interesting other side is the *caller*, so its chip replaces the callee's
      callers.map(c=>paramGroups(c.rows).map(g=>
        paramGroupHtml({...g, refKey:null, refKind:null}, '<div class="opchips">'+nodeChip(c.id)+'</div>')
      ).join('')).join(''));
  }
  // ---------- model structure — data parsed from the model file, linked into the graph ----------
  // Every value that names something else is a link when the target exists: a field id → its variable,
  // a task → its form and candidate groups, a plan item → the process/case/decision it starts.
  // (Field injections live inside each task's entry in the Service tasks section below.)
  // a field id like `customer.email` binds the variable root `customer`
  const fieldLink=id=>{const s=String(id==null?'':id); const r=s.replace(/^\$/,'').split('.')[0].split('[')[0];
    return byId.get('variable:'+r)?'<span class="vlink" data-id="'+enc('variable:'+r)+'" tabindex="0" role="link">'+esc(s)+'</span>':esc(s);};
  if(n.type==='process' && (d.userTasks||[]).length){
    h+=section('usertasks','User tasks ('+d.userTasks.length+')','<div class="oplist">'+
      d.userTasks.map(t=>{
        const eid=(t.id&&t.id!==(t.name||t.id))?'<span class="opid">'+esc(t.id)+'</span>':'';
        const bits=[
          t.formKey?'<span class="muted">form</span> '+vlink('form:'+t.formKey, t.formKey):'',
          t.candidateGroups?'<span class="muted">groups</span> '+groupLinksHtml(t.candidateGroups):'',
          t.assignee?'<span class="muted">assignee</span> '+esc(t.assignee):'',
        ].filter(Boolean).join(' ');
        const extra=[t.dueDate?'due '+t.dueDate:'',t.priority?'priority '+t.priority:'',t.category||'']
          .filter(Boolean).map(x=>'<span class="pt">'+esc(x)+'</span>').join('');
        return '<div class="oprow"'+dataEl(t.id)+'><span style="min-width:150px">'+esc(t.name||t.id||'')+'</span>'+eid+loc(t.id,t.name)+
          '<span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span>'+extra+'</div>';
      }).join('')+'</div>');
  }
  // One collapsible script task — shared by BPMN <scriptTask> and CMMN <task flowable:type="script">.
  const scriptTaskHtml=t=>{
    const fmtV=t.format||t.scriptFormat;
    const rv=t.resultVariable?'<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+paramSide(t.resultVariable)+'</span>':'';
    const fmt=fmtV?'<span class="pt">'+esc(fmtV)+'</span>':'';
    const eid=(t.id&&t.id!==(t.name||t.id))?'<span class="opid">'+esc(t.id)+'</span>':'';
    const body=t.script?codeBoxHtml(t.script, fmtV, t.problems)
      :'<div class="muted" style="padding:4px 10px">no script body</div>';
    return '<details class="op"'+dataEl(t.id)+((t.problems||[]).length?' open':'')+
      '><summary><span class="opname">'+esc(t.name||t.id||'')+'</span>'+eid+loc(t.id,t.name)+fmt+
      scriptIssueBadge(t.problems)+rv+'</summary>'+scriptProblemsHtml(t.problems)+body+'</details>';
  };
  if(n.type==='process' && (d.scriptTasks||[]).length){
    h+=section('scripttasks','Script tasks ('+d.scriptTasks.length+')', d.scriptTasks.map(scriptTaskHtml).join(''));
  }
  // CMMN keeps its script tasks in the plan tree — surface their bodies just like BPMN script tasks.
  if(n.type==='case' && d.planModel){
    const cs=[];
    (function walk(nd){ if(nd.script||(nd.problems||[]).length) cs.push(nd); (nd.children||[]).forEach(walk); })(d.planModel);
    if(cs.length) h+=section('scripttasks','Script tasks ('+cs.length+')', cs.map(scriptTaskHtml).join(''));
  }
  if(n.type==='process' && (d.events||[]).length){
    const evs=d.events.filter(e=>e.def||e.name);
    if(evs.length) h+=section('events','Events ('+evs.length+')','<div class="oplist">'+
      evs.map(e=>'<div class="oprow"'+dataEl(e.id)+'><span style="min-width:150px">'+esc(e.name||e.id||'')+'</span>'+loc(e.id,e.name)+
        '<span class="opkey">'+elementTerm(e.type)+'</span>'+(e.def?'<span class="pt">'+esc(e.def)+'</span>':'')+
        (e.value?'<span class="mono" style="color:var(--ink-faint)">'+esc(e.value)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='process' && (d.multiInstance||[]).length){
    h+=section('multiinstance','Multi-instance ('+d.multiInstance.length+')','<div class="oplist">'+
      d.multiInstance.map(m=>'<div class="oprow"'+dataEl(m.activity)+'><span class="muted" style="min-width:150px">'+esc(elName(EM,m.activity||''))+'</span>'+loc(m.activity)+
        (m.collection?'<span class="muted">over</span><span class="mono">'+paramSide(m.collection)+'</span>':'')+
        (m.elementVariable?'<span class="muted">as</span><span class="mono">'+paramSide(m.elementVariable)+'</span>':'')+
        (m.sequential==='true'?'<span class="pt">sequential</span>':'')+
        (m.cardinality?'<span class="pt">× '+esc(m.cardinality)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='process' && (d.conditions||[]).length){
    // Element *names* instead of raw ids (the id stays as a tooltip), a ⌖ that highlights the flow's
    // arrow on the diagram, and gateway grouping via the from-element — the old raw `sid-… → sid-…`
    // rows were impossible to map to anything.
    const elRef=id=>{ const nm=elName(EM,id);
      return '<span'+(nm!==String(id)?' data-tip="'+esc(String(id))+'"':'')+'>'+esc(nm)+'</span>'; };
    h+=section('conditions','Sequence flow conditions ('+d.conditions.length+')','<div class="oplist">'+
      d.conditions.map(c=>'<div class="oprow"'+dataEl(c.id)+'>'+
        '<span class="cflow" style="min-width:150px">'+elRef(c.from)+' <span class="pa">→</span> '+elRef(c.to)+'</span>'+
        loc(c.id)+
        '<span class="mono" style="flex:1">'+esc(c.condition||'')+'</span></div>').join('')+'</div>');
  }
  // Two things every element can carry, both previously dropped on the floor: the documentation the
  // modeller wrote about it, and the listeners it runs (only the process/case level and BPMN user tasks
  // were read before, so an execution listener on a service task existed nowhere in Atlas).
  if(n.type==='process'||n.type==='case'){
    const recs=elementRecords(n);
    const docs=recs.filter(r=>r.documentation);
    if(docs.length) h+=section('eldocs','Documentation ('+docs.length+')','<div class="oplist">'+
      docs.map(r=>'<div class="oprow"'+dataEl(r.id)+'><span style="min-width:150px">'+esc(r.name||r.id||'')+'</span>'+
        loc(r.id,r.name)+'<span style="flex:1">'+esc(r.documentation)+'</span></div>').join('')+'</div>');
    const ls=[].concat(
      (d.listeners||[]).map(l=>({owner:null, l})),
      ...recs.map(r=>(r.listeners||[]).map(l=>({owner:r, l}))),
    ).filter(x=>x.l&&(x.l.class||x.l.expression||x.l.delegateExpression||x.l.script));
    // Design keeps execution, task and lifecycle listeners in separate property groups — one section
    // each, named the way Design names them, instead of one pile called "Listeners".
    const byKind=new Map();
    ls.forEach(x=>{ const k=x.l.kind||'listener';
      if(!byKind.has(k)) byKind.set(k,[]); byKind.get(k).push(x); });
    [...byKind.keys()].sort().forEach(kind=>{
      const items=byKind.get(kind);
      h+=section('listeners-'+kind, plural(term('el',kind).label)+' ('+items.length+')','<div class="oplist">'+
        items.map(({owner:o, l})=>{
          const impl=l.class?vlink('java:'+l.class, l.class):esc(l.expression||l.delegateExpression||(l.script?'(script)':''));
          const who=o?'<span style="min-width:150px">'+esc(o.name||o.id||'')+'</span>'+loc(o.id,o.name)
                     :'<span class="muted" style="min-width:150px">'+esc(nodeKind(n))+'</span>';
          return '<div class="oprow"'+(o?dataEl(o.id):'')+'>'+who+
            (l.event?'<span class="pt">'+esc(l.event)+'</span>':'')+
            '<span class="mono" style="flex:1">'+impl+'</span></div>';
        }).join('')+'</div>');
    });
  }
  // The decision table itself. Only the row *count* used to survive parsing, so the conditions and
  // values that are the actual business logic were neither visible nor findable.
  if(n.type==='decision' && (d.rules||[]).length){
    const ann=d.rules.some(r=>r.annotation);
    // `o` marks where the inputs end and the outputs begin
    const cell=(tag,v,i)=>'<'+tag+(i===0?' class="o"':'')+'>'+esc(v==null||v===''?'—':String(v))+'</'+tag+'>';
    const row=r=>'<tr>'+(r.inputs||[]).map(c=>cell('td',c,-1)).join('')+
      (r.outputs||[]).map((c,i)=>cell('td',c,i)).join('')+
      (ann?'<td>'+esc(r.annotation||'')+'</td>':'')+'</tr>';
    h+=section('dmnrules','Rules ('+(d.ruleCount||d.rules.length)+')',
      '<div class="dmntab"><table><thead><tr>'+
      (d.inputs||[]).map(x=>cell('th',x,-1)).join('')+
      (d.outputs||[]).map((x,i)=>cell('th',x,i)).join('')+
      (ann?'<th>annotation</th>':'')+'</tr></thead><tbody>'+
      d.rules.map(row).join('')+'</tbody></table>'+
      (d.rulesTruncated?'<div class="muted" style="padding:4px 0">showing '+d.rules.length+' of '+
        d.rulesTruncated+' rules</div>':'')+'</div>');
  }
  if(n.type==='case' && d.planModel){
    const CRIT=caseCriteria(d);
    // the item's entry/exit criteria, each with its sentry's condition — right where the item is listed
    const critsOf=nd=>CRIT.filter(c=>(c.planItemDef!=null&&String(c.planItemDef)===String(nd.id))||
                                     (c.planItemDef==null&&c.planItem&&c.planItem===nd.name))
      .map(criterionChip).join(' ');
    const planItem=nd=>{
      const kids=(nd.children||[]);
      const label=esc(nd.name||nd.id||'');
      if(nd.type==='stage'||nd.type==='planFragment'||nd.type==='casePlanModel'){
        return '<details class="uses" open><summary>'+(nd.type==='casePlanModel'?'Plan model':(label||nd.type))+
          ' <span class="muted">('+kids.length+' item'+(kids.length===1?'':'s')+')</span> '+critsOf(nd)+'</summary>'+
          '<div class="plantree">'+kids.map(planItem).join('')+'</div></details>';
      }
      const rules=nd.rules?Object.keys(nd.rules)
        .map(r=>({repetitionRule:'repeatable',requiredRule:'required',manualActivationRule:'manual'}[r]||r))
        .map(t=>'<span class="pt">'+esc(t)+'</span>').join(''):'';
      const bits=[
        nd.formKey?'<span class="muted">form</span> '+vlink('form:'+nd.formKey, nd.formKey):'',
        nd.processRef?'<span class="muted">process</span> '+vlink('process:'+nd.processRef, nd.processRef):'',
        nd.caseRef?'<span class="muted">case</span> '+vlink('case:'+nd.caseRef, nd.caseRef):'',
        nd.decisionRef?'<span class="muted">decision</span> '+vlink('decision:'+nd.decisionRef, nd.decisionRef):'',
        nd.candidateGroups?'<span class="muted">groups</span> '+groupLinksHtml(nd.candidateGroups):'',
        critsOf(nd),
      ].filter(Boolean).join(' ');
      return '<div class="oprow" style="border:none"'+dataEl(nd.id)+'><span style="min-width:150px">'+(nd.type==='milestone'?'◆ ':'')+label+'</span>'+loc(nd.id,nd.name)+
        '<span class="opkey">'+elementTerm(nd.type, nd.serviceTaskType||undefined)+'</span>'+
        (bits?'<span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span>':'')+rules+'</div>';
    };
    h+=section('plan','Case plan model — stages & plan items', planItem(d.planModel));
  }
  if(n.type==='case' && (d.sentries||[]).length){
    const CRIT=caseCriteria(d);
    const ss=d.sentries.filter(s=>s.condition||(s.onParts||[]).length);
    // name each sentry by what it guards ("entry of Review", not "sentry3"); the raw id stays a tooltip
    if(ss.length) h+=section('sentries','Sentries — entry / exit criteria ('+ss.length+')','<div class="oplist">'+
      ss.map(s=>{
        const uses=CRIT.filter(c=>String(c.sentryRef)===String(s.id))
          .map(c=>(c.type==='entryCriterion'?'entry of ':'exit of ')+
                  elName(EM, c.planItemDef!=null?c.planItemDef:(c.planItem||'?')));
        const who=uses.length
          ? '<span style="min-width:150px" data-tip="'+esc(String(s.id||''))+'">'+esc(uses.join(', '))+'</span>'
          : '<span class="muted" style="min-width:150px">'+esc(s.id||'')+'</span>';
        return '<div class="oprow"'+dataEl(s.id)+'>'+who+
          ((s.onParts||[]).length?'<span class="pt">on '+esc(s.onParts.filter(Boolean).join(', '))+'</span>':'')+
          '<span class="mono" style="flex:1">'+esc(s.condition||'')+'</span></div>';
      }).join('')+'</div>');
  }
  if(n.type==='case' && (d.eventListeners||[]).length){
    h+=section('eventlisteners','Event listeners ('+d.eventListeners.length+')','<div class="oplist">'+
      d.eventListeners.map(e=>{
        const bits=[
          e.timer?'<span class="mono">'+esc(e.timer)+'</span>':'',
          e.eventType?'<span class="muted">event</span> '+vlink('event:'+e.eventType, e.eventType):'',
          e.signalRef?'<span class="muted">signal</span> '+vlink('signal:'+e.signalRef, e.signalRef):'',
        ].filter(Boolean).join(' ');
        return '<div class="oprow"'+dataEl(e.id)+'><span style="min-width:150px">'+esc(e.name||e.id||'')+'</span>'+loc(e.id,e.name)+
          '<span class="opkey">'+elementTerm(e.type)+'</span><span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span></div>';
      }).join('')+'</div>');
  }
  if((n.type==='form'||n.type==='page') && (d.fields||[]).length){
    h+=section('formfields','Fields ('+d.fields.length+')','<div class="oplist">'+
      d.fields.map(f=>{
        const req=(f.required===true||f.required==='true')?'<span class="pt" title="Required field">required</span>':'';
        const val=(f.value!=null&&f.value!=='')?'<span class="muted">←</span> <span class="mono">'+paramSide(String(f.value))+'</span>':'';
        return '<div class="oprow"><span class="mono" style="min-width:150px">'+fieldLink(f.id)+'</span>'+
          '<span class="muted" style="flex:1">'+esc(f.label==null?'':String(f.label))+'</span>'+val+
          '<span class="pt">'+esc(f.type||'')+'</span>'+req+'</div>';
      }).join('')+'</div>');
  }
  if((n.type==='form'||n.type==='page') && (d.dataSources||[]).length){
    h+=section('datasources','Data sources ('+d.dataSources.length+')','<div class="oplist">'+
      d.dataSources.map(s=>{
        const tgt=s.kind==='dataObject'?vlink('dataObject:'+s.key, s.key)
          :s.kind==='service'?vlink('service:'+s.key, s.key):esc(s.url||s.key||'');
        const op=s.op?'<span class="muted">operation</span> <span class="mono">'+esc(s.op)+'</span>':'';
        return '<div class="oprow">'+termHtml('kind-ds', s.kind, 'pt')+'<span class="mono" style="flex:1">'+tgt+'</span>'+op+'</div>';
      }).join('')+'</div>');
  }
  if(n.type==='securityPolicy' && (d.permissions||[]).length){
    h+=section('permissions','Permissions ('+d.permissions.length+') — who may do what','<div class="oplist">'+
      d.permissions.map(p=>'<div class="oprow"><span style="min-width:180px">'+esc(p.label||p.key||'')+'</span>'+
        (p.label&&p.key&&p.label!==p.key?'<span class="opid">'+esc(p.key)+'</span>':'')+
        '<span style="flex:1">'+(p.roles||[]).map(r=>vlink('group:'+r,r)).join(', ')+'</span></div>').join('')+'</div>');
  }
  if(n.type==='agent' && (d.tools||[]).length){
    h+=section('tools','Tools ('+d.tools.length+') — what the agent may call','<div class="nodechips">'+
      d.tools.map(t=>{const id=(t.type||'service')+':'+(t.key||'');
        return byId.get(id)?nodeChip(id):'<span class="nc"><span class="nm">'+esc(t.key||'')+'</span><span class="ty">'+esc(t.type||'')+'</span></span>';}).join('')+'</div>');
  }
  if(n.type==='agent' && (d.operations||[]).length){
    h+=section('agentops','Operations ('+d.operations.length+')',
      d.operations.map(o=>{
        const msgs=[['system',o.systemMessage],['user',o.userMessage]].filter(m=>m[1]);
        const key=(o.key&&o.key!==(o.name||o.key))?'<span class="opkey">'+esc(o.key)+'</span>':'';
        if(!msgs.length) return '<div class="op flat"><span class="opname">'+esc(o.name||o.key||'')+'</span>'+key+'</div>';
        return '<details class="op"><summary><span class="opname">'+esc(o.name||o.key||'')+'</span>'+key+
          '<span class="opcount">'+msgs.length+' prompt'+(msgs.length>1?'s':'')+'</span></summary>'+
          '<div class="parmgrid">'+msgs.map(m=>'<div class="pc"><span class="pd">'+m[0]+'</span>'+
            '<span class="pn mono">'+esc(m[1])+'</span></div>').join('')+'</div></details>';
      }).join(''));
  }
  if(n.type==='app' && (d.variables||[]).length){
    h+=section('appvars','App variables ('+d.variables.length+')','<div class="oplist">'+
      d.variables.map(v=>'<div class="oprow"><span class="mono" style="flex:1">'+fieldLink(v.key)+'</span>'+
        (v.type?'<span class="pt">'+esc(v.type)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='app' && (d.pages||[]).length){
    h+=section('apppages','Pages ('+d.pages.length+')','<div class="nodechips">'+
      d.pages.map(p=>byId.get('page:'+p.key)?nodeChip('page:'+p.key)
        :'<span class="nc"><span class="nm">'+esc(p.key||'')+'</span><span class="ty">page</span></span>').join('')+'</div>');
  }
  if(n.type==='action' && (d.script||(d.scriptProblems||[]).length)){
    h+=section('script','Bot script'+(d.scriptLanguage?' ('+esc(d.scriptLanguage)+')':'')+
      ((d.scriptProblems||[]).length?' '+scriptIssueBadge(d.scriptProblems):''),
      scriptProblemsHtml(d.scriptProblems)+
      codeBoxHtml(d.script, d.scriptLanguage, d.scriptProblems));
  }
  if(n.type==='service' && (d.operations||[]).length){
    h+=section('ops','Operations ('+d.operations.length+')',
      d.operations.map(o=>{
        const verb=o.method?'<span class="verb" style="color:'+color("endpoint")+'">'+esc(o.method)+'</span>':'';
        const title='<span class="opname">'+esc(o.fullUrl||o.url||o.name||'')+'</span>';
        // link the key to the operation's own node (its "where used" page)
        const opid='serviceOperation:'+n.key+'#'+(o.key||'');
        const key=((o.key&&byId.get(opid))
          ? '<span class="opkey vlink" data-id="'+enc(opid)+'" tabindex="0" role="link" title="Show where '+esc(o.key)+' is used">'+esc(o.key)+'</span>'
          : '<span class="opkey">'+esc(o.key||'')+'</span>')+copyBtn(o.key,'operation key');
        // An operation's contract has two halves: what a caller must supply and what it gets back.
        const decl=(o.params||[]).map(p=>['in',p]).concat((o.outParams||[]).map(p=>['out',p]));
        if(!decl.length) return '<div class="op flat">'+verb+title+'<span class="opcount">no params</span>'+key+'</div>';
        return '<details class="op"><summary>'+verb+title+
          '<span class="opcount">'+paramSummary(decl.map(([dir])=>({dir})))+'</span>'+key+'</summary>'+
          '<div class="parmgrid">'+decl.map(([dir,p])=>
            '<div class="pc"><span class="pd" style="color:var('+PDIR_COLOR[dir]+')">'+dir+'</span>'+
            '<span class="pn">'+esc(p.name)+'</span>'+
            (p.type?'<span class="pt">'+esc(p.type)+'</span>':'')+'</div>').join('')+'</div></details>';
      }).join(''));
  }
  if(n.type==='service' && d.schemaCoverage && (d.schemaCoverage.rows||[]).length){
    h+=section('coverage','Schema coverage — Liquibase → Service → Data object',
      schemaCoverageHtml(d.schemaCoverage, false));
  }
  else if(n.type==='service' && (d.columns||[]).length){
    h+=section('columns','Column mappings ('+d.columns.length+')','<div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name||'')+'</span>'+
        (c.columnName&&c.columnName!==c.name?'<span class="muted">'+esc(c.columnName)+'</span>':'')+
        (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  if(n.type==='java' && (d.endpoints||[]).length){
    h+=section('endpoints','Endpoints served','<div class="oplist">'+
      d.endpoints.map(e=>'<div class="oprow"><span class="verb" style="color:'+color("endpoint")+'">'+esc(e.http)+'</span><span>'+esc(e.path)+'</span><span class="muted">'+esc(e.handler)+'() :'+e.line+'</span></div>').join('')+'</div>');
  }
  if(n.type==='java' && (d.methods||[]).length){
    const cm=new Set(d.calledMethods||[]);
    h+=section('methods','Declared methods ('+d.methods.length+')','<div class="oplist">'+
      d.methods.slice(0,80).map(m=>'<div class="oprow"><span>'+esc(m.name)+'('+m.params+')</span><span class="muted">:'+m.line+(cm.has(m.name)?'  ◀ called by models':'')+'</span></div>').join('')+'</div>');
  }
  if((n.type==='process') && (d.serviceTasks||[]).length){
    // One entry per task with everything it owns folded in — implementation, callee, result variable,
    // field injections, a jump to its parameter mappings. Replaces the old flat list that dumped the
    // raw class/expression string plus two more sections (Field injections, Parameters) repeating the
    // same task names.
    const st=d.serviceTasks.filter(s=>s.class||s.delegateExpression||s.expression||s.type);
    if(st.length) h+=section('svctasks','Service tasks ('+st.length+')',
      st.map(s=>{
        const label=s.name||s.id||'';
        const eid=(s.id&&s.id!==label)?'<span class="opid">'+esc(s.id)+'</span>':'';
        const ty=elementTerm('serviceTask', s.type||undefined);
        const impl=s.class||s.delegateExpression||s.expression||'';
        // short impl for the summary: class basename / trimmed expression — the full string lives in the body
        const short=impl?(s.class?s.class.split('.').pop():(impl.length>36?impl.slice(0,35)+'…':impl)):'';
        const rv=s.resultVariable
          ? '<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+paramSide(s.resultVariable)+'</span>' : '';
        const fields=s.fields||{}; const fks=Object.keys(fields);
        const callee=stCalleeChip(s);
        const pn=(d.ioParameters||[]).filter(p=>String(p.element)===String(s.id)).length;
        // body rows: only what the summary can't carry
        let b='';
        if(impl) b+='<div class="oprow" style="border:none"><span class="muted">impl</span><span class="mono" style="flex:1;word-break:break-all">'+esc(impl)+'</span>'+implLink(s)+'</div>';
        if(s.operationKey) b+='<div class="oprow" style="border:none"><span class="muted">operation</span><span class="mono">'+esc(s.operationKey)+'</span></div>';
        if(s.topic) b+='<div class="oprow" style="border:none"><span class="muted">topic</span>'+vlink('topic:'+s.topic, s.topic)+'</div>';
        if(s.caseDefinitionKey) b+='<div class="oprow" style="border:none"><span class="muted">starts case</span>'+vlink('case:'+s.caseDefinitionKey, s.caseDefinitionKey)+'</div>';
        if(callee) b+='<div class="opchips">'+callee+'</div>';
        if(fks.length){
          // a script field is code, not a one-line value — show it as one
          b+='<div class="parmgrid">'+fks.map(k=>{
            const v=fields[k]==null?'':String(fields[k]);
            return k==='script'
              ? '<div class="pc" style="display:block"><span class="pd">script</span><pre class="scriptbox" style="margin:4px 0 2px">'+esc(v)+'</pre></div>'
              : '<div class="pc"><span class="pn">'+esc(k)+'</span><span class="pt" style="max-width:60%;overflow:hidden;text-overflow:ellipsis" data-tip="'+esc(v)+'">'+esc(v)+'</span></div>';
          }).join('')+'</div>';
        }
        if(pn) b+='<div class="opchips"><button type="button" class="dgbtn" data-reveal-el="'+esc(String(s.id))+'">'+pn+' parameter mapping'+(pn>1?'s':'')+' ↓</button></div>';
        if(!b) return '<div class="op flat"'+dataEl(s.id)+'><span class="opname">'+esc(label)+'</span>'+eid+loc(s.id,s.name)+
          (short?'<span class="muted mono" style="overflow:hidden;text-overflow:ellipsis">'+esc(short)+'</span>':'')+rv+
          '<span class="opcount"></span><span class="opkey">'+ty+'</span></div>';
        return '<details class="op"'+dataEl(s.id)+'><summary><span class="opname">'+esc(label)+'</span>'+eid+loc(s.id,s.name)+
          (short?'<span class="muted mono" style="overflow:hidden;text-overflow:ellipsis;flex:none;max-width:32%">'+esc(short)+'</span>':'')+rv+
          '<span class="opcount">'+(fks.length?fks.length+' field'+(fks.length>1?'s':''):'')+'</span>'+
          '<span class="opkey">'+ty+'</span></summary>'+b+'</details>';
      }).join(''));
  }
  if(n.type==='dataObject' && (d.columns||[]).length){
    h+=section('columns','Field mappings ('+d.columns.length+')','<div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name)+'</span><span class="muted">'+esc(c.label||'')+'</span>'+
        (c.refDataObject?'<span class="vlink" data-id="'+enc('dataObject:'+c.refDataObject)+'" tabindex="0" role="link">→ '+esc(c.refDataObject)+(c.relationship?' ('+esc(c.relationship)+')':'')+'</span>':'')+
        (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  if(n.type==='liquibase'){
    const a=d.authority||{};
    if(a.status==='superseded'){ const chips=(a.supersededBy||[]).map(k=>nodeChip('liquibase:'+k)).join('');
      h+='<div class="authnote authnote-old">⚠ Superseded revision — the live definition of <b>'+esc((d.effectiveTables||[]).join(', '))+'</b> is referenced elsewhere. These columns reflect an older revision of the same table.'+(chips?'<div>'+chips+'</div>':'')+'</div>'; }
    else if(a.status==='orphan'){
      h+='<div class="authnote authnote-orphan">⚠ Orphan changelog — no service or data object references it. It may be dead/legacy or referenced only at runtime.</div>'; }
  }
  if(n.type==='liquibase' && (d.columns||[]).length){
    const cov=d.coverage;                    // present only when a service references this changelog
    const inS=cov?new Set(cov.service||[]):null, inD=cov?new Set(cov.dataObject||[]):null;
    const stOf=k=>!inS.has(k)?'bad':(!inD.has(k)?'warn':'good');
    const stTitle={bad:'not mapped by any service',warn:'mapped in service, but no data object field',good:'mapped through to a data object'};
    const byT={}; d.columns.forEach(c=>{ (byT[c.table||'(table)']=byT[c.table||'(table)']||[]).push(c); });
    let b='';
    if(cov) b+='<div class="covlegend">'+
      '<span><span class="covdot" style="background:'+covColor('bad')+'"></span>not in service</span>'+
      '<span><span class="covdot" style="background:'+covColor('warn')+'"></span>not in data object</span>'+
      '<span><span class="covdot" style="background:'+covColor('good')+'"></span>mapped through</span></div>';
    Object.keys(byT).forEach(t=>{
      b+='<div style="margin:6px 0 12px"><div class="muted mono" style="margin-bottom:4px">'+esc(t)+'</div><div class="oplist">'+
        byT[t].map(c=>{ const st=cov?stOf(looseCol(c.name)):null;
          return '<div class="oprow'+(st==='bad'?' cov-bad':st==='warn'?' cov-warn':'')+'">'+
          (cov?'<span class="covdot" title="'+stTitle[st]+'" style="background:'+covColor(st)+'"></span>':'')+
          '<span>'+esc(c.name)+'</span>'+
          (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
          '</div>'; }).join('')+'</div></div>';
    });
    h+=section('columns','Columns ('+d.columns.length+')'+(cov?' — mapping coverage':''), b);
  }
  if((n.type==='expression'||n.type==='binding') && (d.problems||[]).length){
    h+=section('problems','Problems ('+d.problems.length+')','<div class="oplist">'+
      d.problems.map(p=>{
        const isErr=p.severity==='error';
        const col=isErr?color('invalidExpr'):color('suspectExpr');
        const snip=p.snippet||'';
        return '<div class="oprow"><span class="verb" style="color:'+col+'">'+(isErr?'error':'warning')+'</span>'+
          '<span style="flex:1">'+esc(p.message)+'</span>'+
          (snip?'<span class="mono" style="color:var(--ink-faint);font-size:10px">'+esc(snip)+'</span>':'')+
          '</div>';
      }).join('')+'</div>');
  }
  if((n.type==='expression'||n.type==='binding'||n.type==='customFunction'||n.type==='serviceOperation') && (d.usedBy||[]).length){
    h+=section('usedby','Used by ('+d.usedBy.length+')','<div class="nodechips">'+d.usedBy.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='serviceOperation' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">No service button, data-object field or CMMN service mapping in the scanned models calls this operation.</div>';
  }
  // a frontend binding links to the custom function(s) it calls; a custom function links back to the
  // exact bindings that call it (in addition to the forms/models under "Used by").
  if(n.type==='binding' && (d.calls||[]).length){
    h+=section('calls','Calls custom functions 🧩 ('+d.calls.length+')','<div class="nodechips">'+d.calls.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='customFunction' && (d.bindings||[]).length){
    h+=section('inbindings','Called in bindings ('+d.bindings.length+')','<div class="nodechips">'+d.bindings.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='customFunction' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">Registered via <b>externals.additionalData</b> but no <code>{{…}}</code> binding in the scanned models calls it.</div>';
  }
  // The data-flow view of a variable: every in/out mapping that reads or writes it, and where.
  if(n.type==='variable' && (d.ioParams||[]).length){
    h+=section('passedas','Passed as parameter ('+paramSummary(d.ioParams)+')','<div class="oplist">'+
      d.ioParams.map(p=>'<div class="oprow">'+
        '<span class="verb" style="color:var('+(PDIR_COLOR[p.dir]||'--ink-faint')+')">'+esc(p.dir)+'</span>'+
        '<span style="flex:1">'+nodeChip(p.model)+
          (p.element?'<span class="mono" style="color:var(--ink-faint)"> @'+esc(p.element)+'</span>':'')+'</span>'+
        '<span class="mono" style="font-size:var(--text-2xs)">'+paramFlowHtml(p)+'</span>'+
      '</div>').join('')+'</div>');
  }
  // The scripts that touch this variable. Each row jumps to that script's own row in its model — the
  // answer to "where is this variable actually set?" used to require reading every script by hand.
  if(n.type==='variable' && (d.scriptSites||[]).length){
    h+=section('inscripts','In scripts ('+d.scriptSites.length+')','<div class="oplist">'+
      d.scriptSites.map(s=>'<div class="oprow">'+
        '<span class="verb" style="color:var('+(s.api?'--ok-text':'--ink-faint')+')">'+
          (s.api?'sets / reads':'≈ reads')+'</span>'+
        '<span style="flex:1">'+nodeChip(s.model)+
          (s.element?'<span class="opref" data-goto="'+enc(s.model)+'" data-goto-el="'+esc(String(s.element))+
            '" tabindex="0" role="link" style="cursor:pointer">'+esc(s.elementName||s.element)+' ↓</span>':'')+
        '</span>'+
        (s.elementType?'<span class="pt">'+esc(s.elementType)+'</span>':'')+
      '</div>').join('')+'</div>');
  }
  if((n.type==='variable'||n.type==='string') && (d.usages||[]).length){
    let b='';
    d.usages.forEach(u=>{
      b+='<div style="margin:6px 0 12px">'+nodeChip(u.model)+
         '<div class="oplist" style="margin-top:5px">'+
         (u.snippets||[]).map(s=>'<div class="oprow"><span class="mono">'+esc(s)+'</span></div>').join('')+
         '</div></div>';
    });
    h+=section('usedin','Used in ('+d.usages.length+' models) — effective occurrences', b);
  }
  // Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).
  if(d._uses){
    const ord=[['variable','Variables'],['expression','Backend expressions ${ }'],
               ['binding','Frontend bindings {{ }}'],['customFunction','Custom functions 🧩'],
               ['serviceOperation','Service operations'],['string','String literals']];
    let parts='';
    ord.forEach(([t,lbl])=>{ const ids=(d._uses||{})[t]; if(ids&&ids.length)
      parts+='<details class="uses"><summary>'+lbl+' ('+ids.length+')</summary><div class="nodechips">'+ids.map(nodeChip).join('')+'</div></details>'; });
    h+=section('uses','Uses — variables &amp; expressions', parts);
  }
  return h;
}

// ---------- rendered model diagram (BPMN/CMMN/DMN), when Atlas embedded one ----------
function diagramView(n){
  const svg = n.data && n.data.diagram;
  if(!svg) return '';
  // Atlas-generated, script-free SVG; scale it to fit the panel while keeping its aspect ratio.
  // The SVG keeps its intrinsic size; the viewport scales it. A diagram of 40 elements is unreadable
  // squeezed into the panel, so it gets zoom, pan and a fullscreen view instead of `max-width:100%`.
  return section('diagram','Diagram',
    '<div class="dgbar">'+
      '<button data-z="out" title="Zoom out">−</button>'+
      '<button data-z="fit" title="Fit to width">fit</button>'+
      '<button data-z="in" title="Zoom in">+</button>'+
      '<span class="dgpct">100%</span>'+
      '<button data-z="full" title="Open full screen">⤢ full screen</button>'+
      '<span class="dghint">click an element for details · drag to pan · '+MODK+' + scroll to zoom</span>'+
    '</div>'+
    '<div class="dgview"><div class="dgpan">'+svg+'</div></div>');
}

// ---------- neighborhood graph (ego view: selected node + 1-hop neighbors) ----------
const GRAPH_MAX_NEIGHBORS = 26;
function neighborhoodSvg(n){
  // Collect unique neighbors with direction + relation (a node can appear on both sides).
  const seen=new Map();
  (outM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:'out'}); });
  (incM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:'in'}); });
  const all=[...seen.values()];
  if(!all.length) return '';
  const shown=all.slice(0,GRAPH_MAX_NEIGHBORS);
  const W=680,H=340,CX=W/2,CY=H/2,RX=CX-130,RY=CY-40;
  const trunc=(s,len)=>s.length>len?s.slice(0,len-1)+'…':s;
  let g='';
  shown.forEach((e,i)=>{
    const nn=byId.get(e.id);
    const a=-Math.PI/2 + i*2*Math.PI/shown.length;
    const x=CX+RX*Math.cos(a), y=CY+RY*Math.sin(a);
    const dash=e.dir==='in'?' stroke-dasharray="4 3"':'';
    const dim=(e.sus||e.dyn)?' stroke-opacity="0.45"':'';
    const flagTxt=e.sus?' (suspect)':e.dyn?' (dynamic)':'';
    // tooltips via data-tip (the DOM bubble), not <title> children — SVG-native tooltips never
    // render in the embedded JCEF viewer
    const relTerm=term('rel', e.rel).label;
    g+='<line x1="'+CX+'" y1="'+CY+'" x2="'+x.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="var(--line2)" stroke-width="1"'+dash+dim+' data-tip="'+esc(relTerm+flagTxt+(e.dir==='in'?' (incoming)':''))+'"/>';
    const anchor=Math.cos(a)>0.25?'start':Math.cos(a)<-0.25?'end':'middle';
    const tx=x+(anchor==='start'?9:anchor==='end'?-9:0), ty=y+(anchor==='middle'?(Math.sin(a)>0?16:-10):4);
    g+='<g class="gn" data-id="'+enc(e.id)+'" tabindex="0" role="link" style="cursor:pointer"'+
       ' data-tip="'+esc(nn.label+' — '+relTerm)+'" aria-label="'+esc(nn.label+' — '+relTerm)+'">'+
       '<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="5" fill="'+nodeColor(nn)+'"/>'+
       '<text x="'+tx.toFixed(1)+'" y="'+ty.toFixed(1)+'" text-anchor="'+anchor+'" font-size="10" font-family="var(--mono)" fill="var(--ink-dim)">'+esc(trunc(nn.label,26))+'</text></g>';
  });
  // center node on top of the lines
  g+='<circle cx="'+CX+'" cy="'+CY+'" r="8" fill="'+nodeColor(n)+'" stroke="var(--panel)" stroke-width="2"/>'+
     '<text x="'+CX+'" y="'+(CY+22)+'" text-anchor="middle" font-size="11" font-weight="600" font-family="var(--mono)" fill="var(--ink)">'+esc(trunc(n.label,32))+'</text>';
  const more=all.length>shown.length?'<div class="muted" style="font-size:10.5px;margin:2px 0 6px">showing '+shown.length+' of '+all.length+' neighbors — the full list is below</div>':'';
  return '<details class="uses" open><summary>Neighborhood — solid: uses, dashed: used by</summary>'+
    '<div style="padding:4px 10px 8px">'+more+
    '<svg viewBox="0 0 '+W+' '+H+'" style="width:100%;max-width:820px;display:block" role="img" aria-label="Relationship graph of '+esc(n.label)+'">'+g+'</svg></div></details>';
}

// Resolve a service-task implementation to a clickable Java node chip + method.
function implLink(s){
  if(s.class){ const id='java:'+s.class; if(byId.get(id)) return jchip(id, s.class); return ''; }
  const ex=s.expression||s.delegateExpression||'';
  const m=ex.match(/[#$]\{\s*([A-Za-z_]\w*)(?:\s*\.\s*([A-Za-z_]\w*)\s*\()?/);
  if(m){ const id=beanToNode.get(m[1]); if(id) return jchip(id,(byId.get(id).label)+(m[2]?'.'+m[2]+'()':'')); }
  return '';
}
function jchip(id,label){
  const k=(byId.get(id)||{}).key||label;
  return '<span class="nc" data-id="'+enc(id)+'" tabindex="0" role="link" style="flex:none"><span class="dot" style="background:'+color('java')+'"></span><span class="nm">'+esc(label)+'</span>'+copyBtn(k,'class')+'</span>';
}

function renderDetail(){
  const det=document.getElementById('detail');
  // The info card lives on <body> now, so it survives this re-render — drop it, or it would keep
  // showing an element of the model we are navigating away from.
  hideDgCard();
  if(!state.sel || !byId.get(state.sel)){
    det.innerHTML='<div class="estate"><div class="estate-ic" aria-hidden="true">⌕</div>'+
      '<div class="et">'+(state.cat?'Nothing selected':'Flowable Atlas')+'</div>'+
      '<div class="eh">Pick an item from the list — click any relationship to travel the graph.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  let h='';
  h+='<div class="dhead">'+(_navCount>1?'<button id="back">← back</button>':'')+
     '<button id="sectall" title="Expand or collapse every section on this page">⇕ expand all</button>'+
     '<button id="permalink" title="Copy a shareable link to this node">🔗 copy link</button></div>';
  h+='<div class="dbody">';
  const kindHint=term('type', n.type).hint;
  h+='<span class="chip"'+(kindHint?' title="'+esc(kindHint)+'"':'')+'>'+
     '<span class="dot" style="background:'+nodeColor(n)+'"></span>'+esc(nodeKind(n))+'</span>';
  h+='<div class="dtitle">'+esc(n.label)+authBadge(n)+'</div>';
  h+='<div class="dkey mono">'+esc(n.key)+copyBtn(n.key,'key')+'</div>';
  if(n.file) h+='<div class="dfile" title="click to copy" data-copy="'+enc(n.file)+'"><span class="fp">'+esc(n.file)+'</span>'+copyBtn(n.file,'path')+'</div>';
  const rows=describe(n);
  if(rows.length){ h+='<div class="grid">'+rows.map(r=>{
      const v=r[1], isHtml=v&&v.html!==undefined;
      const shown=isHtml?v.html:esc(String(v));
      // auto-copy scalar values; link rows opt in via a `copy:` payload. Skip counts (numbers).
      const ct=isHtml?(v.copy!=null?String(v.copy):null):(typeof v==='number'?null:String(v));
      return '<div class="cell"><div class="k">'+esc(r[0])+'</div><div class="v mono">'+shown+copyBtn(ct,r[0])+'</div></div>';
    }).join('')+'</div>'; }
  h+=diagramView(n);
  h+=neighborhoodSvg(n);
  h+=detailExtra(n);
  const relBody=g=>Object.keys(g).sort().map(rel=>
    '<div class="relgrp"><div class="lab">'+termHtml('rel', rel)+'</div><div class="nodechips">'+
    [...g[rel].values()].map(e=>nodeChip(e.id,e)).join('')+'</div></div>').join('');
  // outgoing
  const ok=Object.keys(out).sort();
  if(ok.length) h+=section('rels-out','Uses / references ('+ok.reduce((a,k)=>a+out[k].size,0)+')', relBody(out));
  // incoming
  const ik=Object.keys(inc).sort();
  if(ik.length) h+=section('rels-in','Used by / referenced from ('+ik.reduce((a,k)=>a+inc[k].size,0)+')', relBody(inc));
  if(!ok.length && !ik.length) h+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  h+='</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>history.back();
  // Remember every section's open state, and offer one control to flip them all at once.
  const sects=[...det.querySelectorAll('details.sect')];
  sects.forEach(s=>s.addEventListener('toggle',()=>sectRemember(dec(s.dataset.sect), s.open)));
  const sa=document.getElementById('sectall');
  if(sa){
    const sync=()=>{ sa.textContent = sects.every(s=>s.open) ? '⇕ collapse all' : '⇕ expand all'; };
    sync();
    sects.forEach(s=>s.addEventListener('toggle',sync));
    sa.onclick=()=>{ const open=!sects.every(s=>s.open);
      sects.forEach(s=>{ s.open=open; sectRemember(dec(s.dataset.sect), open); }); sync(); };
    if(!sects.length) sa.hidden=true;
  }
  const pl=document.getElementById('permalink');
  if(pl) pl.onclick=()=>{
    // strip ?ideTheme=… (IDE embedding seed) — a stale param in a shared link only confuses
    const url=location.search?location.href.replace(location.search,''):location.href;
    const done=()=>{ pl.textContent='✓ link copied'; setTimeout(()=>{ pl.textContent='🔗 copy link'; },1500); };
    if(navigator.clipboard&&navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done,()=>prompt('Copy link:',url));
    else prompt('Copy link:',url);   // clipboard API is unavailable on file:// in some browsers
  };
  det.querySelectorAll('.nc, .gn, .vlink').forEach(c=>{
    c.onclick=()=>select(dec(c.dataset.id));
    c.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); select(dec(c.dataset.id)); } };
  });
  // clicking the path (but not its copy icon) copies too — routed through atlasCopy so the "copied"
  // hint only shows on real success and the child copy button survives (no textContent nuke).
  const fp=det.querySelector('.dfile');
  if(fp) fp.onclick=e=>{ if(e.target.closest('.cpy')) return;
    atlasCopy(dec(fp.dataset.copy), ()=>{ fp.classList.add('copied'); setTimeout(()=>fp.classList.remove('copied'),1200); }); };
  det.querySelectorAll('.cpy').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation();   // don't navigate the chip/link this button sits inside
      atlasCopy(dec(b.dataset.copy), ()=>{ if(b.dataset.busy) return; b.dataset.busy='1';
        const old=b.innerHTML; b.classList.add('ok'); b.innerHTML=CPY_OK_SVG;
        setTimeout(()=>{ b.classList.remove('ok'); b.innerHTML=old; delete b.dataset.busy; },1200); }); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };   // keep Enter/Space from the parent's nav
  });
  // ⌖ locate-on-diagram buttons; preventDefault keeps a click inside a <summary> from toggling it
  det.querySelectorAll('.dgloc').forEach(b=>{
    b.onclick=e=>{ e.preventDefault(); e.stopPropagation(); locateOnDiagram(det, b.dataset.elRef, b.dataset.elName); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };
  });
  // "N parameter mappings ↓" inside a service task — jumps to that element's mapping group
  det.querySelectorAll('[data-reveal-el]').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation(); revealByEl(det, b.dataset.revealEl); };
  });
  // travel to another node AND land on one of its elements (a variable → the script that sets it)
  det.querySelectorAll('[data-goto]').forEach(b=>{
    const go=e=>{ e.stopPropagation(); select(dec(b.dataset.goto), '', b.dataset.gotoEl||''); };
    b.onclick=go;
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); go(e); } };
  });
  wireParamFilter(det);
  wireDiagram(det);
  applyFocus(det);
}

// ---------- diagram zoom / pan ----------
// One controller per viewport: the SVG is never re-rendered, only transformed, so panning and zooming
// cost nothing regardless of how many elements the diagram has.
// `opts.modWheel` (the inline diagram): a plain wheel scrolls the PAGE as everywhere else — zooming
// needs ⌘/Ctrl held (a trackpad pinch reports ctrlKey, so pinch-zoom keeps working). Without it the
// diagram swallows every scroll that happens to pass over it. The fullscreen modal zooms freely.
function zoomable(view, opts){
  opts=opts||{};
  const pan=view.querySelector('.dgpan'), svg=pan&&pan.querySelector('svg');
  if(!svg) return null;
  const z={scale:1, tx:0, ty:0, view, pan, svg, moved:false};
  view._z=z;                                       // reached through the DOM by locate/click handlers
  z.apply=()=>{
    pan.style.transform='translate('+z.tx+'px,'+z.ty+'px) scale('+z.scale+')';
    const pct=view.parentElement&&view.parentElement.querySelector('.dgpct');
    if(pct) pct.textContent=Math.round(z.scale*100)+'%';
  };
  // "fit" means fit the width — the usual reason a diagram is unreadable is that it is wider than the panel
  z.fit=()=>{
    const w=svg.getAttribute('width');
    const natural=w?parseFloat(w):svg.getBoundingClientRect().width/z.scale;
    // clientWidth is 0 while the section is collapsed — never derive a zero/negative scale from it
    z.scale=(natural>0&&view.clientWidth>16)?Math.min(1, (view.clientWidth-8)/natural):1;
    z.tx=0; z.ty=0; z.apply();
    // Inline only: a transform doesn't shrink layout height, so a wide diagram scaled down would
    // leave a tall white gap under itself — size the viewport to the scaled drawing instead.
    if(opts.modWheel){
      const hAttr=parseFloat(svg.getAttribute('height'))||0;
      if(hAttr>0) view.style.height=Math.round(Math.max(120, Math.min(hAttr*z.scale+2, window.innerHeight*0.6)))+'px';
    }
  };
  z.zoom=(factor, ox, oy)=>{
    const next=Math.min(8, Math.max(0.1, z.scale*factor));
    if(ox!=null){                                  // keep the point under the cursor put
      const k=next/z.scale;
      z.tx=ox-(ox-z.tx)*k; z.ty=oy-(oy-z.ty)*k;
    }
    z.scale=next; z.apply();
  };
  view.addEventListener('wheel', e=>{
    if(opts.modWheel && !e.ctrlKey && !e.metaKey){ wheelHint(view); return; }   // let the page scroll
    e.preventDefault();
    const r=view.getBoundingClientRect();
    z.zoom(e.deltaY<0?1.12:1/1.12, e.clientX-r.left, e.clientY-r.top);
  }, {passive:false});
  view.addEventListener('pointerdown', e=>{
    if(e.button!==0) return;
    // setPointerCapture retargets the eventual `click` to the view itself, so e.target there never
    // reaches the SVG element that was pressed — remember the real press target for the click handler.
    z.downTarget=e.target;
    view.setPointerCapture(e.pointerId); view.classList.add('grabbing');
    const sx=e.clientX-z.tx, sy=e.clientY-z.ty, ox=e.clientX, oy=e.clientY;
    z.moved=false;
    const move=ev=>{
      if(Math.abs(ev.clientX-ox)+Math.abs(ev.clientY-oy)>4) z.moved=true;   // a pan, not a click
      z.tx=ev.clientX-sx; z.ty=ev.clientY-sy; z.apply();
    };
    const up=()=>{ view.classList.remove('grabbing');
      view.removeEventListener('pointermove',move); view.removeEventListener('pointerup',up); };
    view.addEventListener('pointermove',move); view.addEventListener('pointerup',up);
  });
  return z;
}
// A transient "how do I zoom" pill, shown when a plain wheel passes over the inline diagram —
// the page scrolled as expected, this just teaches the modifier.
function wheelHint(view){
  let h=view.querySelector('.dgwheelhint');
  if(!h){
    h=document.createElement('div'); h.className='dgwheelhint';
    h.textContent=MODK+' + scroll to zoom';
    view.appendChild(h);
  }
  h.classList.add('show');
  clearTimeout(h._t); h._t=setTimeout(()=>h.classList.remove('show'), 1100);
}
function wireZoomButtons(bar, z){
  bar.querySelectorAll('button[data-z]').forEach(b=>{
    const a=b.dataset.z;
    if(a==='full') return;                          // handled by the caller — it owns the modal
    b.onclick=()=>{ if(a==='in') z.zoom(1.25); else if(a==='out') z.zoom(1/1.25); else z.fit(); };
  });
}
function wireDiagram(det){
  const view=det.querySelector('.dgview');
  if(!view) return;
  const z=zoomable(view, {modWheel:true});
  if(!z) return;
  // A collapsed section has no layout (clientWidth 0) — fit on the first real layout instead.
  const tryFit=()=>{ if(!z._fitted && view.clientWidth>0){ z.fit(); z._fitted=true; } };
  tryFit();
  const sect=view.closest('details');
  if(sect) sect.addEventListener('toggle',()=>{ if(sect.open) tryFit(); });
  liftSvgTitles(view);
  wireDgClicks(view, false);
  const bar=det.querySelector('.dgbar');
  wireZoomButtons(bar, z);
  const full=bar.querySelector('button[data-z="full"]');
  if(full) full.onclick=()=>openDiagramModal(z.svg);
}

// ---------- fullscreen diagram ----------
const dgmodal=document.getElementById('dgmodal');
let _dgZoom=null;
function openDiagramModal(svg){
  if(!dgmodal) return;
  const pan=dgmodal.querySelector('.dgpan');
  pan.innerHTML='';
  pan.appendChild(svg.cloneNode(true));            // a clone: the inline diagram stays as it was
  const t=document.getElementById('dgtitle');
  const n=state.sel&&byId.get(state.sel);
  if(t) t.textContent=n?n.label:'';
  dgmodal.hidden=false;
  const view=document.getElementById('dgmodalview');
  view.scrollTop=0;
  _dgZoom=zoomable(view);
  if(_dgZoom){ _dgZoom.fit(); wireZoomButtons(dgmodal.querySelector('.dgbar'), _dgZoom); }
  liftSvgTitles(view);
  wireDgClicks(view, true);
}
function closeDiagramModal(){
  if(!dgmodal||dgmodal.hidden) return;
  hideDgCard();
  dgmodal.hidden=true;
  dgmodal.querySelector('.dgpan').innerHTML='';    // drop the clone; a big SVG is worth reclaiming
  _dgZoom=null;
}
if(dgmodal){
  dgmodal.addEventListener('mousedown',e=>{ if(e.target.closest('[data-close]')) closeDiagramModal(); });
  document.addEventListener('keydown',e=>{
    if(dgmodal.hidden || !_dgZoom) return;
    if(e.key==='Escape'){ if(_dgCard){ hideDgCard(); } else closeDiagramModal(); }
    else if(e.key==='+'||e.key==='='){ e.preventDefault(); _dgZoom.zoom(1.25); }
    else if(e.key==='-'){ e.preventDefault(); _dgZoom.zoom(1/1.25); }
    else if(e.key==='0'){ e.preventDefault(); _dgZoom.fit(); }
  });
}
document.addEventListener('keydown',e=>{ if(e.key==='Escape'&&_dgCard&&(!dgmodal||dgmodal.hidden)) hideDgCard(); });

// ---------- diagram interactivity ----------
// The renderer stamps every shape/edge group with its model element id (`data-el`), which is the same
// id the parsed data attributes things to (parameters, tasks, flow conditions). That one contract gives
// both directions: click a canvas element → an info card + "show in details"; click ⌖ on a detail row →
// the diagram pans to, and highlights, that element.
const LOC_SVG='<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="6.5"/><path d="M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4"/></svg>';
function cssEsc(s){ return (window.CSS&&CSS.escape)?CSS.escape(String(s)):String(s).replace(/["\\\]]/g,'\\$&'); }

// The renderer keeps native <title> children for no-JS viewers (the exported .svg files), but the
// embedded JCEF viewer never shows them — lift each into the data-tip bubble, the one tooltip path
// that works everywhere. Lifted before any clone, so the modal copy inherits the attributes.
function liftSvgTitles(view){
  view.querySelectorAll('svg g > title').forEach(t=>{
    const g=t.parentNode;
    if(!g.hasAttribute('data-tip')) g.setAttribute('data-tip', t.textContent);
    g.removeChild(t);
  });
}

// element id -> {name, type, sub} from every element list the parser produced for this node.
function elementNames(n){
  const d=n.data||{}, m=new Map();
  const put=(id,name,type,sub)=>{ if(id==null||id==='') return; const k=String(id);
    if(!m.has(k)) m.set(k,{name:name||'', type:type||'', sub:sub||null}); };
  (d.userTasks||[]).forEach(t=>put(t.id,t.name,'userTask'));
  (d.serviceTasks||[]).forEach(t=>put(t.id,t.name,'serviceTask',t.type));
  (d.scriptTasks||[]).forEach(t=>put(t.id,t.name,'scriptTask'));
  (d.callActivities||[]).forEach(t=>put(t.id,t.name,'callActivity'));
  (d.subProcesses||[]).forEach(t=>put(t.id,t.name,t.type||'subProcess'));
  (d.ruleTasks||[]).forEach(t=>put(t.id,t.name,'serviceTask','dmn'));
  (d.events||[]).forEach(e=>put(e.id,e.name,e.type));
  (d.gateways||[]).forEach(g=>put(g.id,g.name,g.type));
  (d.otherTasks||[]).forEach(t=>put(t.id,t.name,t.type));
  (d.eventListeners||[]).forEach(e=>put(e.id,e.name,e.type));
  (d.milestones||[]).forEach(x=>{ if(x&&typeof x==='object') put(x.id,x.name,'milestone'); });
  if(d.planModel)(function walk(nd){ put(nd.id,nd.name,nd.type,nd.serviceTaskType); (nd.children||[]).forEach(walk); })(d.planModel);
  // criterion diamonds: named after the plan item they guard, typed entry/exitCriterion.
  // (Resolve via the definition already indexed above — `planItem` may be a raw definition id.)
  caseCriteria(d).forEach(c=>{
    const def=c.planItemDef!=null?m.get(String(c.planItemDef)):null;
    put(c.id, (def&&def.name)||c.planItem, c.type);
  });
  return m;
}
function elName(em, id){ const e=em.get(String(id)); return (e&&e.name)?e.name:String(id==null?'':id); }
// Every element record of a model, whatever list it lives in. Used by the views that are about a
// property elements *share* (documentation, listeners) rather than about one element type.
function elementRecords(n){
  const d=n.data||{}, out=[];
  const push=arr=>(arr||[]).forEach(r=>{ if(r&&typeof r==='object') out.push(r); });
  push(d.userTasks); push(d.serviceTasks); push(d.scriptTasks); push(d.ruleTasks);
  push(d.callActivities); push(d.subProcesses); push(d.events); push(d.gateways);
  push(d.otherTasks); push(d.eventListeners); push(d.milestones);
  if(d.planModel)(function walk(nd){ out.push(nd); (nd.children||[]).forEach(walk); })(d.planModel);
  return out;
}
// A case's entry/exit criteria (from the plan tree), each joined with its sentry's condition/on-parts.
function caseCriteria(d){
  if(!d.planModel) return [];
  const byS=new Map((d.sentries||[]).map(s=>[String(s.id), s]));
  const out=[];
  (function walk(nd){
    (nd.criteria||[]).forEach(c=>{
      const s=c.sentryRef!=null?byS.get(String(c.sentryRef)):null;
      out.push({id:c.id, planItem:c.planItem, planItemDef:c.planItemDef, type:c.type, sentryRef:c.sentryRef,
                condition:(s&&s.condition)||'', onParts:((s&&s.onParts)||[]).filter(Boolean)});
    });
    (nd.children||[]).forEach(walk);
  })(d.planModel);
  return out;
}
// entry ◇ / exit ◆ chip with the sentry's condition (or its on-parts when there is no if-part).
function criterionChip(c){
  const what=c.condition?'<span class="mono" style="color:var(--ink-faint);font-size:var(--text-2xs)">'+esc(c.condition)+'</span>'
    :(c.onParts.length?'<span class="muted" style="font-size:var(--text-2xs)">on '+esc(c.onParts.join(', '))+'</span>':'');
  return '<span style="display:inline-flex;gap:4px;align-items:baseline">'+
    '<span class="pt">'+(c.type==='entryCriterion'?'entry ◇':'exit ◆')+'</span>'+what+'</span>';
}
// The case plan item carrying this id (CMMN keeps its per-element facts in the plan tree).
function planItemById(d, id){
  let hit=null;
  if(d.planModel)(function walk(nd){ if(String(nd.id)===String(id)) hit=hit||nd; (nd.children||[]).forEach(walk); })(d.planModel);
  return hit;
}

function dgSelect(view, g){
  view.querySelectorAll('.dgsel').forEach(x=>x.classList.remove('dgsel'));
  if(g) g.classList.add('dgsel');
}
// Pan so the element sits centered in the viewport (screen px = user units × scale, since the SVG's
// width/height equal its viewBox size).
function dgCenter(z, g){
  try{
    const bb=g.getBBox(), vb=z.svg.viewBox.baseVal;
    z.tx=z.view.clientWidth/2-(bb.x+bb.width/2-vb.x)*z.scale;
    z.ty=z.view.clientHeight/2-(bb.y+bb.height/2-vb.y)*z.scale;
    z.apply();
  }catch(e){}
}
// A diagram group for the element: by id, falling back to the tooltip name (CMMN DI references plan
// item ids while the parsed tree keys definitions by their own id — the name bridges the two).
function dgFind(view, elId, name){
  let g=view.querySelector('[data-el="'+cssEsc(elId)+'"]');
  if(!g && name){
    g=[...view.querySelectorAll('[data-el]')].find(x=>{
      const t=x.getAttribute('data-tip')||'';
      return t===name || t.indexOf(name+' — ')===0;
    })||null;
  }
  return g;
}
// ⌖ on a detail row: open the diagram section, highlight the element and pan to it.
function locateOnDiagram(det, elId, name){
  const sect=det.querySelector('details.sect[data-sect="diagram"]');
  if(!sect) return;
  sect.open=true;
  const view=sect.querySelector('.dgview'), z=view&&view._z;
  if(!z) return;
  if(!z._fitted && view.clientWidth>0){ z.fit(); z._fitted=true; }   // first reveal of a kept-closed section
  const g=dgFind(view, elId, name);
  if(!g) return;
  dgSelect(view, g);
  dgCenter(z, g);
  sect.scrollIntoView({block:'nearest'});
}
// The other direction: open every detail row/group attributed to this element and flash it.
function revealByEl(det, elId){
  const rows=[...det.querySelectorAll('[data-el]')]
    .filter(x=>x.dataset.el===String(elId) && !x.closest('.dgview'));
  if(!rows.length) return false;
  det.querySelectorAll('.hit').forEach(x=>x.classList.remove('hit'));
  rows.forEach(el=>{
    for(let p=el.parentElement; p&&p!==det; p=p.parentElement){ if(p.tagName==='DETAILS') p.open=true; }
    if(el.tagName==='DETAILS') el.open=true;
    el.classList.add('hit','flash');
  });
  requestAnimationFrame(()=>rows[0].scrollIntoView({block:'center'}));
  setTimeout(()=>det.querySelectorAll('.flash').forEach(x=>x.classList.remove('flash')), 1800);
  return true;
}

// ---------- element info card (click a diagram element) ----------
let _dgCard=null;
function hideDgCard(){ if(_dgCard&&_dgCard.parentNode) _dgCard.parentNode.removeChild(_dgCard); _dgCard=null; }
// The card is a small window on <body> — `position:fixed`, NOT a child of the diagram viewport. That is
// what lets it be dragged and resized far past the drawing area: in a narrow IDE tool window the
// viewport is much smaller than the space a card full of parameters needs, and a card clamped to it
// would stay unusably tiny. It still *starts* docked to the viewport's top-right corner (out of the
// drawing, and stable while you click through elements). Size and the corner offset are remembered, so
// the docking carries over between the inline view and the (wider) fullscreen modal.
const DGCARD_STORE='atlas-dgcard';
function dgCardPrefs(){ try{ return JSON.parse(localStorage.getItem(DGCARD_STORE)||'{}')||{}; }catch(err){ return {}; } }
function dgCardRemember(patch){
  try{ localStorage.setItem(DGCARD_STORE, JSON.stringify(Object.assign(dgCardPrefs(), patch))); }catch(err){}
}
function placeDgCard(view, card){
  const p=dgCardPrefs(), r=view.getBoundingClientRect();
  if(p.w) card.style.width=Math.max(240, Math.min(p.w, window.innerWidth-16))+'px';
  if(p.h) card.style.height=Math.max(90, Math.min(p.h, window.innerHeight-16))+'px';
  const rx=p.rx!=null?p.rx:8, ty=p.ty!=null?p.ty:8;
  card.style.left=(r.right-card.offsetWidth-rx)+'px';
  card.style.top =(r.top+ty)+'px';
  clampDgCard(card);
}
/** Keep the card reachable inside the window — after placing it, a drag, or a window resize. */
function clampDgCard(card){
  const x=parseFloat(card.style.left)||0, y=parseFloat(card.style.top)||0;
  card.style.left=Math.max(4, Math.min(x, window.innerWidth-48))+'px';
  card.style.top =Math.max(4, Math.min(y, window.innerHeight-28))+'px';
}
function wireDgCardMoveResize(view, card){
  const head=card.querySelector('.dgcard-head');
  if(head) head.addEventListener('pointerdown', e=>{
    if(e.target.closest('button')) return;                 // the ✕ stays a click
    e.preventDefault(); e.stopPropagation();
    const sx=e.clientX-card.offsetLeft, sy=e.clientY-card.offsetTop;
    const move=ev=>{
      // clamped to the WINDOW, not to the diagram — the card is free to sit anywhere on the page
      card.style.left=Math.max(4, Math.min(ev.clientX-sx, window.innerWidth-48))+'px';
      card.style.top =Math.max(4, Math.min(ev.clientY-sy, window.innerHeight-28))+'px';
    };
    const up=()=>{
      document.removeEventListener('pointermove',move); document.removeEventListener('pointerup',up);
      const r=view.getBoundingClientRect();
      dgCardRemember({rx:Math.round(r.right-(card.offsetLeft+card.offsetWidth)), ty:Math.round(card.offsetTop-r.top)});
    };
    document.addEventListener('pointermove',move); document.addEventListener('pointerup',up);
  });
  // native corner resize (CSS resize:both) — remember the size the user settles on
  if(window.ResizeObserver){
    let first=true;
    new ResizeObserver(()=>{
      if(first){ first=false; return; }                    // the observe() call itself fires once
      clearTimeout(card._rszT);
      card._rszT=setTimeout(()=>{
        if(card.isConnected) dgCardRemember({w:card.offsetWidth, h:card.offsetHeight});
      }, 300);
    }).observe(card);
  }
}
window.addEventListener('resize',()=>{ if(_dgCard) clampDgCard(_dgCard); });
function wireDgClicks(view, inModal){
  if(view._dgClicksWired) return;                     // the modal view persists across opens
  view._dgClicksWired=true;
  view.addEventListener('click', e=>{
    const z=view._z;
    // Pointer capture (the pan handler) retargets real clicks to the view — resolve the element from
    // the remembered press target; synthetic/keyboard clicks (no pointerdown) fall back to e.target.
    const pressed=z&&z.downTarget; if(z) z.downTarget=null;
    if(z&&z.moved){ z.moved=false; return; }          // that was a pan, not a click
    const t=(pressed&&pressed.isConnected)?pressed:e.target;
    const g=t&&t.closest?t.closest('[data-el]'):null;
    if(!g||!view.contains(g)){ hideDgCard(); dgSelect(view, null); return; }
    dgSelect(view, g);
    showDgCard(view, g, e, inModal);
  });
}
// The id the parsed data knows this diagram element by. Usually data-el itself; CMMN DI references
// plan item ids while the parsed plan tree keys the *definitions* — there the element name bridges.
function dgEffectiveId(n, g){
  const em=elementNames(n), elId=g.dataset.el;
  if(em.has(String(elId))) return elId;
  const tip=g.getAttribute('data-tip')||'';
  const name=tip.indexOf(' — ')>=0?tip.slice(0,tip.indexOf(' — ')):tip;
  if(name){ for(const [k,v] of em){ if(v.name===name) return k; } }
  return elId;
}
function showDgCard(view, g, e, inModal){
  hideDgCard();
  const n=state.sel&&byId.get(state.sel);
  if(!n) return;
  const elId=dgEffectiveId(n, g);
  const card=document.createElement('div'); card.className='dgcard';
  card.setAttribute('role','dialog');
  card.setAttribute('aria-label','Element details — drag the header to move, drag the corner to resize');
  card.innerHTML=dgCardHtml(n, elId, g);
  // on <body>, not in the view: see placeDgCard. `_view` keeps the originating viewport reachable.
  card._view=view;
  document.body.appendChild(card);
  placeDgCard(view, card);
  wireDgCardMoveResize(view, card);
  _dgCard=card;
  // The card is injected after renderDetail's wiring pass, so wire its own affordances here.
  card.addEventListener('pointerdown',ev=>ev.stopPropagation());   // no pan from inside the card
  card.addEventListener('click',ev=>ev.stopPropagation());
  card.addEventListener('wheel',ev=>ev.stopPropagation());         // the card scrolls itself
  card.querySelector('.dgcard-x').onclick=()=>{ hideDgCard(); dgSelect(view, null); };
  const dj=card.querySelector('[data-dgdetails]');
  if(dj) dj.onclick=()=>{
    hideDgCard();
    if(inModal) closeDiagramModal();
    revealByEl(document.getElementById('detail'), dj.getAttribute('data-dgdetails')||elId);
  };
  card.querySelectorAll('.vlink,.nc').forEach(c=>{
    c.onclick=()=>{ hideDgCard(); if(inModal) closeDiagramModal(); select(dec(c.dataset.id)); };
  });
  card.querySelectorAll('.cpy').forEach(b=>{
    b.onclick=ev=>{ ev.stopPropagation();
      atlasCopy(dec(b.dataset.copy), ()=>{ b.classList.add('ok'); setTimeout(()=>b.classList.remove('ok'),1200); }); };
  });
}
// Callee chips for a service-task record — which model the task actually talks to.
function stCalleeChip(st){
  const ids=[st.serviceModelKey&&'service:'+st.serviceModelKey,
             st.dataObjectKey&&'dataObject:'+st.dataObjectKey,
             st.agentModelKey&&'agent:'+st.agentModelKey].filter(Boolean).filter(id=>byId.get(id));
  return ids.map(nodeChip).join('');
}
function dgCardHtml(n, elId, g){
  const d=n.data||{}, em=elementNames(n);
  const info=em.get(String(elId))||{};
  const tip=g.getAttribute('data-tip')||'';
  let name=info.name || (tip.indexOf(' — ')>=0?tip.slice(0,tip.indexOf(' — ')):tip);
  let tyHtml=info.type?elementTerm(info.type, info.sub||undefined):'';
  if(!tyHtml && tip.indexOf(' — ')>=0) tyHtml='<span class="term">'+esc(tip.slice(tip.indexOf(' — ')+3))+'</span>';
  const rows=[];
  const row=(k,v)=>{ if(v) rows.push('<div class="dgrow"><span class="k">'+esc(k)+'</span><span class="v">'+v+'</span></div>'); };
  const sameId=x=>String(x)===String(elId);
  // -- task-flavour facts, from whichever element list owns this id --
  const st=(d.serviceTasks||[]).find(t=>sameId(t.id));
  if(st){
    const impl=st.class||st.delegateExpression||st.expression||'';
    if(impl) row('impl','<span class="mono">'+esc(impl)+'</span> '+implLink(st));
    if(st.resultVariable) row('result','<span class="mono">'+paramSide(st.resultVariable)+'</span>');
    const callee=stCalleeChip(st); if(callee) row('calls', callee);
    if(st.operationKey) row('operation','<span class="mono">'+esc(st.operationKey)+'</span>');
    if(st.topic) row('topic', vlink('topic:'+st.topic, st.topic));
    if(st.caseDefinitionKey) row('starts case', vlink('case:'+st.caseDefinitionKey, st.caseDefinitionKey));
  }
  const ut=(d.userTasks||[]).find(t=>sameId(t.id));
  if(ut){
    if(ut.formKey) row('form', vlink('form:'+ut.formKey, ut.formKey));
    if(ut.assignee) row('assignee','<span class="mono">'+esc(ut.assignee)+'</span>');
    if(ut.candidateGroups) row('groups', groupLinksHtml(ut.candidateGroups));
  }
  const ca=(d.callActivities||[]).find(t=>sameId(t.id));
  if(ca&&ca.calledElement) row('calls', vlink('process:'+ca.calledElement, ca.calledElement));
  const rt=(d.ruleTasks||[]).find(t=>sameId(t.id));
  if(rt&&rt.decisionRef) row('decision', vlink('decision:'+rt.decisionRef, rt.decisionRef));
  const ev=(d.events||[]).find(x=>sameId(x.id))||(d.eventListeners||[]).find(x=>sameId(x.id));
  if(ev&&(ev.def||ev.timer)) row(ev.def||'timer','<span class="mono">'+esc(ev.value||ev.timer||'')+'</span>');
  const pi=n.type==='case'?planItemById(d, elId):null;
  if(pi){
    if(pi.formKey) row('form', vlink('form:'+pi.formKey, pi.formKey));
    if(pi.processRef) row('process', vlink('process:'+pi.processRef, pi.processRef));
    if(pi.caseRef) row('case', vlink('case:'+pi.caseRef, pi.caseRef));
    if(pi.decisionRef) row('decision', vlink('decision:'+pi.decisionRef, pi.decisionRef));
    if(pi.candidateGroups) row('groups', groupLinksHtml(pi.candidateGroups));
  }
  // CMMN criterion diamond: its sentry's condition + the plan item it guards
  const crit=n.type==='case'?caseCriteria(d).find(c=>sameId(c.id)):null;
  if(crit){
    row('guards', esc(elName(em, crit.planItemDef!=null?crit.planItemDef:(crit.planItem||''))));
    if(crit.condition) row('condition','<code class="mono" style="font-size:var(--text-xs)">'+esc(crit.condition)+'</code>');
    if(crit.onParts.length) row('on', esc(crit.onParts.join(', ')));
  }
  // a DMN DRD shape is a decision table of its own — link straight to its node
  if(n.type==='decision'&&byId.get('decision:'+elId)&&('decision:'+elId)!==n.id) row('model', nodeChip('decision:'+elId));
  const mi=(d.multiInstance||[]).find(m=>sameId(m.activity));
  if(mi) row('multi-instance',(mi.collection?'over <span class="mono">'+paramSide(mi.collection)+'</span>':'')+
    (mi.elementVariable?' as <span class="mono">'+paramSide(mi.elementVariable)+'</span>':'')+
    (mi.sequential==='true'?' · sequential':''));
  // -- parameters + the variables they touch --
  const ps=(d.ioParameters||[]).filter(p=>sameId(p.element));
  const vars=new Set();
  const addVar=x=>{ const r=String(x==null?'':x).replace(/^\$/,'').split('.')[0].split('[')[0];
    if(r&&byId.get('variable:'+r)) vars.add(r); };
  ps.forEach(p=>{ addVar(p.source); addVar(p.target); });
  [st&&st.resultVariable, mi&&mi.collection, mi&&mi.elementVariable].forEach(x=>{ if(x) addVar(x); });
  const sc=(d.scriptTasks||[]).find(t=>sameId(t.id))||(pi&&pi.script?pi:null);
  if(sc&&sc.resultVariable) addVar(sc.resultVariable);
  if(vars.size) row('variables', [...vars].map(v=>vlink('variable:'+v, v)).join(', '));
  let body='';
  if(ps.length){
    const shown=ps.slice(0,10);
    body+='<div class="dgsec">Parameters ('+ps.length+') · '+esc(paramSummary(ps))+'</div>'+
      '<div class="parmgrid">'+shown.map(paramRow).join('')+'</div>'+
      (ps.length>shown.length?'<div class="muted" style="font-size:var(--text-2xs);padding:2px 0">+ '+(ps.length-shown.length)+' more in the Parameters section</div>':'');
  }
  // -- flow conditions: the clicked flow's own, or every outgoing flow of the clicked element --
  const selfC=(d.conditions||[]).find(c=>sameId(c.id));
  const outC=(d.conditions||[]).filter(c=>sameId(c.from));
  if(selfC) body+='<div class="dgsec">Condition</div><div class="dgcond">'+
    '<span class="cflow">'+esc(elName(em,selfC.from))+' → '+esc(elName(em,selfC.to))+'</span>'+
    '<code>'+esc(selfC.condition||'')+'</code></div>';
  if(outC.length) body+='<div class="dgsec">Outgoing flow conditions ('+outC.length+')</div>'+
    outC.map(c=>'<div class="dgcond"><span class="cflow">→ '+esc(elName(em,c.to))+'</span>'+
      '<code>'+esc(c.condition||'')+'</code></div>').join('');
  // CMMN plan item: its entry/exit criteria with their sentry conditions
  if(pi){
    const cs=caseCriteria(d).filter(c=>c.planItemDef!=null&&sameId(c.planItemDef));
    if(cs.length) body+='<div class="dgsec">Entry / exit criteria ('+cs.length+')</div>'+
      cs.map(c=>'<div class="dgcond"><span class="cflow">'+(c.type==='entryCriterion'?'entry ◇':'exit ◆')+'</span>'+
        '<code>'+esc(c.condition||(c.onParts.length?'on '+c.onParts.join(', '):'—'))+'</code></div>').join('');
  }
  // -- script preview --
  if(sc&&sc.script){
    const lines=String(sc.script).split('\n');
    body+='<div class="dgsec">Script'+(sc.format||sc.scriptFormat?' ('+esc(sc.format||sc.scriptFormat)+')':'')+'</div>'+
      '<pre class="scriptbox" style="margin:2px 0">'+esc(lines.slice(0,5).join('\n'))+(lines.length>5?'\n…':'')+'</pre>';
  }
  // -- what the modeller wrote about this element, and the listeners it runs --
  const rec=elementRecords(n).find(r=>sameId(r.id));
  if(rec&&rec.documentation) body+='<div class="dgsec">Documentation</div>'+
    '<div style="font-size:var(--text-xs)">'+esc(rec.documentation)+'</div>';
  const recLs=((rec&&rec.listeners)||[]).filter(l=>l.class||l.expression||l.delegateExpression||l.script);
  if(recLs.length) body+='<div class="dgsec">Listeners ('+recLs.length+')</div>'+
    recLs.map(l=>'<div class="dgcond"><span class="cflow">'+
      esc([term('el', l.kind).label, l.event].filter(Boolean).join(' · '))+'</span>'+
      '<code>'+esc(l.class||l.expression||l.delegateExpression||'(script)')+'</code></div>').join('');
  const det=document.getElementById('detail');
  // a criterion has no detail row of its own — its "details" are the guarded plan item's row
  const revealId=crit&&crit.planItemDef!=null?String(crit.planItemDef):String(elId);
  const hasRows=det&&[...det.querySelectorAll('[data-el]')].some(x=>x.dataset.el===revealId&&!x.closest('.dgview'));
  return '<div class="dgcard-head"><span class="dgcard-title">'+esc(name||elId)+'</span>'+
    (tyHtml?'<span class="dgcard-ty">'+tyHtml+'</span>':'')+
    '<button class="dgcard-x" aria-label="Close">×</button></div>'+
    '<div class="dgcard-id mono">'+esc(elId)+copyBtn(elId,'element id')+'</div>'+
    rows.join('')+body+
    (hasRows?'<div class="dgcard-foot"><button class="dgbtn" data-dgdetails="'+esc(revealId)+'">Show in details ↓</button></div>':'');
}

// A search hit lands on the node, not on the row that matched — so find the matching rows, open every
// collapsed ancestor, mark them all and scroll the first one into view. When the hit carried the
// element it came from (a script task, a flow condition), that element's rows win: they are the exact
// place, not a text guess.
function applyFocus(det){
  if(state.focusEl && revealByEl(det, state.focusEl)) return;
  const q=(state.focus||'').trim().toLowerCase();
  if(!q) return;
  const match=el=>(el.dataset.hay||el.textContent||'').toLowerCase().indexOf(q)>=0;
  let rows=[...det.querySelectorAll('.pc, .oprow')].filter(match);
  // script bodies / operation blocks, flow conditions and DMN cells — a free-text hit usually lands here
  if(!rows.length) rows=[...det.querySelectorAll('details.op, .dgcond, .dmntab td')].filter(match);
  if(!rows.length) rows=[...det.querySelectorAll('.cell')].filter(match);
  if(!rows.length) return;
  rows.forEach(el=>{ el.classList.add('hit'); if(el.tagName==='DETAILS') el.open=true; });
  for(let p=rows[0].parentElement; p && p!==det; p=p.parentElement){
    if(p.tagName==='DETAILS') p.open=true;
  }
  rows[0].classList.add('flash');
  // the panel was just replaced; let layout settle before scrolling
  requestAnimationFrame(()=>rows[0].scrollIntoView({block:'center'}));
  setTimeout(()=>det.querySelectorAll('.flash').forEach(e=>e.classList.remove('flash')), 1600);
}

// Navigation: select() only moves the URL hash; the hashchange listener routes. That makes
// the hash the single source of truth — browser back/forward, bookmarks and copied links all
// go through the same path.
// `q` is the search term that led here and `el` the model element the hit came from (a script task, a
// flow condition …) — both ride along in the hash so Back/Forward and "copy link" reproduce the
// highlight without any extra plumbing.
function select(id, q, el){
  if(!byId.get(id)) return;
  const hash=encodeURIComponent(id)+(q?'&q='+encodeURIComponent(q):'')+
             (el?'&e='+encodeURIComponent(el):'');
  if(location.hash.slice(1)===hash){ state.focus=q||''; state.focusEl=el||''; applySelection(id); return; }
  location.hash=hash;
}

function applySelection(id){
  if(!byId.get(id)) return;
  state.view='browse'; showView('browse');
  state.sel=id;
  pushRecent(id);
  const n=byId.get(id);
  // Keep the current category if it already contains this node (so clicking within
  // e.g. "Java · delegate" stays there) — only re-sync when it doesn't match.
  const cur=CATS.find(c=>c.id===state.cat);
  let catChanged=false;
  if(!cur || !cur.match(n)){
    let cat;
    if(n.type==='java'){
      const prio=['controller','delegate','listener','bot','service','repository','configuration','component','other'];
      const r=(n.data.roles||[]).slice().sort((a,b)=>prio.indexOf(a)-prio.indexOf(b))[0];
      cat=CATS.find(c=>c.id==='java::'+r);
    } else if(n.type==='variable'){
      cat=CATS.find(c=>c.id==='variable::'+(n.data.scopes||[])[0]);
    }
    cat=cat||CATS.find(c=>c.id===n.type);
    if(cat && cat.id!==state.cat){ state.cat=cat.id; catChanged=true; }
  }
  if(catChanged || !document.getElementById('listitems')) renderList();
  syncListSelection();
  renderDetail();
  renderSidebarActive(); renderCrumbs();
}

// ---------- search index (shared by the command palette) ----------
// Three haystacks per node, because "find everything" and "rank sensibly" pull in opposite directions:
//   ident  — label / key / file / type (+ the bot key: a Java bot's getKey() and an action's botKey
//            field both live in data.botKey, so ⌘K finds the bot class AND its callers).
//   member — names of things that are NOT nodes of their own (element ids, in/out parameters, form
//            fields, columns, permissions …). Enumerated on purpose: their shape carries meaning.
//   text   — every other string in node.data, collected by a generic deep walk WITH provenance. This
//            is what makes a script body, an element's documentation, a flow condition or a field
//            injection findable at all. Enumerating those field by field kept losing the race with the
//            parsers — a walk cannot fall behind.
const HAY_SKIP=new Set([
  // `diagram` is the rendered SVG (a wall of path data) — indexing it would make every query match
  // every model. The rest is node-id bookkeeping the palette already navigates by.
  'diagram','_uses','usedBy','usages','scopes','_idx','_search',
]);
const HAY_MAX_VALUE=4000, HAY_MAX_ENTRIES=400;
// Field name → what to call it in the "why did this match" hint.
const HAY_LABEL={
  script:'script', documentation:'doc', condition:'condition', conditions:'condition',
  delegateExpression:'delegate', expression:'expression', class:'class', formKey:'form',
  candidateGroups:'groups', candidateUsers:'users', assignee:'assignee', resultVariable:'result var',
  inputs:'DMN input', outputs:'DMN output', rules:'DMN rule', inputExpressions:'DMN input',
  annotation:'DMN annotation', fields:'field', topic:'topic', url:'url', tableName:'table',
};
/**
 * Deep-walk a value, pushing one `{k, id, v}` entry per string found: `k` is the field it came from,
 * `id` the nearest enclosing object's id/name/key — for a script body that is the script task's
 * element id, which is what lets a hit jump straight to the right row.
 * Strings only: numbers and booleans ("true", counts) match everything and mean nothing here.
 */
function walkHay(v, key, owner, out){
  if(out.length>=HAY_MAX_ENTRIES || v==null) return;
  if(typeof v==='string'){
    if(v) out.push({k:key, id:owner, v:v.length>HAY_MAX_VALUE?v.slice(0,HAY_MAX_VALUE):v});
    return;
  }
  if(Array.isArray(v)){ v.forEach(x=>walkHay(x, key, owner, out)); return; }
  if(typeof v!=='object') return;
  const own=v.id||v.name||v.key||owner;        // the element this sub-object belongs to
  for(const k in v){ if(!HAY_SKIP.has(k)) walkHay(v[k], k, own, out); }
}
function searchIndex(n){
  if(n._idx) return n._idx;                    // node data never changes at runtime — build once
  const d=n.data||{};
  const ident=(n.label+' '+n.key+' '+(n.file||'')+' '+n.type+' '+(d.botKey||'')).toLowerCase();
  let s='';
  // model element ids + names (tasks, gateways, events, plan items) — an element id from the BPMN
  // XML or the diagram surfaces its model in ⌘K
  if(n.type==='process'||n.type==='case')
    s+=' '+[...elementNames(n).entries()].map(([id,e])=>id+' '+(e.name||'')).join(' ');
  if(n.type==='dataObject') s+=' '+(d.fields||[]).join(' ')+' '+(d.serviceTableName||'')+' '+
    (d.columns||[]).map(c=>(c.label||'')+' '+(c.type||'')).join(' ');
  if(n.type==='service') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.columnName||'')+' '+(c.type||'')).join(' ');
  if(n.type==='liquibase') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.type||'')).join(' ');
  // In/out parameters are not nodes of their own, so without this a parameter name would never surface
  // the process/case/action that passes it. Both sides of a mapping match — the caller's variable AND
  // the callee's contract name (a service parameter, an event payload field).
  if((d.ioParameters||[]).length) s+=' '+d.ioParameters.map(paramHaystack).join(' ');
  if(n.type==='variable') s+=' '+(d.ioParams||[]).map(paramHaystack).join(' ');
  if(n.type==='service') s+=' '+(d.operations||[]).map(o=>
    (o.params||[]).concat(o.outParams||[]).map(p=>p.name||'').join(' ')).join(' ');
  if(n.type==='serviceOperation') s+=' '+(d.params||[]).concat(d.outParams||[])
    .map(p=>(p.name||'')+' '+(p.type||'')).join(' ');
  // form/page fields, app variables, agent tools, policy permissions and dictionary types are not
  // nodes of their own — index them here so their names surface the model that declares them.
  if(n.type==='form'||n.type==='page') s+=' '+(d.fields||[]).map(f=>(f.id||'')+' '+(f.label||'')).join(' ');
  if(n.type==='app') s+=' '+(d.variables||[]).map(v=>v.key||'').join(' ');
  if(n.type==='agent') s+=' '+(d.tools||[]).map(t=>t.key||'').join(' ');
  if(n.type==='securityPolicy') s+=' '+(d.permissions||[]).map(p=>(p.key||'')+' '+(p.label||'')+' '+(p.roles||[]).join(' ')).join(' ');
  if(n.type==='dataDictionary') s+=' '+(d.types||[]).join(' ');
  const entries=[];
  for(const k in d){ if(!HAY_SKIP.has(k)) walkHay(d[k], k, null, entries); }
  n._idx={ident, mem:s.toLowerCase(), text:entries.map(e=>e.v).join('\n').toLowerCase(), entries};
  return n._idx;
}
// How well does this node match? 0 = label/key starts with the term, 1 = anywhere in the identity,
// 2 = a member name, 3 = free text (script, documentation, condition …), -1 = not at all. Without the
// tiers the free-text hits would drown the node you actually typed the name of.
function matchTier(n,v){
  const ix=searchIndex(n);
  const idn=(n.label+' '+n.key).toLowerCase();
  if((' '+idn).indexOf(' '+v)>=0) return 0;
  if(ix.ident.indexOf(v)>=0) return 1;
  if(ix.mem.indexOf(v)>=0) return 2;
  if(ix.text.indexOf(v)>=0) return 3;
  return -1;
}
function paramHaystack(p){ return (p.source||'')+' '+(p.target||'')+' '+(p.element||'')+' '+(p.kind||''); }
// Why did this node match, and where? When the hit did not come from the node's own name, the palette
// shows the mapping / field it came from instead of the key — otherwise the match looks arbitrary —
// and `el` carries the element id so the detail panel can open exactly that row.
function matchWhere(n,v){
  if(!v || (n.label+' '+n.key).toLowerCase().indexOf(v)>=0) return null;
  const p=((n.data||{}).ioParameters||[]).find(x=>paramHaystack(x).toLowerCase().indexOf(v)>=0);
  if(p){
    const flow=[p.source,p.target].filter(x=>x!=null&&x!=='').join(' → ');
    return {hint:p.dir+' '+flow+(p.element?' @'+p.element:''), el:p.element||''};
  }
  const e=searchIndex(n).entries.find(x=>x.v.toLowerCase().indexOf(v)>=0);
  if(!e) return null;
  return {hint:(HAY_LABEL[e.k]||e.k)+(e.id?' · '+e.id:''), el:e.id||''};
}

// ---------- command palette (⌘K) ----------
const pal=document.getElementById('palette'), palq=document.getElementById('palq'), palres=document.getElementById('palresults');
let palList=[], palSel=-1, _palPrevFocus=null;
function openPalette(){
  if(!pal.hidden) return;
  hideDgCard();                                  // the card floats above the palette (z-index 120 > 100)
  _palPrevFocus=document.activeElement;
  pal.hidden=false; palq.value=''; palSel=-1;
  palRender(); palq.focus();
}
function closePalette(){
  if(pal.hidden) return;
  pal.hidden=true;
  try{ if(_palPrevFocus && document.contains(_palPrevFocus)) _palPrevFocus.focus(); }catch(e){}
  _palPrevFocus=null;
}
function getRecents(){
  try{ return (JSON.parse(localStorage.getItem('atlas-recent')||'[]')||[]).filter(id=>byId.get(id)); }
  catch(e){ return []; }
}
function pushRecent(id){
  try{
    const r=getRecents().filter(x=>x!==id); r.unshift(id);
    localStorage.setItem('atlas-recent', JSON.stringify(r.slice(0,8)));
  }catch(e){}
}
const PAL_LIMIT=60;
function palRender(){
  const v=palq.value.trim().toLowerCase();
  let groups=[], dropped=0;
  if(!v){
    const rec=getRecents().map(id=>byId.get(id));
    if(rec.length) groups=[{label:'Recent', items:rec.map(n=>({n}))}];
  } else {
    const scored=[];
    nodes.forEach(n=>{ const t=matchTier(n,v); if(t>=0) scored.push({n, t}); });
    scored.sort((a,b)=>a.t-b.t || a.n.label.length-b.n.label.length ||
                       (a.n.label<b.n.label?-1:a.n.label>b.n.label?1:0));
    dropped=Math.max(0, scored.length-PAL_LIMIT);
    const bySec={};
    scored.slice(0,PAL_LIMIT).forEach(hit=>{
      const n=hit.n;
      const sec = n.type==='external' ? (n.data&&n.data.flowableApi?'Integration':'Other')
                                      : (TM[n.type]?TM[n.type][1]:'Other');
      (bySec[sec]=bySec[sec]||[]).push(hit);
    });
    // Sections keep their familiar order, except that the one holding the best match comes first —
    // otherwise an exact name match sinks below a section of free-text hits that happens to sort earlier.
    const best=s=>Math.min.apply(null, bySec[s].map(x=>x.t));
    SECTIONS.filter(s=>bySec[s]).sort((a,b)=>best(a)-best(b)||SECTIONS.indexOf(a)-SECTIONS.indexOf(b))
      .forEach(s=>groups.push({label:s, items:bySec[s]}));
  }
  palList=[]; let h='';
  groups.forEach(g=>{
    h+='<div class="pal-group">'+esc(g.label)+'</div>';
    g.items.forEach(hit=>{
      const n=hit.n, i=palList.length;
      // Free-text hits (tier 3) explain themselves: "script · scriptTask1", "doc · orderProcess".
      const w=v?matchWhere(n,v):null;
      palList.push({n, el:(w&&w.el)||''});
      h+='<div class="pal-item'+(i===palSel?' sel':'')+'" id="pal-'+i+'" role="option" aria-selected="'+(i===palSel)+'" data-i="'+i+'">'+
         '<span class="dot" style="background:'+nodeColor(n)+'"></span>'+
         '<span class="nm">'+esc(n.label)+'</span><span class="hint">'+esc((w&&w.hint)||n.key)+'</span></div>';
    });
  });
  if(!h) h='<div class="pal-empty">'+(v?'No matches':'Nothing recent yet — visit a few nodes and they will show up here')+'</div>';
  else if(dropped) h+='<div class="pal-more">+'+dropped+' more — keep typing to narrow it down</div>';
  palres.innerHTML=h;
  if(palSel>=0){
    palq.setAttribute('aria-activedescendant','pal-'+palSel);
    const el=document.getElementById('pal-'+palSel); if(el) el.scrollIntoView({block:'nearest'});
  } else palq.removeAttribute('aria-activedescendant');
  palres.querySelectorAll('.pal-item').forEach(el=>el.onclick=()=>{
    const hit=palList[+el.dataset.i]; closePalette(); select(hit.n.id, v, hit.el);
  });
}
palq.addEventListener('input', debounce(()=>{ palSel=-1; palRender(); },120));
palq.addEventListener('keydown',e=>{
  if(e.key==='ArrowDown'){ e.preventDefault(); palSel=Math.min(palSel+1,palList.length-1); palRender(); }
  else if(e.key==='ArrowUp'){ e.preventDefault(); palSel=Math.max(palSel-1,0); palRender(); }
  else if(e.key==='Enter' && palList[palSel]){
    const hit=palList[palSel]; closePalette(); select(hit.n.id, palq.value.trim(), hit.el);
  }
  else if(e.key==='Escape'){ closePalette(); }
  else if(e.key==='Tab'){ e.preventDefault(); }            // the input is the only tabbable — trap
});
pal.addEventListener('mousedown',e=>{ if(e.target.closest('[data-close]')) closePalette(); });
document.addEventListener('keydown',e=>{
  if((e.metaKey||e.ctrlKey) && (e.key==='k'||e.key==='K')){
    e.preventDefault(); pal.hidden?openPalette():closePalette();
  } else if(e.key==='/' && pal.hidden && !e.target.closest('input,textarea,select,[contenteditable]')){
    e.preventDefault(); openPalette();                     // guarded: '/' typed in a filter stays there
  } else if(e.key==='Escape' && !pal.hidden){
    closePalette();
  }
});
function wireSearchTrigger(){
  document.getElementById('searchbtn').onclick=openPalette;
  document.getElementById('searchkbd').textContent = IS_MAC?'⌘K':'Ctrl K';
  // Reload the page — recovery for the occasional hung/stale explorer. The page is loaded from a
  // file:// URL both in a browser and in the JCEF IDE tab, so a plain reload re-reads it cleanly.
  const rb=document.getElementById('reloadbtn');
  if(rb) rb.onclick=()=>location.reload();
}

// ---------- uncertain-links toggle (suspect ≈ / dynamic ƒ edges) ----------
function wireLinkFilter(){
  const b=document.getElementById('linkfilter');
  const st=DATA.stats||{}, su=st.suspectEdges||0, dy=st.dynamicEdges||0;
  if(!b || !(su+dy)) return;              // nothing flagged — keep the button hidden
  b.hidden=false;
  const paint=()=>{
    b.classList.toggle('off', hideUncertain);
    b.setAttribute('aria-pressed', hideUncertain?'true':'false');
    const tip=(hideUncertain?'Uncertain links hidden':'Uncertain links shown')+' — '+
      su+' suspect (≈ loose/cross-type match), '+dy+' dynamic (ƒ expression-valued). Click to toggle.';
    b.setAttribute('data-tip', tip); b.setAttribute('aria-label', tip);   // data-tip drives the hover bubble
  };
  paint();
  b.onclick=()=>{
    hideUncertain=!hideUncertain;
    try{ localStorage.setItem('atlas-uncertain', hideUncertain?'hide':'show'); }catch(e){}
    rebuildAdj(); paint();
    if(state.view==='browse') renderDetail();
  };
}

// ---------- utils ----------
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function enc(s){ return encodeURIComponent(s); }
function dec(s){ return decodeURIComponent(s); }

// ---------- copy ----------
// feather "copy" (two overlapping rounded rects) + a check for the success flash.
const CPY_SVG='<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const CPY_OK_SVG='<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';
// A copy-to-clipboard icon button; the payload rides in data-copy (URI-encoded), wired by the
// delegated handler in renderDetail. `what` names the thing in the tooltip ("Copy key", …).
function copyBtn(text,what){
  if(text==null||text==='') return '';
  const lbl='Copy'+(what?' '+what:'');
  return '<button type="button" class="cpy" data-copy="'+enc(String(text))+'" title="'+esc(lbl)+'" aria-label="'+esc(lbl)+'">'+CPY_SVG+'</button>';
}
// Single copy path for every affordance. Order: IDE bridge → clipboard API → execCommand → prompt.
// onOk fires only on genuine success, so the UI never shows a false "✓ copied" (the embedded JCEF
// file:// viewer blocks navigator.clipboard — window.__atlasCopy is injected there by the IDE host).
function atlasCopy(text,onOk){
  text=String(text==null?'':text);
  const ok=()=>{ if(onOk) onOk(); };
  if(window.__atlasCopy){ try{ window.__atlasCopy(text); ok(); return; }catch(e){} }
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(text).then(ok,()=>{ if(execCopy(text)) ok(); else prompt('Copy:',text); });
    return;
  }
  if(execCopy(text)){ ok(); return; }
  prompt('Copy:',text);
}
function execCopy(text){
  try{
    const ta=document.createElement('textarea'); ta.value=text; ta.setAttribute('readonly','');
    ta.style.position='fixed'; ta.style.top='0'; ta.style.left='0'; ta.style.opacity='0';
    document.body.appendChild(ta); ta.focus(); ta.select();
    const done=document.execCommand('copy'); document.body.removeChild(ta); return done;
  }catch(e){ return false; }
}

// ---------- theme ----------
// Preference cycle: light → dark → auto (follow the OS). Light is the default — it is the
// Flowable Hub look. JS always resolves the effective theme onto <html data-theme=…>, so the
// CSS needs only one dark-override block; because all node colors are emitted as var()
// references, a switch restyles without re-rendering.
//
// IDE embedding contract: when the page runs inside the IntelliJ JCEF viewer, the IDE seeds
// ?ideTheme=light|dark on the URL and pushes live theme switches via window.__atlasSetIdeTheme.
// The IDE theme is the resolution source for the 'auto' preference (never a hard lock): embedded,
// the default preference becomes 'auto' so the page follows the IDE out of the box, while an
// explicit light/dark from the in-page toggle still wins; cycling back to auto resumes following.
// In a plain browser (no param, no push) the behavior is unchanged.
window.__ideTheme=(()=>{ try{
  const t=new URLSearchParams(location.search).get('ideTheme');
  return (t==='light'||t==='dark')?t:null;
}catch(e){ return null; } })();
window.__atlasSetIdeTheme=t=>{
  window.__ideTheme=(t==='light'||t==='dark')?t:null;
  applyThemePref();
};
function themePref(){ let p=null; try{ p=localStorage.getItem('atlas-theme'); }catch(e){} return p||(window.__ideTheme?'auto':'light'); }
function applyThemePref(){
  const pref=themePref();
  const sys=window.__ideTheme||(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark');
  const theme = pref==='auto'?sys:pref;
  document.documentElement.dataset.theme = theme;
  const mt=document.querySelector('meta[name=theme-color]');
  if(mt) mt.content = theme==='dark'?'#0c141c':'#ffffff';
  document.querySelectorAll('[data-theme-btn]').forEach(b=>{
    b.textContent = pref==='auto'?'◐':(pref==='light'?'☀':'☾');
    const tip='Theme: '+pref+(pref==='auto'&&window.__ideTheme?' (follows IDE)':'')+' — click to switch';
    b.setAttribute('data-tip', tip); b.setAttribute('aria-label', tip);   // data-tip drives the hover bubble
  });
}
function cycleTheme(){
  const next={light:'dark', dark:'auto', auto:'light'}[themePref()];
  try{ localStorage.setItem('atlas-theme', next); }catch(e){}   // private mode / file:// quirks
  applyThemePref();
}
document.querySelectorAll('[data-theme-btn]').forEach(b=>b.onclick=cycleTheme);
matchMedia('(prefers-color-scheme: light)').addEventListener('change',applyThemePref);
applyThemePref();

// ---------- hover tooltips ----------
// A DOM bubble for elements carrying [data-tip]. Native title= tooltips don't render in the embedded
// JCEF viewer (off-screen rendering, especially over Remote Dev), so we draw our own — it shows
// identically in the IDE and a plain browser. Every title= in the page (term hints, copy buttons,
// badge explanations) is lifted into data-tip on first hover/focus: one code path serves them all,
// and nothing depends on the native tooltip the IDE never shows. Reads the attribute at hover time,
// so the dynamic link-filter / theme text is always current.
const _tip=document.createElement('div'); _tip.className='atlas-tip'; _tip.setAttribute('role','tooltip');
let _tipFor=null;
// Move a native title= into data-tip (once): the browser stops racing us with its own tooltip and
// the text keeps working where native tooltips don't. The text stays reachable for screen readers.
function liftTitle(el){
  if(el.hasAttribute('data-tip')) return el;
  const t=el.getAttribute('title'); if(!t) return el;
  el.setAttribute('data-tip', t); el.removeAttribute('title');
  if(!el.hasAttribute('aria-label')) el.setAttribute('aria-label', t);
  return el;
}
function tipTarget(t){ return t && t.closest ? t.closest('[data-tip],[title]') : null; }
function showTip(el){
  const t=el.getAttribute('data-tip'); if(!t){ hideTip(); return; }
  _tipFor=el; _tip.textContent=t;
  if(!_tip.parentNode) document.body.appendChild(_tip);
  const r=el.getBoundingClientRect(), tr=_tip.getBoundingClientRect();
  const left=Math.max(8, Math.min(r.left, window.innerWidth-tr.width-8));   // right-align onto screen
  let top=r.bottom+6;
  if(top+tr.height>window.innerHeight-8) top=r.top-tr.height-6;             // flip above if no room below
  _tip.style.left=left+'px'; _tip.style.top=Math.max(8,top)+'px';
  requestAnimationFrame(()=>_tip.classList.add('show'));
}
function hideTip(){
  clearTimeout(_tipT); _tipT=null;
  _tipFor=null; _tip.classList.remove('show'); if(_tip.parentNode) _tip.parentNode.removeChild(_tip);
}
// Hover tooltips wait — a bubble that appears the instant the cursor passes over something turns every
// mouse movement across a list into a flicker. Keyboard focus shows it immediately: there the tooltip
// is the answer to a deliberate question.
const TIP_DELAY=450;
let _tipT=null;
document.addEventListener('mouseover',e=>{
  let el=tipTarget(e.target);
  if(!el){ if(_tipFor||_tipT) hideTip(); return; }
  if(el===_tipFor) return;
  clearTimeout(_tipT);
  _tipT=setTimeout(()=>{ _tipT=null; showTip(liftTitle(el)); }, TIP_DELAY);
});
document.addEventListener('mouseout',e=>{
  const el=tipTarget(e.target);
  if(_tipT && el && !el.contains(e.relatedTarget)){ clearTimeout(_tipT); _tipT=null; }
  if(_tipFor && el===_tipFor && !_tipFor.contains(e.relatedTarget)) hideTip();
});
document.addEventListener('focusin',e=>{ let el=tipTarget(e.target); if(el){ el=liftTitle(el); showTip(el); } else if(_tipFor) hideTip(); });
window.addEventListener('scroll',()=>{ if(_tipFor||_tipT) hideTip(); }, true);

// ---------- sidebar resize (IntelliJ-style drag handle) ----------
// The expanded width lives in the --sidebar-w custom property; the collapsed
// "rail" is the .shell.rail class. Both are user-controllable via the drag
// handle (#sideresize) and remembered. atlas-sidebar='rail'|'wide' records an
// explicit choice; with none stored the rail auto-engages below 1100px, which
// preserves the old media-query behavior. localStorage is wrapped in try/catch
// for private-mode / file:// quirks, matching the theme prefs above.
const SB_MIN=180, SB_MAX=480, SB_DEF=240, SB_COLLAPSE=140, SB_RAIL=64;
const _sbNarrow=matchMedia('(max-width:1100px)');
function sbPref(){ try{ return localStorage.getItem('atlas-sidebar'); }catch(e){ return null; } }
function sbWidth(){
  let w=NaN; try{ w=parseInt(localStorage.getItem('atlas-sidebar-w'),10); }catch(e){}
  return (w>=SB_MIN&&w<=SB_MAX)?w:SB_DEF;
}
function sbClamp(v){ return Math.max(SB_MIN,Math.min(SB_MAX,v)); }
function applySidebar(){
  const shell=document.querySelector('.shell'); if(!shell) return;
  const pref=sbPref();                              // 'rail' | 'wide' | null(auto)
  const rail = pref ? pref==='rail' : _sbNarrow.matches;
  const w=sbWidth();
  shell.style.setProperty('--sidebar-w', w+'px');
  shell.classList.toggle('rail', rail);
  const h=document.getElementById('sideresize');
  if(h){
    h.setAttribute('aria-valuenow', rail?'0':String(w));
    h.setAttribute('aria-label', rail?'Sidebar collapsed — drag to expand'
                                      :'Sidebar width '+w+'px — drag to resize');
  }
}
function setSidebar(state, w){                       // persist an explicit choice, then re-apply
  try{ localStorage.setItem('atlas-sidebar', state); }catch(e){}
  if(w!=null){ try{ localStorage.setItem('atlas-sidebar-w', String(w)); }catch(e){} }
  applySidebar();
}
function wireSidebarResize(){
  const shell=document.querySelector('.shell');
  const h=document.getElementById('sideresize');
  if(!shell||!h) return;
  let startX=0, startW=0, dragging=false;
  h.addEventListener('pointerdown',e=>{
    dragging=true; startX=e.clientX;
    startW=shell.classList.contains('rail')?SB_RAIL:sbWidth();
    try{ h.setPointerCapture(e.pointerId); }catch(_){}
    shell.classList.add('dragging'); e.preventDefault();
  });
  h.addEventListener('pointermove',e=>{
    if(!dragging) return;
    const raw=startW+(e.clientX-startX);
    if(raw<SB_COLLAPSE){ shell.classList.add('rail'); }
    else{ shell.classList.remove('rail'); shell.style.setProperty('--sidebar-w', sbClamp(raw)+'px'); }
  });
  const end=e=>{
    if(!dragging) return; dragging=false;
    shell.classList.remove('dragging');
    try{ h.releasePointerCapture(e.pointerId); }catch(_){}
    if(shell.classList.contains('rail')) setSidebar('rail');
    else setSidebar('wide', parseInt(shell.style.getPropertyValue('--sidebar-w'),10)||SB_DEF);
  };
  h.addEventListener('pointerup',end);
  h.addEventListener('pointercancel',end);
  h.addEventListener('dblclick',()=>setSidebar('wide',SB_DEF));   // reset to default width
  h.addEventListener('keydown',e=>{
    if(e.key==='ArrowLeft'||e.key==='ArrowRight'){
      e.preventDefault();
      const base=shell.classList.contains('rail')?SB_MIN:sbWidth();
      setSidebar('wide', sbClamp(base+(e.key==='ArrowRight'?16:-16)));
    } else if(e.key==='Home'){ e.preventDefault(); setSidebar('wide',SB_DEF); }
  });
  // Re-evaluate the auto default on viewport crossings, but only while the
  // user has not made an explicit choice.
  _sbNarrow.addEventListener('change',()=>{ if(!sbPref()) applySidebar(); });
}

// In rail mode the collapsed sidebar flies out on :hover/:focus-within. A mouse
// click on a nav item (or a footer button) leaves that element focused, so
// :focus-within stays true and the rail never collapses when the pointer leaves.
// Drop the focus after pointer-initiated clicks so the fly-out closes on mouse-out.
// Keyboard activation reports detail:0 (Enter/Space synthesize el.click()) and is
// left alone, so Tab users keep the fly-out until they move focus away themselves.
function wireRailAutoCollapse(){
  const shell=document.querySelector('.shell');
  const sidebar=document.getElementById('sidebar');
  if(!shell||!sidebar) return;
  sidebar.addEventListener('click',e=>{
    if(e.detail===0) return;                                    // keyboard-synthesized click
    if(!shell.classList.contains('rail')) return;               // only the collapsed rail flies out
    const a=document.activeElement;
    if(a&&a!==document.body&&sidebar.contains(a)) a.blur();      // release :focus-within → collapse on mouse-out
  });
}

// ---------- boot ----------
document.getElementById('proj').textContent=DATA.project;
computeInsights();
renderSidebar();
applySidebar();
wireSidebarResize();
wireRailAutoCollapse();
wireSearchTrigger();
wireLinkFilter();
window.addEventListener('hashchange',route);
route();

// ---------- boot done: dismiss the loading overlay ----------
// The overlay (explorer.html #atlas-boot) covered the file read + this synchronous boot;
// fade it out now that the initial view is rendered, then remove it after the transition.
const _boot=document.getElementById('atlas-boot');
if(_boot){ _boot.classList.add('boot--done'); setTimeout(()=>_boot.remove(),400); }
