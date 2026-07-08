"""CLI contract tests — these encode the interface the IDEA plugin and the
./atlas launcher depend on. If one of these fails, the change breaks consumers."""
import json
import os
import subprocess
import sys

from conftest import FIXTURE, REPO_ROOT

SCRIPT = os.path.join(REPO_ROOT, "flowable_atlas.py")


def run_cli(*args, cwd=None):
    return subprocess.run([sys.executable, SCRIPT, *args],
                          capture_output=True, text=True, cwd=cwd or REPO_ROOT)


def test_all_writes_exactly_the_five_artifacts(tmp_path):
    """The plugin locates --all output purely by these artifact names."""
    proc = run_cli(FIXTURE, "--all", "-o", str(tmp_path))
    assert proc.returncode == 0, proc.stderr
    expected = {f"miniproject.{suffix}" for suffix in
                ("summary.md", "overview.md", "graph.json", "explorer.html", "CLAUDE.md")}
    assert {p.name for p in tmp_path.iterdir()} == expected
    for p in tmp_path.iterdir():
        assert p.stat().st_size > 0, f"{p.name} is empty"


def test_all_graph_json_is_valid_json(tmp_path):
    run_cli(FIXTURE, "--all", "-o", str(tmp_path))
    data = json.loads((tmp_path / "miniproject.graph.json").read_text())
    assert data["graph"]["nodes"] and data["graph"]["edges"]


def test_html_single_mode(tmp_path):
    """`--html -o <file>` is the plugin's explorer-only invocation."""
    out = tmp_path / "explorer.html"
    proc = run_cli(FIXTURE, "--html", "-o", str(out))
    assert proc.returncode == 0, proc.stderr
    html = out.read_text()
    assert html.lstrip().startswith("<!DOCTYPE html>")
    assert "__ATLAS_DATA__" not in html, "data placeholder was not substituted"
    assert "orderProcess" in html


def test_summary_stdout_keeps_stdout_clean(tmp_path):
    proc = run_cli(FIXTURE, "--summary", "--stdout")
    assert proc.returncode == 0
    assert proc.stdout.strip(), "summary must land on stdout"


def test_missing_path_exits_2():
    proc = run_cli("/nonexistent/nowhere")
    assert proc.returncode == 2
    assert "path not found" in proc.stderr


def test_expr_public_api_stays_importable():
    """The Kotlin-parity test (and the IDEA plugin docs) rely on these names."""
    import flowable_atlas as fa
    assert callable(fa.validate_expression)
    assert fa.BACKEND != fa.FRONTEND
