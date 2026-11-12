package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelBytesTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun aLooseFileWithAnExclamationMarkInItsPathIsReadAsThatFile() {
        tmp.newFolder("Q&A!")
        tmp.root.resolve("Q&A!/x.bpmn").writeText("<definitions/>")
        val (bytes, name) = ModelBytes.resolve(tmp.root, "Q&A!/x.bpmn")!!
        assertEquals("<definitions/>", String(bytes))
        assertEquals("x.bpmn", name)
    }
}
