package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What a `{{…}}` binding starts with is a variable only when it names one.
 *
 * `{{true}}`, `{{false}}` and `{{null}}` are what a script button assigns and what a hidden field is set
 * to. Their first word was taken for a variable's name, so a real project had three variables called
 * `true`, `false` and `null`, each used by a dozen forms and read or written by nothing — and its custom
 * function namespace was a fourth, read by every form that calls into it. The names *past* the head were
 * already held to these rules; the head was not.
 */
class BindingHeadTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aLiteralACallAndAFunctionNamespaceAreNoVariables() {
        val dir = Files.createTempDirectory("atlas-binding-head").toFile()
        try {
            File(dir, "src/custom.js").apply { parentFile.mkdirs() }.writeText(
                "export default {\n  additionalData: {\n    acme: {\n      double: (n) => n * 2,\n    },\n  },\n};\n")
            File(dir, "forms/f.form").apply { parentFile.mkdirs() }.writeText(
                """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[[
                    {"id":"a","type":"scriptButton","label":"A","value":"{{done}}","extraSettings":{"script":"{{true}}"}},
                    {"id":"b","type":"text","label":"B","value":"{{note}}","defaultValue":"{{null}}","visible":"{{false}}"},
                    {"id":"c","type":"text","label":"C","value":"{{biggest}}","defaultValue":"{{Math.max(first, second)}}"},
                    {"id":"d","type":"text","label":"D","value":"{{rounded}}","defaultValue":"{{round(amount)}}"},
                    {"id":"e","type":"text","label":"E","value":"{{twice}}","defaultValue":"{{acme.double(amount)}}"}]]}""")
            val nodes = (Atlas.extract(dir)["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
            val variables = nodes.filter { it["type"] == "variable" }.map { it["key"] as String }.sorted()
            // The fields are the variables. `amount` and `second` are only read inside a call, which credits
            // a variable that exists and creates none; none of the words that merely look like names is one.
            assertEquals(listOf("biggest", "done", "note", "rounded", "twice"), variables)
        } finally {
            dir.deleteRecursively()
        }
    }
}
