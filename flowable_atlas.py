#!/usr/bin/env python3
"""
flowable_project_overview.py
============================

Generate an LLM-friendly "App Overview" for ANY Flowable project.

It walks a project directory, discovers BOTH the Flowable app models (loose
.bpmn/.cmmn/.dmn/.form/.app/.agent/.service/... files *and* exported .zip/.bar
archives) AND the Java source code, then writes one Markdown file that wires the
two worlds together:

    "process X calls ${scoringDelegate}"  ->  "bean scoringDelegate is
                                              com.acme.ScoringDelegate (Foo.java:42)"
    "form Y calls REST /customers"        ->  "served by CustomerController#list
                                              (CustomerController.java:31)"
    "agent Z uses tool 'creditService'"   ->  "service model creditService.service"

Every model reference (called process/case/decision/form, delegateExpression
bean, JavaDelegate class, service-registry mapping, agent tool, REST url, ...) is
resolved against the project; whatever cannot be resolved is listed under
"Unresolved references" (likely a library bean or an external system).

NO external dependencies — pure Python 3 standard library.

Usage
-----
    python3 flowable_project_overview.py /path/to/project
    python3 flowable_project_overview.py /path/to/project -o OVERVIEW.md
    python3 flowable_project_overview.py /path/to/app.zip          # single archive
    python3 flowable_project_overview.py /path/to/project --json -o overview.json
    python3 flowable_project_overview.py /path/to/project --stdout # print, don't write
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import html
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

log = logging.getLogger("flowable_atlas")

# ---------------------------------------------------------------------------
# Discovery configuration
# ---------------------------------------------------------------------------
EXT_TO_TYPE = {
    ".app": "app", ".bpmn": "bpmn", ".bpmn20.xml": "bpmn", ".cmmn": "cmmn",
    ".cmmn.xml": "cmmn", ".dmn": "dmn", ".dmn.xml": "dmn", ".form": "form",
    ".page": "page", ".data": "dataObject", ".dictionary": "dataDictionary",
    ".query": "query", ".sequence": "sequence", ".sla": "sla", ".event": "event",
    ".channel": "channel", ".agent": "agent", ".service": "service",
    ".action": "action", ".tpl": "template", ".policy": "securityPolicy",
    ".extractor": "variableExtractor", ".knowledgebase": "knowledgeBase",
    ".palette": "palette", ".masterdata": "masterData",
    ".dashboardcomponent": "dashboardComponent", ".document": "document",
}  # keys lowercase; model_type_for matches case-insensitively
ARCHIVE_EXTS = (".zip", ".bar")
EXCLUDE_DIRS = {
    "target", "build", "node_modules", ".git", ".idea", ".gradle", "dist",
    "out", "bin", ".mvn", "coverage", "__pycache__", ".vscode", "test-classes",
}

EXPR_RE = re.compile(r"[#$]\{[^}]*\}")
MUSTACHE_RE = re.compile(r"\{\{[^}]*\}\}")
METHOD_CALL_RE = re.compile(r"(?<![\w.$])([A-Za-z_][\w]*)\s*\.\s*[A-Za-z_][\w]*\s*\(")
METHOD_CALL_FULL_RE = re.compile(r"(?<![\w.$])([A-Za-z_][\w]*)\s*\.\s*([A-Za-z_][\w]*)\s*\(")
STR_LIT_RE = re.compile(r"'([^']*)'|\"([^\"]*)\"")   # string literals inside expressions/bindings
ROOT_IDENT_RE = re.compile(r"(?<![\w.$])([A-Za-z_][\w]*)")
# Variable names referenced from a script body — action `config.scriptInfo.script`,
# BPMN/CMMN scriptTask <script> bodies, and script listeners. Two high-signal,
# quote-style-agnostic idioms: the Flowable API (flw.getInput/setOutput/
# setTransientOutput(..)) and the legacy execution/*Variable(..) calls. Bare
# identifiers are intentionally NOT harvested here (locals / loop vars / keywords
# make them too noisy) — that is a separate, opt-in pass.
SCRIPT_VAR_RE = re.compile(
    r"\b(?:(?:get|set)(?:Transient)?(?:Input|Output)"
    r"|(?:set|get|has|remove)(?:Transient)?Variable(?:Local)?)"
    r"\s*\(\s*['\"]([A-Za-z_]\w*)['\"]")

FLOWABLE_CONTEXT = {
    "execution", "task", "caseInstance", "planItemInstance", "processInstance",
    "variableContainer", "authenticatedUserId", "authenticatedUser", "currentUserId",
    "loggedInUser", "dateUtil", "date", "currentTime", "now", "initiator",
    "loopCounter", "variables", "vars", "var", "entityManagerFactory", "environment",
    "cmmnRuntimeService", "runtimeService", "taskService", "repetitionCounter",
    "root", "self", "parent", "caseInstanceId", "processInstanceId",
}
# Well-known Flowable platform service-task beans: referenced from models but
# provided by the engine, NOT expected to live in the project source.
FLOWABLE_PLATFORM_BEANS = {
    "initVariablesService", "dataObjectServiceTask", "generateDocumentService",
    "createDocumentService", "serviceRegistryService", "agentService",
    "sendEventServiceTask", "auditLogService", "decisionServiceTask",
    "caseServiceTask", "httpServiceTask", "scriptServiceTask", "mailServiceTask",
}
JAVA_LITERALS = {
    "true", "false", "null", "empty", "and", "or", "not", "div", "mod",
    "instanceof", "gt", "lt", "ge", "le", "eq", "ne", "new",
}
MUSTACHE_IGNORE = {
    "endpoints", "item", "index", "ctx", "root", "parent", "event", "self",
    "first", "last", "start", "pageSize", "flw", "payload", "temp", "filter",
    "sortColumn", "sortDirection", "orderBy", "sortBy", "total", "response",
    "page", "size", "data", "value", "params",
}
# Interfaces that mark a class as Flowable "glue" code.
GLUE_INTERFACES = {
    "JavaDelegate", "ExecutionListener", "TaskListener", "ActivityBehavior",
    "PlanItemJavaDelegate", "PlanItemActivityBehavior", "CaseInstanceLifecycleListener",
    "PlanItemInstanceLifecycleListener", "DelegatePlanItemActivityBehavior",
    "AbstractServiceTask", "JavaDelegatePlanItem", "FlowableEventListener",
}


def model_type_for(filename: str):
    low = filename.lower()
    for ext in (".bpmn20.xml", ".cmmn.xml", ".dmn.xml"):  # compound first
        if low.endswith(ext):
            return EXT_TO_TYPE[ext]
    dot = filename.rfind(".")
    if dot == -1:
        return None
    return EXT_TO_TYPE.get(low[dot:])


# ---------------------------------------------------------------------------
# XML helpers
# ---------------------------------------------------------------------------
def _strip_ns(el: ET.Element) -> ET.Element:
    if "}" in el.tag:
        el.tag = el.tag.split("}", 1)[1]
    if el.attrib:
        el.attrib = {(k.split("}", 1)[1] if "}" in k else k): v for k, v in el.attrib.items()}
    for child in el:
        _strip_ns(child)
    return el


def parse_xml(data: bytes) -> ET.Element:
    return _strip_ns(ET.fromstring(data))


def text_of(el, tag):
    found = el.find(f".//{tag}")
    return found.text.strip() if (found is not None and found.text) else None


def child_text(el, tag):
    found = el.find(tag)
    return found.text.strip() if (found is not None and found.text) else None


def ext_el(el):
    return el.find("extensionElements")


# Flowable Design stores work-/start-form model references as extension elements
# (e.g. <flowable:workformkey>KEY</flowable:workformkey> on a case plan model,
# process, start event or task) rather than as the runtime flowable:formKey
# attribute. Surface them too, otherwise a form linked only this way looks "unused".
DESIGN_FORM_TAGS = {"workformkey": "work-form", "startformkey": "start-form", "formkey": "form"}


def design_form_keys(el):
    """[(rel, formKey), ...] for forms referenced via Flowable Design extension
    elements directly under `el`'s <extensionElements>."""
    ext = ext_el(el)
    if ext is None:
        return []
    out = []
    for tag, rel in DESIGN_FORM_TAGS.items():
        v = child_text(ext, tag)
        if v:
            out.append((rel, v))
    return out


def inout_form_keys(mappings):
    """Literal form keys pushed into a child scope's form via an in/out mapping,
    e.g. <flowable:in sourceExpression="DEMO-F062" target="formKey"/>."""
    return [m.get("source") for m in (mappings or [])
            if m.get("source") and "formkey" in (m.get("target") or "").lower()]


def read_fields(el) -> dict:
    fields, ext = {}, ext_el(el)
    if ext is None:
        return fields
    for fld in ext.findall("field"):
        name = fld.get("name")
        if not name:
            continue
        s, e = fld.find("string"), fld.find("expression")
        if s is not None and s.text:
            fields[name] = s.text.strip()
        elif e is not None and e.text:
            fields[name] = e.text.strip()
        else:
            fields[name] = fld.get("stringValue") or fld.get("expression")
    return fields


def read_in_out(el):
    ext, mappings = ext_el(el), []
    if ext is None:
        return mappings
    for d in ("in", "out"):
        for m in ext.findall(d):
            mappings.append({"dir": d, "source": m.get("source") or m.get("sourceExpression"),
                             "target": m.get("target")})
    return mappings


def read_listeners(el):
    ext, out = ext_el(el), []
    if ext is None:
        return out
    for tag in ("executionListener", "taskListener", "planItemLifecycleListener"):
        for lst in ext.findall(tag):
            out.append({"kind": tag, "event": lst.get("event") or lst.get("targetState"),
                        "class": lst.get("class"), "expression": lst.get("expression"),
                        "delegateExpression": lst.get("delegateExpression"),
                        "script": child_text(lst, "script")})
    return out


# ---------------------------------------------------------------------------
# Reference collection (the heart of cross-linking)
# ---------------------------------------------------------------------------
def add_ref(ctx, frm, ftype, ffile, rel, kind, value):
    if not value:
        return
    v = str(value).strip()
    if not v:
        return
    if "${" in v or "{{" in v:   # dynamic expression, not a static key/bean
        # Not resolvable to a model, but keep it visible: a dynamic callActivity/
        # formKey/... is a real dependency the static graph cannot follow.
        ctx.setdefault("dynamic_refs", []).append({"from": frm, "fromType": ftype,
                                                   "fromFile": ffile, "rel": rel,
                                                   "kind": kind, "value": v})
        return
    ctx["refs"].append({"from": frm, "fromType": ftype, "fromFile": ffile,
                        "rel": rel, "kind": kind, "value": v})


def collect_listener_refs(ctx, frm, ftype, ffile, listeners):
    for ls in listeners:
        if ls.get("class"):
            add_ref(ctx, frm, ftype, ffile, f"{ls['kind']}:{ls.get('event')}", "class", ls["class"])
        for ex in (ls.get("delegateExpression"), ls.get("expression")):
            if ex:
                for b in re.findall(r"[#$]\{\s*([A-Za-z_][\w]*)", ex):
                    if b not in FLOWABLE_CONTEXT:
                        add_ref(ctx, frm, ftype, ffile, f"{ls['kind']}:{ls.get('event')}", "bean", b)
        collect_script_vars(ctx, ls.get("script"), [frm])


def split_ids(s):
    """Split a comma/semicolon-separated group/user string into individual ids."""
    if not s:
        return []
    return [t.strip() for t in re.split(r"[,;]", str(s)) if t.strip()]


def add_access(ctx, model, mtype, scope, action, groups=None, users=None):
    """Record a 'who can do what' entry; feed literal group names into the index."""
    g, u = split_ids(groups), split_ids(users)
    if not g and not u:
        return
    ctx["access"].append({"model": model, "modelType": mtype, "scope": scope,
                          "action": action, "groups": g, "users": u})
    ctx["groups"].update(x for x in g if "${" not in x and "{{" not in x)


def add_var(ctx, model_key, name, bucket="var_use"):
    """Record a plain variable identifier declared/mapped/used by a model."""
    if not model_key or not name:
        return
    name = str(name).strip()
    if re.match(r"^[A-Za-z_]\w*$", name) and name not in FLOWABLE_CONTEXT and name not in JAVA_LITERALS:
        ctx[bucket].setdefault(name, set()).add(model_key)


# Variables declared/mapped in backend models (BPMN/CMMN): single-purpose attrs,
# variableMapping (init vars), in/out, input/outputParameter, multi-instance, outputVariableName.
_DECL_VAR_RE = re.compile(
    r'\b(?:resultVariableName|elementVariable|counterVariable|collectionVariable|'
    r'initiatorVariableName|variableName)="([A-Za-z_]\w*)"')
_COLL_RE = re.compile(r'(?:flowable:|activiti:)?collection="([A-Za-z_]\w*)"')
_INOUT_RE = re.compile(r'<(?:flowable:|activiti:)?(?:in|out)\b([^>]*?)/?>')
_VARMAP_RE = re.compile(r'<(?:flowable:|activiti:)?variableMapping\b([^>]*?)/?>')
_PARAM_RE = re.compile(r'<(?:flowable:|activiti:)?(?:input|output)Parameter\b([^>]*?)/?>')
_OUTVAR_RE = re.compile(r'<(?:flowable:|activiti:)?outputVariableName>\s*(?:<!\[CDATA\[)?([A-Za-z_]\w*)')
_NAME_TARGET_RE = re.compile(r'\b(?:name|target)="([A-Za-z_]\w*)"')
_SRC_TARGET_RE = re.compile(r'\b(?:source|target)="([A-Za-z_]\w*)"')


def _collect_declared_vars(ctx, raw, mkeys):
    """Pull declared/mapped backend variable names out of a model's raw XML and
    attribute them to the model(s) in the file (covers init vars, in/out, MI, ...)."""
    names = set(m.group(1) for m in _DECL_VAR_RE.finditer(raw))
    names.update(m.group(1) for m in _COLL_RE.finditer(raw))
    names.update(m.group(1) for m in _OUTVAR_RE.finditer(raw))
    for m in _INOUT_RE.finditer(raw):
        names.update(am.group(1) for am in _SRC_TARGET_RE.finditer(m.group(1)))
    for m in _VARMAP_RE.finditer(raw):
        names.update(am.group(1) for am in _NAME_TARGET_RE.finditer(m.group(1)))
    for m in _PARAM_RE.finditer(raw):
        names.update(am.group(1) for am in re.finditer(r'\bname="([A-Za-z_]\w*)"', m.group(1)))
    for k in mkeys:
        for n in names:
            add_var(ctx, k, n)


def collect_script_vars(ctx, script, mkeys):
    """Attribute variable names referenced inside a script body to the owning
    model(s). Covers script-evaluation actions (config.scriptInfo.script),
    BPMN/CMMN scriptTask <script> bodies and script listeners. Uses SCRIPT_VAR_RE
    (Flowable API + legacy execution/*Variable idioms) — high-signal and
    quote-style agnostic; bare identifiers are intentionally NOT harvested."""
    if not script:
        return
    names = {m.group(1) for m in SCRIPT_VAR_RE.finditer(script)}
    for k in mkeys:
        for n in names:
            add_var(ctx, k, n, "script_var_use")


# ---------------------------------------------------------------------------
# BPMN
# ---------------------------------------------------------------------------
BPMN_EVENT_DEFS = ("timerEventDefinition", "messageEventDefinition", "signalEventDefinition",
                   "errorEventDefinition", "conditionalEventDefinition", "escalationEventDefinition",
                   "terminateEventDefinition", "compensateEventDefinition")
BPMN_EVENT_TAGS = ("startEvent", "endEvent", "intermediateCatchEvent",
                   "intermediateThrowEvent", "boundaryEvent")
BPMN_GW_TAGS = ("exclusiveGateway", "parallelGateway", "inclusiveGateway",
                "eventBasedGateway", "complexGateway")


def _event_info(ev):
    for c in ev:
        if c.tag in BPMN_EVENT_DEFS:
            kind = c.tag.replace("EventDefinition", "")
            val = (child_text(c, "timeDuration") or child_text(c, "timeCycle")
                   or child_text(c, "timeDate") or c.get("messageRef") or c.get("signalRef")
                   or c.get("errorRef"))
            return kind, val
    return None, None


def parse_bpmn(data, ctx, ffile):
    root = parse_xml(data)
    processes = []
    for proc in root.iter("process"):
        pkey = proc.get("id")
        info = {"key": pkey, "name": proc.get("name"), "file": ffile,
                "documentation": text_of(proc, "documentation"),
                "candidateStarterGroups": proc.get("candidateStarterGroups"),
                "userTasks": [], "serviceTasks": [], "scriptTasks": [], "ruleTasks": [],
                "callActivities": [], "subProcesses": [], "events": [], "gateways": [],
                "conditions": [], "otherTasks": [], "listeners": [], "multiInstance": []}

        info["listeners"] = read_listeners(proc)
        collect_listener_refs(ctx, pkey, "bpmn", ffile, info["listeners"])
        add_access(ctx, pkey, "process", "start", "start",
                   proc.get("candidateStarterGroups"), proc.get("candidateStarterUsers"))
        # process-level extension references (parity with CMMN cases)
        pext = ext_el(proc)
        if pext is not None:
            info["modelRefs"] = []
            for tag, kind in (("sla-definition-key", "sla"), ("security-policy-model", "securityPolicy"),
                              ("eventType", "event"), ("channelKey", "channel")):
                v = child_text(pext, tag)
                if v:
                    info["modelRefs"].append({"rel": tag, "key": v})
                    add_ref(ctx, pkey, "bpmn", ffile, tag, kind, v)
            dd = pext.find("data-dictionary-model")
            if dd is not None and dd.get("key"):
                info["modelRefs"].append({"rel": "data-dictionary", "key": dd.get("key")})
                add_ref(ctx, pkey, "bpmn", ffile, "data-dictionary", "dataDictionary", dd.get("key"))
        for sq in list(proc.iter("processSequence")) + list(proc.iter("caseSequence")):
            if sq.text:
                info.setdefault("modelRefs", []).append({"rel": "sequence", "key": sq.text.strip()})
                add_ref(ctx, pkey, "bpmn", ffile, "uses-sequence", "sequence", sq.text.strip())

        for el in proc.iter():
            tag, eid, ename = el.tag, el.get("id"), el.get("name")
            # forms linked via Design extension elements (work-/start-form) on the
            # process, a start event, a task — anywhere in the tree
            for rel, fk in design_form_keys(el):
                add_ref(ctx, pkey, "bpmn", ffile, rel, "form", fk)
            # Event-registry links: Design puts <flowable:eventType> (and
            # <flowable:triggerEventType>) under the element's extensionElements
            # — on send-event service tasks, receive tasks, start/intermediate
            # events etc. (process-level eventType is handled above).
            if el is not proc:
                eext = ext_el(el)
                if eext is not None:
                    ev = child_text(eext, "eventType")
                    if ev:
                        rel = ("sends-event" if el.get("type") in ("send-event", "sendEvent")
                               else "receives-event")
                        add_ref(ctx, pkey, "bpmn", ffile, rel, "event", ev)
                    add_ref(ctx, pkey, "bpmn", ffile, "trigger-event", "event",
                            child_text(eext, "triggerEventType"))
            mi = el.find("multiInstanceLoopCharacteristics")
            if mi is not None and tag not in ("process",):
                info["multiInstance"].append(
                    {"activity": eid, "collection": mi.get("collection"),
                     "elementVariable": mi.get("elementVariable"),
                     "cardinality": child_text(mi, "loopCardinality"),
                     "sequential": mi.get("isSequential")})

            if tag == "userTask":
                ut = {"id": eid, "name": ename, "assignee": el.get("assignee"),
                      "candidateGroups": el.get("candidateGroups"),
                      "formKey": el.get("formKey")}
                info["userTasks"].append(ut)
                add_ref(ctx, pkey, "bpmn", ffile, "userTask-form", "form", ut["formKey"])
                add_access(ctx, pkey, "process", f"task:{eid}", "assign",
                           el.get("candidateGroups"),
                           el.get("candidateUsers") or el.get("assignee"))
                ls = read_listeners(el)
                collect_listener_refs(ctx, pkey, "bpmn", ffile, ls)
            elif tag == "serviceTask":
                st = {"id": eid, "name": ename, "class": el.get("class"),
                      "expression": el.get("expression"),
                      "delegateExpression": el.get("delegateExpression"),
                      "type": el.get("type"), "resultVariable": el.get("resultVariableName")}
                info["serviceTasks"].append(st)
                add_ref(ctx, pkey, "bpmn", ffile, "serviceTask-class", "class", st["class"])
                if st["delegateExpression"]:
                    for b in re.findall(r"[#$]\{\s*([A-Za-z_][\w]*)", st["delegateExpression"]):
                        if b not in FLOWABLE_CONTEXT:
                            add_ref(ctx, pkey, "bpmn", ffile, "serviceTask-delegate", "bean", b)
                if el.get("type") == "http":
                    f = read_fields(el)
                    if f.get("requestUrl"):
                        ctx["rest_calls"].append({"source": pkey, "sourceFile": ffile,
                                                  "where": eid, "method": f.get("requestMethod") or "GET",
                                                  "url": f.get("requestUrl"), "kind": "http-task"})
                elif el.get("type") in ("send-event", "sendEvent"):
                    # eventType normally sits under extensionElements (generic
                    # sweep above); keep the legacy field-injection form.
                    add_ref(ctx, pkey, "bpmn", ffile, "sends-event", "event",
                            read_fields(el).get("eventType"))
                elif el.get("type") == "dmn":
                    # Design emits DMN calls as <serviceTask flowable:type="dmn">
                    # with the decision key field-injected (not businessRuleTask).
                    f = read_fields(el)
                    dref = f.get("decisionTableReferenceKey") or f.get("decisionServiceReferenceKey")
                    info["ruleTasks"].append({"id": eid, "name": ename, "decisionRef": dref})
                    add_ref(ctx, pkey, "bpmn", ffile, "ruleTask-decision", "decision", dref)
                # data object service task (field injection)
                ext = ext_el(el)
                if ext is not None:
                    dom = ext.find("dataObjectMapping")
                    if dom is not None:
                        add_ref(ctx, pkey, "bpmn", ffile, "dataObjectMapping", "dataObject",
                                dom.get("definitionKey"))
            elif tag == "scriptTask":
                body = child_text(el, "script")
                info["scriptTasks"].append({"id": eid, "name": ename,
                                            "format": el.get("scriptFormat"), "script": body,
                                            "resultVariable": el.get("resultVariable")})
                collect_script_vars(ctx, body, [pkey])
                add_var(ctx, pkey, el.get("resultVariable"))
            elif tag == "businessRuleTask":
                f = read_fields(el)
                dref = (el.get("decisionTableReferenceKey") or f.get("decisionTableReferenceKey")
                        or text_of(el, "decisionRef"))
                info["ruleTasks"].append({"id": eid, "name": ename, "decisionRef": dref})
                add_ref(ctx, pkey, "bpmn", ffile, "ruleTask-decision", "decision", dref)
            elif tag == "callActivity":
                called = el.get("calledElement")
                io = read_in_out(el)
                info["callActivities"].append({"id": eid, "name": ename, "calledElement": called,
                                                "inOut": io})
                add_ref(ctx, pkey, "bpmn", ffile, "callActivity", "process", called)
                for fk in inout_form_keys(io):
                    add_ref(ctx, pkey, "bpmn", ffile, "task-form-mapping", "form", fk)
            elif tag in ("subProcess", "transaction", "adhocSubProcess"):
                info["subProcesses"].append({"id": eid, "name": ename, "type": tag,
                                             "eventSubProcess": el.get("triggeredByEvent") == "true"})
            elif tag in BPMN_EVENT_TAGS:
                k, v = _event_info(el)
                info["events"].append({"id": eid, "name": ename, "type": tag, "def": k, "value": v})
                if tag == "startEvent" and el.get("formKey"):
                    add_ref(ctx, pkey, "bpmn", ffile, "start-form", "form", el.get("formKey"))
            elif tag in BPMN_GW_TAGS:
                info["gateways"].append({"id": eid, "name": ename, "type": tag})
            elif tag in ("sendTask", "receiveTask", "manualTask", "task"):
                info["otherTasks"].append({"id": eid, "name": ename, "type": tag})
            elif tag == "sequenceFlow":
                cond = text_of(el, "conditionExpression")
                if cond:
                    info["conditions"].append({"from": el.get("sourceRef"),
                                              "to": el.get("targetRef"), "condition": cond})
        processes.append(info)
    return processes


# ---------------------------------------------------------------------------
# CMMN
# ---------------------------------------------------------------------------
def _cmmn_service_refs(ctx, case_key, ffile, el):
    """Service-registry / data-object / agent / template mappings on a CMMN task."""
    ext = ext_el(el)
    info = {}
    if ext is None:
        return info
    info["serviceTaskType"] = child_text(el, "serviceTaskType") or child_text(ext, "serviceTaskType")
    sm = ext.find("serviceMapping")
    if sm is not None:
        info["serviceModelKey"] = sm.get("serviceModelKey")
        info["operationKey"] = sm.get("operationKey")
        add_ref(ctx, case_key, "cmmn", ffile, "serviceMapping", "service", sm.get("serviceModelKey"))
    dom = ext.find("dataObjectMapping")
    if dom is not None:
        info["dataObjectKey"] = dom.get("definitionKey")
        add_ref(ctx, case_key, "cmmn", ffile, "dataObjectMapping", "dataObject", dom.get("definitionKey"))
    am = ext.find("agentMapping")
    if am is not None:
        info["agentModelKey"] = am.get("agentModelKey")
        add_ref(ctx, case_key, "cmmn", ffile, "agentMapping", "agent", am.get("agentModelKey"))
    for tk in ("templateKey", "subjectTemplateModelKey", "bodyTemplateModelKey"):
        v = child_text(ext, tk)
        if v:
            add_ref(ctx, case_key, "cmmn", ffile, tk, "template", v)
    if el.get("delegateExpression"):
        for b in re.findall(r"[#$]\{\s*([A-Za-z_][\w]*)", el.get("delegateExpression")):
            if b not in FLOWABLE_CONTEXT:
                add_ref(ctx, case_key, "cmmn", ffile, "task-delegate", "bean", b)
    return info


def _cmmn_def(ctx, case_key, ffile, el):
    tag, d = el.tag, {"id": el.get("id"), "name": el.get("name"), "type": el.tag}
    if tag == "humanTask":
        d["assignee"] = el.get("assignee")
        d["candidateGroups"] = el.get("candidateGroups")
        d["formKey"] = el.get("formKey")
        add_ref(ctx, case_key, "cmmn", ffile, "humanTask-form", "form", el.get("formKey"))
        add_access(ctx, case_key, "case", f"task:{el.get('id')}", "assign",
                   el.get("candidateGroups"), el.get("candidateUsers") or el.get("assignee"))
    elif tag == "processTask":
        ref = text_of(el, "processRefExpression") or el.get("processRef")
        d["processRef"] = ref
        d["inOut"] = read_in_out(el)
        add_ref(ctx, case_key, "cmmn", ffile, "processTask", "process", ref)
    elif tag == "caseTask":
        ref = text_of(el, "caseRefExpression") or el.get("caseRef")
        d["caseRef"] = ref
        d["inOut"] = read_in_out(el)
        add_ref(ctx, case_key, "cmmn", ffile, "caseTask", "case", ref)
    elif tag == "decisionTask":
        ref = text_of(el, "decisionRefExpression") or el.get("decisionRef")
        d["decisionRef"] = ref
        add_ref(ctx, case_key, "cmmn", ffile, "decisionTask", "decision", ref)
    elif tag in ("task", "serviceTask", "humanTaskWithService"):
        d.update(_cmmn_service_refs(ctx, case_key, ffile, el))
        d["formKey"] = el.get("formKey")
        if el.get("formKey"):
            add_ref(ctx, case_key, "cmmn", ffile, "task-form", "form", el.get("formKey"))
        # CMMN script task: <task flowable:type="script"> with the body in a
        # <flowable:field name="script">. read_fields() surfaces it as "script".
        if el.get("type") == "script":
            d["scriptFormat"] = el.get("scriptFormat")
            d["script"] = read_fields(el).get("script")
            collect_script_vars(ctx, d["script"], [case_key])
    # Forms linked via Design extension elements or pushed in through an in-mapping
    # (e.g. a process/case task that sets the child's formKey to a literal form key).
    for rel, fk in design_form_keys(el):
        add_ref(ctx, case_key, "cmmn", ffile, rel, "form", fk)
    for fk in inout_form_keys(d.get("inOut")):
        add_ref(ctx, case_key, "cmmn", ffile, "task-form-mapping", "form", fk)
    # A Case Page task (stencil CasePageTask) exposes tabs via <flowable:page-element>;
    # a tab can render a specific form, which makes that form used (not orphaned).
    for pe in el.iter("page-element"):
        for attr in ("formKey", "formReference", "formRef", "formKeyExpression"):
            add_ref(ctx, case_key, "cmmn", ffile, "casePage-form", "form", pe.get(attr))
    # Event-registry links: <flowable:eventType>/<flowable:triggerEventType>
    # under the definition's extensionElements (send-event tasks, event listeners).
    dext = ext_el(el)
    if dext is not None:
        ev = child_text(dext, "eventType")
        if ev:
            rel = ("sends-event"
                   if (el.get("type") or d.get("serviceTaskType")) in ("send-event", "sendEvent")
                   else "receives-event")
            add_ref(ctx, case_key, "cmmn", ffile, rel, "event", ev)
        add_ref(ctx, case_key, "cmmn", ffile, "trigger-event", "event",
                child_text(dext, "triggerEventType"))
    d["listeners"] = read_listeners(el)
    collect_listener_refs(ctx, case_key, "cmmn", ffile, d["listeners"])
    return d


def _cmmn_walk(ctx, case_key, ffile, stage, all_defs=None, seen=None):
    defs = {c.get("id"): c for c in stage if c.get("id")}
    # CMMN resolves a planItem's definitionRef against the whole case, not just the
    # current scope's direct children. Flowable Design, for instance, keeps a Case
    # Page task's definition at case-plan-model level while the planItem that places
    # it sits inside a plan fragment — so a scope-local lookup misses it and the task
    # (and its formKey) is never read. Fall back to a case-wide index.
    if all_defs is None:
        all_defs = {el.get("id"): el for el in stage.iter() if el.get("id")}
    seen = seen or set()
    node = {"id": stage.get("id"), "name": stage.get("name"), "type": stage.tag,
            "autoComplete": stage.get("autoComplete"), "children": [], "criteria": []}
    for pi in stage.findall("planItem"):
        for crit in pi:
            if crit.tag in ("entryCriterion", "exitCriterion"):
                node["criteria"].append({"planItem": pi.get("name") or pi.get("definitionRef"),
                                        "type": crit.tag, "sentryRef": crit.get("sentryRef")})
        # item control rules
        ic = pi.find("itemControl")
        rules = {}
        if ic is not None:
            for r in ("repetitionRule", "requiredRule", "manualActivationRule"):
                rn = ic.find(r)
                if rn is not None:
                    rules[r] = child_text(rn, "condition") or True
        ref = pi.get("definitionRef")
        target = defs.get(ref)
        if target is None:   # not in this scope — resolve case-wide (avoid Element truthiness)
            target = all_defs.get(ref)
        if target is None:
            node["children"].append({"id": pi.get("id"), "name": pi.get("name"),
                                    "type": "planItem(?)", "rules": rules})
        elif target.tag in ("stage", "planFragment"):
            if target.get("id") in seen:   # guard against pathological scope cycles
                continue
            child = _cmmn_walk(ctx, case_key, ffile, target, all_defs, seen | {target.get("id")})
            child["rules"] = rules
            node["children"].append(child)
        else:
            d = _cmmn_def(ctx, case_key, ffile, target)
            d["rules"] = rules
            node["children"].append(d)
    return node


def parse_cmmn(data, ctx, ffile):
    root = parse_xml(data)
    cases = []
    for case in root.iter("case"):
        ckey = case.get("id")
        plan = case.find("casePlanModel")
        info = {"key": ckey, "name": case.get("name"), "file": ffile,
                "documentation": text_of(case, "documentation"),
                "initiatorVariableName": case.get("initiatorVariableName"),
                "candidateStarterGroups": case.get("candidateStarterGroups"),
                "planModel": _cmmn_walk(ctx, ckey, ffile, plan) if plan is not None else None,
                "sentries": [], "milestones": [], "eventListeners": [], "modelRefs": []}
        add_access(ctx, ckey, "case", "start", "start",
                   case.get("candidateStarterGroups"), case.get("candidateStarterUsers"))
        if plan is not None:
            if plan.get("formKey"):
                add_ref(ctx, ckey, "cmmn", ffile, "start-form", "form", plan.get("formKey"))
            # case work form / start form referenced via Design extension elements
            for rel, fk in design_form_keys(plan):
                add_ref(ctx, ckey, "cmmn", ffile, rel, "form", fk)
        # case-level extension references
        ext = ext_el(case) if case is not None else None
        if ext is not None:
            for tag, kind in (("sla-definition-key", "sla"), ("security-policy-model", "securityPolicy"),
                              ("eventType", "event"), ("channelKey", "channel")):
                v = child_text(ext, tag)
                if v:
                    info["modelRefs"].append({"rel": tag, "key": v})
                    add_ref(ctx, ckey, "cmmn", ffile, tag, kind, v)
            dd = ext.find("data-dictionary-model")
            if dd is not None and dd.get("key"):
                info["modelRefs"].append({"rel": "data-dictionary", "key": dd.get("key")})
                add_ref(ctx, ckey, "cmmn", ffile, "data-dictionary", "dataDictionary", dd.get("key"))
        for sq in list(case.iter("caseSequence")) + list(case.iter("processSequence")):
            if sq.text:
                info["modelRefs"].append({"rel": "sequence", "key": sq.text.strip()})
                add_ref(ctx, ckey, "cmmn", ffile, "uses-sequence", "sequence", sq.text.strip())
        if plan is not None:
            for el in plan.iter():
                if el.tag == "sentry":
                    cond = text_of(el, "condition") or text_of(el, "ifPart")
                    on = [op.get("sourceRef") for op in el.findall("planItemOnPart")]
                    info["sentries"].append({"id": el.get("id"), "condition": cond, "onParts": on})
                elif el.tag == "milestone":
                    info["milestones"].append({"id": el.get("id"), "name": el.get("name")})
                elif el.tag == "timerEventListener":
                    info["eventListeners"].append({"id": el.get("id"), "name": el.get("name"),
                                                   "type": el.tag, "timer": child_text(el, "timerExpression")})
                elif el.tag.endswith("EventListener"):
                    lext = ext_el(el)
                    lev = child_text(lext, "eventType") if lext is not None else None
                    if lev:
                        add_ref(ctx, ckey, "cmmn", ffile, "receives-event", "event", lev)
                    entry = {"id": el.get("id"), "name": el.get("name"), "type": el.tag}
                    if lev:
                        entry["eventType"] = lev
                    info["eventListeners"].append(entry)
        cases.append(info)
    return cases


# ---------------------------------------------------------------------------
# DMN
# ---------------------------------------------------------------------------
def parse_dmn(data, ctx, ffile):
    root = parse_xml(data)
    out = []
    for dec in root.iter("decision"):
        t = dec.find(".//decisionTable")
        info = {"key": dec.get("id"), "name": dec.get("name"), "file": ffile}
        if t is not None:
            info["hitPolicy"] = t.get("hitPolicy") or "UNIQUE"
            info["inputs"] = [i.get("label") or text_of(i, "inputExpression") for i in t.findall("input")]
            info["outputs"] = [o.get("label") or o.get("name") for o in t.findall("output")]
            info["ruleCount"] = len(t.findall("rule"))
        out.append(info)
    return out


# ---------------------------------------------------------------------------
# JSON model helpers
# ---------------------------------------------------------------------------
def _walk_json(node, fn):
    if isinstance(node, dict):
        fn(node)
        for v in node.values():
            _walk_json(v, fn)
    elif isinstance(node, list):
        for v in node:
            _walk_json(v, fn)


def parse_form(data, ctx, ffile):
    doc = json.loads(data)
    meta = doc.get("metadata", {})
    key = meta.get("key")
    # .page models share this parser but are a distinct model type — don't let them
    # default to "form" (that mislabels dashboards and drags them into "Forms · unused").
    default_type = "page" if str(ffile).lower().endswith(".page") else "form"
    info = {"key": key, "name": meta.get("name"), "file": ffile,
            "modelType": meta.get("modelType") or default_type, "fields": [], "outcomes": [],
            "dataSources": [], "subforms": []}

    for oc in doc.get("outcomes", []) or []:
        if isinstance(oc, dict):
            info["outcomes"].append({"value": oc.get("value"), "label": oc.get("label")})
            add_ref(ctx, key, info["modelType"], ffile, "outcome-form", "form", oc.get("outcomeFormKey"))

    def visit(n):
        if n.get("id") and "type" in n and "label" in n:
            f = {"id": n["id"], "type": n.get("type"), "label": n.get("label"),
                 "required": n.get("isRequired", False), "value": n.get("value")}
            info["fields"].append(f)
            if n.get("type") in ("outcomeButton",) and n.get("value"):
                info["outcomes"].append({"value": n.get("value"), "label": n.get("label")})
        es = n.get("extraSettings")
        if isinstance(es, dict):
            if es.get("formRef"):
                info["subforms"].append(es["formRef"])
                add_ref(ctx, key, info["modelType"], ffile, "subform", "form", es["formRef"])
            if es.get("dataObjectDefinitionKey"):
                info["dataSources"].append({"kind": "dataObject", "key": es["dataObjectDefinitionKey"],
                                            "op": es.get("dataObjectOperationKey")})
                add_ref(ctx, key, info["modelType"], ffile, "field-dataObject", "dataObject",
                        es["dataObjectDefinitionKey"])
            if es.get("queryUrl"):
                info["dataSources"].append({"kind": "rest", "url": es["queryUrl"]})
                ctx["rest_calls"].append({"source": key, "sourceFile": ffile, "where": n.get("id"),
                                         "method": "GET", "url": es["queryUrl"], "kind": "form-query"})
            sm = es.get("serviceModel")
            if isinstance(sm, dict) and sm.get("serviceModelKey"):
                info["dataSources"].append({"kind": "service", "key": sm["serviceModelKey"],
                                            "op": sm.get("operationKey")})
                add_ref(ctx, key, info["modelType"], ffile, "field-service", "service", sm["serviceModelKey"])
            for fk in ("dataObjectDataTableCreateFormKey", "dataObjectDataTableEditFormKey",
                       "dataObjectDataTableViewFormKey"):
                if es.get(fk):
                    add_ref(ctx, key, info["modelType"], ffile, fk, "form", es[fk])
            # a data table's expandable detail row renders a form (expandablePanel)
            if es.get("expandablePanel"):
                add_ref(ctx, key, info["modelType"], ffile, "datatable-detail-form",
                        "form", es["expandablePanel"])
            # a button / action component that triggers an Action model — records the
            # form/page as a consumer so the Action shows where it is used (and via that
            # form, which other forms/references it pulls in).
            if es.get("actionDefinitionKey"):
                add_ref(ctx, key, info["modelType"], ffile, "triggers-action",
                        "action", es["actionDefinitionKey"])
        u = n.get("url")
        if isinstance(u, str) and u.strip():
            ctx["rest_calls"].append({"source": key, "sourceFile": ffile, "where": n.get("id"),
                                     "method": "(button)", "url": u.strip(), "kind": "form-button"})

    _walk_json(doc.get("rows", []), visit)
    return info


def parse_app(data, ctx, ffile):
    doc = json.loads(data)
    design = (doc.get("extension") or {}).get("design") or {}
    variables = doc.get("variables") or {}
    key = doc.get("key")
    info = {"key": key, "name": doc.get("name"), "file": ffile,
            "description": doc.get("description"), "theme": doc.get("theme"),
            "paletteDefinitionCategory": doc.get("paletteDefinitionCategory"),
            "usersAccess": doc.get("usersAccess"), "groupsAccess": doc.get("groupsAccess"),
            "variables": [{"key": k, "type": (v or {}).get("type")} for k, v in variables.items()],
            "pages": [{"key": p.get("key"), "access": p.get("accessPermissions")}
                      for p in (doc.get("pageModels") or [])],
            "childModels": design.get("childModels", [])}
    for cm in info["childModels"]:
        add_ref(ctx, key, "app", ffile, "contains", "model:" + (cm.get("type") or "?"), cm.get("key"))
    add_access(ctx, key, "app", "app", "open-app", doc.get("groupsAccess"), doc.get("usersAccess"))
    for p in (doc.get("pageModels") or []):
        add_access(ctx, p.get("key") or key, "page", "page", "view", p.get("accessPermissions"))
    return info


def parse_agent(data, ctx, ffile):
    doc = json.loads(data)
    key = doc.get("key")
    ms = doc.get("modelSettings") or {}
    info = {"key": key, "name": doc.get("name"), "file": ffile, "type": doc.get("type"),
            "aiVendor": ms.get("aiVendor"), "modelName": ms.get("modelName"),
            "temperature": ms.get("temperature"), "operations": [], "tools": [],
            "knowledgeBase": None, "enableApiEndpoint": doc.get("enableApiEndpoint")}

    def tool_ref(t):
        if isinstance(t, dict) and t.get("key"):
            mt = t.get("modelType") or "service"
            info["tools"].append({"key": t["key"], "type": mt})
            add_ref(ctx, key, "agent", ffile, "tool", mt, t["key"])

    for t in doc.get("tools", []) or []:
        tool_ref(t)
    for op in doc.get("operations", []) or []:
        if not isinstance(op, dict):
            continue
        beh = op.get("behavior") or {}
        info["operations"].append({"key": op.get("key"), "name": op.get("name"),
                                   "systemMessage": (beh.get("systemMessage") or "")[:200],
                                   "userMessage": (beh.get("userMessage") or "")[:200]})
        for t in op.get("tools", []) or []:
            tool_ref(t)
    kb = (doc.get("knowledgeBase") or {}).get("knowledgeBaseModelReference") or {}
    if kb.get("key"):
        info["knowledgeBase"] = kb["key"]
        add_ref(ctx, key, "agent", ffile, "knowledgeBase", "knowledgeBase", kb["key"])
    da = (doc.get("documentAgent") or {}).get("documentAgentModel") or {}
    if da.get("key"):
        add_ref(ctx, key, "agent", ffile, "documentAgent", "agent", da["key"])
    return info


def _operation_params(op):
    """The input parameters an operation declares — the variables it requires — each
    with its name and type. Read from the operation's authoritative inputParameters
    list, which is present across all service types (database, REST, script, …)."""
    out = []
    for p in (op.get("inputParameters") or []):
        if isinstance(p, dict) and p.get("name"):
            out.append({"name": p.get("name"), "type": p.get("type")})
    return out


def parse_service(data, ctx, ffile):
    doc = json.loads(data)
    cfg = doc.get("config") or {}
    base = cfg.get("baseUrl") or cfg.get("url")
    info = {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "type": doc.get("type"), "baseUrl": base, "auth": (cfg.get("authentication") or {}).get("type"),
            "tableName": doc.get("tableName"),
            "referencedLiquibaseModelKey": doc.get("referencedLiquibaseModelKey"),
            "referenceKey": doc.get("referenceKey"),
            "columns": [{"name": c.get("name") or c.get("columnName"),
                         "columnName": c.get("columnName"), "type": c.get("type")}
                        for c in (doc.get("columnMappings") or [])],
            "operations": []}
    for op in doc.get("operations", []) or []:
        if not isinstance(op, dict):
            continue
        oc = op.get("config") or {}
        full = oc.get("url")
        if base and full and not str(full).startswith("http"):
            full = base.rstrip("/") + "/" + str(full).lstrip("/")
        info["operations"].append({"key": op.get("key"), "name": op.get("name"),
                                   "method": oc.get("method"), "url": oc.get("url"), "fullUrl": full,
                                   "params": _operation_params(op)})
        if oc.get("method") or oc.get("url"):
            ctx["rest_calls"].append({"source": doc.get("key"), "sourceFile": ffile,
                                     "where": op.get("key"), "method": oc.get("method") or "?",
                                     "url": full or oc.get("url"), "kind": "service-op"})
    return info


def parse_channel(data, ctx, ffile):
    doc = json.loads(data)
    ek = doc.get("channelEventKeyDetection") or {}
    add_ref(ctx, doc.get("key"), "channel", ffile, "channel-event", "event", ek.get("fixedValue"))
    return {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "channelType": doc.get("channelType"), "type": doc.get("type"),
            "topics": doc.get("topics"), "destination": doc.get("destination"),
            "eventKey": ek}


def parse_action(data, ctx, ffile):
    doc = json.loads(data)
    key = doc.get("key")
    add_ref(ctx, key, "action", ffile, "action-form", "form", doc.get("formKey"))
    for ch in doc.get("channels", []) or []:
        add_ref(ctx, key, "action", ffile, "action-channel", "channel",
                ch if isinstance(ch, str) else (ch or {}).get("key"))
    # signalName very often equals the process key the action triggers
    add_ref(ctx, key, "action", ffile, "triggers-signal", "process", doc.get("signalName"))
    add_access(ctx, key, "action", "action", "use", ",".join(doc.get("permissionGroups") or []))
    # Script-evaluation actions (botKey == platform-script-evaluation-bot) embed their
    # script + language under config.scriptInfo; harvest the variables it touches.
    script_info = (doc.get("config") or {}).get("scriptInfo") or {}
    script = script_info.get("script")
    collect_script_vars(ctx, script, [key])
    return {"key": key, "name": doc.get("name"), "file": ffile, "botKey": doc.get("botKey"),
            "formKey": doc.get("formKey"), "signalName": doc.get("signalName"),
            "channels": doc.get("channels"), "scopeType": doc.get("scopeType"),
            "icon": doc.get("icon"), "permissionGroups": doc.get("permissionGroups"),
            "script": script, "scriptLanguage": script_info.get("language")}


def parse_event(data, ctx, ffile):
    doc = json.loads(data)
    payload = doc.get("payload") or []
    # Correlation params are flagged inline on payload entries; the top-level
    # correlationParameters list only exists in older exports.
    correlation = [p.get("name") for p in (doc.get("correlationParameters") or [])]
    correlation += [p.get("name") for p in payload
                    if p.get("correlationParameter") and p.get("name") not in correlation]
    return {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "correlation": correlation,
            "payload": [p.get("name") for p in payload]}


def parse_dictionary(data, ctx, ffile):
    doc = json.loads(data)
    types = doc.get("types") or {}
    return {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "types": list(types.keys())}


def parse_data_object(data, ctx, ffile):
    doc = json.loads(data)
    key = doc.get("key")
    # Access: who may query/create/update/delete instances of this data object.
    for action, links in (doc.get("definitionIdentityLinks") or {}).items():
        if isinstance(links, dict):
            add_access(ctx, key, "dataObject", "definition", action, ",".join(links.get("groups") or []))
    for il in (doc.get("instanceIdentityLinks") or []):
        if isinstance(il, dict):
            add_access(ctx, key, "dataObject", "instance", il.get("type") or "link",
                       ",".join(il.get("groups") or []))
    # Backing integration: which service / data dictionary this data object is bound to.
    add_ref(ctx, key, "dataObject", ffile, "backed-by-service", "service",
            doc.get("referencedServiceDefinitionModelKey"))
    add_ref(ctx, key, "dataObject", ffile, "typed-by-dictionary", "dataDictionary",
            doc.get("referencedDataDictionaryModelKey"))
    columns = []
    for f in (doc.get("fieldMappings") or []):
        col = {"name": f.get("name"), "label": f.get("label"), "type": f.get("type")}
        # Object-relation fields point at another data object model.
        if f.get("dataObjectModelKey"):
            col["refDataObject"] = f["dataObjectModelKey"]
            col["relationship"] = f.get("dataObjectModelRelationshipType")
            add_ref(ctx, key, "dataObject", ffile, "relates-to", "dataObject",
                    f["dataObjectModelKey"])
        columns.append(col)
    # masterData-shaped data objects keep their field list in a `variables`
    # map (name -> label) instead of fieldMappings.
    variables = doc.get("variables")
    if isinstance(variables, dict):
        for n, lbl in variables.items():
            columns.append({"name": n, "label": lbl if isinstance(lbl, str) else None,
                            "type": None})
            add_var(ctx, key, n)
    out = {"key": key, "name": doc.get("name"), "file": ffile,
           "dataObjectType": doc.get("dataObjectType"), "sourceId": doc.get("sourceId"),
           "service": doc.get("referencedServiceDefinitionModelKey"),
           "dictionary": doc.get("referencedDataDictionaryModelKey"),
           "columns": columns, "fields": [c["name"] for c in columns]}
    for k in ("type", "subType", "keyField", "idField", "nameField", "supportsNameFiltering"):
        if doc.get(k) is not None:
            out[k] = doc[k]
    return out


def parse_policy(data, ctx, ffile):
    doc = json.loads(data)
    perms = []
    pm = doc.get("permissionMappings") or {}
    if isinstance(pm, dict):
        items = pm.items()
    else:  # Design/deployed shape: [{"key": ..., "definition": {label, permissionValues}}]
        items = ((p.get("key"), p.get("definition") or p) for p in pm if isinstance(p, dict))
    for pk, pv in items:
        if not isinstance(pv, dict):
            continue
        roles = [r for r, val in (pv.get("permissionValues") or {}).items() if val]
        perms.append({"key": pk, "label": pv.get("label"), "roles": roles})
        ctx["groups"].update(roles)
    return {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "type": doc.get("type"), "permissions": perms}


GENERIC_KEYS = ("key", "name", "description", "type", "subType", "modelType")


def parse_generic(data, ctx, ffile, mtype):
    try:
        doc = json.loads(data)
    except Exception:
        return {"key": None, "name": None, "file": ffile, "modelType": mtype}
    out = {"file": ffile, "modelType": mtype}
    for k in GENERIC_KEYS:
        if isinstance(doc, dict) and doc.get(k):
            out[k] = doc[k]
    # dashboardComponent -> query reference
    if isinstance(doc, dict):
        qm = doc.get("queryModel")
        if isinstance(qm, dict) and qm.get("key"):
            add_ref(ctx, doc.get("key"), mtype, ffile, "queryModel", "query", qm["key"])
        # variableExtractor -> the case/process scope it extracts from + target variables
        if mtype == "variableExtractor":
            for ve in doc.get("variableExtractors", []) or []:
                scope = (ve.get("filter") or {}).get("scopeDefinitionKey")
                add_ref(ctx, doc.get("key"), mtype, ffile, "extracts-from", "process", scope)
                add_var(ctx, doc.get("key"), ve.get("to"))
        # template -> the form it renders into
        if mtype == "template" and doc.get("formKey"):
            add_ref(ctx, doc.get("key"), mtype, ffile, "template-form", "form", doc.get("formKey"))
        # document model -> the form(s) used to create / edit / view its instances
        if mtype == "document" and isinstance(doc.get("forms"), dict):
            for op, fk in doc["forms"].items():
                add_ref(ctx, doc.get("key"), mtype, ffile, f"document-{op}-form", "form", fk)
    return out


PARSERS = {
    "app": parse_app, "bpmn": parse_bpmn, "cmmn": parse_cmmn, "dmn": parse_dmn,
    "form": parse_form, "page": parse_form, "agent": parse_agent, "service": parse_service,
    "channel": parse_channel, "event": parse_event, "dataDictionary": parse_dictionary,
    "dataObject": parse_data_object, "securityPolicy": parse_policy, "action": parse_action,
}

# Canonical model-kind registry: (model type, result bucket, normalized node type).
# THE single place tying a parsed model type to its result bucket and graph type —
# the bucket lists, dedupe, reference resolution and graph building all derive from
# it, so adding a model kind is exactly one entry here (+ a PARSERS entry).
MODEL_KINDS = [
    ("app",            "apps",         "app"),
    ("bpmn",           "processes",    "process"),
    ("cmmn",           "cases",        "case"),
    ("dmn",            "decisions",    "decision"),
    ("form",           "forms",        "form"),
    ("page",           "forms",        "page"),
    ("agent",          "agents",       "agent"),
    ("service",        "services",     "service"),
    ("channel",        "channels",     "channel"),
    ("event",          "events",       "event"),
    ("dataDictionary", "dictionaries", "dataDictionary"),
    ("dataObject",     "dataObjects",  "dataObject"),
    ("securityPolicy", "policies",     "securityPolicy"),
    ("action",         "actions",      "action"),
]
MODEL_BUCKET = {mtype: bucket for mtype, bucket, _ in MODEL_KINDS}
# unique bucket names in declaration order (+ the two non-parser buckets)
MODEL_BUCKETS = tuple(dict.fromkeys(b for _, b, _ in MODEL_KINDS)) + ("liquibase", "others")
# {"bpmn": "process", "cmmn": "case"} — model types whose graph/index type differs
NORMALIZE_TYPE = {mtype: norm for mtype, _, norm in MODEL_KINDS if mtype != norm}


# ---------------------------------------------------------------------------
# Java parsing
# ---------------------------------------------------------------------------
PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
TYPE_RE = re.compile(r"\b(?:public\s+|final\s+|abstract\s+)*(class|interface|enum)\s+(\w+)")
BEAN_ANN_RE = re.compile(r'@(Component|Service|Repository|Named)\s*(?:\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\))?')
IMPLEMENTS_RE = re.compile(r"\bimplements\s+([\w.,\s<>]+?)\s*\{")
MAPPING_RE = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(?:\(([^)]*)\))?")
CONTROLLER_RE = re.compile(r"@(RestController|Controller)\b")
METHOD_RE = re.compile(r"(?:public|protected|private)\s+(?:[\w$<>\[\].,]+\s+)+?(\w+)\s*\(([^;{)]*)\)\s*(?:throws[\w.,\s]+)?\{")
FIELD_RE = re.compile(r"(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([A-Z]\w+)(?:<[^>]*>)?\s+[a-z]\w*\s*[;=]")
# Process/case variables touched from Java: (set|get|has|remove)Variable[Local]("name", ...)
# — the variable name is the first string-literal arg (optionally after an id arg).
JAVA_VAR_RE = re.compile(r'\b(?:set|get|has|remove)Variable(?:Local)?\s*\(\s*(?:[^,"]+,\s*)?"([A-Za-z_]\w*)"')
# String literals in Java — matched against model keys to draw CODE -> MODEL references
# (e.g. processDefinitionKey("APP-P012"), caseDefinitionKey("..."), or a bare key literal).
JAVA_STR_RE = re.compile(r'"([^"\\\n]{2,80})"')


def _decap(name):
    return name[0].lower() + name[1:] if name else name


def _mapping_path(args):
    if not args:
        return ""
    m = re.search(r'(?:value|path)\s*=\s*"([^"]*)"', args) or re.search(r'"([^"]*)"', args)
    return m.group(1) if m else ""


COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)


def _blank_comments(text):
    # Replace comment bodies with spaces (newlines kept) so regex scans skip
    # commented-out code and Javadoc like "This class is ..." — offsets preserved.
    return COMMENT_RE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


def parse_java(text, ffile):
    text = _blank_comments(text)
    pkg_m = PKG_RE.search(text)
    pkg = pkg_m.group(1) if pkg_m else ""
    types = [m.group(2) for m in TYPE_RE.finditer(text)]
    primary = types[0] if types else os.path.splitext(os.path.basename(ffile))[0]
    line_of = lambda idx: text.count("\n", 0, idx) + 1

    bean_names = set()
    for m in BEAN_ANN_RE.finditer(text):
        bean_names.add(m.group(2) if m.group(2) else _decap(primary))
    interfaces = set()
    for m in IMPLEMENTS_RE.finditer(text):
        for it in m.group(1).split(","):
            interfaces.add(re.sub(r"<.*?>", "", it).strip().split(".")[-1])

    is_controller = bool(CONTROLLER_RE.search(text))
    class_decl_idx = text.find("class ")
    endpoints = []
    if is_controller:
        base = ""
        for m in MAPPING_RE.finditer(text):
            if class_decl_idx != -1 and m.start() < class_decl_idx and m.group(1) == "Request":
                base = _mapping_path(m.group(2))
                break
        for m in MAPPING_RE.finditer(text):
            if class_decl_idx != -1 and m.start() < class_decl_idx:
                continue
            verb = m.group(1)
            http = "ANY" if verb == "Request" else verb.upper()
            if verb == "Request" and m.group(2):
                vm = re.search(r"RequestMethod\.(\w+)", m.group(2))
                http = vm.group(1) if vm else "ANY"
            path = _mapping_path(m.group(2))
            handler_m = re.search(r"\b(\w+)\s*\(", text[m.end():m.end() + 400])
            full = "/" + "/".join(s for s in (base + "/" + path).split("/") if s)
            endpoints.append({"http": http, "path": full, "handler": handler_m.group(1) if handler_m else "?",
                             "line": line_of(m.start())})

    # Declared methods (skip control-flow keywords — they have no visibility modifier).
    methods, seen_m = [], set()
    for m in METHOD_RE.finditer(text):
        nm = m.group(1)
        if nm in ("if", "for", "while", "switch", "catch", "synchronized", "return", "new"):
            continue
        arity = 0 if not m.group(2).strip() else m.group(2).count(",") + 1
        sig = f"{nm}/{arity}"
        if sig in seen_m:
            continue
        seen_m.add(sig)
        methods.append({"name": nm, "params": arity, "line": line_of(m.start())})

    # Candidate dependency types (fields + constructor params) for Java->Java "uses".
    deps = set(FIELD_RE.findall(text))
    for cm in re.finditer(r"(?:public|protected)\s+" + re.escape(primary) + r"\s*\(([^)]*)\)", text):
        deps.update(re.findall(r"\b([A-Z]\w+)(?:<[^>]*>)?\s+\w+", cm.group(1)))

    # Categorize the class by role so the code map can answer "where are the X".
    roles = set()
    if is_controller:
        roles.add("controller")
    if re.search(r"@Service\b", text):
        roles.add("service")
    if re.search(r"@Repository\b", text):
        roles.add("repository")
    if re.search(r"@Configuration\b", text):
        roles.add("configuration")
    if re.search(r"@Component\b", text):
        roles.add("component")
    if interfaces & {"JavaDelegate", "PlanItemJavaDelegate", "JavaDelegatePlanItem",
                     "ActivityBehavior", "PlanItemActivityBehavior", "DelegatePlanItemActivityBehavior"}:
        roles.add("delegate")
    if any(i.endswith("Listener") for i in interfaces):
        roles.add("listener")
    # Flowable bot: implements BotService and exposes its key via getKey() { return "..."; }
    bot_key = None
    if any(i == "BotService" or i.endswith("Bot") or i.endswith("BotService") for i in interfaces):
        roles.add("bot")
        bm = re.search(r'getKey\s*\(\s*\)[^{]*\{[^{}]*?return\s+"([^"]+)"', text, re.S)
        bot_key = bm.group(1) if bm else None
    if not roles:
        roles.add("other")

    return {"file": ffile, "package": pkg, "primary": primary, "fqn": (pkg + "." + primary) if pkg else primary,
            "types": types, "beanNames": bean_names, "interfaces": interfaces, "roles": roles,
            "isController": is_controller, "isGlue": bool(interfaces & GLUE_INTERFACES),
            "endpoints": endpoints, "methods": methods, "deps": deps, "botKey": bot_key,
            "vars": sorted(set(JAVA_VAR_RE.findall(text))),
            "strings": set(JAVA_STR_RE.findall(text)),
            "line": line_of(class_decl_idx) if class_decl_idx != -1 else 1}


# ---------------------------------------------------------------------------
# REST matching
# ---------------------------------------------------------------------------
def _norm_path(url):
    p = re.sub(r"^[a-z]+://[^/]+", "", url or "")          # strip scheme+host
    p = p.split("?")[0]
    p = re.sub(r"[#$]\{[^}]*\}|\{\{[^}]*\}\}|\{[^}]*\}", "*", p)  # placeholders -> *
    return [s for s in p.lower().split("/") if s]


def match_rest(url, code_endpoints):
    target = _norm_path(url)
    if not target:
        return []
    matches = []
    for ep in code_endpoints:
        ep_segs = _norm_path(ep["path"])
        if not ep_segs:
            continue
        lits = [s for s in ep_segs if s != "*"]
        if lits and lits[-1] in target:           # share last literal segment
            matches.append(ep)
        elif ep_segs and target[-len(ep_segs):] == ep_segs:  # suffix match
            matches.append(ep)
    return matches


# ---------------------------------------------------------------------------
# Discovery + extraction
# ---------------------------------------------------------------------------
def discover(root):
    models, archives, javas, xmls = [], [], [], []
    if os.path.isfile(root):
        if root.lower().endswith(ARCHIVE_EXTS):
            archives.append(root)
        return models, archives, javas, xmls
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            low = fn.lower()
            if low.endswith(".java"):
                javas.append(full)
            elif low.endswith(ARCHIVE_EXTS):
                archives.append(full)
            elif model_type_for(fn):
                models.append(full)
            elif low.endswith((".xml", ".sql")):       # liquibase changelog candidates
                xmls.append(full)
    return models, archives, javas, xmls


def extract(root, expr_allowlist=None, custom=None, discover_custom=True, custom_path=None):
    ctx = {"refs": [], "dynamic_refs": [], "rest_calls": [], "expr": set(), "mustache": set(),
           "delegate_classes": set(), "access": [], "groups": set(),
           "expr_use": {}, "mustache_use": {}, "var_use": {}, "script_var_use": {},
           "query_meta": {}}
    result = {bucket: [] for bucket in MODEL_BUCKETS}
    result.update({"javaBeans": [], "javaControllers": [], "javaGlue": [], "endpoints": [],
                   "warnings": [], "diagnostics": []})
    model_index, by_key = {}, {}

    def _diag(kind, path, message):
        """One channel for every 'something went wrong with this file' event:
        a structured record (all output formats) + the legacy warning string."""
        result["diagnostics"].append({"kind": kind, "path": path, "message": str(message)})
        result["warnings"].append(f"{kind} {path}: {message}")
        log.info("diagnostic: %s %s: %s", kind, path, message)

    def dispatch(mtype, data, label):
        raw = data.decode("utf-8", "replace") if isinstance(data, bytes) else data
        # Unescape XML entities so &quot;..&quot; / &#39;..&#39; are seen as string literals.
        exprs = {html.unescape(x) for x in EXPR_RE.findall(raw)}
        musts = {html.unescape(x) for x in MUSTACHE_RE.findall(raw)}
        ctx["expr"].update(exprs)
        ctx["mustache"].update(musts)
        ctx["delegate_classes"].update(re.findall(r'(?:flowable|activiti):class="([^"]+)"', raw))
        parser = PARSERS.get(mtype)
        mkeys = []
        try:
            if parser is None:
                obj = parse_generic(data, ctx, label, mtype)
                result["others"].append(obj)
                _index(mtype, obj, label)
                mkeys = [obj.get("key")]
            else:
                parsed = parser(data, ctx, label)
                bucket = MODEL_BUCKET[mtype]
                if isinstance(parsed, list):
                    result[bucket].extend(parsed)
                    for p in parsed:
                        _index(mtype if mtype != "bpmn" else "process", p, label, p.get("key"))
                        mkeys.append(p.get("key"))
                else:
                    result[bucket].append(parsed)
                    _index(mtype, parsed, label, parsed.get("key"))
                    mkeys.append(parsed.get("key"))
                # Make ${bean.method()} references in this model visible (model -> bean, labelled).
                calls = set()
                for em in EXPR_RE.finditer(raw):
                    for cm in METHOD_CALL_FULL_RE.finditer(em.group(0)):
                        b, meth = cm.group(1), cm.group(2)
                        if b not in FLOWABLE_CONTEXT and b not in JAVA_LITERALS:
                            calls.add((b, meth))
                for k in mkeys:
                    for b, meth in calls:
                        add_ref(ctx, k, mtype, label, f"calls {meth}()", "bean", b)
        except Exception as e:  # noqa: BLE001
            _diag("parse", label, f"({mtype}) {e}")
        # Attribute every ${...} / {{...}} occurrence to the model(s) in this file.
        for k in mkeys:
            if not k:
                continue
            for e in exprs:
                ctx["expr_use"].setdefault(e, set()).add(k)
            for m in musts:
                ctx["mustache_use"].setdefault(m, set()).add(k)
        _collect_declared_vars(ctx, raw, mkeys)
        # Queries: pull the user groups they gate by + their source index straight from
        # the raw text (the deployed -bar .query files are invalid JSON — control chars in
        # the FreeMarker template — so json parsing drops exactly the version with groups).
        if mtype == "query":
            km = re.search(r'"key"\s*:\s*"([^"]+)"', raw)
            if km:
                meta = ctx["query_meta"].setdefault(km.group(1), {"groups": set(), "sourceIndex": None})
                gs = set(re.findall(r'seq_contains\(\s*\\?"([A-Za-z0-9_.\-]+)', raw))
                meta["groups"].update(gs)
                ctx["groups"].update(gs)
                si = re.search(r'"sourceIndex"\s*:\s*"([^"]+)"', raw)
                if si and not meta["sourceIndex"]:
                    meta["sourceIndex"] = si.group(1)

    def _index(mtype, obj, label, key=None):
        key = key if key is not None else (obj.get("key") if isinstance(obj, dict) else None)
        norm = NORMALIZE_TYPE.get(mtype, mtype)
        if key:
            model_index[(norm, key)] = label
            by_key.setdefault(key, []).append((norm, label))

    models, archives, javas, xmls = discover(root)
    log.info("discovered %d model files, %d archives, %d java files, %d xml/sql candidates",
             len(models), len(archives), len(javas), len(xmls))

    for path in models:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "rb") as fh:
                dispatch(model_type_for(os.path.basename(path)), fh.read(), rel)
        except Exception as e:  # noqa: BLE001
            _diag("read", rel, e)

    for arc in archives:
        rel = os.path.relpath(arc, root) if os.path.isdir(root) else os.path.basename(arc)
        try:
            with zipfile.ZipFile(arc) as zf:
                for name in zf.namelist():
                    if name.endswith("/"):
                        continue
                    mt = model_type_for(name.rsplit("/", 1)[-1])
                    if mt:
                        dispatch(mt, zf.read(name), f"{rel}!{name}")
        except Exception as e:  # noqa: BLE001
            _diag("archive", rel, e)

    log.info("parsed models: %d processes, %d cases, %d forms, %d services, %d data objects",
             len(result["processes"]), len(result["cases"]), len(result["forms"]),
             len(result["services"]), len(result["dataObjects"]))

    # Java
    bean_index, class_index, fqn_index, all_java = {}, {}, {}, {}
    for path in javas:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                jc = parse_java(fh.read(), rel)
        except Exception as e:  # noqa: BLE001
            _diag("java", rel, e)
            continue
        for b in jc["beanNames"]:
            bean_index.setdefault(b, jc)
        class_index.setdefault(jc["primary"], jc)
        fqn_index[jc["fqn"]] = jc
        all_java[jc["fqn"]] = jc
        for ep in jc["endpoints"]:
            ep2 = dict(ep, file=rel, controller=jc["primary"], controllerFqn=jc["fqn"])
            result["endpoints"].append(ep2)
        if jc["isController"]:
            result["javaControllers"].append(jc)
        if jc["isGlue"]:
            result["javaGlue"].append(jc)
        for role in jc["roles"]:
            result.setdefault("javaByRole", {}).setdefault(role, []).append(jc)

    # Liquibase changelogs (link data objects / services by key-in-content).
    # Parse each into ordered change-ops, then replay every changelog in
    # apply-order so renameColumn/dropColumn/modifyDataType are honoured: a column
    # renamed (or dropped) in a later file — e.g. under v2/ — is no longer flagged
    # "not mapped" against the table an earlier file (v1/) created. Each file-node
    # then carries the *effective* columns of the tables it touches, not just the
    # columns as first declared. Apply-order follows a master changelog's
    # <include>/<includeAll>, else a natural path sort.
    lb_files = []
    for path in xmls:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                txt = fh.read()
        except Exception as e:  # noqa: BLE001
            _diag("read", rel, e)
            continue
        if "databaseChangeLog" not in txt and "<changeSet" not in txt and "createTable" not in txt.lower():
            continue
        lb_files.append({"path": path, "rel": rel, "txt": txt, "ops": _liquibase_ops(txt),
                         "tables": sorted(set(re.findall(r'tableName="([^"]+)"', txt)))})

    if lb_files:
        schema_of, alias_of = {}, {}                        # per replay-group, keyed by file id
        for grp in _liquibase_groups(lb_files, root):
            schema, alias = _liquibase_replay(grp)
            for lf in grp:
                schema_of[id(lf)], alias_of[id(lf)] = schema, alias
        for lf in lb_files:
            schema, alias = schema_of.get(id(lf), {}), alias_of.get(id(lf), {})
            touched, seen_t = [], set()                     # tables this file has schema ops on
            for op in lf["ops"]:
                t = op.get("table") or op.get("newTable")
                if t and t.upper() not in seen_t:
                    seen_t.add(t.upper())
                    touched.append(t)
            cols = []
            for t in touched:
                cols.extend(schema.get(alias.get(t.upper(), t.upper()), []))
            # effectiveTables = the tables that actually survive the replay (rename
            # applied, dropped tables gone), taken from the surviving columns so a
            # renameTable follows to its new name and a history table created
            # alongside stays a distinct entry.
            eff_tables = sorted({c["table"] for c in cols if c.get("table")})
            # serviceDefinitionReferences: Flowable writes the service key(s) that
            # back this changelog straight into the changelog as a property. It is
            # the authoritative changelog<->service link and survives table renames
            # (a stale service may still carry the pre-rename tableName).
            svc_refs = sorted({tok for v in re.findall(
                r'name="serviceDefinitionReferences"\s+value="([^"]*)"', lf["txt"])
                for tok in re.split(r"[,\s]+", v) if tok})
            result["liquibase"].append({"key": _liquibase_key(lf["rel"]), "file": lf["rel"],
                                        "tables": lf["tables"], "effectiveTables": eff_tables,
                                        "serviceRefs": svc_refs, "columns": cols})

    # Dedupe: the same model is often present both as loose files and inside a
    # -bar.zip; collapse identical refs / rest-calls / model entries.
    def _dedupe(items, keyfn):
        seen, out = set(), []
        for it in items:
            k = keyfn(it)
            if k in seen:
                continue
            seen.add(k)
            out.append(it)
        return out

    ctx["refs"] = _dedupe(ctx["refs"], lambda r: (r["from"], r["rel"], r["kind"], r["value"]))
    ctx["dynamic_refs"] = _dedupe(ctx["dynamic_refs"],
                                  lambda r: (r["from"], r["rel"], r["kind"], r["value"]))
    ctx["rest_calls"] = _dedupe(ctx["rest_calls"], lambda rc: (rc["source"], rc.get("where"), rc["url"]))
    for bucket in MODEL_BUCKETS:
        result[bucket] = _dedupe(result[bucket], lambda o: o.get("key") or id(o))
    ctx["access"] = _dedupe(ctx["access"], lambda a: (a["model"], a["scope"], a["action"],
                                                      tuple(a["groups"]), tuple(a["users"])))

    # Platform query URLs carry model references in their query string / path, e.g.
    # ".../query-case-instances/query/APP-Q003?...&dataObjectDefinitionKey=APP-D06&..."
    for rc in ctx["rest_calls"]:
        url = rc.get("url") or ""
        for k in re.findall(r"dataObjectDefinitionKey=([A-Za-z0-9_.\-]+)", url):
            add_ref(ctx, rc["source"], "form", rc.get("sourceFile"), "queries-dataObject", "dataObject", k)
        for k in re.findall(r"/query/([A-Za-z0-9_.\-]+)", url):
            add_ref(ctx, rc["source"], "form", rc.get("sourceFile"), "runs-query", "query", k)
    ctx["refs"] = _dedupe(ctx["refs"], lambda r: (r["from"], r["rel"], r["kind"], r["value"]))

    # Resolve references
    resolved, unresolved = [], []
    for ref in ctx["refs"]:
        kind, val = ref["kind"], ref["value"]
        target = None
        if kind.startswith("model:") or kind in ("process", "case", "decision", "form", "page",
                                                  "service", "agent", "dataObject", "dataDictionary",
                                                  "channel", "event", "template", "sla", "securityPolicy",
                                                  "knowledgeBase", "query", "sequence", "masterData",
                                                  "action", "document", "variableExtractor", "dashboardComponent"):
            norm = kind.split(":", 1)[1] if kind.startswith("model:") else kind
            norm = NORMALIZE_TYPE.get(norm, norm)
            target = model_index.get((norm, val))
            ref2 = dict(ref, target=target, targetType="model")
            if target is None and val in by_key:
                # Fallback across model types: prefer a same-type entry, and tag
                # cross-type resolutions so they stay auditable in the output.
                entries = by_key[val]
                match = next((e for e in entries if e[0] == norm), entries[0])
                ref2["target"] = target = match[1]
                if match[0] != norm:
                    ref2["fallbackType"] = match[0]
        elif kind == "bean":
            jc = bean_index.get(val) or class_index.get(val[0].upper() + val[1:] if val else val)
            target = f"{jc['file']}:{jc['line']} ({jc['fqn']})" if jc else None
            ref2 = dict(ref, target=target, targetType="bean", targetFqn=jc["fqn"] if jc else None)
        elif kind == "class":
            simple = val.split(".")[-1]
            jc = fqn_index.get(val) or class_index.get(simple)
            target = f"{jc['file']}:{jc['line']}" if jc else None
            ref2 = dict(ref, target=target, targetType="class", targetFqn=jc["fqn"] if jc else None)
        else:
            ref2 = dict(ref, target=None, targetType=kind)
        (resolved if target else unresolved).append(ref2)
    # Must land in result BEFORE _build_graph — it reads unresolvedRefs to emit
    # external nodes for beans/classes/missing models.
    result["resolvedRefs"], result["unresolvedRefs"] = resolved, unresolved
    result["dynamicRefs"] = ctx["dynamic_refs"]

    # Resolve REST calls -> code endpoints
    for rc in ctx["rest_calls"]:
        rc["_matchEps"] = match_rest(rc["url"], result["endpoints"])
        rc["matches"] = [f"{m['http']} {m['path']} -> {m['controller']}#{m['handler']} ({m['file']}:{m['line']})"
                         for m in rc["_matchEps"]]

    # Variables / beans / expressions (+ bean.method() map for the graph)
    beans, variables, bean_methods = set(), set(), {}
    for e in ctx["expr"]:
        body = re.sub(r"^[#$]\{|\}$", "", e)
        body = re.sub(r"'[^']*'|\"[^\"]*\"", " ", body)   # drop string literals
        for bm in METHOD_CALL_FULL_RE.finditer(body):
            if bm.group(1) not in FLOWABLE_CONTEXT and bm.group(1) not in JAVA_LITERALS:
                beans.add(bm.group(1))
                bean_methods.setdefault(bm.group(1), set()).add(bm.group(2))
        for im in ROOT_IDENT_RE.finditer(body):
            n = im.group(1)
            if body[im.end():].lstrip()[:1] == "(" or n in FLOWABLE_CONTEXT or n in JAVA_LITERALS:
                continue
            variables.add(n)
    for ph in ctx["mustache"]:
        m = re.match(r"\$?([A-Za-z_][\w]*)", ph.strip("{} ").strip())
        if m and m.group(1) not in MUSTACHE_IGNORE:
            variables.add(m.group(1))

    # ---- Denormalize service table names onto data objects ----
    _enrich_data_objects(result)
    # ---- Schema coverage: Liquibase column -> service mapping -> data object field ----
    _schema_coverage(result)
    # ---- Flag each changelog as the live definition of its table(s) vs a
    #      superseded/orphan revision (several changelogs often recreate the same
    #      physical table at different revisions). ----
    _mark_liquibase_authority(result)

    log.info("resolved %d refs, %d unresolved", len(resolved), len(unresolved))

    # ---- Custom frontend functions (externals.additionalData) — read the real names from the
    #      project's frontend-customization source so `flowkyc.*` etc. validate precisely. ----
    if custom is None and discover_custom:
        try:
            custom = extract_custom_functions(custom_path or root, explicit=custom_path)
        except Exception as e:  # noqa: BLE001 — never let extraction abort a run
            _diag("custom-functions", str(custom_path or root), e)
            custom = None
    if custom:
        log.info("custom functions: %s", custom_functions_summary(custom))

    # ---- Build a navigable graph (nodes + edges) for the HTML explorer ----
    graph = _build_graph(result, ctx, resolved, all_java, bean_methods, by_key, expr_allowlist, custom)
    log.info("graph: %d nodes, %d edges", len(graph["nodes"]), len(graph["edges"]))

    result.update({
        "modelIndex": {f"{k[0]}:{k[1]}": v for k, v in model_index.items()},
        "restCalls": ctx["rest_calls"], "beanIndex": sorted(bean_index.keys()),
        "variables": sorted(variables - beans), "beans": sorted(beans),
        "expressions": sorted(ctx["expr"]), "placeholders": sorted(ctx["mustache"]),
        "delegateClasses": sorted(ctx["delegate_classes"]),
        "access": ctx["access"], "groups": sorted(ctx["groups"]),
        "javaByRole": result.get("javaByRole", {}), "graph": graph,
        "customFunctions": ({
            "namespaces": {ns: sorted(m) for ns, m in custom["namespaces"].items()},
            "flw": sorted(custom["flw"]), "topLevel": sorted(custom["top_level"]),
            "sources": custom["sources"], "diagnostics": custom["diagnostics"],
            "signatures": custom.get("signatures", {}),
            "summary": custom_functions_summary(custom),
        } if custom else None),
        "stats": {"models": len(models), "archives": len(archives), "java": len(javas),
                  "endpoints": len(result["endpoints"]), "groups": len(ctx["groups"]),
                  "nodes": len(graph["nodes"]), "edges": len(graph["edges"])},
    })
    return result


# ---------------------------------------------------------------------------
# Graph builder (nodes + edges) for the interactive HTML explorer
# ---------------------------------------------------------------------------
# Map model bucket -> node type; java/endpoint/group/external handled separately.
def _container_of(path):
    """The 'app container' of a file: its archive (before '!') or its parent dir."""
    if not path:
        return None
    if "!" in path:
        return path.split("!", 1)[0]
    return path.rsplit("/", 1)[0] if "/" in path else "."


_LB_BLOCK_RE = re.compile(r'<(createTable|addColumn)\b([^>]*?)>(.*?)</\1\s*>', re.S)
_LB_COLUMN_RE = re.compile(r'<column\b([^>]*?)/?>')
_LB_RENAMECOL_RE = re.compile(r'<renameColumn\b([^>]*?)/?>', re.S)
_LB_DROPCOL_RE = re.compile(r'<dropColumn\b([^>]*?)(?:/>|>(.*?)</dropColumn\s*>)', re.S)
_LB_MODIFYTYPE_RE = re.compile(r'<modifyDataType\b([^>]*?)/?>', re.S)
_LB_RENAMETABLE_RE = re.compile(r'<renameTable\b([^>]*?)/?>', re.S)
_LB_DROPTABLE_RE = re.compile(r'<dropTable\b([^>]*?)/?>', re.S)
_LB_INCLUDE_RE = re.compile(r'<include(All)?\b([^>]*?)/?>', re.I | re.S)


def _lb_attr(s, name):
    """Read a single XML attribute value out of a tag's attribute string."""
    m = re.search(r'\b' + re.escape(name) + r'\s*=\s*"([^"]*)"', s or "")
    return m.group(1) if m else None


def _natural_key(s):
    """Sort key that orders embedded numbers numerically, so v2 < v10 (and
    DEMO-L2 < DEMO-L10) instead of the lexical v10 < v2."""
    return [int(p) if p.isdigit() else p.lower() for p in re.split(r'(\d+)', s or "")]


def _liquibase_ops(txt):
    """Parse a Liquibase changelog into an ordered list of schema change-ops,
    preserving document order so they can be replayed to compute a table's
    *effective* columns. Beyond createTable/addColumn this also understands
    renameColumn, dropColumn, modifyDataType, renameTable and dropTable — the
    operations that mutate an already-created table. Columns inside insert/update
    data blocks carry no type and are not matched, so only schema columns flow in.
    Types may be raw (BIGINT) or property placeholders (${varchar.type}(255))."""
    found = []
    for m in _LB_BLOCK_RE.finditer(txt):
        cols = []
        for cm in _LB_COLUMN_RE.finditer(m.group(3)):
            nm = _lb_attr(cm.group(1), "name")
            if nm:
                cols.append({"name": nm, "type": _lb_attr(cm.group(1), "type")})
        if cols:
            found.append((m.start(), {"op": m.group(1),
                                      "table": _lb_attr(m.group(2), "tableName"),
                                      "columns": cols}))
    for m in _LB_RENAMECOL_RE.finditer(txt):
        a = m.group(1)
        old, new = _lb_attr(a, "oldColumnName"), _lb_attr(a, "newColumnName")
        if old and new:
            found.append((m.start(), {"op": "renameColumn", "table": _lb_attr(a, "tableName"),
                                      "oldName": old, "newName": new,
                                      "type": _lb_attr(a, "columnDataType")}))
    for m in _LB_DROPCOL_RE.finditer(txt):
        a, body = m.group(1), m.group(2) or ""
        names = []
        single = _lb_attr(a, "columnName")          # attribute form
        if single:
            names.append(single)
        for cm in _LB_COLUMN_RE.finditer(body):     # nested <column name=".."/> form
            nm = _lb_attr(cm.group(1), "name")
            if nm:
                names.append(nm)
        if names:
            found.append((m.start(), {"op": "dropColumn", "table": _lb_attr(a, "tableName"),
                                      "columns": names}))
    for m in _LB_MODIFYTYPE_RE.finditer(txt):
        a = m.group(1)
        col = _lb_attr(a, "columnName")
        if col:
            found.append((m.start(), {"op": "modifyDataType", "table": _lb_attr(a, "tableName"),
                                      "column": col, "type": _lb_attr(a, "newDataType")}))
    for m in _LB_RENAMETABLE_RE.finditer(txt):
        a = m.group(1)
        old, new = _lb_attr(a, "oldTableName"), _lb_attr(a, "newTableName")
        if old and new:
            found.append((m.start(), {"op": "renameTable", "oldTable": old, "newTable": new}))
    for m in _LB_DROPTABLE_RE.finditer(txt):
        t = _lb_attr(m.group(1), "tableName")
        if t:
            found.append((m.start(), {"op": "dropTable", "table": t}))
    found.sort(key=lambda x: x[0])
    return [op for _, op in found]


def _liquibase_groups(files, root):
    """Partition changelog files into replay groups, each an apply-ordered list.

    Files reached from a master changelog via <include>/<includeAll> form ONE
    ordered group that shares a schema — this is the v1/v2/v3 case, where a
    renameColumn/dropColumn in a later file (e.g. under v2/) acts on a table that
    an earlier file (v1/) created. Every other file is its own singleton group.

    Scoping by the master's include chain (rather than globally by table name) is
    deliberate: standalone changelogs — e.g. several Flowable service-registry
    model exports that each independently `createTable` the same physical table at
    different revisions — must NOT be merged, or one revision's columns leak into
    another. include paths match by path suffix, which is robust to
    classpath:/relativeToChangelogFile resolution differences. v2 < v10 ordering
    within a group comes from the natural sort."""
    by_base = {}
    for f in files:
        by_base.setdefault(os.path.basename(f["path"]), []).append(f)

    def clean(p):
        return re.sub(r'^classpath\*?:', '', p or '').replace('\\', '/').strip('/').lower()

    nat = lambda fs: sorted(fs, key=lambda x: _natural_key(x["rel"]))

    def closure(master):                                    # apply-ordered include chain
        ordered, seen = [], set()

        def walk(f):
            if id(f) in seen:
                return
            seen.add(id(f))
            for m in _LB_INCLUDE_RE.finditer(f["txt"]):
                a = m.group(2)
                if m.group(1):                              # <includeAll path="dir">
                    pdir = clean(_lb_attr(a, "path") or _lb_attr(a, "dir") or "")
                    if not pdir:
                        continue
                    kids = [g for g in files if id(g) != id(f)
                            and os.path.dirname(g["path"]).replace('\\', '/').lower().endswith(pdir)]
                    for g in nat(kids):
                        walk(g)
                else:                                       # <include file="x.xml">
                    ref = clean(_lb_attr(a, "file"))
                    if not ref:
                        continue
                    g = next((x for x in files
                              if x["path"].replace('\\', '/').lower().endswith(ref)), None)
                    if g is None:
                        b = by_base.get(os.path.basename(ref))
                        g = b[0] if b else None
                    if g:
                        walk(g)
            ordered.append(f)                               # master itself after its includes

        walk(master)
        return ordered

    masters = [f for f in files if _LB_INCLUDE_RE.search(f["txt"])]
    assigned, groups = set(), []
    for master in nat(masters):
        if id(master) in assigned:                          # already pulled in by an outer master
            continue
        grp = [f for f in closure(master) if id(f) not in assigned]
        if grp:
            assigned.update(id(f) for f in grp)
            groups.append(grp)
    for f in nat(files):                                    # standalone files: one per group
        if id(f) not in assigned:
            assigned.add(id(f))
            groups.append([f])
    return groups


def _liquibase_replay(files):
    """Replay change-ops across the ordered changelog files to compute each
    table's effective columns. Returns (schema, alias): schema maps an
    upper-cased table name to its surviving columns (renames applied, drops
    removed, types modified); alias maps any historical table name to its final
    name (for renameTable). Column identity uses _loose() so SQL/logical naming
    differences don't double-count."""
    schema, alias = {}, {}

    def cur(tu):                                            # follow renameTable chain
        seen = set()
        while tu in alias and tu not in seen:
            seen.add(tu)
            tu = alias[tu]
        return tu

    for lf in files:
        for op in lf.get("ops", []):
            kind = op["op"]
            if kind in ("createTable", "addColumn"):
                tu = cur((op.get("table") or "").upper())
                lst = schema.setdefault(tu, [])
                idx = {c["_k"]: c for c in lst}
                for col in op["columns"]:
                    k = _loose(col["name"])
                    if k in idx:
                        if col.get("type"):
                            idx[k]["type"] = col["type"]
                        continue
                    c = {"name": col["name"], "type": col.get("type"),
                         "table": op.get("table"), "_k": k}
                    idx[k] = c
                    lst.append(c)
            elif kind == "renameColumn":
                tu = cur((op.get("table") or "").upper())
                lst = schema.get(tu)
                ok, nk = _loose(op["oldName"]), _loose(op["newName"])
                hit = next((c for c in lst if c["_k"] == ok), None) if lst else None
                if hit:
                    hit["name"], hit["_k"] = op["newName"], nk
                    if op.get("type"):
                        hit["type"] = op["type"]
                else:                                       # old col unknown — surface the new one
                    schema.setdefault(tu, []).append({"name": op["newName"], "type": op.get("type"),
                                                      "table": op.get("table"), "_k": nk})
            elif kind == "dropColumn":
                tu = cur((op.get("table") or "").upper())
                lst = schema.get(tu)
                if lst:
                    drop = {_loose(n) for n in op["columns"]}
                    schema[tu] = [c for c in lst if c["_k"] not in drop]
            elif kind == "modifyDataType":
                tu = cur((op.get("table") or "").upper())
                lst = schema.get(tu)
                if lst and op.get("type"):
                    k = _loose(op["column"])
                    for c in lst:
                        if c["_k"] == k:
                            c["type"] = op["type"]
            elif kind == "renameTable":
                old, new = cur(op["oldTable"].upper()), op["newTable"].upper()
                if old in schema:
                    lst = schema.pop(old)
                    for c in lst:
                        c["table"] = op["newTable"]
                    schema[new] = schema.get(new, []) + lst
                alias[old] = new
            elif kind == "dropTable":
                schema.pop(cur((op.get("table") or "").upper()), None)

    out = {t: [{"name": c["name"], "type": c["type"], "table": c["table"]} for c in lst]
           for t, lst in schema.items()}
    return out, {k: cur(k) for k in alias}


def _liquibase_key(path):
    """Derive a liquibase model key from a changelog filename (the authoritative
    referencedLiquibaseModelKey points at this), e.g. liquibase-APP-L003.data.changelog.xml -> APP-L003."""
    base = path.split("!")[-1].rsplit("/", 1)[-1]
    base = re.sub(r"^liquibase-", "", base)
    base = re.sub(r"\.data\.changelog\.xml$|\.changelog\.xml$|\.xml$|\.sql$", "", base, flags=re.I)
    return base


def _loose(s):
    """Loose column-identity key: lowercase, drop every non-alphanumeric. Bridges
    the SQL <-> logical naming gap so the same field lines up across layers, e.g.
    CREW_ID_  ==  crewId  ==  crew_id  ==  crewid."""
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def _enrich_data_objects(result):
    """Denormalize the backing service's physical table onto each data object,
    so the table name is visible on the data object itself (explorer card,
    overview, search) without hopping to the service node."""
    svc = {s.get("key"): s for s in result["services"] if s.get("key")}
    for d in result["dataObjects"]:
        s = svc.get(d.get("service"))
        if s:
            if s.get("tableName"):
                d["serviceTableName"] = s["tableName"]
            if s.get("type"):
                d["serviceType"] = s["type"]


def _schema_coverage(result):
    """Line up every column across the three layers of the data chain
    (Liquibase changelog -> service column mapping -> data object field) and flag
    any Liquibase column that is not carried through to the service and/or to a
    backing data object.

    Attaches `schemaCoverage` to each service (the join hub: it points at its
    Liquibase changelog via referencedLiquibaseModelKey/tableName and is referenced
    back by its data objects) and `coverage` to each referenced Liquibase changelog
    (so the changelog view can highlight orphan columns directly)."""
    lb_by_key = {lb["key"]: lb for lb in result["liquibase"]}
    # Index changelogs by BOTH current and historical table names (the union of
    # effectiveTables and the raw tableName= occurrences), so a service still
    # resolves whether it carries the post-rename name or the original one.
    lb_by_table = {}
    for lb in result["liquibase"]:
        for t in set(lb.get("effectiveTables") or []) | set(lb.get("tables") or []):
            lb_by_table.setdefault(t.upper(), lb)
    lb_by_svcref = {}                                   # service key -> changelog (authoritative)
    for lb in result["liquibase"]:
        for sk in (lb.get("serviceRefs") or []):
            lb_by_svcref.setdefault(sk, lb)
    dos_by_service = {}
    for d in result["dataObjects"]:
        if d.get("service"):
            dos_by_service.setdefault(d["service"], []).append(d)
    # consumed[lb_key] = {"service": {loose names}, "dataObject": {loose names}}
    consumed = {}

    for s in result["services"]:
        rk = s.get("referencedLiquibaseModelKey")
        lb = lb_by_key.get(rk) if rk else None
        if lb is None:                                  # authoritative back-reference
            lb = lb_by_svcref.get(s.get("key"))
        if lb is None and s.get("tableName"):           # current or pre-rename table name
            lb = lb_by_table.get(s["tableName"].upper())

        svc_table = (s.get("tableName") or "").upper() or None
        lb_cols = []
        if lb:
            for c in (lb.get("columns") or []):
                if svc_table and c.get("table") and c["table"].upper() != svc_table:
                    continue
                lb_cols.append(c)
            if svc_table and not lb_cols:        # table name didn't line up — show the lot
                lb_cols = list(lb.get("columns") or [])

        svc_by_loose = {}
        for c in (s.get("columns") or []):
            sql = c.get("columnName") or c.get("name")
            if sql:
                svc_by_loose.setdefault(_loose(sql), c)

        dos = dos_by_service.get(s.get("key"), [])
        do_by_loose = {}                          # loose name -> [(do_key, field), ...]
        for d in dos:
            for f in (d.get("columns") or []):
                if f.get("name"):
                    do_by_loose.setdefault(_loose(f["name"]), []).append((d.get("key"), f))

        def do_hits_for(*names):
            seen, hits = set(), []
            for nm in names:
                if not nm:
                    continue
                for dk, f in do_by_loose.get(_loose(nm), []):
                    k = (dk, f.get("name"))
                    if k not in seen:
                        seen.add(k)
                        hits.append({"do": dk, "field": f.get("name"), "type": f.get("type")})
            return hits

        rows, seen_svc = [], set()
        for c in lb_cols:                          # Liquibase = source of truth
            sql = c.get("name") or ""
            key = _loose(sql)
            svc = svc_by_loose.get(key)
            if svc:
                seen_svc.add(key)
            hits = do_hits_for(sql, svc.get("name") if svc else None)
            status = "ok" if (svc and hits) else ("no-dataobject" if svc else "no-service")
            rows.append({"sql": sql, "table": c.get("table"), "sqlType": c.get("type"),
                         "inLiquibase": True, "inService": bool(svc),
                         "service": (svc.get("name") if svc else None),
                         "serviceCol": (svc.get("columnName") if svc else None),
                         "serviceType": (svc.get("type") if svc else None),
                         "dataObjects": hits, "status": status})

        if lb:                                     # service mappings with no schema column
            for c in (s.get("columns") or []):
                sql = c.get("columnName") or c.get("name") or ""
                if not sql or _loose(sql) in seen_svc:
                    continue
                rows.append({"sql": sql, "table": None, "sqlType": None,
                             "inLiquibase": False, "inService": True,
                             "service": c.get("name"), "serviceCol": c.get("columnName"),
                             "serviceType": c.get("type"),
                             "dataObjects": do_hits_for(c.get("name"), c.get("columnName")),
                             "status": "extra-service"})

        if lb:
            cc = consumed.setdefault(lb["key"], {"service": set(), "dataObject": set()})
            for r in rows:
                if r["inLiquibase"] and r["inService"]:
                    cc["service"].add(_loose(r["sql"]))
                if r["inLiquibase"] and r["dataObjects"]:
                    cc["dataObject"].add(_loose(r["sql"]))

        if not rows:
            continue
        s["schemaCoverage"] = {
            "liquibase": lb["key"] if lb else None,
            "table": s.get("tableName"),
            "dataObjects": [d.get("key") for d in dos],
            "rows": rows,
            "counts": {"total": len(rows),
                       "ok": sum(r["status"] == "ok" for r in rows),
                       "noService": sum(r["status"] == "no-service" for r in rows),
                       "noDataObject": sum(r["status"] == "no-dataobject" for r in rows),
                       "extra": sum(r["status"] == "extra-service" for r in rows)}}

    for lb in result["liquibase"]:
        cc = consumed.get(lb["key"])
        if cc:
            lb["coverage"] = {"service": sorted(cc["service"]),
                              "dataObject": sorted(cc["dataObject"])}


def _mark_liquibase_authority(result):
    """Flag each changelog as the LIVE (authoritative) definition of its table(s)
    or a SUPERSEDED / ORPHAN revision.

    Projects routinely carry several changelogs that each `createTable` the same
    physical table at different revisions (e.g. DEMO-D06 / DEMO-D06Schema /
    DEMO-D06SchemaNew all create SHOPPING_LIST_). The authoritative one is the
    revision a service / data object actually references via
    referencedLiquibaseModelKey (or, lacking that, a service whose tableName
    matches); the rest are superseded. Table identity is by NAME — taken from
    effectiveTables, so renameTable follows to the new name and a history table
    created alongside (same columns, different name) is never mistaken for a copy.

    Sets lb["authority"] = {status, referencedBy, supersededBy}, with status:
    'live', 'superseded' (a forward-bound sibling owns the same table) or 'orphan'.

    Signals, by strength:
      1. FORWARD binding — a service's referencedLiquibaseModelKey points HERE.
         This is what the service actually runs against, so it owns its table(s).
      2. BACK reference — the changelog's own serviceDefinitionReferences names a
         real service. Reliable for linking and enough to keep a changelog 'live'
         when no forward-bound sibling competes (e.g. a dead, renamed table whose
         service still carries the old name), but it does NOT outrank a forward
         binding — several stale revisions often carry the same service key.
      3. tableName match — weakest fallback.
    Table identity is by NAME, from effectiveTables, so renameTable follows to the
    new name and a history table (same columns, different name) is never a copy.
    Changelogs that define no surviving table and are unreferenced get no mark."""
    forward = {}                                        # lb key -> [services binding it forward]
    svc_by_table = {}                                   # TABLE -> [service keys] (tableName match)
    for s in result.get("services", []):
        rk = s.get("referencedLiquibaseModelKey")
        if rk:
            forward.setdefault(rk, []).append(s.get("key"))
        tn = s.get("tableName")
        if tn:
            svc_by_table.setdefault(tn.upper(), []).append(s.get("key"))
    svc_keys = {s.get("key") for s in result.get("services", []) if s.get("key")}
    backref = {}                                        # lb key -> [services it names back]
    for lb in result.get("liquibase", []):
        for sk in (lb.get("serviceRefs") or []):
            if sk in svc_keys:
                backref.setdefault(lb["key"], []).append(sk)

    def eff(lb):
        return {t.upper() for t in (lb.get("effectiveTables") or [])}

    forward_owner = {}                                  # TABLE -> [lb keys bound forward]
    for lb in result.get("liquibase", []):
        if forward.get(lb["key"]):
            for t in eff(lb):
                forward_owner.setdefault(t, []).append(lb["key"])

    for lb in result.get("liquibase", []):
        tbls = eff(lb)
        fwd = sorted(set(forward.get(lb["key"], [])))
        back = sorted(set(backref.get(lb["key"], [])))
        if not tbls and not fwd and not back:           # defines no table, unreferenced -> skip
            continue
        owners = sorted({o for t in tbls for o in forward_owner.get(t, []) if o != lb["key"]})
        if fwd:                                         # the service runs against this one
            status, by = "live", fwd
        elif owners:                                    # a forward-bound sibling owns this table
            status, by = "superseded", []
        elif back:                                      # own back-reference, no live competitor
            status, by = "live", back
        else:
            tbl_refs = sorted({sk for t in tbls for sk in svc_by_table.get(t, [])})
            status, by = ("live", tbl_refs) if tbl_refs else ("orphan", [])
        lb["authority"] = {"status": status, "referencedBy": by, "supersededBy": owners}


def _vars_in_expr(expr):
    """Variable identifiers used in a ${...}/#{...} expression (not beans/functions/context)."""
    body = re.sub(r"^[#$]\{|\}$", "", expr)
    body = re.sub(r"'[^']*'|\"[^\"]*\"", " ", body)   # drop string literals (e.g. date formats 'dd-M-yyyy')
    beans = {m.group(1) for m in METHOD_CALL_FULL_RE.finditer(body)}
    out = set()
    for m in ROOT_IDENT_RE.finditer(body):
        n = m.group(1)
        if body[m.end():].lstrip()[:1] == "(":
            continue
        if n in FLOWABLE_CONTEXT or n in JAVA_LITERALS or n in beans:
            continue
        out.add(n)
    return out


def _var_in_mustache(ph):
    """The bound variable name of a {{...}} binding, e.g. {{myVar}} -> myVar, {{order.x}} -> order."""
    inner = ph.strip("{} ").strip()
    m = re.match(r"\$?([A-Za-z_][\w]*)", inner)
    if not m:
        return None
    root = m.group(1)
    if root in MUSTACHE_IGNORE or root.lstrip("$") in MUSTACHE_IGNORE:
        return None
    return root


# ===========================================================================
# Flowable expression validator — a faithful Python port of the IntelliJ
# plugin's Kotlin validator (idea-plugin/src/main/kotlin/com/flowable/atlas/
# expr/). Keep in sync with, in order:
#   - lang/ExpressionLexer.kt              -> _expr_tokenize
#   - parse/ExprParser.kt + parse/ExprAst.kt -> _ExprParser (structural errors)
#   - catalog/FlowableExpressionCatalog.kt -> the _BACKEND_* / _FRONTEND_* tables
#   - inspection/Suggestions.kt            -> _closest / _levenshtein
#   - ExpressionValidator.kt               -> validate_expression
# validate_expression(body, dialect) returns a list of problem dicts
#   {"start", "end", "message", "severity": "error"|"warning", "quickFix"}
# (empty list == valid), with offsets pointing into `body`.
# ===========================================================================

BACKEND = "backend"
FRONTEND = "frontend"
_FRONTEND_NS = "flw"

# --- token kinds (mirror lang/ExpressionLexer.kt TokType) ---
(_IDENT, _NUMBER, _STRING, _STRING_BAD, _DOT, _COLON, _COMMA, _LPAREN, _RPAREN,
 _LBRACKET, _RBRACKET, _PIPE, _ARROW, _OP, _BAD) = range(15)


class _Tok:
    __slots__ = ("type", "start", "end", "text")

    def __init__(self, type, start, end, text):
        self.type = type
        self.start = start
        self.end = end
        self.text = text


_THREE_CHAR_OPS = {"===", "!=="}
_TWO_CHAR_OPS = {"==", "!=", "<=", ">=", "&&", "||"}
_ONE_CHAR_OPS = set("+-*/%<>!?=")


def _is_ident_start(c):
    return c.isalpha() or c == "_" or c == "$"


def _is_ident_part(c):
    return c.isalnum() or c == "_" or c == "$"


def _expr_tokenize(text):
    """Port of ExpressionLexer.tokenize — whitespace-skipping, offsets preserved."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
        elif c == "'" or c == '"':
            start = i
            i += 1
            terminated = False
            while i < n:
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    i += 2
                    continue
                if ch == c:
                    i += 1
                    terminated = True
                    break
                if ch == "\n":
                    break
                i += 1
            out.append(_Tok(_STRING if terminated else _STRING_BAD, start, i, text[start:i]))
        elif _is_ident_start(c):
            start = i
            i += 1
            while i < n and _is_ident_part(text[i]):
                i += 1
            out.append(_Tok(_IDENT, start, i, text[start:i]))
        elif c.isdigit():
            start = i
            i += 1
            while i < n and (text[i].isdigit() or text[i] == "."):
                i += 1
            out.append(_Tok(_NUMBER, start, i, text[start:i]))
        else:
            three = text[i:i + 3]
            two = text[i:i + 2]
            if three in _THREE_CHAR_OPS:
                out.append(_Tok(_OP, i, i + 3, three)); i += 3
            elif two == "|>":
                out.append(_Tok(_PIPE, i, i + 2, two)); i += 2
            elif two == "->" or two == "=>":
                out.append(_Tok(_ARROW, i, i + 2, two)); i += 2
            elif two in _TWO_CHAR_OPS:
                out.append(_Tok(_OP, i, i + 2, two)); i += 2
            elif c == ".":
                out.append(_Tok(_DOT, i, i + 1, ".")); i += 1
            elif c == ":":
                out.append(_Tok(_COLON, i, i + 1, ":")); i += 1
            elif c == ",":
                out.append(_Tok(_COMMA, i, i + 1, ",")); i += 1
            elif c == "(":
                out.append(_Tok(_LPAREN, i, i + 1, "(")); i += 1
            elif c == ")":
                out.append(_Tok(_RPAREN, i, i + 1, ")")); i += 1
            elif c == "[":
                out.append(_Tok(_LBRACKET, i, i + 1, "[")); i += 1
            elif c == "]":
                out.append(_Tok(_RBRACKET, i, i + 1, "]")); i += 1
            elif c in _ONE_CHAR_OPS:
                out.append(_Tok(_OP, i, i + 1, c)); i += 1
            else:
                out.append(_Tok(_BAD, i, i + 1, c)); i += 1
    return out


# --- parser (port of parse/ExprParser.kt) ---

class _ParseError(Exception):
    def __init__(self, message, start, end):
        super().__init__(message)
        self.message = message
        self.start = start
        self.end = end


class _Node:
    """Minimal AST node — only the fields the parser's own control flow needs."""
    __slots__ = ("kind", "start", "end", "name", "items", "elements")

    def __init__(self, kind, start, end, name=None, items=None, elements=None):
        self.kind = kind
        self.start = start
        self.end = end
        self.name = name
        self.items = items
        self.elements = elements


_WORD_BINARY = {"or": "||", "and": "&&", "eq": "==", "ne": "!=",
                "lt": "<", "gt": ">", "le": "<=", "ge": ">=", "div": "/", "mod": "%"}
_WORD_UNARY = {"not", "empty"}
_WORD_LITERAL = {"true", "false", "null"}
_BINARY_PREC = {"||": 1, "&&": 2, "==": 3, "!=": 3, "===": 3, "!==": 3,
                "<": 4, ">": 4, "<=": 4, ">=": 4, "+": 5, "-": 5,
                "*": 6, "/": 6, "%": 6, "|>": 7}
_UNARY_OPS = {"!", "-", "+"}


def _dialect_display(backend):
    return "Backend (${…})" if backend else "Frontend ({{…}})"


class _ExprParser:
    def __init__(self, toks, text_len, dialect):
        self._toks = toks
        self._pos = 0
        self._text_len = text_len
        self._backend = dialect == BACKEND
        self._arrow_text = "->" if self._backend else "=>"

    def _peek(self):
        return self._toks[self._pos] if self._pos < len(self._toks) else None

    def _peek_at(self, k):
        j = self._pos + k
        return self._toks[j] if 0 <= j < len(self._toks) else None

    def _advance(self):
        t = self._toks[self._pos]
        self._pos += 1
        return t

    def _eof_offset(self):
        return self._toks[-1].end if self._toks else self._text_len

    def _err_tok(self, t, msg):
        if t is None:
            raise _ParseError(msg, self._eof_offset(), self._eof_offset())
        raise _ParseError(msg, t.start, t.end)

    def _err_node(self, n, msg):
        raise _ParseError(msg, n.start, n.end)

    def parse_top(self):
        if self._peek() is None:
            self._err_tok(None, "Expected an expression")
        node = self._parse_expression()
        extra = self._peek()
        if extra is not None:
            self._err_tok(extra, "Unexpected '%s' — expected an operator or end of expression" % extra.text)
        return self._require_not_params(node)

    def _parse_expression(self):
        left = self._parse_ternary()
        arrow = self._peek()
        if arrow is not None and arrow.type == _ARROW:
            if arrow.text != self._arrow_text:
                self._err_tok(arrow, "'%s' is not valid here (%s uses '%s')"
                              % (arrow.text, _dialect_display(self._backend), self._arrow_text))
            self._advance()
            params = self._extract_params(left)
            body = self._parse_expression()
            return _Node("arrow", left.start, body.end, elements=params)
        return left

    def _parse_ternary(self):
        cond = self._parse_binary(0)
        q = self._peek()
        if q is not None and q.type == _OP and q.text == "?":
            self._advance()
            then = self._parse_expression()
            colon = self._peek()
            if colon is None or colon.type != _COLON:
                self._err_tok(colon if colon is not None else q, "Expected ':' in ternary expression")
            self._advance()
            otherwise = self._parse_expression()
            return _Node("ternary", cond.start, otherwise.end)
        return cond

    def _peek_binary_op(self):
        t = self._peek()
        if t is None:
            return None
        if t.type == _PIPE:
            return ("|>", _BINARY_PREC["|>"])
        if t.type == _OP and t.text in _BINARY_PREC and not (self._backend and t.text in ("===", "!==")):
            return (t.text, _BINARY_PREC[t.text])
        if self._backend and t.type == _IDENT and t.text in _WORD_BINARY:
            sym = _WORD_BINARY[t.text]
            return (sym, _BINARY_PREC[sym])
        return None

    def _parse_binary(self, min_prec):
        left = self._parse_unary()
        while True:
            ob = self._peek_binary_op()
            if ob is None:
                break
            op, prec = ob
            if prec < min_prec:
                break
            op_tok = self._advance()
            nxt = self._peek()
            if nxt is None:
                self._err_tok(op_tok, "Expression ends unexpectedly after '%s'" % op_tok.text)
            if nxt.type in (_RPAREN, _RBRACKET, _COMMA):
                self._err_tok(op_tok, "'%s' is missing its right operand" % op_tok.text)
            right = self._parse_binary(prec + 1)
            left = _Node("pipe" if op == "|>" else "binary", left.start, right.end)
        return left

    def _parse_unary(self):
        t = self._peek()
        if t is not None and ((t.type == _OP and t.text in _UNARY_OPS) or
                              (self._backend and t.type == _IDENT and t.text in _WORD_UNARY)):
            self._advance()
            operand = self._parse_unary()
            return _Node("unary", t.start, operand.end)
        return self._parse_postfix()

    def _parse_postfix(self):
        node = self._parse_primary()
        while True:
            t = self._peek()
            if t is None:
                break
            if t.type == _DOT:
                self._advance()
                name = self._peek()
                if name is None or name.type != _IDENT:
                    self._err_tok(name if name is not None else t, "Expected a name after '.'")
                self._advance()
                node = _Node("member", node.start, name.end, name=name.text)
            elif t.type == _LBRACKET:
                self._advance()
                self._parse_expression()
                close = self._peek()
                if close is None or close.type != _RBRACKET:
                    self._err_tok(close if close is not None else t, "Unclosed '['")
                end = self._advance().end
                node = _Node("index", node.start, end)
            elif t.type == _LPAREN:
                _args, end = self._parse_arg_list()
                node = _Node("call", node.start, end)
            else:
                break
        return node

    def _parse_primary(self):
        t = self._peek()
        if t is None:
            self._err_tok(None, "Expected an expression")
        tt = t.type
        if tt == _NUMBER or tt == _STRING:
            self._advance()
            return _Node("lit", t.start, t.end)
        if tt == _STRING_BAD:
            self._err_tok(t, "Unterminated string literal")
        if tt == _IDENT:
            return self._parse_ident_or_call(t)
        if tt == _LPAREN:
            return self._parse_paren_or_params()
        if tt == _LBRACKET:
            if not self._backend:
                return self._parse_array_literal()
            self._err_tok(t, "Array literals are not supported in backend expressions — use listOf(…)")
        if tt == _OP:
            self._err_tok(t, "'%s' is missing its left operand" % t.text)
        if tt == _PIPE:
            self._err_tok(t, "'|>' is missing its left operand")
        if tt == _ARROW:
            self._err_tok(t, "'%s' is missing its parameter" % t.text)
        if tt == _DOT:
            self._err_tok(t, "Expected an expression before '.'")
        if tt == _RPAREN:
            self._err_tok(t, "Unmatched ')'")
        if tt == _RBRACKET:
            self._err_tok(t, "Unmatched ']'")
        if tt == _COLON:
            self._err_tok(t, "Unexpected ':'")
        if tt == _COMMA:
            self._err_tok(t, "Unexpected ','")
        self._err_tok(t, "Unexpected character '%s'" % t.text)  # _BAD

    def _parse_ident_or_call(self, t):
        if (self._peek_at(1) is not None and self._peek_at(1).type == _COLON and
                self._peek_at(2) is not None and self._peek_at(2).type == _IDENT and
                self._peek_at(3) is not None and self._peek_at(3).type == _LPAREN):
            ns = self._advance()   # prefix
            self._advance()        # ':'
            self._advance()        # name
            _args, end = self._parse_arg_list()
            return _Node("nscall", ns.start, end)
        self._advance()
        if t.text in _WORD_LITERAL:
            return _Node("lit", t.start, t.end)
        return _Node("ident", t.start, t.end, name=t.text)

    def _parse_paren_or_params(self):
        open_ = self._advance()  # '('
        p = self._peek()
        if p is not None and p.type == _RPAREN:
            end = self._advance().end
            return _Node("parenparams", open_.start, end, items=[])
        first = self._parse_expression()
        p = self._peek()
        if p is not None and p.type == _COMMA:
            items = [first]
            while self._peek() is not None and self._peek().type == _COMMA:
                self._advance()
                items.append(self._parse_expression())
            close = self._peek()
            if close is None or close.type != _RPAREN:
                self._err_tok(close if close is not None else open_, "Unclosed '('")
            end = self._advance().end
            return _Node("parenparams", open_.start, end, items=items)
        close = self._peek()
        if close is None or close.type != _RPAREN:
            self._err_tok(close if close is not None else open_, "Unclosed '('")
        self._advance()
        return first

    def _parse_array_literal(self):
        open_ = self._advance()  # '['
        elements = []
        p = self._peek()
        if p is None or p.type != _RBRACKET:
            elements.append(self._parse_expression())
            while self._peek() is not None and self._peek().type == _COMMA:
                self._advance()
                if self._peek() is not None and self._peek().type == _RBRACKET:
                    break  # trailing comma tolerated
                elements.append(self._parse_expression())
        close = self._peek()
        if close is None or close.type != _RBRACKET:
            self._err_tok(close if close is not None else open_, "Unclosed '['")
        end = self._advance().end
        return _Node("array", open_.start, end, elements=elements)

    def _parse_arg_list(self):
        open_ = self._advance()  # '('
        args = []
        p = self._peek()
        if p is not None and p.type == _RPAREN:
            return [], self._advance().end
        while True:
            here = self._peek()
            if here is None:
                self._err_tok(open_, "Unclosed '('")
            if here.type in (_COMMA, _RPAREN):
                self._err_tok(here, "Empty argument")
            args.append(self._parse_expression())
            sep = self._peek()
            st = sep.type if sep is not None else None
            if st == _COMMA:
                self._advance()
                nx = self._peek()
                if nx is not None and nx.type == _RPAREN:
                    self._err_tok(nx, "Empty argument")
            elif st == _RPAREN:
                return args, self._advance().end
            elif st is None:
                self._err_tok(open_, "Unclosed '('")
            else:
                self._err_tok(sep, "Expected ',' or ')' in argument list")

    def _extract_params(self, node):
        if node.kind == "ident":
            return [node.name]
        if node.kind == "parenparams":
            names = []
            for it in node.items:
                if it.kind == "ident":
                    names.append(it.name)
                else:
                    self._err_node(it, "Arrow parameters must be plain names")
            return names
        if node.kind == "array":
            names = []
            for it in node.elements:
                if it.kind == "ident":
                    names.append(it.name)
                else:
                    self._err_node(it, "Arrow parameters must be plain names")
            return names
        self._err_node(node, "Invalid arrow parameters")

    def _require_not_params(self, node):
        if node.kind == "parenparams":
            self._err_node(node, "Unexpected ',' — a comma is only allowed inside a call or array")
        return node


def _expr_parse_error(body, dialect):
    """Return (message, start, end) of the first structural syntax error, or None if valid."""
    toks = _expr_tokenize(body)
    try:
        _ExprParser(toks, len(body), dialect).parse_top()
        return None
    except _ParseError as e:
        return (e.message, e.start, e.end)


# --- catalog (port of catalog/FlowableExpressionCatalog.kt) ---

_IDENTITY_LINK_NAMES = [
    "getAssignee", "setAssignee", "removeAssignee", "getOwner", "setOwner", "removeOwner",
    "addCandidateUser", "addCandidateUsers", "addCandidateGroup", "addCandidateGroups",
    "removeCandidateUser", "removeCandidateUsers", "removeCandidateGroup", "removeCandidateGroups",
    "addParticipantUser", "addParticipantUsers", "addParticipantGroup", "addParticipantGroups",
    "removeParticipantUser", "removeParticipantUsers", "removeParticipantGroup", "removeParticipantGroups",
    "addWatcherUser", "addWatcherUsers", "addWatcherGroup", "addWatcherGroups",
    "removeWatcherUser", "removeWatcherUsers", "removeWatcherGroup", "removeWatcherGroups",
]
_BUSINESS_NAMES = ["getBusinessKey", "setBusinessKey", "getBusinessStatus", "setBusinessStatus"]

# Canonical prefix -> every accepted local name (canonical + aliases).
_BACKEND_FUNCTIONS = {
    "variables": ["get", "getOrDefault", "contains", "containsAny", "containsAll",
                  "notContains", "notContainsAny", "notContainsAll",
                  "equals", "eq", "notEquals", "ne", "exists", "exist",
                  "isEmpty", "empty", "isNotEmpty", "notEmpty",
                  "lowerThan", "lessThan", "lt", "lowerThanOrEquals", "lessThanOrEquals", "lte",
                  "greaterThan", "gt", "greaterThanOrEquals", "gte", "base64", "makeTransient"],
    "date": ["format", "now", "toDate", "addDate", "subtractDate"],
    "task": ["get"] + _IDENTITY_LINK_NAMES,
    "bpmn": _BUSINESS_NAMES + ["copyLocalVariable", "copyLocalVariableToParent",
                               "replaceVariableInList", "triggerCaseEvaluation"] + _IDENTITY_LINK_NAMES,
    "cmmn": ["isPlanItemCompleted", "isStageCompletable"] + _BUSINESS_NAMES +
            ["copyVariable", "copyLocalVariable", "replaceVariableInList", "triggerCaseEvaluation"]
            + _IDENTITY_LINK_NAMES,
    "collection": ["allOf", "anyOf", "noneOf", "notAllOf", "containsAny", "contains",
                   "notContainsAny", "notContains"],
    "json": ["object", "array", "arrayWithSize", "addToArray"],
    "content": ["getContentItem", "getContentItemData", "getMetadataValues", "getMetadataValue",
                "getRenditionItem", "getRenditionByType", "getRenditionItemData", "getRenditionItemDataByType"],
    "userInfo": ["findUserInfo", "findBooleanUserInfo"],
    "template": ["createMessage"],
    "conversationStatus": ["unreadCountForUser", "unreadCountPerConversation"],
    "sequence": ["nextNumber", "next", "nextValue"],
}

_BACKEND_NO_PREFIX = ["listOf", "mapOf", "markdownToHtml", "findUser", "findUserAccount",
                      "isUserInAllGroups", "isUserInAnyGroup", "isUserInNoGroup",
                      "findGroupMemberUserIds", "findGroupMemberEmails", "setPlatformUserInfo",
                      "setUserState", "setUserSubState", "setUserStateAndSubState",
                      "setUserAccountState", "setUserAccountSubState", "setUserAccountStateAndSubState"]

_PREFIX_ALIASES = {
    "variables": "variables", "vars": "variables", "var": "variables",
    "date": "date", "task": "task", "cmmn": "cmmn", "collection": "collection",
    "json": "json", "bpmn": "bpmn", "content": "content", "userInfo": "userInfo",
    "template": "template", "conversationStatus": "conversationStatus",
    "sequence": "sequence", "seq": "sequence",
}

_FRONTEND_MEMBERS = ["sum", "avg", "count", "min", "max", "dotProd", "join",
                     "mapAttr", "find", "findAll", "merge", "add", "forceCollectionSize",
                     "in", "keys", "values", "remove", "array", "data",
                     "now", "currentDate", "secondsOfDay", "timeZone", "parseDate", "formatDate",
                     "formatTime", "dateAdd", "dateSubtract", "startOf", "isBefore", "isAfter",
                     "sameDate", "formattedDurationFromNow", "formattedTimeLapseBetween", "durationBetween",
                     "round", "floor", "ceil", "abs", "parseInt", "parseFloat",
                     "encode", "encodeURI", "encodeURIComponent", "JSON", "numberFormat",
                     "sanitizeHtml", "escapeHtml", "exists", "notExists",
                     # Work/platform-injected members — NOT part of the base @flowable/forms
                     # FunctionsFactory. The Work runtime merges these onto `flw` at eval time via
                     # additionalData.flw (useGlobalResolver in flowable-shared) and Form.tsx.
                     # A project can inject *further* custom functions — onto `flw` (via
                     # `flowable.externals.additionalData.flw`) or as top-level identifiers (any other
                     # `externals.additionalData` key, spread into the expression scope by
                     # `hookEvalExpression`). We can't enumerate those; the validator stays lenient
                     # about them (top-level calls are never flagged; a no-near-match `flw.<x>` is
                     # treated as custom, see `_check_functions`).
                     "getUser", "getMasterDataInstance", "getMasterDataInstanceByKey",
                     "getDataObjectInstance", "translateWorkObject", "stringify",
                     "validate", "setActiveTab", "getActiveTab"]
_FRONTEND_MEMBER_SET = set(_FRONTEND_MEMBERS)


def _resolve_prefix(prefix):
    return _PREFIX_ALIASES.get(prefix)


def _backend_prefixes():
    return list(_PREFIX_ALIASES.keys())


def _backend_names_for_prefix(prefix):
    canonical = _resolve_prefix(prefix)
    if canonical is None:
        return []
    return _BACKEND_FUNCTIONS.get(canonical, [])


def _is_backend_function(prefix, name):
    if prefix is None:
        return name in _BACKEND_NO_PREFIX
    return name in _backend_names_for_prefix(prefix)


def _is_frontend_member(name):
    return name in _FRONTEND_MEMBER_SET


# --- "did you mean" (port of inspection/Suggestions.kt) ---

def _levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        ai = a[i - 1]
        for j in range(1, len(b) + 1):
            cost = 0 if ai == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(b)]


def _closest(value, candidates):
    threshold = max(2, len(value) // 3)
    best = None
    best_d = None
    for c in candidates:
        d = _levenshtein(value, c)
        if d <= threshold and (best_d is None or d < best_d):
            best = c
            best_d = d
    return best


# --- validator (port of ExpressionValidator.kt) ---

def _expr_strip_outer_wrapper(body):
    """If body is entirely wrapped in {{ }} / ${ } / #{ }, return (inner, start_shift)."""
    start = -1
    for i, ch in enumerate(body):
        if not ch.isspace():
            start = i
            break
    if start < 0:
        return body, 0
    end = start
    for i in range(len(body) - 1, -1, -1):
        if not body[i].isspace():
            end = i + 1
            break
    core = body[start:end]
    for open_, close in (("{{", "}}"), ("${", "}"), ("#{", "}")):
        if len(core) >= len(open_) + len(close) and core.startswith(open_) and core.endswith(close):
            inner_start = start + len(open_)
            inner_end = end - len(close)
            return body[inner_start:inner_end], inner_start
    return body, 0


def _tok_type_at(toks, i):
    return toks[i].type if 0 <= i < len(toks) else None


def _hint(suggestion):
    return (" — did you mean '%s'?" % suggestion) if suggestion else ""


def _problem(t, message, severity, quick_fix=None, kind=None, subject=None):
    p = {"start": t.start, "end": t.end, "message": message, "severity": severity, "quickFix": quick_fix}
    if kind:
        p["kind"] = kind
        p["subject"] = subject
    return p


def _problem_range(start, end, message, severity, quick_fix=None):
    return {"start": start, "end": end, "message": message, "severity": severity, "quickFix": quick_fix}


def _check_dialect_operators(toks, dialect, out):
    if dialect == BACKEND:
        for t in toks:
            if t.type == _PIPE:
                out.append(_problem(t, "'|>' is a frontend-only pipe operator", "warning"))


def _check_functions(toks, dialect, out, custom=None):
    custom_flw = custom["flw"] if custom else set()
    custom_ns = custom["namespaces"] if custom else {}
    n = len(toks)
    for i in range(n):
        t = toks[i]
        # Custom namespace call: <ns> '.' IDENT '(' where <ns> was registered via
        # externals.additionalData (e.g. `flowkyc.findCommonAttribute(x)`). We know the exact
        # member set, so an unknown member with a near-match is a real typo → suspect.
        if (dialect == FRONTEND and t.type == _IDENT and t.text in custom_ns and
                _tok_type_at(toks, i + 1) == _DOT and _tok_type_at(toks, i + 2) == _IDENT and
                _tok_type_at(toks, i + 3) == _LPAREN):
            member = toks[i + 2]
            if member.text not in custom_ns[t.text]:
                suggestion = _closest(member.text, sorted(custom_ns[t.text]))
                if suggestion is not None:
                    out.append(_problem(member, "Unknown function '%s.%s'%s"
                                        % (t.text, member.text, _hint(suggestion)), "warning", suggestion,
                                        kind="unknown-function", subject=f"{t.text}.{member.text}"))
            continue
        # Backend namespaced call: IDENT ':' IDENT '('
        if (t.type == _IDENT and _tok_type_at(toks, i + 1) == _COLON and
                _tok_type_at(toks, i + 2) == _IDENT and _tok_type_at(toks, i + 3) == _LPAREN):
            prefix = t
            name = toks[i + 2]
            if dialect == FRONTEND:
                if _resolve_prefix(prefix.text) is not None:
                    out.append(_problem_range(
                        prefix.start, name.end,
                        "'%s:%s' is backend function syntax; frontend expressions use flw.%s(…)"
                        % (prefix.text, name.text, name.text), "warning"))
                continue
            canonical = _resolve_prefix(prefix.text)
            if canonical is None:
                suggestion = _closest(prefix.text, _backend_prefixes())
                out.append(_problem(prefix, "Unknown function namespace '%s'%s"
                                    % (prefix.text, _hint(suggestion)), "warning", suggestion,
                                    kind="unknown-namespace", subject=prefix.text))
            elif not _is_backend_function(prefix.text, name.text):
                suggestion = _closest(name.text, _backend_names_for_prefix(prefix.text))
                out.append(_problem(name, "Unknown function '%s:%s'%s"
                                    % (prefix.text, name.text, _hint(suggestion)), "warning", suggestion,
                                    kind="unknown-function", subject=f"{prefix.text}:{name.text}"))
            continue
        # Frontend member call: flw '.' IDENT
        if (dialect == FRONTEND and t.type == _IDENT and t.text == _FRONTEND_NS and
                _tok_type_at(toks, i + 1) == _DOT and _tok_type_at(toks, i + 2) == _IDENT):
            member = toks[i + 2]
            if not _is_frontend_member(member.text) and member.text not in custom_flw:
                suggestion = _closest(member.text, _FRONTEND_MEMBERS + sorted(custom_flw))
                # A member with no near-match to any known flw function is most likely a *custom*
                # function a project injected onto `flw` via `flowable.externals.additionalData.flw`
                # (merged in `hookEvalExpression`). If we extracted that source (custom_flw) the name
                # is known and validates cleanly; otherwise it's invisible to us, so don't flag it.
                # Only a plausible typo (`flw.sim` → `sum`) is surfaced, and always as a suspect.
                if suggestion is not None:
                    out.append(_problem(member, "Unknown flw function 'flw.%s'%s"
                                        % (member.text, _hint(suggestion)), "warning", suggestion,
                                        kind="unknown-function", subject=f"flw.{member.text}"))


def validate_expression(body, dialect, custom=None):
    """Faithful port of ExpressionValidator.validate. Returns a list of problem dicts (empty == valid).
    `custom` is an optional extracted `externals.additionalData` catalog (see extract_custom_functions)
    that lets known custom namespaces/flw members validate precisely instead of staying lenient."""
    inner, shift = _expr_strip_outer_wrapper(body)
    # An empty interpolation (`{{}}`, `${}`) or blank body is a runtime no-op — the frontend
    # silently ignores an expression that yields nothing — so there is nothing to flag.
    if not inner.strip():
        return []
    toks = _expr_tokenize(inner)
    problems = []
    err = _expr_parse_error(inner, dialect)          # Layer 1 — structural syntax (ERROR)
    if err is not None:
        msg, s, e = err
        problems.append(_problem_range(s, e, msg, "error"))
    _check_dialect_operators(toks, dialect, problems)  # Layer 2 — semantic (WARNING)
    _check_functions(toks, dialect, problems, custom)
    if shift:
        for p in problems:
            p["start"] += shift
            p["end"] += shift
    problems.sort(key=lambda p: p["start"])
    return problems


# Model types that render ${…} as Freemarker (e.g. ${x?json_string}, ${x!default}) rather than
# JUEL — query models, content/email templates, document templates. Their expressions must NOT be
# validated as JUEL; the IntelliJ plugin likewise never injects the expression language into them.
_FREEMARKER_MODEL_TYPES = {"query", "template", "document"}


def _decode_json_string_escapes(s):
    """Reverse the backslash escaping that survives when an expression is harvested from a JSON
    model (.form / .page): `\\"` → `"`, `\\\\` → `\\`, `\\n` → newline, etc. The harvester captures
    raw file text, but the browser evaluates the JSON-decoded string — so `{{x["a"]}}` reaches us as
    `{{x[\\"a\\"]}}` and must be decoded before it parses. A no-op when there is no backslash."""
    if "\\" not in s:
        return s
    out = []
    i = 0
    n = len(s)
    simple = {'"': '"', "\\": "\\", "/": "/", "n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f"}
    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n:
            out.append(simple.get(s[i + 1], s[i + 1]))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def expr_problem_allowlisted(p, allow):
    """True when a catalog finding refers to a namespace/function the user declared as
    project-provided (--expr-allowlist ns,ns:fn,flw.member). Allowlisting a namespace
    covers every function under it; structural syntax errors are never suppressed."""
    subj = p.get("subject")
    if not subj or p.get("severity") == "error":
        return False
    if subj in allow:
        return True
    for sep in (":", "."):
        if sep in subj and subj.split(sep, 1)[0] in allow:
            return True
    return False


# ---------------------------------------------------------------------------
# Custom frontend function catalog — read from a project's
# `flowable.externals.additionalData` source (the frontend-customization module).
#
# The Work runtime spreads `externals.additionalData` into the frontend expression
# scope (its top-level keys) and merges `additionalData.flw` onto `flw` (see
# `hookEvalExpression`/`useGlobalResolver`). Projects register their own functions
# there — e.g. the KYC app exposes a `flowkyc` namespace of helpers, called as
# `{{ flowkyc.foo(x) }}`. When that source is inside the scanned tree we can read
# the real names and validate calls *precisely* (known member → valid, close typo →
# suspect) instead of staying blanket-lenient.
#
# Best-effort STATIC parsing: `ext/custom.js` is usually a compiled bundle we can't
# read, so we anchor on readable source (`export default { … additionalData … }`, or
# a direct `externals.additionalData = { … }`). Anything we can't resolve (spreads,
# computed keys, dynamic assembly) is recorded in `diagnostics`, never guessed.
# ---------------------------------------------------------------------------

_CUSTOM_SKIP_DIRS = {"node_modules", "dist", "build", "target", ".git", ".idea",
                     ".gradle", "coverage", "storybook-static", "__pycache__", ".next"}
_CUSTOM_SRC_EXT = (".ts", ".tsx", ".js", ".jsx", ".mjs")
# NB: used with re.match(masked, pos, end) — match() already anchors at pos, and a leading `^`
# would (per the pos semantics) only match at the real string start, so it must be omitted.
_IDENT_KEY_RE = re.compile(r"""\s*(?:['"]([A-Za-z_$][\w$]*)['"]|([A-Za-z_$][\w$]*))\s*""")


def _ts_mask(text):
    """Return `text` with comment and string/template *contents* blanked to spaces (length
    preserved, delimiters kept) so brace/comma/colon scanning isn't fooled by punctuation
    inside strings or comments. Newlines are preserved."""
    out = list(text)
    i, n = 0, len(text)
    state = None  # None | "line" | "block" | "'" | '"' | "`"
    while i < n:
        c = text[i]
        if state is None:
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                out[i] = out[i + 1] = " "; i += 2; state = "line"; continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                out[i] = out[i + 1] = " "; i += 2; state = "block"; continue
            if c in "'\"`":
                state = c; i += 1; continue
        elif state == "line":
            if c == "\n":
                state = None
            else:
                out[i] = " "
            i += 1; continue
        elif state == "block":
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                out[i] = out[i + 1] = " "; i += 2; state = None; continue
            if c != "\n":
                out[i] = " "
            i += 1; continue
        else:  # inside a '/"/` string
            if c == "\\" and i + 1 < n:
                out[i] = " "
                if text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2; continue
            if c == state:
                state = None; i += 1; continue
            if c != "\n":
                out[i] = " "
            i += 1; continue
        i += 1
    return "".join(out)


def _match_brace(masked, open_idx):
    """`masked[open_idx]` is an opening `{`/`(`/`[`; return the index of its matching close, or -1."""
    depth = 0
    for i in range(open_idx, len(masked)):
        c = masked[i]
        if c in "{([":
            depth += 1
        elif c in "})]":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _object_entries(masked, brace_open, brace_close):
    """Yield (start, end) spans of the top-level, comma-separated entries of the object literal
    whose braces are at `brace_open`/`brace_close` (depth-aware over () [] {})."""
    depth = 0
    s = brace_open + 1
    for i in range(brace_open + 1, brace_close):
        c = masked[i]
        if c in "{([":
            depth += 1
        elif c in "})]":
            depth -= 1
        elif c == "," and depth == 0:
            yield (s, i)
            s = i + 1
    if masked[s:brace_close].strip():
        yield (s, brace_close)


def _entry_key_and_value_kind(masked, orig, s, e):
    """Parse one object entry span. Returns (key, kind) where kind is:
    ('object', open, close) | ('plain',) | ('spread',) | ('computed',) | ('none',).
    Structure is read from `masked`; the key *name* is read from `orig` (a quoted key like
    `"foo"` is blanked in `masked`, so its characters survive only in the original text)."""
    seg = masked[s:e]
    stripped = seg.lstrip()
    lead = s + (len(seg) - len(stripped))
    if stripped.startswith("..."):
        return (None, ("spread",))
    if stripped.startswith("["):
        return (None, ("computed",))
    m = _IDENT_KEY_RE.match(orig, lead, e)
    if not m:
        return (None, ("none",))
    key = m.group(1) or m.group(2)
    j = m.end()
    while j < e and masked[j].isspace():
        j += 1
    if j >= e:                       # shorthand property (`getFoo,`)
        return (key, ("plain",))
    if masked[j] == "(":             # method shorthand (`getFoo() {}`)
        return (key, ("plain",))
    if masked[j] == ":":             # `key: value`
        j += 1
        while j < e and masked[j].isspace():
            j += 1
        if j < e and masked[j] == "{":
            close = _match_brace(masked, j)
            if close != -1:
                return (key, ("object", j, close))
        return (key, ("plain",))
    return (key, ("plain",))


def _paren_text(masked, orig, open_paren):
    """Text between `masked[open_paren]=='('` and its match, whitespace-collapsed and comma-spaced
    (read from orig so real names survive masking); None if unbalanced. Minified sources have no
    spaces, so `e,t,r` reads back as `e, t, r`."""
    close = _match_brace(masked, open_paren)
    if close == -1:
        return None
    return re.sub(r"\s*,\s*", ", ", re.sub(r"\s+", " ", orig[open_paren + 1:close].strip()))


def _resolve_fn_signature(masked, orig, name):
    """Parameter text of a same-file `function name(…)`, `… name = function(…)`, `… name = (…) =>`,
    or `… name = x =>` declaration — used for compiled bundles where a member is an identifier ref
    (`findCommon: findCommon`) to a named function. None if not found."""
    n = re.escape(name)
    for pat in (r"\bfunction\s+" + n + r"\s*\(",
                r"\b(?:var|let|const)\s+" + n + r"\s*=\s*function\b\s*(?:[A-Za-z_$][\w$]*\s*)?\(",
                r"\b(?:var|let|const)\s+" + n + r"\s*=\s*\("):
        m = re.search(pat, masked)
        if m:
            return _paren_text(masked, orig, m.end() - 1)   # the '(' is the last matched char
    m = re.search(r"\b(?:var|let|const)\s+" + n + r"\s*=\s*([A-Za-z_$][\w$]*)\s*=>", masked)
    return m.group(1) if m else None


def _value_signature(masked, orig, j, end):
    """Parameter text of a value expression starting at j: a function/arrow literal, or a bare
    identifier resolved to its same-file declaration. None if not function-like."""
    if j >= end:
        return None
    tail = masked[j:end]
    fm = re.match(r"function\b\s*(?:[A-Za-z_$][\w$]*\s*)?", tail)
    if fm:
        k = j + fm.end()
        return _paren_text(masked, orig, k) if k < end and masked[k] == "(" else None
    if masked[j] == "(":                                   # (a, b) => …
        return _paren_text(masked, orig, j)
    im = re.match(r"([A-Za-z_$][\w$]*)\s*=>", tail)
    if im:                                                 # x => …
        return im.group(1)
    im2 = re.match(r"([A-Za-z_$][\w$]*)\s*$", tail)         # bare identifier reference
    return _resolve_fn_signature(masked, orig, im2.group(1)) if im2 else None


def _member_signature(masked, orig, s, e):
    """Best-effort parameter text of an object entry whose value is a function (method shorthand,
    arrow, function expression, or a resolvable identifier ref). None when it isn't a function."""
    key, kind = _entry_key_and_value_kind(masked, orig, s, e)
    if key is None or kind[0] == "object":
        return None
    seg = masked[s:e]
    lead = s + (len(seg) - len(seg.lstrip()))
    m = _IDENT_KEY_RE.match(orig, lead, e)
    if not m:
        return None
    j = m.end()
    while j < e and masked[j].isspace():
        j += 1
    if j < e and masked[j] == "(":                         # method shorthand `key(params) {}`
        return _paren_text(masked, orig, j)
    if j < e and masked[j] == ":":                         # `key: <value>`
        j += 1
        while j < e and masked[j].isspace():
            j += 1
        return _value_signature(masked, orig, j, e)
    return None


def _object_member_names(masked, orig, brace_open, brace_close, diagnostics, ctx, sigs=None, prefix=""):
    """Collect the top-level key names of an object literal (its members). When [sigs] is given,
    also record each function member's parameter text under `prefix + key` (best effort)."""
    names = set()
    for (s, e) in _object_entries(masked, brace_open, brace_close):
        key, kind = _entry_key_and_value_kind(masked, orig, s, e)
        if key is not None:
            names.add(key)
            if sigs is not None:
                params = _member_signature(masked, orig, s, e)
                if params is not None:
                    sigs[prefix + key] = params
        elif kind[0] in ("spread", "computed"):
            diagnostics.append(f"{ctx}: unresolved {kind[0]} entry (members may be incomplete)")
    return names


def _find_export_default_object(masked):
    """Return (open_idx, close_idx) of the object literal in `export default { … }`, or None."""
    for m in re.finditer(r"export\s+default\s*", masked):
        i = m.end()
        while i < len(masked) and masked[i].isspace():
            i += 1
        if i < len(masked) and masked[i] == "{":
            close = _match_brace(masked, i)
            if close != -1:
                return (i, close)
    return None


def _object_value_for_key(masked, orig, brace_open, brace_close, key):
    """Find `key` in the object literal and describe its value:
    ('object', open, close) | ('ident', name) | ('plain',) | None (absent)."""
    for (s, e) in _object_entries(masked, brace_open, brace_close):
        k, kind = _entry_key_and_value_kind(masked, orig, s, e)
        if k != key:
            continue
        if kind[0] == "object":
            return ("object", kind[1], kind[2])
        # shorthand (`additionalData`) or `additionalData: ad` → the value is an identifier binding
        seg = orig[s:e]
        after = seg.split(":", 1)[1] if ":" in seg else k
        m = re.match(r"\s*([A-Za-z_$][\w$]*)\s*$", after)
        if m:
            return ("ident", m.group(1))
        return ("plain",)
    return None


def _resolve_import(from_file, binding, orig_text):
    """Resolve `import <binding> from "…"` / `import { …, <binding>, … } from "…"` to a project
    file path (relative specifiers only — bare package imports aren't project source)."""
    b = re.escape(binding)
    for pat in (r"import\s+" + b + r"\s+from\s*['\"]([^'\"]+)['\"]",
                r"import\s*\{[^}]*\b" + b + r"\b[^}]*\}\s*from\s*['\"]([^'\"]+)['\"]"):
        m = re.search(pat, orig_text)
        if m:
            spec = m.group(1)
            if not spec.startswith("."):
                return None
            base = os.path.normpath(os.path.join(os.path.dirname(from_file), spec))
            for cand in ([base + ext for ext in _CUSTOM_SRC_EXT] +
                         [os.path.join(base, "index" + ext) for ext in _CUSTOM_SRC_EXT]):
                if os.path.isfile(cand):
                    return cand
            return None
    return None


def _absorb_additional_data_object(masked, orig, brace_open, brace_close, cat, ctx):
    """Read the members of the resolved `additionalData` object literal into `cat`."""
    for (s, e) in _object_entries(masked, brace_open, brace_close):
        key, kind = _entry_key_and_value_kind(masked, orig, s, e)
        if key is None:
            if kind[0] in ("spread", "computed"):
                cat["diagnostics"].append(f"{ctx}: unresolved {kind[0]} in additionalData (custom names may be incomplete)")
            continue
        sigs = cat.get("signatures")
        if kind[0] == "object":
            members = _object_member_names(masked, orig, kind[1], kind[2], cat["diagnostics"],
                                           f"{ctx}.{key}", sigs=sigs, prefix=f"{key}.")
            if key == "flw":
                cat["flw"].update(members)          # merged onto the flw namespace
            else:
                cat["namespaces"].setdefault(key, set()).update(members)
        else:
            cat["top_level"].add(key)               # a bare top-level callable/value
            if sigs is not None:
                params = _member_signature(masked, orig, s, e)
                if params is not None:
                    sigs[key] = params


def _entry_value_is_fn_or_obj(masked, orig, s, e):
    """True when an object entry's VALUE is an object literal or a function (arrow / `function` /
    method shorthand). Used to tell a real `additionalData` registration (namespaces of functions)
    from an incidental `additionalData` object such as a React `<Form additionalData={{…}}>` prop,
    whose values are data/identifiers (`currentUser: props.currentUser`)."""
    key, kind = _entry_key_and_value_kind(masked, orig, s, e)
    if key is None:
        return False
    if kind[0] == "object":
        return True
    seg = masked[s:e]
    if "=>" in seg or re.search(r"\bfunction\b", seg):
        return True
    # method shorthand: `name(...) { … }`
    lead = s + (len(seg) - len(seg.lstrip()))
    m = _IDENT_KEY_RE.match(orig, lead, e)
    if m:
        j = m.end()
        while j < e and masked[j].isspace():
            j += 1
        if j < e and masked[j] == "(":
            return True
    return False


def _object_is_registration_shaped(masked, orig, brace_open, brace_close):
    """An `additionalData` object is a plausible function registration when at least one member's
    value is an object (a namespace like `flw`/`flowkyc`) or a function. Empty objects and pure
    data props are not — this guards the low-confidence `additionalData: { … }` property case."""
    for (s, e) in _object_entries(masked, brace_open, brace_close):
        if _entry_value_is_fn_or_obj(masked, orig, s, e):
            return True
    return False


def _resolve_ident_object(masked, orig, name):
    """Find `name = { … }` (any declarator position) whose object is a plausible additionalData
    registration (registration-shaped), and return its (open, close). Handles the common minified
    bundle shape where the config is a local var — `var …,a={flowkyc:{…}}` referenced by
    `return {…, additionalData:a}`. Prefers a shaped object over incidental `name={…}` assignments
    (catch-block `a={error:e}`, etc.)."""
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*=\s*", masked):
        j = m.end()
        if j < len(masked) and masked[j] == "{":
            close = _match_brace(masked, j)
            if close != -1 and _object_is_registration_shaped(masked, orig, j, close):
                return (j, close)
    return None


def _split_top_level_commas(s):
    """Split on commas not nested inside () [] {} <> (so a type like `Record<string, number>` or a
    default `= [1, 2]` stays one parameter)."""
    out, depth, start = [], 0, 0
    for i, c in enumerate(s):
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth = max(0, depth - 1)
        elif c == "," and depth == 0:
            out.append(s[start:i]); start = i + 1
    out.append(s[start:])
    return out


def _clean_param_list(raw):
    """Reduce a raw TS/JS parameter list to comma-separated bare names (drop type annotations and
    defaults; keep `...rest` and a trailing `?` for optional): `allItems: any[], path: string,
    identifierPath?: string` → `allItems, path, identifierPath?`."""
    names = []
    for seg in _split_top_level_commas(raw):
        seg = seg.strip()
        if not seg:
            continue
        m = re.match(r"(\.\.\.)?\s*([A-Za-z_$][\w$]*)\s*(\??)", seg)
        names.append((m.group(1) or "") + m.group(2) + (m.group(3) or "") if m else seg)
    return ", ".join(names)


def _scan_source_signatures(src, sigs):
    """Record {funcName: cleaned params} for top-level function / const-arrow / const-function defs in
    one (original, non-minified) source file. First definition wins."""
    masked = _ts_mask(src)

    def rec(name, open_paren):
        raw = _paren_text(masked, src, open_paren)
        if raw is not None:
            sigs.setdefault(name, _clean_param_list(raw))

    for m in re.finditer(r"\bfunction\s+([A-Za-z_$][\w$]*)\s*\(", masked):
        rec(m.group(1), m.end() - 1)
    for m in re.finditer(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*", masked):
        name, j = m.group(1), m.end()
        fm = re.match(r"function\b\s*(?:[A-Za-z_$][\w$]*\s*)?", masked[j:])
        if fm and j + fm.end() < len(masked) and masked[j + fm.end()] == "(":
            rec(name, j + fm.end())
        elif j < len(masked) and masked[j] == "(":
            rec(name, j)
        else:
            am = re.match(r"([A-Za-z_$][\w$]*)\s*=>", masked[j:])
            if am:
                sigs.setdefault(name, am.group(1))


def _find_sourcemap(bundle_path, text):
    """The sibling sourcemap of a bundle: the last `sourceMappingURL=` comment (if a readable file),
    else `<bundle>.map`. None when there isn't one."""
    url = None
    for m in re.finditer(r"sourceMappingURL=([^\s'\"]+)", text):
        url = m.group(1)
    if url and not url.startswith("data:"):
        cand = os.path.normpath(os.path.join(os.path.dirname(bundle_path), url))
        if os.path.isfile(cand):
            return cand
    cand = bundle_path + ".map"
    return cand if os.path.isfile(cand) else None


def _signatures_from_sourcemap(bundle_path, text):
    """Real {funcName: params} scanned from a bundle's sourcemap `sourcesContent` (the embedded
    original sources) — recovers the parameter names that minification renamed to `e,t,r`."""
    mp = _find_sourcemap(bundle_path, text)
    if not mp:
        return {}
    try:
        if os.path.getsize(mp) > 20_000_000:
            return {}
        with open(mp, "r", encoding="utf-8", errors="replace") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    sigs = {}
    for content in (data.get("sourcesContent") or []):
        if isinstance(content, str) and len(content) < 2_000_000:
            _scan_source_signatures(content, sigs)
    return sigs


def _apply_source_signatures(cat, smap):
    """Override extracted signatures with the real (source) ones, matched by member simple name."""
    if not smap:
        return
    def upd(display, name):
        if smap.get(name):
            cat["signatures"][display] = smap[name]
    for ns, members in cat["namespaces"].items():
        for mm in members:
            upd(f"{ns}.{mm}", mm)
    for mm in cat["flw"]:
        upd(f"flw.{mm}", mm)
    for mm in cat["top_level"]:
        upd(mm, mm)


def _fallback_additional_data_spans(masked):
    """Yield (brace_open, brace_close, high_confidence) for `additionalData` object literals that the
    primary anchors miss — chiefly the compiled Rollup bundle in `static/ext/custom.js`
    (`var additionalData = { … }`, from `export default { …, additionalData }`) and nested config
    (`externals: { additionalData: { … } }`). A leading-dot `.additionalData = {…}` is left to the
    primary pass. High confidence = a `var/let/const additionalData =` declaration (never a JSX prop,
    which is always `additionalData:`); property/bare-assignment spans are low confidence and the
    caller applies [_object_is_registration_shaped]."""
    for m in re.finditer(r"\badditionalData\b", masked):
        p = m.start()
        b = p - 1
        while b >= 0 and masked[b].isspace():
            b -= 1
        if b >= 0 and masked[b] == ".":
            continue                                 # `.additionalData` → primary pass / member access
        j = m.end()
        while j < len(masked) and masked[j].isspace():
            j += 1
        if j >= len(masked) or masked[j] not in "=:":
            continue
        j += 1
        while j < len(masked) and masked[j].isspace():
            j += 1
        if j >= len(masked) or masked[j] != "{":
            continue
        close = _match_brace(masked, j)
        if close == -1:
            continue
        high = re.search(r"\b(?:var|let|const)\s+$", masked[:p]) is not None
        yield (j, close, high)


def _absorb_from_file(path, cat, seen, root):
    """Try to read a custom-function catalog out of one source file. Returns True on success."""
    if path in seen or not os.path.isfile(path):
        return False
    seen.add(path)
    try:
        if os.path.getsize(path) > 2_000_000:
            return False
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            orig = fh.read()
    except OSError:
        return False
    masked = _ts_mask(orig)
    rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
    handled = False
    # (1) `export default { … additionalData … }` — the externals config object.
    dflt = _find_export_default_object(masked)
    if dflt:
        val = _object_value_for_key(masked, orig, dflt[0], dflt[1], "additionalData")
        if val and val[0] == "object":
            _absorb_additional_data_object(masked, orig, val[1], val[2], cat, rel)
            cat["sources"].append(rel)
            handled = True
        elif val and val[0] == "ident":
            target = _resolve_import(path, val[1], orig)
            if target and target not in seen:
                seen.add(target)
                try:
                    with open(target, "r", encoding="utf-8", errors="replace") as fh:
                        t_orig = fh.read()
                    t_masked = _ts_mask(t_orig)
                    t_dflt = _find_export_default_object(t_masked)
                    if t_dflt:
                        t_rel = os.path.relpath(target, root) if os.path.isdir(root) else os.path.basename(target)
                        _absorb_additional_data_object(t_masked, t_orig, t_dflt[0], t_dflt[1], cat, t_rel)
                        cat["sources"].append(t_rel)
                        handled = True
                except OSError:
                    pass
    # (2) Direct assignment: `externals(.default)?.additionalData = { … }` (dot guards against JSX props).
    if not handled:
        for m in re.finditer(r"\.additionalData\s*=\s*", masked):
            j = m.end()
            if j < len(masked) and masked[j] == "{":
                close = _match_brace(masked, j)
                if close != -1:
                    _absorb_additional_data_object(masked, orig, j, close, cat, rel)
                    cat["sources"].append(rel)
                    handled = True
                    break
    # (3) Fallback for compiled bundles / nested config: `var additionalData = { … }` (Rollup output
    #     of `export default { …, additionalData }`) or a bare `additionalData: { … }` property. The
    #     property case is guarded so a React `<Form additionalData={{…}}>` prop is not mistaken for a
    #     registration. Absorbs every distinct span (a bundle may inline more than one).
    if not handled:
        absorbed = set()
        for (o, c, high) in _fallback_additional_data_spans(masked):
            if o in absorbed:
                continue
            if not high and not _object_is_registration_shaped(masked, orig, o, c):
                continue
            _absorb_additional_data_object(masked, orig, o, c, cat, rel)
            absorbed.add(o)
            handled = True
        # (3b) `additionalData: <identifier>` → resolve the identifier to its object assignment. This is
        #      the minified-bundle shape: `var …,a={flowkyc:{…}}` … `return {…, additionalData:a}`.
        if not handled:
            for m in re.finditer(r"\badditionalData\b\s*[:=]\s*([A-Za-z_$][\w$]*)", masked):
                span = _resolve_ident_object(masked, orig, m.group(1))
                if span:
                    _absorb_additional_data_object(masked, orig, span[0], span[1], cat, rel)
                    handled = True
                    break
        if handled:
            cat["sources"].append(rel)
    # Recover real parameter names from a bundle's sourcemap (minification renamed them to e,t,r).
    if handled:
        _apply_source_signatures(cat, _signatures_from_sourcemap(path, orig))
    return handled


def _custom_entry_candidates(root, explicit):
    """Files that plausibly register `externals.additionalData`, most-specific first."""
    if explicit:
        if os.path.isfile(explicit):
            return [explicit]
        if os.path.isdir(explicit):
            for ext in _CUSTOM_SRC_EXT:
                idx = os.path.join(explicit, "index" + ext)
                if os.path.isfile(idx):
                    return [idx]
            root = explicit  # fall through to a walk of the given dir
        else:
            return []
    if not os.path.isdir(root):
        return []
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in _CUSTOM_SKIP_DIRS]
        for fn in filenames:
            if not fn.endswith(_CUSTOM_SRC_EXT):
                continue
            fp = os.path.join(dirpath, fn)
            try:
                if os.path.getsize(fp) > 2_000_000:
                    continue
                with open(fp, "r", encoding="utf-8", errors="replace") as fh:
                    head = fh.read()
            except OSError:
                continue
            if "additionalData" in head and ("export default" in head or ".additionalData" in head
                                             or "externals" in head
                                             or re.search(r"additionalData\s*[:=]\s*\{", head)):
                hits.append(fp)
    return hits


def extract_custom_functions(root, explicit=None):
    """Scan a project tree for its `externals.additionalData` custom functions. Returns a catalog
    dict {namespaces, top_level, flw, sources, diagnostics} or None when nothing is found."""
    cat = {"namespaces": {}, "top_level": set(), "flw": set(), "sources": [], "diagnostics": [],
           "signatures": {}}
    seen = set()
    for f in _custom_entry_candidates(root, explicit):
        try:
            _absorb_from_file(f, cat, seen, root)
        except Exception as e:  # noqa: BLE001 — extraction must never abort a run
            cat["diagnostics"].append(f"custom-extract error in {f}: {e}")
    if not (cat["namespaces"] or cat["top_level"] or cat["flw"]):
        return None
    return cat


def custom_functions_summary(cat):
    """A one-line, human-readable digest of an extracted custom-function catalog."""
    if not cat:
        return ""
    parts = [f"{ns}.* ({len(m)})" for ns, m in sorted(cat["namespaces"].items())]
    if cat["flw"]:
        parts.append(f"flw.* (+{len(cat['flw'])})")
    if cat["top_level"]:
        parts.append(f"{len(cat['top_level'])} top-level")
    src = f" from {cat['sources'][0]}" if cat["sources"] else ""
    return ", ".join(parts) + src


def custom_function_entries(cat):
    """Flatten a custom-function catalog into (display, kind, namespace, member) tuples — one per
    callable, sorted. `display` is what a binding writes: `ns.member`, `flw.member`, or a bare
    top-level name."""
    out = []
    for ns in sorted(cat["namespaces"]):
        for m in sorted(cat["namespaces"][ns]):
            out.append((f"{ns}.{m}", "namespace", ns, m))
    for m in sorted(cat["flw"]):
        out.append((f"flw.{m}", "flw", "flw", m))
    for fn in sorted(cat["top_level"]):
        out.append((fn, "top-level", None, fn))
    return out


def custom_fns_called_in(text, cat):
    """Display names of the project's custom functions called in a frontend binding body. Namespaced
    (`ns.member` / `flw.member`) calls are matched qualified (high confidence); a top-level function
    must appear as a call `fn(` to avoid matching a same-named variable."""
    if not cat:
        return set()
    body = _decode_json_string_escapes(html.unescape(text))
    found = set()
    for ns, members in cat["namespaces"].items():
        for m in members:
            if re.search(r"\b" + re.escape(ns) + r"\s*\.\s*" + re.escape(m) + r"\b", body):
                found.add(f"{ns}.{m}")
    for m in cat["flw"]:
        if re.search(r"\bflw\s*\.\s*" + re.escape(m) + r"\b", body):
            found.add(f"flw.{m}")
    for fn in cat["top_level"]:
        if re.search(r"\b" + re.escape(fn) + r"\s*\(", body):
            found.add(fn)
    return found


def validate_harvested_expr(text, dialect, custom=None):
    """Validate a harvested expression/binding node. Returns a list of problems (empty == valid),
    or None when the harvester likely truncated the body — EXPR_RE / MUSTACHE_RE stop at the first
    '}', so a nested object/map literal (`${f({'a':1})}`) leaves a stray '{' after the wrapper is
    stripped; such a body cannot be validated reliably, so it is skipped rather than mis-flagged.

    The body is decoded to what the engine actually parses: XML entities (`&quot;` → `"`,
    `&amp;&amp;` → `&&`) from BPMN/CMMN attribute & element text, then JSON backslash escapes
    (`\\"` → `"`) from .form / .page JSON models."""
    body = _decode_json_string_escapes(html.unescape(text))
    inner, _shift = _expr_strip_outer_wrapper(body)
    if "{" in inner:
        return None
    problems = validate_expression(body, dialect, custom)
    for p in problems:
        p["snippet"] = body[p["start"]:p["end"]] if 0 <= p["start"] < p["end"] <= len(body) else ""
    return problems


def _build_graph(result, ctx, resolved, all_java, bean_methods, by_key, expr_allowlist=None, custom=None):
    nodes, key_to_node = {}, {}

    def add_node(ntype, key, label, file, data):
        if key is None:
            return None
        nid = f"{ntype}:{key}"
        if nid not in nodes:
            nodes[nid] = {"id": nid, "type": ntype, "label": label or key,
                          "key": key, "file": file, "data": data}
        key_to_node.setdefault(key, nid)
        return nid

    # --- model nodes from buckets. Node type comes from the registry; forms/pages
    # carry their own modelType (a .page must not be labelled "form"). The explicit
    # order is semantic: key_to_node is first-wins, so on a key collision across
    # kinds the earlier bucket claims the key. ---
    _node_type = {b: n for _, b, n in MODEL_KINDS}
    for bucket in ("apps", "processes", "cases", "decisions", "forms", "dataObjects",
                   "services", "agents", "channels", "events", "dictionaries",
                   "policies", "actions"):
        for o in result[bucket]:
            ntype = o.get("modelType", "form") if bucket == "forms" else _node_type[bucket]
            add_node(ntype, o.get("key"), o.get("name"), o.get("file"), o)
    for lb in result["liquibase"]:
        add_node("liquibase", lb.get("key"), os.path.basename(lb["file"]), lb["file"],
                 {"tables": lb.get("tables"), "effectiveTables": lb.get("effectiveTables"),
                  "columns": lb.get("columns"), "coverage": lb.get("coverage"),
                  "authority": lb.get("authority")})
    for o in result["others"]:
        add_node(o.get("modelType", "other"), o.get("key"), o.get("name"), o.get("file"), o)

    # all model keys (for CODE -> MODEL references: Java string literals == a model key);
    # liquibase keys are file-derived, not model keys, so that bucket stays out
    model_keys = set()
    for bucket in MODEL_BUCKETS:
        if bucket == "liquibase":
            continue
        for o in result[bucket]:
            if o.get("key"):
                model_keys.add(o["key"])

    # --- which java classes are referenced by models (so we can include them) ---
    referenced_java = {r["targetFqn"] for r in resolved if r.get("targetFqn")}
    # functional roles: a bean wired via delegateExpression / a listener expression
    # IS a delegate / listener even without implementing the marker interface.
    delegate_fqns, listener_fqns = set(), set()
    for r in resolved:
        if not r.get("targetFqn"):
            continue
        if r["rel"] in ("serviceTask-delegate", "task-delegate"):
            delegate_fqns.add(r["targetFqn"])
        elif r["rel"].startswith(("executionListener", "taskListener", "planItemLifecycleListener")):
            listener_fqns.add(r["targetFqn"])

    def called_methods_for(jc):
        out = set()
        for b in (jc["beanNames"] | {jc["primary"][:1].lower() + jc["primary"][1:]}):
            out |= bean_methods.get(b, set())
        return sorted(out)

    def java_node(jc):
        roles = set(jc["roles"])
        if jc["fqn"] in delegate_fqns:
            roles.add("delegate")
        if jc["fqn"] in listener_fqns:
            roles.add("listener")
        roles.discard("other") if len(roles) > 1 else None
        data = {"fqn": jc["fqn"], "package": jc["package"], "file": jc["file"],
                "line": jc["line"], "roles": sorted(roles), "beanNames": sorted(jc["beanNames"]),
                "interfaces": sorted(jc["interfaces"]), "endpoints": jc["endpoints"], "botKey": jc.get("botKey"),
                "methods": jc.get("methods", []), "calledMethods": called_methods_for(jc)}
        return add_node("java", jc["fqn"], jc["primary"], jc["file"], data)

    for fqn, jc in all_java.items():
        if ((jc["roles"] - {"other"}) or fqn in referenced_java or jc.get("vars")
                or (jc.get("strings", set()) & model_keys)):
            java_node(jc)

    # index java simple-name -> fqn for dependency (DI) edges
    simple_to_fqn = {}
    for fqn, jc in all_java.items():
        simple_to_fqn.setdefault(jc["primary"], fqn)

    # --- endpoint nodes (+ controller -> endpoint "serves" edge added later) ---
    def endpoint_node(ep):
        key = f"{ep['http']} {ep['path']}"
        return add_node("endpoint", key, key, ep.get("file"),
                        {"controller": ep.get("controller"), "handler": ep.get("handler"),
                         "line": ep.get("line"), "http": ep["http"], "path": ep["path"]})
    for ep in result["endpoints"]:
        endpoint_node(ep)

    # --- group nodes ---
    for g in ctx["groups"]:
        add_node("group", g, g, None, {})

    # --- expression / binding nodes (with the models that use them) ---
    def _usage_nodes(ntype, usage):
        dialect = BACKEND if ntype == "expression" else FRONTEND
        for text, keys in usage.items():
            used = sorted({key_to_node[k] for k in keys if k in key_to_node})
            if used:
                data = {"usedBy": used}
                # Skip expressions that live only in Freemarker contexts (query/template/document
                # models) — ${x?json_string} etc. is not JUEL and would only produce false errors.
                used_types = {nodes[uid]["type"] for uid in used if uid in nodes}
                if not used_types.issubset(_FREEMARKER_MODEL_TYPES):
                    problems = validate_harvested_expr(text, dialect, custom)   # None == not validated (truncated)
                    if problems and expr_allowlist:
                        problems = [p for p in problems
                                    if not expr_problem_allowlisted(p, expr_allowlist)]
                    if problems:
                        data["problems"] = problems
                add_node(ntype, text, text, None, data)
    _usage_nodes("expression", ctx["expr_use"])     # backend  ${ } / #{ }
    _usage_nodes("binding", ctx["mustache_use"])     # frontend {{ }}

    # --- variable nodes: distinct variable identifiers, the concrete expressions/
    # bindings they appear in (per model), and which model TYPES use them (scope). ---
    # Beans are NOT variables: drop platform beans, delegate/method-call beans, Java beans.
    beans = set(FLOWABLE_PLATFORM_BEANS) | set(bean_methods.keys())
    beans.update(r["value"] for r in ctx["refs"] if r["kind"] == "bean")
    for jc in all_java.values():
        beans.update(jc.get("beanNames") or [])
        if jc.get("primary"):
            beans.add(jc["primary"][:1].lower() + jc["primary"][1:])

    var_usages = {}   # var -> {model_key: set(snippet)}

    def add_usage(v, k, snippet):
        if v in beans:
            return
        var_usages.setdefault(v, {}).setdefault(k, set()).add(snippet)

    for expr, keys in ctx["expr_use"].items():
        for v in _vars_in_expr(expr):
            for k in keys:
                add_usage(v, k, expr)
    for ph, keys in ctx["mustache_use"].items():
        v = _var_in_mustache(ph)
        if v:
            for k in keys:
                add_usage(v, k, ph)
    for v, keys in ctx["var_use"].items():            # backend-declared / mapped
        for k in keys:
            add_usage(v, k, "(declared / mapped)")
    for v, keys in ctx["script_var_use"].items():     # referenced inside a script body
        for k in keys:
            add_usage(v, k, "(script)")
    for a in result["apps"]:
        for v in a.get("variables", []):
            if v.get("key"):
                add_usage(v["key"], a.get("key"), "(app variable)")
    for fqn, jc in all_java.items():                  # variables touched from Java code
        for v in (jc.get("vars") or []):
            add_usage(v, fqn, "(Java: set/getVariable)")

    for v, per_model in var_usages.items():
        usedBy = sorted({key_to_node[k] for k in per_model if k in key_to_node})
        if not usedBy:
            continue
        scopes = sorted({nodes[uid]["type"] for uid in usedBy if uid in nodes})
        usages = [{"model": key_to_node[k], "snippets": sorted(snips)[:10]}
                  for k, snips in per_model.items() if k in key_to_node]
        add_node("variable", v, v, None, {"usedBy": usedBy, "scopes": scopes, "usages": usages})

    # --- string-literal nodes: the '...'/"..." constants inside expressions/bindings ---
    str_usages = {}   # literal -> {model_key: set(snippet)}
    for usage in (ctx["expr_use"], ctx["mustache_use"]):
        for text, keys in usage.items():
            for m in STR_LIT_RE.finditer(text):
                lit = m.group(1) if m.group(1) is not None else m.group(2)
                if not lit or not lit.strip():
                    continue
                for k in keys:
                    str_usages.setdefault(lit, {}).setdefault(k, set()).add(text)
    for lit, per_model in str_usages.items():
        usedBy = sorted({key_to_node[k] for k in per_model if k in key_to_node})
        if not usedBy:
            continue
        usages = [{"model": key_to_node[k], "snippets": sorted(snips)[:10]}
                  for k, snips in per_model.items() if k in key_to_node]
        add_node("string", lit, lit, None, {"usedBy": usedBy, "usages": usages})

    # --- custom-function nodes (externals.additionalData) — one per callable, cross-referenced to
    #     the forms/models AND the individual {{…}} bindings that call it. Every catalog entry becomes
    #     a node so the explorer lists them all; usedBy links to the calling models (bidirectional via
    #     _uses), bindings/calls link a fn to the exact bindings and back. The label carries the
    #     extracted signature (arguments) when known. ---
    if custom:
        sigs = custom.get("signatures", {})
        cfn_used = {}        # display -> set(model node id)
        cfn_bindings = {}    # display -> set(binding node id)
        for text, keys in ctx["mustache_use"].items():
            called = custom_fns_called_in(text, custom)
            if not called:
                continue
            bnode = f"binding:{text}"
            call_ids = []
            for disp in sorted(called):
                call_ids.append(f"customFunction:{disp}")
                for k in keys:
                    uid = key_to_node.get(k)
                    if uid:
                        cfn_used.setdefault(disp, set()).add(uid)
                if bnode in nodes:
                    cfn_bindings.setdefault(disp, set()).add(bnode)
            if bnode in nodes and call_ids:
                nodes[bnode]["data"]["calls"] = sorted(set(call_ids))
        for (disp, kind, ns, member) in custom_function_entries(custom):
            params = sigs.get(disp)
            label = f"{disp}({params})" if params is not None else disp
            add_node("customFunction", disp, label, None,
                     {"kind": kind, "namespace": ns, "member": member, "signature": params,
                      "sources": custom["sources"], "usedBy": sorted(cfn_used.get(disp, set())),
                      "bindings": sorted(cfn_bindings.get(disp, set()))})

    # Reverse direction: attach to each model the artifacts it uses (vars/exprs/...)
    # so a process/case/form can list "all its variables" (rendered collapsible).
    for n in list(nodes.values()):
        if n["type"] in ("variable", "expression", "binding", "string", "customFunction"):
            for muid in n["data"].get("usedBy", []):
                if muid in nodes:
                    uses = nodes[muid]["data"].setdefault("_uses", {})
                    uses.setdefault(n["type"], []).append(n["id"])
    for n in nodes.values():
        if isinstance(n["data"], dict) and "_uses" in n["data"]:
            n["data"]["_uses"] = {k: sorted(v) for k, v in n["data"]["_uses"].items()}

    edges = []

    def add_edge(s, t, rel):
        if s and t and s != t:
            edges.append({"s": s, "t": t, "rel": rel})

    # model -> model / java
    for r in resolved:
        s = key_to_node.get(r["from"])
        if r["targetType"] == "model":
            t = key_to_node.get(r["value"])
        elif r.get("targetFqn"):
            t = f"java:{r['targetFqn']}"
        else:
            t = None
        add_edge(s, t, r["rel"])

    # Java methods called from models: a method node per (class, method) that a model
    # invokes — model --calls--> method --declared-in--> class (navigable both ways).
    methods_called = {}   # method node id -> (fqn, name, set of caller model node ids)
    for r in resolved:
        if r["rel"].startswith("calls ") and r.get("targetFqn"):
            mname = r["rel"][6:].strip().rstrip("()")
            mid = f"method:{r['targetFqn']}#{mname}"
            info = methods_called.setdefault(mid, (r["targetFqn"], mname, set()))
            src = key_to_node.get(r["from"])
            if src:
                info[2].add(src)
    for mid, (fqn, mname, callers) in methods_called.items():
        cls = f"java:{fqn}"
        label = fqn.split(".")[-1] + "." + mname + "()"
        add_node("method", mid.split(":", 1)[1], label, None,
                 {"name": mname, "class": fqn, "declaredIn": cls if cls in nodes else None})
        for c in callers:
            add_edge(c, mid, "calls")
        if cls in nodes:
            add_edge(mid, cls, "declared-in")

    # expression --calls--> method / --reads--> bean class, so an expression node itself
    # links to what it invokes (e.g. ${myService.doWork(...)} -> the method node).
    bean_fqn = {r["value"]: r["targetFqn"] for r in resolved if r["kind"] == "bean" and r.get("targetFqn")}
    for expr in ctx["expr_use"]:
        enode = f"expression:{expr}"
        if enode not in nodes:
            continue
        body = re.sub(r"^[#$]\{|\}$", "", expr)
        for cm in METHOD_CALL_FULL_RE.finditer(body):
            fqn = bean_fqn.get(cm.group(1))
            if not fqn:
                continue
            mid = f"method:{fqn}#{cm.group(2)}"
            add_edge(enode, mid if mid in nodes else f"java:{fqn}", "calls")

    # model -> external (unresolved beans/classes/platform + missing model keys)
    # so "what it touches" is complete and broken key references become visible
    ext_seen = set()
    for r in result.get("unresolvedRefs", []):
        if r["kind"] in ("bean", "class"):
            platform = r["kind"] == "bean" and r["value"] in FLOWABLE_PLATFORM_BEANS
            data = {"platform": platform, "kind": r["kind"]}
        elif r.get("targetType") == "model":
            # A typed model key that no model in the workspace defines.
            data = {"kind": r["kind"], "missingModel": True}
        else:
            continue
        nid = f"external:{r['value']}"
        if nid not in ext_seen:
            ext_seen.add(nid)
            nodes[nid] = {"id": nid, "type": "external", "label": r["value"], "key": r["value"],
                          "file": None, "data": data}
        add_edge(key_to_node.get(r["from"]), nid, r["rel"])

    # rest calls -> endpoint (matched) or external url
    for rc in ctx["rest_calls"]:
        s = key_to_node.get(rc["source"])
        if rc.get("_matchEps"):
            for ep in rc["_matchEps"]:
                add_edge(s, f"endpoint:{ep['http']} {ep['path']}", "rest-call")
        elif re.search(r"dataObjectDefinitionKey=|/query/", rc["url"]):
            continue   # represented by queries-dataObject / runs-query edges instead
        else:
            url = rc["url"]
            # Classify the target — only genuinely third-party URLs are "external".
            #  * endpoints.* is the Flowable platform REST API (e.g. endpoints.dataObject
            #    -> the Data Object API), reached via the configured `endpoints` context.
            #  * a #/ fragment is in-app navigation: a normal app URL with a client route
            #    appended (e.g. #/case-view/case/{{$item.id}}), not a REST call at all.
            data = {"method": rc.get("method")}
            if "#/" in url or url.lstrip().startswith("#"):
                data["route"] = True
                rel = "navigates-to"
            elif re.search(r"(?:^|[/{$\s])endpoints\.", url):
                data["platform"] = True
                data["flowableApi"] = True
                rel = "rest-call"
            else:
                data["external_url"] = True
                rel = "rest-call"
            nid = f"external:{url}"
            if nid not in ext_seen:
                ext_seen.add(nid)
                nodes[nid] = {"id": nid, "type": "external", "label": url, "key": url,
                              "file": None, "data": data}
            add_edge(s, nid, rel)

    # group -> model (access)
    for a in ctx["access"]:
        t = key_to_node.get(a["model"])
        for g in a["groups"]:
            if "${" in g or "{{" in g:
                continue
            add_edge(f"group:{g}", t, a["action"])

    # controller -> endpoint it serves (so an endpoint shows its controller + callers)
    for ep in result["endpoints"]:
        if ep.get("controllerFqn"):
            add_edge(f"java:{ep['controllerFqn']}", f"endpoint:{ep['http']} {ep['path']}", "serves")

    # java -> java dependency wiring (constructor / field injection)
    # + CODE -> MODEL: a Java string literal matching a model key references that model
    #   (e.g. processDefinitionKey("..."), caseDefinitionKey("..."), bare key constants).
    for fqn, jc in all_java.items():
        snode = f"java:{fqn}"
        if snode not in nodes:
            continue
        for dep in jc.get("deps", set()):
            dfqn = simple_to_fqn.get(dep)
            if dfqn and dfqn != fqn and f"java:{dfqn}" in nodes:
                add_edge(snode, f"java:{dfqn}", "uses")
        for s in jc.get("strings", set()) & model_keys:
            t = key_to_node.get(s)
            if t and t.split(":", 1)[0] not in ("liquibase", "java", "endpoint", "group"):
                add_edge(snode, t, "references")

    # query -> group: enrich from raw-extracted meta, then link to the user groups it gates by
    for n in list(nodes.values()):
        if n["type"] == "query":
            qm = ctx["query_meta"].get(n["key"])
            if qm:
                n["data"]["groups"] = sorted(qm["groups"])
                n["data"]["sourceIndex"] = qm["sourceIndex"] or n["data"].get("sourceIndex")
            for grp in n["data"].get("groups", []):
                gid = f"group:{grp}"
                if gid in nodes:
                    add_edge(n["id"], gid, "filters-by-group")

    # action -> bot (botKey): prefer an agent model or the project Java class that
    # implements the bot (BotService.getKey() == botKey), else a platform bot node.
    bot_to_fqn = {j["botKey"]: j["fqn"] for j in all_java.values() if j.get("botKey")}
    for a in result["actions"]:
        bot = a.get("botKey")
        if not bot:
            continue
        anode = key_to_node.get(a["key"])
        jc = next((j for j in all_java.values() if bot in j["beanNames"] or j["primary"] == bot), None)
        if key_to_node.get(bot):                       # botKey is an agent (or other) model
            add_edge(anode, key_to_node[bot], "bot")
        elif bot in bot_to_fqn:                         # botKey implemented by a project BotService
            add_edge(anode, f"java:{bot_to_fqn[bot]}", "bot")
        elif jc:                                        # botKey matches a bean / class name
            add_edge(anode, f"java:{jc['fqn']}", "bot")
        else:                                           # Flowable platform bot
            bid = f"bot:{bot}"
            if bid not in nodes:
                nodes[bid] = {"id": bid, "type": "bot", "label": bot, "key": bot, "file": None,
                              "data": {"platform": True}}
            add_edge(anode, bid, "bot")

    # service / data object -> liquibase changelog, via AUTHORITATIVE signals only:
    # the model's referencedLiquibaseModelKey, the changelog's own
    # serviceDefinitionReferences back-pointer, a tableName match (current OR
    # pre-rename name), or the data object's own schema changelog filename — NOT
    # loose key-in-text.
    lb_by_key = {lb["key"]: f"liquibase:{lb['key']}" for lb in result["liquibase"]}
    lb_by_table = {}
    lb_by_svcref = {}
    for lb in result["liquibase"]:
        for t in set(lb.get("effectiveTables") or []) | set(lb.get("tables") or []):
            lb_by_table.setdefault(t.upper(), set()).add(f"liquibase:{lb['key']}")
        for sk in (lb.get("serviceRefs") or []):
            lb_by_svcref.setdefault(sk, set()).add(f"liquibase:{lb['key']}")
    for n in nodes.values():
        if n["type"] == "service":
            rk = n["data"].get("referencedLiquibaseModelKey")
            if rk and rk in lb_by_key:
                add_edge(n["id"], lb_by_key[rk], "schema")
            for lid in lb_by_svcref.get(n["key"], ()):  # changelog names this service
                add_edge(n["id"], lid, "schema")
            tn = n["data"].get("tableName")
            if tn:
                for lid in lb_by_table.get(tn.upper(), ()):
                    add_edge(n["id"], lid, "schema")
        elif n["type"] == "dataObject":
            for cand in (n["key"], (n["key"] or "") + "Schema"):
                if cand in lb_by_key:
                    add_edge(n["id"], lb_by_key[cand], "schema")

    # app -> model membership: a model belongs to the app it is co-located with
    # (same -bar.zip archive or same folder as the .app). childModels covers the
    # rest; here we use ALL file occurrences of a key (design + bar copies).
    app_by_container = {}
    for a in result["apps"]:
        c = _container_of(a.get("file"))
        if c:
            app_by_container[c] = key_to_node.get(a.get("key"))
    if app_by_container:
        non_model = {"java", "endpoint", "group", "external", "bot", "liquibase", "app",
                     "expression", "binding", "variable", "string", "method"}
        for n in list(nodes.values()):
            if n["type"] in non_model:
                continue
            containers = {_container_of(f) for _, f in by_key.get(n["key"], [])}
            containers.add(_container_of(n.get("file")))
            for c in containers:
                if c in app_by_container:
                    add_edge(app_by_container[c], n["id"], "contains")

    # drop _matchEps from restCalls before serialising (internal only)
    for rc in ctx["rest_calls"]:
        rc.pop("_matchEps", None)

    seen_e, uniq = set(), []
    for e in edges:
        k = (e["s"], e["t"], e["rel"])
        if k not in seen_e:
            seen_e.add(k)
            uniq.append(e)
    return {"nodes": list(nodes.values()), "edges": uniq}


# ---------------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------------
def render(result, root):
    L = []
    s = result["stats"]
    L.append(f"# Flowable App Overview — `{os.path.basename(os.path.abspath(root))}`\n")
    L.append(f"_Scanned {s['models']} model files, {s['archives']} archives, {s['java']} Java files, "
             f"{s['endpoints']} REST endpoints. Generated by flowable_project_overview.py._\n")

    # Apps & inventory
    if result["apps"]:
        L.append("## 1. Apps\n")
        for a in result["apps"]:
            L.append(f"### {a.get('name')} (`{a.get('key')}`) — `{a['file']}`")
            if a.get("description"):
                L.append(f"> {a['description']}")
            L.append(f"- palette: `{a.get('paletteDefinitionCategory')}`, "
                     f"variables: {len(a.get('variables', []))}, models: {len(a.get('childModels', []))}")
            for cm in a.get("childModels", []):
                norm = NORMALIZE_TYPE.get(cm.get("type"), cm.get("type"))
                loc = result["modelIndex"].get(f"{norm}:{cm.get('key')}")
                L.append(f"    - {cm.get('type')} `{cm.get('key')}`" + (f" → `{loc}`" if loc else " → _(not found)_"))
            L.append("")

    def hdr(n, title):
        L.append(f"## {n}. {title}\n")

    # Processes
    if result["processes"]:
        hdr(2, "Processes (BPMN)")
        for p in result["processes"]:
            L.append(f"### {p.get('name') or ''} (`{p['key']}`) — `{p['file']}`")
            if p.get("documentation"):
                L.append(f"> {p['documentation']}")
            if p.get("candidateStarterGroups"):
                L.append(f"- starter groups: `{p['candidateStarterGroups']}`")
            for ut in p["userTasks"]:
                extra = f" form=`{ut['formKey']}`" if ut.get("formKey") else ""
                L.append(f"- 👤 userTask `{ut['id']}` {ut.get('name') or ''}{extra}"
                         + (f" assignee={ut['assignee']}" if ut.get("assignee") else ""))
            for st in p["serviceTasks"]:
                impl = st.get("class") or st.get("delegateExpression") or st.get("expression") or st.get("type") or "?"
                rv = f" → var `{st['resultVariable']}`" if st.get("resultVariable") else ""
                L.append(f"- ⚙️ serviceTask `{st['id']}` {st.get('name') or ''} → {impl}{rv}")
            for rt in p["ruleTasks"]:
                L.append(f"- 📊 ruleTask `{rt['id']}` → decision `{rt.get('decisionRef')}`")
            for ca in p["callActivities"]:
                io = "".join(f" {m['dir']}({m['source']}→{m['target']})" for m in ca.get("inOut", []))
                L.append(f"- 📞 callActivity `{ca['id']}` → process `{ca.get('calledElement')}`{io}")
            for mi in p.get("multiInstance", []):
                L.append(f"- 🔁 multi-instance on `{mi['activity']}` collection=`{mi.get('collection')}` "
                         f"elementVar=`{mi.get('elementVariable')}`")
            for ev in p["events"]:
                d = f" ({ev['def']}{'=' + ev['value'] if ev.get('value') else ''})" if ev.get("def") else ""
                L.append(f"- 🔔 {ev['type']}{d} `{ev['id']}` {ev.get('name') or ''}")
            for ls in p.get("listeners", []):
                impl = ls.get("class") or ls.get("delegateExpression") or ls.get("expression")
                if impl:
                    L.append(f"- 🎧 {ls['kind']} [{ls.get('event')}] → {impl}")
            for c in p["conditions"]:
                L.append(f"    - flow {c['from']}→{c['to']}: `{c['condition']}`")
            L.append("")

    # Cases
    if result["cases"]:
        hdr(3, "Cases (CMMN)")
        for c in result["cases"]:
            L.append(f"### {c.get('name') or ''} (`{c['key']}`) — `{c['file']}`")
            if c.get("documentation"):
                L.append(f"> {c['documentation']}")
            for mr in c.get("modelRefs", []):
                L.append(f"- {mr['rel']}: `{mr['key']}`")
            if c.get("planModel"):
                _render_stage(c["planModel"], L, 1)
            for s_ in c["sentries"]:
                cond = f" if `{s_['condition']}`" if s_.get("condition") else ""
                L.append(f"- 🚪 sentry `{s_['id']}`{cond}" + (f" on {s_['onParts']}" if s_.get("onParts") else ""))
            for e in c["eventListeners"]:
                t = f" timer=`{e['timer']}`" if e.get("timer") else ""
                L.append(f"- ⏰ {e['type']} `{e['id']}`{t}")
            L.append("")

    if result["decisions"]:
        hdr(4, "Decisions (DMN)")
        for d in result["decisions"]:
            L.append(f"- `{d['key']}` ({d.get('hitPolicy')}, {d.get('ruleCount')} rules) — "
                     f"in: {d.get('inputs')}, out: {d.get('outputs')} — `{d['file']}`")
        L.append("")

    if result["forms"]:
        hdr(5, "Forms & Pages")
        for f in result["forms"]:
            L.append(f"### {f.get('name') or ''} (`{f['key']}`) — `{f['file']}`")
            for fld in f["fields"]:
                b = f" ← `{fld['value']}`" if fld.get("value") else ""
                req = " *(req)*" if fld.get("required") else ""
                L.append(f"- `{fld['id']}` [{fld.get('type')}] {fld.get('label') or ''}{req}{b}")
            if f["outcomes"]:
                L.append("- outcomes: " + ", ".join(f"`{o['value']}`" for o in f["outcomes"] if o.get("value")))
            for ds in f["dataSources"]:
                L.append(f"- 🔌 data source: {ds}")
            for sf in f["subforms"]:
                L.append(f"- 📑 subform → `{sf}`")
            L.append("")

    if result["dataObjects"]:
        L.append("## 5b. Data objects\n")
        for d in result["dataObjects"]:
            flds = d.get("fields") or []
            preview = ", ".join(str(x) for x in flds[:12]) + (" …" if len(flds) > 12 else "")
            table = f" (table `{d['serviceTableName']}`)" if d.get("serviceTableName") else ""
            L.append(f"- `{d.get('key')}` ({d.get('dataObjectType') or 'dataObject'}){table} "
                     f"[{len(flds)} fields{': ' + preview if preview else ''}] — `{d['file']}`")
        L.append("")

    if result["dictionaries"]:
        L.append("## 5c. Data dictionaries\n")
        for d in result["dictionaries"]:
            L.append(f"- `{d.get('key')}` — types: {d.get('types')} — `{d['file']}`")
        L.append("")

    if result["others"]:
        L.append("## 5d. Other models (queries, templates, policies, sequences, …)\n")
        by_type = {}
        for o in result["others"]:
            by_type.setdefault(o.get("modelType", "?"), []).append(o)
        for mtype in sorted(by_type):
            L.append(f"**{mtype}** ({len(by_type[mtype])}):")
            for o in by_type[mtype]:
                L.append(f"- `{o.get('key')}` {o.get('name') or ''} — `{o['file']}`")
        L.append("")

    if result["agents"]:
        hdr(6, "Agents / Bots")
        for a in result["agents"]:
            L.append(f"### `{a.get('key')}` — {a.get('name') or ''} ({a.get('type')}) — `{a['file']}`")
            L.append(f"- model: {a.get('aiVendor')}/{a.get('modelName')} (temp {a.get('temperature')}), "
                     f"apiEndpoint={a.get('enableApiEndpoint')}")
            if a.get("knowledgeBase"):
                L.append(f"- 📚 knowledgeBase → `{a['knowledgeBase']}`")
            for t in a.get("tools", []):
                L.append(f"- 🛠️ tool ({t['type']}) → `{t['key']}`")
            for op in a.get("operations", []):
                L.append(f"- op `{op['key']}`: {op.get('systemMessage') or op.get('userMessage') or ''}")
            L.append("")

    # Integration
    hdr(7, "Integration: Services, Channels, Events & REST")
    if result["services"]:
        L.append("### Service models (.service)")
        for sv in result["services"]:
            L.append(f"- **{sv.get('name')}** (`{sv.get('key')}`, {sv.get('type')}) base=`{sv.get('baseUrl')}` "
                     f"auth={sv.get('auth')} — `{sv['file']}`")
            for op in sv["operations"]:
                L.append(f"    - `{op.get('method')}` {op.get('fullUrl') or op.get('url')} (op `{op['key']}`)")
        L.append("")
    if result["channels"]:
        L.append("### Channels (.channel)")
        for ch in result["channels"]:
            L.append(f"- `{ch.get('key')}` {ch.get('channelType')}/{ch.get('type')} "
                     f"topics={ch.get('topics')} dest={ch.get('destination')} — `{ch['file']}`")
        L.append("")
    if result["events"]:
        L.append("### Events (.event)")
        for ev in result["events"]:
            L.append(f"- `{ev.get('key')}` payload={ev.get('payload')} correlation={ev.get('correlation')}")
        L.append("")
    if result["restCalls"]:
        L.append("### REST endpoints CALLED by the app (→ matched code)")
        for rc in result["restCalls"]:
            src = f"{rc['source']}/{rc['where']}" if rc.get("where") else rc["source"]
            L.append(f"- `{rc['method']}` {rc['url']}  _(from {rc['kind']} `{src}`)_")
            for m in rc.get("matches", []):
                L.append(f"    - ✅ served by {m}")
            if not rc.get("matches"):
                L.append("    - ⚠️ no matching controller in project (external or unimplemented)")
        L.append("")

    # Java glue
    hdr(8, "Java glue code")
    if result["javaGlue"]:
        L.append("### Delegates / listeners (model-referenced classes)")
        for jc in result["javaGlue"]:
            L.append(f"- `{jc['fqn']}` implements {sorted(jc['interfaces'] & GLUE_INTERFACES)} — "
                     f"`{jc['file']}:{jc['line']}`")
        L.append("")
    if result["javaControllers"]:
        L.append("### REST controllers & endpoints")
        for jc in result["javaControllers"]:
            L.append(f"- **{jc['primary']}** — `{jc['file']}`")
            for ep in jc["endpoints"]:
                L.append(f"    - `{ep['http']}` {ep['path']} → {ep['handler']}() (line {ep['line']})")
        L.append("")

    # Model <-> code resolved references
    hdr(9, "Resolved references (model → code / model)")
    code_refs = [r for r in result["resolvedRefs"] if r["targetType"] in ("bean", "class")]
    model_refs = [r for r in result["resolvedRefs"] if r["targetType"] == "model"]
    if code_refs:
        L.append("### Model → Java code")
        for r in code_refs:
            L.append(f"- `{r['from']}` [{r['rel']}] `{r['value']}` → {r['target']}")
        L.append("")
    if model_refs:
        L.append("### Model → Model")
        for r in model_refs:
            L.append(f"- `{r['from']}` —{r['rel']}→ {r['kind']} `{r['value']}` (`{r['target']}`)")
        L.append("")

    # Unresolved (grouped by what is referenced; platform beans called out separately)
    hdr(10, "Unresolved references (external / library / missing)")
    groups = {}
    for r in result["unresolvedRefs"]:
        groups.setdefault((r["kind"], r["value"]), []).append(r)
    platform = {k: v for k, v in groups.items() if k[0] == "bean" and k[1] in FLOWABLE_PLATFORM_BEANS}
    others = {k: v for k, v in groups.items() if k not in platform}
    if platform:
        L.append("### Expected Flowable platform beans (provided by the engine, not project code)")
        for (kind, val), rs in sorted(platform.items()):
            n = len({r["from"] for r in rs})
            L.append(f"- `{val}` — referenced by {n} model(s)")
        L.append("")
    if others:
        L.append("### Other unresolved — review (external dependency, typo, or missing)")
        for (kind, val), rs in sorted(others.items()):
            froms = sorted({r["from"] for r in rs})
            more = f" (+{len(froms) - 1} more)" if len(froms) > 1 else ""
            L.append(f"- [{kind}] `{val}` — from `{froms[0]}`{more}")
        L.append("")
    if not result["unresolvedRefs"]:
        L.append("_None — every reference resolved within the project._\n")

    # Access & user groups — "who can see / start / do what"
    hdr(11, "Access & user groups (who can do what)")
    acc = result["access"]
    if result["groups"]:
        L.append(f"**All groups/roles referenced ({len(result['groups'])}):** "
                 + ", ".join(f"`{g}`" for g in result["groups"]) + "\n")
    app_acc = [a for a in acc if a["scope"] in ("app", "page")]
    if app_acc:
        L.append("### App / page access")
        for a in app_acc:
            who = ", ".join(a["groups"] + [f"user:{u}" for u in a["users"]]) or "(everyone)"
            L.append(f"- {a['modelType']} `{a['model']}`: {who}")
        L.append("")
    starts = [a for a in acc if a["action"] == "start"]
    if starts:
        L.append("### Who can start (processes & cases)")
        for a in sorted(starts, key=lambda x: x["model"]):
            who = ", ".join(a["groups"] + a["users"]) or "(no restriction)"
            L.append(f"- {a['modelType']} `{a['model']}` ← {who}")
        L.append("")
    assigns = [a for a in acc if a["action"] == "assign"]
    if assigns:
        L.append("### Task assignment (candidate groups / assignees per model)")
        bym = {}
        for a in assigns:
            bym.setdefault(a["model"], set()).update(a["groups"] + a["users"])
        for model in sorted(bym):
            L.append(f"- `{model}`: {', '.join(sorted(bym[model]))}")
        L.append("")
    do_acc = [a for a in acc if a["modelType"] == "dataObject"]
    if do_acc:
        L.append("### Data object access (who may query/create/update/delete)")
        bym = {}
        for a in do_acc:
            bym.setdefault(a["model"], []).append(a)
        for model in sorted(bym):
            perms = "; ".join(f"{a['action']}={'/'.join(a['groups']) or '?'}" for a in bym[model])
            L.append(f"- `{model}`: {perms}")
        L.append("")
    if result["policies"]:
        L.append("### Security policies")
        for p in result["policies"]:
            L.append(f"- `{p.get('key')}` — `{p['file']}`")
            for perm in p.get("permissions", []):
                L.append(f"    - {perm.get('key')}: {', '.join(perm.get('roles') or []) or '(none)'}")
        L.append("")
    if not acc and not result["policies"]:
        L.append("_No explicit access restrictions found._\n")

    # Code map — where bots, services, listeners, delegates, controllers live
    hdr(12, "Code map (where things live)")
    if result["agents"]:
        L.append("**Agents / bots (models):**")
        for a in result["agents"]:
            L.append(f"- `{a.get('key')}` — `{a['file']}`")
    if result["services"]:
        L.append("**Service models (.service):**")
        for sv in result["services"]:
            L.append(f"- `{sv.get('key')}` ({sv.get('type')}) base=`{sv.get('baseUrl')}` — `{sv['file']}`")
    # Models grouped by folder
    folders = {}
    bucket_label = [("processes", "bpmn"), ("cases", "cmmn"), ("decisions", "dmn"),
                    ("forms", "form"), ("dataObjects", "data"), ("services", "service"),
                    ("agents", "agent"), ("channels", "channel"), ("events", "event"),
                    ("dictionaries", "dict"), ("policies", "policy"), ("actions", "action"),
                    ("liquibase", "liquibase"), ("others", "other")]
    for bucket, label in bucket_label:
        for o in result[bucket]:
            fpath = o.get("file", "")
            folder = fpath.split("!")[0].rsplit("/", 1)[0] if "/" in fpath.split("!")[0] else "."
            folders.setdefault(folder, {})
            folders[folder][label] = folders[folder].get(label, 0) + 1
    if folders:
        L.append("\n**Models by folder:**")
        for folder in sorted(folders):
            counts = ", ".join(f"{n} {t}" for t, n in sorted(folders[folder].items()))
            L.append(f"- `{folder}/` — {counts}")
    # Java by role
    jbr = result.get("javaByRole", {})
    if jbr:
        L.append("\n**Java code by role:**")
        for role in ("controller", "delegate", "listener", "service", "repository",
                     "configuration", "component", "other"):
            items = jbr.get(role) or []
            if not items:
                continue
            if role == "other":
                L.append(f"\n_other (no Spring/Flowable role — DTOs, utils, …): {len(items)} classes_")
                continue
            L.append(f"\n_{role} ({len(items)}):_")
            for jc in sorted(items, key=lambda x: x["fqn"])[:80]:
                iface = ""
                if role in ("delegate", "listener"):
                    rel = sorted(i for i in jc["interfaces"] if i.endswith("Listener") or i in GLUE_INTERFACES)
                    iface = f" [{', '.join(rel)}]" if rel else ""
                L.append(f"- `{jc['fqn']}`{iface} — `{jc['file']}:{jc['line']}`")
            if len(items) > 80:
                L.append(f"- … (+{len(items) - 80} more)")
    L.append("")

    # Variables / expressions
    hdr(13, "Variables, beans & expressions")
    if result["variables"]:
        L.append("**Variables:** " + ", ".join(f"`{v}`" for v in result["variables"]) + "\n")
    if result["beans"]:
        L.append("**Beans/objects with method calls:** " + ", ".join(f"`{b}`" for b in result["beans"]) + "\n")
    if result["delegateClasses"]:
        L.append("**JavaDelegate/listener classes referenced:** "
                 + ", ".join(f"`{c}`" for c in result["delegateClasses"]) + "\n")
    if result["expressions"]:
        L.append("<details><summary>All expressions (${ } / #{ })</summary>\n")
        for ex in result["expressions"]:
            L.append(f"- `{ex}`")
        L.append("\n</details>\n")

    if result["warnings"]:
        hdr(14, f"Warnings ({len(result['warnings'])})")
        for w in result["warnings"][:200]:
            L.append(f"- {w}")
        if len(result["warnings"]) > 200:
            L.append(f"- … (+{len(result['warnings']) - 200} more — see `diagnostics` in graph.json)")
    return "\n".join(L)


def _render_stage(node, L, depth):
    ind = "    " * depth
    L.append(f"{ind}- 📁 **{node['type']}** {node.get('name') or node.get('id') or ''}")
    for ch in node.get("children", []):
        rules = ""
        if ch.get("rules"):
            rules = " " + ", ".join(f"{k}={v}" for k, v in ch["rules"].items())
        if "children" in ch:
            _render_stage(ch, L, depth + 1)
        else:
            extra = ""
            for k in ("assignee", "formKey", "processRef", "caseRef", "decisionRef",
                      "serviceModelKey", "agentModelKey", "dataObjectKey"):
                if ch.get(k):
                    extra += f" {k}=`{ch[k]}`"
            L.append(f"{ind}    - {ch['type']} {ch.get('name') or ch.get('id') or ''}{extra}{rules}")


# ---------------------------------------------------------------------------
# Interactive HTML explorer (self-contained, offline, no external libs)
# ---------------------------------------------------------------------------
# The explorer frontend lives in frontend/{explorer.html,explorer.css,explorer.js} — edit those
# files, then run `python3 tools/embed_frontend.py` to refresh the embedded copies below (a
# single-file copy of this script has no frontend/ directory next to it). tests/test_embed.py
# fails when the two drift apart.
# --- BEGIN GENERATED FRONTEND (tools/embed_frontend.py) ---
_EMBEDDED_FRONTEND = {
    'explorer.html': '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n<title>Flowable Atlas</title>\n<style>\n/*__ATLAS_CSS__*/</style>\n</head>\n<body>\n<header>\n  <div class="brand"><div class="mark">Flowable&nbsp;<b>Atlas</b></div><div class="proj mono" id="proj"></div></div>\n  <div class="search">\n    <span class="ic" aria-hidden="true">⌕</span>\n    <input id="q" placeholder="Search everything — keys, files, classes, groups…" autocomplete="off"\n           role="combobox" aria-expanded="false" aria-controls="results" aria-autocomplete="list" aria-label="Search all nodes">\n    <span class="hint" aria-hidden="true">/</span>\n    <div class="results" id="results" role="listbox" aria-label="Search results"></div>\n  </div>\n  <div class="stats" id="stats"></div>\n  <button id="themebtn" class="tbtn" aria-label="Switch color theme"></button>\n  <div class="diagpanel" id="diagpanel"></div>\n  <div class="diagpanel cfnpanel" id="cfnpanel"></div>\n</header>\n<div class="layout">\n  <div class="col"><nav class="rail" id="rail" aria-label="Categories"></nav></div>\n  <div class="col"><div id="list"></div></div>\n  <main class="col detail" id="detail" aria-label="Node details"></main>\n</div>\n<script type="application/json" id="atlas-data">__ATLAS_DATA__</script>\n<script>\n/*__ATLAS_JS__*/</script>\n</body>\n</html>\n',
    'explorer.css': '  /* ------------------------------------------------------------------ */\n  /* Theme tokens. Dark is the default; the light palette overrides via  */\n  /* [data-theme=light], which boot-JS resolves from the OS preference   */\n  /* (prefers-color-scheme) or the user\'s toggle (persisted).            */\n  /* ------------------------------------------------------------------ */\n  :root{\n    --bg:#0a0c0f; --panel:#101418; --panel2:#0d1115; --line:#1d242c; --line2:#2a333d;\n    --ink:#e7edf3; --ink-dim:#9aa7b4; --ink-faint:#66727f; --accent:#34e0c0;\n    --hover:#141a20; --active:#15202a; --input:#0a0e12;\n    --glass:rgba(10,12,15,.82); --scroll:#222b34; --scroll-hover:#2f3a45;\n    --grid-line:rgba(255,255,255,.018); --glow:rgba(52,224,192,.06);\n    --shadow:rgba(0,0,0,.5); --focus:rgba(52,224,192,.12);\n    --mono:"SFMono-Regular",ui-monospace,"JetBrains Mono",Menlo,Consolas,monospace;\n    --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,sans-serif;\n    --serif:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;\n    --c-app:#e6edf3;--c-process:#34e0c0;--c-case:#4cc9f0;--c-decision:#b694ff;\n    --c-form:#f4b942;--c-page:#e89b3b;--c-dataObject:#ff7a93;--c-dataDictionary:#ff9eb0;\n    --c-service:#5b9cff;--c-agent:#ff6fd8;--c-channel:#9be15d;--c-event:#ff9f45;\n    --c-endpoint:#56c2d6;--c-java:#b8c0cc;--c-query:#8aa0b4;--c-template:#c9a26b;\n    --c-sequence:#8aa0b4;--c-action:#6fe0a8;--c-document:#8aa0b4;--c-variableExtractor:#8aa0b4;\n    --c-securityPolicy:#ffb3c0;--c-group:#d8b75a;--c-external:#6b7480;--c-masterData:#ff7a93;\n    --c-knowledgeBase:#b694ff;--c-sla:#c9a26b;--c-dashboardComponent:#6fe0a8;\n    --c-bot:#ff6fd8;--c-liquibase:#7aa0ff;--c-expression:#b08cff;--c-binding:#5fd0e0;\n    --c-variable:#ffd27f;--c-string:#9ad07a;--c-method:#9fb8ff;--c-customFunction:#4fd6c0;\n    --c-invalidExpr:#ff6b6b;--c-suspectExpr:#e0b34a;\n    --cov-good:#9ad07a;--cov-warn:#f4b942;--cov-bad:#ff7a93;--cov-info:#5b9cff;--cov-miss:#ff9aab;\n  }\n  :root[data-theme=light]{\n    --bg:#f2f4f7; --panel:#ffffff; --panel2:#e9edf2; --line:#dbe1e8; --line2:#c4cdd7;\n    --ink:#182230; --ink-dim:#46566a; --ink-faint:#718093; --accent:#0c8f77;\n    --hover:#eaeef3; --active:#dff0ea; --input:#f7f9fb;\n    --glass:rgba(245,247,250,.85); --scroll:#c6cfd9; --scroll-hover:#aeb9c6;\n    --grid-line:rgba(16,32,48,.035); --glow:rgba(12,143,119,.05);\n    --shadow:rgba(30,45,60,.22); --focus:rgba(12,143,119,.15);\n    --c-app:#33465c;--c-process:#0c8f77;--c-case:#0e7fae;--c-decision:#7a4fd8;\n    --c-form:#a87508;--c-page:#a8650f;--c-dataObject:#d23b5e;--c-dataDictionary:#bb566f;\n    --c-service:#2f66c8;--c-agent:#b52f96;--c-channel:#4d8f1f;--c-event:#bd6412;\n    --c-endpoint:#1f7f8f;--c-java:#5a6b80;--c-query:#647a8e;--c-template:#8f6b32;\n    --c-sequence:#647a8e;--c-action:#1f9a5e;--c-document:#647a8e;--c-variableExtractor:#647a8e;\n    --c-securityPolicy:#c04a64;--c-group:#8f7a1e;--c-external:#75808d;--c-masterData:#d23b5e;\n    --c-knowledgeBase:#7a4fd8;--c-sla:#8f6b32;--c-dashboardComponent:#1f9a5e;\n    --c-bot:#b52f96;--c-liquibase:#3b5fd0;--c-expression:#7a56d6;--c-binding:#1f87a0;\n    --c-variable:#a26f0d;--c-string:#4d8f1f;--c-method:#4c68c0;--c-customFunction:#0e9d84;\n    --c-invalidExpr:#d43b4f;--c-suspectExpr:#a87b12;\n    --cov-good:#3d8f2f;--cov-warn:#a87b12;--cov-bad:#d23b5e;--cov-info:#2f66c8;--cov-miss:#d23b5e;\n  }\n  *{box-sizing:border-box}\n  html,body{height:100%;margin:0}\n  body{\n    background:var(--bg);color:var(--ink);font-family:var(--sans);font-size:13px;\n    background-image:\n      radial-gradient(1200px 600px at 80% -10%, var(--glow), transparent 60%),\n      linear-gradient(var(--grid-line) 1px, transparent 1px),\n      linear-gradient(90deg, var(--grid-line) 1px, transparent 1px);\n    background-size:auto, 32px 32px, 32px 32px;\n  }\n  ::-webkit-scrollbar{width:10px;height:10px}\n  ::-webkit-scrollbar-thumb{background:var(--scroll);border-radius:6px;border:2px solid var(--bg)}\n  ::-webkit-scrollbar-thumb:hover{background:var(--scroll-hover)}\n  .mono{font-family:var(--mono)}\n  a{color:inherit}\n  :focus-visible{outline:2px solid var(--accent);outline-offset:1px;border-radius:4px}\n\n  header{\n    position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:18px;\n    padding:0 18px;height:54px;border-bottom:1px solid var(--line);\n    background:var(--glass);backdrop-filter:blur(10px);\n  }\n  .brand{display:flex;align-items:baseline;gap:10px;white-space:nowrap}\n  .brand .mark{font-family:var(--serif);font-size:20px;letter-spacing:.04em;color:var(--ink)}\n  .brand .mark b{color:var(--accent);font-weight:600}\n  .brand .proj{font-family:var(--mono);font-size:11px;color:var(--ink-dim);\n    border:1px solid var(--line2);padding:2px 8px;border-radius:999px}\n  .search{flex:1;position:relative;max-width:520px;margin:0 auto}\n  .search input{\n    width:100%;background:var(--panel2);border:1px solid var(--line2);color:var(--ink);\n    font-family:var(--mono);font-size:12px;padding:8px 12px 8px 32px;border-radius:8px;outline:none}\n  .search input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--focus)}\n  .search .ic{position:absolute;left:11px;top:50%;transform:translateY(-50%);color:var(--ink-faint)}\n  .search .hint{position:absolute;right:10px;top:50%;transform:translateY(-50%);\n    color:var(--ink-faint);font-family:var(--mono);font-size:10px;border:1px solid var(--line2);\n    border-radius:4px;padding:1px 5px}\n  .results{position:absolute;top:42px;left:0;right:0;background:var(--panel);\n    border:1px solid var(--line2);border-radius:8px;max-height:60vh;overflow:auto;\n    box-shadow:0 16px 50px var(--shadow);display:none}\n  .results.on{display:block}\n  .results .r{display:flex;align-items:center;gap:9px;padding:8px 12px;cursor:pointer;border-bottom:1px solid var(--line)}\n  .results .r:hover,.results .r.sel{background:var(--hover)}\n  .stats{display:flex;gap:14px;color:var(--ink-dim);font-family:var(--mono);font-size:11px;white-space:nowrap;align-items:center}\n  .stats b{color:var(--ink)}\n  .tbtn{background:var(--panel);border:1px solid var(--line2);color:var(--ink-dim);cursor:pointer;\n    border-radius:6px;padding:3px 8px;font-size:12px;line-height:1}\n  .tbtn:hover{color:var(--ink);border-color:var(--accent)}\n  #diagbtn{color:var(--c-suspectExpr);cursor:pointer;border:1px solid transparent;border-radius:6px;padding:2px 6px}\n  #diagbtn:hover{border-color:var(--c-suspectExpr)}\n  .diagpanel{display:none;position:absolute;top:52px;right:18px;width:min(560px,90vw);max-height:50vh;overflow:auto;\n    background:var(--panel);border:1px solid var(--line2);border-radius:10px;box-shadow:0 18px 40px var(--shadow);z-index:60}\n  .diagpanel.on{display:block}\n  .dp-head{padding:10px 12px;color:var(--ink-dim);font-size:11px;border-bottom:1px solid var(--line)}\n  .dp-row{display:flex;gap:10px;align-items:baseline;padding:8px 12px;border-bottom:1px solid var(--line);font-size:11px}\n  .dp-kind{color:var(--c-suspectExpr);font-family:var(--mono);flex:none}\n  .dp-path{color:var(--ink);flex:none;max-width:40%;overflow:hidden;text-overflow:ellipsis}\n  .dp-msg{color:var(--ink-dim)}\n  /* custom frontend functions (externals.additionalData) — informational, not a warning */\n  #cfnbtn{color:var(--c-binding,var(--accent));cursor:pointer;border:1px solid transparent;border-radius:6px;padding:2px 6px}\n  #cfnbtn:hover{border-color:var(--c-binding,var(--accent))}\n  .cf-ns{padding:9px 12px;border-bottom:1px solid var(--line)}\n  .cf-ns b{color:var(--ink);font-family:var(--mono)}\n  .cf-src{color:var(--ink-dim)}\n  .cf-mem{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}\n  .cf-mem span{font-family:var(--mono);font-size:10.5px;color:var(--ink-dim);background:var(--panel2,rgba(127,127,127,.12));\n    border:1px solid var(--line);border-radius:5px;padding:1px 6px}\n\n  .layout{display:grid;grid-template-columns:230px 330px 1fr;height:calc(100vh - 54px)}\n  .col{overflow:auto;border-right:1px solid var(--line)}\n  .col.detail{border-right:none}\n\n  /* left rail */\n  .rail{padding:14px 8px}\n  .rail .sec{font-family:var(--mono);font-size:10px;letter-spacing:.14em;text-transform:uppercase;\n    color:var(--ink-faint);padding:14px 12px 6px}\n  .cat{display:flex;align-items:center;gap:9px;padding:6px 12px;border-radius:7px;cursor:pointer;color:var(--ink-dim)}\n  .cat:hover{background:var(--hover);color:var(--ink)}\n  .cat.on{background:var(--active);color:var(--ink)}\n  .cat.on .dot{box-shadow:0 0 0 3px var(--focus)}\n  .cat .lbl{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n  .cat .n{font-family:var(--mono);font-size:11px;color:var(--ink-faint)}\n  .dot{width:8px;height:8px;border-radius:50%;flex:none;background:var(--ink-faint)}\n\n  /* list column */\n  .listhead{position:sticky;top:0;background:var(--panel2);border-bottom:1px solid var(--line);padding:10px;z-index:5}\n  .listhead .t{font-family:var(--mono);font-size:11px;color:var(--ink-dim);margin-bottom:8px;display:flex;justify-content:space-between}\n  .listhead input{width:100%;background:var(--input);border:1px solid var(--line2);color:var(--ink);\n    font-family:var(--mono);font-size:12px;padding:6px 9px;border-radius:6px;outline:none}\n  .listhead input:focus{border-color:var(--accent)}\n  .item{display:flex;align-items:flex-start;gap:9px;padding:9px 12px;cursor:pointer;border-bottom:1px solid var(--line);\n    animation:rise .25s ease both}\n  .item:hover{background:var(--hover)}\n  .item.on{background:var(--active);box-shadow:inset 3px 0 0 var(--accent)}\n  .item .meta{min-width:0}\n  .item .nm{color:var(--ink);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n  .item .sub{font-family:var(--mono);font-size:10.5px;color:var(--ink-faint);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n  .sentinel{height:1px}\n  @keyframes rise{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:none}}\n  @media (prefers-reduced-motion: reduce){ .item{animation:none} }\n\n  /* detail */\n  .detail{padding:0}\n  .empty{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:var(--ink-faint);gap:10px;text-align:center}\n  .empty .big{font-family:var(--serif);font-size:30px;color:var(--ink-dim)}\n  .crumbs{position:sticky;top:0;background:var(--glass);backdrop-filter:blur(8px);\n    border-bottom:1px solid var(--line);padding:8px 18px;display:flex;align-items:center;gap:8px;font-family:var(--mono);font-size:11px;z-index:6}\n  .crumbs button{background:var(--panel);border:1px solid var(--line2);color:var(--ink-dim);\n    border-radius:6px;padding:3px 9px;cursor:pointer;font-family:var(--mono);font-size:11px}\n  .crumbs button:hover{color:var(--ink);border-color:var(--accent)}\n  .crumbs .trail{color:var(--ink-faint);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n  .crumbs #permalink{margin-left:auto;flex:none}\n  .dbody{padding:22px 26px;max-width:1000px}\n  .chip{display:inline-flex;align-items:center;gap:6px;font-family:var(--mono);font-size:10.5px;\n    border:1px solid var(--line2);border-radius:999px;padding:2px 9px;color:var(--ink-dim)}\n  .dtitle{font-family:var(--serif);font-size:30px;line-height:1.15;margin:12px 0 4px}\n  .dkey{font-family:var(--mono);font-size:12px;color:var(--accent)}\n  .dfile{font-family:var(--mono);font-size:11px;color:var(--ink-faint);margin-top:6px;cursor:copy}\n  .dfile:hover{color:var(--ink-dim)}\n  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1px;\n    background:var(--line);border:1px solid var(--line);border-radius:8px;overflow:hidden;margin:18px 0}\n  .cell{background:var(--panel);padding:10px 12px}\n  .cell .k{font-family:var(--mono);font-size:10px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-faint)}\n  .cell .v{margin-top:3px;color:var(--ink);word-break:break-word}\n  .cell .v.mono{font-family:var(--mono);font-size:11.5px}\n  .vlink{color:var(--accent);cursor:pointer;border-bottom:1px dotted var(--accent)}\n  .vlink:hover{border-bottom-style:solid}\n  .vlink:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:2px}\n  h3.rel{font-family:var(--mono);font-size:11px;letter-spacing:.12em;text-transform:uppercase;\n    color:var(--ink-dim);margin:24px 0 10px;display:flex;align-items:center;gap:8px}\n  h3.rel:before{content:"";flex:none;width:14px;height:1px;background:var(--line2)}\n  .relgrp{margin:0 0 14px}\n  .relgrp .lab{font-family:var(--mono);font-size:11px;color:var(--ink-faint);margin:0 0 6px}\n  .nodechips{display:flex;flex-wrap:wrap;gap:7px}\n  .nc{display:inline-flex;align-items:center;gap:7px;background:var(--panel);border:1px solid var(--line2);\n    border-radius:8px;padding:5px 10px;cursor:pointer;max-width:380px}\n  .nc:hover{border-color:var(--accent);background:var(--hover);transform:translateY(-1px)}\n  .nc .nm{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n  .nc .ty{font-family:var(--mono);font-size:9.5px;color:var(--ink-faint);text-transform:uppercase;letter-spacing:.06em}\n  .oplist{border:1px solid var(--line);border-radius:8px;overflow:hidden;margin:8px 0}\n  details.uses{border:1px solid var(--line);border-radius:8px;margin:6px 0;background:var(--panel)}\n  details.uses>summary{cursor:pointer;padding:8px 12px;font-family:var(--mono);font-size:11px;color:var(--ink-dim);list-style:none}\n  details.uses>summary::-webkit-details-marker{display:none}\n  details.uses>summary:before{content:"▸ ";color:var(--ink-faint)}\n  details.uses[open]>summary:before{content:"▾ "}\n  details.uses>summary:hover{color:var(--ink)}\n  details.uses[open]>summary{border-bottom:1px solid var(--line);color:var(--ink)}\n  details.uses .nodechips{padding:10px 12px}\n  .oprow{display:flex;gap:10px;padding:7px 12px;border-bottom:1px solid var(--line);font-family:var(--mono);font-size:11.5px}\n  .oprow:last-child{border-bottom:none}\n  .verb{font-weight:600;min-width:48px}\n  .tag{font-family:var(--mono);font-size:9.5px;text-transform:uppercase;letter-spacing:.05em;\n    border:1px solid var(--line2);border-radius:4px;padding:1px 6px;color:var(--ink-dim)}\n  .muted{color:var(--ink-faint)}\n\n  /* schema coverage (Liquibase -> service -> data object) */\n  .covmeta{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-family:var(--mono);\n    font-size:11px;color:var(--ink-dim);margin:8px 0}\n  .covbadges{margin:6px 0 4px}\n  .cov-badge{display:inline-block;font-family:var(--mono);font-size:10px;border-radius:999px;\n    padding:2px 9px;margin:0 6px 6px 0;border:1px solid var(--line2);color:var(--ink-dim)}\n  .cov-badge.cov-bad{color:var(--cov-bad);border-color:var(--cov-bad)}\n  .cov-badge.cov-warn{color:var(--cov-warn);border-color:var(--cov-warn)}\n  .cov-badge.cov-info{color:var(--cov-info);border-color:var(--cov-info)}\n  .cov-badge.cov-good{color:var(--cov-good);border-color:var(--cov-good)}\n  .authb{display:inline-block;font-family:var(--mono);font-size:9px;letter-spacing:.04em;text-transform:uppercase;\n    border-radius:999px;padding:1px 7px;margin-left:7px;border:1px solid var(--line2);vertical-align:middle}\n  .authb-live{color:var(--cov-good);border-color:var(--cov-good)}\n  .authb-old{color:var(--cov-warn);border-color:var(--cov-warn)}\n  .authb-orphan{color:var(--cov-bad);border-color:var(--cov-bad)}\n  .authnote{font-size:11.5px;line-height:1.5;border-radius:8px;padding:8px 11px;margin:10px 0;border:1px solid var(--line2)}\n  .authnote-old{color:var(--cov-warn);background:rgba(244,185,66,.07);border-color:var(--cov-warn)}\n  .authnote-orphan{color:var(--cov-bad);background:rgba(255,122,147,.07);border-color:var(--cov-bad)}\n  .authnote .nc{margin-top:4px}\n  .covwrap{overflow-x:auto;border:1px solid var(--line);border-radius:8px;margin:8px 0}\n  table.cov{border-collapse:collapse;width:100%;font-family:var(--mono);font-size:11.5px}\n  table.cov th{text-align:left;font-weight:500;color:var(--ink-faint);background:var(--panel2);\n    padding:7px 11px;border-bottom:1px solid var(--line2);font-size:10px;\n    letter-spacing:.06em;text-transform:uppercase;white-space:nowrap}\n  table.cov td{padding:6px 11px;border-bottom:1px solid var(--line);vertical-align:top}\n  table.cov tr:last-child td{border-bottom:none}\n  table.cov tr.cov-bad td{background:rgba(255,122,147,.08)}\n  table.cov tr.cov-warn td{background:rgba(244,185,66,.07)}\n  table.cov tr.cov-info td{background:rgba(91,156,255,.06)}\n  table.cov .miss{color:var(--cov-miss)}\n  table.cov .arrow{color:var(--ink-faint)}\n  .covlegend{display:flex;flex-wrap:wrap;gap:14px;font-size:10.5px;color:var(--ink-faint);margin:4px 0 8px}\n  .covlegend span{display:inline-flex;align-items:center;gap:5px}\n  .covdot{display:inline-block;width:7px;height:7px;border-radius:50%;flex:none}\n  .oprow.cov-bad{box-shadow:inset 3px 0 0 var(--cov-bad)}\n  .oprow.cov-warn{box-shadow:inset 3px 0 0 var(--cov-warn)}\n\n  /* operations — each collapsible, params reveal as a single-column list */\n  details.op,.op.flat{border:1px solid var(--line);border-radius:8px;margin:6px 0;\n    background:var(--panel);overflow:hidden}\n  details.op>summary,.op.flat{display:flex;align-items:center;gap:10px;padding:8px 12px;\n    font-family:var(--mono);font-size:11.5px;color:var(--ink)}\n  details.op>summary{cursor:pointer;list-style:none}\n  details.op>summary::-webkit-details-marker{display:none}\n  details.op>summary:before{content:"▸";color:var(--ink-faint);flex:none;font-size:10px}\n  details.op[open]>summary:before{content:"▾"}\n  .op.flat:before{content:"·";color:var(--ink-faint);flex:none;font-size:10px}\n  details.op>summary:hover{background:var(--hover)}\n  details.op[open]>summary{border-bottom:1px solid var(--line)}\n  .opname{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n  .opcount{margin-left:auto;flex:none;color:var(--ink-faint);font-size:10px;\n    border:1px solid var(--line2);border-radius:999px;padding:1px 8px}\n  .opkey{flex:none;color:var(--ink-faint)}\n  .parmgrid{display:grid;grid-template-columns:1fr;gap:1px;background:var(--line)}\n  .parmgrid .pc{display:flex;justify-content:space-between;align-items:baseline;gap:10px;\n    background:var(--panel);padding:5px 12px;min-width:0;font-family:var(--mono);font-size:11.5px}\n  .parmgrid .pn{color:var(--ink-dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n  .parmgrid .pt{color:var(--ink-faint);flex:none}\n\n  /* ------------------------------------------------------------------ */\n  /* Responsive: ≤1100px the rail collapses to dot-only chips (tooltips  */\n  /* carry the labels); ≤800px the columns stack and the page scrolls.   */\n  /* ------------------------------------------------------------------ */\n  @media (max-width:1100px){\n    .layout{grid-template-columns:56px 280px 1fr}\n    .rail{padding:14px 4px}\n    .rail .sec{padding:10px 0 2px;text-align:center;letter-spacing:0;overflow:hidden;white-space:nowrap}\n    .cat{justify-content:center;padding:8px 6px}\n    .cat .lbl,.cat .n{display:none}\n    .brand .proj{display:none}\n    .stats>span{display:none}\n    .stats>#diagbtn,.stats>#cfnbtn{display:inline}\n  }\n  @media (max-width:800px){\n    html,body{height:auto}\n    header{flex-wrap:wrap;height:auto;min-height:54px;padding:8px 12px;gap:10px}\n    .search{order:3;flex-basis:100%;max-width:none;margin:0}\n    .layout{display:flex;flex-direction:column;height:auto}\n    .col{border-right:none;border-bottom:1px solid var(--line);max-height:42vh}\n    .col.detail{max-height:none;border-bottom:none}\n    .rail{display:flex;flex-wrap:wrap;gap:2px;padding:8px}\n    .rail .sec{display:none}\n    .cat{padding:6px 10px}\n    .cat .lbl,.cat .n{display:inline}\n    .dbody{padding:16px 14px}\n  }\n',
    'explorer.js': '// Data arrives as a JSON island (<script type="application/json" id="atlas-data">):\n// JSON.parse is faster than a JS literal for large payloads and needs no JS escaping.\nconst DATA = JSON.parse(document.getElementById(\'atlas-data\').textContent);\nconst nodes = DATA.nodes, edges = DATA.edges;\nconst byId = new Map(nodes.map(n => [n.id, n]));\nconst TM = {\n  app:[\'Apps\',\'Models\'],process:[\'Processes\',\'Models\'],case:[\'Cases\',\'Models\'],\n  decision:[\'Decisions\',\'Models\'],form:[\'Forms\',\'Models\'],page:[\'Pages\',\'Models\'],\n  dataObject:[\'Data objects\',\'Models\'],dataDictionary:[\'Data dictionaries\',\'Models\'],\n  masterData:[\'Master data\',\'Models\'],\n  service:[\'Service models\',\'Integration\'],agent:[\'Agents / bots\',\'Integration\'],\n  channel:[\'Channels\',\'Integration\'],event:[\'Events\',\'Integration\'],knowledgeBase:[\'Knowledge bases\',\'Integration\'],\n  endpoint:[\'REST endpoints\',\'Code\'],java:[\'Java classes\',\'Code\'],method:[\'Java methods\',\'Code\'],liquibase:[\'Liquibase changelogs\',\'Code\'],\n  action:[\'Actions\',\'Integration\'],bot:[\'Bots\',\'Integration\'],\n  query:[\'Queries\',\'Other\'],template:[\'Templates\',\'Other\'],sequence:[\'Sequences\',\'Other\'],\n  document:[\'Documents\',\'Other\'],variableExtractor:[\'Variable extractors\',\'Other\'],\n  sla:[\'SLAs\',\'Other\'],dashboardComponent:[\'Dashboard widgets\',\'Other\'],\n  securityPolicy:[\'Security policies\',\'Access\'],group:[\'User groups\',\'Access\'],\n  variable:[\'Variables\',\'Variables\'],\n  expression:[\'Backend expressions ${ }\',\'Expressions\'],binding:[\'Frontend bindings {{ }}\',\'Expressions\'],\n  string:[\'String literals\',\'Expressions\'],customFunction:[\'Custom functions 🧩\',\'Expressions\'],\n  external:[\'External / library\',\'Other\'],\n};\nconst SECTIONS = [\'Models\',\'Integration\',\'Code\',\'Expressions\',\'Variables\',\'Access\',\'Other\'];\n// Colors are emitted as var() references, not resolved values: the browser resolves them\n// at paint time, so a theme switch restyles everything without any re-render (and there is\n// no getComputedStyle per node, which used to force a style recalculation in large lists).\nconst color = t => \'var(--c-\'+t+\', #8aa0b4)\';\nconst covColor = k => \'var(--cov-\'+k+\', #8aa0b4)\';\nconst debounce = (fn,ms) => { let t; return function(){ clearTimeout(t); t=setTimeout(()=>fn.apply(this,arguments),ms); }; };\nconst looseCol = s => String(s==null?\'\':s).toLowerCase().replace(/[^a-z0-9]/g,\'\');\n// external nodes split into Flowable API / navigation routes / real third-party deps.\nconst nodeColor = n => (n && n.type===\'external\')\n  ? (n.data&&n.data.flowableApi?color(\'endpoint\'):n.data&&n.data.route?color(\'page\'):color(\'external\'))\n  : color(n?n.type:\'\');\nconst nodeKind = n => (n.type!==\'external\')\n  ? (TM[n.type]?TM[n.type][0]:n.type)\n  : (n.data.flowableApi?\'Flowable API\':n.data.route?\'Navigation route\':\'External / library\');\n\n// adjacency\nconst outM = new Map(), incM = new Map();\nconst push = (m,k,v)=>{ if(!m.has(k)) m.set(k,[]); m.get(k).push(v); };\nedges.forEach(e=>{ push(outM,e.s,{rel:e.rel,id:e.t}); push(incM,e.t,{rel:e.rel,id:e.s}); });\n\n// bean name -> java node id (for direct links from ${bean.method()} expressions)\nconst beanToNode = new Map();\nnodes.filter(n=>n.type===\'java\').forEach(n=>{\n  (n.data.beanNames||[]).forEach(b=>beanToNode.set(b,n.id));\n  const dc=n.label.charAt(0).toLowerCase()+n.label.slice(1);\n  if(!beanToNode.has(dc)) beanToNode.set(dc,n.id);\n});\n\n// a form is "unused / unlinked" when nothing functionally references it — i.e. it\n// has no incoming edge other than app \'contains\' membership (every form sits in an\n// app, so that edge alone does not count as being used).\nconst isUnusedForm = n => n.type===\'form\' && !(incM.get(n.id)||[]).some(e=>e.rel!==\'contains\');\n\n// state — navigation history lives in the URL hash (browser back/forward just works);\n// `trail` only remembers recently visited labels for the breadcrumb display.\nlet state = {cat:null, sel:null, trail:[], filter:\'\'};\n\n// ---------- categories ----------\nfunction categories(){\n  const byType = {};\n  nodes.forEach(n => (byType[n.type] = byType[n.type]||[]).push(n));\n  const cats = [];\n  Object.keys(byType).forEach(t=>{\n    if(t===\'java\'){\n      const roles = {};\n      byType.java.forEach(n=>(n.data.roles||[]).forEach(r=>roles[r]=(roles[r]||0)+1));\n      Object.keys(roles).sort().forEach(r=>cats.push({\n        id:\'java::\'+r, label:\'Java · \'+r, sec:\'Code\', color:color(\'java\'), count:roles[r],\n        match:n=>n.type===\'java\' && (n.data.roles||[]).includes(r)}));\n    } else if(t===\'variable\'){\n      // group variables by the model type(s) that use them (process / form / case / java …)\n      const scopes = {};\n      byType.variable.forEach(n=>(n.data.scopes||[]).forEach(s=>scopes[s]=(scopes[s]||0)+1));\n      Object.keys(scopes).sort().forEach(s=>cats.push({\n        id:\'variable::\'+s, label:\'Variable · \'+s, sec:\'Variables\',\n        color:color(\'variable\'), count:scopes[s], match:n=>n.type===\'variable\' && (n.data.scopes||[]).includes(s)}));\n    } else if(t===\'external\'){\n      // external nodes are not all "library": split out Flowable platform API calls\n      // (endpoints.*) and in-app navigation routes (#/...) from real third-party deps.\n      [{id:\'external::api\',  label:\'Flowable API\',        sec:\'Integration\', color:color(\'endpoint\'), match:n=>n.type===\'external\'&&n.data.flowableApi},\n       {id:\'external::route\',label:\'Navigation · routes\', sec:\'Other\',       color:color(\'page\'),     match:n=>n.type===\'external\'&&n.data.route},\n       {id:\'external::missing\',label:\'Missing model refs\',sec:\'Other\',       color:color(\'external\'), match:n=>n.type===\'external\'&&n.data.missingModel},\n       {id:\'external::lib\',  label:\'External / library\',  sec:\'Other\',       color:color(\'external\'), match:n=>n.type===\'external\'&&!n.data.flowableApi&&!n.data.route&&!n.data.missingModel}\n      ].forEach(c=>{ const count=byType.external.filter(c.match).length; if(count) cats.push(Object.assign({count}, c)); });\n    } else {\n      const m = TM[t]||[t,\'Other\'];\n      cats.push({id:t,label:m[0],sec:m[1],color:color(t),count:byType[t].length,match:n=>n.type===t});\n    }\n  });\n  // a review list: forms that nothing links to (orphaned UI models worth pruning)\n  const unusedForms = nodes.filter(isUnusedForm);\n  if(unusedForms.length) cats.push({id:\'unused-form\', label:\'Forms · unused\', sec:\'Models\',\n    color:color(\'form\'), count:unusedForms.length, match:isUnusedForm});\n  // Review lists for flagged expressions/bindings. Structural syntax errors make an\n  // expression *invalid*; catalog findings (unknown function/namespace — the catalog may\n  // simply not know a project-registered function) only make it *suspect*.\n  const isExprN = n => n.type===\'expression\'||n.type===\'binding\';\n  const hasErr = n => isExprN(n) && (n.data.problems||[]).some(p=>p.severity===\'error\');\n  const hasWarnOnly = n => isExprN(n) && (n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity===\'error\');\n  const invalidExprs = nodes.filter(hasErr);\n  if(invalidExprs.length) cats.push({id:\'invalid-expr\', label:\'Invalid — syntax ⚠\', sec:\'Expressions\',\n    color:color(\'invalidExpr\'), count:invalidExprs.length, match:hasErr});\n  const suspectExprs = nodes.filter(hasWarnOnly);\n  if(suspectExprs.length) cats.push({id:\'suspect-expr\', label:\'Suspect — review\', sec:\'Expressions\',\n    color:color(\'suspectExpr\'), count:suspectExprs.length, match:hasWarnOnly});\n  cats.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) || a.label.localeCompare(b.label));\n  return cats;\n}\nconst CATS = categories();\n\nfunction renderRail(){\n  const rail = document.getElementById(\'rail\'); rail.innerHTML=\'\';\n  let cur=\'\';\n  CATS.forEach(c=>{\n    if(c.sec!==cur){ cur=c.sec; const h=document.createElement(\'div\'); h.className=\'sec\'; h.textContent=cur; rail.appendChild(h); }\n    const on = state.cat===c.id;\n    const el=document.createElement(\'div\'); el.className=\'cat\'+(on?\' on\':\'\');\n    el.setAttribute(\'role\',\'button\');\n    el.setAttribute(\'aria-pressed\', on?\'true\':\'false\');\n    el.tabIndex = 0;                              // every category is tabbable; arrows also work\n    el.title = c.label+\' (\'+c.count+\')\';          // label survives the collapsed ≤1100px rail\n    el.innerHTML=\'<span class="dot" style="background:\'+c.color+\'"></span><span class="lbl">\'+esc(c.label)+\'</span><span class="n">\'+c.count+\'</span>\';\n    const activate=()=>{ state.cat=c.id; state.filter=\'\'; renderRail(); renderList(); };\n    el.onclick=activate;\n    el.onkeydown=e=>{\n      if(e.key===\'Enter\'||e.key===\' \'){ e.preventDefault(); activate(); }\n      else if(e.key===\'ArrowDown\'||e.key===\'ArrowUp\'){\n        e.preventDefault();\n        const cats=[...rail.querySelectorAll(\'.cat\')];\n        const i=cats.indexOf(el)+(e.key===\'ArrowDown\'?1:-1);\n        if(cats[i]) cats[i].focus();\n      }\n    };\n    rail.appendChild(el);\n  });\n}\n\nfunction renderList(){\n  const cat = CATS.find(c=>c.id===state.cat);\n  const list = document.getElementById(\'list\'); list.innerHTML=\'\';\n  if(!cat) return;\n  const head=document.createElement(\'div\'); head.className=\'listhead\';\n  head.innerHTML=\'<div class="t"><span>\'+esc(cat.label)+\'</span><span class="muted">\'+cat.count+\'</span></div>\'+\n    \'<input id="lf" placeholder="filter \'+esc(cat.label.toLowerCase())+\'…" aria-label="Filter list">\';\n  list.appendChild(head);\n  const wrap=document.createElement(\'div\'); wrap.id=\'listitems\';\n  wrap.setAttribute(\'role\',\'listbox\');\n  wrap.setAttribute(\'aria-label\',cat.label);\n  list.appendChild(wrap);\n  renderItems(cat, wrap);\n  // The input lives outside the re-rendered items wrap, so typing never loses focus.\n  const lf=document.getElementById(\'lf\'); lf.value=state.filter;\n  lf.oninput=debounce(()=>{ state.filter=lf.value; renderItems(cat, wrap); },120);\n  // Arrow/Enter keyboard navigation over the items (roving focus).\n  wrap.onkeydown=e=>{\n    const els=[...wrap.querySelectorAll(\'.item[data-id]\')];\n    const i=els.indexOf(document.activeElement);\n    if(e.key===\'ArrowDown\'||e.key===\'ArrowUp\'){\n      e.preventDefault();\n      const j=e.key===\'ArrowDown\'?Math.min(i+1,els.length-1):Math.max(i-1,0);\n      if(els[j]) els[j].focus();\n    } else if(e.key===\'Home\'&&els[0]){ e.preventDefault(); els[0].focus(); }\n    else if(e.key===\'End\'&&els[els.length-1]){ e.preventDefault(); els[els.length-1].focus(); }\n    else if((e.key===\'Enter\'||e.key===\' \')&&i>=0){ e.preventDefault(); select(els[i].dataset.id); }\n  };\n}\n\n// Incremental rendering: 200 rows at a time, the IntersectionObserver on a trailing\n// sentinel appends the next chunk when it scrolls into view — every item of a large\n// category is reachable by scrolling (the old hard cap cut off at 600).\nconst LIST_CHUNK=200;\nlet _listIO=null;\nfunction renderItems(cat, wrap){\n  if(_listIO){ _listIO.disconnect(); _listIO=null; }\n  wrap.innerHTML=\'\';\n  let items = nodes.filter(cat.match);\n  const f = state.filter.toLowerCase();\n  if(f) items = items.filter(n => (n.label+\' \'+n.key+\' \'+(n.file||\'\')).toLowerCase().includes(f));\n  items.sort((a,b)=>a.label.localeCompare(b.label));\n  const sentinel=document.createElement(\'div\'); sentinel.className=\'sentinel\';\n  wrap.appendChild(sentinel);\n  let idx=0;\n  function makeItem(n,i){\n    const el=document.createElement(\'div\'); el.className=\'item\'+(state.sel===n.id?\' on\':\'\');\n    el.dataset.id=n.id;\n    el.setAttribute(\'role\',\'option\');\n    el.setAttribute(\'aria-selected\', state.sel===n.id?\'true\':\'false\');\n    el.tabIndex=-1;\n    el.style.animationDelay=Math.min(i*8,300)+\'ms\';\n    el.innerHTML=\'<span class="dot" style="margin-top:5px;background:\'+nodeColor(n)+\'"></span>\'+\n      \'<div class="meta"><div class="nm">\'+esc(n.label)+authBadge(n)+\'</div><div class="sub">\'+esc(n.key)+\'</div></div>\';\n    el.onclick=()=>select(n.id);\n    return el;\n  }\n  function append(){\n    const slice=items.slice(idx, idx+LIST_CHUNK);\n    slice.forEach((n,i)=>wrap.insertBefore(makeItem(n,i), sentinel));\n    if(idx===0 && wrap.querySelector(\'.item\')) wrap.querySelector(\'.item\').tabIndex=0;\n    idx+=slice.length;\n    if(idx>=items.length){ if(_listIO){ _listIO.disconnect(); _listIO=null; } sentinel.remove(); }\n  }\n  _listIO=new IntersectionObserver(es=>{ if(es.some(e=>e.isIntersecting)) append(); },\n                                   {root: wrap.closest(\'.col\'), rootMargin:\'600px\'});\n  _listIO.observe(sentinel);\n  append();\n}\n\n// Selection within the current category only toggles classes — no full list rebuild.\nfunction syncListSelection(){\n  let hit=null;\n  document.querySelectorAll(\'#list .item[data-id]\').forEach(el=>{\n    const on = el.dataset.id===state.sel;\n    el.classList.toggle(\'on\', on);\n    el.setAttribute(\'aria-selected\', on?\'true\':\'false\');\n    if(on) hit=el;\n  });\n  if(hit) hit.scrollIntoView({block:\'nearest\'});\n}\n\n// ---------- detail ----------\nfunction relName(r){ return r; }\nfunction nodeChip(id){\n  const n=byId.get(id); if(!n) return \'\';\n  return \'<span class="nc" data-id="\'+enc(id)+\'" tabindex="0" role="link"><span class="dot" style="background:\'+nodeColor(n)+\'"></span>\'+\n    \'<span class="nm">\'+esc(n.label)+\'</span><span class="ty">\'+esc(nodeKind(n))+\'</span></span>\';\n}\nfunction groupRels(arr){ const g={}; (arr||[]).forEach(x=>{ (g[x.rel]=g[x.rel]||new Set()).add(x.id); }); return g; }\n// Small badge marking a changelog as the live definition of its table vs a superseded/orphan revision.\nfunction authBadge(n){\n  if(n.type!==\'liquibase\') return \'\';\n  const a=(n.data||{}).authority; if(!a||!a.status) return \'\';\n  if(a.status===\'live\'){ const by=(a.referencedBy||[]).join(\', \');\n    return \'<span class="authb authb-live" title="Live / authoritative\'+(by?\' — referenced by \'+esc(by):\'\')+\'">live</span>\'; }\n  if(a.status===\'superseded\'){ const by=(a.supersededBy||[]).join(\', \');\n    return \'<span class="authb authb-old" title="Superseded — the same table is provided by \'+esc(by||\'a referenced changelog\')+\'">superseded</span>\'; }\n  return \'<span class="authb authb-orphan" title="Orphan — not referenced by any service or data object">orphan</span>\';\n}\n\nfunction describe(n){\n  const d=n.data||{}, rows=[];\n  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==\'\'&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };\n  if(n.type===\'process\'){ add(\'Starter groups\',d.candidateStarterGroups); add(\'User tasks\',(d.userTasks||[]).length);\n    add(\'Service tasks\',(d.serviceTasks||[]).length); add(\'Call activities\',(d.callActivities||[]).length);\n    add(\'Documentation\',d.documentation); }\n  else if(n.type===\'case\'){ add(\'Starter groups\',d.candidateStarterGroups); add(\'Initiator var\',d.initiatorVariableName); add(\'Documentation\',d.documentation); }\n  else if(n.type===\'decision\'){ add(\'Hit policy\',d.hitPolicy); add(\'Rules\',d.ruleCount); add(\'Inputs\',(d.inputs||[]).join(\', \')); add(\'Outputs\',(d.outputs||[]).join(\', \')); }\n  else if(n.type===\'form\'||n.type===\'page\'){ add(\'Fields\',(d.fields||[]).length); add(\'Outcomes\',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(\', \')); }\n  else if(n.type===\'dataObject\'){ add(\'Type\',d.dataObjectType); add(\'Data source\',d.sourceId); add(\'Backing service\',d.service);\n    // When backed by a service, surface that service\'s physical table here and link the name back to the service node.\n    const svc=d.service&&byId.get(\'service:\'+d.service), tbl=d.serviceTableName||(svc&&(svc.data||{}).tableName);\n    if(tbl) rows.push([\'Table\',{html:\'<span class="vlink" data-id="\'+enc(\'service:\'+d.service)+\'" tabindex="0" role="link" title="Provided by service \'+esc(d.service)+\'">\'+esc(tbl)+\'</span>\'}]);\n    add(\'Data dictionary\',d.dictionary); add(\'Columns\',(d.fields||[]).length); }\n  else if(n.type===\'service\'){ add(\'Type\',d.type); add(\'Base URL\',d.baseUrl); add(\'Auth\',d.auth); add(\'Table\',d.tableName); add(\'Liquibase model\',d.referencedLiquibaseModelKey); add(\'Columns\',(d.columns||[]).length); add(\'Operations\',(d.operations||[]).length);\n    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) add(\'Schema gaps\',g+\' of \'+(c.total||0)+\' columns\'); } }\n  else if(n.type===\'agent\'){ add(\'Vendor / model\',(d.aiVendor||\'\')+\' / \'+(d.modelName||\'\')); add(\'Temperature\',d.temperature); add(\'API endpoint\',String(d.enableApiEndpoint)); add(\'Knowledge base\',d.knowledgeBase); }\n  else if(n.type===\'channel\'){ add(\'Direction\',d.channelType); add(\'Type\',d.type); add(\'Topics\',(d.topics||[]).join(\', \')); add(\'Destination\',d.destination); }\n  else if(n.type===\'event\'){ add(\'Payload\',(d.payload||[]).join(\', \')); add(\'Correlation\',(d.correlation||[]).join(\', \')); }\n  else if(n.type===\'java\'){ add(\'Package\',d.package); add(\'Roles\',(d.roles||[]).join(\', \')); add(\'Bot key\',d.botKey); add(\'Implements\',(d.interfaces||[]).join(\', \')); add(\'Methods\',(d.methods||[]).length); add(\'Called from models\',(d.calledMethods||[]).join(\', \')); }\n  else if(n.type===\'endpoint\'){ add(\'Method\',d.http); add(\'Path\',d.path); add(\'Handler\',(d.controller||\'\')+\'#\'+(d.handler||\'\')); }\n  else if(n.type===\'method\'){ add(\'Method\',(d.name||\'\')+\'()\'); add(\'Declared in\',d.class); }\n  else if(n.type===\'query\'){ add(\'Source index\',d.sourceIndex); add(\'Parameters\',(d.parameters||[]).join(\', \')); add(\'Filters by groups\',(d.groups||[]).length); }\n  else if(n.type===\'action\'){ add(\'Bot\',d.botKey); add(\'Form\',d.formKey); add(\'Triggers signal\',d.signalName); add(\'Scope\',d.scopeType); }\n  else if(n.type===\'bot\'){ add(\'Kind\',d.platform?\'Flowable platform bot\':\'project-defined bot\'); }\n  else if(n.type===\'liquibase\'){ const a=d.authority||{};\n    add(\'Status\', a.status===\'live\'?\'live (authoritative)\':a.status===\'superseded\'?\'superseded revision\':a.status===\'orphan\'?\'orphan — unreferenced\':undefined);\n    if((a.referencedBy||[]).length) add(\'Referenced by\',(a.referencedBy||[]).join(\', \'));\n    if((a.supersededBy||[]).length) add(\'Live definition\',(a.supersededBy||[]).join(\', \'));\n    add(\'Tables\',(d.effectiveTables||d.tables||[]).join(\', \')); add(\'Columns\',(d.columns||[]).length); }\n  else if(n.type===\'expression\'||n.type===\'binding\'){ add(\'Used by\', (d.usedBy||[]).length+\' model(s)\');\n    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity===\'error\').length, wc=pr.length-ec;\n      add(\'Problems\',[ec?ec+\' error\'+(ec>1?\'s\':\'\'):\'\', wc?wc+\' warning\'+(wc>1?\'s\':\'\'):\'\'].filter(Boolean).join(\', \')); } }\n  else if(n.type===\'variable\'){ add(\'Scope\',(d.scopes||[]).join(\', \')); add(\'Used in\', (d.usages||[]).length+\' model(s)\'); }\n  else if(n.type===\'string\'){ add(\'Used in\', (d.usages||[]).length+\' model(s)\'); }\n  else if(n.type===\'customFunction\'){\n    add(\'Kind\', d.kind===\'namespace\'?(\'namespace \'+d.namespace+\'.*\'):d.kind===\'flw\'?\'flw.* member\':\'top-level\');\n    add(\'Signature\', d.member+\'(\'+(d.signature!=null?d.signature:\'…\')+\')\');\n    add(\'Registered in\',(d.sources||[]).join(\', \')); add(\'Used by\', (d.usedBy||[]).length+\' form(s) / model(s)\'); }\n  else if(n.type===\'external\'){ add(\'Kind\',d.flowableApi?\'Flowable platform API\':d.route?\'In-app navigation route\':d.platform?\'Flowable platform bean\':d.missingModel?\'Missing model reference (\'+(d.kind||\'model\')+\')\':(d.external_url?\'External URL\':d.kind||\'external\')); if(d.method&&d.method!==\'(button)\') add(\'Method\',d.method); }\n  else { Object.keys(d).forEach(k=>{ const v=d[k]; if(typeof v===\'string\'||typeof v===\'number\') add(k,v); }); }\n  return rows;\n}\n\nfunction detailExtra(n){\n  const d=n.data||{}; let h=\'\';\n  if(n.type===\'service\' && (d.operations||[]).length){\n    h+=\'<h3 class="rel">Operations (\'+d.operations.length+\')</h3>\'+\n      d.operations.map(o=>{\n        const verb=o.method?\'<span class="verb" style="color:\'+color("endpoint")+\'">\'+esc(o.method)+\'</span>\':\'\';\n        const title=\'<span class="opname">\'+esc(o.fullUrl||o.url||o.name||\'\')+\'</span>\';\n        const key=\'<span class="opkey">\'+esc(o.key||\'\')+\'</span>\';\n        const np=(o.params||[]).length;\n        if(!np) return \'<div class="op flat">\'+verb+title+\'<span class="opcount">no params</span>\'+key+\'</div>\';\n        return \'<details class="op"><summary>\'+verb+title+\n          \'<span class="opcount">\'+np+\' param\'+(np>1?\'s\':\'\')+\'</span>\'+key+\'</summary>\'+\n          \'<div class="parmgrid">\'+o.params.map(p=>\'<div class="pc"><span class="pn">\'+esc(p.name)+\'</span>\'+\n            (p.type?\'<span class="pt">\'+esc(p.type)+\'</span>\':\'\')+\'</div>\').join(\'\')+\'</div></details>\';\n      }).join(\'\');\n  }\n  if(n.type===\'service\' && d.schemaCoverage && (d.schemaCoverage.rows||[]).length){\n    const sc=d.schemaCoverage, ct=sc.counts||{};\n    h+=\'<h3 class="rel">Schema coverage — Liquibase → Service → DataObject</h3>\';\n    // source changelog + backing data objects (clickable)\n    let meta=\'\';\n    if(sc.liquibase){ const lc=nodeChip(\'liquibase:\'+sc.liquibase); if(lc) meta+=\'<span class="muted">changelog</span>\'+lc; }\n    (sc.dataObjects||[]).forEach(k=>{ const dc=nodeChip(\'dataObject:\'+k); if(dc) meta+=dc; });\n    if(meta) h+=\'<div class="covmeta">\'+meta+\'</div>\';\n    // gap summary\n    let badges=\'\';\n    if(ct.noService) badges+=\'<span class="cov-badge cov-bad">\'+ct.noService+\' not mapped in service</span>\';\n    if(ct.noDataObject) badges+=\'<span class="cov-badge cov-warn">\'+ct.noDataObject+\' not in data object</span>\';\n    if(ct.extra) badges+=\'<span class="cov-badge cov-info">\'+ct.extra+\' not in Liquibase</span>\';\n    if(ct.ok) badges+=\'<span class="cov-badge cov-good">\'+ct.ok+\' mapped through</span>\';\n    if(badges) h+=\'<div class="covbadges">\'+badges+\'</div>\';\n    const rowCls={\'no-service\':\'cov-bad\',\'no-dataobject\':\'cov-warn\',\'extra-service\':\'cov-info\',\'ok\':\'\'};\n    const miss=\'<span class="miss">✗ not mapped</span>\';\n    h+=\'<div class="covwrap"><table class="cov"><thead><tr>\'+\n       \'<th>Liquibase column</th><th>Service mapping</th><th>Data object field</th></tr></thead><tbody>\';\n    sc.rows.forEach(r=>{\n      const lbCell = r.inLiquibase\n        ? \'<span>\'+esc(r.sql)+\'</span>\'+(r.sqlType?\' <span class="muted">\'+esc(r.sqlType)+\'</span>\':\'\')\n        : \'<span class="miss">— not in changelog</span>\';\n      const svCell = r.inService\n        ? \'<span>\'+esc(r.service||r.serviceCol||\'\')+\'</span>\'+\n          (r.serviceCol&&looseCol(r.serviceCol)!==looseCol(r.service||\'\')?\' <span class="muted">\'+esc(r.serviceCol)+\'</span>\':\'\')+\n          (r.serviceType?\' <span class="muted">\'+esc(r.serviceType)+\'</span>\':\'\')\n        : miss;\n      const doCell = (r.dataObjects&&r.dataObjects.length)\n        ? r.dataObjects.map(x=>\'<span>\'+esc(x.field)+\'</span>\'+\n            ((sc.dataObjects||[]).length>1?\' <span class="muted">\'+esc(x.do)+\'</span>\':\'\')).join(\', \')\n        : (r.inLiquibase||r.inService?miss:\'\');\n      h+=\'<tr class="\'+(rowCls[r.status]||\'\')+\'"><td>\'+lbCell+\'</td><td>\'+svCell+\'</td><td>\'+doCell+\'</td></tr>\';\n    });\n    h+=\'</tbody></table></div>\';\n  }\n  else if(n.type===\'service\' && (d.columns||[]).length){\n    h+=\'<h3 class="rel">Columns / field mappings (\'+d.columns.length+\')</h3><div class="oplist">\'+\n      d.columns.map(c=>\'<div class="oprow"><span>\'+esc(c.name||\'\')+\'</span>\'+\n        (c.columnName&&c.columnName!==c.name?\'<span class="muted">\'+esc(c.columnName)+\'</span>\':\'\')+\n        (c.type?\'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">\'+esc(c.type)+\'</span>\':\'\')+\n        \'</div>\').join(\'\')+\'</div>\';\n  }\n  if(n.type===\'java\' && (d.endpoints||[]).length){\n    h+=\'<h3 class="rel">Endpoints served</h3><div class="oplist">\'+\n      d.endpoints.map(e=>\'<div class="oprow"><span class="verb" style="color:\'+color("endpoint")+\'">\'+esc(e.http)+\'</span><span>\'+esc(e.path)+\'</span><span class="muted">\'+esc(e.handler)+\'() :\'+e.line+\'</span></div>\').join(\'\')+\'</div>\';\n  }\n  if(n.type===\'java\' && (d.methods||[]).length){\n    const cm=new Set(d.calledMethods||[]);\n    h+=\'<h3 class="rel">Declared methods (\'+d.methods.length+\')</h3><div class="oplist">\'+\n      d.methods.slice(0,80).map(m=>\'<div class="oprow"><span>\'+esc(m.name)+\'(\'+m.params+\')</span><span class="muted">:\'+m.line+(cm.has(m.name)?\'  ◀ called by models\':\'\')+\'</span></div>\').join(\'\')+\'</div>\';\n  }\n  if((n.type===\'process\') && (d.serviceTasks||[]).length){\n    const st=d.serviceTasks.filter(s=>s.class||s.delegateExpression||s.expression||s.type);\n    if(st.length) h+=\'<h3 class="rel">Service tasks</h3><div class="oplist">\'+\n      st.map(s=>\'<div class="oprow"><span class="muted" style="min-width:150px">\'+esc(s.name||s.id)+\'</span>\'+\n        \'<span style="flex:1">\'+esc(s.class||s.delegateExpression||s.expression||s.type||\'\')+\'</span>\'+\n        implLink(s)+\'</div>\').join(\'\')+\'</div>\';\n  }\n  if(n.type===\'dataObject\' && (d.columns||[]).length){\n    h+=\'<h3 class="rel">Columns / field mappings (\'+d.columns.length+\')</h3><div class="oplist">\'+\n      d.columns.map(c=>\'<div class="oprow"><span>\'+esc(c.name)+\'</span><span class="muted">\'+esc(c.label||\'\')+\'</span>\'+\n        (c.refDataObject?\'<span class="vlink" data-id="\'+enc(\'dataObject:\'+c.refDataObject)+\'" tabindex="0" role="link">→ \'+esc(c.refDataObject)+(c.relationship?\' (\'+esc(c.relationship)+\')\':\'\')+\'</span>\':\'\')+\n        (c.type?\'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">\'+esc(c.type)+\'</span>\':\'\')+\n        \'</div>\').join(\'\')+\'</div>\';\n  }\n  if(n.type===\'liquibase\'){\n    const a=d.authority||{};\n    if(a.status===\'superseded\'){ const chips=(a.supersededBy||[]).map(k=>nodeChip(\'liquibase:\'+k)).join(\'\');\n      h+=\'<div class="authnote authnote-old">⚠ Superseded revision — the live definition of <b>\'+esc((d.effectiveTables||[]).join(\', \'))+\'</b> is referenced elsewhere. These columns reflect an older revision of the same table.\'+(chips?\'<div>\'+chips+\'</div>\':\'\')+\'</div>\'; }\n    else if(a.status===\'orphan\'){\n      h+=\'<div class="authnote authnote-orphan">⚠ Orphan changelog — no service or data object references it. It may be dead/legacy or referenced only at runtime.</div>\'; }\n  }\n  if(n.type===\'liquibase\' && (d.columns||[]).length){\n    const cov=d.coverage;                    // present only when a service references this changelog\n    const inS=cov?new Set(cov.service||[]):null, inD=cov?new Set(cov.dataObject||[]):null;\n    const stOf=k=>!inS.has(k)?\'bad\':(!inD.has(k)?\'warn\':\'good\');\n    const stTitle={bad:\'not mapped by any service\',warn:\'mapped in service, but no data object field\',good:\'mapped through to a data object\'};\n    const byT={}; d.columns.forEach(c=>{ (byT[c.table||\'(table)\']=byT[c.table||\'(table)\']||[]).push(c); });\n    h+=\'<h3 class="rel">Columns (\'+d.columns.length+\')\'+(cov?\' — mapping coverage\':\'\')+\'</h3>\';\n    if(cov) h+=\'<div class="covlegend">\'+\n      \'<span><span class="covdot" style="background:\'+covColor(\'bad\')+\'"></span>not in service</span>\'+\n      \'<span><span class="covdot" style="background:\'+covColor(\'warn\')+\'"></span>not in data object</span>\'+\n      \'<span><span class="covdot" style="background:\'+covColor(\'good\')+\'"></span>mapped through</span></div>\';\n    Object.keys(byT).forEach(t=>{\n      h+=\'<div style="margin:6px 0 12px"><div class="muted mono" style="margin-bottom:4px">\'+esc(t)+\'</div><div class="oplist">\'+\n        byT[t].map(c=>{ const st=cov?stOf(looseCol(c.name)):null;\n          return \'<div class="oprow\'+(st===\'bad\'?\' cov-bad\':st===\'warn\'?\' cov-warn\':\'\')+\'">\'+\n          (cov?\'<span class="covdot" title="\'+stTitle[st]+\'" style="background:\'+covColor(st)+\'"></span>\':\'\')+\n          \'<span>\'+esc(c.name)+\'</span>\'+\n          (c.type?\'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">\'+esc(c.type)+\'</span>\':\'\')+\n          \'</div>\'; }).join(\'\')+\'</div></div>\';\n    });\n  }\n  if((n.type===\'expression\'||n.type===\'binding\') && (d.problems||[]).length){\n    h+=\'<h3 class="rel">Problems (\'+d.problems.length+\')</h3><div class="oplist">\'+\n      d.problems.map(p=>{\n        const isErr=p.severity===\'error\';\n        const col=isErr?color(\'invalidExpr\'):color(\'suspectExpr\');\n        const snip=p.snippet||\'\';\n        return \'<div class="oprow"><span class="verb" style="color:\'+col+\'">\'+(isErr?\'error\':\'warning\')+\'</span>\'+\n          \'<span style="flex:1">\'+esc(p.message)+\'</span>\'+\n          (snip?\'<span class="mono" style="color:var(--ink-faint);font-size:10px">\'+esc(snip)+\'</span>\':\'\')+\n          \'</div>\';\n      }).join(\'\')+\'</div>\';\n  }\n  if((n.type===\'expression\'||n.type===\'binding\'||n.type===\'customFunction\') && (d.usedBy||[]).length){\n    h+=\'<h3 class="rel">Used by (\'+d.usedBy.length+\')</h3><div class="nodechips">\'+d.usedBy.map(nodeChip).join(\'\')+\'</div>\';\n  }\n  // a frontend binding links to the custom function(s) it calls; a custom function links back to the\n  // exact bindings that call it (in addition to the forms/models under "Used by").\n  if(n.type===\'binding\' && (d.calls||[]).length){\n    h+=\'<h3 class="rel">Calls custom functions 🧩 (\'+d.calls.length+\')</h3><div class="nodechips">\'+d.calls.map(nodeChip).join(\'\')+\'</div>\';\n  }\n  if(n.type===\'customFunction\' && (d.bindings||[]).length){\n    h+=\'<h3 class="rel">Called in bindings (\'+d.bindings.length+\')</h3><div class="nodechips">\'+d.bindings.map(nodeChip).join(\'\')+\'</div>\';\n  }\n  if(n.type===\'customFunction\' && !(d.usedBy||[]).length){\n    h+=\'<div class="authnote authnote-orphan">Registered via <b>externals.additionalData</b> but no <code>{{…}}</code> binding in the scanned models calls it.</div>\';\n  }\n  if((n.type===\'variable\'||n.type===\'string\') && (d.usages||[]).length){\n    h+=\'<h3 class="rel">Used in (\'+d.usages.length+\' models) — effective occurrences</h3>\';\n    d.usages.forEach(u=>{\n      h+=\'<div style="margin:6px 0 12px">\'+nodeChip(u.model)+\n         \'<div class="oplist" style="margin-top:5px">\'+\n         (u.snippets||[]).map(s=>\'<div class="oprow"><span class="mono">\'+esc(s)+\'</span></div>\').join(\'\')+\n         \'</div></div>\';\n    });\n  }\n  // Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).\n  if(d._uses){\n    const ord=[[\'variable\',\'Variables\'],[\'expression\',\'Backend expressions ${ }\'],\n               [\'binding\',\'Frontend bindings {{ }}\'],[\'customFunction\',\'Custom functions 🧩\'],\n               [\'string\',\'String literals\']];\n    let parts=\'\';\n    ord.forEach(([t,lbl])=>{ const ids=(d._uses||{})[t]; if(ids&&ids.length)\n      parts+=\'<details class="uses"><summary>\'+lbl+\' (\'+ids.length+\')</summary><div class="nodechips">\'+ids.map(nodeChip).join(\'\')+\'</div></details>\'; });\n    if(parts) h+=\'<h3 class="rel">Uses — variables &amp; expressions</h3>\'+parts;\n  }\n  return h;\n}\n\n// ---------- neighborhood graph (ego view: selected node + 1-hop neighbors) ----------\nconst GRAPH_MAX_NEIGHBORS = 26;\nfunction neighborhoodSvg(n){\n  // Collect unique neighbors with direction + relation (a node can appear on both sides).\n  const seen=new Map();\n  (outM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:\'out\'}); });\n  (incM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:\'in\'}); });\n  const all=[...seen.values()];\n  if(!all.length) return \'\';\n  const shown=all.slice(0,GRAPH_MAX_NEIGHBORS);\n  const W=680,H=340,CX=W/2,CY=H/2,RX=CX-130,RY=CY-40;\n  const trunc=(s,len)=>s.length>len?s.slice(0,len-1)+\'…\':s;\n  let g=\'\';\n  shown.forEach((e,i)=>{\n    const nn=byId.get(e.id);\n    const a=-Math.PI/2 + i*2*Math.PI/shown.length;\n    const x=CX+RX*Math.cos(a), y=CY+RY*Math.sin(a);\n    const dash=e.dir===\'in\'?\' stroke-dasharray="4 3"\':\'\';\n    g+=\'<line x1="\'+CX+\'" y1="\'+CY+\'" x2="\'+x.toFixed(1)+\'" y2="\'+y.toFixed(1)+\'" stroke="var(--line2)" stroke-width="1"\'+dash+\'><title>\'+esc(e.rel)+(e.dir===\'in\'?\' (incoming)\':\'\')+\'</title></line>\';\n    const anchor=Math.cos(a)>0.25?\'start\':Math.cos(a)<-0.25?\'end\':\'middle\';\n    const tx=x+(anchor===\'start\'?9:anchor===\'end\'?-9:0), ty=y+(anchor===\'middle\'?(Math.sin(a)>0?16:-10):4);\n    g+=\'<g class="gn" data-id="\'+enc(e.id)+\'" tabindex="0" role="link" style="cursor:pointer">\'+\n       \'<title>\'+esc(nn.label)+\' — \'+esc(e.rel)+\'</title>\'+\n       \'<circle cx="\'+x.toFixed(1)+\'" cy="\'+y.toFixed(1)+\'" r="5" fill="\'+nodeColor(nn)+\'"/>\'+\n       \'<text x="\'+tx.toFixed(1)+\'" y="\'+ty.toFixed(1)+\'" text-anchor="\'+anchor+\'" font-size="10" font-family="var(--mono)" fill="var(--ink-dim)">\'+esc(trunc(nn.label,26))+\'</text></g>\';\n  });\n  // center node on top of the lines\n  g+=\'<circle cx="\'+CX+\'" cy="\'+CY+\'" r="8" fill="\'+nodeColor(n)+\'" stroke="var(--bg)" stroke-width="2"/>\'+\n     \'<text x="\'+CX+\'" y="\'+(CY+22)+\'" text-anchor="middle" font-size="11" font-weight="600" font-family="var(--mono)" fill="var(--ink)">\'+esc(trunc(n.label,32))+\'</text>\';\n  const more=all.length>shown.length?\'<div class="muted" style="font-size:10.5px;margin:2px 0 6px">showing \'+shown.length+\' of \'+all.length+\' neighbors — the full list is below</div>\':\'\';\n  return \'<details class="uses" open><summary>Neighborhood — solid: uses, dashed: used by</summary>\'+\n    \'<div style="padding:4px 10px 8px">\'+more+\n    \'<svg viewBox="0 0 \'+W+\' \'+H+\'" style="width:100%;max-width:820px;display:block" role="img" aria-label="Relationship graph of \'+esc(n.label)+\'">\'+g+\'</svg></div></details>\';\n}\n\n// Resolve a service-task implementation to a clickable Java node chip + method.\nfunction implLink(s){\n  if(s.class){ const id=\'java:\'+s.class; if(byId.get(id)) return jchip(id, s.class); return \'\'; }\n  const ex=s.expression||s.delegateExpression||\'\';\n  const m=ex.match(/[#$]\\{\\s*([A-Za-z_]\\w*)(?:\\s*\\.\\s*([A-Za-z_]\\w*)\\s*\\()?/);\n  if(m){ const id=beanToNode.get(m[1]); if(id) return jchip(id,(byId.get(id).label)+(m[2]?\'.\'+m[2]+\'()\':\'\')); }\n  return \'\';\n}\nfunction jchip(id,label){\n  return \'<span class="nc" data-id="\'+enc(id)+\'" tabindex="0" role="link" style="flex:none"><span class="dot" style="background:\'+color(\'java\')+\'"></span><span class="nm">\'+esc(label)+\'</span></span>\';\n}\n\nfunction renderDetail(){\n  const det=document.getElementById(\'detail\');\n  if(!state.sel || !byId.get(state.sel)){\n    det.innerHTML=\'<div class="empty"><div class="big">Flowable Atlas</div><div>Pick a category on the left, then an item.<br>Click any relationship to travel the graph.</div></div>\';\n    return;\n  }\n  const n=byId.get(state.sel);\n  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));\n  let h=\'\';\n  // crumbs — display only; navigation is the browser history (each select() pushes a hash entry)\n  const trail = state.trail.slice(-4).map(id=>{const x=byId.get(id);return x?esc(x.label):\'\';}).filter(Boolean).join(\' › \');\n  h+=\'<div class="crumbs">\'+(state.trail.length?\'<button id="back">← back</button>\':\'\')+\n     \'<span class="trail">\'+trail+(trail?\' › \':\'\')+\'<b style="color:var(--ink)">\'+esc(n.label)+\'</b></span>\'+\n     \'<button id="permalink" title="Copy a shareable link to this node">🔗 copy link</button></div>\';\n  h+=\'<div class="dbody">\';\n  h+=\'<span class="chip"><span class="dot" style="background:\'+nodeColor(n)+\'"></span>\'+esc(nodeKind(n))+\'</span>\';\n  h+=\'<div class="dtitle">\'+esc(n.label)+authBadge(n)+\'</div>\';\n  h+=\'<div class="dkey mono">\'+esc(n.key)+\'</div>\';\n  if(n.file) h+=\'<div class="dfile" title="click to copy" data-copy="\'+enc(n.file)+\'">\'+esc(n.file)+\'</div>\';\n  const rows=describe(n);\n  if(rows.length){ h+=\'<div class="grid">\'+rows.map(r=>\'<div class="cell"><div class="k">\'+esc(r[0])+\'</div><div class="v mono">\'+(r[1]&&r[1].html!==undefined?r[1].html:esc(String(r[1])))+\'</div></div>\').join(\'\')+\'</div>\'; }\n  h+=neighborhoodSvg(n);\n  h+=detailExtra(n);\n  // outgoing\n  const ok=Object.keys(out).sort();\n  if(ok.length){ h+=\'<h3 class="rel">Uses / references (\'+ok.reduce((a,k)=>a+out[k].size,0)+\')</h3>\';\n    ok.forEach(rel=>{ h+=\'<div class="relgrp"><div class="lab">\'+esc(rel)+\'</div><div class="nodechips">\'+[...out[rel]].map(nodeChip).join(\'\')+\'</div></div>\'; }); }\n  // incoming\n  const ik=Object.keys(inc).sort();\n  if(ik.length){ h+=\'<h3 class="rel">Used by / referenced from (\'+ik.reduce((a,k)=>a+inc[k].size,0)+\')</h3>\';\n    ik.forEach(rel=>{ h+=\'<div class="relgrp"><div class="lab">\'+esc(rel)+\'</div><div class="nodechips">\'+[...inc[rel]].map(nodeChip).join(\'\')+\'</div></div>\'; }); }\n  if(!ok.length && !ik.length) h+=\'<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>\';\n  h+=\'</div>\';\n  det.innerHTML=h;\n  det.scrollTop=0;\n  const b=document.getElementById(\'back\'); if(b) b.onclick=()=>history.back();\n  const pl=document.getElementById(\'permalink\');\n  if(pl) pl.onclick=()=>{\n    const url=location.href;\n    const done=()=>{ pl.textContent=\'✓ link copied\'; setTimeout(()=>{ pl.textContent=\'🔗 copy link\'; },1500); };\n    if(navigator.clipboard&&navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done,()=>prompt(\'Copy link:\',url));\n    else prompt(\'Copy link:\',url);   // clipboard API is unavailable on file:// in some browsers\n  };\n  det.querySelectorAll(\'.nc, .gn, .vlink\').forEach(c=>{\n    c.onclick=()=>select(dec(c.dataset.id));\n    c.onkeydown=e=>{ if(e.key===\'Enter\'||e.key===\' \'){ e.preventDefault(); select(dec(c.dataset.id)); } };\n  });\n  const fp=det.querySelector(\'.dfile\'); if(fp) fp.onclick=()=>{navigator.clipboard&&navigator.clipboard.writeText(dec(fp.dataset.copy)); fp.textContent=\'✓ copied — \'+dec(fp.dataset.copy); };\n}\n\n// Navigation: select() only moves the URL hash; the hashchange listener renders. That makes\n// the hash the single source of truth — browser back/forward, bookmarks and copied links all\n// go through the same path (the old internal back-stack competed with browser history).\nfunction select(id){\n  if(!byId.get(id)) return;\n  if(state.sel===id) return;\n  if(dec(location.hash.slice(1))===id) applySelection(id);\n  else location.hash=encodeURIComponent(id);\n}\n\nfunction applySelection(id){\n  if(!byId.get(id)) return;\n  if(state.sel && state.sel!==id){ state.trail.push(state.sel); state.trail=state.trail.slice(-8); }\n  state.sel=id;\n  const n=byId.get(id);\n  // Keep the current category if it already contains this node (so clicking within\n  // e.g. "Java · delegate" stays there) — only re-sync when it doesn\'t match.\n  const cur=CATS.find(c=>c.id===state.cat);\n  let catChanged=false;\n  if(!cur || !cur.match(n)){\n    let cat;\n    if(n.type===\'java\'){\n      const prio=[\'controller\',\'delegate\',\'listener\',\'bot\',\'service\',\'repository\',\'configuration\',\'component\',\'other\'];\n      const r=(n.data.roles||[]).slice().sort((a,b)=>prio.indexOf(a)-prio.indexOf(b))[0];\n      cat=CATS.find(c=>c.id===\'java::\'+r);\n    } else if(n.type===\'variable\'){\n      cat=CATS.find(c=>c.id===\'variable::\'+(n.data.scopes||[])[0]);\n    }\n    cat=cat||CATS.find(c=>c.id===n.type);\n    if(cat && cat.id!==state.cat){ state.cat=cat.id; catChanged=true; }\n  }\n  if(catChanged){ renderRail(); renderList(); syncListSelection(); }\n  else syncListSelection();\n  renderDetail();\n}\n\n// ---------- search ----------\nconst q=document.getElementById(\'q\'), results=document.getElementById(\'results\');\nlet resSel=-1, resList=[];\n// search haystack: base identity plus a few type-specific extras. A DataObject\'s\n// fields/columns are not nodes of their own, so without this a field name like\n// \'crewId\' (or its label / type) would never surface its data object.\nfunction searchText(n){\n  const d=n.data||{};\n  let s=n.label+\' \'+n.key+\' \'+(n.file||\'\')+\' \'+n.type;\n  if(n.type===\'dataObject\') s+=\' \'+(d.fields||[]).join(\' \')+\' \'+(d.serviceTableName||\'\')+\' \'+\n    (d.columns||[]).map(c=>(c.label||\'\')+\' \'+(c.type||\'\')).join(\' \');\n  if(n.type===\'service\') s+=\' \'+(d.columns||[]).map(c=>(c.name||\'\')+\' \'+(c.columnName||\'\')+\' \'+(c.type||\'\')).join(\' \');\n  if(n.type===\'liquibase\') s+=\' \'+(d.columns||[]).map(c=>(c.name||\'\')+\' \'+(c.type||\'\')).join(\' \');\n  return s.toLowerCase();\n}\nfunction doSearch(){\n  const v=q.value.trim().toLowerCase();\n  if(!v){ results.classList.remove(\'on\'); return; }\n  resList = nodes.filter(n=>searchText(n).includes(v))\n                 .sort((a,b)=> a.label.length-b.label.length).slice(0,40);\n  results.innerHTML = resList.map((n,i)=>\'<div class="r\'+(i===resSel?\' sel\':\'\')+\'" id="sr-\'+i+\'" role="option" aria-selected="\'+(i===resSel)+\'" data-id="\'+enc(n.id)+\'">\'+\n    \'<span class="dot" style="background:\'+nodeColor(n)+\'"></span><span class="nm">\'+esc(n.label)+\'</span>\'+\n    \'<span class="ty mono" style="color:var(--ink-faint);font-size:10px">\'+esc(nodeKind(n))+\'</span>\'+\n    \'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">\'+esc(n.key)+\'</span></div>\').join(\'\')\n    || \'<div class="r muted">no matches</div>\';\n  results.classList.add(\'on\');\n  q.setAttribute(\'aria-expanded\',\'true\');\n  if(resSel>=0) q.setAttribute(\'aria-activedescendant\',\'sr-\'+resSel); else q.removeAttribute(\'aria-activedescendant\');\n  results.querySelectorAll(\'.r[data-id]\').forEach(r=>r.onclick=()=>{ select(dec(r.dataset.id)); closeSearch(); });\n}\nfunction closeSearch(){ results.classList.remove(\'on\'); q.setAttribute(\'aria-expanded\',\'false\'); q.removeAttribute(\'aria-activedescendant\'); q.blur(); resSel=-1; }\nq.addEventListener(\'input\',debounce(()=>{ resSel=-1; doSearch(); },120));\nq.addEventListener(\'keydown\',e=>{\n  if(e.key===\'ArrowDown\'){ resSel=Math.min(resSel+1,resList.length-1); doSearch(); e.preventDefault(); }\n  else if(e.key===\'ArrowUp\'){ resSel=Math.max(resSel-1,0); doSearch(); e.preventDefault(); }\n  else if(e.key===\'Enter\' && resList[resSel]){ select(resList[resSel].id); closeSearch(); }\n  else if(e.key===\'Escape\'){ closeSearch(); }\n});\ndocument.addEventListener(\'keydown\',e=>{ if(e.key===\'/\' && document.activeElement!==q){ e.preventDefault(); q.focus(); } });\ndocument.addEventListener(\'click\',e=>{ if(!e.target.closest(\'.search\')) results.classList.remove(\'on\'); });\n\n// ---------- utils ----------\nfunction esc(s){ return String(s==null?\'\':s).replace(/[&<>"]/g,c=>({\'&\':\'&amp;\',\'<\':\'&lt;\',\'>\':\'&gt;\',\'"\':\'&quot;\'}[c])); }\nfunction enc(s){ return encodeURIComponent(s); }\nfunction dec(s){ return decodeURIComponent(s); }\n\n// ---------- theme ----------\n// Preference cycle: auto (follow the OS) → light → dark. JS always resolves the effective\n// theme onto <html data-theme=…>, so the CSS needs only one light-override block; because\n// all node colors are emitted as var() references, a switch restyles without re-rendering.\nfunction themePref(){ let p=null; try{ p=localStorage.getItem(\'atlas-theme\'); }catch(e){} return p||\'auto\'; }\nfunction applyThemePref(){\n  const pref=themePref();\n  const sys=matchMedia(\'(prefers-color-scheme: light)\').matches?\'light\':\'dark\';\n  document.documentElement.dataset.theme = pref===\'auto\'?sys:pref;\n  const b=document.getElementById(\'themebtn\');\n  if(b){ b.textContent = pref===\'auto\'?\'◐\':(pref===\'light\'?\'☀\':\'☾\');\n         b.title=\'Theme: \'+pref+\' — click to switch\'; }\n}\ndocument.getElementById(\'themebtn\').onclick=()=>{\n  const next={auto:\'light\', light:\'dark\', dark:\'auto\'}[themePref()];\n  try{ localStorage.setItem(\'atlas-theme\', next); }catch(e){}   // private mode / file:// quirks\n  applyThemePref();\n};\nmatchMedia(\'(prefers-color-scheme: light)\').addEventListener(\'change\',applyThemePref);\napplyThemePref();\n\n// ---------- diagnostics (parse/read failures of the generator run) ----------\nconst diags=DATA.diagnostics||[];\nfunction renderDiagBadge(){\n  if(!diags.length) return \'\';\n  return \'<span id="diagbtn" title="Files the generator could not fully analyze — the map may be incomplete">⚠ <b>\'+diags.length+\'</b> parse issue\'+(diags.length>1?\'s\':\'\')+\'</span>\';\n}\nfunction toggleDiagPanel(){\n  const p=document.getElementById(\'diagpanel\');\n  document.getElementById(\'cfnpanel\').classList.remove(\'on\');  // panels share the top-right anchor\n  if(p.classList.contains(\'on\')){ p.classList.remove(\'on\'); return; }\n  p.innerHTML=\'<div class="dp-head">Files that could not be fully analyzed — their models/relations may be missing from this map.</div>\'+\n    diags.map(d=>\'<div class="dp-row"><span class="dp-kind">\'+esc(d.kind)+\'</span>\'+\n      \'<span class="dp-path mono">\'+esc(d.path)+\'</span>\'+\n      \'<span class="dp-msg">\'+esc(d.message)+\'</span></div>\').join(\'\');\n  p.classList.add(\'on\');\n}\n\n// ---------- custom frontend functions (externals.additionalData) ----------\nconst cfns=DATA.customFunctions;\nconst cfnTotal=cfns?Object.values(cfns.namespaces).reduce((a,m)=>a+m.length,0)+cfns.flw.length+cfns.topLevel.length:0;\nfunction renderCustomBadge(){\n  if(!cfns||!cfnTotal) return \'\';\n  return \'<span id="cfnbtn" title="Project custom functions from flowable.externals.additionalData — read from source and validated precisely">🧩 <b>\'+cfnTotal+\'</b> custom fn\'+(cfnTotal>1?\'s\':\'\')+\'</span>\';\n}\nfunction chips(names){ return \'<div class="cf-mem">\'+names.map(n=>\'<span>\'+esc(n)+\'</span>\').join(\'\')+\'</div>\'; }\nfunction toggleCustomPanel(){\n  const p=document.getElementById(\'cfnpanel\');\n  document.getElementById(\'diagpanel\').classList.remove(\'on\');  // panels share the top-right anchor\n  if(p.classList.contains(\'on\')){ p.classList.remove(\'on\'); return; }\n  let h=\'<div class="dp-head">Custom functions the project injects via <b>flowable.externals.additionalData</b>. \'+\n    \'Read from source, so calls to them validate precisely (a close typo is flagged; unknown names are not).\'+\n    (cfns.sources.length?\' Source: <span class="mono">\'+esc(cfns.sources.join(\', \'))+\'</span>\':\'\')+\'</div>\';\n  Object.keys(cfns.namespaces).sort().forEach(ns=>{\n    h+=\'<div class="cf-ns"><b>\'+esc(ns)+\'.*</b> <span class="cf-src">(\'+cfns.namespaces[ns].length+\')</span>\'+chips(cfns.namespaces[ns])+\'</div>\';\n  });\n  if(cfns.flw.length) h+=\'<div class="cf-ns"><b>flw.*</b> <span class="cf-src">(+\'+cfns.flw.length+\' custom)</span>\'+chips(cfns.flw)+\'</div>\';\n  if(cfns.topLevel.length) h+=\'<div class="cf-ns"><b>top-level</b> <span class="cf-src">(\'+cfns.topLevel.length+\')</span>\'+chips(cfns.topLevel)+\'</div>\';\n  (cfns.diagnostics||[]).forEach(d=>{ h+=\'<div class="dp-row"><span class="dp-kind">note</span><span class="dp-msg">\'+esc(d)+\'</span></div>\'; });\n  p.innerHTML=h; p.classList.add(\'on\');\n}\n\n// ---------- boot ----------\ndocument.getElementById(\'proj\').textContent=DATA.project;\nconst st=DATA.stats;\nconst invalidN=nodes.filter(n=>(n.data.problems||[]).some(p=>p.severity===\'error\')).length;\nconst suspectN=nodes.filter(n=>(n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity===\'error\')).length;\ndocument.getElementById(\'stats\').innerHTML=\n  \'<span><b>\'+nodes.length+\'</b> nodes</span><span><b>\'+edges.length+\'</b> links</span>\'+\n  \'<span><b>\'+(st.models||0)+\'</b> models</span><span><b>\'+(st.java||0)+\'</b> java</span><span><b>\'+(st.groups||0)+\'</b> groups</span>\'+\n  (invalidN?\'<span style="color:\'+color(\'invalidExpr\')+\'"><b>\'+invalidN+\'</b> invalid</span>\':\'\')+\n  (suspectN?\'<span style="color:\'+color(\'suspectExpr\')+\'"><b>\'+suspectN+\'</b> suspect</span>\':\'\')+\n  renderCustomBadge()+\n  renderDiagBadge();\nconst db=document.getElementById(\'diagbtn\'); if(db) db.onclick=toggleDiagPanel;\nconst cb=document.getElementById(\'cfnbtn\'); if(cb) cb.onclick=toggleCustomPanel;\ndocument.addEventListener(\'click\',e=>{\n  const p=document.getElementById(\'diagpanel\');\n  if(p && p.classList.contains(\'on\') && !e.target.closest(\'#diagpanel\') && !e.target.closest(\'#diagbtn\')) p.classList.remove(\'on\');\n  const c=document.getElementById(\'cfnpanel\');\n  if(c && c.classList.contains(\'on\') && !e.target.closest(\'#cfnpanel\') && !e.target.closest(\'#cfnbtn\')) c.classList.remove(\'on\'); });\nstate.cat = (CATS.find(c=>c.id===\'process\')||CATS[0]||{}).id;\nrenderRail(); renderList(); renderDetail();\nconst hash=location.hash?decodeURIComponent(location.hash.slice(1)):\'\';\nif(hash && byId.get(hash)) applySelection(hash);\nwindow.addEventListener(\'hashchange\',()=>{ const h=dec(location.hash.slice(1)); if(h&&byId.get(h)&&h!==state.sel) applySelection(h); });\n',
}
# --- END GENERATED FRONTEND ---


def _frontend_asset(name):
    """Text of a frontend asset: read from frontend/ next to this script when present (dev mode,
    instant iteration without re-embedding), else from the embedded copy (single-file mode)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "frontend", name)
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    return _EMBEDDED_FRONTEND[name]


def _compose_template():
    """The full explorer HTML page (CSS/JS inlined; __ATLAS_DATA__ still unresolved)."""
    t = _frontend_asset("explorer.html")
    t = t.replace("/*__ATLAS_CSS__*/", _frontend_asset("explorer.css"))
    t = t.replace("/*__ATLAS_JS__*/", _frontend_asset("explorer.js"))
    return t.rstrip("\n")


def summary_render(result, root):
    """A compact (~few KB) LLM-first overview: the essentials + how to dig deeper."""
    g = result["graph"]
    nodes, edges, st = g["nodes"], g["edges"], result["stats"]
    by_id = {n["id"]: n for n in nodes}
    by_type = {}
    for n in nodes:
        by_type.setdefault(n["type"], []).append(n)
    jroles = {}
    for n in by_type.get("java", []):
        for r in (n["data"].get("roles") or []):
            jroles.setdefault(r, []).append(n)

    def cap(items, n=15):
        items = list(items)
        extra = f" … (+{len(items) - n} more)" if len(items) > n else ""
        return ", ".join(items[:n]) + extra

    L = [f"# Flowable project — `{os.path.basename(os.path.abspath(root))}` (quick overview)\n"]
    L.append(f"_{st['models']} model files · {st['java']} Java files · {st.get('nodes',0)} nodes · "
             f"{st.get('edges',0)} relationships · {st.get('groups',0)} user groups. "
             f"Compact summary — use `--json` for the full graph, or open the HTML explorer._\n")
    diags = result.get("diagnostics") or []
    if diags:
        L.append(f"⚠ **{len(diags)} file(s) could not be fully analyzed** (parse/read failures) — "
                 f"the map below may be incomplete. Details: `diagnostics` in graph.json / "
                 f"Warnings section of the overview.\n")

    # Apps
    app_models = {}
    for e in edges:
        if e["rel"] == "contains" and e["s"].startswith("app:"):
            app_models[e["s"]] = app_models.get(e["s"], 0) + 1
    if by_type.get("app"):
        L.append("## Apps")
        for a in sorted(by_type["app"], key=lambda n: -app_models.get(n["id"], 0)):
            L.append(f"- **{a['label']}** (`{a['key']}`) — {app_models.get(a['id'], 0)} models")
        L.append("")

    # Inventory
    order = ["process", "case", "decision", "form", "page", "dataObject", "dataDictionary",
             "service", "agent", "channel", "event", "action", "query", "template", "sequence",
             "securityPolicy", "variableExtractor", "liquibase"]
    inv = [f"{len(by_type[t])} {t}" for t in order if by_type.get(t)]
    L.append("## Inventory")
    L.append("Models: " + " · ".join(inv))
    if jroles:
        L.append("Java: " + " · ".join(f"{len(v)} {r}" for r, v in sorted(jroles.items(), key=lambda x: -len(x[1]))))
    ne, nb, nv, ns = (len(by_type.get("expression", [])), len(by_type.get("binding", [])),
                      len(by_type.get("variable", [])), len(by_type.get("string", [])))
    if nv:
        L.append("Variables: %d (grouped by scope: process / form / case / java / …)" % nv)
    if ne or nb or ns:
        L.append("Expressions: %d backend ${ } · %d frontend {{ }} · %d string literals" % (ne, nb, ns))
    L.append("")

    # Entry points
    starts = [a for a in result.get("access", []) if a["action"] == "start"]
    if starts:
        L.append("## Entry points — who can start what")
        for a in sorted(starts, key=lambda x: x["model"])[:15]:
            who = ", ".join(a["groups"] + a["users"]) or "(no restriction)"
            L.append(f"- {a['modelType']} `{a['model']}` ← {who}")
        if len(starts) > 15:
            L.append(f"- … (+{len(starts) - 15} more)")
        L.append("")
    if by_type.get("endpoint"):
        ctrls = sorted({n["data"].get("controller") for n in by_type["endpoint"] if n["data"].get("controller")})
        L.append(f"## REST API surface\n{len(by_type['endpoint'])} endpoints across {len(ctrls)} controllers: "
                 + cap(ctrls) + "\n")

    # Integrations
    if by_type.get("service"):
        L.append("## Integrations — services")
        for s in by_type["service"][:15]:
            d = s["data"]
            loc = d.get("baseUrl") or d.get("tableName") or "?"
            L.append(f"- `{s['key']}` {s['label']} ({d.get('type')} → {loc})")
        if len(by_type["service"]) > 15:
            L.append(f"- … (+{len(by_type['service']) - 15} more)")
        L.append("")
    misc = []
    if by_type.get("channel"):
        misc.append("Channels: " + cap([n["label"] for n in by_type["channel"]]))
    if by_type.get("event"):
        misc.append("Events: " + cap([n["key"] for n in by_type["event"]]))
    if by_type.get("agent"):
        misc.append("Agents: " + cap([n["key"] for n in by_type["agent"]]))
    if misc:
        L.append("## Integrations — messaging / AI\n" + "  \n".join(misc) + "\n")

    # Java glue (model <-> code)
    glue = []
    for role, label in (("delegate", "Delegates"), ("bot", "Bots"), ("listener", "Listeners")):
        if jroles.get(role):
            glue.append(f"**{label} ({len(jroles[role])}):** "
                        + cap([n["label"] for n in jroles[role]]))
    if glue:
        L.append("## Java glue (wired to models)\n" + "  \n".join(glue) + "\n")

    # Hotspots — most-referenced nodes (excluding the uniform app 'contains')
    indeg = {}
    for e in edges:
        if e["rel"] == "contains":
            continue
        indeg[e["t"]] = indeg.get(e["t"], 0) + 1
    hot = sorted(((c, nid) for nid, c in indeg.items() if by_id.get(nid) and by_id[nid]["type"] not in ("group",)),
                 reverse=True)[:12]
    if hot:
        L.append("## Hotspots — most-referenced (central) artifacts")
        for c, nid in hot:
            n = by_id[nid]
            L.append(f"- {n['type']} `{n['key']}` {('— ' + n['label']) if n['label'] != n['key'] else ''} (referenced by {c})")
        L.append("")

    # External / unresolved surface
    groups = {}
    for r in result.get("unresolvedRefs", []):
        groups.setdefault((r["kind"], r["value"]), []).append(r)
    platform = sorted({v for (k, v) in groups if k == "bean" and v in FLOWABLE_PLATFORM_BEANS})
    review = sorted({f"{k}:{v}" for (k, v) in groups if not (k == "bean" and v in FLOWABLE_PLATFORM_BEANS)})
    ext_urls = sum(1 for n in by_type.get("external", []) if n["data"].get("external_url"))
    L.append("## External surface")
    if platform:
        L.append("- Flowable platform beans (engine-provided): " + cap(platform))
    if ext_urls:
        L.append(f"- External REST URLs called: {ext_urls}")
    L.append("- Review (unresolved in project — likely missing/external): "
             + (cap(review) if review else "none ✅"))
    L.append("")

    L.append("---\n_For details: `--json` gives the full traversable graph; `--html` opens the interactive explorer; "
             "the Markdown report (default) has every model, relationship and the access map._")
    return "\n".join(L)


# --- CLAUDE.md generator: generic Flowable primer + this project's discovered facts ---
_CLAUDE_PLATFORM = """## 1. What Flowable is (the mental model an LLM usually gets wrong)

Flowable is a Java process-automation platform. A solution project is custom Java + models that run
**on top of** the Flowable engines — you extend a platform, you don't build from scratch.

- **Work** = the **runtime *and* the end-user React frontend** (executes definitions, renders forms,
  hosts tasks/cases). Custom Java + REST controllers run here. **Design** = the visual modeler.
  (Control/Hub = admin consoles.) Engines: BPMN (processes), CMMN (cases), DMN (decisions), Form, IDM.

**Models vs Definitions (the key concept):** models are mutable design-time JSON; when deployed they
become **immutable, versioned Definitions** (stored in the DB's `ACT_*` tables — rarely touched
directly; use the engine services/APIs). Everything is referenced by **key** (process/case/form/decision
key) — cross-references between models, from Java, and from the frontend are all by key.

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
- **Service-registry data objects** — `.data` backed by a `.service` (REST/DB); DB-backed map to **Liquibase** tables.
- **Forms** — bind fields to **variables**; outcomes drive flow; can call REST for options/data tables.
- **Queries** (`.query`) — index queries (tasks/case-instances/…), often gated by **user group**.
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
"""

_CLAUDE_RULES = """## 5. Rules for the agent

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
"""


def claude_render(result, root):
    from collections import Counter
    name = os.path.splitext(os.path.basename(os.path.abspath(str(root).rstrip("/"))))[0] or "project"
    s = result["stats"]
    nodes = result["graph"]["nodes"]
    by_type = {}
    for n in nodes:
        by_type.setdefault(n["type"], []).append(n)
    MODEL = {"app", "process", "case", "decision", "form", "page", "dataObject", "dataDictionary",
             "service", "agent", "channel", "event", "action", "query", "template", "sequence",
             "securityPolicy", "variableExtractor", "masterData", "document", "dashboardComponent"}

    def folder(f):
        f = (f or "").split("!")[0]
        return f.rsplit("/", 1)[0] if "/" in f else "."

    def keypat(t):
        keys = [n["key"] for n in by_type.get(t, []) if n.get("key")]
        if not keys:
            return None
        pat = Counter(re.sub(r"\d+", "#", k) for k in keys).most_common(1)[0][0]
        example = next((k for k in keys if re.sub(r"\d+", "#", k) == pat), keys[0])
        return f"`{pat}` (e.g. `{example}`)"

    L = [f"# CLAUDE.md — `{name}` (Flowable solution project)\n",
         "_Auto-generated by Flowable Atlas: a generic Flowable primer + this project's discovered map. "
         "Regenerate with `atlas <project-dir>`. Edit freely — re-running overwrites only the Atlas copy._\n"]

    # §0 — start here, pointing at the actual artifact filenames
    L.append("## 0. Understand this project — start here\n")
    L.append("Don't guess — build the picture in order:")
    L.append(f"1. Read **`{name}.summary.md`** — apps, inventory, entry points, integrations, hotspots.")
    L.append(f"2. Query **`{name}.graph.json`** for specific questions (what calls X, who uses variable Y, "
             "which controller serves form Z) — relationships are bidirectional (model↔code).")
    L.append(f"3. Open **`{name}.explorer.html`** for the clickable view.")
    L.append("4. Read the actual source to verify, then implement.\n")

    L.append(_CLAUDE_PLATFORM)

    # §4 — discovered facts
    L.append("## 4. This project (auto-discovered by Atlas)\n")
    L.append(f"- **Scale:** {s['models']} model files · {s['java']} Java files · "
             f"{s.get('nodes', 0)} graph nodes · {s.get('edges', 0)} relationships · {s.get('groups', 0)} user groups.")
    if by_type.get("app"):
        L.append("- **Apps:** " + ", ".join(f"{a['label']} (`{a['key']}`)" for a in by_type["app"]))
    order = ["process", "case", "decision", "form", "page", "dataObject", "service", "agent",
             "channel", "event", "action", "query", "template", "sequence", "variableExtractor"]
    inv = " · ".join(f"{len(by_type[t])} {t}" for t in order if by_type.get(t))
    if inv:
        L.append("- **Models:** " + inv)
    jr = Counter()
    for n in by_type.get("java", []):
        for r in (n["data"].get("roles") or []):
            jr[r] += 1
    if jr:
        L.append("- **Java by role:** " + " · ".join(f"{c} {r}" for r, c in jr.most_common()))
    mf = Counter(folder(n.get("file")) for n in nodes if n["type"] in MODEL and n.get("file"))
    jf = Counter(folder(n.get("file")) for n in by_type.get("java", []) if n.get("file"))
    if mf:
        L.append("- **Models live in:** " + ", ".join(f"`{d}/` ({c})" for d, c in mf.most_common(5)))
    if jf:
        L.append("- **Java lives in:** " + ", ".join(f"`{d}/` ({c})" for d, c in jf.most_common(5)))
    convs = [f"{t} {p}" for t in ("process", "case", "form", "decision", "service", "dataObject", "query")
             for p in [keypat(t)] if p]
    if convs:
        L.append("- **Key conventions:** " + "; ".join(convs))
    starts = sorted({a["model"] for a in result.get("access", []) if a["action"] == "start"})
    if starts:
        L.append(f"- **Startable entry points:** {len(starts)} processes/cases — see `{name}.summary.md`.")
    # build / version detection
    if os.path.isdir(root):
        ex = lambda *p: os.path.exists(os.path.join(root, *p))
        b = []
        if ex("mvnw") or ex("pom.xml"):
            b.append("Maven — `./mvnw clean install -DskipTests -T 1C`; tests `./mvnw test -pl <module> -am -Dtest=Class` "
                     "(use `-am` on scoped builds)")
        if ex("gradlew") or ex("build.gradle"):
            b.append("Gradle — `./gradlew build`")
        for fe in ("frontend", "ui", "web", "src/main/frontend"):
            if ex(fe, "package.json"):
                b.append(f"Frontend — `cd {fe} && yarn install && yarn build`")
                break
        if b:
            L.append("- **Build/test:** " + "; ".join(b))
        # run & verify loop (detect frontend start script + a backend app module)
        run = []
        fe_dir = next((d for d in ("frontend", "ui", "web", "src/main/frontend") if ex(d, "package.json")), None)
        if fe_dir:
            try:
                with open(os.path.join(root, fe_dir, "package.json"), "r", encoding="utf-8") as fh:
                    scripts = (json.load(fh).get("scripts") or {})
                cand = [k for k in scripts if k.startswith("start")] or [k for k in scripts if k in ("dev", "serve")]
                if cand:
                    run.append(f"frontend `cd {fe_dir} && yarn {sorted(cand)[0]}`")
            except Exception:  # noqa: BLE001
                pass
        try:
            appmod = next((d for d in sorted(os.listdir(root))
                           if os.path.isdir(os.path.join(root, d))
                           and (d.endswith(("-app", "-work", "-workflow", "-runtime")))), None)
        except Exception:  # noqa: BLE001
            appmod = None
        if appmod:
            run.append(f"backend = Spring Boot app in `{appmod}/`")
        L.append("- **Run & verify:** " + (("; ".join(run) + "; ") if run else "")
                 + "the app auto-deploys its bundled models on startup — exercise the change in the Work UI, "
                 "or cover backend logic with a test.")
        ver = None
        try:
            if ex("pom.xml"):
                with open(os.path.join(root, "pom.xml"), "r", encoding="utf-8", errors="replace") as fh:
                    pom = fh.read()
                m = (re.search(r"<flowable(?:[.-]engine|[.-]bom|[.-]platform)?\.version>\s*([0-9][^<\s]+)", pom)
                     or re.search(r"(?:com|org)\.flowable\b[^<]*</groupId>\s*<artifactId>[^<]*</artifactId>"
                                  r"\s*<version>\s*([0-9][^<\s]+)", pom, re.S))
                cand = m.group(1).strip() if m else None
                ver = cand if cand and re.match(r"^[6-9]\.", cand) else None  # Flowable engine majors
        except Exception:  # noqa: BLE001
            ver = None
        L.append(f"- **Flowable version:** {ver if ver else 'see pom.xml / dependencies (not auto-detected)'}")

    # Concrete wiring examples, auto-picked from the resolved graph — mirror these for new code.
    rr = result.get("resolvedRefs", [])

    def _first(pred):
        return next((r for r in rr if pred(r)), None)

    def _cls(fqn):
        return fqn.split(".")[-1] if fqn else fqn

    ex = []
    d = _first(lambda r: r["rel"] == "serviceTask-delegate" and r.get("targetFqn"))
    if d:
        ex.append(f"- **Delegate:** process `{d['from']}` → `${{{d['value']}}}` → `{_cls(d['targetFqn'])}` (`{d['targetFqn']}`)")
    ls = _first(lambda r: r["rel"].startswith(("taskListener", "executionListener", "planItemLifecycleListener"))
                and r.get("targetFqn"))
    if ls:
        ex.append(f"- **Listener:** `{ls['from']}` → `{_cls(ls['targetFqn'])}` ({ls['rel']})")
    cm = _first(lambda r: r["rel"].startswith("calls ") and r.get("targetFqn"))
    if cm:
        ex.append(f"- **Expression → method:** `{cm['from']}` → `${{{cm['value']}.{cm['rel'][6:].rstrip('()')}(…)}}` → `{_cls(cm['targetFqn'])}`")
    bote = next((e for e in result["graph"]["edges"] if e["rel"] == "bot" and e["t"].startswith("java:")), None)
    if bote:
        ex.append(f"- **Bot:** action `{bote['s'].split(':', 1)[1]}` → `{bote['t'].split('.')[-1]}` (BotService)")
    rc = next((r for r in result.get("restCalls", []) if r.get("matches")), None)
    if rc:
        url = (rc["url"][:60] + "…") if len(rc["url"]) > 60 else rc["url"]
        ex.append(f"- **Form → REST:** `{rc['source']}` calls `{rc['method']} {url}` → {rc['matches'][0]}")
    if ex:
        L.append("\n**Wiring examples in this project — mirror these for new code:**")
        L.extend(ex)

    L.append("")
    L.append("> `<!-- Add house rules: code style, where business logic goes, what NOT to touch, "
             "how this app is deployed/published to Work. -->`\n")

    L.append(_CLAUDE_RULES)
    return "\n".join(L)


def html_render(result, root):
    payload = {"project": os.path.basename(os.path.abspath(root)) or "project",
               "stats": result["stats"],
               "diagnostics": result.get("diagnostics", []),
               "customFunctions": result.get("customFunctions"),
               "nodes": result["graph"]["nodes"],
               "edges": result["graph"]["edges"]}
    data = json.dumps(payload, ensure_ascii=False, default=list).replace("</", "<\\/")
    return _compose_template().replace("__ATLAS_DATA__", data)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def _open_file(path):
    import shutil
    import subprocess
    for opener in ("open", "xdg-open", "start"):
        if shutil.which(opener):
            try:
                subprocess.Popen([opener, path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception as e:  # noqa: BLE001
                log.warning("could not open %s with %s: %s", path, opener, e)
            return


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="flowable_atlas.py",
        description="Flowable Atlas — map any Flowable project (models + Java) into an "
                    "interactive HTML explorer, an LLM overview and a traversable graph.")
    ap.add_argument("path", help="Project directory, or a single .zip/.bar archive")
    ap.add_argument("-o", "--output", help="Output file (single mode) or output DIRECTORY (with --all)")
    fmt = ap.add_mutually_exclusive_group()
    fmt.add_argument("--all", action="store_true",
                     help="Write all artifacts in one run (summary + overview + graph json + html explorer + CLAUDE.md)")
    fmt.add_argument("--json", action="store_true", help="Emit the full traversable graph as JSON")
    fmt.add_argument("--html", action="store_true", help="Emit the self-contained interactive HTML explorer")
    fmt.add_argument("--summary", action="store_true", help="Emit a compact (~few KB) LLM-first overview")
    fmt.add_argument("--claude", action="store_true",
                     help="Emit a CLAUDE.md (Flowable primer + this project's discovered facts) for AI agents")
    ap.add_argument("--stdout", action="store_true", help="Print to stdout instead of writing a file")
    ap.add_argument("--open", dest="open_", action="store_true", help="Open the HTML explorer when done")
    ap.add_argument("--expr-allowlist", default="", metavar="NS[,NS:FN,flw.member]",
                    help="Comma-separated expression-function namespaces/functions the project "
                         "provides itself (e.g. 'myfns,util:format,flw.custom') — matching "
                         "'unknown function' findings are suppressed instead of flagged as suspect")
    ap.add_argument("--custom-functions", metavar="PATH",
                    help="Path to the frontend-customization source (dir or index file) that registers "
                         "flowable.externals.additionalData. Its custom namespaces/functions (e.g. "
                         "flowkyc.*) are read and validated precisely. Default: auto-discovered under the "
                         "scanned project.")
    ap.add_argument("--no-custom-functions", action="store_true",
                    help="Disable auto-discovery of externals.additionalData custom functions "
                         "(fall back to lenient validation).")
    ap.add_argument("-v", "--verbose", action="count", default=0,
                    help="Progress + per-file diagnostics on stderr (-vv for debug)")
    ap.add_argument("-q", "--quiet", action="store_true", help="Suppress the summary line on stderr")
    args = ap.parse_args(argv)

    level = logging.WARNING
    if args.verbose >= 2:
        level = logging.DEBUG
    elif args.verbose == 1:
        level = logging.INFO
    elif args.quiet:
        level = logging.ERROR
    logging.basicConfig(stream=sys.stderr, level=level, format="%(message)s")

    if not os.path.exists(args.path):
        print(f"error: path not found: {args.path}", file=sys.stderr)
        return 2

    allow = {t.strip() for t in args.expr_allowlist.split(",") if t.strip()}
    result = extract(args.path, expr_allowlist=allow or None,
                     discover_custom=not args.no_custom_functions,
                     custom_path=args.custom_functions)
    s = result["stats"]
    name = os.path.splitext(os.path.basename(os.path.abspath(args.path.rstrip("/"))))[0] or "project"
    n_diag = len(result.get("diagnostics") or [])
    cf = result.get("customFunctions")
    status = (f"{s['models']} models · {s['java']} java · {s.get('nodes', 0)} nodes · "
              f"{s.get('edges', 0)} links · {len(result['resolvedRefs'])} resolved / "
              f"{len(result['unresolvedRefs'])} unresolved refs"
              + (f" · custom fns: {cf['summary']}" if cf else "")
              + (f" · ⚠ {n_diag} parse issue(s), see -v" if n_diag else ""))

    if args.all:
        outdir = args.output or "."
        os.makedirs(outdir, exist_ok=True)
        artifacts = [
            (f"{name}.summary.md", summary_render(result, args.path)),
            (f"{name}.overview.md", render(result, args.path)),
            (f"{name}.graph.json", json.dumps(result, indent=2, ensure_ascii=False, default=list)),
            (f"{name}.explorer.html", html_render(result, args.path)),
            (f"{name}.CLAUDE.md", claude_render(result, args.path)),
        ]
        written = []
        for fn, content in artifacts:
            p = os.path.join(outdir, fn)
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(content)
            written.append(p)
        if not args.quiet:
            print(f"Flowable Atlas — {name}: {status}", file=sys.stderr)
            for p in written:
                print(f"  ✓ {p}", file=sys.stderr)
        if args.open_:
            _open_file(next(p for p in written if p.endswith(".html")))
        return 0

    if args.claude:
        out, ext = claude_render(result, args.path), "CLAUDE.md"
    elif args.summary:
        out, ext = summary_render(result, args.path), "summary.md"
    elif args.html:
        out, ext = html_render(result, args.path), "html"
    elif args.json:
        out, ext = json.dumps(result, indent=2, ensure_ascii=False, default=list), "json"
    else:
        out, ext = render(result, args.path), "md"

    if args.stdout:
        sys.stdout.write(out + "\n")
        return 0
    if args.output:
        target = args.output
    else:
        base = args.path if os.path.isdir(args.path) else os.path.dirname(os.path.abspath(args.path)) or "."
        # --claude single mode writes a ready-to-drop-in CLAUDE.md, not APP_OVERVIEW.CLAUDE.md
        target = os.path.join(base, "CLAUDE.md" if args.claude else f"APP_OVERVIEW.{ext}")
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(out)
    if not args.quiet:
        print(f"wrote {target} — {status}", file=sys.stderr)
    if args.open_ and ext == "html":
        _open_file(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
