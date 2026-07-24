package com.flowable.atlas.generate.liquibase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the filename engine: token derivation ({servicePrefix}/{serviceNo}), rendering, the
 * optional regex rename, sanitization, and that the default `{key}` pattern reproduces the historical
 * `<sanitized-key>.changelog.xml`.
 */
class LiquibaseFileNamePatternTest {

    private fun tokens(key: String, name: String, service: String?, table: String? = null) =
        LiquibaseFileNamePattern.deriveTokens(key, name, service, table)

    // ---- token derivation -------------------------------------------------------------------

    @Test fun serviceNo_strips_letters_and_leading_zeros() {
        assertEquals("9", tokens("do", "n", "DEMO-S009").serviceNo)
        assertEquals("10", tokens("do", "n", "DEMO-S010").serviceNo)
        assertEquals("0", tokens("do", "n", "DEMO-S000").serviceNo)
        assertEquals("", tokens("do", "n", "DEMO-SERVICE").serviceNo)
        assertEquals("", tokens("do", "n", null).serviceNo)
    }

    @Test fun servicePrefix_drops_the_trailing_id_segment() {
        assertEquals("DEMO", tokens("do", "n", "DEMO-S009").servicePrefix)
        assertEquals("DEMO", tokens("do", "n", "DEMO_S009").servicePrefix)
        assertEquals("", tokens("do", "n", "S009").servicePrefix)
        assertEquals("", tokens("do", "n", null).servicePrefix)
    }

    // ---- render + full pipeline -------------------------------------------------------------

    @Test fun the_user_example_renders_without_regex() {
        val t = tokens("DEMO-DO-009", "pod-member", "DEMO-S009", "POD_MEMBER")
        assertEquals(
            "DEMO-L9-pod-member.changelog.xml",
            LiquibaseFileNamePattern.fileName("{servicePrefix}-L{serviceNo}-{name}", t),
        )
    }

    @Test fun the_user_example_also_works_via_a_regex_rename() {
        val t = tokens("DEMO-DO-009", "pod-member", "DEMO-S009")
        assertEquals(
            "DEMO-L9-pod-member.changelog.xml",
            LiquibaseFileNamePattern.fileName("{service}-{name}", t, renameFind = "S0*(\\d+)", renameReplace = "L$1"),
        )
    }

    @Test fun unknown_tokens_render_empty() {
        val t = tokens("k", "n", null)
        assertEquals("--n.changelog.xml", LiquibaseFileNamePattern.render("{service}-{missing}-{name}", t.asMap()) + ".changelog.xml")
    }

    @Test fun default_pattern_reproduces_the_legacy_sanitized_key() {
        val t = tokens("DEMO DO/009", "n", null)
        // legacy behavior was key.replace([^A-Za-z0-9._-], "-") + ".changelog.xml"
        assertEquals("DEMO-DO-009.changelog.xml", LiquibaseFileNamePattern.fileName("{key}", t))
        assertEquals("DEMO-DO-009.changelog.xml", LiquibaseFileNamePattern.fileName("", t))
    }

    @Test fun slug_lowercases_and_dashes() {
        assertEquals("pod-member", LiquibaseFileNamePattern.slug("Pod Member"))
        assertEquals("pod-member", LiquibaseFileNamePattern.slug("  pod   member!  "))
        assertEquals("", LiquibaseFileNamePattern.slug("   "))
    }

    @Test fun blank_regex_find_is_a_no_op() {
        assertEquals("abc", LiquibaseFileNamePattern.applyRename("abc", "", "X"))
    }
}
