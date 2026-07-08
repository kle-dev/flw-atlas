#!/usr/bin/env python3
"""Refresh the embedded frontend assets inside flowable_atlas.py.

The explorer frontend is developed as real files in frontend/ (editable, lintable,
syntax-highlighted). flowable_atlas.py must stay a single copy-anywhere file — the
IDEA plugin bundles exactly that one file — so this tool re-embeds the assets into
the marked block:

    python3 tools/embed_frontend.py            # rewrite the block in place
    python3 tools/embed_frontend.py --check    # exit 1 when the block is stale (CI/pytest)

repr() is used for the embedded strings: unlike a raw triple-quoted literal it is
robust against any quotes/backslashes/`\"\"\"` the CSS/JS may contain.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "flowable_atlas.py")
FRONTEND = os.path.join(ROOT, "frontend")
ASSETS = ("explorer.html", "explorer.css", "explorer.js")
BEGIN = "# --- BEGIN GENERATED FRONTEND (tools/embed_frontend.py) ---"
END = "# --- END GENERATED FRONTEND ---"


def build_block():
    lines = [BEGIN, "_EMBEDDED_FRONTEND = {"]
    for name in ASSETS:
        with open(os.path.join(FRONTEND, name), encoding="utf-8") as fh:
            lines.append("    %r: %r," % (name, fh.read()))
    lines.append("}")
    lines.append(END)
    return "\n".join(lines)


def main(argv):
    check = "--check" in argv
    with open(SCRIPT, encoding="utf-8") as fh:
        src = fh.read()
    try:
        start = src.index(BEGIN)
        end = src.index(END) + len(END)
    except ValueError:
        print("error: generated-frontend markers not found in flowable_atlas.py", file=sys.stderr)
        return 2
    updated = src[:start] + build_block() + src[end:]
    compile(updated, SCRIPT, "exec")   # never write (or accept) a syntactically broken script
    if updated == src:
        print("embedded frontend is up to date")
        return 0
    if check:
        print("error: embedded frontend is stale — run: python3 tools/embed_frontend.py", file=sys.stderr)
        return 1
    with open(SCRIPT, "w", encoding="utf-8") as fh:
        fh.write(updated)
    print("embedded %s into flowable_atlas.py" % ", ".join(ASSETS))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
