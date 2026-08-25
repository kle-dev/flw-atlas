package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptLanguages
import com.flowable.atlas.script.ScriptValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The examples are shipped teaching material, so they are held to what Atlas itself would say about
 * them: every body runs through :core's [ScriptValidator] in its own context, and a single warning
 * fails the build. That is what keeps the catalog honest when a binding is renamed or a `flw`
 * namespace moves — a stale example would otherwise ship a squiggle the moment it is loaded.
 */
class ScriptExamplesTest {

    @Test
    fun everyExampleValidatesCleanInItsOwnContext() {
        for (example in ScriptExamples.ALL) {
            // formatRequired = true: an example is a complete script, so the empty-body and
            // missing-format findings a playground suppresses must not apply to it either.
            val problems = ScriptValidator.validate(
                example.body, example.format, formatRequired = true, context = example.context)
            assertTrue(
                "${example.title}: ${problems.joinToString { "${it.kind} ${it.message}" }}",
                problems.isEmpty())
        }
    }

    @Test
    fun everyContextAndEveryLanguageIsCovered() {
        val contexts = ScriptExamples.ALL.map { it.context }.toSet()
        for (context in ScriptContext.entries.filter { it != ScriptContext.UNKNOWN }) {
            assertTrue("no example for ${context.display}", context in contexts)
        }
        val languages = ScriptExamples.ALL.map { PlaygroundScriptLanguage.fromFormat(it.format) }.toSet()
        assertEquals(PlaygroundScriptLanguage.entries.toSet(), languages)
    }

    @Test
    fun everyExampleIsSelfExplaining() {
        val titles = HashSet<String>()
        for (example in ScriptExamples.ALL) {
            assertTrue("duplicate title '${example.title}'", titles.add(example.title))
            assertTrue("${example.title}: unknown scriptFormat '${example.format}'",
                example.format in ScriptLanguages.SCRIPT_LANGS)
            // A body opens with the comment that explains it — that comment IS the example's
            // documentation, which is why the popup rows stay one line.
            val opener = if (ScriptLanguages.family(example.format) == ScriptLanguages.Family.PYTHON) "#" else "//"
            assertTrue("${example.title}: does not open with an explaining comment",
                example.body.startsWith(opener))
            assertTrue("${example.title}: body is not trimmed", example.body == example.body.trim())
            assertTrue("${example.title}: label should name context and language",
                example.label.startsWith(example.title) && example.context.display in example.label)
        }
    }

    @Test
    fun thePlaygroundsOwnContextComesFirst() {
        val ordered = ScriptExamples.orderedFor(ScriptContext.ACTION_BOT)
        val bots = ScriptExamples.ALL.filter { it.context == ScriptContext.ACTION_BOT }
        assertEquals(bots, ordered.take(bots.size))
        assertEquals("ordering must not drop or duplicate an example",
            ScriptExamples.ALL.toSet(), ordered.toSet())
        // …and it is a stable regrouping: the rest keeps the catalog order.
        assertEquals(ScriptExamples.ALL.filter { it.context != ScriptContext.ACTION_BOT },
            ordered.drop(bots.size))
    }
}
