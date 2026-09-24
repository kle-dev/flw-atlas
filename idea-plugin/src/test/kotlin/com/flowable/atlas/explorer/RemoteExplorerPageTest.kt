package com.flowable.atlas.explorer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/** The Remote-Dev hand-over of a report: parts that rejoin and unpack, a hash that keys the cache, a stub that states both. */
class RemoteExplorerPageTest {

    /** What the stub does with the parts: join, Base64-decode, gunzip. */
    private fun unpack(parts: List<String>): ByteArray =
        GZIPInputStream(Base64.getDecoder().decode(parts.joinToString("")).inputStream()).use { it.readBytes() }

    @Test fun partsRejoinAndUnpackToThePage() {
        // incompressible bytes, so the page needs several parts; an emoji and umlauts so UTF-8 survives too
        val page = ("<html>🧩 Grüße " + "x").toByteArray() + Random(7).nextBytes(700_000) + "</html>".toByteArray()
        val t = RemoteExplorerPage.prepare(page)
        assertTrue("expected several parts, got ${t.parts.size}", t.parts.size >= 3)
        assertTrue(t.parts.all { it.length <= RemoteExplorerPage.PART_CHARS })
        assertEquals(t.wireChars, t.parts.sumOf { it.length })
        assertArrayEquals(page, unpack(t.parts))
    }

    @Test fun aReportTravelsCompressed() {
        // a report is mostly its JSON island: what crosses the connection is a fraction of the page
        val island = (1..40_000).joinToString(",", "[", "]") { """{"id":"process:DEMO-P$it","type":"process","label":"Demo $it"}""" }
        val page = "<html><script id=\"atlas-data\">$island</script></html>".toByteArray()
        val t = RemoteExplorerPage.prepare(page)
        assertTrue("wire ${t.wireChars} vs page ${page.size}", t.wireChars * 4 < page.size)
        assertArrayEquals(page, unpack(t.parts))
    }

    @Test fun aSmallPageIsOnePart() {
        val t = RemoteExplorerPage.prepare("<html></html>".toByteArray())
        assertEquals(1, t.parts.size)
        assertEquals("<html></html>", unpack(t.parts).toString(Charsets.UTF_8))
    }

    @Test fun theStubCarriesHashPartsAndSizes() {
        val hash = "ab".repeat(32)
        val page = Random(3).nextBytes(RemoteExplorerPage.PART_CHARS)    // Base64 of it is > 1 part
        val t = RemoteExplorerPage.prepare(page, hash)
        assertEquals(2, t.parts.size)
        assertEquals("263 KB", t.sizeLabel)
        assertTrue(t.stub.contains("HASH='$hash'"))
        assertTrue(t.stub.contains("PARTS=2,"))
        assertTrue(t.stub.contains("SIZE='263 KB'"))
        assertTrue(t.stub.contains("WIRE=${t.wireChars};"))
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
