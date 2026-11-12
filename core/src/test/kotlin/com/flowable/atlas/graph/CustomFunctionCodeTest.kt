package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A custom function's page shows the function: its node carries the code, the file and the line. */
class CustomFunctionCodeTest {

    /**
     * `unusedFns` reports a function nothing names. A `$`-prefixed name is named like any other, a function
     * handed on by reference is used, and a literal value beside the functions is no function at all.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aDollarNameAFunctionReferenceAndALiteralValueAreNoUnusedFunctions() {
        val d = "${'$'}"
        val dir = Files.createTempDirectory("atlas-custom-fn-use").toFile()
        try {
            File(dir, "frontend/customisation.ts").apply { parentFile.mkdirs() }.writeText(
                """export default {
                  additionalData: {
                    ${d}isDesktop: true,
                    ${d}fmt: function (x) { return x; },
                    ${d}ns: { f(a) { return a; } },
                    passed: function (x) { return x; },
                    fmt: function (x) { return x; },
                    supportEmail: "help@example.com",
                    retries: 3,
                    unused: function () { return 1; },
                  },
                };""")
            File(dir, "forms/f.form").apply { parentFile.mkdirs() }.writeText(
                """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[[
                    {"id":"a","type":"text","label":"A","value":"{{name}}","visible":"{{${d}isDesktop}}"},
                    {"id":"b","type":"text","label":"B","value":"{{other}}","defaultValue":"{{${d}fmt(name)}}"},
                    {"id":"c","type":"text","label":"C","value":"{{third}}","defaultValue":"{{${d}ns.f(name)}}"},
                    {"id":"e","type":"text","label":"E","value":"{{names.map(passed)}}"},
                    {"id":"g","type":"text","label":"G","value":"{{item.fmt}}","visible":"{{kind == 'unused'}}"},
                    {"id":"h","type":"text","label":"H","value":"{{supportEmail}}"}]]}""")
            val r = Atlas.extract(dir)
            val unused = (r["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedFns" }.map { it["node"] }
            // `item.fmt` is a member of `item`, not the top-level `fmt`, and the text `'unused'` names nothing
            assertEquals(listOf("customFunction:fmt", "customFunction:unused"), unused.sortedBy { it.toString() })
            val custom = r["customFunctions"] as Map<String, Any?>
            assertEquals(listOf("${d}isDesktop", "retries", "supportEmail"), custom["constants"])
            val top = custom["topLevel"] as List<*>
            assertTrue("${d}fmt" in top)
            assertFalse("${d}isDesktop" in top)
            val ids = ((r["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"] }
            assertFalse("a value gets no function node", "customFunction:supportEmail" in ids)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCustomFunctionsNodeCarriesItsCode() {
        val dir = Files.createTempDirectory("atlas-custom-fn-code").toFile()
        try {
            File(dir, "src/custom.js").apply { parentFile.mkdirs() }.writeText(
                "export default {\n  additionalData: {\n    acme: {\n      double: (n) => n * 2,\n    },\n  },\n};\n")
            File(dir, "forms/f.form").apply { parentFile.mkdirs() }.writeText(
                """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[[
                    {"id":"t","type":"text","label":"T","value":"{{acme.double(amount)}}"}]]}""")
            val r = Atlas.extract(dir)
            val node = ((r["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
                .single { it["id"] == "customFunction:acme.double" }
            val d = node["data"] as Map<String, Any?>
            assertEquals("double: (n) => n * 2", d["code"])
            assertEquals(4, d["line"])
            assertEquals("src/custom.js", node["file"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
