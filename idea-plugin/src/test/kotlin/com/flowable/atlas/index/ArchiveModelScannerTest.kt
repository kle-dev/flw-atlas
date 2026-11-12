package com.flowable.atlas.index

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model index reads every model on project open, loose and inside every archive. It now skips what
 * :core skips — a model above [Atlas.MAX_MODEL_BYTES] — so a crafted archive cannot make the IDE allocate
 * gigabytes before anyone asked for anything, and the index and Generate agree about what a model is.
 */
class ArchiveModelScannerTest {

    @Test
    fun aModelUpToTheCoreLimitIsReadAndOneByteMoreIsNot() {
        assertTrue(ArchiveModelScanner.readable(0))
        assertTrue(ArchiveModelScanner.readable(Atlas.MAX_MODEL_BYTES))
        assertFalse(ArchiveModelScanner.readable(Atlas.MAX_MODEL_BYTES + 1))
    }
}
