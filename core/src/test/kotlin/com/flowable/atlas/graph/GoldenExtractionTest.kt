package com.flowable.atlas.graph

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.render.GraphJsonRenderer
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * `golden/miniproject.graph.json` is the `graph.json` artifact of the `miniproject` fixture — the bytes
 * the CLI writes, laid out one token per line so a change reads as a line diff — compared as text.
 *
 * It used to be the raw extract result with every object's keys and every list sorted, compared as a
 * `Map`. That was the deleted Python suite's normaliser, kept for parity, and it cost three things: no
 * ordering regression could fail it (a form's field order, a case's stage order, `containerGates`
 * nearest first, the findings order a test elsewhere pins), the golden matched no file Atlas ever
 * writes (it carried every model body twice, where the artifact points into its bucket), and a failure
 * printed two one-line ~200 KB `Map.toString()`s. Now order is checked, the golden *is* the artifact, and
 * [GoldenFiles.assertMatches] reports the first differing line with its JSON path.
 *
 * `_generated` is left out: it holds the time and the Atlas version, so every release would churn the
 * golden without a change to its content. If a list ever turns out to be unordered, fix its order where
 * it is built — a test that sorts it hides the next ordering bug as well.
 */
class GoldenExtractionTest {

    @Test
    fun graphJsonMatchesGolden() {
        GoldenFiles.assertMatches("miniproject.graph.json", goldenText(Atlas.extract(fixtureDir())))
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    companion object {
        /**
         * The golden's form of a result: the graph.json projection without `_generated`, serialized the
         * way the CLI serializes it (compact, so a whole number stays `64` rather than the `64.0` the
         * indented writer prints for a parsed Double) and then re-laid out token by token, which changes
         * whitespace and nothing else. `CliTest` builds the same text from the file the CLI wrote.
         */
        fun goldenText(result: Map<String, Any?>): String {
            val projected = LinkedHashMap(GraphJsonRenderer.project(result, Instant.EPOCH))
            projected.remove("_generated")
            return MiniJson.reindent(MiniJson.stringify(projected), 2)!! + "\n"
        }
    }
}
