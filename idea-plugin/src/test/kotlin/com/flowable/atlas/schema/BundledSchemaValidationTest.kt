package com.flowable.atlas.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/**
 * Every model this repository ships validates against the schemas the plugin bundles.
 *
 * The point is not the fixtures — it is that turning schemas on turns **validation** on, at error level,
 * in every reader's editor. A model in the demo project or in a test corpus that does not validate is a
 * red squiggle on the docs site's own screenshots, and the kind of thing that is noticed by a user
 * before it is noticed here. It already found five: two files with no `targetNamespace`, two using an
 * undeclared `xsd:` prefix, and a `<caseTask>` — CMMN's element — inside a BPMN process.
 *
 * Validation runs with the JDK's own parser, so this costs no dependency. Both the format's schema and
 * Flowable's extension schema are handed over together, which is the IDE's situation: with only the
 * former, `flowable:` attributes are skipped as an unknown namespace and half the coverage is silent.
 */
class BundledSchemaValidationTest {

    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val schemas: File by lazy { File(repoRoot, "idea-plugin/src/main/resources/schemas") }

    /** The root namespace decides the schema, the way the IDE decides it. */
    private fun schemaFor(text: String): List<File>? = when {
        text.contains("http://www.omg.org/spec/BPMN/20100524/MODEL") ->
            listOf(File(schemas, "bpmn/BPMN20.xsd"), File(schemas, "bpmn/flowable-bpmn-extensions.xsd"))
        text.contains("http://www.omg.org/spec/CMMN/20151109/MODEL") -> listOf(File(schemas, "cmmn/CMMN11.xsd"))
        text.contains("https://www.omg.org/spec/DMN/20191111/MODEL/") -> listOf(File(schemas, "dmn/DMN13.xsd"))
        text.contains("http://www.omg.org/spec/DMN/20180521/MODEL/") -> listOf(File(schemas, "dmn/DMN12.xsd"))
        text.contains("http://www.omg.org/spec/DMN/20151101") -> listOf(File(schemas, "dmn/dmn.xsd"))
        else -> null
    }

    @Test
    fun everyShippedModelValidatesAgainstTheBundledSchemas() {
        val roots = listOf(File(repoRoot, "site/flowable-demo"), File(repoRoot, "core/src/test/resources"))
        val models = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("bpmn", "cmmn", "dmn", "xml") }
                .filter { !it.path.contains("/build/") }
                .toList()
        }
        assertTrue("no model files found — the walk is looking in the wrong place", models.isNotEmpty())

        val problems = ArrayList<String>()
        var checked = 0
        for (model in models) {
            val text = runCatching { model.readText() }.getOrNull() ?: continue
            val schemaFiles = schemaFor(text) ?: continue
            checked++
            problems += validate(model, schemaFiles).map { "${model.toRelativeString(repoRoot)}: $it" }
        }
        assertTrue("no model matched a bundled schema — the namespace check is wrong", checked > 0)
        assertEquals("models this repository ships must validate", emptyList<String>(), problems)
    }

    /**
     * The same validation against a checkout of real models, on demand:
     *
     * ```
     * ./gradlew :idea-plugin:validateModelCorpus -Patlas.corpus=/path/to/a/checkout
     * ```
     *
     * It cannot live in CI — a real corpus is customer work and must never be copied into this public
     * repository — so it is a command a developer runs before shipping a schema change, and again
     * whenever the schemas are refreshed from a newer engine. It reports rather than asserts: the
     * question it answers is *what proportion* of real models come out clean, and which causes account
     * for the rest.
     */
    @Test
    fun aRealCorpusValidatesWhenOneIsPointedAt() {
        val corpus = System.getProperty("atlas.corpus")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val byFormat = LinkedHashMap<String, IntArray>()          // format -> [clean, with findings]
        val causes = HashMap<String, Int>()
        corpus.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("bpmn", "cmmn", "dmn", "xml") }
            .filter { !it.path.contains("/target/") && !it.path.contains("/node_modules/") }
            .forEach { model ->
                val text = runCatching { model.readText() }.getOrNull() ?: return@forEach
                val schemaFiles = schemaFor(text) ?: return@forEach
                val format = schemaFiles.first().parentFile.name
                val found = validate(model, schemaFiles)
                byFormat.getOrPut(format) { IntArray(2) }[if (found.isEmpty()) 0 else 1]++
                found.forEach { causes.merge(it.substringAfter(": ").take(60), 1, Int::plus) }
            }
        val clean = byFormat.values.sumOf { it[0] }
        val dirty = byFormat.values.sumOf { it[1] }
        println("\n--- ${corpus.name} ---")
        byFormat.forEach { (format, n) -> println("%-6s %5d clean %5d with findings".format(format, n[0], n[1])) }
        println("total  %5d clean %5d with findings (%.2f %% clean)".format(clean, dirty, 100.0 * clean / (clean + dirty)))
        causes.entries.sortedByDescending { it.value }.take(10).forEach { println("  %4d  %s".format(it.value, it.key)) }
    }

    private fun validate(model: File, schemaFiles: List<File>): List<String> {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        // Relative <xsd:include>s resolve against each schema's own directory, which is why the three
        // formats are vendored into three directories: DC.xsd and DI.xsd exist once per format.
        factory.resourceResolver = DirectoryResolver(schemaFiles.map { it.parentFile }.distinct())
        val schema = factory.newSchema(schemaFiles.map { StreamSource(it) }.toTypedArray())
        val validator = schema.newValidator()
        val found = ArrayList<String>()
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) { found += "${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: SAXParseException) { found += "${e.lineNumber}: ${e.message}" }
        }
        runCatching { validator.validate(StreamSource(model)) }
            .onFailure { if (found.isEmpty()) found += it.message.orEmpty() }
        return found
    }

    /** Resolves a schema's own `<xsd:include>` / `<xsd:import>` targets out of the bundled directories. */
    private class DirectoryResolver(private val dirs: List<File>) : LSResourceResolver {
        override fun resolveResource(
            type: String?, namespaceURI: String?, publicId: String?, systemId: String?, baseURI: String?,
        ): LSInput? {
            val name = systemId?.substringAfterLast('/') ?: return null
            val file = dirs.map { File(it, name) }.firstOrNull { it.isFile } ?: return null
            return object : LSInput {
                override fun getCharacterStream() = null
                override fun setCharacterStream(cs: java.io.Reader?) = Unit
                override fun getByteStream() = file.inputStream()
                override fun setByteStream(bs: java.io.InputStream?) = Unit
                override fun getStringData() = null
                override fun setStringData(sd: String?) = Unit
                override fun getSystemId() = file.toURI().toString()
                override fun setSystemId(sid: String?) = Unit
                override fun getPublicId() = publicId
                override fun setPublicId(pid: String?) = Unit
                override fun getBaseURI() = baseURI
                override fun setBaseURI(uri: String?) = Unit
                override fun getEncoding() = "UTF-8"
                override fun setEncoding(enc: String?) = Unit
                override fun getCertifiedText() = false
                override fun setCertifiedText(ct: Boolean) = Unit
            }
        }
    }
}
