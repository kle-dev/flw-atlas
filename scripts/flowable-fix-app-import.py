#!/usr/bin/env python3
"""
flowable-fix-app-import.py

Make a Flowable Design app export importable on versions affected by FLW-7994
("Cannot import multiple package models. Already found app model X. Trying to
also add app model null").

WHY THIS IS NEEDED
------------------
When an app uses a custom component, Flowable's export bundles the component's
build artifacts (build-info.json, package.json, ...) into the ROOT of the export
ZIP. On affected versions the importer misreads each root-level *.json file as a
second "app" model (with a null key) and aborts the whole import.

This script removes exactly those offending root-level *.json files and nothing
else, preserving the flat ZIP layout, and writes a new "<name>-fixed.zip".
The original ZIP is never modified.

  * Legitimate user models (*.user.json) are kept.
  * Model files (.app, .bpmn, .cmmn, .form, .dmn, .page, ...) are kept.
  * .js / .css / .custom-component-metadata are kept (the importer ignores them).

AFFECTED VERSIONS
-----------------
  Broken:  2025.1.x (all patches, incl. 2025.1.13) and <= 2025.2.04
  Fixed:   2025.2.05+ and 2026.1+   (upgrading removes the need for this script)

IMPORTANT — the custom component itself
---------------------------------------
App import NEVER installs the custom component (this is true in every version).
After importing, a form/page that uses the component renders only if the
component is already installed in the TARGET tenant. That is automatically the
case when you import into another workspace of the SAME tenant (the default).
For a different tenant / fresh environment, install the component bundle
separately via the custom-component upload/install flow.

USAGE
-----
  python3 flowable-fix-app-import.py myapp.zip                 # -> myapp-fixed.zip
  python3 flowable-fix-app-import.py myapp.zip -o cleaned.zip  # custom output name
  python3 flowable-fix-app-import.py myapp.zip --dry-run       # report only, write nothing
"""

import argparse
import json
import os
import sys
import zipfile

CC_METADATA_SUFFIX = ".custom-component-metadata"
USER_MODEL_SUFFIX = ".user.json"  # legitimate user models — never remove


def is_root_level(name: str) -> bool:
    """A ZIP entry is at the archive root when its name has no path separator."""
    return "/" not in name


def load_cc_resource_names(zf):
    """Return (set_of_cc_resource_names, metadata_entry_name_or_None).

    Reads usedCustomComponentProjects[].resources[] from the
    *.custom-component-metadata entry, which is the authoritative list of the
    bundled custom-component resource filenames.
    """
    for info in zf.infolist():
        if info.filename.endswith(CC_METADATA_SUFFIX):
            try:
                meta = json.loads(zf.read(info.filename))
            except (json.JSONDecodeError, ValueError):
                return set(), info.filename
            names = set()
            for project in meta.get("usedCustomComponentProjects", []) or []:
                for res in project.get("resources", []) or []:
                    if isinstance(res, str):
                        names.add(res)
            return names, info.filename
    return set(), None


def default_output(src):
    base, ext = os.path.splitext(src)
    return "{}-fixed{}".format(base, ext or ".zip")


def main():
    parser = argparse.ArgumentParser(
        description="Strip custom-component *.json files that break Flowable Design "
                    "app import (FLW-7994)."
    )
    parser.add_argument("zip", help="path to the exported app .zip")
    parser.add_argument("-o", "--output", help="output zip path (default: <name>-fixed.zip)")
    parser.add_argument("--dry-run", action="store_true",
                        help="only report what would be removed; write nothing")
    args = parser.parse_args()

    src = args.zip
    if not os.path.isfile(src):
        sys.exit("error: file not found: {}".format(src))
    if not zipfile.is_zipfile(src):
        sys.exit("error: not a valid zip file: {}".format(src))

    with zipfile.ZipFile(src) as zf:
        cc_names, meta_name = load_cc_resource_names(zf)

        root_entries = [i.filename for i in zf.infolist() if is_root_level(i.filename)]
        app_files = [n for n in root_entries if n.endswith(".app")]

        to_remove = [
            n for n in root_entries
            if n.endswith(".json") and not n.endswith(USER_MODEL_SUFFIX)
        ]

    # ---- Report ---------------------------------------------------------
    print("Archive: {}".format(src))
    print("Root-level entries: {}".format(len(root_entries)))
    print("App model file(s):  {}".format(", ".join(app_files) if app_files else "(none!)"))
    if meta_name:
        print("Custom-component metadata: {} ({} resource name(s) listed)".format(
            meta_name, len(cc_names)))
    else:
        print("Custom-component metadata: (none found)")

    if not root_entries:
        print("\nWARNING: no root-level entries found. This ZIP may be wrapped in a "
              "top-level folder (e.g. re-zipped in Finder). Re-export from Design or "
              "re-zip so the .app file sits at the ZIP root, then run this again.")
        sys.exit(2)

    if not to_remove:
        print("\nNo offending root-level *.json files found — this archive should "
              "import as-is. Nothing to do.")
        return

    print("\nWill remove {} root-level *.json file(s):".format(len(to_remove)))
    for name in to_remove:
        tag = "custom-component resource" if name in cc_names else "unrecognized root .json"
        print("  - {}    [{}]".format(name, tag))

    if not app_files:
        print("\nWARNING: no .app model file found at the ZIP root. Double-check this "
              "is a Design app export before importing the result.")

    if args.dry_run:
        print("\n--dry-run: no output written.")
        return

    # ---- Rewrite without the offending entries --------------------------
    out = args.output or default_output(src)
    if os.path.abspath(out) == os.path.abspath(src):
        sys.exit("error: output path must differ from the input path")

    remove_set = set(to_remove)
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            if info.filename in remove_set:
                continue
            # Passing the original ZipInfo preserves name, timestamp and the flat
            # layout; entry keeps its original compression type.
            zout.writestr(info, zin.read(info.filename))

    print("\nWrote cleaned archive: {}".format(out))
    print("Import this file into Flowable Design (original left untouched).")


if __name__ == "__main__":
    main()
