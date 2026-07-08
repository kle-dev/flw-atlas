"""Parity tests for the Python expression validator ported into flowable_atlas.py.

Mirrors idea-plugin/src/test/kotlin/com/flowable/atlas/expr/ExpressionValidatorTest.kt
1:1 so the webpage generator and the IntelliJ plugin agree on what "invalid" means,
plus a few cases for the harvester truncation guard (Python-only concern).

Run:  python3 -m unittest test_flowable_atlas_expr -v
"""

import unittest

import flowable_atlas as fa


def backend(body):
    return fa.validate_expression(body, fa.BACKEND)


def frontend(body):
    return fa.validate_expression(body, fa.FRONTEND)


def warnings(problems):
    return [p for p in problems if p["severity"] == "warning"]


def has(problems, severity=None, contains=None):
    return any((severity is None or p["severity"] == severity) and
               (contains is None or contains in p["message"]) for p in problems)


class ExpressionValidatorTest(unittest.TestCase):
    # --- valid expressions ---
    def test_valid_backend(self):
        self.assertEqual(backend("date:now()"), [])
        self.assertEqual(backend("vars:get('order')"), [])
        self.assertEqual(backend("execution.getVariable('a')"), [])
        self.assertEqual(backend("a == b ? c : d"), [])  # ternary colon is not a namespace call

    def test_valid_frontend(self):
        self.assertEqual(frontend("flw.sum(items)"), [])
        self.assertEqual(frontend("total |> flw.round(2)"), [])
        self.assertEqual(frontend("flw.remove.nulls(list)"), [])

    def test_work_injected_flw_members_are_valid(self):
        # Work/platform-injected flw.* members (useGlobalResolver + Form.tsx), not base @flowable/forms.
        self.assertEqual(frontend("flw.getUser('userId').displayName"), [])
        self.assertEqual(frontend("flw.getMasterDataInstance('id').name"), [])
        self.assertEqual(frontend("flw.getMasterDataInstanceByKey('k', 'd').name"), [])
        self.assertEqual(frontend("flw.getDataObjectInstance('d', 'o', 'k', 'v').variableOne"), [])
        self.assertEqual(frontend("flw.validate('componentId')"), [])
        self.assertEqual(frontend("flw.stringify(payload)"), [])

    def test_empty_interpolation_is_clean(self):
        # An empty `{{}}` / `${}` is a runtime no-op, not a syntax error.
        self.assertEqual(frontend("{{}}"), [])
        self.assertEqual(backend("${}"), [])
        self.assertEqual(frontend("{{  }}"), [])

    # --- structural errors ---
    def test_unbalanced_paren_is_error(self):
        self.assertTrue(has(backend("date:now("), "error", "Unclosed"))

    def test_unterminated_string_is_error(self):
        self.assertTrue(has(backend("vars:get('order)"), "error", "Unterminated"))

    def test_empty_argument_is_error(self):
        self.assertTrue(has(backend("date:now(,)"), contains="Empty argument"))

    def test_trailing_operator_is_error(self):
        self.assertTrue(has(backend("a +"), contains="ends unexpectedly"))

    def test_operator_before_closing_paren_is_flagged(self):
        self.assertTrue(has(frontend("{{test && (test || )}}"), "error", "missing its right operand"))

    def test_binary_operator_without_left_operand_is_flagged(self):
        self.assertTrue(has(backend("(&& b)"), contains="missing its left operand"))

    def test_dot_without_name_is_flagged(self):
        self.assertTrue(has(backend("execution."), contains="Expected a name after"))

    def test_juxtaposed_operands_are_an_error(self):
        self.assertTrue(has(backend("a b c"), "error"))

    def test_backend_array_literal_is_an_error(self):
        self.assertTrue(has(backend("[1, 2, 3]"), "error"))
        self.assertEqual(backend("items[0]"), [])

    # --- semantic warnings (with suggestions) ---
    def test_unknown_namespace_warns_with_suggestion(self):
        w = warnings(backend("daate:now()"))
        self.assertEqual(len(w), 1)
        self.assertIn("Unknown function namespace", w[0]["message"])
        self.assertEqual(w[0]["quickFix"], "date")

    def test_unknown_backend_function_warns_with_suggestion(self):
        w = warnings(backend("date:noww()"))
        self.assertEqual(len(w), 1)
        self.assertIn("Unknown function", w[0]["message"])
        self.assertEqual(w[0]["quickFix"], "now")

    def test_unknown_frontend_member_warns_with_suggestion(self):
        w = warnings(frontend("flw.sim(items)"))
        self.assertEqual(len(w), 1)
        self.assertIn("flw.sim", w[0]["message"])
        self.assertEqual(w[0]["quickFix"], "sum")

    def test_top_level_custom_function_is_not_flagged(self):
        # Custom JS functions provided via `flowable.externals.additionalData` are spread into the
        # frontend expression scope as top-level identifiers — they resolve at runtime but are
        # invisible to us, so a bare call (or a call through a custom object) must never be flagged.
        self.assertEqual(frontend("myCustomFn(order)"), [])
        self.assertEqual(frontend("myLib.doThing(order)"), [])
        self.assertEqual(frontend("{{ computeRiskScore($payload) }}"), [])

    def test_unknown_flw_member_without_near_match_is_lenient(self):
        # `additionalData.flw` can carry custom functions too. A `flw.<x>` with no near-match to any
        # known member is treated as one of those (not flagged); only a plausible typo is surfaced.
        self.assertEqual(frontend("flw.computeRiskScore(order)"), [])
        self.assertTrue(has(frontend("flw.sim(items)"), "warning"))  # typo → still suspect

    def test_pipe_in_backend_is_warned(self):
        self.assertTrue(has(backend("a |> b"), contains="frontend-only pipe"))

    def test_backend_function_syntax_in_frontend_is_warned(self):
        self.assertTrue(has(frontend("date:now()"), contains="backend function syntax"))

    def test_known_function_with_pipe_is_clean_in_frontend(self):
        self.assertFalse(has(frontend("a |> flw.sum(b)"), "error"))

    # --- wrapper tolerance & offsets ---
    def test_body_wrapped_in_delimiters_is_tolerated(self):
        self.assertEqual(frontend("{{test && test}}"), [])
        self.assertEqual(frontend("{{ flw.sum(items) }}"), [])
        self.assertEqual(backend("${ date:now() }"), [])
        self.assertEqual(backend("#{execution.id}"), [])

    def test_problem_offsets_point_into_original_text_when_wrapped(self):
        text = "${date:noww()}"
        w = warnings(backend(text))
        self.assertEqual(len(w), 1)
        self.assertEqual(text[w[0]["start"]:w[0]["end"]], "noww")

    # --- well-formed / non-flagging ---
    def test_unary_operators_do_not_falsely_flag(self):
        self.assertEqual(backend("(-1)"), [])
        self.assertEqual(backend("a + -b"), [])
        self.assertEqual(frontend("!done"), [])
        self.assertEqual(frontend("a && !done"), [])

    def test_well_formed_nested_expression_is_valid(self):
        self.assertEqual(frontend("test && (test2 || other)"), [])
        self.assertEqual(backend("(a == b) && (c != d)"), [])

    def test_frontend_array_literal_and_higher_order_are_valid(self):
        self.assertEqual(frontend("[1, 2, 3]"), [])
        self.assertFalse(has(frontend("flw.array.filter(items, item => item > 2)"), "error"))

    def test_backend_word_operators_are_valid(self):
        self.assertEqual(backend("empty items"), [])
        self.assertEqual(backend("a lt b and not done"), [])
        self.assertEqual(backend("a div b mod 2"), [])


class HarvestTruncationGuardTest(unittest.TestCase):
    def test_truncated_nested_brace_is_skipped(self):
        # EXPR_RE stops at the first '}', so this is what the harvester actually captures.
        self.assertIsNone(fa.validate_harvested_expr("${f({'a':1}", fa.BACKEND))

    def test_valid_harvested_expression_has_no_problems(self):
        self.assertEqual(fa.validate_harvested_expr("${execution.id}", fa.BACKEND), [])
        self.assertEqual(fa.validate_harvested_expr("{{flw.sum(items)}}", fa.FRONTEND), [])

    def test_invalid_harvested_expression_reports_problems(self):
        # Realistic harvested forms always end with the closing delimiter (EXPR_RE / MUSTACHE_RE
        # require a closing '}'); a broken authored expression still carries one.
        self.assertTrue(fa.validate_harvested_expr("${date:now(}", fa.BACKEND))   # unclosed '('
        self.assertTrue(fa.validate_harvested_expr("{{flw.sim(x)}}", fa.FRONTEND))  # unknown flw member

    def test_xml_entities_are_unescaped_before_validation(self):
        # BPMN/CMMN attribute & element text carries XML entities; the engine parses the unescaped
        # form, so these are valid — not "Unexpected character '&'".
        self.assertEqual(fa.validate_harvested_expr("${svc.update(a, &quot;DONE&quot;)}", fa.BACKEND), [])
        self.assertEqual(fa.validate_harvested_expr("${a &amp;&amp; b}", fa.BACKEND), [])
        self.assertEqual(fa.validate_harvested_expr("${a &lt;= b}", fa.BACKEND), [])

    def test_json_string_escapes_are_decoded(self):
        # A binding harvested from a .form JSON carries \" for "; the engine sees the decoded form.
        self.assertEqual(fa.validate_harvested_expr('{{$temp.allTasks[\\"Pre-KYC\\"]}}', fa.FRONTEND), [])
        self.assertEqual(fa.validate_harvested_expr('{{stage.split(\\".\\")[0]}}', fa.FRONTEND), [])

    def test_problem_carries_snippet(self):
        p = fa.validate_harvested_expr("${date:noww()}", fa.BACKEND)
        self.assertEqual(p[0]["snippet"], "noww")


if __name__ == "__main__":
    unittest.main()
