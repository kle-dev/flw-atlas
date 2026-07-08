import json
import os
import sys

import pytest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

FIXTURE = os.path.join(REPO_ROOT, "tests", "fixtures", "miniproject")
GOLDEN_DIR = os.path.join(REPO_ROOT, "tests", "golden")


@pytest.fixture(scope="session")
def result():
    """One extract() run over the fixture project, shared by all tests."""
    import flowable_atlas as fa
    return fa.extract(FIXTURE)


def normalize(obj):
    """Deterministic form of an extract() result: sets become sorted lists and
    every list is sorted by its canonical JSON — insertion/hash order (os.walk,
    PYTHONHASHSEED) no longer leaks into golden files."""
    if isinstance(obj, dict):
        return {k: normalize(v) for k, v in sorted(obj.items())}
    if isinstance(obj, (list, tuple, set, frozenset)):
        items = [normalize(x) for x in obj]
        return sorted(items, key=lambda x: json.dumps(x, sort_keys=True, default=str))
    return obj


def assert_matches_golden(name, content):
    """Compare `content` against tests/golden/<name>; ATLAS_UPDATE_GOLDEN=1 regenerates."""
    path = os.path.join(GOLDEN_DIR, name)
    if os.environ.get("ATLAS_UPDATE_GOLDEN"):
        os.makedirs(GOLDEN_DIR, exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
    assert os.path.exists(path), f"golden {name} missing — run: ATLAS_UPDATE_GOLDEN=1 pytest"
    with open(path, encoding="utf-8") as fh:
        expected = fh.read()
    assert content == expected, (
        f"output differs from tests/golden/{name} — if the change is intended, "
        f"regenerate with: ATLAS_UPDATE_GOLDEN=1 pytest"
    )
