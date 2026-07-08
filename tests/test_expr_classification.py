"""A3: catalog findings are 'suspect' (warning), never 'invalid' (error); an
allowlist silences findings for project-provided functions."""
from conftest import FIXTURE
from test_cli import run_cli

import flowable_atlas as fa


def _bogus_node(result):
    return next(n for n in result["graph"]["nodes"] if n["id"] == "expression:${vars:bogus(}")


def test_catalog_finding_is_warning_with_kind_and_subject(result):
    problems = _bogus_node(result)["data"]["problems"]
    catalog = [p for p in problems if p.get("kind")]
    assert catalog == [dict(catalog[0])]  # exactly one
    assert catalog[0]["kind"] == "unknown-function"
    assert catalog[0]["subject"] == "vars:bogus"
    assert catalog[0]["severity"] == "warning"


def test_structural_error_is_never_suppressed():
    p = {"severity": "error", "kind": "unknown-function", "subject": "vars:bogus"}
    assert not fa.expr_problem_allowlisted(p, {"vars", "vars:bogus"})


def test_allowlist_covers_namespace_and_exact_function():
    warn = {"severity": "warning", "kind": "unknown-function", "subject": "vars:bogus"}
    assert fa.expr_problem_allowlisted(warn, {"vars"})
    assert fa.expr_problem_allowlisted(warn, {"vars:bogus"})
    assert not fa.expr_problem_allowlisted(warn, {"other"})
    flw = {"severity": "warning", "kind": "unknown-function", "subject": "flw.custom"}
    assert fa.expr_problem_allowlisted(flw, {"flw.custom"})


def test_allowlist_filters_graph_problems():
    r = fa.extract(FIXTURE, expr_allowlist={"vars"})
    problems = _bogus_node(r)["data"]["problems"]
    assert all(p["severity"] == "error" for p in problems), problems


def test_cli_expr_allowlist_flag(tmp_path):
    out = tmp_path / "e.html"
    proc = run_cli(FIXTURE, "--html", "-o", str(out), "--expr-allowlist", "vars")
    assert proc.returncode == 0, proc.stderr
    assert "Unknown function" not in out.read_text()


def test_explorer_separates_invalid_and_suspect(result):
    html_out = fa.html_render(result, FIXTURE)
    assert "Invalid — syntax" in html_out
    assert "Suspect — review" in html_out
