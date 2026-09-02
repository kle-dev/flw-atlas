package com.flowable.atlas.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Remote-Dev hand-over of a report: parts that rejoin, a hash that keys the cache, a stub that states both. */
class RemoteExplorerPageTest {

    @Test fun partsRejoinToThePage() {
        val html = "0123456789".repeat(157_293)                 // 3 × PART_CHARS + 66
        val parts = RemoteExplorerPage.split(html)
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.length <= RemoteExplorerPage.PART_CHARS })
        assertEquals(html, parts.joinToString(""))
    }

    @Test fun aSurrogatePairIsNeverSplit() {
        // the emoji's high half would land exactly on the first part boundary
        val html = "a".repeat(RemoteExplorerPage.PART_CHARS - 1) + "🧩" + "b".repeat(10)
        val parts = RemoteExplorerPage.split(html)
        assertEquals(RemoteExplorerPage.PART_CHARS - 1, parts[0].length)
        assertTrue(parts[1].startsWith("🧩"))
        assertEquals(html, parts.joinToString(""))
    }

    @Test fun aSmallPageIsOnePart() {
        assertEquals(listOf("<html></html>"), RemoteExplorerPage.split("<html></html>"))
        assertEquals(listOf(""), RemoteExplorerPage.split(""))
    }

    @Test fun theStubCarriesHashPartsAndSize() {
        val hash = "ab".repeat(32)
        val t = RemoteExplorerPage.prepare("x".repeat(RemoteExplorerPage.PART_CHARS + 1), hash, 3_512_345)
        assertEquals(2, t.parts.size)
        assertEquals("3.5 MB", t.sizeLabel)
        assertTrue(t.stub.contains("HASH='$hash'"))
        assertTrue(t.stub.contains("PARTS=2,"))
        assertTrue(t.stub.contains("SIZE='3.5 MB'"))
        assertFalse("a placeholder survived", t.stub.contains("__ATLAS_"))
        assertTrue("the stub calls the bridge the editor installs", t.stub.contains("window.__atlasFetch"))
        // The whole point: the stub must cross the IDE connection in a single 16 KB resource packet.
        assertTrue("stub is ${t.stub.length} chars", t.stub.length < 16 * 1024)
    }

    @Test fun sizeLabels() {
        assertEquals("1 KB", RemoteExplorerPage.sizeLabel(1))
        assertEquals("588 KB", RemoteExplorerPage.sizeLabel(587_187))
        assertEquals("1.0 MB", RemoteExplorerPage.sizeLabel(1_000_000))
        assertEquals("3.5 MB", RemoteExplorerPage.sizeLabel(3_512_345))
    }

    @Test fun theHashIsSha256Hex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", RemoteExplorerPage.sha256(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RemoteExplorerPage.sha256("abc".toByteArray()))
    }
}
