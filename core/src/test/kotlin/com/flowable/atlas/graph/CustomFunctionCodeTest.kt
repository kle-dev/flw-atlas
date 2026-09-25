package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A custom function's page shows the function: its node carries the code, the file and the line. */
class CustomFunctionCodeTest {

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
