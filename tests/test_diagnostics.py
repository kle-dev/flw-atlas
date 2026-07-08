"""Diagnostics channel: parse/read failures must be visible in every output format."""
import json

from conftest import FIXTURE
from test_cli import run_cli

import flowable_atlas as fa


def test_structured_diagnostics_present(result):
    diags = result["diagnostics"]
    assert any(d["kind"] == "parse" and d["path"] == "broken.form" for d in diags), diags
    for d in diags:
        assert set(d) == {"kind", "path", "message"}


def test_diagnostics_in_graph_json(tmp_path):
    run_cli(FIXTURE, "--all", "-o", str(tmp_path))
    data = json.loads((tmp_path / "miniproject.graph.json").read_text())
    assert any("broken.form" in d["path"] for d in data["diagnostics"])


def test_diagnostics_in_summary(result):
    out = fa.summary_render(result, FIXTURE)
    assert "could not be fully analyzed" in out


def test_diagnostics_in_html_payload(result):
    html_out = fa.html_render(result, FIXTURE)
    assert "broken.form" in html_out


def test_format_flags_are_mutually_exclusive():
    proc = run_cli(FIXTURE, "--json", "--html")
    assert proc.returncode == 2
    assert "not allowed with" in proc.stderr


def test_quiet_suppresses_summary_line(tmp_path):
    proc = run_cli(FIXTURE, "--summary", "-q", "-o", str(tmp_path / "s.md"))
    assert proc.returncode == 0
    assert proc.stderr.strip() == ""


def test_verbose_reports_progress_and_diagnostics(tmp_path):
    proc = run_cli(FIXTURE, "--summary", "-v", "-o", str(tmp_path / "s.md"))
    assert "discovered" in proc.stderr
    assert "broken.form" in proc.stderr


def test_status_line_counts_parse_issues(tmp_path):
    proc = run_cli(FIXTURE, "--summary", "-o", str(tmp_path / "s.md"))
    assert "1 parse issue" in proc.stderr
    assert "resolved" in proc.stderr
