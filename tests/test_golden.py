"""Golden regression tests: the full extract() result and the summary rendering
of the miniproject fixture must stay stable. Any intended change is made visible
by regenerating the goldens (ATLAS_UPDATE_GOLDEN=1 pytest) and reviewing the diff."""
import json

from conftest import FIXTURE, assert_matches_golden, normalize

import flowable_atlas as fa


def test_graph_golden(result):
    data = json.loads(json.dumps(result, ensure_ascii=False, default=list))
    content = json.dumps(normalize(data), indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    assert_matches_golden("miniproject.graph.json", content)


def test_summary_golden(result):
    assert_matches_golden("miniproject.summary.md", fa.summary_render(result, FIXTURE) + "\n")


def test_overview_golden(result):
    assert_matches_golden("miniproject.overview.md", fa.render(result, FIXTURE) + "\n")


# ---------------------------------------------------------------------------
# Intent-revealing facts — unlike the goldens these fail loudly with a reason,
# and they guard against blindly regenerating a broken golden.
# ---------------------------------------------------------------------------

def test_broken_file_produces_warning(result):
    assert any("broken.form" in w for w in result["warnings"]), result["warnings"]


def test_all_fixture_models_indexed(result):
    assert set(result["modelIndex"]) >= {
        "app:demoApp", "process:orderProcess", "case:reviewCase",
        "form:orderForm", "service:customerService", "dataObject:customerDO",
    }


def test_bean_method_resolves_to_java(result):
    hits = [r for r in result["resolvedRefs"]
            if r["from"] == "orderProcess" and r["kind"] == "bean" and r["value"] == "demoBean"]
    assert hits and "DemoBean.java" in hits[0]["target"]


def test_unresolved_refs_stand_out(result):
    unresolved = {(r["value"]) for r in result["unresolvedRefs"]}
    assert {"notifierBean", "fulfilmentProcess"} <= unresolved


def test_form_rest_call_matches_controller(result):
    calls = [rc for rc in result["restCalls"] if rc["source"] == "orderForm"]
    assert calls and any("CustomerController#customers" in m for m in calls[0]["matches"])


def test_invalid_expression_is_flagged_in_graph(result):
    node = next(n for n in result["graph"]["nodes"] if n["id"] == "expression:${vars:bogus(}")
    problems = node["data"]["problems"]
    assert any(p["severity"] == "error" for p in problems), problems


def test_liquibase_coverage_links_service(result):
    lb = result["liquibase"][0]
    assert lb["tables"] == ["cust_customer"]
    assert lb["authority"]["referencedBy"] == ["customerService"]


def test_masterdata_fields_extracted(result):
    md = next(d for d in result["dataObjects"] if d["key"] == "priorityMD")
    assert md["fields"] == ["level", "color"], "masterData `variables` map must become fields"
    assert md["keyField"] == "key" and md["subType"] == "priority"
    assert md["columns"][0]["label"] == "Level"


def test_dmn_servicetask_ref_resolves(result):
    hits = [r for r in result["resolvedRefs"] if r["from"] == "orderProcess"
            and r["kind"] == "decision" and r["value"] == "orderDecision"]
    assert hits, "serviceTask flowable:type=dmn must link the process to its decision"


def test_policy_list_shape_indexed(result):
    assert "securityPolicy:orderPolicy" in result["modelIndex"]
    pol = next(p for p in result["policies"] if p["key"] == "orderPolicy")
    perms = {p["key"]: p["roles"] for p in pol["permissions"]}
    assert perms == {"read": ["sales"], "update": ["backoffice"]}


def test_dataobject_relation_edge(result):
    assert any(e["s"] == "dataObject:customerDO" and e["t"] == "dataObject:priorityMD"
               and e["rel"] == "relates-to" for e in result["graph"]["edges"]), \
        "object-relation field mappings must become dataObject->dataObject edges"


def test_dataobject_table_denormalized(result):
    do = next(d for d in result["dataObjects"] if d["key"] == "customerDO")
    assert do["serviceTableName"] == "cust_customer"
    assert do["serviceType"] == "db"


def test_external_node_for_unresolved_refs(result):
    by_id = {n["id"]: n for n in result["graph"]["nodes"]}
    assert "external:notifierBean" in by_id, "unresolved beans must surface as external nodes"
    missing = by_id.get("external:fulfilmentProcess")
    assert missing and missing["data"].get("missingModel"), \
        "a referenced-but-undefined model key must surface as a missing-model node"


def test_receive_event_ref_resolves(result):
    hits = [r for r in result["resolvedRefs"] if r["from"] == "orderProcess"
            and r["rel"] == "receives-event" and r["value"] == "orderShipped"]
    assert hits, "receiveTask <flowable:eventType> must link the process to its event"


def test_event_correlation_from_payload(result):
    ev = next(e for e in result["events"] if e["key"] == "orderShipped")
    assert ev["correlation"] == ["orderId"]
    assert ev["payload"] == ["orderId", "carrier"]
