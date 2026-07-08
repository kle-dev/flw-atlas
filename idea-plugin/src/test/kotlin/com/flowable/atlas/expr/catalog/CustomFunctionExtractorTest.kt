package com.flowable.atlas.expr.catalog

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Parity twin of `tests/test_custom_functions.py`: extract a project's
 * `flowable.externals.additionalData` custom functions from readable source and validate
 * `<ns>.member(...)` / `flw.<custom>` calls precisely instead of staying blanket-lenient.
 */
class CustomFunctionExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(rel: String, text: String): File {
        val f = File(tmp.root, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
        return f
    }

    private fun frontend(body: String, cat: CustomFunctionCatalog?) =
        ExpressionValidator.validate(body, ExpressionDialect.FRONTEND, cat)

    // ---- extraction shapes ----

    @Test fun extractsNamespaceViaImportedBinding() {
        write("fe/index.tsx",
            "import additionalData from \"./additionaldata\";\n" +
                "import components from \"./components\";\n" +
                "export default { components, additionalData };\n")
        write("fe/additionaldata/index.ts",
            "import {findCommon} from \"./a\";\nimport {sortByDate} from \"./b\";\n" +
                "export default { flowkyc: { findCommon, sortByDate } };\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)
        assertNotNull(cat)
        assertEquals(setOf("findCommon", "sortByDate"), cat!!.namespaces["flowkyc"])
        assertTrue(cat.sources.first().endsWith("index.ts"))
    }

    @Test fun extractsInlineObjectFlwMergeAndTopLevel() {
        write("src/custom.ts",
            "export default {\n" +
                "  additionalData: {\n" +
                "    acme: { doThing: () => 1, \"quoted\": function () {} },\n" +
                "    flw: { formatIban: (s) => s, roundBig: (n) => n },\n" +
                "    bareFn: () => 2,\n" +
                "  },\n" +
                "};\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertEquals(setOf("doThing", "quoted"), cat.namespaces["acme"])
        assertEquals(setOf("formatIban", "roundBig"), cat.flw)
        assertTrue("bareFn" in cat.topLevel)
    }

    @Test fun directExternalsAssignment() {
        write("ext/custom.js",
            "flowable.externals = flowable.externals || {};\n" +
                "flowable.externals.additionalData = { acme: { foo: function(){}, bar: function(){} } };\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertEquals(setOf("foo", "bar"), cat.namespaces["acme"])
    }

    @Test fun extractsFromCompiledRollupBundle() {
        // static/ext/custom.js compiled UMD: `export default { …, additionalData }` becomes
        // `var additionalData = { … }`. Member KEYS survive minification; the React <Form> prop must not.
        write("src/main/resources/static/ext/custom.js",
            "(function (global, factory) {\n" +
                "  global.flowable.externals = factory(global.flowable.React);\n" +
                "}(this, function (React) { 'use strict';\n" +
                "  function findCommon(x){ return x; }\n" +
                "  var additionalData = { flowkyc: { findCommon: findCommon, sortByDate: sortByDate },\n" +
                "                         flw: { formatIban: function(s){ return s; } }, bareFn: function(){ return 2; } };\n" +
                "  var index = { applications: [], additionalData: additionalData };\n" +
                "  React.createElement(Form, { config: c, additionalData: { currentUser: props.user } });\n" +
                "  return index;\n" +
                "}));\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertEquals(setOf("findCommon", "sortByDate"), cat.namespaces["flowkyc"])
        assertEquals(setOf("formatIban"), cat.flw)
        assertTrue("bareFn" in cat.topLevel)
        assertTrue("currentUser" !in cat.topLevel)   // React <Form> prop is data, not a registration
    }

    @Test fun nestedExternalsAdditionalDataProperty() {
        write("static/ext/custom.js",
            "window.flowable = { externals: { additionalData: {\n" +
                "  acme: { doThing: function(){}, calc: () => 1 },\n" +
                "} } };\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertEquals(setOf("doThing", "calc"), cat.namespaces["acme"])
    }

    @Test fun reactFormAdditionalDataPropAloneIsNotARegistration() {
        write("static/ext/custom.js",
            "React.createElement(Form, { config: c, additionalData: { currentUser: props.user, " +
                "count: state.count }, lang: 'en' });\n")
        assertNull(CustomFunctionExtractor.extract(tmp.root))
    }

    @Test fun spreadIsRecordedAsDiagnosticNotGuessed() {
        write("src/custom.ts",
            "const base = {};\n" +
                "export default { additionalData: { acme: { ...base, real: () => 1 } } };\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertTrue("real" in cat.namespaces.getValue("acme"))
        assertTrue(cat.diagnostics.any { it.contains("spread") })
    }

    @Test fun stringsAndCommentsDoNotFoolTheParser() {
        write("src/custom.ts",
            "export default {\n" +
                "  // additionalData: { fake: 1 } <- comment, must be ignored\n" +
                "  additionalData: {\n" +
                "    acme: { real: () => \"not { a } brace\" },\n" +
                "  },\n" +
                "};\n")
        val cat = CustomFunctionExtractor.extract(tmp.root)!!
        assertEquals(setOf("real"), cat.namespaces["acme"])
        assertTrue("fake" !in cat.topLevel)
    }

    @Test fun noCustomizationSourceReturnsNull() {
        write("src/util.ts", "export const x = 1;\n")
        assertNull(CustomFunctionExtractor.extract(tmp.root))
    }

    // ---- precise validation using an extracted catalog ----

    private val cat = CustomFunctionCatalog(
        namespaces = mapOf("flowkyc" to setOf("findCommonAttribute", "sortByDateProperty")),
        flw = setOf("formatIban"), topLevel = setOf("bareFn"), sources = emptyList(), diagnostics = emptyList(),
    )

    @Test fun knownCustomNamespaceMemberIsValid() {
        assertTrue(frontend("{{ flowkyc.findCommonAttribute(x) }}", cat).isEmpty())
    }

    @Test fun typoInCustomNamespaceMemberIsSuspectWithSuggestion() {
        val w = frontend("{{ flowkyc.findComonAttribute(x) }}", cat).single { it.severity == ExprSeverity.WARNING }
        assertEquals("findCommonAttribute", w.quickFix)
        assertEquals("flowkyc.findComonAttribute", w.subject)
    }

    @Test fun unknownCustomMemberWithoutNearMatchStaysLenient() {
        assertTrue(frontend("{{ flowkyc.bananaSplitXyz(x) }}", cat).isEmpty())
    }

    @Test fun customFlwMemberIsValidWhenExtracted() {
        assertTrue(frontend("{{ flw.formatIban(x) }}", cat).isEmpty())
    }

    @Test fun withoutCatalogCustomNamespaceCallStaysLenient() {
        // No catalog → we can't know flowkyc, so a member call on it is never flagged (parity default).
        assertTrue(frontend("{{ flowkyc.anything(x) }}", null).isEmpty())
    }
}
