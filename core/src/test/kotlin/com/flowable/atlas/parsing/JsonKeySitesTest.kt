package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JSON key-site catalog the IDE navigates by must say exactly what the parsers record as a
 * reference — a site the parsers do not record would be a Ctrl+click into nothing, and the CLI's
 * graph and the IDE would disagree on what a reference is.
 */
class JsonKeySitesTest {

    private fun p(vararg segments: String) = segments.toList()

    @Test
    fun aComponentSettingMatchesAtAnyDepthAndAsARefObject() {
        val site = JsonKeySites.match(p("components", "[]", "components", "[]", "extraSettings", "formRef"), ModelType.FORM)
        assertEquals("extraSettings.formRef", site?.path)
        // Design's newer `{id, key}` reference: the `key` inside is the reference
        assertEquals(site, JsonKeySites.match(p("components", "[]", "extraSettings", "formRef", "key"), ModelType.PAGE))
        // …but an `id` inside is not
        assertNull(JsonKeySites.match(p("components", "[]", "extraSettings", "formRef", "id"), ModelType.FORM))
        // and a form component's setting inside a data object is nothing
        assertNull(JsonKeySites.match(p("components", "[]", "extraSettings", "formRef"), ModelType.DATA_OBJECT))
    }

    @Test
    fun aRootOnlySiteIsNotMatchedDeeperAndAModelsOwnKeyIsNoReference() {
        assertEquals("referencedServiceDefinitionModelKey", JsonKeySites.match(p("referencedServiceDefinitionModelKey"), ModelType.DATA_OBJECT)?.path)
        assertNull(JsonKeySites.match(p("nested", "referencedServiceDefinitionModelKey"), ModelType.DATA_OBJECT))
        assertEquals("fieldMappings.[].dataObjectModelKey", JsonKeySites.match(p("fieldMappings", "[]", "dataObjectModelKey"), ModelType.DATA_OBJECT)?.path)
        assertNull(JsonKeySites.match(p("key"), ModelType.FORM))
        assertNull(JsonKeySites.match(p("metadata", "key"), ModelType.FORM))
        assertNull(JsonKeySites.match(p("fields", "[]", "key"), ModelType.FORM))
    }

    @Test
    fun aWildcardLastSegmentAndASiblingCondition() {
        val forms = JsonKeySites.match(p("forms", "edit"), ModelType.DOCUMENT)
        assertEquals("forms.*", forms?.path)
        assertEquals(forms, JsonKeySites.match(p("forms", "view", "key"), ModelType.DOCUMENT))
        // an action's signalName is a process or a case key depending on the bot — two candidates, the caller decides
        val signal = JsonKeySites.matches(p("signalName"), ModelType.ACTION)
        assertEquals(listOf("botKey" to "bpmn-start-process-instance-bot", "botKey" to "cmmn-start-case-instance-bot"), signal.map { it.sibling })
        assertNull(JsonKeySites.match(p("signalName"), ModelType.ACTION))
    }

    /**
     * Every string the catalog calls a reference, in every fixture model, is a reference the model's
     * parser recorded. Walks each `miniproject` JSON model the way the IDE walks its PSI.
     */
    @Test
    fun everyCatalogSiteInTheFixturesIsAReferenceTheParsersRecord() {
        val root = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        var checked = 0
        val misses = ArrayList<String>()
        for (file in root.walkTopDown().filter { it.isFile }) {
            val type = ModelType.byExtension(file.name) ?: continue
            if (ModelType.isXmlModel(file.name)) continue
            val parser = ModelParsers.PARSERS[type.parserKey] ?: continue
            val doc = try { MiniJson.parse(file.readText()) } catch (e: Exception) { continue } as? Map<*, *> ?: continue
            val expected = ArrayList<Pair<String, String>>()
            walk(doc, ArrayList(), type, expected)
            if (expected.isEmpty()) continue
            val ctx = Ctx()
            parser(file.readBytes(), ctx, file.name)
            val recorded = (ctx.refs + ctx.dynamicRefs).map { it["value"].toString() }.toSet()
            for ((path, value) in expected) {
                checked++
                if (value !in recorded) misses.add("${file.name}: $path = \"$value\"")
            }
        }
        assertTrue("sites the parsers do not record:\n" + misses.joinToString("\n"), misses.isEmpty())
        assertTrue("the fixtures should exercise the catalog", checked >= 8)
    }

    private fun walk(node: Any?, path: MutableList<String>, host: ModelType, out: MutableList<Pair<String, String>>) {
        when (node) {
            is Map<*, *> -> for ((k, v) in node) {
                path.add(k.toString()); walk(v, path, host, out); path.removeAt(path.size - 1)
            }
            is List<*> -> for (v in node) { path.add("[]"); walk(v, path, host, out); path.removeAt(path.size - 1) }
            is String -> {
                if (node.isBlank() || node.contains("{{") || node.contains("\${")) return
                // a sibling-conditioned site cannot be judged without the holding object; the plain ones can
                if (JsonKeySites.match(path, host) != null) out.add(path.joinToString(".") to node)
            }
        }
    }
}
