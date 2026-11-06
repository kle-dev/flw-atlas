package com.flowable.atlas

import java.io.File

/**
 * Locates the committed golden files **on disk** (not on the test classpath) and implements the
 * `ATLAS_UPDATE_GOLDEN=1` escape hatch.
 *
 * The deleted Python suite had exactly this: *"any intended change is made visible by regenerating the
 * goldens (`ATLAS_UPDATE_GOLDEN=1 pytest`) and reviewing the diff"*. The mechanism was lost in the
 * Kotlin port, which left hand-written throwaway tests as the only way to refresh a golden — so the
 * goldens quietly became a reason not to change the generated artifacts at all. Restoring it makes the
 * regression gate cheap to re-baseline *and* keeps the diff review that gives the gate its value.
 *
 * Usage in a test: call [assertMatches]. It asserts by default; with the env var set it overwrites the
 * source file and lets the test pass, so a full `ATLAS_UPDATE_GOLDEN=1 ./gradlew :core:test` rewrites
 * every golden in one go (`./gradlew :core:updateGoldens` does that for you).
 */
object GoldenFiles {

    /** True when the run should rewrite goldens instead of asserting against them. */
    val updating: Boolean = System.getenv("ATLAS_UPDATE_GOLDEN") == "1"

    /** The Gradle root, found by walking up from the test working directory. */
    val repoRoot: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        d ?: error("could not locate the Gradle root from ${System.getProperty("user.dir")}")
    }

    /** `core/src/test/resources/golden`. */
    val dir: File by lazy {
        File(repoRoot, "core/src/test/resources/golden").also {
            require(it.isDirectory) { "golden directory not found at $it" }
        }
    }

    fun file(name: String): File = File(dir, name)

    fun read(name: String): String = file(name).readText()

    /**
     * Assert [actual] equals the golden [name] — or, when [updating], write it and report what changed.
     *
     * Callers pass the exact bytes the artifact should have (the Markdown goldens include the trailing
     * newline the CLI's `--stdout` adds), so a rewrite is byte-for-byte what the next run will compare.
     */
    fun assertMatches(name: String, actual: String) = assertFileMatches(file(name), actual)

    /** [assertMatches] for a generated file that lives outside the golden directory. */
    fun assertFileMatches(f: File, actual: String) {
        val name = f.name
        val expected = if (f.isFile) f.readText() else null
        if (actual == expected) return
        if (updating) {
            f.writeText(actual)
            println("[golden] rewrote ${f.absolutePath} (${expected?.length ?: 0} → ${actual.length} chars)")
            return
        }
        // A ComparisonFailure (two Strings), so an IDE offers its side-by-side diff. Its own message
        // only shows ~20 characters around the change, which in a Gradle console is no place to look —
        // hence the line, the JSON path and a few lines of both sides up front.
        org.junit.Assert.assertEquals(
            "$name differs — review the change, then re-baseline with `./gradlew :core:updateGoldens`\n" +
                (expected?.let { firstDifference(it, actual) } ?: "(no golden yet)"),
            expected, actual,
        )
    }

    /**
     * Where [expected] and [actual] first part, as a reader wants it: the line number, the JSON path of
     * that line when the text is a laid-out JSON document, and three lines of context with the two
     * versions of the differing lines marked `-` (golden) and `+` (now).
     */
    internal fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        var i = 0
        while (i < e.size && i < a.size && e[i] == a[i]) i++
        val sb = StringBuilder("first difference at line ${i + 1}")
        jsonPathAt(if (i < a.size) a else e, minOf(i, maxOf(a.size, e.size) - 1))?.let { sb.append(", at ").append(it) }
        sb.append(":\n")
        for (k in maxOf(0, i - 3) until i) sb.append("  ").append(e[k]).append('\n')
        for (k in i until minOf(e.size, i + 4)) sb.append("- ").append(e[k]).append('\n')
        for (k in i until minOf(a.size, i + 4)) sb.append("+ ").append(a[k]).append('\n')
        return sb.toString()
    }

    private val KEY_LINE = Regex("""^"((?:[^"\\]|\\.)*)": ?(.*)$""")

    /**
     * The path of line [at] in a JSON document laid out one token per line (`MiniJson.reindent`), e.g.
     * `$.graph.nodes[12 id=form:orderForm].data`. An array element is named by its `id` or `key` when one
     * has been read by then, because `[12]` in a list of 119 nodes is no place to look. Null when the
     * text is not such a document.
     */
    internal fun jsonPathAt(lines: List<String>, at: Int): String? {
        if (lines.isEmpty() || lines[0].trim() !in setOf("{", "[")) return null
        class Frame(val array: Boolean, var segment: String) { var count = 0 }
        val stack = ArrayList<Frame>()
        stack.add(Frame(lines[0].trim() == "[", "$"))
        var current = "$"
        for (k in 1..minOf(at, lines.size - 1)) {
            val t = lines[k].trim().removeSuffix(",")
            if (t == "}" || t == "]") {
                // a closing bracket belongs to what it closes
                current = stack.joinToString("") { it.segment }
                if (stack.size > 1) stack.removeAt(stack.size - 1)
                continue
            }
            val parent = stack.last()
            val m = KEY_LINE.find(t)
            val value = m?.groupValues?.get(2) ?: t
            val segment = if (m != null) ".${m.groupValues[1]}" else "[${parent.count++}]"
            if (m != null && !parent.array && (m.groupValues[1] == "id" || m.groupValues[1] == "key") &&
                stack.size >= 2 && stack[stack.size - 2].array && value.startsWith("\"") && !parent.segment.contains(' ')
            ) {
                // name the element by its id once seen: `[12]` → `[12 id=form:orderForm]`
                parent.segment = parent.segment.removeSuffix("]") + " ${m.groupValues[1]}=${value.trim('"')}]"
            }
            current = stack.joinToString("") { it.segment } + segment
            if (value == "{" || value == "[") stack.add(Frame(value == "[", segment))
        }
        return current
    }
}
