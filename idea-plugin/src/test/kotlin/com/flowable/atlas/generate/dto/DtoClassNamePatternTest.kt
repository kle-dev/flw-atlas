package com.flowable.atlas.generate.dto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the DTO class-name engine: token derivation (PascalCase, the never-doubled suffix),
 * rendering, the optional regex rename, reduction to identifier characters, and that the default
 * `{name}{suffix}` reproduces the historical [DataObjectDtoPlanner.defaultClassName].
 * DEMO-* placeholder keys — this repo is public.
 */
class DtoClassNamePatternTest {

    private fun tokens(key: String, name: String?, app: String? = null, suffix: String = "Dto") =
        DtoClassNamePattern.deriveTokens(key, name, app, suffix)

    // ---- token derivation -------------------------------------------------------------------

    @Test fun tokens_are_pascal_case_and_identifier_safe() {
        val t = tokens("DEMO-D010", "Pod Member", "demo-app")
        assertEquals("PodMember", t.name)
        assertEquals("DEMOD010", t.key)
        assertEquals("DemoApp", t.app)
        assertEquals("Dto", t.suffix)
    }

    // ---- {shortName}: the model key most model names are prefixed with ----------------------

    @Test fun shortName_drops_the_key_the_model_name_starts_with() {
        assertEquals("PodMember", tokens("DEMO-D009", "DEMO-D009 Pod Member").shortName)
        assertEquals("PodMember", tokens("DEMO-D009", "DEMO-D009-Pod-Member").shortName)
    }

    @Test fun shortName_also_drops_a_key_written_differently_from_the_key_model() {
        // The name abbreviates the key: DEMOD9… against key DEMOD009 — rule 1 misses, rule 2 catches it.
        assertEquals("DocumentType", tokens("DEMO-D009", "DEMO-D9 Document Type").shortName)
        assertEquals("Customer", tokens("DEMO-D010", "D10Customer").shortName)
    }

    @Test fun shortName_leaves_a_name_without_a_key_prefix_alone() {
        assertEquals("PodMember", tokens("DEMO-D009", "Pod Member").shortName)
        // No digit in the leading run → an acronym, not a key.
        assertEquals("IBANCheck", tokens("DEMO-D009", "IBAN Check").shortName)
    }

    @Test fun shortName_never_renders_empty() {
        // An unnamed model: the name *is* the key, and something must still name the class.
        assertEquals("DEMOD009", tokens("DEMO-D009", null).shortName)
        assertEquals("DEMOD009", tokens("DEMO-D009", "DEMO-D009").shortName)
    }

    @Test fun shortName_composes_with_the_suffix() {
        assertEquals(
            "PodMemberDto",
            DtoClassNamePattern.className("{shortName}{suffix}", tokens("DEMO-D009", "DEMO-D009 Pod Member")),
        )
    }

    @Test fun name_falls_back_to_the_key_and_app_may_be_empty() {
        val t = tokens("DEMO-D010", null)
        assertEquals("DEMOD010", t.name)
        assertEquals("", t.app)
        assertEquals("", tokens("DEMO-D010", "Customer", "   ").app)
    }

    @Test fun the_suffix_token_is_empty_when_the_name_already_ends_in_it() {
        assertEquals("", tokens("DEMO-D010", "Customer DTO").suffix)
        assertEquals("", tokens("DEMO-D010", "Customer Dto").suffix)
        assertEquals("", tokens("DEMO-D010", "Customer", suffix = "  ").suffix)
        assertEquals("Bean", tokens("DEMO-D010", "Customer", suffix = "Bean").suffix)
    }

    // ---- render + full pipeline -------------------------------------------------------------

    @Test fun default_pattern_reproduces_the_pre_pattern_class_name() {
        for (name in listOf("Customer", "Shopping List", "Customer DTO", null)) {
            assertEquals(
                DataObjectDtoPlanner.defaultClassName(name, "DEMO-D010", "Dto"),
                DtoClassNamePattern.className(DtoClassNamePattern.DEFAULT_PATTERN, tokens("DEMO-D010", name)),
            )
        }
        // A blank pattern is the default pattern.
        assertEquals("CustomerDto", DtoClassNamePattern.className("", tokens("DEMO-D010", "Customer")))
    }

    @Test fun a_pattern_composes_tokens_and_literal_text() {
        val t = tokens("DEMO-D010", "Customer", "demo-app")
        assertEquals("DemoAppCustomerDto", DtoClassNamePattern.className("{app}{name}{suffix}", t))
        assertEquals("AcmeCustomerDto", DtoClassNamePattern.className("Acme{name}Dto", t))
        assertEquals("DEMOD010Dto", DtoClassNamePattern.className("{key}{suffix}", t))
    }

    @Test fun unknown_tokens_render_empty() {
        assertEquals("Customer", DtoClassNamePattern.className("{name}{model}", tokens("DEMO-D010", "Customer", suffix = "")))
    }

    @Test fun illegal_characters_are_dropped_from_the_rendered_name() {
        val t = tokens("DEMO-D010", "Customer")
        assertEquals("MyCustomerDto", DtoClassNamePattern.className("My-{name} {suffix}!", t))
    }

    @Test fun a_regex_rename_is_applied_to_the_rendered_name() {
        val t = tokens("DEMO-D010", "DEMO Customer")
        assertEquals(
            "DemoCustomerDto",
            DtoClassNamePattern.className("{name}{suffix}", t, renameFind = "^DEMO(\\w+)", renameReplace = "Demo$1"),
        )
    }

    @Test fun a_broken_regex_leaves_the_rendered_name_alone() {
        val t = tokens("DEMO-D010", "Customer")
        // An unclosed group would throw; the intention must still propose a name.
        assertEquals("CustomerDto", DtoClassNamePattern.className("{name}{suffix}", t, renameFind = "(", renameReplace = "X"))
    }

    @Test fun a_pattern_of_literals_only_can_render_empty() {
        // Nothing usable left → the dialog reports the row instead of writing a nameless file.
        assertEquals("", DtoClassNamePattern.className("---", tokens("DEMO-D010", "Customer")))
    }
}
