"""Custom frontend functions: extract a project's `flowable.externals.additionalData`
catalog from readable source and validate `<ns>.member(...)` / `flw.<custom>` calls precisely
instead of staying blanket-lenient (see extract_custom_functions in flowable_atlas.py)."""
import flowable_atlas as fa


def _write(tmp_path, rel, text):
    p = tmp_path / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p


# ---- extraction shapes ----------------------------------------------------

def test_extracts_namespace_via_imported_binding(tmp_path):
    # The KYC shape: entry re-exports an imported `additionalData` whose default export is the
    # namespace object. Extraction must follow the one import hop.
    _write(tmp_path, "fe/index.tsx",
           'import additionalData from "./additionaldata";\n'
           'import components from "./components";\n'
           'export default { components, additionalData };\n')
    _write(tmp_path, "fe/additionaldata/index.ts",
           'import {findCommon} from "./a";\nimport {sortByDate} from "./b";\n'
           'export default { flowkyc: { findCommon, sortByDate } };\n')
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat is not None
    assert cat["namespaces"]["flowkyc"] == {"findCommon", "sortByDate"}
    assert cat["sources"] and cat["sources"][0].endswith("additionaldata/index.ts")


def test_extracts_inline_object_flw_merge_and_top_level(tmp_path):
    _write(tmp_path, "src/custom.ts",
           'export default {\n'
           '  additionalData: {\n'
           '    acme: { doThing: () => 1, "quoted": function () {} },\n'
           '    flw: { formatIban: (s) => s, roundBig: (n) => n },\n'
           '    bareFn: () => 2,\n'
           '  },\n'
           '};\n')
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat["namespaces"]["acme"] == {"doThing", "quoted"}
    assert cat["flw"] == {"formatIban", "roundBig"}
    assert "bareFn" in cat["top_level"]


def test_direct_externals_assignment(tmp_path):
    # `externals.additionalData = { ... }` (the dot guards against JSX `additionalData={...}` props).
    _write(tmp_path, "ext/custom.js",
           'window.flowable = window.flowable || {};\n'
           'flowable.externals = flowable.externals || {};\n'
           'flowable.externals.additionalData = { acme: { foo: function(){}, bar: function(){} } };\n')
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat["namespaces"]["acme"] == {"foo", "bar"}


def test_extracts_from_compiled_rollup_bundle(tmp_path):
    # static/ext/custom.js is usually a compiled UMD bundle: `export default { …, additionalData }`
    # becomes `var additionalData = { … }`, referenced by `var index = { …, additionalData }`.
    # The member KEYS survive minification even though the function refs are renamed.
    _write(tmp_path, "src/main/resources/static/ext/custom.js",
           "(function (global, factory) {\n"
           "  global.flowable.externals = factory(global.flowable.React);\n"
           "}(this, function (React) { 'use strict';\n"
           "  function findCommon(x){ return x; }\n"
           "  function formatIban(s){ return s; }\n"
           "  var additionalData = { flowkyc: { findCommon: findCommon, sortByDate: sortByDate },\n"
           "                         flw: { formatIban: formatIban }, bareFn: function(){ return 2; } };\n"
           "  var index = { applications: [], additionalData: additionalData };\n"
           "  React.createElement(Form, { config: form70, additionalData: { currentUser: props.user } });\n"
           "  return index;\n"
           "}));\n")
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat is not None
    assert cat["namespaces"]["flowkyc"] == {"findCommon", "sortByDate"}
    assert cat["flw"] == {"formatIban"}
    assert "bareFn" in cat["top_level"]
    # the React <Form additionalData={{…}}> prop is DATA, not a registration → not picked up
    assert "currentUser" not in cat["top_level"]


def test_nested_externals_additionaldata_property(tmp_path):
    # Hand-written global config: `flowable.externals = { additionalData: { … } }` (a property, not
    # a `.additionalData =` assignment) — extracted because a namespace member gives it away.
    _write(tmp_path, "static/ext/custom.js",
           "window.flowable = { externals: { additionalData: {\n"
           "  acme: { doThing: function(){}, calc: () => 1 },\n"
           "} } };\n")
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat is not None and cat["namespaces"]["acme"] == {"doThing", "calc"}


def test_react_form_additionaldata_prop_alone_is_not_a_registration(tmp_path):
    # A file whose only `additionalData` is a React <Form> prop (values are data/identifiers) must
    # NOT be mistaken for a custom-function registration.
    _write(tmp_path, "static/ext/custom.js",
           "React.createElement(Form, { config: c, additionalData: { currentUser: props.user, "
           "count: state.count }, lang: 'en' });\n")
    assert fa.extract_custom_functions(str(tmp_path)) is None


def test_spread_is_recorded_as_diagnostic_not_guessed(tmp_path):
    _write(tmp_path, "src/custom.ts",
           'const base = {};\n'
           'export default { additionalData: { acme: { ...base, real: () => 1 } } };\n')
    cat = fa.extract_custom_functions(str(tmp_path))
    assert "real" in cat["namespaces"]["acme"]
    assert any("spread" in d for d in cat["diagnostics"])


def test_no_customization_source_returns_none(tmp_path):
    _write(tmp_path, "src/util.ts", "export const x = 1;\n")
    assert fa.extract_custom_functions(str(tmp_path)) is None


def test_strings_and_comments_do_not_fool_the_parser(tmp_path):
    _write(tmp_path, "src/custom.ts",
           'export default {\n'
           '  // additionalData: { fake: 1 }  <- a comment, must be ignored\n'
           '  additionalData: {\n'
           '    acme: { real: () => "not { a } brace" },\n'
           '  },\n'
           '};\n')
    cat = fa.extract_custom_functions(str(tmp_path))
    assert cat["namespaces"]["acme"] == {"real"}
    assert "fake" not in cat.get("top_level", set())


# ---- precise validation using an extracted catalog ------------------------

_CAT = {"namespaces": {"flowkyc": {"findCommonAttribute", "sortByDateProperty"}},
        "flw": {"formatIban"}, "top_level": {"bareFn"}, "sources": [], "diagnostics": []}


def test_known_custom_namespace_member_is_valid():
    assert fa.validate_expression("{{ flowkyc.findCommonAttribute(x) }}", fa.FRONTEND, _CAT) == []


def test_typo_in_custom_namespace_member_is_suspect_with_suggestion():
    w = [p for p in fa.validate_expression("{{ flowkyc.findComonAttribute(x) }}", fa.FRONTEND, _CAT)
         if p["severity"] == "warning"]
    assert len(w) == 1
    assert w[0]["quickFix"] == "findCommonAttribute"
    assert w[0]["subject"] == "flowkyc.findComonAttribute"


def test_unknown_custom_member_without_near_match_stays_lenient():
    # No near-match → could be a dynamically-added/spread member we didn't see → don't flag.
    assert fa.validate_expression("{{ flowkyc.bananaSplitXyz(x) }}", fa.FRONTEND, _CAT) == []


def test_custom_flw_member_is_valid_when_extracted():
    assert fa.validate_expression("{{ flw.formatIban(x) }}", fa.FRONTEND, _CAT) == []


def test_custom_namespace_member_findings_are_allowlistable():
    p = {"severity": "warning", "kind": "unknown-function", "subject": "flowkyc.findComonAttribute"}
    assert fa.expr_problem_allowlisted(p, {"flowkyc"})
    assert fa.expr_problem_allowlisted(p, {"flowkyc.findComonAttribute"})


# ---- end-to-end wiring through extract() ----------------------------------

def test_extract_auto_discovers_and_validates(tmp_path):
    _write(tmp_path, "fe/index.tsx",
           'import additionalData from "./additionaldata";\n'
           'export default { additionalData };\n')
    _write(tmp_path, "fe/additionaldata/index.ts",
           'export default { flowkyc: { findCommonAttribute: () => 1 } };\n')
    # A form that calls a valid custom fn and one with a typo.
    _write(tmp_path, "models/ok.form",
           '{"metadata":{"key":"okForm","modelType":"form"},'
           '"rows":[[{"id":"a","type":"text","value":"{{ flowkyc.findCommonAttribute(x) }}"}]]}')
    _write(tmp_path, "models/typo.form",
           '{"metadata":{"key":"typoForm","modelType":"form"},'
           '"rows":[[{"id":"a","type":"text","value":"{{ flowkyc.findComonAttribute(x) }}"}]]}')
    r = fa.extract(str(tmp_path))
    cf = r["customFunctions"]
    assert cf and cf["namespaces"]["flowkyc"] == ["findCommonAttribute"]

    nodes = {n["id"]: n for n in r["graph"]["nodes"]}
    ok = nodes.get("binding:{{ flowkyc.findCommonAttribute(x) }}")
    typo = nodes.get("binding:{{ flowkyc.findComonAttribute(x) }}")
    assert ok is not None and "problems" not in ok["data"]
    assert typo is not None and any("did you mean" in p["message"] for p in typo["data"]["problems"])


def test_custom_functions_become_cross_referenced_graph_nodes(tmp_path):
    # (a) Custom functions are first-class nodes; each links to the forms that call it, and each form
    # lists the custom functions it uses (bidirectional).
    _write(tmp_path, "fe/index.tsx",
           'import additionalData from "./additionaldata";\nexport default { additionalData };\n')
    _write(tmp_path, "fe/additionaldata/index.ts",
           'export default { flowkyc: { findCommonAttribute: () => 1 }, '
           'flw: { formatIban: (s) => s }, greet: () => "hi" };\n')
    _write(tmp_path, "models/reg.form",
           '{"metadata":{"key":"regForm","modelType":"form"},'
           '"rows":[[{"id":"a","type":"text","value":"{{ flowkyc.findCommonAttribute(customer) }}"},'
           '{"id":"b","type":"text","value":"{{ flw.formatIban(iban) }}"}]]}')
    r = fa.extract(str(tmp_path))
    nodes = {n["id"]: n for n in r["graph"]["nodes"]}

    # a node per callable, including the unused top-level `greet`
    fc = nodes.get("customFunction:flowkyc.findCommonAttribute")
    assert fc is not None and fc["data"]["kind"] == "namespace" and fc["data"]["namespace"] == "flowkyc"
    assert "form:regForm" in fc["data"]["usedBy"]                 # forward: fn -> form
    assert nodes.get("customFunction:flw.formatIban")["data"]["usedBy"] == ["form:regForm"]
    greet = nodes.get("customFunction:greet")
    assert greet is not None and greet["data"]["usedBy"] == []    # listed even though unused

    # reverse: the form lists the custom functions it uses
    used = nodes["form:regForm"]["data"].get("_uses", {}).get("customFunction", [])
    assert "customFunction:flowkyc.findCommonAttribute" in used
    assert "customFunction:flw.formatIban" in used
    assert "customFunction:greet" not in used                    # not called by this form


def test_no_custom_functions_flag_disables_discovery(tmp_path):
    _write(tmp_path, "fe/index.tsx",
           'import additionalData from "./additionaldata";\nexport default { additionalData };\n')
    _write(tmp_path, "fe/additionaldata/index.ts",
           'export default { flowkyc: { findCommonAttribute: () => 1 } };\n')
    r = fa.extract(str(tmp_path), discover_custom=False)
    assert r["customFunctions"] is None


def test_explorer_embeds_and_surfaces_custom_functions(tmp_path):
    # The HTML explorer must carry the catalog in its data island and the badge/panel JS, so the
    # custom functions are visible in the webpage (not just in the JSON).
    _write(tmp_path, "fe/index.tsx",
           'import additionalData from "./additionaldata";\nexport default { additionalData };\n')
    _write(tmp_path, "fe/additionaldata/index.ts",
           'export default { flowkyc: { findCommonAttribute: () => 1, sortByDateProperty: () => 2 } };\n')
    r = fa.extract(str(tmp_path))
    html = fa.html_render(r, str(tmp_path))
    assert '"customFunctions"' in html            # payload in the data island
    assert "findCommonAttribute" in html          # the extracted member names
    assert "renderCustomBadge" in html            # the badge JS is wired in
    assert "toggleCustomPanel" in html
