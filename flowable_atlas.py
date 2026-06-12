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
import os
import html
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

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
    ".extractor": "variableExtractor", ".knowledgeBase": "knowledgeBase",
    ".palette": "palette", ".masterdata": "masterData",
    ".dashboardComponent": "dashboardComponent", ".document": "document",
}
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
    return EXT_TO_TYPE.get(filename[dot:])


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
                        "delegateExpression": lst.get("delegateExpression")})
    return out


# ---------------------------------------------------------------------------
# Reference collection (the heart of cross-linking)
# ---------------------------------------------------------------------------
def add_ref(ctx, frm, ftype, ffile, rel, kind, value):
    if not value:
        return
    v = str(value).strip()
    if not v or "${" in v or "{{" in v:   # dynamic expression, not a static key/bean
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


def add_var(ctx, model_key, name):
    """Record a plain variable identifier declared/mapped/used by a model."""
    if not model_key or not name:
        return
    name = str(name).strip()
    if re.match(r"^[A-Za-z_]\w*$", name) and name not in FLOWABLE_CONTEXT and name not in JAVA_LITERALS:
        ctx["var_use"].setdefault(name, set()).add(model_key)


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
                    f = read_fields(el)
                    ev = f.get("eventType") or child_text(el, "eventType")
                    add_ref(ctx, pkey, "bpmn", ffile, "sends-event", "event", ev)
                # data object service task (field injection)
                ext = ext_el(el)
                if ext is not None:
                    dom = ext.find("dataObjectMapping")
                    if dom is not None:
                        add_ref(ctx, pkey, "bpmn", ffile, "dataObjectMapping", "dataObject",
                                dom.get("definitionKey"))
            elif tag == "scriptTask":
                info["scriptTasks"].append({"id": eid, "name": ename, "format": el.get("scriptFormat")})
            elif tag == "businessRuleTask":
                f = read_fields(el)
                dref = (el.get("decisionTableReferenceKey") or f.get("decisionTableReferenceKey")
                        or text_of(el, "decisionRef"))
                info["ruleTasks"].append({"id": eid, "name": ename, "decisionRef": dref})
                add_ref(ctx, pkey, "bpmn", ffile, "ruleTask-decision", "decision", dref)
            elif tag == "callActivity":
                called = el.get("calledElement")
                info["callActivities"].append({"id": eid, "name": ename, "calledElement": called,
                                                "inOut": read_in_out(el)})
                add_ref(ctx, pkey, "bpmn", ffile, "callActivity", "process", called)
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
    d["listeners"] = read_listeners(el)
    collect_listener_refs(ctx, case_key, "cmmn", ffile, d["listeners"])
    return d


def _cmmn_walk(ctx, case_key, ffile, stage):
    defs = {c.get("id"): c for c in stage if c.get("id")}
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
        target = defs.get(pi.get("definitionRef"))
        if target is None:
            node["children"].append({"id": pi.get("id"), "name": pi.get("name"),
                                    "type": "planItem(?)", "rules": rules})
        elif target.tag in ("stage", "planFragment"):
            child = _cmmn_walk(ctx, case_key, ffile, target)
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
        if plan is not None and plan.get("formKey"):
            add_ref(ctx, ckey, "cmmn", ffile, "start-form", "form", plan.get("formKey"))
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
                    info["eventListeners"].append({"id": el.get("id"), "name": el.get("name"), "type": el.tag})
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
    info = {"key": key, "name": meta.get("name"), "file": ffile,
            "modelType": meta.get("modelType", "form"), "fields": [], "outcomes": [],
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


def parse_service(data, ctx, ffile):
    doc = json.loads(data)
    cfg = doc.get("config") or {}
    base = cfg.get("baseUrl") or cfg.get("url")
    info = {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "type": doc.get("type"), "baseUrl": base, "auth": (cfg.get("authentication") or {}).get("type"),
            "tableName": doc.get("tableName"),
            "referencedLiquibaseModelKey": doc.get("referencedLiquibaseModelKey"),
            "referenceKey": doc.get("referenceKey"),
            "columns": [c.get("columnName") or c.get("name") for c in (doc.get("columnMappings") or [])],
            "operations": []}
    for op in doc.get("operations", []) or []:
        if not isinstance(op, dict):
            continue
        oc = op.get("config") or {}
        full = oc.get("url")
        if base and full and not str(full).startswith("http"):
            full = base.rstrip("/") + "/" + str(full).lstrip("/")
        info["operations"].append({"key": op.get("key"), "name": op.get("name"),
                                   "method": oc.get("method"), "url": oc.get("url"), "fullUrl": full})
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
    return {"key": key, "name": doc.get("name"), "file": ffile, "botKey": doc.get("botKey"),
            "formKey": doc.get("formKey"), "signalName": doc.get("signalName"),
            "channels": doc.get("channels"), "scopeType": doc.get("scopeType"),
            "icon": doc.get("icon"), "permissionGroups": doc.get("permissionGroups")}


def parse_event(data, ctx, ffile):
    doc = json.loads(data)
    return {"key": doc.get("key"), "name": doc.get("name"), "file": ffile,
            "correlation": [p.get("name") for p in (doc.get("correlationParameters") or [])],
            "payload": [p.get("name") for p in (doc.get("payload") or [])]}


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
    return {"key": key, "name": doc.get("name"), "file": ffile,
            "dataObjectType": doc.get("dataObjectType"), "sourceId": doc.get("sourceId"),
            "service": doc.get("referencedServiceDefinitionModelKey"),
            "dictionary": doc.get("referencedDataDictionaryModelKey"),
            "columns": [{"name": f.get("name"), "label": f.get("label")}
                        for f in (doc.get("fieldMappings") or [])],
            "fields": [f.get("name") for f in (doc.get("fieldMappings") or [])]}


def parse_policy(data, ctx, ffile):
    doc = json.loads(data)
    perms = []
    for pk, pv in (doc.get("permissionMappings") or {}).items():
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
    return out


PARSERS = {
    "app": parse_app, "bpmn": parse_bpmn, "cmmn": parse_cmmn, "dmn": parse_dmn,
    "form": parse_form, "page": parse_form, "agent": parse_agent, "service": parse_service,
    "channel": parse_channel, "event": parse_event, "dataDictionary": parse_dictionary,
    "dataObject": parse_data_object, "securityPolicy": parse_policy, "action": parse_action,
}


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


def extract(root):
    ctx = {"refs": [], "rest_calls": [], "expr": set(), "mustache": set(),
           "delegate_classes": set(), "access": [], "groups": set(),
           "expr_use": {}, "mustache_use": {}, "var_use": {}}
    result = {"apps": [], "processes": [], "cases": [], "decisions": [], "forms": [],
              "agents": [], "services": [], "channels": [], "events": [], "dictionaries": [],
              "dataObjects": [], "policies": [], "actions": [], "liquibase": [], "others": [],
              "javaBeans": [], "javaControllers": [], "javaGlue": [], "endpoints": [], "warnings": []}
    model_index, by_key = {}, {}

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
                bucket = {"app": "apps", "bpmn": "processes", "cmmn": "cases", "dmn": "decisions",
                          "form": "forms", "page": "forms", "agent": "agents", "service": "services",
                          "channel": "channels", "event": "events", "dataDictionary": "dictionaries",
                          "dataObject": "dataObjects", "securityPolicy": "policies", "action": "actions"}[mtype]
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
            result["warnings"].append(f"parse {label} ({mtype}): {e}")
        # Attribute every ${...} / {{...}} occurrence to the model(s) in this file.
        for k in mkeys:
            if not k:
                continue
            for e in exprs:
                ctx["expr_use"].setdefault(e, set()).add(k)
            for m in musts:
                ctx["mustache_use"].setdefault(m, set()).add(k)
        _collect_declared_vars(ctx, raw, mkeys)

    def _index(mtype, obj, label, key=None):
        key = key if key is not None else (obj.get("key") if isinstance(obj, dict) else None)
        norm = {"bpmn": "process", "process": "process", "cmmn": "case"}.get(mtype, mtype)
        if key:
            model_index[(norm, key)] = label
            by_key.setdefault(key, []).append((norm, label))

    models, archives, javas, xmls = discover(root)

    for path in models:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "rb") as fh:
                dispatch(model_type_for(os.path.basename(path)), fh.read(), rel)
        except Exception as e:  # noqa: BLE001
            result["warnings"].append(f"read {rel}: {e}")

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
            result["warnings"].append(f"archive {rel}: {e}")

    # Java
    bean_index, class_index, fqn_index, all_java = {}, {}, {}, {}
    for path in javas:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                jc = parse_java(fh.read(), rel)
        except Exception as e:  # noqa: BLE001
            result["warnings"].append(f"java {rel}: {e}")
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
    for path in xmls:
        rel = os.path.relpath(path, root) if os.path.isdir(root) else os.path.basename(path)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                txt = fh.read()
        except Exception:
            continue
        if "databaseChangeLog" not in txt and "<changeSet" not in txt and "createTable" not in txt.lower():
            continue
        tables = sorted(set(re.findall(r'tableName="([^"]+)"', txt)))
        result["liquibase"].append({"key": _liquibase_key(rel), "file": rel, "tables": tables})

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
    ctx["rest_calls"] = _dedupe(ctx["rest_calls"], lambda rc: (rc["source"], rc.get("where"), rc["url"]))
    for bucket in ("apps", "processes", "cases", "decisions", "forms", "agents",
                   "services", "channels", "events", "dictionaries", "dataObjects",
                   "policies", "actions", "liquibase", "others"):
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
                                                  "document", "variableExtractor", "dashboardComponent"):
            norm = kind.split(":", 1)[1] if kind.startswith("model:") else kind
            norm = {"bpmn": "process", "cmmn": "case"}.get(norm, norm)
            target = model_index.get((norm, val))
            if target is None and val in by_key:
                target = by_key[val][0][1]
            ref2 = dict(ref, target=target, targetType="model")
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

    # ---- Build a navigable graph (nodes + edges) for the HTML explorer ----
    graph = _build_graph(result, ctx, resolved, all_java, bean_methods, by_key)

    result.update({
        "modelIndex": {f"{k[0]}:{k[1]}": v for k, v in model_index.items()},
        "resolvedRefs": resolved, "unresolvedRefs": unresolved,
        "restCalls": ctx["rest_calls"], "beanIndex": sorted(bean_index.keys()),
        "variables": sorted(variables - beans), "beans": sorted(beans),
        "expressions": sorted(ctx["expr"]), "placeholders": sorted(ctx["mustache"]),
        "delegateClasses": sorted(ctx["delegate_classes"]),
        "access": ctx["access"], "groups": sorted(ctx["groups"]),
        "javaByRole": result.get("javaByRole", {}), "graph": graph,
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


def _liquibase_key(path):
    """Derive a liquibase model key from a changelog filename (the authoritative
    referencedLiquibaseModelKey points at this), e.g. liquibase-APP-L003.data.changelog.xml -> APP-L003."""
    base = path.split("!")[-1].rsplit("/", 1)[-1]
    base = re.sub(r"^liquibase-", "", base)
    base = re.sub(r"\.data\.changelog\.xml$|\.changelog\.xml$|\.xml$|\.sql$", "", base, flags=re.I)
    return base


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


def _build_graph(result, ctx, resolved, all_java, bean_methods, by_key):
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

    # --- model nodes from buckets ---
    for a in result["apps"]:
        add_node("app", a.get("key"), a.get("name"), a.get("file"), a)
    for p in result["processes"]:
        add_node("process", p.get("key"), p.get("name"), p.get("file"), p)
    for c in result["cases"]:
        add_node("case", c.get("key"), c.get("name"), c.get("file"), c)
    for d in result["decisions"]:
        add_node("decision", d.get("key"), d.get("name"), d.get("file"), d)
    for f in result["forms"]:
        add_node(f.get("modelType", "form"), f.get("key"), f.get("name"), f.get("file"), f)
    for d in result["dataObjects"]:
        add_node("dataObject", d.get("key"), d.get("name"), d.get("file"), d)
    for s in result["services"]:
        add_node("service", s.get("key"), s.get("name"), s.get("file"), s)
    for a in result["agents"]:
        add_node("agent", a.get("key"), a.get("name"), a.get("file"), a)
    for c in result["channels"]:
        add_node("channel", c.get("key"), c.get("name"), c.get("file"), c)
    for e in result["events"]:
        add_node("event", e.get("key"), e.get("name"), e.get("file"), e)
    for d in result["dictionaries"]:
        add_node("dataDictionary", d.get("key"), d.get("name"), d.get("file"), d)
    for p in result["policies"]:
        add_node("securityPolicy", p.get("key"), p.get("name"), p.get("file"), p)
    for a in result["actions"]:
        add_node("action", a.get("key"), a.get("name"), a.get("file"), a)
    for lb in result["liquibase"]:
        add_node("liquibase", lb.get("key"), os.path.basename(lb["file"]), lb["file"],
                 {"tables": lb.get("tables")})
    for o in result["others"]:
        add_node(o.get("modelType", "other"), o.get("key"), o.get("name"), o.get("file"), o)

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
        if (jc["roles"] - {"other"}) or fqn in referenced_java or jc.get("vars"):
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
        for text, keys in usage.items():
            used = sorted({key_to_node[k] for k in keys if k in key_to_node})
            if used:
                add_node(ntype, text, text, None, {"usedBy": used})
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

    # Reverse direction: attach to each model the artifacts it uses (vars/exprs/...)
    # so a process/case/form can list "all its variables" (rendered collapsible).
    for n in list(nodes.values()):
        if n["type"] in ("variable", "expression", "binding", "string"):
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

    # model -> external (unresolved beans/classes/platform) so "what it touches" is complete
    ext_seen = set()
    for r in result.get("unresolvedRefs", []):
        if r["kind"] not in ("bean", "class"):
            continue
        platform = r["kind"] == "bean" and r["value"] in FLOWABLE_PLATFORM_BEANS
        nid = f"external:{r['value']}"
        if nid not in ext_seen:
            ext_seen.add(nid)
            nodes[nid] = {"id": nid, "type": "external", "label": r["value"], "key": r["value"],
                          "file": None, "data": {"platform": platform, "kind": r["kind"]}}
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
            nid = f"external:{rc['url']}"
            if nid not in ext_seen:
                ext_seen.add(nid)
                nodes[nid] = {"id": nid, "type": "external", "label": rc["url"], "key": rc["url"],
                              "file": None, "data": {"external_url": True, "method": rc.get("method")}}
            add_edge(s, nid, "rest-call")

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
    for fqn, jc in all_java.items():
        snode = f"java:{fqn}"
        if snode not in nodes:
            continue
        for dep in jc.get("deps", set()):
            dfqn = simple_to_fqn.get(dep)
            if dfqn and dfqn != fqn and f"java:{dfqn}" in nodes:
                add_edge(snode, f"java:{dfqn}", "uses")

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

    # service / data object -> liquibase changelog, via AUTHORITATIVE signals only
    # (the model's referencedLiquibaseModelKey and tableName, or the data object's
    # own schema changelog filename) — NOT loose key-in-text, which picks up wrong
    # copy-pasted headers like <property serviceDefinitionReferences value=...>.
    lb_by_key = {lb["key"]: f"liquibase:{lb['key']}" for lb in result["liquibase"]}
    lb_by_table = {}
    for lb in result["liquibase"]:
        for t in (lb.get("tables") or []):
            lb_by_table.setdefault(t.upper(), set()).add(f"liquibase:{lb['key']}")
    for n in nodes.values():
        if n["type"] == "service":
            rk = n["data"].get("referencedLiquibaseModelKey")
            if rk and rk in lb_by_key:
                add_edge(n["id"], lb_by_key[rk], "schema")
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
                norm = {"bpmn": "process", "cmmn": "case"}.get(cm.get("type"), cm.get("type"))
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
            L.append(f"- `{d.get('key')}` ({d.get('dataObjectType') or 'dataObject'}) "
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
        hdr(14, "Warnings")
        for w in result["warnings"][:50]:
            L.append(f"- {w}")
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
HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Flowable Atlas</title>
<style>
  :root{
    --bg:#0a0c0f; --panel:#101418; --panel2:#0d1115; --line:#1d242c; --line2:#2a333d;
    --ink:#e7edf3; --ink-dim:#9aa7b4; --ink-faint:#66727f; --accent:#34e0c0;
    --mono:"SFMono-Regular",ui-monospace,"JetBrains Mono",Menlo,Consolas,monospace;
    --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,sans-serif;
    --serif:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;
    --c-app:#e6edf3;--c-process:#34e0c0;--c-case:#4cc9f0;--c-decision:#b694ff;
    --c-form:#f4b942;--c-page:#e89b3b;--c-dataObject:#ff7a93;--c-dataDictionary:#ff9eb0;
    --c-service:#5b9cff;--c-agent:#ff6fd8;--c-channel:#9be15d;--c-event:#ff9f45;
    --c-endpoint:#56c2d6;--c-java:#b8c0cc;--c-query:#8aa0b4;--c-template:#c9a26b;
    --c-sequence:#8aa0b4;--c-action:#6fe0a8;--c-document:#8aa0b4;--c-variableExtractor:#8aa0b4;
    --c-securityPolicy:#ffb3c0;--c-group:#d8b75a;--c-external:#6b7480;--c-masterData:#ff7a93;
    --c-knowledgeBase:#b694ff;--c-sla:#c9a26b;--c-dashboardComponent:#6fe0a8;
    --c-action:#6fe0a8;--c-bot:#ff6fd8;--c-liquibase:#7aa0ff;--c-expression:#b08cff;--c-binding:#5fd0e0;--c-variable:#ffd27f;--c-string:#9ad07a;--c-method:#9fb8ff;
  }
  *{box-sizing:border-box}
  html,body{height:100%;margin:0}
  body{
    background:var(--bg);color:var(--ink);font-family:var(--sans);font-size:13px;
    background-image:
      radial-gradient(1200px 600px at 80% -10%, rgba(52,224,192,.06), transparent 60%),
      linear-gradient(rgba(255,255,255,.018) 1px, transparent 1px),
      linear-gradient(90deg, rgba(255,255,255,.018) 1px, transparent 1px);
    background-size:auto, 32px 32px, 32px 32px;
  }
  ::-webkit-scrollbar{width:10px;height:10px}
  ::-webkit-scrollbar-thumb{background:#222b34;border-radius:6px;border:2px solid var(--bg)}
  ::-webkit-scrollbar-thumb:hover{background:#2f3a45}
  .mono{font-family:var(--mono)}
  a{color:inherit}

  header{
    position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:18px;
    padding:0 18px;height:54px;border-bottom:1px solid var(--line);
    background:rgba(10,12,15,.82);backdrop-filter:blur(10px);
  }
  .brand{display:flex;align-items:baseline;gap:10px;white-space:nowrap}
  .brand .mark{font-family:var(--serif);font-size:20px;letter-spacing:.04em;color:var(--ink)}
  .brand .mark b{color:var(--accent);font-weight:600}
  .brand .proj{font-family:var(--mono);font-size:11px;color:var(--ink-dim);
    border:1px solid var(--line2);padding:2px 8px;border-radius:999px}
  .search{flex:1;position:relative;max-width:520px;margin:0 auto}
  .search input{
    width:100%;background:var(--panel2);border:1px solid var(--line2);color:var(--ink);
    font-family:var(--mono);font-size:12px;padding:8px 12px 8px 32px;border-radius:8px;outline:none}
  .search input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(52,224,192,.12)}
  .search .ic{position:absolute;left:11px;top:50%;transform:translateY(-50%);color:var(--ink-faint)}
  .search .hint{position:absolute;right:10px;top:50%;transform:translateY(-50%);
    color:var(--ink-faint);font-family:var(--mono);font-size:10px;border:1px solid var(--line2);
    border-radius:4px;padding:1px 5px}
  .results{position:absolute;top:42px;left:0;right:0;background:var(--panel);
    border:1px solid var(--line2);border-radius:8px;max-height:60vh;overflow:auto;
    box-shadow:0 16px 50px rgba(0,0,0,.5);display:none}
  .results.on{display:block}
  .results .r{display:flex;align-items:center;gap:9px;padding:8px 12px;cursor:pointer;border-bottom:1px solid var(--line)}
  .results .r:hover,.results .r.sel{background:#161b21}
  .stats{display:flex;gap:14px;color:var(--ink-dim);font-family:var(--mono);font-size:11px;white-space:nowrap}
  .stats b{color:var(--ink)}

  .layout{display:grid;grid-template-columns:230px 330px 1fr;height:calc(100vh - 54px)}
  .col{overflow:auto;border-right:1px solid var(--line)}
  .col.detail{border-right:none}

  /* left rail */
  .rail{padding:14px 8px}
  .rail .sec{font-family:var(--mono);font-size:10px;letter-spacing:.14em;text-transform:uppercase;
    color:var(--ink-faint);padding:14px 12px 6px}
  .cat{display:flex;align-items:center;gap:9px;padding:6px 12px;border-radius:7px;cursor:pointer;color:var(--ink-dim)}
  .cat:hover{background:#141a20;color:var(--ink)}
  .cat.on{background:#152028;color:var(--ink)}
  .cat.on .dot{box-shadow:0 0 0 3px rgba(255,255,255,.06)}
  .cat .lbl{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .cat .n{font-family:var(--mono);font-size:11px;color:var(--ink-faint)}
  .dot{width:8px;height:8px;border-radius:50%;flex:none;background:var(--ink-faint)}

  /* list column */
  .listhead{position:sticky;top:0;background:var(--panel2);border-bottom:1px solid var(--line);padding:10px;z-index:5}
  .listhead .t{font-family:var(--mono);font-size:11px;color:var(--ink-dim);margin-bottom:8px;display:flex;justify-content:space-between}
  .listhead input{width:100%;background:#0a0e12;border:1px solid var(--line2);color:var(--ink);
    font-family:var(--mono);font-size:12px;padding:6px 9px;border-radius:6px;outline:none}
  .listhead input:focus{border-color:var(--accent)}
  .item{display:flex;align-items:flex-start;gap:9px;padding:9px 12px;cursor:pointer;border-bottom:1px solid var(--line);
    animation:rise .25s ease both}
  .item:hover{background:#12171c}
  .item.on{background:#15202a;box-shadow:inset 3px 0 0 var(--accent)}
  .item .meta{min-width:0}
  .item .nm{color:var(--ink);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .item .sub{font-family:var(--mono);font-size:10.5px;color:var(--ink-faint);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  @keyframes rise{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:none}}

  /* detail */
  .detail{padding:0}
  .empty{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:var(--ink-faint);gap:10px;text-align:center}
  .empty .big{font-family:var(--serif);font-size:30px;color:var(--ink-dim)}
  .crumbs{position:sticky;top:0;background:rgba(10,12,15,.82);backdrop-filter:blur(8px);
    border-bottom:1px solid var(--line);padding:8px 18px;display:flex;align-items:center;gap:8px;font-family:var(--mono);font-size:11px;z-index:6}
  .crumbs button{background:var(--panel);border:1px solid var(--line2);color:var(--ink-dim);
    border-radius:6px;padding:3px 9px;cursor:pointer;font-family:var(--mono);font-size:11px}
  .crumbs button:hover{color:var(--ink);border-color:var(--accent)}
  .crumbs .trail{color:var(--ink-faint);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .dbody{padding:22px 26px;max-width:1000px}
  .chip{display:inline-flex;align-items:center;gap:6px;font-family:var(--mono);font-size:10.5px;
    border:1px solid var(--line2);border-radius:999px;padding:2px 9px;color:var(--ink-dim)}
  .dtitle{font-family:var(--serif);font-size:30px;line-height:1.15;margin:12px 0 4px}
  .dkey{font-family:var(--mono);font-size:12px;color:var(--accent)}
  .dfile{font-family:var(--mono);font-size:11px;color:var(--ink-faint);margin-top:6px;cursor:copy}
  .dfile:hover{color:var(--ink-dim)}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1px;
    background:var(--line);border:1px solid var(--line);border-radius:8px;overflow:hidden;margin:18px 0}
  .cell{background:var(--panel);padding:10px 12px}
  .cell .k{font-family:var(--mono);font-size:10px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-faint)}
  .cell .v{margin-top:3px;color:var(--ink);word-break:break-word}
  .cell .v.mono{font-family:var(--mono);font-size:11.5px}
  h3.rel{font-family:var(--mono);font-size:11px;letter-spacing:.12em;text-transform:uppercase;
    color:var(--ink-dim);margin:24px 0 10px;display:flex;align-items:center;gap:8px}
  h3.rel:before{content:"";flex:none;width:14px;height:1px;background:var(--line2)}
  .relgrp{margin:0 0 14px}
  .relgrp .lab{font-family:var(--mono);font-size:11px;color:var(--ink-faint);margin:0 0 6px}
  .nodechips{display:flex;flex-wrap:wrap;gap:7px}
  .nc{display:inline-flex;align-items:center;gap:7px;background:var(--panel);border:1px solid var(--line2);
    border-radius:8px;padding:5px 10px;cursor:pointer;max-width:380px}
  .nc:hover{border-color:var(--accent);background:#13191f;transform:translateY(-1px)}
  .nc .nm{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .nc .ty{font-family:var(--mono);font-size:9.5px;color:var(--ink-faint);text-transform:uppercase;letter-spacing:.06em}
  .oplist{border:1px solid var(--line);border-radius:8px;overflow:hidden;margin:8px 0}
  details.uses{border:1px solid var(--line);border-radius:8px;margin:6px 0;background:var(--panel)}
  details.uses>summary{cursor:pointer;padding:8px 12px;font-family:var(--mono);font-size:11px;color:var(--ink-dim);list-style:none}
  details.uses>summary::-webkit-details-marker{display:none}
  details.uses>summary:before{content:"▸ ";color:var(--ink-faint)}
  details.uses[open]>summary:before{content:"▾ "}
  details.uses>summary:hover{color:var(--ink)}
  details.uses[open]>summary{border-bottom:1px solid var(--line);color:var(--ink)}
  details.uses .nodechips{padding:10px 12px}
  .oprow{display:flex;gap:10px;padding:7px 12px;border-bottom:1px solid var(--line);font-family:var(--mono);font-size:11.5px}
  .oprow:last-child{border-bottom:none}
  .verb{font-weight:600;min-width:48px}
  .tag{font-family:var(--mono);font-size:9.5px;text-transform:uppercase;letter-spacing:.05em;
    border:1px solid var(--line2);border-radius:4px;padding:1px 6px;color:var(--ink-dim)}
  .muted{color:var(--ink-faint)}
</style>
</head>
<body>
<header>
  <div class="brand"><div class="mark">Flowable&nbsp;<b>Atlas</b></div><div class="proj mono" id="proj"></div></div>
  <div class="search">
    <span class="ic">⌕</span>
    <input id="q" placeholder="Search everything — keys, files, classes, groups…" autocomplete="off">
    <span class="hint">/</span>
    <div class="results" id="results"></div>
  </div>
  <div class="stats" id="stats"></div>
</header>
<div class="layout">
  <div class="col"><div class="rail" id="rail"></div></div>
  <div class="col"><div id="list"></div></div>
  <div class="col detail" id="detail"></div>
</div>
<script>
const DATA = __ATLAS_DATA__;
const nodes = DATA.nodes, edges = DATA.edges;
const byId = new Map(nodes.map(n => [n.id, n]));
const TM = {
  app:['Apps','Models'],process:['Processes','Models'],case:['Cases','Models'],
  decision:['Decisions','Models'],form:['Forms','Models'],page:['Pages','Models'],
  dataObject:['Data objects','Models'],dataDictionary:['Data dictionaries','Models'],
  masterData:['Master data','Models'],
  service:['Service models','Integration'],agent:['Agents / bots','Integration'],
  channel:['Channels','Integration'],event:['Events','Integration'],knowledgeBase:['Knowledge bases','Integration'],
  endpoint:['REST endpoints','Code'],java:['Java classes','Code'],method:['Java methods','Code'],liquibase:['Liquibase changelogs','Code'],
  action:['Actions','Integration'],bot:['Bots','Integration'],
  query:['Queries','Other'],template:['Templates','Other'],sequence:['Sequences','Other'],
  document:['Documents','Other'],variableExtractor:['Variable extractors','Other'],
  sla:['SLAs','Other'],dashboardComponent:['Dashboard widgets','Other'],
  securityPolicy:['Security policies','Access'],group:['User groups','Access'],
  variable:['Variables','Variables'],
  expression:['Backend expressions ${ }','Expressions'],binding:['Frontend bindings {{ }}','Expressions'],
  string:['String literals','Expressions'],
  external:['External / library','Other'],
};
const SECTIONS = ['Models','Integration','Code','Expressions','Variables','Access','Other'];
const color = t => getComputedStyle(document.body).getPropertyValue('--c-'+t).trim() || '#8aa0b4';

// adjacency
const outM = new Map(), incM = new Map();
const push = (m,k,v)=>{ if(!m.has(k)) m.set(k,[]); m.get(k).push(v); };
edges.forEach(e=>{ push(outM,e.s,{rel:e.rel,id:e.t}); push(incM,e.t,{rel:e.rel,id:e.s}); });

// bean name -> java node id (for direct links from ${bean.method()} expressions)
const beanToNode = new Map();
nodes.filter(n=>n.type==='java').forEach(n=>{
  (n.data.beanNames||[]).forEach(b=>beanToNode.set(b,n.id));
  const dc=n.label.charAt(0).toLowerCase()+n.label.slice(1);
  if(!beanToNode.has(dc)) beanToNode.set(dc,n.id);
});

// state
let state = {cat:null, sel:null, hist:[], filter:''};

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
    } else {
      const m = TM[t]||[t,'Other'];
      cats.push({id:t,label:m[0],sec:m[1],color:color(t),count:byType[t].length,match:n=>n.type===t});
    }
  });
  cats.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) || a.label.localeCompare(b.label));
  return cats;
}
const CATS = categories();

function renderRail(){
  const rail = document.getElementById('rail'); rail.innerHTML='';
  let cur='';
  CATS.forEach(c=>{
    if(c.sec!==cur){ cur=c.sec; const h=document.createElement('div'); h.className='sec'; h.textContent=cur; rail.appendChild(h); }
    const el=document.createElement('div'); el.className='cat'+(state.cat===c.id?' on':'');
    el.innerHTML='<span class="dot" style="background:'+c.color+'"></span><span class="lbl">'+c.label+'</span><span class="n">'+c.count+'</span>';
    el.onclick=()=>{ state.cat=c.id; state.filter=''; renderRail(); renderList(); };
    rail.appendChild(el);
  });
}

function renderList(){
  const cat = CATS.find(c=>c.id===state.cat);
  const list = document.getElementById('list'); list.innerHTML='';
  if(!cat) return;
  const head=document.createElement('div'); head.className='listhead';
  head.innerHTML='<div class="t"><span>'+cat.label+'</span><span class="muted">'+cat.count+'</span></div><input id="lf" placeholder="filter '+cat.label.toLowerCase()+'…">';
  list.appendChild(head);
  let items = nodes.filter(cat.match);
  const f = state.filter.toLowerCase();
  if(f) items = items.filter(n => (n.label+' '+n.key+' '+(n.file||'')).toLowerCase().includes(f));
  items.sort((a,b)=>a.label.localeCompare(b.label));
  const wrap=document.createElement('div');
  items.slice(0,600).forEach((n,i)=>{
    const el=document.createElement('div'); el.className='item'+(state.sel===n.id?' on':'');
    el.style.animationDelay=Math.min(i*8,300)+'ms';
    el.innerHTML='<span class="dot" style="margin-top:5px;background:'+color(n.type)+'"></span>'+
      '<div class="meta"><div class="nm">'+esc(n.label)+'</div><div class="sub">'+esc(n.key)+'</div></div>';
    el.onclick=()=>select(n.id,true);
    wrap.appendChild(el);
  });
  if(items.length>600){ const m=document.createElement('div'); m.className='item muted'; m.textContent='… +'+(items.length-600)+' more (use search)'; wrap.appendChild(m); }
  list.appendChild(wrap);
  const lf=document.getElementById('lf'); lf.value=state.filter;
  lf.oninput=()=>{ state.filter=lf.value; renderList(); const x=document.getElementById('lf'); x.focus(); x.setSelectionRange(x.value.length,x.value.length); };
}

// ---------- detail ----------
function relName(r){ return r; }
function nodeChip(id){
  const n=byId.get(id); if(!n) return '';
  return '<span class="nc" data-id="'+enc(id)+'"><span class="dot" style="background:'+color(n.type)+'"></span>'+
    '<span class="nm">'+esc(n.label)+'</span><span class="ty">'+n.type+'</span></span>';
}
function groupRels(arr){ const g={}; (arr||[]).forEach(x=>{ (g[x.rel]=g[x.rel]||new Set()).add(x.id); }); return g; }

function describe(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  if(n.type==='process'){ add('Starter groups',d.candidateStarterGroups); add('User tasks',(d.userTasks||[]).length);
    add('Service tasks',(d.serviceTasks||[]).length); add('Call activities',(d.callActivities||[]).length);
    add('Documentation',d.documentation); }
  else if(n.type==='case'){ add('Starter groups',d.candidateStarterGroups); add('Initiator var',d.initiatorVariableName); add('Documentation',d.documentation); }
  else if(n.type==='decision'){ add('Hit policy',d.hitPolicy); add('Rules',d.ruleCount); add('Inputs',(d.inputs||[]).join(', ')); add('Outputs',(d.outputs||[]).join(', ')); }
  else if(n.type==='form'||n.type==='page'){ add('Fields',(d.fields||[]).length); add('Outcomes',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(', ')); }
  else if(n.type==='dataObject'){ add('Type',d.dataObjectType); add('Data source',d.sourceId); add('Backing service',d.service); add('Data dictionary',d.dictionary); add('Columns',(d.fields||[]).length); }
  else if(n.type==='service'){ add('Type',d.type); add('Base URL',d.baseUrl); add('Auth',d.auth); add('Table',d.tableName); add('Liquibase model',d.referencedLiquibaseModelKey); add('Columns',(d.columns||[]).join(', ')); add('Operations',(d.operations||[]).length); }
  else if(n.type==='agent'){ add('Vendor / model',(d.aiVendor||'')+' / '+(d.modelName||'')); add('Temperature',d.temperature); add('API endpoint',String(d.enableApiEndpoint)); add('Knowledge base',d.knowledgeBase); }
  else if(n.type==='channel'){ add('Direction',d.channelType); add('Type',d.type); add('Topics',(d.topics||[]).join(', ')); add('Destination',d.destination); }
  else if(n.type==='event'){ add('Payload',(d.payload||[]).join(', ')); add('Correlation',(d.correlation||[]).join(', ')); }
  else if(n.type==='java'){ add('Package',d.package); add('Roles',(d.roles||[]).join(', ')); add('Bot key',d.botKey); add('Implements',(d.interfaces||[]).join(', ')); add('Methods',(d.methods||[]).length); add('Called from models',(d.calledMethods||[]).join(', ')); }
  else if(n.type==='endpoint'){ add('Method',d.http); add('Path',d.path); add('Handler',(d.controller||'')+'#'+(d.handler||'')); }
  else if(n.type==='method'){ add('Method',(d.name||'')+'()'); add('Declared in',d.class); }
  else if(n.type==='action'){ add('Bot',d.botKey); add('Form',d.formKey); add('Triggers signal',d.signalName); add('Scope',d.scopeType); }
  else if(n.type==='bot'){ add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); }
  else if(n.type==='liquibase'){ add('Tables',(d.tables||[]).join(', ')); }
  else if(n.type==='expression'||n.type==='binding'){ add('Used by', (d.usedBy||[]).length+' model(s)'); }
  else if(n.type==='variable'){ add('Scope',(d.scopes||[]).join(', ')); add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='string'){ add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='external'){ add('Kind',d.platform?'Flowable platform bean':(d.external_url?'External URL':d.kind||'external')); }
  else { Object.keys(d).forEach(k=>{ const v=d[k]; if(typeof v==='string'||typeof v==='number') add(k,v); }); }
  return rows;
}

function detailExtra(n){
  const d=n.data||{}; let h='';
  if(n.type==='service' && (d.operations||[]).length){
    h+='<h3 class="rel">Operations</h3><div class="oplist">'+
      d.operations.map(o=>'<div class="oprow"><span class="verb" style="color:'+color("endpoint")+'">'+esc(o.method||'?')+'</span><span>'+esc(o.fullUrl||o.url||'')+'</span><span class="muted">'+esc(o.key||'')+'</span></div>').join('')+'</div>';
  }
  if(n.type==='java' && (d.endpoints||[]).length){
    h+='<h3 class="rel">Endpoints served</h3><div class="oplist">'+
      d.endpoints.map(e=>'<div class="oprow"><span class="verb" style="color:'+color("endpoint")+'">'+esc(e.http)+'</span><span>'+esc(e.path)+'</span><span class="muted">'+esc(e.handler)+'() :'+e.line+'</span></div>').join('')+'</div>';
  }
  if(n.type==='java' && (d.methods||[]).length){
    const cm=new Set(d.calledMethods||[]);
    h+='<h3 class="rel">Declared methods ('+d.methods.length+')</h3><div class="oplist">'+
      d.methods.slice(0,80).map(m=>'<div class="oprow"><span>'+esc(m.name)+'('+m.params+')</span><span class="muted">:'+m.line+(cm.has(m.name)?'  ◀ called by models':'')+'</span></div>').join('')+'</div>';
  }
  if((n.type==='process') && (d.serviceTasks||[]).length){
    const st=d.serviceTasks.filter(s=>s.class||s.delegateExpression||s.expression||s.type);
    if(st.length) h+='<h3 class="rel">Service tasks</h3><div class="oplist">'+
      st.map(s=>'<div class="oprow"><span class="muted" style="min-width:150px">'+esc(s.name||s.id)+'</span>'+
        '<span style="flex:1">'+esc(s.class||s.delegateExpression||s.expression||s.type||'')+'</span>'+
        implLink(s)+'</div>').join('')+'</div>';
  }
  if(n.type==='dataObject' && (d.columns||[]).length){
    h+='<h3 class="rel">Columns / field mappings ('+d.columns.length+')</h3><div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name)+'</span><span class="muted">'+esc(c.label||'')+'</span></div>').join('')+'</div>';
  }
  if((n.type==='expression'||n.type==='binding') && (d.usedBy||[]).length){
    h+='<h3 class="rel">Used by ('+d.usedBy.length+')</h3><div class="nodechips">'+d.usedBy.map(nodeChip).join('')+'</div>';
  }
  if((n.type==='variable'||n.type==='string') && (d.usages||[]).length){
    h+='<h3 class="rel">Used in ('+d.usages.length+' models) — effective occurrences</h3>';
    d.usages.forEach(u=>{
      h+='<div style="margin:6px 0 12px">'+nodeChip(u.model)+
         '<div class="oplist" style="margin-top:5px">'+
         (u.snippets||[]).map(s=>'<div class="oprow"><span class="mono">'+esc(s)+'</span></div>').join('')+
         '</div></div>';
    });
  }
  // Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).
  if(d._uses){
    const ord=[['variable','Variables'],['expression','Backend expressions ${ }'],
               ['binding','Frontend bindings {{ }}'],['string','String literals']];
    let parts='';
    ord.forEach(([t,lbl])=>{ const ids=(d._uses||{})[t]; if(ids&&ids.length)
      parts+='<details class="uses"><summary>'+lbl+' ('+ids.length+')</summary><div class="nodechips">'+ids.map(nodeChip).join('')+'</div></details>'; });
    if(parts) h+='<h3 class="rel">Uses — variables &amp; expressions</h3>'+parts;
  }
  return h;
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
  return '<span class="nc" data-id="'+enc(id)+'" style="flex:none"><span class="dot" style="background:'+color('java')+'"></span><span class="nm">'+esc(label)+'</span></span>';
}

function renderDetail(){
  const det=document.getElementById('detail');
  if(!state.sel || !byId.get(state.sel)){
    det.innerHTML='<div class="empty"><div class="big">Flowable Atlas</div><div>Pick a category on the left, then an item.<br>Click any relationship to travel the graph.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  let h='';
  // crumbs
  const trail = state.hist.slice(-4).map(id=>{const x=byId.get(id);return x?esc(x.label):'';}).filter(Boolean).join(' › ');
  h+='<div class="crumbs">'+(state.hist.length?'<button id="back">← back</button>':'')+
     '<span class="trail">'+trail+(trail?' › ':'')+'<b style="color:var(--ink)">'+esc(n.label)+'</b></span></div>';
  h+='<div class="dbody">';
  h+='<span class="chip"><span class="dot" style="background:'+color(n.type)+'"></span>'+(TM[n.type]?TM[n.type][0]:n.type)+'</span>';
  h+='<div class="dtitle">'+esc(n.label)+'</div>';
  h+='<div class="dkey mono">'+esc(n.key)+'</div>';
  if(n.file) h+='<div class="dfile" title="click to copy" data-copy="'+enc(n.file)+'">'+esc(n.file)+'</div>';
  const rows=describe(n);
  if(rows.length){ h+='<div class="grid">'+rows.map(r=>'<div class="cell"><div class="k">'+esc(r[0])+'</div><div class="v mono">'+esc(String(r[1]))+'</div></div>').join('')+'</div>'; }
  h+=detailExtra(n);
  // outgoing
  const ok=Object.keys(out).sort();
  if(ok.length){ h+='<h3 class="rel">Uses / references ('+ok.reduce((a,k)=>a+out[k].size,0)+')</h3>';
    ok.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...out[rel]].map(nodeChip).join('')+'</div></div>'; }); }
  // incoming
  const ik=Object.keys(inc).sort();
  if(ik.length){ h+='<h3 class="rel">Used by / referenced from ('+ik.reduce((a,k)=>a+inc[k].size,0)+')</h3>';
    ik.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...inc[rel]].map(nodeChip).join('')+'</div></div>'; }); }
  if(!ok.length && !ik.length) h+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  h+='</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>{ const prev=state.hist.pop(); if(prev) select(prev,false); };
  det.querySelectorAll('.nc').forEach(c=>c.onclick=()=>select(dec(c.dataset.id),true));
  const fp=det.querySelector('.dfile'); if(fp) fp.onclick=()=>{navigator.clipboard&&navigator.clipboard.writeText(dec(fp.dataset.copy)); fp.textContent='✓ copied — '+dec(fp.dataset.copy); };
}

function select(id, pushHist){
  if(!byId.get(id)) return;
  if(pushHist && state.sel && state.sel!==id) state.hist.push(state.sel);
  state.sel=id;
  const n=byId.get(id);
  // Keep the current category if it already contains this node (so clicking within
  // e.g. "Java · delegate" stays there) — only re-sync when it doesn't match.
  const cur=CATS.find(c=>c.id===state.cat);
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
    if(cat) state.cat=cat.id;
  }
  location.hash=encodeURIComponent(id);
  renderRail(); renderList(); renderDetail();
  // scroll selected list item into view
  const on=document.querySelector('.item.on'); if(on) on.scrollIntoView({block:'nearest'});
}

// ---------- search ----------
const q=document.getElementById('q'), results=document.getElementById('results');
let resSel=-1, resList=[];
function doSearch(){
  const v=q.value.trim().toLowerCase();
  if(!v){ results.classList.remove('on'); return; }
  resList = nodes.filter(n=>(n.label+' '+n.key+' '+(n.file||'')+' '+n.type).toLowerCase().includes(v))
                 .sort((a,b)=> a.label.length-b.label.length).slice(0,40);
  results.innerHTML = resList.map((n,i)=>'<div class="r'+(i===resSel?' sel':'')+'" data-id="'+enc(n.id)+'">'+
    '<span class="dot" style="background:'+color(n.type)+'"></span><span class="nm">'+esc(n.label)+'</span>'+
    '<span class="ty mono" style="color:var(--ink-faint);font-size:10px">'+n.type+'</span>'+
    '<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(n.key)+'</span></div>').join('')
    || '<div class="r muted">no matches</div>';
  results.classList.add('on');
  results.querySelectorAll('.r[data-id]').forEach(r=>r.onclick=()=>{ select(dec(r.dataset.id),true); closeSearch(); });
}
function closeSearch(){ results.classList.remove('on'); q.blur(); resSel=-1; }
q.addEventListener('input',()=>{ resSel=-1; doSearch(); });
q.addEventListener('keydown',e=>{
  if(e.key==='ArrowDown'){ resSel=Math.min(resSel+1,resList.length-1); doSearch(); e.preventDefault(); }
  else if(e.key==='ArrowUp'){ resSel=Math.max(resSel-1,0); doSearch(); e.preventDefault(); }
  else if(e.key==='Enter' && resList[resSel]){ select(resList[resSel].id,true); closeSearch(); }
  else if(e.key==='Escape'){ closeSearch(); }
});
document.addEventListener('keydown',e=>{ if(e.key==='/' && document.activeElement!==q){ e.preventDefault(); q.focus(); } });
document.addEventListener('click',e=>{ if(!e.target.closest('.search')) results.classList.remove('on'); });

// ---------- utils ----------
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function enc(s){ return encodeURIComponent(s); }
function dec(s){ return decodeURIComponent(s); }

// ---------- boot ----------
document.getElementById('proj').textContent=DATA.project;
const st=DATA.stats;
document.getElementById('stats').innerHTML=
  '<span><b>'+nodes.length+'</b> nodes</span><span><b>'+edges.length+'</b> links</span>'+
  '<span><b>'+(st.models||0)+'</b> models</span><span><b>'+(st.java||0)+'</b> java</span><span><b>'+(st.groups||0)+'</b> groups</span>';
state.cat = (CATS.find(c=>c.id==='process')||CATS[0]||{}).id;
renderRail(); renderList(); renderDetail();
const hash=location.hash?decodeURIComponent(location.hash.slice(1)):'';
if(hash && byId.get(hash)) select(hash,false);
window.addEventListener('hashchange',()=>{ const h=decodeURIComponent(location.hash.slice(1)); if(h&&byId.get(h)&&h!==state.sel) select(h,false); });
</script>
</body>
</html>"""


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


def html_render(result, root):
    payload = {"project": os.path.basename(os.path.abspath(root)) or "project",
               "stats": result["stats"],
               "nodes": result["graph"]["nodes"],
               "edges": result["graph"]["edges"]}
    data = json.dumps(payload, ensure_ascii=False, default=list).replace("</", "<\\/")
    return HTML_TEMPLATE.replace("__ATLAS_DATA__", data)


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
            except Exception:  # noqa: BLE001
                pass
            return


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="flowable_atlas.py",
        description="Flowable Atlas — map any Flowable project (models + Java) into an "
                    "interactive HTML explorer, an LLM overview and a traversable graph.")
    ap.add_argument("path", help="Project directory, or a single .zip/.bar archive")
    ap.add_argument("-o", "--output", help="Output file (single mode) or output DIRECTORY (with --all)")
    ap.add_argument("--all", action="store_true",
                    help="Write all four artifacts in one run (summary + overview + graph json + html explorer)")
    ap.add_argument("--json", action="store_true", help="Emit the full traversable graph as JSON")
    ap.add_argument("--html", action="store_true", help="Emit the self-contained interactive HTML explorer")
    ap.add_argument("--summary", action="store_true", help="Emit a compact (~few KB) LLM-first overview")
    ap.add_argument("--stdout", action="store_true", help="Print to stdout instead of writing a file")
    ap.add_argument("--open", dest="open_", action="store_true", help="Open the HTML explorer when done")
    args = ap.parse_args(argv)

    if not os.path.exists(args.path):
        print(f"error: path not found: {args.path}", file=sys.stderr)
        return 2

    result = extract(args.path)
    s = result["stats"]
    name = os.path.splitext(os.path.basename(os.path.abspath(args.path.rstrip("/"))))[0] or "project"

    if args.all:
        outdir = args.output or "."
        os.makedirs(outdir, exist_ok=True)
        artifacts = [
            (f"{name}.summary.md", summary_render(result, args.path)),
            (f"{name}.overview.md", render(result, args.path)),
            (f"{name}.graph.json", json.dumps(result, indent=2, ensure_ascii=False, default=list)),
            (f"{name}.explorer.html", html_render(result, args.path)),
        ]
        written = []
        for fn, content in artifacts:
            p = os.path.join(outdir, fn)
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(content)
            written.append(p)
        print(f"Flowable Atlas — {name}: {s['models']} models · {s['java']} java · "
              f"{s.get('nodes', 0)} nodes · {s.get('edges', 0)} links", file=sys.stderr)
        for p in written:
            print(f"  ✓ {p}", file=sys.stderr)
        if args.open_:
            _open_file(next(p for p in written if p.endswith(".html")))
        return 0

    if args.summary:
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
        target = os.path.join(base, f"APP_OVERVIEW.{ext}")
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(out)
    print(f"wrote {target} — {s['models']} models, {s['java']} java files, "
          f"{s.get('nodes', 0)} nodes / {s.get('edges', 0)} links, "
          f"{len(result['resolvedRefs'])} resolved / {len(result['unresolvedRefs'])} unresolved refs",
          file=sys.stderr)
    if args.open_ and ext == "html":
        _open_file(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
