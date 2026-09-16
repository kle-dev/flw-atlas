package com.flowable.atlas.compare

import com.flowable.atlas.compare.ModelArchiveMatch.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the same model under three different names. A form generated into the project folder is
 * `DEMO-F001.json`; the Design export inside the app zip calls it `form-models/DEMO-F001.json`, the
 * deployment `.bar` calls it `form-DEMO-F001.form`, and a generator may have named the file after
 * nothing at all while putting the key inside it. DEMO-* keys — this repository is public.
 */
class ModelArchiveMatchTest {

    private fun rank(fileName: String, key: String?, vararg candidates: String) =
        ModelArchiveMatch.rank(fileName, key, candidates.toList()) { it }

    @Test fun bothSerializationsMatchAndTheSameShapeComesFirst() {
        val ranked = rank(
            "DEMO-F001.json", "DEMO-F001",
            "DEMO-P031.bpmn", "form-DEMO-F001.form", "DEMO-F001.json", "DEMO-F002.json",
        )
        assertEquals(listOf("DEMO-F001.json", "form-DEMO-F001.form"), ranked.map { it.value })
        assertEquals(listOf(Rank.SAME_NAME, Rank.SAME_STEM), ranked.map { it.rank })
        assertEquals(listOf(true, false), ranked.map { it.sameExtension })
    }

    @Test fun aGeneratorsOwnFileNameIsAnsweredByTheKeyInsideIt() {
        // Nothing in "generated-form.json" points at the model; its `"key"` does.
        val ranked = rank("generated-form.json", "DEMO-F001", "form-DEMO-F001.form", "DEMO-F001.json")
        assertEquals(listOf("DEMO-F001.json", "form-DEMO-F001.form"), ranked.map { it.value })
        assertTrue(ranked.all { it.rank == Rank.SAME_KEY })
    }

    @Test fun aCompoundXmlExtensionIsStrippedWhole() {
        // .bpmn20.xml must not be read as the .xml of "DEMO-P031.bpmn20".
        val ranked = rank("DEMO-P031.bpmn20.xml", "DEMO-P031", "DEMO-P031.bpmn")
        assertEquals(listOf("DEMO-P031.bpmn"), ranked.map { it.value })
        assertEquals(Rank.SAME_STEM, ranked.single().rank)
    }

    @Test fun anUnrelatedEntryIsNotOffered() {
        assertEquals(emptyList<String>(), rank("DEMO-F001.json", "DEMO-F001", "DEMO-F002.json", "package.json").map { it.value })
    }

    @Test fun aKindPrefixIsOnlyStrippedAtASegmentBoundary() {
        // "form.json" is not "form-DEMO-F001.form" — a prefix match has to end on the separator.
        assertEquals(emptyList<String>(), rank("form.json", null, "form-DEMO-F001.form").map { it.value })
    }

    @Test fun equallyRankedCandidatesKeepTheCallersOrder() {
        // The sort is stable, which is what lets the caller order by archive and then by entry path.
        val ranked = rank("DEMO-F001.json", null, "a-DEMO-F001.json", "b-DEMO-F001.json")
        assertEquals(listOf("a-DEMO-F001.json", "b-DEMO-F001.json"), ranked.map { it.value })
    }

    @Test fun theKeyIsReadFromEitherJsonShape() {
        assertEquals(
            "DEMO-F001",
            ModelArchiveMatch.keyOf("DEMO-F001.json", """{"key":"DEMO-F001","name":"Demo"}""".toByteArray()),
        )
        assertEquals(
            "a deployment .form only carries it under metadata",
            "DEMO-F001",
            ModelArchiveMatch.keyOf("form-DEMO-F001.form", """{"rows":[],"metadata":{"key":"DEMO-F001"}}""".toByteArray()),
        )
        assertEquals(
            "DEMO-P031",
            ModelArchiveMatch.keyOf(
                "DEMO-P031.bpmn",
                """<definitions><process id="DEMO-P031" name="Demo"/></definitions>""".toByteArray(),
            ),
        )
    }

    @Test fun aFileThatDeclaresNoKeyIsNotAnError() {
        assertNull(ModelArchiveMatch.keyOf("half-written.json", """{"rows":[""".toByteArray()))
        assertNull(ModelArchiveMatch.keyOf("notes.json", """{"name":"no key here"}""".toByteArray()))
    }
}
