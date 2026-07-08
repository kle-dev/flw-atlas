"""Unit tests for the individual model parsers on inline documents."""
import conftest  # noqa: F401  (puts the repo root on sys.path)

import flowable_atlas as fa


def _ctx():
    """Fresh parse context matching what extract() hands to the parsers."""
    return {"refs": [], "rest_calls": [], "expr": set(), "mustache": set(),
            "delegate_classes": set(), "access": [], "groups": set(),
            "expr_use": {}, "mustache_use": {}, "var_use": {}, "script_var_use": {},
            "query_meta": {}}


def _refs(ctx):
    return {(r["rel"], r["kind"], r["value"]) for r in ctx["refs"]}


def test_model_type_for():
    assert fa.model_type_for("x.bpmn") == "bpmn"
    assert fa.model_type_for("x.bpmn20.xml") == "bpmn"
    assert fa.model_type_for("x.cmmn.xml") == "cmmn"
    assert fa.model_type_for("x.form") == "form"
    assert fa.model_type_for("x.txt") is None
    assert fa.model_type_for("noext") is None


def test_parse_bpmn_tasks_and_refs():
    xml = b"""<?xml version="1.0"?>
    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                 xmlns:flowable="http://flowable.org/bpmn">
      <process id="p1" name="P" flowable:candidateStarterGroups="g1">
        <userTask id="t1" name="Do" flowable:formKey="f1" flowable:candidateGroups="g2"/>
        <serviceTask id="s1" flowable:delegateExpression="${myBean}"/>
        <callActivity id="c1" calledElement="child"/>
      </process>
    </definitions>"""
    ctx = _ctx()
    procs = fa.parse_bpmn(xml, ctx, "p.bpmn")
    assert [p["key"] for p in procs] == ["p1"]
    assert procs[0]["userTasks"][0]["formKey"] == "f1"
    assert {("userTask-form", "form", "f1"),
            ("serviceTask-delegate", "bean", "myBean"),
            ("callActivity", "process", "child")} <= _refs(ctx)
    assert {"g1", "g2"} <= ctx["groups"]


def test_parse_cmmn_plan_model():
    xml = b"""<?xml version="1.0"?>
    <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                 xmlns:flowable="http://flowable.org/cmmn">
      <case id="c1" name="C">
        <casePlanModel id="pm">
          <planItem id="pi1" definitionRef="h1"/>
          <humanTask id="h1" flowable:formKey="f1"/>
        </casePlanModel>
      </case>
    </definitions>"""
    ctx = _ctx()
    cases = fa.parse_cmmn(xml, ctx, "c.cmmn")
    assert cases[0]["key"] == "c1"
    kids = cases[0]["planModel"]["children"]
    assert [k["id"] for k in kids] == ["h1"]
    assert ("humanTask-form", "form", "f1") in _refs(ctx)


def test_parse_form_fields_and_rest():
    doc = b"""{"metadata": {"key": "f1", "name": "F"},
               "rows": [[{"id": "a", "type": "select", "label": "A",
                          "extraSettings": {"queryUrl": "/api/x", "formRef": "sub1"}}]]}"""
    ctx = _ctx()
    info = fa.parse_form(doc, ctx, "f.form")
    assert info["key"] == "f1"
    assert [f["id"] for f in info["fields"]] == ["a"]
    assert info["subforms"] == ["sub1"]
    assert ctx["rest_calls"][0]["url"] == "/api/x"
    assert ("subform", "form", "sub1") in _refs(ctx)


def test_parse_app_child_models():
    doc = b"""{"key": "app1", "name": "A", "groupsAccess": "g1",
               "extension": {"design": {"childModels": [{"key": "p1", "type": "bpmn"}]}}}"""
    ctx = _ctx()
    info = fa.parse_app(doc, ctx, "a.app")
    assert info["key"] == "app1"
    assert ("contains", "model:bpmn", "p1") in _refs(ctx)
    assert ctx["access"][0]["groups"] == ["g1"]


def test_parse_java_bean_endpoint_and_vars():
    src = """package com.x;
    import org.springframework.stereotype.Component;
    // @Component("commentedOut") must be ignored
    @Component("beanName")
    public class MyBean {
        public void go(Object execution) {
            execution.setVariable("orderId", 1);
        }
    }"""
    jc = fa.parse_java(src, "MyBean.java")
    assert jc["primary"] == "MyBean"
    assert jc["fqn"] == "com.x.MyBean"
    assert "beanName" in jc["beanNames"]
    assert "commentedOut" not in jc["beanNames"]
    assert "orderId" in jc["vars"]


def test_parse_java_controller_mapping():
    src = """package com.x;
    @RestController
    public class Ctl {
        @GetMapping("/api/things")
        public String list() { return ""; }
    }"""
    jc = fa.parse_java(src, "Ctl.java")
    assert jc["isController"]
    assert [(e["http"], e["path"]) for e in jc["endpoints"]] == [("GET", "/api/things")]


def test_match_rest():
    eps = [{"http": "GET", "path": "/api/things", "controller": "Ctl",
            "handler": "list", "file": "Ctl.java", "line": 3}]
    assert fa.match_rest("/api/things", eps)
    assert fa.match_rest("http://host:8080/api/things?x=1", eps)
    assert not fa.match_rest("/api/other", eps)
