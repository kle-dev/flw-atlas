"""C1: the frontend lives in frontend/*; the embedded copies in flowable_atlas.py must not drift."""
import os
import subprocess
import sys

from conftest import FIXTURE, REPO_ROOT

import flowable_atlas as fa


def test_embedded_frontend_is_current():
    proc = subprocess.run(
        [sys.executable, os.path.join(REPO_ROOT, "tools", "embed_frontend.py"), "--check"],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr or proc.stdout


def test_embedded_assets_match_disk():
    for name in ("explorer.html", "explorer.css", "explorer.js"):
        with open(os.path.join(REPO_ROOT, "frontend", name), encoding="utf-8") as fh:
            assert fa._EMBEDDED_FRONTEND[name] == fh.read(), f"{name} drifted — run tools/embed_frontend.py"


def test_composed_template_has_no_markers(result):
    html_out = fa.html_render(result, FIXTURE)
    assert "/*__ATLAS_CSS__*/" not in html_out
    assert "/*__ATLAS_JS__*/" not in html_out
    assert "__ATLAS_DATA__" not in html_out
    assert html_out.lstrip().startswith("<!DOCTYPE html>")
