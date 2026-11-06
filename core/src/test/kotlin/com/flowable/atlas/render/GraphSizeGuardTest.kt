package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.NestedFormFixture
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * graph.json stores each fact once, and does not grow with the number of things *below* a fact.
 *
 * 0.30.0 shipped a graph.json several times its former size — every form field carried a copy of every
 * enclosing panel's conditions — and passed every gate: neither fixture has a gated panel, the golden
 * test normalised what it compared, and the CLI test skipped every node without a `dataIn` pointer. The
 * file is what an agent queries and what the explorer's island is cut from, and an IDE's embedded browser
 * stops at 20 MB, so its size is a property worth a test of its own, not a side effect of the goldens.
 *
 * Two checks, each aimed at a way the file has grown before:
 *  - **a copy stored under a second path** — a model body under `graph.nodes[].data` beside its bucket,
 *    a class record under `javaControllers` beside `javaByRole.controller`. Compared by path *shape*
 *    (indices dropped), because two unrelated nodes can hold equal data by coincidence and that is no copy;
 *  - **a fact copied onto every element below it** — measured as growth between a fixture and the same
 *    fixture twice as deep.
 */
class GraphSizeGuardTest {

    /**
     * Sub-trees serialized to at least this many characters are compared. Below it, equal values are
     * mostly coincidence (two `{"id":…,"type":"text"}` fields) and cost nothing.
     */
    private val minChars = 256

    /**
     * Copies kept on purpose, as pairs of path shapes, each with the reason it stays. Empty: add a pair
     * only with a sentence that would convince a reviewer the second copy is worth its bytes.
     */
    private val allowed: Map<Set<String>, String> = emptyMap()

    @Test
    fun noSubtreeOfTheFixtureIsStoredUnderTwoPaths() = assertNoCopies("miniproject", fixture("miniproject"))

    @Test
    fun noSubtreeOfTheSampleProjectIsStoredUnderTwoPaths() =
        assertNoCopies("site/flowable-demo", File(GoldenFiles.repoRoot, "site/flowable-demo"))

    /**
     * A panel's condition is stored once per panel, not once per field inside it. Doubling the depth adds
     * five panels — five more copies of the condition at most, wherever the file keeps them — while a
     * per-field copy would add hundreds. The size bound is the backstop for a per-element copy of anything
     * else: twice the depth is about twice the fields, and measured 2.04× the file.
     */
    @Test
    fun anEnclosingPanelsConditionIsStoredPerPanelNotPerField() {
        val shallow = renderNested(5)
        val deep = renderNested(10)
        val shallowCopies = occurrences(shallow, "caseStatus == 'DEMO-OPEN'")
        val deepCopies = occurrences(deep, "caseStatus == 'DEMO-OPEN'")
        assertTrue("5 more panels added ${deepCopies - shallowCopies} copies of their condition ($shallowCopies → $deepCopies)",
            deepCopies - shallowCopies <= 2 * 5)
        val ratio = deep.length.toDouble() / shallow.length
        assertTrue("twice the depth made graph.json ${"%.2f".format(ratio)}× as large (${shallow.length} → ${deep.length} chars)",
            ratio < 2.6)
    }

    // ---- the copy detector ----

    private fun assertNoCopies(name: String, dir: File) {
        val json = MiniJson.parse(GraphJsonRenderer.render(Atlas.extract(dir), generatedAt = Instant.EPOCH))
        val copies = copiesByShape(json).filterKeys { it !in allowed }
        if (copies.isEmpty()) return
        fail(
            "graph.json of $name stores the same sub-tree under more than one path — keep one and point at it " +
                "(see GraphJsonRenderer), or add the pair to `allowed` with the reason it stays:\n" +
                copies.entries.sortedByDescending { it.value }.take(10).joinToString("\n") { (shapes, bytes) ->
                    "  $bytes chars duplicated: ${shapes.joinToString("  ==  ")}"
                },
        )
    }

    /**
     * Every sub-tree of at least [minChars] serialized characters that occurs under two or more distinct
     * path shapes, grouped by that set of shapes, with the characters its extra copies cost.
     */
    private fun copiesByShape(json: Any?): Map<Set<String>, Long> {
        val shapesOf = HashMap<String, MutableSet<String>>()
        val countOf = HashMap<String, Int>()
        // Serialized bottom-up, so each node is stringified once rather than once per ancestor.
        fun visit(v: Any?, shape: String): String {
            val s = when (v) {
                is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, c) ->
                    MiniJson.stringify(k.toString()) + ":" + visit(c, "$shape.$k")
                }
                is List<*> -> v.joinToString(",", "[", "]") { visit(it, "$shape[]") }
                else -> return MiniJson.stringify(v)
            }
            if (s.length >= minChars) {
                shapesOf.getOrPut(s) { LinkedHashSet() }.add(shape)
                countOf.merge(s, 1, Int::plus)
            }
            return s
        }
        visit(json, "$")
        val out = HashMap<Set<String>, Long>()
        for ((s, shapes) in shapesOf) {
            if (shapes.size < 2) continue
            out.merge(shapes.toSortedSet(), s.length.toLong() * (countOf.getValue(s) - 1), Long::plus)
        }
        return out
    }

    // ---- helpers ----

    private fun fixture(name: String): File = File(javaClass.classLoader.getResource(name)!!.toURI())

    private fun renderNested(depth: Int): String {
        val tmp = Files.createTempDirectory("atlas-size-guard").toFile()
        try {
            File(tmp, "forms").mkdirs()
            File(tmp, "forms/deep.form").writeText(NestedFormFixture.json(depth))
            return GraphJsonRenderer.render(Atlas.extract(tmp), generatedAt = Instant.EPOCH)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()
}
