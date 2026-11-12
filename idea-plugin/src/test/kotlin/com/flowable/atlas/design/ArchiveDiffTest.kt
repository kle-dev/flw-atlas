package com.flowable.atlas.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a pull changed in one app's export, entry by entry — the modeller's changelog in the balloon. */
class ArchiveDiffTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test fun aFirstExportHasNoBaseline() {
        assertNull(archiveDiff(null, mapOf("a.bpmn" to 1L)))
    }

    @Test fun changedAddedAndRemovedAreToldApart() {
        val before = modelEntryChecksums(zip(
            "processes/order.bpmn" to "<definitions><process id=\"order\"/></definitions>",
            "forms/old.form" to "{}",
            "README.md" to "not a model",
        ))
        val after = modelEntryChecksums(zip(
            "processes/order.bpmn" to "<definitions><process id=\"order\" name=\"Order\"/></definitions>",
            "forms/new.form" to "{}",
            "README.md" to "still not a model, and changed",
        ))
        val d = archiveDiff(before, after)!!
        assertEquals(listOf("processes/order.bpmn"), d.changed)
        assertEquals(listOf("forms/new.form"), d.added)
        assertEquals(listOf("forms/old.form"), d.removed)
        assertEquals("1 changed, 1 added, 1 removed", d.summary())
        assertTrue("a README is not a model and does not count", before.keys.none { it.endsWith(".md") })
    }

    @Test fun anIdenticalExportIsUnchanged() {
        val bytes = zip("processes/order.bpmn" to "<definitions/>")
        val d = archiveDiff(modelEntryChecksums(bytes), modelEntryChecksums(bytes))!!
        assertTrue(d.isEmpty)
        assertEquals("unchanged", d.summary())
    }
}
